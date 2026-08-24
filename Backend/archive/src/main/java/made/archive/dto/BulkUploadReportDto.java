package made.archive.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class BulkUploadReportDto
{
    private int total;
    private int success;
    private int failed;
    private List<BulkUploadItemResultDto> details;
}