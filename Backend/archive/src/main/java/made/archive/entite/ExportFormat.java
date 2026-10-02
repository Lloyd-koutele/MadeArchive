package made.archive.entite;

/** Format de sortie d'un export administratif (voir ExportJob.format). */
public enum ExportFormat
{
    /** ZIP arborescent + manifest.csv + preuves (.tsr/.sig) — voir DocumentExportGenerationService. */
    ZIP_CSV,

    /** Paquet SEDA 2.1 (SIP) : manifest.xml validé contre le XSD officiel + content/ — voir SedaExportGenerationService. */
    SEDA
}
