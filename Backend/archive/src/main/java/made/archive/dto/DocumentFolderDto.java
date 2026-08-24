package made.archive.dto;
 
import lombok.Builder;
import lombok.Data;
 
@Data
@Builder
public class DocumentFolderDto
{
    private Long   typeDocumentId;
    private String typeDocumentNom;
    private Long   count;
}
