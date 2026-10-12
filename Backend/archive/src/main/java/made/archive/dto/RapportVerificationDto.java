package made.archive.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

/**
 * Rapport d'une vérification d'intégrité demandée à la volée sur UN document — voir
 * service.integrite.VerificationDocumentService. Contrairement au contrôle de fixité (qui met à jour le statut du
 * document et alerte l'administration), cette vérification est en lecture seule : elle ne modifie rien.
 */
@Data
@Builder
public class RapportVerificationDto
{
    public static final String CONFORME = "CONFORME";
    public static final String PREUVES_ALTEREES = "PREUVES_ALTEREES";
    public static final String FICHIER_ALTERE = "FICHIER_ALTERE";
    public static final String NON_VERIFIABLE = "NON_VERIFIABLE";

    private UUID documentId;
    private String titre;
    private Long version;
    private Instant verifieLe;
    private String verifiePar;

    /** CONFORME, PREUVES_ALTEREES (fichier sain, base réécrite), FICHIER_ALTERE ou NON_VERIFIABLE. */
    private String verdict;
    private String conclusion;

    /** true si aucune racine de confiance n'a pu être consultée : verdict fondé sur la seule empreinte stockée. */
    private boolean degrade;

    /** Empreinte SHA-256 recalculée sur le fichier réellement stocké (null si le fichier n'a pas pu être lu). */
    private String empreinteRecalculee;
    private String empreinteEnregistree;

    /** Date attestée par l'autorité d'horodatage (RFC 3161), si un jeton existe. */
    private Instant horodatageAttesteLe;

    private List<Etape> etapes;

    /** Un contrôle du rapport. statut : OK, ALERTE (non probant ou absent), ECHEC (altération), INFO. */
    @Data
    @Builder
    public static class Etape
    {
        private String code;
        private String libelle;
        private String statut;
        private String detail;
    }
}
