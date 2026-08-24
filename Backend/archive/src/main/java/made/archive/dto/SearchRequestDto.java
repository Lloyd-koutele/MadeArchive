package made.archive.dto;

import lombok.Data;

@Data
public class SearchRequestDto
{
    private String query;
    private Long typeDocumentId; // null = recherche globale
    private int page = 1;
    private int hitsPerPage = 20;
}