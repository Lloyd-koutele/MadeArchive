package made.archive.service.document;

import lombok.extern.slf4j.Slf4j;
import made.archive.exception.PdfAConversionException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

@Slf4j
@Service
public class PdfAConversionService
{
    /**
     * Reçoit un PDF en bytes, retourne un PDF/A-3b en bytes.
     * LibreOfficeConversionService doit être appelé avant pour les
     * formats non-PDF.
     */
    public byte[] convertToPdfA3(byte[] pdfBytes) throws PdfAConversionException
    {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes).readAllBytes()))
        {
            // 1. Métadonnées de base
            PDDocumentInformation info = document.getDocumentInformation();
            if (info.getCreationDate() == null)
            {
                info.setCreationDate(Calendar.getInstance());
            }
            info.setModificationDate(Calendar.getInstance());

            // 2. Métadonnées XMP PDF/A-3b
            addPdfAXmpMetadata(document);

            // 3. OutputIntent sRGB
            addOutputIntent(document);

            // 4. Sérialiser
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            log.info("[PDF/A] Marquage PDF/A-3b réussi");
            return out.toByteArray();
        }
        catch (PdfAConversionException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new PdfAConversionException("Erreur marquage PDF/A-3b", e);
        }
    }

    private void addPdfAXmpMetadata(PDDocument document) throws Exception
    {
        XMPMetadata xmp = XMPMetadata.createXMPMetadata();

        PDFAIdentificationSchema pdfaSchema = xmp.createAndAddPDFAIdentificationSchema();
        pdfaSchema.setPart(3);
        pdfaSchema.setConformance("B");

        DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
        dc.setFormat("application/pdf");

        XmpSerializer serializer = new XmpSerializer();
        ByteArrayOutputStream xmpOut = new ByteArrayOutputStream();
        serializer.serialize(xmp, xmpOut, true);

        PDMetadata metadata = new PDMetadata(document);
        metadata.importXMPMetadata(xmpOut.toByteArray());
        document.getDocumentCatalog().setMetadata(metadata);
    }

    private void addOutputIntent(PDDocument document) throws IOException
    {
        try (InputStream colorProfile = getClass().getResourceAsStream(
                "/org/apache/pdfbox/resources/icc/sRGB.icc"))
        {
            if (colorProfile == null)
            {
                log.warn("[PDF/A] Profil ICC sRGB introuvable, OutputIntent ignoré");
                return;
            }
            PDOutputIntent intent = new PDOutputIntent(document, colorProfile);
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            document.getDocumentCatalog().addOutputIntent(intent);
        }
    }
}