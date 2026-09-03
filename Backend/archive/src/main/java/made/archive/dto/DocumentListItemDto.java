package made.archive.dto;
 
import lombok.Builder;
import lombok.Data;
 
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
 
@Data
@Builder
public class DocumentListItemDto
{
    private UUID          documentId;
    private String        titre;
    private Long          typeDocumentId;
    private String        typeDocumentNom;
    private String        status;           // ACTIVE, PENDING, CORRUPTED...
    private String        access;           // PUBLIC, PRIVE
    private LocalDate     retentionUntil;
    private LocalDateTime createAt;

    /**
     * Statut d'avant corbeille (ex. "CORRUPTED") — non-null uniquement
     * quand status == "CORBEILLE" (voir Document.statutAvantCorbeille).
     * Sert au badge de distinction dans la vue corbeille : un document
     * corrompu envoyé à la corbeille reste identifiable comme tel.
     */
    private String statutAvantCorbeille;

    /** Date de purge définitive prévue — non-null uniquement quand status == "CORBEILLE". */
    private LocalDate suppressionPrevueLe;

    /**
     * "Version 1", "Version 2"... ou "Final" pour la version actuelle d'une
     * chaîne. null si ce document n'a jamais été versionné (pas de badge).
     */
    private String versionLabel;

    /**
     * true si l'utilisateur consultant peut envoyer/restaurer CE document
     * précis vers/depuis la corbeille (éditeur ayant accès au document —
     * voir DocumentService.envoyerCorbeille). Permet d'afficher l'action
     * rapide directement dans la liste, sans ouvrir le détail.
     */
    private boolean peutGererCorbeille;
}
