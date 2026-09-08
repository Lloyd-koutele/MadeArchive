package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Détail d'un projet, avec la checklist des types de documents attendus
 * (informatif — voir Projet.typesDocumentsAttendus).
 */
@Data
@Builder
public class ProjetDetailDto
{
    private Long id;
    private String nom;
    private String description;
    private Long uoId;
    private String uoNom;
    private String creePar;
    private LocalDateTime createAt;
    private List<TypeAttenduDto> typesAttendus;

    /** "PUBLIC" ou "PRIVE". */
    private String access;

    /** true si le demandeur courant est un EDITOR de l'UO du projet — peut ajouter/retirer des types attendus. */
    private boolean peutGererTypes;

    /** true si le demandeur courant est le CRÉATEUR du projet — seul habilité à gérer les droits d'accès (GroupeAccess). */
    private boolean peutGererAcces;

    /** true si le demandeur courant peut basculer PUBLIC ↔ PRIVÉ ce projet
     *  (même autorité que peutGererTypes — voir ProjetService.peutGererProjet ;
     *  contrairement à peutGererAcces ci-dessus, reste true même si le projet
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
