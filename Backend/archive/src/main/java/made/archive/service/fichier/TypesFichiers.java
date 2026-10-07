package made.archive.service.fichier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Référentiel des types de fichiers : lesquels MadeArchive accepte, comment les nommer, et quelles extensions annoncent
 * quel type réel. Source UNIQUE, partagée par le contrôle de cohérence (ControleTypeFichierService) et la conversion
 * (LibreOfficeConversionService).
 *
 * Le type RÉEL vient toujours du contenu (Tika, signature du fichier) — jamais du nom. Une extension ne sert qu'à
 * vérifier que ce que le fichier prétend être est bien ce qu'il est.
 */
public final class TypesFichiers
{
    private TypesFichiers() {}

    public static final String PDF = "application/pdf";

    /** Formats convertis en PDF par LibreOffice (Gotenberg) — tout le reste est refusé. */
    public static final Set<String> MIMES_CONVERTIBLES = Set.of(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "text/plain",
        "text/csv",
        "image/jpeg",
        "image/png",
        "image/tiff",
        "image/bmp",
        "image/gif"
    );

    public static boolean estAutorise(String mime)
    {
        return PDF.equals(mime) || MIMES_CONVERTIBLES.contains(mime);
    }

    /** Archives : jamais acceptées, et jamais décompressées par l'application. */
    public static final Map<String, String> ARCHIVES = Map.ofEntries(
        Map.entry("application/zip", "ZIP"),
        Map.entry("application/x-zip-compressed", "ZIP"),
        Map.entry("application/java-archive", "JAR"),
        Map.entry("application/x-rar-compressed", "RAR"),
        Map.entry("application/vnd.rar", "RAR"),
        Map.entry("application/x-7z-compressed", "7-Zip"),
        Map.entry("application/x-tar", "TAR"),
        Map.entry("application/gzip", "GZIP"),
        Map.entry("application/x-gzip", "GZIP"),
        Map.entry("application/x-bzip", "BZIP"),
        Map.entry("application/x-bzip2", "BZIP2"),
        Map.entry("application/x-xz", "XZ"),
        Map.entry("application/zstd", "Zstandard"),
        Map.entry("application/x-compress", "Z"),
        Map.entry("application/x-cpio", "CPIO"),
        Map.entry("application/x-iso9660-image", "ISO"),
        Map.entry("application/vnd.android.package-archive", "APK")
    );

    /** Extension annoncée → types réels qui lui correspondent sans réserve. */
    private static final Map<String, Set<String>> EXTENSION_VERS_MIMES = new LinkedHashMap<>();
    static
    {
        EXTENSION_VERS_MIMES.put("pdf",  Set.of(PDF));
        EXTENSION_VERS_MIMES.put("doc",  Set.of("application/msword"));
        EXTENSION_VERS_MIMES.put("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        EXTENSION_VERS_MIMES.put("xls",  Set.of("application/vnd.ms-excel"));
        EXTENSION_VERS_MIMES.put("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        EXTENSION_VERS_MIMES.put("ppt",  Set.of("application/vnd.ms-powerpoint"));
        EXTENSION_VERS_MIMES.put("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        EXTENSION_VERS_MIMES.put("odt",  Set.of("application/vnd.oasis.opendocument.text"));
        EXTENSION_VERS_MIMES.put("ods",  Set.of("application/vnd.oasis.opendocument.spreadsheet"));
        EXTENSION_VERS_MIMES.put("odp",  Set.of("application/vnd.oasis.opendocument.presentation"));
        // Le contenu d'un CSV est du texte : rien ne le distingue d'un .txt dans les octets, les deux se valent.
        EXTENSION_VERS_MIMES.put("txt",  Set.of("text/plain", "text/csv"));
        EXTENSION_VERS_MIMES.put("csv",  Set.of("text/plain", "text/csv"));
        EXTENSION_VERS_MIMES.put("jpg",  Set.of("image/jpeg"));
        EXTENSION_VERS_MIMES.put("jpeg", Set.of("image/jpeg"));
        EXTENSION_VERS_MIMES.put("png",  Set.of("image/png"));
        EXTENSION_VERS_MIMES.put("tif",  Set.of("image/tiff"));
        EXTENSION_VERS_MIMES.put("tiff", Set.of("image/tiff"));
        EXTENSION_VERS_MIMES.put("bmp",  Set.of("image/bmp"));
        EXTENSION_VERS_MIMES.put("gif",  Set.of("image/gif"));
    }

    /**
     * Écarts TOLÉRÉS, avec avertissement : une extension de la famille moderne pour un fichier de l'ancienne (ou
     * l'inverse). Même logiciel, même contenu : seul le format de fichier diffère, l'éditeur valide en connaissance de cause.
     */
    private static final Map<String, Set<String>> ECARTS_TOLERES = Map.of(
        "doc",  Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        "docx", Set.of("application/msword"),
        "xls",  Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        "xlsx", Set.of("application/vnd.ms-excel"),
        "ppt",  Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        "pptx", Set.of("application/vnd.ms-powerpoint")
    );

    private static final Map<String, String> LIBELLES = Map.ofEntries(
        Map.entry(PDF, "PDF"),
        Map.entry("application/msword", "Word 97-2003 (.doc)"),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)"),
        Map.entry("application/vnd.ms-excel", "Excel 97-2003 (.xls)"),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)"),
        Map.entry("application/vnd.ms-powerpoint", "PowerPoint 97-2003 (.ppt)"),
        Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint (.pptx)"),
        Map.entry("application/vnd.oasis.opendocument.text", "OpenDocument texte (.odt)"),
        Map.entry("application/vnd.oasis.opendocument.spreadsheet", "OpenDocument tableur (.ods)"),
        Map.entry("application/vnd.oasis.opendocument.presentation", "OpenDocument présentation (.odp)"),
        Map.entry("text/plain", "texte brut"),
        Map.entry("text/csv", "CSV"),
        Map.entry("image/jpeg", "image JPEG"),
        Map.entry("image/png", "image PNG"),
        Map.entry("image/tiff", "image TIFF"),
        Map.entry("image/bmp", "image BMP"),
        Map.entry("image/gif", "image GIF"),
        Map.entry("application/octet-stream", "données binaires inconnues"),
        Map.entry("text/html", "page HTML"),
        Map.entry("application/x-msdownload", "exécutable Windows"),
        Map.entry("application/x-dosexec", "exécutable Windows"),
        Map.entry("application/x-executable", "exécutable"),
        Map.entry("application/x-sh", "script shell"),
        Map.entry("application/rtf", "RTF")
    );

    /** Extension canonique d'un type réel (pour nommer correctement le fichier envoyé à la conversion). */
    private static final Map<String, String> MIME_VERS_EXTENSION = Map.ofEntries(
        Map.entry(PDF, "pdf"),
        Map.entry("application/msword", "doc"),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        Map.entry("application/vnd.ms-excel", "xls"),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        Map.entry("application/vnd.ms-powerpoint", "ppt"),
        Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
        Map.entry("application/vnd.oasis.opendocument.text", "odt"),
        Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"),
        Map.entry("application/vnd.oasis.opendocument.presentation", "odp"),
        Map.entry("text/plain", "txt"),
        Map.entry("text/csv", "csv"),
        Map.entry("image/jpeg", "jpg"),
        Map.entry("image/png", "png"),
        Map.entry("image/tiff", "tiff"),
        Map.entry("image/bmp", "bmp"),
        Map.entry("image/gif", "gif")
    );

    /** Extension en minuscules, sans le point ; null si le nom n'en a pas. */
    public static String extensionDuNom(String nom)
    {
        if (nom == null) return null;
        String base = nom.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int idx = base.lastIndexOf('.');
        if (idx <= 0 || idx == base.length() - 1) return null;
        return base.substring(idx + 1).toLowerCase(Locale.ROOT).trim();
    }

    public static boolean extensionConnue(String extension)
    {
        return extension != null && EXTENSION_VERS_MIMES.containsKey(extension);
    }

    /** Le type réel correspond-il, sans réserve, à cette extension annoncée ? */
    public static boolean correspond(String extension, String mimeReel)
    {
        return EXTENSION_VERS_MIMES.getOrDefault(extension, Set.of()).contains(mimeReel);
    }

    /** Écart toléré avec avertissement (.docx annoncé pour un .doc réel, etc.) ? */
    public static boolean ecartTolere(String extension, String mimeReel)
    {
        return ECARTS_TOLERES.getOrDefault(extension, Set.of()).contains(mimeReel);
    }

    /** Libellé lisible d'un type (ex. « Word (.docx) ») ; à défaut, le type technique lui-même. */
    public static String libelle(String mime)
    {
        if (mime == null) return "inconnu";
        String archive = ARCHIVES.get(mime);
        if (archive != null) return "archive " + archive;
        return LIBELLES.getOrDefault(mime, mime);
    }

    /** Libellé de ce qu'ANNONCE une extension (ex. « docx » → « Word (.docx) »). */
    public static String libelleDeExtension(String extension)
    {
        Set<String> mimes = EXTENSION_VERS_MIMES.get(extension);
        if (mimes == null || mimes.isEmpty()) return "." + extension;
        // CSV/TXT : le premier de la liste n'est pas significatif, on annonce l'extension telle quelle.
        if (mimes.size() > 1) return "texte (." + extension + ")";
        return libelle(mimes.iterator().next());
    }

    public static String extensionPour(String mime)
    {
        return MIME_VERS_EXTENSION.get(mime);
    }

    public static List<String> extensionsConnues()
    {
        return List.copyOf(EXTENSION_VERS_MIMES.keySet());
    }
}
