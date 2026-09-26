package made.archive.service.document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.AppProperties;
import made.archive.dto.AttestationDto;
import made.archive.entite.Attestation;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.AttestationRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttestationService
{
    /** Durée de vie d'une attestation — voir Javadoc de l'entité Attestation. */
    private static final long VALIDITE_JOURS = 2;

    private final AttestationRepository attestationRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final AttestationPdfService attestationPdfService;
    private final AuditLogService auditLogService;
    private final AppProperties appProperties;

    /**
     * Crée toujours une NOUVELLE attestation indépendante — jamais de
     * réutilisation, même si d'autres sont encore valides pour ce même
     * document (voir Javadoc de l'entité Attestation).
     */
    @Transactional
    public AttestationDto genererNouvelle(UUID documentId, UserDetails userDetails)
    {
        Document doc = documentService.resolveDocumentPourAttestation(documentId, userDetails);

        User user = userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));

        LocalDateTime maintenant = LocalDateTime.now();
        Attestation attestation = new Attestation();
        attestation.setToken(genererToken());
        attestation.setDocument(doc);
        attestation.setGenerePar(user);
        attestation.setGenereLe(maintenant);
        attestation.setExpireLe(maintenant.plusDays(VALIDITE_JOURS));
        attestationRepository.save(attestation);

        auditLogService.log(user, AuditAction.ATTESTATION_GENEREE, AuditCible.DOCUMENT,
            documentId.toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Attestation d'archivage générée pour \"" + doc.getTitre() + "\" par " + user.getEmail(),
            true);

        return versDto(attestation);
    }

    /**
     * Purge quotidienne des attestations expirées (voir
     * AttestationExpirationScheduler) — trace conservée dans le journal
     * d'audit avant chaque suppression.
     */
    @Transactional
    public void purgerAttestationsExpirees()
    {
        List<Attestation> expirees = attestationRepository.findByExpireLeBefore(LocalDateTime.now());
        for (Attestation attestation : expirees)
        {
            purgerAvecLog(attestation, "expiration du délai de " + VALIDITE_JOURS + " jours");
        }
    }

    private void purgerAvecLog(Attestation attestation, String raison)
    {
        Document doc = attestation.getDocument();
        auditLogService.log(null, AuditAction.ATTESTATION_PURGEE, AuditCible.DOCUMENT,
            doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Attestation d'archivage purgée pour \"" + doc.getTitre() + "\" — " + raison,
            true);
        attestationRepository.delete(attestation);
    }

    @Transactional(readOnly = true)
    public byte[] genererPdfPourToken(String token)
    {
        Attestation attestation = resolveAttestationValide(token);
        Document doc = attestation.getDocument();

        // Le QR/lien imprimé sur l'attestation mène au DOCUMENT ORIGINAL
        // (page /attestation/{token}/document, voir AttestationDocumentPublique
        // côté frontend), jamais à cette attestation elle-même — décision
        // produit assumée : même un document PRIVÉ reste accessible via ce
        // jeton, exactement comme l'attestation l'est déjà (voir
        // AttestationPublicController). Le lien "/attestation/{token}" seul
        // (sans /document) reste réservé à la consultation de CETTE
        // attestation, utilisé par ex. par le bouton "Ouvrir" après génération
        // côté éditeur (voir AttestationDto.url) — jamais confondu avec celui-ci.
        String lien = "https://" + appProperties.getAppDomain() + "/attestation/" + token + "/document";

        AttestationPdfData data = new AttestationPdfData(
            doc.getTitre(),
            doc.getTypeDocument().getNom(),
            doc.getCreateAt(),
            doc.getPdfaSha256(),
            doc.getData().stream()
                .map(dt -> new AttestationPdfData.MetaEntry(
                    dt.getMetaData() != null ? dt.getMetaData().getNom() : "Métadonnée",
                    dt.getValeur()))
                .toList(),
            doc.getUploadedBy().getPrenom() + " " + doc.getUploadedBy().getNom(),
            doc.getUploadedBy().getEmail(),
            doc.getUploadedBy().getTelephone(),
            lien
        );

        auditLogService.log(null, AuditAction.ATTESTATION_CONSULTEE_PUBLIQUEMENT,
            AuditCible.DOCUMENT, doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Consultation publique de l'attestation du document \"" + doc.getTitre() + "\"",
            true);

        return attestationPdfService.genererPdf(data);
    }

    /**
     * Résout le document ORIGINAL derrière un jeton d'attestation valide —
     * pour AttestationPublicController (/{token}/document/view|download).
     * AUCUNE vérification de confidentialité ici (voir DocumentService.
     * lireBytesPdfAViaAttestation) : décision produit assumée, le jeton
     * donne accès au fichier même pour un document PRIVÉ.
     */
    @Transactional(readOnly = true)
    public Document resolveDocumentPourToken(String token)
    {
        Attestation attestation = resolveAttestationValide(token);
        Document doc = attestation.getDocument();

        auditLogService.log(null, AuditAction.DOCUMENT_VERIFICATION_PUBLIQUE,
            AuditCible.DOCUMENT, doc.getId().toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Consultation publique du document original \"" + doc.getTitre() + "\" via son attestation",
            true);

        return doc;
    }

    /** Lève si le jeton est inconnu, le document supprimé, ou l'attestation expirée. */
    private Attestation resolveAttestationValide(String token)
    {
        Attestation attestation = attestationRepository.findByToken(token)
            .orElseThrow(() -> new BusinessException("Attestation introuvable"));

        Document doc = attestation.getDocument();
        if (doc.getStatus() == DocumentStatus.DELETED)
        {
            throw new BusinessException("Ce document a été supprimé");
        }
        if (attestation.getExpireLe().isBefore(LocalDateTime.now()))
        {
            throw new BusinessException("Cette attestation a expiré");
        }

        return attestation;
    }

    private AttestationDto versDto(Attestation attestation)
    {
        return AttestationDto.builder()
            .token(attestation.getToken())
            .url("https://" + appProperties.getAppDomain() + "/attestation/" + attestation.getToken())
            .build();
    }

    private String genererToken()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
