package made.archive.service.integrite;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.AncrageCatalogue;
import made.archive.entite.AuditAction;
import made.archive.repository.AncrageCatalogueRepository;
import made.archive.repository.DocumentRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.BlockchainService;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;
import made.archive.service.integrite.HorodatageVerificationService.Etat;

/**
 * Ancrage quotidien du catalogue : une racine de Merkle des preuves de tous les nouveaux documents, signée par la
 * clé du système et horodatée par une autorité tierce (jeton RFC 3161), éventuellement inscrite aussi sur une
 * blockchain. Les documents sont rattachés à leur lot (documents.ancrage_id).
 *
 * Pourquoi : modifier ensuite l'empreinte, la signature ou l'identifiant d'UN seul document de ce lot change la
 * racine recalculée — qui ne correspond plus à celle que l'autorité a certifiée à cette date, qu'aucune
 * réécriture de PostgreSQL ne peut refaire. La vérification (verifier) le détecte pour TOUS les documents d'un
 * coup, y compris ceux dont personne ne rouvre le fichier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogueAncrageService
{
    private static final String PREFIXE_SIGNATURE = "MADEARCHIVE-ANCRAGE-V1";
    private static final long CLE_VERROU_ANCRAGE = 0x414E4352L; // "ANCR"
    private static final int TAILLE_LOT_SQL = 5000;

    public record Rapport(boolean intact, int ancrages, int documentsAncres, List<String> anomalies) {}

    private final AncrageCatalogueRepository ancrages;
    private final DocumentRepository documents;
    private final HsmKeyStoreService hsm;
    private final HashService hashService;
    private final HorodatageService horodatageService;
    private final HorodatageVerificationService jetons;
    private final AuditLogService auditLogService;
    private final BlockchainService blockchainService;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Value("${ancrage.blockchain.actif:false}")
    private boolean blockchainActive;

    private TransactionTemplate tx;

    @PostConstruct
    void init()
    {
        this.tx = new TransactionTemplate(transactionManager);
    }

    private static String payload(AncrageCatalogue a)
    {
        return String.join("|", PREFIXE_SIGNATURE, String.valueOf(a.getCreatedAt().getEpochSecond()),
            String.valueOf(a.getNombreDocuments()), a.getRacineMerkle(),
            a.getRacinePrecedente() != null ? a.getRacinePrecedente() : "-");
    }

    private String empreinteSignature(AncrageCatalogue a)
    {
        return hashService.calculateFromBytes(payload(a).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Ancrage
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ancre les documents scellés pas encore ancrés. Sans effet s'il n'y en a aucun, ou si une autre instance est
     * déjà en train d'ancrer.
     */
    public Optional<AncrageCatalogue> ancrer()
    {
        AncrageCatalogue cree = tx.execute(statut ->
        {
            Boolean verrou = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, CLE_VERROU_ANCRAGE);
            if (!Boolean.TRUE.equals(verrou))
            {
                return null;
            }
            List<FeuilleDocument> feuilles = documents.findFeuillesAAncrer();
            if (feuilles.isEmpty())
            {
                return null;
            }

            AncrageCatalogue precedent = ancrages.findTopByOrderByIdDesc();
            AncrageCatalogue a = new AncrageCatalogue();
            a.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            a.setNombreDocuments(feuilles.size());
            a.setRacineMerkle(ArbreMerkle.racine(feuilles));
            a.setRacinePrecedente(precedent != null ? precedent.getRacineMerkle() : null);
            a.setSignatureAlias(PreuveIntegriteService.ALIAS_SYSTEME);
            a.setSignature(hsm.sign(PreuveIntegriteService.ALIAS_SYSTEME, empreinteSignature(a)));
            AncrageCatalogue enregistre = ancrages.saveAndFlush(a);

            List<UUID> ids = feuilles.stream().map(FeuilleDocument::id).toList();
            int rattaches = 0;
            for (int i = 0; i < ids.size(); i += TAILLE_LOT_SQL)
            {
                rattaches += documents.rattacherAncrage(enregistre.getId(),
                    ids.subList(i, Math.min(i + TAILLE_LOT_SQL, ids.size())));
            }
            if (rattaches != feuilles.size())
            {
                throw new IllegalStateException("Ancrage annulé : " + rattaches + " document(s) rattaché(s) sur "
                    + feuilles.size() + " attendus (modification concurrente)");
            }
            return enregistre;
        });

        if (cree == null)
        {
            return Optional.empty();
        }

        // Hors transaction : l'appel à l'autorité d'horodatage peut durer jusqu'à son délai de réponse.
        horodaterEtInscrire(cree);
        auditLogService.log(null, AuditAction.CATALOGUE_ANCRE, null, String.valueOf(cree.getId()), null,
            "Ancrage du catalogue n°" + cree.getId() + " : " + cree.getNombreDocuments() + " document(s), racine de Merkle "
                + cree.getRacineMerkle(), true);
        log.info("[Ancrage] Catalogue ancré : lot {} ({} documents), racine {}", cree.getId(),
            cree.getNombreDocuments(), cree.getRacineMerkle());
        return Optional.of(cree);
    }

    /** Obtient (si manquant) le jeton RFC 3161 de la racine, et l'inscription blockchain si elle est activée. */
    private void horodaterEtInscrire(AncrageCatalogue a)
    {
        boolean modifie = false;
        if (a.getHorodatageToken() == null)
        {
            HorodatageService.HorodatageResult r = horodatageService.horodater(a.getRacineMerkle());
            if (r != null)
            {
                a.setHorodatageToken(r.token());
                a.setHorodatageDate(r.date());
                modifie = true;
            }
        }
        if (blockchainActive && a.getBlockchainTx() == null)
        {
            try
            {
                a.setBlockchainTx(blockchainService.registerDocument(a.getRacineMerkle()));
                modifie = true;
            }
            catch (Exception e)
            {
                log.warn("[Ancrage] Inscription blockchain échouée pour le lot {} (non bloquant) : {}",
                    a.getId(), e.getMessage());
            }
        }
        if (modifie)
        {
            ancrages.save(a);
        }
    }

    /** Reprise des ancrages dont le jeton (ou l'inscription blockchain) n'a pas pu être obtenu. */
    public int completerHorodatages()
    {
        int completes = 0;
        for (AncrageCatalogue a : ancrages.findByHorodatageTokenIsNullOrderByIdAsc())
        {
            horodaterEtInscrire(a);
            if (a.getHorodatageToken() != null)
            {
                completes++;
            }
        }
        return completes;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    // Vérification
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Recalcule chaque racine à partir des documents tels qu'ils sont AUJOURD'HUI en base et la confronte à ce
     *  qui a été signé et horodaté à l'époque. */
    public Rapport verifier()
    {
        List<String> anomalies = new ArrayList<>();
        List<AncrageCatalogue> tous = ancrages.findAllByOrderByIdAsc();
        int totalDocuments = 0;
        AncrageCatalogue precedent = null;

        for (AncrageCatalogue a : tous)
        {
            String nom = "Ancrage n°" + a.getId() + " (" + a.getCreatedAt() + ")";

            List<FeuilleDocument> feuilles = new ArrayList<>(documents.findFeuillesParAncrage(a.getId()));
            feuilles.sort(Comparator.comparing(f -> f.id().toString()));
            totalDocuments += feuilles.size();

            if (feuilles.size() != a.getNombreDocuments())
            {
                anomalies.add(nom + " : " + feuilles.size() + " document(s) rattaché(s) au lieu de "
                    + a.getNombreDocuments() + " (document retiré, ajouté ou déplacé)");
            }
            if (!ArbreMerkle.racine(feuilles).equals(a.getRacineMerkle()))
            {
                anomalies.add(nom + " : la racine recalculée ne correspond pas à la racine ancrée — les preuves d'au moins "
                    + "un document de ce lot ont été modifiées");
            }

            Optional<Boolean> signature = hsm.verifier(a.getSignatureAlias(), empreinteSignature(a), a.getSignature());
            if (signature.isPresent() && !signature.get())
            {
                anomalies.add(nom + " : signature du système invalide (racine, date ou effectif modifiés)");
            }

            Etat jeton = jetons.verifier(a.getHorodatageToken(), a.getRacineMerkle());
            if (jeton == Etat.EMPREINTE_DIFFERENTE)
            {
                anomalies.add(nom + " : le jeton d'horodatage atteste une autre racine que celle enregistrée");
            }
            else if (jeton == Etat.INVALIDE)
            {
                anomalies.add(nom + " : jeton d'horodatage illisible, invalide ou d'une autorité inconnue");
            }

            String attendue = precedent != null ? precedent.getRacineMerkle() : null;
            if (a.getRacinePrecedente() == null ? attendue != null : !a.getRacinePrecedente().equals(attendue))
            {
                anomalies.add(nom + " : le lien avec l'ancrage précédent est rompu (un ancrage a été retiré ou modifié)");
            }
            precedent = a;
        }
        return new Rapport(anomalies.isEmpty(), tous.size(), totalDocuments, anomalies);
    }
}
