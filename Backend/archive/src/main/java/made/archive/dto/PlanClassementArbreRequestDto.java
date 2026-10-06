package made.archive.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Création ou mise à jour d'UNE activité avec toute sa descendance, en un seul appel — même principe que
 * l'organigramme de création des emplacements physiques. Les codes ne sont jamais envoyés : le serveur les génère.
 */
@Data
public class PlanClassementArbreRequestDto
{
    /** Création uniquement : UO du plan. */
    private Long uoId;
    /** Création uniquement : activité sous laquelle accrocher la nouvelle racine (null = racine du plan). */
    private Long parentId;
    private Noeud node;

    @Data
    public static class Noeud
    {
        /** Absent = nouvelle activité ; renseigné = activité existante (mise à jour uniquement). */
        private Long id;
        private String libelle;
        private List<Noeud> children = new ArrayList<>();
    }
}
