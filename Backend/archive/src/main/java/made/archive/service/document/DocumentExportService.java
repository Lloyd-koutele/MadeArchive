package made.archive.service.document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.DocumentExportProperties;
import made.archive.dto.ExportApercuDocumentDto;
import made.archive.dto.ExportApercuRequestDto;
import made.archive.dto.ExportJobStatutDto;
import made.archive.dto.ExportLancerRequestDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.ExportJob;
import made.archive.entite.ExportJobStatus;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.ExportJobRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Export administratif de documents d'une ou plusieurs UO — pensé pour une
 * migration ou un changement de système d'archivage, en remplacement du
 * script export_uo_documents.py (accès direct DB + MinIO + clé de
 * chiffrement, réservé jusqu'ici à qui avait un accès d'infrastructure).
 *
 * Frontière de sécurité DÉLIBÉRÉE, à deux niveaux (voir échange de conception
 * précédant ce code) :
 *   — un export "normal" ne contourne AUCUNE règle de visibilité déjà en
 *     place ailleurs dans l'app : un document privé n'y apparaît que si
 *     l'appelant en est déjà membre, exactement comme
 *     DocumentService.resolveDocument/estVisibleNormalement. Accessible à
 *     ROLE_ADMIN et ROLE_ADMIN_UO (scopé à son UO + descendantes).
 *   — un export élargi aux documents privés dont l'appelant n'est PAS
 *     membre (includePriveNonMembre) est réservé à ROLE_ADMIN, exige un
 *     motif, et déclenche une notification OBLIGATOIRE (pas un simple
 *     journal d'audit) vers les membres concernés et l'admin_uo de leur UO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportService
{
    private final DocumentRepository               documentRepository;
    private final ExportJobRepository              exportJobRepository;
    private final UniteOrganisationnelleService    uniteOrganisationnelleService;
    private final NotificationService              notificationService;
    private final AuditLogService                  auditLogService;
    private final DocumentExportProperties         properties;
    private final DocumentExportGenerationService  generationService;

    @Transactional(readOnly = true)
    public List<ExportApercuDocumentDto> apercu(ExportApercuRequestDto requete, User appelant)
    {
        if (requete.getUoIds() == null || requete.getUoIds().isEmpty())
        {
            throw new BusinessException("Aucune unité organisationnelle sélectionnée");
        }

        verifierUoAutorisees(requete.getUoIds(), appelant);
        verifierEligibiliteElevation(requete.isIncludePriveNonMembre(), appelant);

        List<Document> candidats = documentRepository.findForExport(
            requete.getUoIds(), statutsExclus(requete.isExcludeCorbeille()));

        return candidats.stream()
            .filter(doc -> requete.isIncludePriveNonMembre() || estVisibleSansElevation(doc, appelant))
            .map(doc -> new ExportApercuDocumentDto(
                doc.getId(),
                doc.getTitre(),
                doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getNom() : null,
                doc.getProjet() != null ? doc.getProjet().getNom() : null,
                doc.getAccess(),
                estVisibleSansElevation(doc, appelant)))
            .toList();
    }

    @Transactional
    public ExportJob lancerExport(ExportLancerRequestDto requete, User appelant)
    {
        if (requete.getUoIds() == null || requete.getUoIds().isEmpty())
        {
            throw new BusinessException("Aucune unité organisationnelle sélectionnée");
        }

        verifierUoAutorisees(requete.getUoIds(), appelant);
        verifierEligibiliteElevation(requete.isIncludePriveNonMembre(), appelant);

        if (requete.isIncludePriveNonMembre()
            && (requete.getMotif() == null || requete.getMotif().isBlank()))
        {
            throw new BusinessException(
                "Un motif est obligatoire pour un export incluant des documents privés dont vous n'êtes pas membre");
        }

        List<Document> candidats = documentRepository.findForExport(
            requete.getUoIds(), statutsExclus(requete.isExcludeCorbeille()));

        List<Document> documents = candidats.stream()
            .filter(doc -> requete.isIncludePriveNonMembre() || estVisibleSansElevation(doc, appelant))
            .toList();

        if (requete.getDocIds() != null && !requete.getDocIds().isEmpty())
        {
            Set<UUID> voulus = new HashSet<>(requete.getDocIds());
            documents = documents.stream().filter(d -> voulus.contains(d.getId())).toList();
        }

        if (documents.isEmpty())
        {
            throw new BusinessException("Aucun document à exporter dans ce périmètre");
        }

        ExportJob job = new ExportJob();
        job.setDemandePar(appelant);
        job.setUoIds(requete.getUoIds());
        job.setDocumentIds(documents.stream().map(Document::getId).toList());
        job.setIncludePriveNonMembre(requete.isIncludePriveNonMembre());
        job.setSeparateProjects(requete.isSeparateProjects());
        job.setExcludeCorbeille(requete.isExcludeCorbeille());
        job.setMotif(requete.getMotif());
        job.setDocumentsTotal(documents.size());
        job.setCreateAt(LocalDateTime.now());
        job.setExpireAt(LocalDateTime.now().plusHours(properties.getRetentionHours()));

        ExportJob saved = exportJobRepository.save(job);

        auditLogService.log(appelant, AuditAction.EXPORT_DOCUMENTS_DEMANDE, AuditCible.DOCUMENT,
            saved.getId().toString(), null,
            documents.size() + " document(s), " + requete.getUoIds().size() + " UO"
                + (requete.getMotif() != null ? " — motif : " + requete.getMotif() : ""),
            true);

        if (requete.isIncludePriveNonMembre())
        {
            List<Document> privesNonMembres = documents.stream()
                .filter(d -> d.getAccess() == TypeAccess.PRIVE && !estMembre(d, appelant))
                .toList();

            if (!privesNonMembres.isEmpty())
            {
                auditLogService.log(appelant, AuditAction.EXPORT_DOCUMENTS_PRIVES_INCLUS, AuditCible.DOCUMENT,
                    saved.getId().toString(), null,
                    privesNonMembres.size() + " document(s) privé(s) hors appartenance inclus — motif : "
                        + requete.getMotif(),
                    true);

                notifierDocumentsPrivesInclus(privesNonMembres, appelant, requete.getMotif());
            }
        }

        generationService.genererExportAsync(saved.getId());

        return saved;
    }

    @Transactional(readOnly = true)
    public ExportJobStatutDto getStatut(UUID jobId, User appelant)
    {
        ExportJob job = trouverJobAutorise(jobId, appelant);
        return new ExportJobStatutDto(job.getId(), job.getStatut(), job.getDocumentsTotal(),
            job.getDocumentsTraites(), job.getDocumentsEnEchec(), job.getCreateAt(),
            job.getCompletedAt(), job.getExpireAt());
    }

    @Transactional
    public Path getZipPourTelechargement(UUID jobId, User appelant)
    {
        ExportJob job = trouverJobAutorise(jobId, appelant);

        if (job.getStatut() != ExportJobStatus.PRET || job.getCheminZip() == null)
        {
            throw new BusinessException("Cet export n'est pas encore prêt");
        }

        Path zip = Path.of(job.getCheminZip());
        if (!Files.exists(zip))
        {
            throw new BusinessException("Le fichier d'export n'existe plus (probablement expiré)");
        }

        auditLogService.log(appelant, AuditAction.EXPORT_TELECHARGE, AuditCible.DOCUMENT,
            job.getId().toString(), null,
            "Téléchargement de l'export (" + job.getDocumentsTotal() + " document(s))", true);

        return zip;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Contrôles d'accès
    // ─────────────────────────────────────────────────────────────────────

    /** null renvoyé par getUoIdsSousAutorite = ADMIN global, aucune restriction (même
     *  convention que partout ailleurs dans UniteOrganisationnelleService). */
    private void verifierUoAutorisees(List<Long> uoIdsDemandes, User appelant)
    {
        Set<Long> autorisees = uniteOrganisationnelleService.getUoIdsSousAutorite(appelant);
        if (autorisees == null)
        {
            return;
        }
        for (Long uoId : uoIdsDemandes)
        {
            if (!autorisees.contains(uoId))
            {
                throw new BusinessException(
                    "Vous n'avez pas autorité sur l'unité organisationnelle " + uoId);
            }
        }
    }

    private void verifierEligibiliteElevation(boolean includePriveNonMembre, User appelant)
    {
        if (includePriveNonMembre && !isAdmin(appelant))
        {
            throw new BusinessException(
                "Seul un administrateur global peut exporter des documents privés dont il n'est pas membre");
        }
    }

    private ExportJob trouverJobAutorise(UUID jobId, User appelant)
    {
        ExportJob job = exportJobRepository.findById(jobId)
            .orElseThrow(() -> new BusinessException("Export introuvable : " + jobId));

        if (!isAdmin(appelant))
        {
            verifierUoAutorisees(job.getUoIds(), appelant);
        }

        return job;
    }

    private boolean isAdmin(User user)
    {
        return user.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.ADMIN);
    }

    /** Même règle que DocumentService.estVisibleNormalement, appliquée
     *  document par document — un export "normal" ne doit jamais montrer
     *  plus qu'un accès direct au document ne montrerait déjà. */
    private boolean estVisibleSansElevation(Document doc, User user)
    {
        return doc.getAccess() == TypeAccess.PUBLIC || estMembre(doc, user);
    }

    private boolean estMembre(Document doc, User user)
    {
        return doc.getGroupe() != null && doc.getGroupe().getMembres().stream()
            .anyMatch(m -> m.getId().equals(user.getId()));
    }

    private List<DocumentStatus> statutsExclus(boolean excludeCorbeille)
    {
        return excludeCorbeille
            ? List.of(DocumentStatus.DELETED, DocumentStatus.CORBEILLE)
            : List.of(DocumentStatus.DELETED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Transparence obligatoire — documents privés inclus hors appartenance
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Notification OBLIGATOIRE (jamais optionnelle, jamais un simple journal
     * d'audit que personne ne consulte) : chaque membre d'un groupe d'accès
     * dont un document a été inclus sans qu'il/elle en soit membre — et
     * chaque admin_uo ayant autorité sur l'UO concernée — est averti au
     * moment même du déclenchement, avec le motif déclaré.
     */
    private void notifierDocumentsPrivesInclus(List<Document> documentsPrives, User admin, String motif)
    {
        Map<UUID, User> destinataires = new LinkedHashMap<>();

        for (Document doc : documentsPrives)
        {
            if (doc.getGroupe() != null)
            {
                for (User membre : doc.getGroupe().getMembres())
                {
                    destinataires.putIfAbsent(membre.getId(), membre);
                }
            }
        }

        Set<Long> uoConcernees = documentsPrives.stream()
            .map(Document::getUniteOrganisationnelle)
            .filter(Objects::nonNull)
            .map(UniteOrganisationnelle::getId)
            .collect(Collectors.toSet());

        for (Long uoId : uoConcernees)
        {
            for (User adminUo : uniteOrganisationnelleService.getAdminUOAvecAutoriteSur(uoId))
            {
                destinataires.putIfAbsent(adminUo.getId(), adminUo);
            }
        }

        String message = documentsPrives.size() + " document(s) privé(s) concernant votre périmètre ont été "
            + "inclus dans un export déclenché par " + admin.getEmail() + " (motif : " + motif + ").";

        notificationService.notifier(destinataires.values(), NotificationType.DOCUMENT_INCLUS_DANS_EXPORT, message);
    }
}
