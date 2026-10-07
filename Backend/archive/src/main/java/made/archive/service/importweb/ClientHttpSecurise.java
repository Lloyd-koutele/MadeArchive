package made.archive.service.importweb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import made.archive.exception.BusinessException;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Client HTTP de l'import par lien, protégé contre le SSRF.
 *
 *  - La résolution DNS est faite UNE SEULE FOIS, par le client lui-même, au moment de se connecter, et c'est ce résultat
 *    qui est contrôlé ET utilisé pour la connexion : un nom qui répond une adresse publique à la validation puis une
 *    adresse interne à la connexion (« DNS rebinding ») n'a aucune fenêtre pour agir.
 *  - Toute adresse interdite parmi celles d'un nom (AdressesInterdites) fait refuser le nom entier.
 *  - Redirections suivies à la main (3 au plus) : chaque saut repasse par la même résolution contrôlée.
 *  - Schémas http/https seulement, aucun proxy, délais bornés (connexion, lecture, durée totale).
 *  - Le corps est lu en flux et la lecture S'ARRÊTE dès que la taille maximale est dépassée : un serveur qui diffuse sans
 *    fin ne peut pas saturer la mémoire.
 */
@Slf4j
@Component
public class ClientHttpSecurise
{
    public record Reponse(int statut, Map<String, List<String>> entetes, byte[] corps, URI uriFinale)
    {
        public Optional<String> premierEntete(String nom)
        {
            return entetes.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(nom) && !e.getValue().isEmpty())
                .map(e -> e.getValue().get(0)).findFirst();
        }
    }

    /** Refus de sécurité levé depuis la résolution DNS du client — distingué d'une panne réseau. */
    static class AdresseInterditeException extends UnknownHostException
    {
        AdresseInterditeException(String message)
        {
            super(message);
        }
    }

    private static final int SAUTS_MAX = 3;

    private final Predicate<InetAddress> interdite;
    private final OkHttpClient client;

    public ClientHttpSecurise()
    {
        this(AdressesInterdites::estInterdite);
    }

    /** Visible pour les tests : permet d'autoriser la boucle locale pour exercer le reste de la logique. */
    ClientHttpSecurise(Predicate<InetAddress> interdite)
    {
        this.interdite = interdite;
        Dns dnsControle = hote ->
        {
            List<InetAddress> adresses = Dns.SYSTEM.lookup(hote);
            for (InetAddress a : adresses)
            {
                if (interdite.test(a))
                {
                    throw new AdresseInterditeException("Cette adresse pointe vers un réseau interne/privé — import refusé : " + hote);
                }
            }
            return adresses;
        };
        this.client = new OkHttpClient.Builder()
            .dns(dnsControle)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(15))
            .callTimeout(Duration.ofSeconds(45))
            .build();
    }

    /**
     * @param tailleMax taille maximale du corps, en octets
     * @throws LienRefuseException adresse interne/privée, schéma interdit
     * @throws BusinessException   autre échec (redirections, code HTTP, taille, réseau)
     */
    public Reponse telecharger(URI depart, long tailleMax)
    {
        URI courant = depart;
        for (int saut = 0; ; saut++)
        {
            verifierSchemaEtHote(courant);
            Request requete = new Request.Builder().url(courant.toString())
                .header("User-Agent", "MadeArchive-Import/1.0").get().build();

            try (Response reponse = client.newCall(requete).execute())
            {
                int code = reponse.code();
                String location = reponse.header("Location");
                if (code >= 300 && code < 400 && location != null)
                {
                    if (saut >= SAUTS_MAX)
                    {
                        throw new BusinessException("Trop de redirections à cette adresse");
                    }
                    courant = courant.resolve(location);
                    continue;
                }
                if (code < 200 || code >= 300)
                {
                    throw new BusinessException("Le serveur distant a répondu avec le code " + code);
                }
                ResponseBody corps = reponse.body();
                if (corps == null)
                {
                    return new Reponse(code, reponse.headers().toMultimap(), new byte[0], courant);
                }
                if (corps.contentLength() > tailleMax)
                {
                    throw new BusinessException("Contenu trop volumineux à cette adresse");
                }
                return new Reponse(code, reponse.headers().toMultimap(), lireBorne(corps.source(), tailleMax), courant);
            }
            catch (BusinessException e)
            {
                throw e;
            }
            catch (IOException e)
            {
                if (causeInterdite(e))
                {
                    throw new LienRefuseException("Cette adresse pointe vers un réseau interne/privé — import refusé : " + courant.getHost());
                }
                throw new BusinessException("Impossible de joindre cette adresse : " + e.getMessage());
            }
        }
    }

    /** Lit au plus tailleMax octets ; au premier octet de trop, arrête et refuse — sans jamais tout charger. */
    static byte[] lireBorne(BufferedSource source, long tailleMax) throws IOException
    {
        Buffer tampon = new Buffer();
        while (tampon.size() <= tailleMax)
        {
            if (source.read(tampon, 8192) == -1) break;
        }
        if (tampon.size() > tailleMax)
        {
            throw new BusinessException("Contenu trop volumineux à cette adresse");
        }
        return tampon.readByteArray();
    }

    /**
     * Schéma et hôte, vérifiés AVANT la requête. Indispensable pour une adresse IP littérale : le client ne consulte pas
     * le DNS dans ce cas, donc le contrôle de résolution ci-dessus ne s'y applique pas.
     */
    private void verifierSchemaEtHote(URI uri)
    {
        String schema = uri.getScheme();
        if (schema == null || !(schema.equalsIgnoreCase("http") || schema.equalsIgnoreCase("https")))
        {
            throw new LienRefuseException("Seuls les liens http:// ou https:// sont pris en charge");
        }
        String hote = uri.getHost();
        if (hote == null || hote.isBlank())
        {
            throw new BusinessException("Lien invalide — hôte manquant");
        }
        InetAddress[] adresses;
        try
        {
            adresses = InetAddress.getAllByName(hote);
        }
        catch (UnknownHostException e)
        {
            throw new BusinessException("Impossible de résoudre l'adresse : " + hote);
        }
        for (InetAddress a : adresses)
        {
            if (interdite.test(a))
            {
                throw new LienRefuseException("Cette adresse pointe vers un réseau interne/privé — import refusé : " + hote);
            }
        }
    }

    private static boolean causeInterdite(Throwable e)
    {
        List<Throwable> vus = new ArrayList<>();
        for (Throwable t = e; t != null && !vus.contains(t); t = t.getCause())
        {
            if (t instanceof AdresseInterditeException) return true;
            vus.add(t);
        }
        return false;
    }

    /** Pour les messages : l'hôte seul, jamais l'URL complète (elle peut contenir un jeton). */
    public static String hoteDe(String url)
    {
        try
        {
            String h = URI.create(url.trim()).getHost();
            return h != null ? h.toLowerCase(Locale.ROOT) : "(inconnu)";
        }
        catch (Exception e)
        {
            return "(invalide)";
        }
    }
}
