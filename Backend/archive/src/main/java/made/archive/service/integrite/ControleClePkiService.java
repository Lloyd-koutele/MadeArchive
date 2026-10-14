package made.archive.service.integrite;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.PkiKeyStatus;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.UserRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.PkiService;

/**
 * Contrôle que la clé de signature d'un éditeur est toujours celle qui a été créée pour lui.
 *
 * Deux copies indépendantes de sa clé publique existent : dans le certificat du jeton SoftHSM et en base
 * (users.pki_public_key, posée une fois à la création). La clé privée ne sort jamais du jeton et ne peut pas être
 * comparée ; on la contrôle par ce qu'elle produit : une signature qui doit se vérifier avec la clé publique de la base.
 *
 *   - à chaque archivage : le certificat de l'éditeur est relu dans le jeton (sans mémoire intermédiaire) et comparé
 *     à la clé de la base, puis la signature est vérifiée aussitôt après avoir été produite ; en cas d'écart
 *     l'archivage est refusé et les administrateurs sont alertés (une fois par éditeur et par 6 h au plus) ;
 *   - au démarrage de l'application : toutes les clés actives sont contrôlées de la même façon, avec une signature
 *     d'essai, y compris celles d'éditeurs qui n'archivent pas — un jeton remplacé ou restauré lors d'un
 *     redéploiement est ainsi vu tout de suite.
 *
 * Le contrôle ne vise qu'UNE clé à la fois : la création de la clé d'un autre éditeur ne peut donc pas le perturber.
 * Limite assumée : un attaquant qui modifierait le jeton ET la base de façon cohérente n'est pas détecté ici.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControleClePkiService
{
    private static final Duration INTERVALLE_ALERTE = Duration.ofHours(6);
    private static final SecureRandom ALEA = new SecureRandom();

    private final HsmKeyStoreService hsm;
    private final PkiService pkiService;
    private final UserRepository userRepository;
    private final AlerteIntegriteService alertes;

    private final Map<UUID, Instant> dernieresAlertes = new ConcurrentHashMap<>();

    /**
     * Signe l'empreinte avec la clé de l'éditeur, puis vérifie la signature avec la clé publique enregistrée.
     *
     * @throws BusinessException si la clé du jeton ne correspond plus à la référence (rien n'est alors archivé)
     */
    public String signerEtControler(User editeur, String hashHex)
    {
        String reference = editeur.getPkiPublicKey();
        if (reference == null || reference.isBlank())
        {
            refuser(editeur, "aucune clé publique de référence n'est enregistrée pour cet éditeur");
        }
        String ecart = comparerAuJeton(editeur.getPkiKeyAlias(), reference);
        if (ecart != null)
        {
            refuser(editeur, ecart);
        }
        String signature = hsm.sign(editeur.getPkiKeyAlias(), hashHex);
        if (!pkiService.verifySignature(hashHex, signature, reference))
        {
            refuser(editeur, "la signature produite par le jeton ne correspond pas à la clé publique enregistrée "
                + "(clé remplacée dans le jeton ou référence altérée)");
        }
        return signature;
    }

    private void refuser(User editeur, String raison)
    {
        String description = "clé de signature de l'éditeur " + editeur.getEmail() + " (alias " + editeur.getPkiKeyAlias()
            + ") : " + raison;
        log.error("[Cles-PKI] {}", description);
        Instant maintenant = Instant.now();
        boolean alerter = dernieresAlertes.merge(editeur.getId(), maintenant,
            (ancienne, nouvelle) -> Duration.between(ancienne, nouvelle).compareTo(INTERVALLE_ALERTE) >= 0 ? nouvelle : ancienne)
            .equals(maintenant);
        if (alerter)
        {
            alertes.clePkiAnormale(description);
        }
        throw new BusinessException("La clé de signature de cet éditeur ne correspond plus à la clé enregistrée : "
            + "archivage refusé. Les administrateurs ont été alertés.");
    }

    /**
     * Contrôle toutes les clés actives. Une anomalie n'est retenue qu'après une deuxième lecture, pour ne pas
     * signaler une création de clé en cours.
     *
     * @return une ligne par éditeur en anomalie ; vide si tout est conforme
     */
    public List<String> balayer()
    {
        List<String> anomalies = new ArrayList<>();
        for (User editeur : userRepository.findByPkiKeyStatus(PkiKeyStatus.ACTIVE))
        {
            String probleme = controler(editeur);
            if (probleme != null)
            {
                probleme = controler(editeur);
            }
            if (probleme != null)
            {
                anomalies.add(editeur.getEmail() + " (alias " + editeur.getPkiKeyAlias() + ") : " + probleme);
            }
        }
        return anomalies;
    }

    /** @return null si la clé est conforme, sinon la raison de l'écart */
    private String controler(User editeur)
    {
        try
        {
            String alias = editeur.getPkiKeyAlias();
            String reference = editeur.getPkiPublicKey();
            if (alias == null || alias.isBlank() || reference == null || reference.isBlank())
            {
                return "alias ou clé publique de référence absents en base";
            }
            String ecart = comparerAuJeton(alias, reference);
            if (ecart != null)
            {
                return ecart;
            }
            byte[] essai = new byte[32];
            ALEA.nextBytes(essai);
            String empreinteEssai = HexFormat.of().formatHex(essai);
            String signature = hsm.sign(alias, empreinteEssai);
            if (!pkiService.verifySignature(empreinteEssai, signature, reference))
            {
                return "la clé privée du jeton ne correspond plus à la clé publique enregistrée";
            }
            return null;
        }
        catch (Exception e)
        {
            return "contrôle impossible : " + e.getMessage();
        }
    }

    /** Certificat du jeton relu à neuf et comparé à la clé enregistrée — null si identiques, sinon la raison de l'écart. */
    private String comparerAuJeton(String alias, String reference)
    {
        PublicKey dansLeJeton = hsm.clePubliqueRelue(alias).orElse(null);
        if (dansLeJeton == null)
        {
            return "clé absente du jeton";
        }
        if (!normaliser(reference).equals(Base64.getEncoder().encodeToString(dansLeJeton.getEncoded())))
        {
            return "la clé publique du jeton diffère de celle enregistrée";
        }
        return null;
    }

    private static String normaliser(String pem)
    {
        return pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
    }
}
