package made.archive.util;

import made.archive.entite.Document;

/**
 * Calcule le libellé de version affiché ("Version 1", "Version 2"... ou
 * "Final") — centralisé ici pour éviter de dupliquer la règle partout où un
 * Document est converti en DTO (DocumentService, DocumentAccessService,
 * MeilisearchService...).
 */
public final class DocumentVersionLabels
{
    private DocumentVersionLabels() {}

    /**
     * null si le document n'a jamais été versionné (v1 seule dans sa chaîne)
     * — pas de badge dans ce cas. Sinon "Version N" ou "Final".
     */
    public static String compute(Document doc)
    {
        Long version = doc.getVersion();
        if (version == null)
        {
            return null;
        }
        if (version <= 1 && doc.isDerniereVersion())
        {
            return null;
        }
        return doc.isDerniereVersion() ? "Final" : "Version " + version;
    }
}
