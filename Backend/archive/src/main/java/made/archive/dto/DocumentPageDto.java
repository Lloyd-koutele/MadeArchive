package made.archive.dto;
 
import lombok.Builder;
import lombok.Data;
 
import java.util.Collections;
import java.util.List;
 
@Data
@Builder
public class DocumentPageDto
{
    private List<DocumentListItemDto> content;
    private int  page;
    private int  size;
    private long totalElements;
    private int  totalPages;
 
    /** Retourne une page vide (utile quand Meilisearch ne trouve rien). */
    public static DocumentPageDto empty(int page, int size)
    {
        return DocumentPageDto.builder()
            .content(Collections.emptyList())
            .page(page)
            .size(size)
            .totalElements(0)
            .totalPages(0)
            .build();
    }
}
