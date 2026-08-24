package made.archive.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.AuditLogDto;
import made.archive.dto.AuditLogPageDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.JournalAudit;
import made.archive.entite.User;
import made.archive.repository.JournalAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Journal d'audit du système — écriture best-effort (une panne de journalisation ne
 * doit jamais faire échouer l'action réelle) et consultation filtrée/paginée.
 *
 * Le scoping ADMIN_UO (restreindre la consultation à son UO + sous-arbre) est résolu
 * par l'appelant — voir AuditLogController — et passé ici en paramètre (uoIdsAutorisees),
 * plutôt que résolu depuis ce service : UniteOrganisationnelleService dépend déjà de
 * AuditLogService pour écrire ses propres entrées (création/suppression d'UO, etc.), donc
 * la dépendance inverse créerait un cycle.
 *
 * Le catalogue des actions journalisées (AuditAction) et le raisonnement derrière —
 * ce qui mérite d'être tracé vs. ce qui serait du bruit — a été discuté et fixé avant
 * l'implémentation ; voir les appels de log() dans chaque service métier concerné.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService
{
    private final JournalAuditRepository journalAuditRepository;
    private final ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════════════════
    // ÉCRITURE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Forme complète — détails structurés (avant/après) inclus.
     *
     * REQUIRES_NEW + saveAndFlush, ensemble, sont indispensables pour un vrai
     * "best-effort" : save() seul ne fait qu'enregistrer l'intention d'écrire — le SQL
     * (et la vérification des contraintes) n'a lieu qu'au flush, normalement reporté au
     * commit de la transaction ENGLOBANTE (celle de l'appelant, ex. TypeDocumentService).
     * Sans ces deux éléments, une violation de contrainte sur l'entrée d'audit remonterait
     * hors de ce try/catch, au moment du commit de l'appelant — et ferait échouer toute
     * SON opération avec elle, ce qui est arrivé (TransactionSystemException: Could not
     * commit JPA transaction). REQUIRES_NEW isole cette écriture dans sa propre
     * transaction ; saveAndFlush force la vérification des contraintes ICI, pas après.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(User acteur, AuditAction action, AuditCible cibleType, String cibleId,
                     Long uoId, String description, boolean succes, Map<String, Object> details)
    {
        try
        {
            JournalAudit entree = JournalAudit.builder()
                .horodatage(Instant.now())
                .acteurId(acteur != null ? acteur.getId() : null)
                .acteurEmail(acteur != null ? acteur.getEmail() : null)
                .acteurRole(acteur != null ? rolesToString(acteur) : null)
                .adresseIp(adresseIpCourante())
                .action(action)
                .cibleType(cibleType)
                .cibleId(cibleId)
                .uoId(uoId)
                .description(description)
                .succes(succes)
                .details(serialiserDetails(details))
                .build();

            journalAuditRepository.saveAndFlush(entree);
        }
        catch (Exception e)
        {
            // Best-effort : ne jamais interrompre l'action réelle pour un problème de journalisation.
            // Cause racine loguée (pas juste e.getMessage(), souvent un wrapper JPA peu parlant).
            Throwable racine = causeRacine(e);
            log.warn("[Audit] Échec d'écriture ({} / {}) : {}", action, cibleType, racine.getMessage());
        }
    }

    private Throwable causeRacine(Throwable t)
    {
        Throwable courant = t;
        while (courant.getCause() != null && courant.getCause() != courant)
        {
            courant = courant.getCause();
        }
        return courant;
    }

    /** Sans détails structurés. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(User acteur, AuditAction action, AuditCible cibleType, String cibleId,
                     Long uoId, String description, boolean succes)
    {
        log(acteur, action, cibleType, cibleId, uoId, description, succes, null);
    }

    /** Sans cible ni contexte UO précis (ex. tentative de connexion échouée). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(User acteur, AuditAction action, String description, boolean succes)
    {
        log(acteur, action, null, null, null, description, succes, null);
    }

    private String rolesToString(User acteur)
    {
        if (acteur.getRoles() == null) return null;
        return acteur.getRoles().stream()
            .map(r -> r.getName().name())
            .collect(Collectors.joining(","));
    }

    private String serialiserDetails(Map<String, Object> details)
    {
        if (details == null || details.isEmpty()) return null;
        try
        {
            return objectMapper.writeValueAsString(details);
        }
        catch (Exception e)
        {
            log.warn("[Audit] Impossible de sérialiser les détails : {}", e.getMessage());
            return null;
        }
    }

    private String adresseIpCourante()
    {
        try
        {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;

            HttpServletRequest request = attrs.getRequest();
            String transmis = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(transmis))
            {
                return transmis.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONSULTATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Recherche filtrée et paginée.
     *
     * @param uoIdsAutorisees null = aucune restriction (ADMIN global) ; sinon la recherche
     *                        est restreinte à ces UO (résolu par l'appelant via
     *                        UniteOrganisationnelleService.getUoIdsSousAutorite — un
     *                        ADMIN_UO ne doit jamais voir le journal d'une autre branche).
     *                        Un ensemble vide renvoie une page vide (ADMIN_UO sans UO active).
     */
    public AuditLogPageDto rechercher(Set<Long> uoIdsAutorisees,
                                       UUID acteurId,
                                       AuditAction action,
                                       AuditCible cibleType,
                                       Long uoId,
                                       Instant dateDebut,
                                       Instant dateFin,
                                       String texte,
                                       int page,
                                       int size)
    {
        if (uoIdsAutorisees != null && uoIdsAutorisees.isEmpty())
        {
            return AuditLogPageDto.empty(page, size);
        }

        Specification<JournalAudit> spec = construireSpecification(
            uoIdsAutorisees, acteurId, action, cibleType, uoId, dateDebut, dateFin, texte);

        Page<JournalAudit> resultat = journalAuditRepository.findAll(
            spec, PageRequest.of(page, size, Sort.by("horodatage").descending()));

        return AuditLogPageDto.builder()
            .content(resultat.getContent().stream().map(this::toDto).toList())
            .page(resultat.getNumber())
            .size(resultat.getSize())
            .totalElements(resultat.getTotalElements())
            .totalPages(resultat.getTotalPages())
            .build();
    }

    /** Nombre maximal de lignes renvoyées par un export — filet de sécurité contre un filtre trop large. */
    private static final int EXPORT_MAX_LIGNES = 20_000;

    /**
     * Variante non paginée pour l'export (CSV / .log) — mêmes filtres et même scoping
     * ADMIN_UO que {@link #rechercher}, plafonnée à EXPORT_MAX_LIGNES pour éviter un
     * export accidentellement démesuré à charger entièrement en mémoire.
     */
    public List<AuditLogDto> rechercherTout(Set<Long> uoIdsAutorisees,
                                             UUID acteurId,
                                             AuditAction action,
                                             AuditCible cibleType,
                                             Long uoId,
                                             Instant dateDebut,
                                             Instant dateFin,
                                             String texte)
    {
        if (uoIdsAutorisees != null && uoIdsAutorisees.isEmpty())
        {
            return List.of();
        }

        Specification<JournalAudit> spec = construireSpecification(
            uoIdsAutorisees, acteurId, action, cibleType, uoId, dateDebut, dateFin, texte);

        Page<JournalAudit> resultat = journalAuditRepository.findAll(
            spec, PageRequest.of(0, EXPORT_MAX_LIGNES, Sort.by("horodatage").descending()));

        return resultat.getContent().stream().map(this::toDto).toList();
    }

    private Specification<JournalAudit> construireSpecification(Set<Long> uoIdsAutorisees,
                                                                  UUID acteurId,
                                                                  AuditAction action,
                                                                  AuditCible cibleType,
                                                                  Long uoId,
                                                                  Instant dateDebut,
                                                                  Instant dateFin,
                                                                  String texte)
    {
        Specification<JournalAudit> spec = (root, query, cb) -> cb.conjunction();

        if (uoIdsAutorisees != null)
        {
            Set<Long> autorisees = uoIdsAutorisees;
            spec = spec.and((root, query, cb) -> root.get("uoId").in(autorisees));
        }
        if (uoId != null)
        {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("uoId"), uoId));
        }
        if (acteurId != null)
        {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("acteurId"), acteurId));
        }
        if (action != null)
        {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (cibleType != null)
        {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("cibleType"), cibleType));
        }
        if (dateDebut != null)
        {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("horodatage"), dateDebut));
        }
        if (dateFin != null)
        {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("horodatage"), dateFin));
        }
        if (StringUtils.hasText(texte))
        {
            String motif = "%" + texte.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("description")), motif),
                cb.like(cb.lower(root.get("acteurEmail")), motif)
            ));
        }

        return spec;
    }

    private AuditLogDto toDto(JournalAudit entree)
    {
        return AuditLogDto.builder()
            .id(entree.getId())
            .horodatage(entree.getHorodatage())
            .acteurId(entree.getActeurId())
            .acteurEmail(entree.getActeurEmail())
            .acteurRole(entree.getActeurRole())
            .adresseIp(entree.getAdresseIp())
            .action(entree.getAction())
            .cibleType(entree.getCibleType())
            .cibleId(entree.getCibleId())
            .uoId(entree.getUoId())
            .description(entree.getDescription())
            .succes(entree.isSucces())
            .details(entree.getDetails())
            .build();
    }
}
