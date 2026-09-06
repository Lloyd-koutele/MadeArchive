package made.archive.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import made.archive.entite.TypeAccess;

/**
 * Projection dédiée à DocumentExportGenerationService — ne charge QUE les
 * colonnes réellement nécessaires à la génération du ZIP, contrairement à
 * `Document` entier (via JOIN FETCH ou findAllById), dont la matérialisation
 * complète touche `horodatageToken` (@Lob). Postgres exige une transaction
 * explicite (pas auto-commit) pour streamer un Large Object, ce que ce
 * traitement @Async n'a jamais — "Large Objects may not be used in
 * auto-commit mode", constaté en conditions réelles. Ne jamais élargir cette
 * projection pour inclure des champs @Lob.
 */
public record DocumentExportRow(
    UUID id,
    String titre,
    String storageKey,
    TypeAccess access,
    LocalDateTime createAt,
    Long uoId,
    String uoNom,
    String typeDocumentNom,
    String projetNom
) {}
