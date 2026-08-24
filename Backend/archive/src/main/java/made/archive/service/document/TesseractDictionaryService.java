package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.TesseractProperties;
import made.archive.entite.MetaData;
import made.archive.entite.TypeDocument;
import made.archive.repository.DataTypeRepository;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Construit, par TypeDocument, un répertoire "tessdata" isolé contenant un
 * dictionnaire "user-words" — les noms d'attributs du type (toujours
 * disponibles, même pour le tout premier document) et les valeurs déjà
 * confirmées lors de dépôts précédents (grossit avec le temps) — pour biaiser
 * la reconnaissance Tesseract vers ce vocabulaire.
 *
 * Isolé PAR TYPE (répertoire séparé + liens symboliques vers les .traineddata
 * partagés, pas de copie) pour que deux types en cours d'OCR simultanément ne
 * se marchent jamais dessus : le fichier user-words de l'un n'affecte que ses
 * propres appels Tesseract.
 *
 * Note : l'efficacité du biais "user-words" dépend du moteur Tesseract utilisé
 * (fort avec le moteur historique, plus limité avec le moteur LSTM par défaut
 * des versions récentes) — à valider empiriquement une fois déployé.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TesseractDictionaryService
{
    private static final String[] LANGUAGES = { "fra", "eng", "ara" };

    private final TesseractProperties tesseractProperties;
    private final DataTypeRepository  dataTypeRepository;

    /** Sérialise les (re)constructions concurrentes du dictionnaire d'un même type. */
    private final Map<Long, ReentrantLock> locksByType = new ConcurrentHashMap<>();

    /**
     * Garantit que le répertoire tessdata isolé du type existe, avec ses
     * liens vers les .traineddata et un user-words à jour, puis retourne son
     * chemin — à passer à Tesseract.setDatapath().
     *
     * Retourne le datapath GLOBAL (comportement inchangé) en cas d'échec de
     * construction — le guidage est un bonus, jamais un point de blocage OCR.
     */
    public String ensureDictionary(TypeDocument typeDocument)
    {
        String baseDataPath = tesseractProperties.getDataPath();
        if (typeDocument == null || typeDocument.getId() == null)
        {
            return baseDataPath;
        }

        ReentrantLock lock = locksByType.computeIfAbsent(
            typeDocument.getId(), id -> new ReentrantLock());
        lock.lock();
        try
        {
            Path typeDir = Path.of(
                tesseractProperties.getCustomDictionaryPath(),
                "type-" + typeDocument.getId());

            Files.createDirectories(typeDir);
            linkTrainedData(Path.of(baseDataPath), typeDir);

            Set<String> words = buildWordList(typeDocument);
            writeUserWordsFiles(typeDir, words);

            log.debug("[TesseractDict] Dictionnaire prêt pour le type {} : {} mot(s)/expression(s)",
                typeDocument.getId(), words.size());

            return typeDir.toString();
        }
        catch (Exception e)
        {
            log.warn("[TesseractDict] Impossible de préparer le dictionnaire guidé pour le type {} "
                + "— fallback sur le datapath global : {}", typeDocument.getId(), e.getMessage());
            return baseDataPath;
        }
        finally
        {
            lock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════
    // Construction du vocabulaire
    // ════════════════════════════════════════════════════════

    private Set<String> buildWordList(TypeDocument typeDocument)
    {
        Set<String> words = new LinkedHashSet<>();

        // 1. Noms des attributs du type — disponibles dès le premier document,
        //    ce sont typiquement les libellés imprimés sur le document
        //    ("Facture N°", "Montant TTC"...).
        if (typeDocument.getMetaData() != null)
        {
            typeDocument.getMetaData().stream()
                .map(MetaData::getNom)
                .filter(Objects::nonNull)
                .filter(nom -> !nom.isBlank())
                .forEach(words::add);
        }

        // 2. Valeurs déjà confirmées sur des documents précédents du même
        //    type — vide pour le tout premier document, grossit ensuite.
        dataTypeRepository.findDistinctValeursByTypeDocumentId(typeDocument.getId()).stream()
            .filter(Objects::nonNull)
            .filter(v -> !v.isBlank())
            .forEach(words::add);

        return words;
    }

    // ════════════════════════════════════════════════════════
    // Filesystem
    // ════════════════════════════════════════════════════════

    /** Lie (symlink) les .traineddata du datapath global dans le répertoire isolé du type. */
    private void linkTrainedData(Path baseDataPath, Path typeDir) throws IOException
    {
        File[] trainedDataFiles = baseDataPath.toFile()
            .listFiles((dir, name) -> name.endsWith(".traineddata"));

        if (trainedDataFiles == null)
        {
            return;
        }

        for (File source : trainedDataFiles)
        {
            Path link = typeDir.resolve(source.getName());
            if (Files.exists(link) || Files.isSymbolicLink(link))
            {
                continue;
            }
            try
            {
                Files.createSymbolicLink(link, source.toPath());
            }
            catch (Exception e)
            {
                // Environnement sans droits de symlink (ex : certains conteneurs) :
                // fallback en copie — plus lourd, mais reste correct.
                Files.copy(source.toPath(), link, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Écrit {lang}.user-words pour chaque langue chargée par OcrService. */
    private void writeUserWordsFiles(Path typeDir, Set<String> words) throws IOException
    {
        String content = words.stream().collect(Collectors.joining("\n"));

        for (String lang : LANGUAGES)
        {
            Path file = typeDir.resolve(lang + ".user-words");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }
}
