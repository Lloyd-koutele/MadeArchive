package made.archive.service.document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Données déjà extraites (aucune entité JPA) nécessaires pour générer le PDF
 * d'une attestation — voir AttestationPdfService. Construit par
 * AttestationService pendant que la transaction est encore ouverte, pour ne
 * jamais risquer un accès à un champ lazy non initialisé une fois sorti de la
 * transaction (LazyInitializationException).
 */
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
