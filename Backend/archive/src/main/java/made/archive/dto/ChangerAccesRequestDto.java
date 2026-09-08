package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import made.archive.entite.TypeAccess;

/**
 * Requête de bascule PUBLIC ↔ PRIVÉ après coup, pour un document déjà
 * archivé (DocumentService.modifierAcces) ou un projet déjà créé
 * (ProjetService.modifierAcces) — même DTO pour les deux, la logique de
 * bascule étant symétrique (voir leur Javadoc).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangerAccesRequestDto
{
    private TypeAccess access;

    /**
     * Utilisé seulement quand access passe à PRIVE : membres initiaux du
     * nouveau groupe d'accès, en plus de l'auteur de la demande (toujours
     * ajouté automatiquement, même convention qu'à la création — voir
     * DocumentUploadDto.groupeMembresIds). Ignoré si access passe à PUBLIC.
     */
    private List<UUID> groupeMembresIds;
}
