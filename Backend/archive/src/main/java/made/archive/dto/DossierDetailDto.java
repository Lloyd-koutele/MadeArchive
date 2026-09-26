package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Détail d'un dossier, avec la checklist des types de documents attendus
 * (informatif — voir Dossier.typesDocumentsAttendus).
 */
@Data
@Builder
public class DossierDetailDto
{
    private Long id;
    private String nom;
    private Long uoId;
    private String uoNom;
    /** null si dossier racine de l'UO. */
    private Long parentId;
    private String parentNom;
    /** Fil d'Ariane complet, ex. "Contrats / 2026" (racine exclue, voir DossierService.construireChemin). */
    private String cheminComplet;
    private String creePar;
    private LocalDateTime createAt;
    private List<TypeAttenduDto> typesAttendus;

    /** "PUBLIC" ou "PRIVE". */
    private String access;

    /** true si le demandeur courant est un EDITOR de l'UO du dossier — peut ajouter/retirer des types attendus. */
    private boolean peutGererTypes;

    /** true si le demandeur courant est le CRÉATEUR du dossier — seul habilité à gérer les droits d'accès (GroupeAccess). */
    private boolean peutGererAcces;

    /** true si le demandeur courant peut basculer PUBLIC ↔ PRIVÉ ce dossier
     *  (même autorité que peutGererTypes — voir DossierService.peutGererDossier ;
     *  contrairement à peutGererAcces ci-dessus, reste true même si le dossier
     *  est actuellement PUBLIC, pour permettre justement de le rendre privé). */
    private boolean peutModifierAcces;

    @Data
    @Builder
    public static class TypeAttenduDto
    {
        private Long typeDocumentId;
        private String nom;
        private long nombreDocuments;
        private boolean fourni;
    }
}
