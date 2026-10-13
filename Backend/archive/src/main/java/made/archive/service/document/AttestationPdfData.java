package made.archive.service.document;

import java.time.LocalDateTime;
import java.util.List;


record AttestationPdfData(
    String titreDocument,
    String typeDocumentNom,
    /** Nom de l'unité organisationnelle du document (le producteur / fonds) ; null si le document n'en a pas. */
    String institution,
    LocalDateTime dateArchivage,
    String pdfaSha256,
    List<MetaEntry> metadonnees,
    String uploadeurNomComplet,
    String uploadeurEmail,
    String uploadeurTelephone,
    String lienPublic
)
{
    record MetaEntry(String label, String valeur) { }
}
