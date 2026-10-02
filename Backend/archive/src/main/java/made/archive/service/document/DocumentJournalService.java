package made.archive.service.document;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import made.archive.dto.AuditLogDto;
import made.archive.dto.DocumentJournalDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.JournalAudit;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.FixityCheckResultRepository;
import made.archive.repository.JournalAuditRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;

/**
 * Journal de cycle de vie d'un document : tout ce qu'il a subi dans le système (dépôt, consultations,
 * téléchargements, attestations/QR, changements d'accès, de dossier, d'emplacement ou de métadonnées,
 * corbeille, contrôles d'intégrité en échec, horodatage, vérifications publiques...).
 *
 * N'a PAS de stockage propre : c'est le journal d'audit (journal_audit, chaîné et horodaté — voir
 * AuditChainService) filtré sur ce document, plus les entrées de son groupe d'accès. Une seule source
 * de vérité, donc aucune divergence possible avec l'audit, et la même protection contre l'altération.
 *
 * Accessible à qui a accès au document (même règle que son détail — DocumentService.resolveDocumentSiVisible).
 * Réservé aux ADMIN, ADMIN_UO et EDITOR (un simple USER n'y a pas accès, même sur un document qu'il
 * peut ouvrir) — vérifié ici EN PLUS du @Secured du contrôleur. L'adresse IP n'est montrée qu'aux ADMIN/ADMIN_UO : le reste (qui, quand, quoi) est visible de tous
 * ceux qui peuvent ouvrir le document.
 */
@Service
@RequiredArgsConstructor
public class DocumentJournalService
{
    /** Plafond d'un export, comme AuditLogService.EXPORT_MAX_LIGNES. */
    private static final int EXPORT_MAX_LIGNES = 20_000;

    private final JournalAuditRepository         journalAuditRepository;
    private final FixityCheckResultRepository    fixityCheckResultRepository;
    private final UserRepository                 userRepository;
    private final DocumentService                documentService;
    private final AuditLogService                auditLogService;

    @Transactional(readOnly = true)
    public DocumentJournalDto consulter(UUID documentId, int page, int size, UserDetails userDetails)
    {
        User user = resoudreUtilisateur(userDetails);
        Document doc = resoudreDocument(documentId, user);

        Page<JournalAudit> resultat = journalAuditRepository.findJournalDocument(
            documentId.toString(), groupeId(doc), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));

        var controle = fixityCheckResultRepository.findByDocumentId(documentId);

        return DocumentJournalDto.builder()
            .content(resultat.getContent().stream().map(e -> versDto(e, voitIp(user))).toList())
            .page(resultat.getNumber())
            .size(resultat.getSize())
            .totalElements(resultat.getTotalElements())
            .totalPages(resultat.getTotalPages())
            .dernierControleLe(controle.map(c -> c.getCheckedAt()).orElse(null))
            .dernierControleResultat(controle.map(c -> c.getResult().name()).orElse(null))
            .build();
    }

    /** Toutes les entrées (plafonnées) pour l'export ; l'export est lui-même journalisé sur ce document. */
    @Transactional
    public List<AuditLogDto> exporter(UUID documentId, String format, UserDetails userDetails)
    {
        User user = resoudreUtilisateur(userDetails);
        Document doc = resoudreDocument(documentId, user);

        List<AuditLogDto> entrees = journalAuditRepository.findJournalDocument(
                documentId.toString(), groupeId(doc), PageRequest.of(0, EXPORT_MAX_LIGNES))
            .getContent().stream().map(e -> versDto(e, voitIp(user))).toList();

        auditLogService.log(user, AuditAction.DOCUMENT_JOURNAL_EXPORTE, AuditCible.DOCUMENT,
            documentId.toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Export (" + format + ") du journal du document \"" + doc.getTitre() + "\" par " + user.getEmail()
                + " — " + entrees.size() + " entrée(s)", true);

        return entrees;
    }

    private User resoudreUtilisateur(UserDetails userDetails)
    {
        return userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
    }

    private Document resoudreDocument(UUID documentId, User user)
    {
        boolean autorise = user.getRoles().stream().anyMatch(r ->
            r.getName() == Role_Name.ADMIN || r.getName() == Role_Name.ADMIN_UO || r.getName() == Role_Name.EDITOR);
        if (!autorise)
        {
            throw new BusinessException("Le journal d'un document est réservé aux administrateurs et aux éditeurs");
        }
        return documentService.resolveDocumentSiVisible(documentId, user)
            .orElseThrow(() -> new BusinessException("Document introuvable ou accès refusé"));
    }

    private String groupeId(Document doc)
    {
        return doc.getGroupe() != null ? doc.getGroupe().getId().toString() : "";
    }

    private boolean voitIp(User user)
    {
        return user.getRoles().stream()
            .anyMatch(r -> r.getName() == Role_Name.ADMIN || r.getName() == Role_Name.ADMIN_UO);
    }

    private AuditLogDto versDto(JournalAudit e, boolean avecIp)
    {
        return AuditLogDto.builder()
            .id(e.getId())
            .horodatage(e.getHorodatage())
            .acteurId(e.getActeurId())
            .acteurEmail(e.getActeurEmail())
            .acteurRole(e.getActeurRole())
            .adresseIp(avecIp ? e.getAdresseIp() : null)
            .action(e.getAction())
            .cibleType(e.getCibleType())
            .cibleId(e.getCibleId())
            .uoId(e.getUoId())
            .description(e.getDescription())
            .succes(e.isSucces())
            .details(e.getDetails())
            .build();
    }
}
