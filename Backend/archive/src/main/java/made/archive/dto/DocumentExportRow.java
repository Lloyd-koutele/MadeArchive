package made.archive.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import made.archive.entite.DocumentStatus;
import made.archive.entite.TypeAccess;

/**
 * Projection dédiée à DocumentExportGenerationService — ne charge QUE les
 * colonnes réellement nécessaires à la génération du ZIP, contrairement à
 * `Document` entier (via JOIN FETCH ou findAllById). Historiquement motivé
 * par `horodatageToken`, alors mappé en Large Object Postgres (@Lob sur un
 * byte[] — voir Document.horodatageToken) : le matérialiser exigeait une
 * transaction explicite, absente de ce traitement @Async ("Large Objects
 * may not be used in auto-commit mode", constaté en conditions réelles).
 * horodatageToken est désormais en bytea (voir schema.sql), donc cette
 * contrainte précise n'existe plus — la projection reste volontairement
 * étroite malgré tout : aucune raison de charger des colonnes binaires
 * inutiles à la génération du ZIP.
 */
public record DocumentExportRow(
    UUID id,
    String titre,
    String storageKey,
    TypeAccess access,
    DocumentStatus status,
    LocalDateTime createAt,
    Long uoId,
    String uoNom,
    String typeDocumentNom,
    String projetNom
) {}
