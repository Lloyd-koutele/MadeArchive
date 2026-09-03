package made.archive.service.document;

import java.time.LocalDateTime;
import java.util.List;


record AttestationPdfData(
    String titreDocument,
    String typeDocumentNom,
    LocalDateTime dateArchivage,
    List<MetaEntry> metadonnees,
    String uploadeurNomComplet,
    String uploadeurEmail,
    String uploadeurTelephone,
    String lienPublic
)
{
    record MetaEntry(String label, String valeur) { }
}
