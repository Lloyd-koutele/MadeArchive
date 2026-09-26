package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.GotenbergProperties;
import made.archive.exception.PdfAConversionException;
import org.apache.tika.Tika;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Set;

/**
 * Conversion vers PDF via Gotenberg — un conteneur LibreOffice persistant
 * (docker-compose.yml), interrogé en HTTP. Remplace l'ancien fonctionnement
 * "un `soffice --headless` relancé à chaque fichier" (coût de démarrage à
 * froid de plusieurs secondes, payé sur CHAQUE document) : Gotenberg garde
 * son propre pool LibreOffice chaud en permanence, et tourne dans un
 * conteneur séparé — sa RAM/CPU ne rentre plus en concurrence avec celles de
 * l'appli, et un seul Gotenberg peut servir plusieurs instances de l'appli.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibreOfficeConversionService
{
    private final GotenbergProperties props;
    private final WebClient.Builder   webClientBuilder;
    private final Tika                tika = new Tika();

    // Formats supportés par LibreOffice
    private static final Set<String> SUPPORTED_MIME = Set.of(
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

    private static final Set<String> ALREADY_PDF = Set.of(
        "application/pdf"
    );

    // Tableurs — voir singlePageSheets ci-dessous : sans cette option, un
    // tableau plus large que la zone d'impression par défaut de LibreOffice
    // se retrouve scindé sur PLUSIEURS pages PDF distinctes (colonnes de
    // gauche sur une page, colonnes de droite sur la suivante), cassant la
    // correspondance ligne par ligne entre elles — constaté en conditions
    // réelles sur une feuille de présence (noms sur une page, colonnes
    // Présent/Absent sur une autre, sans plus aucun moyen de les recroiser).
    private static final Set<String> SPREADSHEET_MIME = Set.of(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet",
        "text/csv"
    );

    /**
     * Résultat d'une conversion — singlePageSheetsApplied indique si l'option
     * de mise à l'échelle forcée sur une page (voir SPREADSHEET_MIME) a été
     * utilisée, pour que l'appelant sache s'il doit vérifier la taille de
     * police résultante (voir DocumentOcrService.mesurerPoliceMinimalePt) —
     * jamais pertinent pour un Word/PowerPoint, dont la mise en page n'est
     * pas retouchée par cette conversion.
     */
    public record ConversionResult(byte[] pdfBytes, boolean singlePageSheetsApplied) {}

    /**
     * Convertit n'importe quel format supporté en PDF via Gotenberg.
     * Si le fichier est déjà un PDF, le retourne tel quel.
     */
    public ConversionResult convertToPdf(byte[] fileBytes, String originalFilename)
            throws PdfAConversionException
    {
        String mimeType = tika.detect(fileBytes);
        log.info("[Gotenberg] Format détecté : {} pour {}", mimeType, originalFilename);

        if (ALREADY_PDF.contains(mimeType))
        {
            log.info("[Gotenberg] Déjà un PDF, pas de conversion nécessaire");
            return new ConversionResult(fileBytes, false);
        }

        if (!SUPPORTED_MIME.contains(mimeType))
        {
            throw new PdfAConversionException(
                "Format non supporté : " + mimeType + " (" + originalFilename + ")"
            );
        }

        boolean isSpreadsheet = SPREADSHEET_MIME.contains(mimeType);
        byte[] pdfBytes = convertWithGotenberg(fileBytes, originalFilename, isSpreadsheet);
        return new ConversionResult(pdfBytes, isSpreadsheet);
    }

    private byte[] convertWithGotenberg(byte[] fileBytes, String originalFilename, boolean isSpreadsheet)
            throws PdfAConversionException
    {
        try
        {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("files", new ByteArrayResource(fileBytes)
            {
                @Override
                public String getFilename()
                {
                    return originalFilename;
                }
            });

            if (isSpreadsheet)
            {
                builder.part("singlePageSheets", "true");
            }

            byte[] pdfBytes = webClient()
                .post()
                .uri("/forms/libreoffice/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .block();

            if (pdfBytes == null || pdfBytes.length == 0)
            {
                throw new PdfAConversionException(
                    "Gotenberg n'a pas produit de PDF pour : " + originalFilename);
            }

            log.info("[Gotenberg] Conversion réussie : {} → PDF ({} bytes)",
                originalFilename, pdfBytes.length);
            return pdfBytes;
        }
        catch (PdfAConversionException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new PdfAConversionException(
                "Erreur conversion Gotenberg pour : " + originalFilename, e);
        }
    }

    private WebClient webClient()
    {
        return webClientBuilder.baseUrl(props.getBaseUrl()).build();
    }
}
