package made.archive.service.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.HsmProperties;
import made.archive.entite.AuditChainSeal;
import made.archive.security.HsmKeyStoreService;
import made.archive.service.document.HashService;
import made.archive.service.integrite.PreuveIntegriteService;

/**
 * Registre des scellements du journal d'audit, tenu HORS de la base de données : un fichier à côté du KeyStore HSM
 * (même volume que les ancres de confiance de la TSA — voir TsaAncreService), en ajout seul, une ligne par
 * scellement créé.
 *
 * Pourquoi : la chaîne se vérifie contre les scellements stockés en base — mais un attaquant qui désactive les
 * déclencheurs de PostgreSQL peut SUPPRIMER des scellements, et la vérification n'a alors plus rien à comparer.
 * Le registre garde une trace que cet attaquant n'atteint pas avec la seule base : si un scellement qui figure au
 * registre n'existe plus en base (ou a changé), c'est détecté, scellement par scellement.
 *
 * Chaque ligne est signée par la clé système (HSM) : on ne peut pas en fabriquer une sans cette clé. Limite assumée :
 * qui contrôle aussi ce fichier (accès au serveur) peut en retirer des lignes — d'où l'intérêt d'en exporter
 * périodiquement une copie hors du serveur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistreScellementsService
{
    static final String NOM_FICHIER = "audit-scellements.registre";

    /** Une ligne du registre : le scellement tel qu'il était à sa création. */
    public record Ligne(long id, long creeLeEpoch, String chainHash, String empreinteSignature) {}

    /**
     * @param disponible       false si aucun emplacement n'est configuré (pas de KeyStore HSM) : rien à comparer
     * @param fichierPresent   false si le fichier n'existe pas
     * @param lignes           lignes dont la signature est valide
     * @param lignesInvalides  nombre de lignes illisibles ou à signature invalide (registre altéré)
     */
    public record Lecture(boolean disponible, boolean fichierPresent, List<Ligne> lignes, int lignesInvalides) {}

    private final HsmProperties hsmProperties;
    private final HsmKeyStoreService hsm;
    private final HashService hashService;

    private Optional<Path> fichier()
    {
        String chemin = hsmProperties.getKeystorePath();
        if (chemin == null || chemin.isBlank())
        {
            return Optional.empty();
        }
        return Optional.of(Path.of(chemin).resolveSibling(NOM_FICHIER));
    }

    private String contenu(AuditChainSeal s)
    {
        return s.getId() + ";" + s.getCreatedAt().getEpochSecond() + ";" + s.getDernierChainHash() + ";"
            + hashService.calculateFromBytes(String.valueOf(s.getSignature()).getBytes(StandardCharsets.UTF_8));
    }

    /** Inscrit le scellement (signé) au registre. Best-effort : un échec est journalisé, jamais bloquant. */
    public synchronized void ajouter(AuditChainSeal seal)
    {
        Optional<Path> chemin = fichier();
        if (chemin.isEmpty())
        {
            return;
        }
        try
        {
            String contenu = contenu(seal);
            String signature = hsm.sign(PreuveIntegriteService.ALIAS_SYSTEME,
                hashService.calculateFromBytes(contenu.getBytes(StandardCharsets.UTF_8)));
            Files.writeString(chemin.get(), contenu + "|" + signature + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception e)
        {
            log.error("[Registre-Scellements] Scellement {} NON inscrit au registre ({}) : {}", seal.getId(),
                chemin.get(), e.getMessage());
        }
    }

    public synchronized Lecture lire()
    {
        Optional<Path> chemin = fichier();
        if (chemin.isEmpty())
        {
            return new Lecture(false, false, List.of(), 0);
        }
        if (!Files.isReadable(chemin.get()))
        {
            return new Lecture(true, false, List.of(), 0);
        }

        List<Ligne> lignes = new ArrayList<>();
        int invalides = 0;
        try
        {
            for (String brute : Files.readAllLines(chemin.get(), StandardCharsets.UTF_8))
            {
                if (brute.isBlank())
                {
                    continue;
                }
                Ligne ligne = analyser(brute);
                if (ligne == null)
                {
                    invalides++;
                }
                else
                {
                    lignes.add(ligne);
                }
            }
        }
        catch (IOException e)
        {
            log.warn("[Registre-Scellements] Lecture de {} impossible : {}", chemin.get(), e.getMessage());
            return new Lecture(true, false, List.of(), 0);
        }
        return new Lecture(true, true, lignes, invalides);
    }

    /** La ligne si sa signature est valide ; null si elle est illisible ou falsifiée. */
    private Ligne analyser(String brute)
    {
        int separateur = brute.lastIndexOf('|');
        if (separateur < 0)
        {
            return null;
        }
        String contenu = brute.substring(0, separateur);
        String signature = brute.substring(separateur + 1).trim();
        String[] champs = contenu.split(";");
        if (champs.length != 4)
        {
            return null;
        }
        Optional<Boolean> valide = hsm.verifier(PreuveIntegriteService.ALIAS_SYSTEME,
            hashService.calculateFromBytes(contenu.getBytes(StandardCharsets.UTF_8)), signature);
        // Clé de confiance introuvable : indéterminé — on ne déclare pas la ligne falsifiée.
        if (valide.isPresent() && !valide.get())
        {
            return null;
        }
        try
        {
            return new Ligne(Long.parseLong(champs[0]), Long.parseLong(champs[1]), champs[2], champs[3]);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** Empreinte d'un scellement telle qu'inscrite au registre (pour comparer à la base). */
    public String empreinteSignature(AuditChainSeal seal)
    {
        return hashService.calculateFromBytes(String.valueOf(seal.getSignature()).getBytes(StandardCharsets.UTF_8));
    }
}
