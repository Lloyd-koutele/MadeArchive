package made.archive.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SearchResultDto
{
    private int totalHits;
    private int page;
    private int hitsPerPage;
    private int totalPages;
    private List<SearchResultItemDto> results;
}