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

    /** Durée de rétention (en années) configurée sur le TYPE de ce document,
     *  null si aucune limite — voir DocumentDetailDto.retentionYearsType
     *  (même usage : annoncer la nouvelle échéance avant de restaurer, depuis
     *  la corbeille, un document dont retentionUntil est déjà dépassé). */
    private Long retentionYearsType;

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

    /**
     * true si l'utilisateur peut supprimer CE document définitivement MAINTENANT : il est en corbeille
     * parce qu'il est arrivé en FIN DE VIE avec un sort final (CONSERVER/TRIER) qui exclut la purge
     * automatique, et son élimination n'est pas bloquée. Motif obligatoire à la saisie.
     */
    private boolean peutSupprimerDefinitivement;

    /** Pourquoi le document est en corbeille (ERREUR_ARCHIVAGE, SUPPRESSION_LEGALE, AUTRE, FIN_DE_VIE) —
     *  null hors corbeille, ou pour une suppression antérieure aux motifs ("non renseigné"). */
    private String motifSuppression;
    private String commentaireSuppression;

    /** true si le SYSTÈME supprimera ce document tout seul à l'échéance (suppressionPrevueLe) — faux pour
     *  un document de conservation permanente, qui attend une décision de l'éditeur. */
    private boolean suppressionAutomatique;

    /** L'éditeur a bloqué la suppression automatique (motif/auteur dans le journal du document). */
    private boolean eliminationBloquee;
    private String blocageMotif;

    /** L'utilisateur peut bloquer / débloquer la suppression automatique de CE document. */
    private boolean peutBloquerElimination;
    private boolean peutDebloquerElimination;
}
