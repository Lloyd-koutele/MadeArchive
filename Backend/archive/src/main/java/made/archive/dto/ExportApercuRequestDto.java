package made.archive.dto;

import java.util.List;

import lombok.Data;

/**
 * Requête d'aperçu du périmètre d'un export (voir
 * DocumentExportController) — équivalent de --list-documents dans le
 * script export_uo_documents.py, mais AVANT toute décision de génération.
 */
@Data
public class ExportApercuRequestDto
{
    private List<Long> uoIds;
    private boolean excludeCorbeille;

    /** Réservé à ROLE_ADMIN — voir DocumentExportService.verifierEligibiliteElevation. */
    private boolean includePriveNonMembre;
}
