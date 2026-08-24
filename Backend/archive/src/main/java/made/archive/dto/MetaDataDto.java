package made.archive.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MetaDataDto 
{
    private Long id;
    private String nom;
    private Boolean obligatoire;
}
