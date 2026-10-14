package made.archive.service.integrite;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.dto.RapportVerificationDto;
import made.archive.dto.RapportVerificationDto.Etape;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.exception.CleChiffrementException;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditChainService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.DocumentService;
import made.archive.service.document.HashService;
import made.archive.service.integrite.PreuveIntegriteService.Constat;
import made.archive.service.integrite.PreuveIntegriteService.ExamenPreuves;
import made.archive.service.integrite.PreuveIntegriteService.Point;
import made.archive.service.storage.StorageService;

/**
 * Vérification instantanée d'un document, à la demande de quiconque peut le consulter : relit le PDF/A réellement
 * stocké, recalcule son empreinte, la confronte aux preuves indépendantes de la base (signature du HSM, jeton
 * d'horodatage RFC 3161, manifeste verrouillé — arbitre {@link PreuveIntegriteService}) puis contrôle le journal
 * d'audit chaîné. Ne modifie JAMAIS le document (ni statut, ni résultat de fixité) et n'alerte personne : c'est un
 * rapport, la décision de traiter une anomalie reste au contrôle d'intégrité et à l'administration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationDocumentService
{
    private final DocumentService documentService;
    private final StorageService storageService;
    private final DocumentEncryptionService encryption;
    private final HashService hashService;
    private final PreuveIntegriteService preuves;
    private final HorodatageVerificationService jetons;
    private final AuditChainService auditChainService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    @Transactional
    public RapportVerificationDto verifier(UUID documentId, UserDetails userDetails)
    {
        User user = userRepository.findByEmail(userDetails.getUsername())
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable"));
        // Mêmes règles d'accès que la consultation : une exception est levée si le document n'est pas visible.
        Document doc = documentService.resolveDocumentPourAttestation(documentId, userDetails);
        Long uoId = doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getId() : null;

        List<Etape> etapes = new ArrayList<>();
        RapportVerificationDto.RapportVerificationDtoBuilder rapport = RapportVerificationDto.builder()
            .documentId(doc.getId()).titre(doc.getTitre()).version(doc.getVersion())
            .verifieLe(Instant.now()).verifiePar(user.getEmail())
            .empreinteEnregistree(doc.getPdfaSha256());

        String verdict;
        String conclusion;
        boolean degrade = false;

        // ÉTAPE 1 — l'empreinte enregistrée n'a pas été modifiée (avant toute lecture du fichier).
        etapes.add(etapeReference(preuves.authentifierEmpreinte(doc)));

        byte[] clair = lireFichier(doc, etapes);
        if (clair == null)
        {
            verdict = etapes.stream().anyMatch(e -> "ECHEC".equals(e.getStatut()) && "DECHIFFREMENT".equals(e.getCode()))
                ? RapportVerificationDto.FICHIER_ALTERE : RapportVerificationDto.NON_VERIFIABLE;
            conclusion = RapportVerificationDto.FICHIER_ALTERE.equals(verdict)
                ? "Le fichier stocké ne peut plus être déchiffré : il a été altéré."
                : "Le fichier n'a pas pu être lu : la vérification ne permet pas de conclure.";
        }
        else
        {
            String recalculee = hashService.calculateFromBytes(clair);
            rapport.empreinteRecalculee(recalculee);
            boolean memeEmpreinte = recalculee.equals(doc.getPdfaSha256());
            etapes.add(Etape.builder().code("EMPREINTE").libelle("Étape 2 — Empreinte SHA-256 du fichier stocké")
                .statut(memeEmpreinte ? "OK" : "ECHEC")
                .detail(memeEmpreinte ? "Identique à l'empreinte enregistrée à l'archivage."
                    : "Différente de l'empreinte enregistrée en base (enregistrée : " + doc.getPdfaSha256()
                        + ", recalculée : " + recalculee + ").").build());

            ExamenPreuves examen = preuves.examinerPreuves(doc);
            for (Point p : examen.points())
            {
                etapes.add(Etape.builder().code(p.code()).libelle(libelleDe(p.code())).statut(p.gravite().name())
                    .detail(p.texte()).build());
            }
            jetons.dateDuJeton(doc.getHorodatageToken()).ifPresent(rapport::horodatageAttesteLe);

            Constat constat = preuves.evaluer(doc, recalculee);
            degrade = constat.degrade();
            switch (constat.verdict())
            {
                case OK ->
                {
                    verdict = RapportVerificationDto.CONFORME;
                    conclusion = degrade
                        ? "Fichier conforme à l'empreinte enregistrée, mais aucune preuve indépendante n'a pu être consultée "
                            + "(ancien document non signé ou clé de confiance absente) : vérification dégradée."
                        : "Le fichier est identique à celui archivé et ses preuves indépendantes concordent.";
                }
                case PREUVE_ALTEREE ->
                {
                    verdict = RapportVerificationDto.PREUVES_ALTEREES;
                    conclusion = constat.raison();
                }
                default ->
                {
                    verdict = RapportVerificationDto.FICHIER_ALTERE;
                    conclusion = constat.raison();
                }
            }
        }

        etapes.add(etapeJournal(uoId));

        auditLogService.log(user, AuditAction.FIXITY_CHECK_DEMANDE, AuditCible.DOCUMENT, doc.getId().toString(), uoId,
            "Vérification instantanée du document \"" + doc.getTitre() + "\" : " + verdict,
            RapportVerificationDto.CONFORME.equals(verdict));

        return rapport.verdict(verdict).conclusion(conclusion).degrade(degrade).etapes(etapes).build();
    }

    private Etape etapeReference(PreuveIntegriteService.Reference reference)
    {
        String libelle = "Étape 1 — Authenticité de l'empreinte enregistrée";
        return switch (reference.etat())
        {
            case AUTHENTIQUE -> Etape.builder().code("REFERENCE").libelle(libelle).statut("OK")
                .detail("L'empreinte enregistrée est attestée par une preuve qu'un accès à la base ne permet pas de refaire.").build();
            case ALTEREE -> Etape.builder().code("REFERENCE").libelle(libelle).statut("ECHEC")
                .detail("L'empreinte enregistrée a été modifiée : " + String.join(" ; ", reference.faits()) + ".").build();
            case NON_VERIFIABLE -> Etape.builder().code("REFERENCE").libelle(libelle).statut("ALERTE")
                .detail("Aucune preuve indépendante n'a pu être consultée (ancien document non signé ou clé de confiance absente).").build();
        };
    }

    /** Lit et déchiffre le PDF/A stocké ; ajoute l'étape correspondante. null si impossible. */
    private byte[] lireFichier(Document doc, List<Etape> etapes)
    {
        byte[] chiffre;
        try (InputStream flux = storageService.download(doc.getStorageKey()))
        {
            if (flux == null)
            {
                etapes.add(Etape.builder().code("FICHIER").libelle("Lecture du fichier archivé").statut("ECHEC")
                    .detail("Fichier introuvable dans le stockage.").build());
                return null;
            }
            chiffre = flux.readAllBytes();
        }
        catch (Exception e)
        {
            log.warn("[Verification] Lecture impossible du document {} : {}", doc.getId(), e.getMessage());
            etapes.add(Etape.builder().code("FICHIER").libelle("Lecture du fichier archivé").statut("ECHEC")
                .detail("Le stockage est injoignable ou le fichier illisible.").build());
            return null;
        }
        etapes.add(Etape.builder().code("FICHIER").libelle("Lecture du fichier archivé").statut("OK")
            .detail(chiffre.length + " octets lus dans le stockage objet.").build());
        try
        {
            byte[] clair = encryption.decrypt(chiffre);
            etapes.add(Etape.builder().code("DECHIFFREMENT").libelle("Déchiffrement AES-256-GCM").statut("OK")
                .detail("Le contenu est authentique (balise GCM valide).").build());
            return clair;
        }
        catch (CleChiffrementException e)
        {
            etapes.add(Etape.builder().code("DECHIFFREMENT").libelle("Déchiffrement AES-256-GCM").statut("ALERTE")
                .detail("Clé de chiffrement absente ou différente de celle des archives : aucune conclusion sur le fichier.").build());
            return null;
        }
        catch (Exception e)
        {
            etapes.add(Etape.builder().code("DECHIFFREMENT").libelle("Déchiffrement AES-256-GCM").statut("ECHEC")
                .detail("Échec du déchiffrement : contenu altéré (balise GCM invalide).").build());
            return null;
        }
    }

    /**
     * Le chaînage traverse toute la chaîne (une vérification partielle n'aurait pas de sens cryptographique) ; seules
     * les ruptures de l'UO du document sont détaillées, les autres sont signalées sans détail.
     */
    private Etape etapeJournal(Long uoId)
    {
        try
        {
            ChaineAuditVerificationDto chaine = auditChainService.verifierChaine(uoId == null ? Set.of() : Set.of(uoId));
            boolean ok = chaine.isChaineIntacte() && !chaine.isRupturesHorsPerimetre();
            StringBuilder detail = new StringBuilder();
            detail.append(chaine.getNombreEntreesChainees()).append(" entrée(s) chaînée(s) contrôlée(s). ");
            if (chaine.getRuptures() != null && !chaine.getRuptures().isEmpty())
            {
                detail.append(chaine.getRuptures().size()).append(" rupture(s) dans l'unité de ce document. ");
            }
            if (chaine.isRupturesHorsPerimetre())
            {
                detail.append("Des ruptures existent ailleurs dans le journal. ");
            }
            if (chaine.getAnomaliesGlobales() != null && !chaine.getAnomaliesGlobales().isEmpty())
            {
                detail.append(chaine.getAnomaliesGlobales().size()).append(" anomalie(s) globale(s). ");
                ok = false;
            }
            if (ok)
            {
                detail.append("Aucune rupture détectée.");
            }
            if (chaine.getDernierScellementDate() != null)
            {
                detail.append(" Dernier scellement horodaté : ").append(chaine.getDernierScellementDate()).append('.');
            }
            return Etape.builder().code("JOURNAL").libelle("Chaînage du journal d'audit")
                .statut(ok ? "OK" : "ECHEC").detail(detail.toString().trim()).build();
        }
        catch (Exception e)
        {
            log.warn("[Verification] Contrôle du journal impossible : {}", e.getMessage());
            return Etape.builder().code("JOURNAL").libelle("Chaînage du journal d'audit").statut("ALERTE")
                .detail("Le contrôle du journal n'a pas pu être effectué.").build();
        }
    }

    private static String libelleDe(String code)
    {
        return switch (code)
        {
            case "SIGNATURE_HASH" -> "Signature du hash PDF/A (clé du SoftHSM)";
            case "ENREGISTREMENT" -> "Sceau de l'enregistrement en base";
            case "HORODATAGE" -> "Horodatage RFC 3161";
            case "MANIFESTE" -> "Manifeste verrouillé (bucket de preuves)";
            default -> code;
        };
    }
}
