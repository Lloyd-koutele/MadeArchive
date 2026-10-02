package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.GotenbergProperties;
import made.archive.config.PdfAProperties;
import made.archive.exception.PdfAConversionException;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.tika.Tika;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    private final PdfAProperties      pdfAProps;
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

    // Sous-ensemble de SPREADSHEET_MIME qu'Apache POI (déjà une dépendance)
    // sait ouvrir pour y régler le "Fit to 1 page wide" nous-mêmes (voir
    // appliquerAjustementLargeurUnePage) — ODS n'est pas un format Microsoft
    // (POI ne l'éditerait pas) et CSV n'a structurellement aucune page
    // d'impression à configurer. Ces deux-là retombent sur le
    // singlePageSheets de Gotenberg, moins précis mais seul disponible.
    private static final Set<String> SPREADSHEET_MIME_EDITABLE_PAR_POI = Set.of(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
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
        byte[] bytesAEnvoyer = fileBytes;
        boolean demanderSinglePageSheets = isSpreadsheet;

        if (SPREADSHEET_MIME_EDITABLE_PAR_POI.contains(mimeType))
        {
            byte[] ajuste = appliquerAjustementLargeurUnePage(fileBytes, originalFilename);
            if (ajuste != null)
            {
                // Notre propre mise à l'échelle (largeur seule) remplace celle,
                // plus brutale, de Gotenberg (largeur ET hauteur forcées).
                bytesAEnvoyer = ajuste;
                demanderSinglePageSheets = false;
            }
            // sinon (POI n'a pas pu ouvrir/réécrire le classeur) : on retombe
            // silencieusement sur le comportement précédent, voir Javadoc.
        }

        byte[] pdfBytes = convertWithGotenberg(bytesAEnvoyer, originalFilename, demanderSinglePageSheets);
        return new ConversionResult(pdfBytes, isSpreadsheet);
    }

    /**
     * Pré-traite un classeur Excel AVANT envoi à Gotenberg : force "Ajuster à
     * 1 page en largeur" (hauteur libre) sur chaque feuille, au lieu du
     * singlePageSheets de Gotenberg qui force TOUT sur une seule page
     * (largeur ET hauteur), quelle que soit la mise en page du classeur. Le
     * singlePageSheets de Gotenberg convient pour éviter qu'un tableau large
     * soit scindé en colonnes sur plusieurs pages (voir Javadoc
     * SPREADSHEET_MIME), mais un PETIT tableau forcé sur une page ENTIÈRE s'y
     * retrouve anormalement zoomé — la mise à l'échelle de LibreOffice le
     * GROSSIT pour remplir la page, pas seulement pour le faire tenir (vu en
     * pratique le 10/2026 sur une feuille de présence). "Ajuster en largeur
     * seulement" (fitHeight=0, convention Excel pour "hauteur illimitée")
     * résout le VRAI problème visé — des colonnes qui débordent
     * horizontalement — sans jamais grossir un tableau qui tenait déjà
     * naturellement sur une page.
     *
     * Best-effort : un classeur corrompu/protégé par mot de passe que POI ne
     * sait pas ouvrir ne doit jamais bloquer tout l'archivage — retombe sur
     * null, l'appelant garde alors l'ancien comportement (singlePageSheets).
     */
    private byte[] appliquerAjustementLargeurUnePage(byte[] fileBytes, String originalFilename)
    {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(fileBytes)))
        {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++)
            {
                Sheet sheet = workbook.getSheetAt(i);
                sheet.setAutobreaks(true);
                sheet.setFitToPage(true);
                PrintSetup printSetup = sheet.getPrintSetup();
                printSetup.setFitWidth((short) 1);
                printSetup.setFitHeight((short) 0);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            log.warn("[Gotenberg] Ajustement \"largeur 1 page\" impossible pour {} — repli sur singlePageSheets : {}",
                originalFilename, e.getMessage());
            return null;
        }
    }

    private byte[] convertWithGotenberg(byte[] fileBytes, String originalFilename, boolean demanderSinglePageSheets)
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

            if (demanderSinglePageSheets)
            {
                builder.part("singlePageSheets", "true");
            }

            // LibreOffice produit directement un PDF/A (polices intégrées dès
            // l'export) — bien plus fidèle qu'une conversion a posteriori. Le
            // résultat est de toute façon revalidé par PdfAConversionService.
            builder.part("pdfa", pdfAProps.getLibelleProfil());

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

    // WebClient borne par DÉFAUT la réponse mise en mémoire à 256 Ko
    // (spring.codec.max-in-memory-size, 262144 par défaut) — bien trop peu
    // pour un PDF converti : un document de plusieurs dizaines de pages (avec
    // logos/images, comme un mémoire universitaire) produit facilement
    // plusieurs Mo, déclenchant un DataBufferLimitException alors même que
    // Gotenberg a répondu 200 OK avec un PDF parfaitement valide — vu en
    // pratique le 10/2026 (document de 40 pages, PDF ~2 Mo, converti avec
    // succès côté Gotenberg mais rejeté ici à la lecture du corps de
    // réponse). 50 Mo largement au-dessus de ce qu'un document métier produit.
    private static final int MAX_RESPONSE_BYTES = 50 * 1024 * 1024;

    private WebClient webClient()
    {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
            .build();
        return webClientBuilder.baseUrl(props.getBaseUrl()).exchangeStrategies(strategies).build();
    }
}
