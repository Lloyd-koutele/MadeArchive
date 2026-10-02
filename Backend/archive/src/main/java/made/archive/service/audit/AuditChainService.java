package made.archive.service.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.ChaineAuditRuptureDto;
import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.entite.AuditChainSeal;
import made.archive.entite.JournalAudit;
import made.archive.repository.AuditChainSealRepository;
import made.archive.repository.JournalAuditRepository;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chaînage et scellement du journal d'audit — chaque entrée reçoit l'empreinte
 * SHA-256 d'elle-même concaténée à l'empreinte de l'entrée précédente (par id
 * croissant), rendant toute modification ou suppression détectable.
 *
 * Calcul VOLONTAIREMENT DIFFÉRÉ (ce job planifié — voir config.AuditChainScheduler
 * — pas AuditLogService.log() au moment de l'écriture) : log() est appelé en
 * parallèle depuis des dizaines de services métier, chacun dans sa PROPRE
 * transaction REQUIRES_NEW (voir sa Javadoc). Si le chaînage était calculé à
 * l'écriture, deux écritures concurrentes liraient la même "dernière empreinte",
 * produiraient deux entrées prétendant toutes deux descendre du même ancêtre, et
 * une vérification ultérieure (qui relit par id croissant) détecterait des
 * "ruptures" qui n'en sont pas — exactement l'inverse de l'effet recherché. Un
 * job planifié, par construction séquentiel, élimine ce risque sans verrou.
 *
 * Pas de chaînage rétroactif de l'historique antérieur à la mise en place de ce
 * mécanisme : les entrées déjà en base avant son premier passage ne seront
 * JAMAIS chaînées (chainHash restera null pour elles indéfiniment). Un chaînage
 * rétroactif donnerait une fausse impression d'avoir protégé un historique qui,
 * en réalité, ne l'était pas au moment où il a été écrit — un attaquant ayant
 * déjà altéré une vieille ligne avant ce déploiement verrait sa falsification
 * légitimée par un backfill. La chaîne démarre honnêtement au premier passage
 * de calculerChainage() après ce déploiement.
 *
 * Limite assumée de verifierChaine : détecte toute incohérence dans la chaîne
 * VIVANTE (altération isolée, ou même une altération suivie d'un ré-enchaînement
 * complet de la suite, puisque celui-ci diverge alors du dernier scellement
 * horodaté externe déjà enregistré — voir la comparaison avec AuditChainSeal).
 * Ne va PAS jusqu'à revalider cryptographiquement chaque jeton RFC 3161 stocké
 * contre le certificat public du TSA (ce que AuditChainSeal.horodatageToken
 * permettrait en théorie) — un attaquant contrôlant entièrement la base pourrait
 * en théorie supprimer/falsifier aussi les lignes audit_chain_seals elles-mêmes.
 * Amélioration possible plus tard si un niveau de preuve supérieur est requis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainService
{
    private final JournalAuditRepository    journalAuditRepository;
    private final AuditChainSealRepository  auditChainSealRepository;
    private final HashService               hashService;
    private final HorodatageService         horodatageService;

    /** Valeur de départ de la chaîne — jamais une vraie empreinte d'entrée,
     *  juste une constante fixe connue de tous pour amorcer le calcul. */
    private static final String GENESE = "GENESE-CHAINE-AUDIT-MADEARCHIVE";

    private static final char SEP = '\u001F'; // séparateur ASCII "unit separator", jamais présent dans un texte normal

    /**
     * Chaîne toutes les entrées pas encore chaînées (par id croissant), puis
     * scelle (horodatage RFC 3161, best-effort) la dernière empreinte obtenue.
     * Idempotent et sans effet si rien de nouveau à chaîner — appelable à
     * volonté (voir AuditChainScheduler pour la cadence nocturne).
     */
    @Transactional
    public void calculerChainage()
    {
        List<JournalAudit> aChainer = journalAuditRepository.findByChainHashIsNullOrderByIdAsc();
        if (aChainer.isEmpty())
        {
            return;
        }

        JournalAudit dernierChaine = journalAuditRepository.findTopByChainHashIsNotNullOrderByIdDesc();
        String hashPrecedent = dernierChaine != null ? dernierChaine.getChainHash() : sha256(GENESE);

        for (JournalAudit entree : aChainer)
        {
            String hash = sha256(hashPrecedent + SEP + serialiserCanonique(entree));
            entree.setChainHash(hash);
            journalAuditRepository.save(entree);
            hashPrecedent = hash;
        }

        log.info("[Chaine-Audit] {} nouvelle(s) entrée(s) chaînée(s) (jusqu'à l'id {}), dernière empreinte {}",
            aChainer.size(), aChainer.get(aChainer.size() - 1).getId(), hashPrecedent);

        sceller(aChainer.get(aChainer.size() - 1).getId(), hashPrecedent);
    }

    private void sceller(Long dernierEntryId, String dernierChainHash)
    {
        HorodatageService.HorodatageResult resultat = horodatageService.horodater(dernierChainHash);

        AuditChainSeal seal = new AuditChainSeal();
        seal.setDernierEntryId(dernierEntryId);
        seal.setDernierChainHash(dernierChainHash);
        seal.setCreatedAt(Instant.now());

        if (resultat != null)
        {
            seal.setHorodatageToken(resultat.token());
            seal.setHorodatageDate(resultat.date());
        }
        else
        {
            log.warn("[Chaine-Audit] Scellement horodaté échoué (best-effort, voir HorodatageService) — "
                + "le chaînage lui-même est conservé, seule la preuve tierce RFC 3161 manque pour ce passage");
        }

        auditChainSealRepository.save(seal);
    }

    /**
     * Recalcule la chaîne entière et compare à ce qui est stocké. uoIdsAutorisees
     * (même convention que AuditLogService.rechercher) restreint les RUPTURES
     * renvoyées en détail à l'appelant (ADMIN_UO), jamais le calcul lui-même : la
     * chaîne traverse toutes les UO, une vérification partielle n'aurait pas de
     * sens cryptographique — voir rupturesHorsPerimetre pour signaler sans
     * détail qu'autre chose est cassé ailleurs.
     */
    @Transactional(readOnly = true)
    public ChaineAuditVerificationDto verifierChaine(Set<Long> uoIdsAutorisees)
    {
        List<JournalAudit> chaine = journalAuditRepository.findByChainHashIsNotNullOrderByIdAsc();

        List<ChaineAuditRuptureDto> ruptures = new ArrayList<>();
        Map<Long, String> hashLiveParEntryId = new java.util.HashMap<>();
        String hashAttendu = sha256(GENESE);

        for (JournalAudit entree : chaine)
        {
            String hashCalcule = sha256(hashAttendu + SEP + serialiserCanonique(entree));
            if (!hashCalcule.equals(entree.getChainHash()))
            {
                ruptures.add(ChaineAuditRuptureDto.builder()
                    .id(entree.getId())
                    .horodatage(entree.getHorodatage())
                    .uoId(entree.getUoId())
                    .action(entree.getAction())
                    .description(entree.getDescription())
                    .build());
            }
            // Continue avec l'empreinte STOCKÉE (pas la recalculée) comme base du
            // tour suivant : une entrée altérée est ainsi signalée SEULE, sans
            // propager une rupture en cascade à toutes les entrées suivantes qui,
            // elles, sont parfaitement cohérentes avec ce qui est réellement stocké.
            hashAttendu = entree.getChainHash();
            hashLiveParEntryId.put(entree.getId(), entree.getChainHash());
        }

        // Comparaison avec les scellements déjà horodatés : si l'empreinte VIVANTE
        // à l'id scellé ne correspond plus à ce qui a été scellé à l'époque, c'est
        // le signal le plus fort possible (même un ré-enchaînement complet de la
        // suite, après une altération, ne peut pas faire correspondre à nouveau
        // une valeur déjà figée par un tiers externe — voir Javadoc de la classe).
        for (AuditChainSeal seal : auditChainSealRepository.findAll())
        {
            String hashVivant = hashLiveParEntryId.get(seal.getDernierEntryId());
            if (hashVivant != null && !hashVivant.equals(seal.getDernierChainHash())
                && ruptures.stream().noneMatch(r -> r.getId().equals(seal.getDernierEntryId())))
            {
                JournalAudit entree = chaine.stream()
                    .filter(e -> e.getId().equals(seal.getDernierEntryId()))
                    .findFirst().orElse(null);
                ruptures.add(ChaineAuditRuptureDto.builder()
                    .id(seal.getDernierEntryId())
                    .horodatage(entree != null ? entree.getHorodatage() : null)
                    .uoId(entree != null ? entree.getUoId() : null)
                    .action(entree != null ? entree.getAction() : null)
                    .description("Divergence avec le scellement horodaté du "
                        + seal.getCreatedAt() + " — l'empreinte actuelle ne correspond plus à celle scellée")
                    .build());
            }
        }

        List<ChaineAuditRuptureDto> ruptureVisibles = uoIdsAutorisees == null
            ? ruptures
            : ruptures.stream()
                .filter(r -> r.getUoId() == null || uoIdsAutorisees.contains(r.getUoId()))
                .collect(Collectors.toList());

        AuditChainSeal dernierScellement = auditChainSealRepository.findTopByOrderByIdDesc();

        return ChaineAuditVerificationDto.builder()
            .chaineIntacte(ruptures.isEmpty())
            .nombreEntreesChainees(chaine.size())
            .ruptures(ruptureVisibles)
            .rupturesHorsPerimetre(uoIdsAutorisees != null && ruptureVisibles.size() < ruptures.size())
            .dernierScellementDate(dernierScellement != null ? dernierScellement.getHorodatageDate() : null)
            .dernierScellementEntryId(dernierScellement != null ? dernierScellement.getDernierEntryId() : null)
            .build();
    }

    /** Concaténation déterministe des champs logiques d'une entrée, dans un ordre
     *  FIXE — jamais ObjectMapper.writeValueAsString(entité) : l'ordre des champs
     *  JSON de Jackson est un détail d'implémentation, pas un contrat ; une montée
     *  de version pourrait silencieusement invalider toutes les vérifications passées. */
    private String serialiserCanonique(JournalAudit e)
    {
        return new StringBuilder()
            .append(e.getId()).append(SEP)
            .append(e.getHorodatage()).append(SEP)
            .append(e.getActeurId()).append(SEP)
            .append(e.getActeurEmail()).append(SEP)
            .append(e.getActeurRole()).append(SEP)
            .append(e.getAdresseIp()).append(SEP)
            .append(e.getAction()).append(SEP)
            .append(e.getCibleType()).append(SEP)
            .append(e.getCibleId()).append(SEP)
            .append(e.getUoId()).append(SEP)
            .append(e.getDescription()).append(SEP)
            .append(e.isSucces()).append(SEP)
            .append(e.getDetails())
            .toString();
    }

    private String sha256(String texte)
    {
        return hashService.calculateFromBytes(texte.getBytes(StandardCharsets.UTF_8));
    }
}
