package made.archive.util;

import made.archive.entite.Document;
public final class DocumentVersionLabels
{
    private DocumentVersionLabels() {}

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
