package made.archive.dto;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TypeDocumentDto 
{
    private Long id;
    private String nom;
    private List<MetaDataDto> metaData;
    private UUID userId;
    private Long retentionYears;
    private Long periodGrace;
    private List <DocumentDetailDto> documents;  
    private Long uoId;
    private String uoNom; 
}
