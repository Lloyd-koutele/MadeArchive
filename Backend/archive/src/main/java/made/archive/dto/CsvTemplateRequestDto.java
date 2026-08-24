package made.archive.dto;

import lombok.Data;
import java.util.List;

@Data
public class CsvTemplateRequestDto
{
    private List<Long> typeDocumentIds;
    // "csv" ou "excel"
    private String format;
}