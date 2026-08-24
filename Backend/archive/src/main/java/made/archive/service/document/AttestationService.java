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

/**
 * Attestation d'archivage — voir Attestation (entité) et AttestationPdfService
 * (génération du PDF). Le PDF n'est jamais stocké : reconstruit à chaque
 * consultation depuis les données actuelles du document, seul le jeton
 * (token↔document) est persistant. Deux points d'entrée :
 *   - genererOuRecuperer : authentifié, réservé à qui a normalement accès au
 *     document (mêmes règles que consulter/télécharger — voir
 *     DocumentService.resolveDocumentPourAttestation) ; idempotent, réutilise
 *     le jeton existant plutôt que d'en créer un nouveau à chaque appel ;
 *   - genererPdfPourToken : PUBLIC, sans authentification, seulement à partir
 *     du jeton opaque (jamais l'UUID réel du document) — ne change jamais le
 *     statut d'accès du document, donne juste un accès en lecture/
 *     téléchargement du PDF/A à quiconque possède le lien.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttestationService
{
    private final AttestationRepository attestationRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final AttestationPdfService attestationPdfService;
    private final AuditLogService auditLogService;
    private final AppProperties appProperties;

    @Transactional
    public AttestationDto genererOuRecuperer(UUID documentId, UserDetails userDetails)
    {
        // Vérifie l'accès au document AVANT toute chose — mêmes règles que
        // consulter/télécharger (uploadeur, ou accès normal si sain, ou
        // éditeur+accès si corrompu). Lève BusinessException sinon.
        Document doc = documentService.resolveDocumentPourAttestation(documentId, userDetails);

        var existante = attestationRepository.findByDocumentId(documentId);
        if (existante.isPresent())
        {
            return versDto(existante.get(), true);
        }

        User user = userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));

        Attestation attestation = new Attestation();
        attestation.setToken(genererToken());
        attestation.setDocument(doc);
        attestation.setGenerePar(user);
        attestation.setGenereLe(LocalDateTime.now());
        attestationRepository.save(attestation);

        auditLogService.log(user, AuditAction.ATTESTATION_GENEREE, AuditCible.DOCUMENT,
            documentId.toString(),
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null,
            "Attestation d'archivage générée pour \"" + doc.getTitre() + "\" par " + user.getEmail(),
            true);

        return versDto(attestation, false);
    }

    /**
     * PUBLIC — aucune vérification d'accès autre que la possession du jeton.
     * Le document reste dans son état de confidentialité normal ; ce jeton
     * est simplement un accès dérivé qui ne le modifie jamais.
     */
    @Transactional(readOnly = true)
    public byte[] genererPdfPourToken(String token)
    {
        Attestation attestation = attestationRepository.findByToken(token)
            .orElseThrow(() -> new BusinessException("Attestation introuvable"));

        Document doc = attestation.getDocument();
        if (doc.getStatus() == DocumentStatus.DELETED)
        {
            throw new BusinessException("Ce document a été supprimé");
        }

        String lien = appProperties.getFrontendUrl() + "/attestation/" + token;

        AttestationPdfData data = new AttestationPdfData(
            doc.getTitre(),
            doc.getTypeDocument().getNom(),
            doc.getCreateAt(),
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

    private AttestationDto versDto(Attestation attestation, boolean dejaExistante)
    {
        return AttestationDto.builder()
            .token(attestation.getToken())
            .url(appProperties.getFrontendUrl() + "/attestation/" + attestation.getToken())
            .dejaExistante(dejaExistante)
            .build();
    }

    private String genererToken()
    {
        // UUID sans tirets : 32 caractères hexadécimaux, ~122 bits d'entropie —
        // pas devinable par force brute, et distinct de l'UUID réel du document
        // (jamais le même identifiant exposé publiquement).
        return UUID.randomUUID().toString().replace("-", "");
    }
}
