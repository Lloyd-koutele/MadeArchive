package made.archive.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * Filtre de recherche pour les documents accessibles à un utilisateur.
 * Tous les champs sont optionnels sauf page et size.
 */
@Data
public class DocumentAccessFilterDto
{
    /** Recherche par titre (LIKE %titre%) */
    private String    titre;

    /** Filtre par type de document (ID) */
    private Long      typeDocumentId;

    /**
     * Restreint à une UO précise (ex. navigation dans l'arbre côté Admin/Admin_UO)
     * — reste borné par le périmètre déjà autorisé pour l'appelant (voir
     * DocumentAccessService.buildSpecification) : demander une UO hors de son
     * périmètre renvoie simplement zéro résultat, jamais une fuite. null = pas
     * de restriction supplémentaire, tout le périmètre autorisé est renvoyé.
     */
    private Long      uoId;

    /** Filtre par accès : "PUBLIC", "PRIVE", ou null pour les deux */
    private String    access;

    /** Date de début de la plage d'archivage (inclusive) */
    private LocalDate dateDebut;

    /** Date de fin de la plage d'archivage (inclusive) */
    private LocalDate dateFin;

    /** Filtre par statut : "ACTIVE", "PENDING", "CORRUPTED", etc. */
    private String    statut;

    /** Numéro de page (1-based) */
    private int page = 1;

    /** Taille de page (max 50) */
    private int size = 10;
}