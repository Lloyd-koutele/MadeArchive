package made.archive.dto;

import lombok.Data;

/** Motif libre obligatoire (blocage / déblocage de la suppression d'un document en corbeille). */
@Data
public class MotifRequestDto
{
    private String motif;
}
