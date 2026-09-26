package made.archive.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Requête de création de dossier.
 */
@Data
public class DossierDto
{
    private String nom;
    private Long uoId;

    /** null = dossier racine de l'UO ; sinon dossier parent (même UO obligatoire). */
    private Long parentId;

    /** Types de documents attendus (informatif — voir Dossier.typesDocumentsAttendus). Optionnel. */
    private List<Long> typeDocumentIds;

    /**
     * "PUBLIC" (défaut si absent) ou "PRIVE" — IGNORÉ si le parent est déjà
     * PRIVÉ : l'enfant est alors automatiquement forcé PRIVÉ (voir
     * DossierService.creerDossier, invariant de confidentialité).
     */
    private String access;

    /**
     * Membres initiaux du groupe d'accès si le dossier créé est PRIVE — le
     * créateur y est ajouté automatiquement, inutile de l'inclure ici. Sous
     * un parent PRIVÉ, ces membres s'AJOUTENT à ceux hérités du parent
     * (jamais à la place) — voir DossierService.creerDossier.
     */
    private List<UUID> groupeMembresIds;
}
