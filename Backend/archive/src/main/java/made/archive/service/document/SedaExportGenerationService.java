package made.archive.service.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.SedaProperties;
import made.archive.dto.DocumentExportRow;
import made.archive.entite.ExportJob;
import made.archive.repository.DossierRepository;
import made.archive.repository.ExportJobRepository;
import made.archive.repository.PlanClassementNoeudRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.document.SedaManifestBuilder.DossierInfo;
import made.archive.service.document.SedaManifestBuilder.Entree;
import made.archive.service.document.SedaManifestBuilder.Noeud;
import made.archive.service.storage.StorageService;

/**
 * Écrit un paquet SEDA 2.1 (SIP) dans le ZIP d'un export : les PDF/A déchiffrés sous
 * content/ et un manifest.xml, VALIDÉ contre le XSD officiel avant d'être écrit (un SIP
 * qui échoue au schéma serait rejeté par le destinataire — mieux vaut échouer l'export
 * ici, avec la cause dans les logs).
 *
 * Schéma : fichiers seda-2.1-*.xsd + xml.xsd/xlink.xsd sous resources/seda/2.1/, copie
 * du schéma des Archives de France (récupérée via le miroir Libriciel/asalae-core 1.0.0a3 ;
 * le site officiel francearchives.fr n'était pas joignable). Les xsd sont chargés depuis le
 * classpath (recopiés en fichiers temporaires) — aucune requête réseau à l'exécution.
 *
 * Empreinte et taille de chaque BinaryDataObject sont MESURÉES sur les octets écrits dans
 * le ZIP ; si l'empreinte ne correspond pas à Document.pdfaSha256, le document est compté
 * en échec (altération probable) plutôt que versé avec une empreinte qui ne prouve rien.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SedaExportGenerationService
{
    private static final String[] XSD = {
        "seda-2.1-main.xsd", "seda-2.1-types.xsd", "seda-2.1-technical.xsd",
        "seda-2.1-management.xsd", "seda-2.1-descriptive.xsd", "seda-2.1-ontology.xsd",
        "xml.xsd", "xlink.xsd"
    };

    private final ExportJobRepository            exportJobRepository;
    private final StorageService                 storageService;
    private final DocumentEncryptionService      documentEncryptionService;
    private final HashService                    hashService;
    private final DossierRepository              dossierRepository;
    private final PlanClassementNoeudRepository  planClassementNoeudRepository;
    private final SedaProperties                 properties;

    private volatile Schema schema;

    public record Resultat(int traites, int echecs) {}

    public Resultat ecrireSip(ExportJob job, List<DocumentExportRow> documents,
                              Map<java.util.UUID, Map<String, String>> metadonneesParDoc,
                              Map<Long, String> cheminsUO, ZipOutputStream zos) throws IOException
    {
        List<Entree> entrees = new ArrayList<>();
        int traites = 0;
        int echecs = 0;

        for (DocumentExportRow doc : documents)
        {
            try
            {
                byte[] chiffre;
                try (InputStream in = storageService.download(doc.storageKey()))
                {
                    chiffre = in.readAllBytes();
                }
                byte[] clair = documentEncryptionService.decrypt(chiffre);

                String sha256 = hashService.calculateFromBytes(clair);
                if (doc.pdfaSha256() != null && !doc.pdfaSha256().equalsIgnoreCase(sha256))
                {
                    throw new IllegalStateException("empreinte SHA-256 différente de celle archivée (document altéré ?)");
                }

                String uri = "content/" + doc.id() + ".pdf";
                zos.putNextEntry(new ZipEntry(uri));
                zos.write(clair);
                zos.closeEntry();

                String cheminUO = doc.uoId() != null ? cheminsUO.getOrDefault(doc.uoId(), doc.uoNom()) : null;
                entrees.add(new Entree(doc, sha256, clair.length, uri, cheminUO,
                    metadonneesParDoc.getOrDefault(doc.id(), Map.of())));
                traites++;
            }
            catch (Exception e)
            {
                echecs++;
                log.warn("[Export-SEDA] Échec document {} (job {}) : {}", doc.id(), job.getId(), e.getMessage());
            }

            job.setDocumentsTraites(traites);
            job.setDocumentsEnEchec(echecs);
            exportJobRepository.save(job);
        }

        if (entrees.isEmpty())
        {
            throw new IllegalStateException("Aucun document exportable dans ce paquet SEDA");
        }

        byte[] manifest = construireManifest(job, entrees);
        valider(manifest);

        zos.putNextEntry(new ZipEntry("manifest.xml"));
        zos.write(manifest);
        zos.closeEntry();

        return new Resultat(traites, echecs);
    }

    byte[] construireManifest(ExportJob job, List<Entree> entrees)
    {
        Collection<Long> uoIds = new LinkedHashSet<>();
        entrees.forEach(e -> { if (e.doc().uoId() != null) uoIds.add(e.doc().uoId()); });

        Map<Long, Noeud> noeuds = new HashMap<>();
        Map<Long, DossierInfo> dossiers = new HashMap<>();
        if (!uoIds.isEmpty())
        {
            for (Object[] r : planClassementNoeudRepository.findNoeudsPourExport(uoIds))
            {
                noeuds.put((Long) r[0], new Noeud((Long) r[0], (String) r[1], (String) r[2], (Long) r[3]));
            }
            for (Object[] r : dossierRepository.findDossiersPourExport(uoIds))
            {
                dossiers.put((Long) r[0], new DossierInfo((Long) r[0], (String) r[1], (Long) r[2]));
            }
        }

        // TransferringAgency : l'UO productrice quand il n'y en a qu'une, sinon la valeur par défaut.
        String serviceVersant = uoIds.size() == 1
            ? "UO-" + uoIds.iterator().next()
            : properties.getTransferringAgencyDefault();

        SedaManifestBuilder.Contexte ctx = new SedaManifestBuilder.Contexte(
            "MADEARCHIVE-" + job.getId(),
            Instant.now(),
            "Transfert d'archives généré par MadeArchive (export " + job.getId() + ")",
            properties.getArchivalAgreement(),
            properties.getArchivalAgencyIdentifier(),
            serviceVersant,
            properties.getAppraisalRulePrefix());

        return new SedaManifestBuilder().construire(ctx, entrees, noeuds, dossiers, job.isSeparateProjects());
    }

    /** Lève IllegalStateException avec la 1re violation du schéma (ligne + message) si le manifest est invalide. */
    void valider(byte[] manifest)
    {
        try
        {
            chargerSchema().newValidator().validate(
                new StreamSource(new StringReader(new String(manifest, StandardCharsets.UTF_8))));
        }
        catch (SAXException | IOException e)
        {
            throw new IllegalStateException("Le manifest SEDA généré est invalide au regard du XSD SEDA 2.1 : " + e.getMessage(), e);
        }
    }

    private Schema chargerSchema()
    {
        Schema s = schema;
        if (s == null)
        {
            synchronized (this)
            {
                if (schema == null)
                {
                    try
                    {
                        // Copie des XSD dans un répertoire temporaire puis chargement par fichier :
                        // les <include> relatifs du schéma se résolvent alors partout, y compris dans
                        // le jar exécutable Spring Boot (URL "jar:nested:", fragile pour ça).
                        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("seda-xsd-");
                        for (String nom : XSD)
                        {
                            try (InputStream in = getClass().getResourceAsStream("/seda/2.1/" + nom))
                            {
                                if (in == null) throw new IllegalStateException("XSD manquant : " + nom);
                                java.nio.file.Files.copy(in, dir.resolve(nom));
                            }
                        }
                        SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                        schema = f.newSchema(dir.resolve("seda-2.1-main.xsd").toFile());
                    }
                    catch (SAXException | IOException e)
                    {
                        throw new IllegalStateException("Chargement du schéma SEDA 2.1 impossible", e);
                    }
                }
                s = schema;
            }
        }
        return s;
    }
}
