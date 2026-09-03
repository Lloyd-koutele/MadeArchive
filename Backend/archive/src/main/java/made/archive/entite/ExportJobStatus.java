package made.archive.entite;

/** Cycle de vie d'un {@link ExportJob} — voir DocumentExportService. */
public enum ExportJobStatus
{
    EN_ATTENTE,
    EN_COURS,
    PRET,
    ECHEC
}
