package made.archive.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record MembreUODto(
    UUID userId,
    String nom,
    String prenom,
    String email,
    LocalDateTime dateAjout,
    boolean actif,
    LocalDateTime dateRetrait,
    String retireParNom
) {}