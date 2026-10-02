package made.archive.dto;

import java.time.Instant;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/** Une page du journal de cycle de vie d'un document + état du dernier contrôle d'intégrité. */
@Data
@Builder
public class DocumentJournalDto
{
    private List<AuditLogDto> content;
    private int  page;
    private int  size;
    private long totalElements;
    private int  totalPages;

    /** Dernier contrôle d'intégrité (fixité) de ce document — les contrôles RÉUSSIS ne sont pas
     *  journalisés un par un (un par document et par jour noierait le journal), seul leur dernier
     *  résultat est conservé ; les échecs, eux, apparaissent dans la liste. Null si jamais contrôlé. */
    private Instant dernierControleLe;
    private String  dernierControleResultat;
}
