package made.archive.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class AuditLogPageDto
{
    private List<AuditLogDto> content;
    private int  page;
    private int  size;
    private long totalElements;
    private int  totalPages;

    public static AuditLogPageDto empty(int page, int size)
    {
        return AuditLogPageDto.builder()
            .content(Collections.emptyList())
            .page(page)
            .size(size)
            .totalElements(0)
            .totalPages(0)
            .build();
    }
}
