package made.archive.service.document;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.DocumentExportProperties;
import made.archive.entite.Document;
import made.archive.entite.ExportJob;
import made.archive.entite.ExportJobStatus;
import made.archive.entite.NotificationType;
import made.archive.repository.DocumentRepository;
import made.archive.repository.ExportJobRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.notification.NotificationService;
import made.archive.service.storage.StorageService;

/**
 * Génération effective du ZIP d'export — @Async, dans un bean SÉPARÉ de
 * DocumentExportService (même raison que HorodatageService/
 * RegexGenerationService : un appel this.xxx() depuis DocumentExportService
 * contournerait silencieusement le proxy @Async de Spring).
 *
 * Reçoit un jobId déjà créé, avec sa liste de documents déjà RÉSOLUE (voir
 * ExportJob.documentIdsJson) — aucune décision d'autorisation n'est reprise
 * ici, uniquement lecture/déchiffrement/empaquetage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportGenerationService
{
    private final ExportJobRepository       exportJobRepository;
    private final DocumentRepository        documentRepository;
    private final StorageService            storageService;
    private final DocumentEncryptionService documentEncryptionService;
    private final NotificationService       notificationService;
    private final DocumentExportProperties  properties;

    @Async
    public void genererExportAsync(UUID jobId)
    {
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job == null)
        {
            log.warn("[Export] Job {} introuvable au démarrage de la génération", jobId);
            return;
        }

        job.setStatut(ExportJobStatus.EN_COURS);
        exportJobRepository.save(job);

        List<Document> documents = documentRepository.findAllById(job.getDocumentIds());

        try
        {
            Path dir = Path.of(properties.getTempDir());
            Files.createDirectories(dir);
            Path zipPath = dir.resolve(jobId + ".zip");

            int traites = 0;
            int echecs  = 0;

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath)))
            {
                for (Document doc : documents)
                {
                    try
                    {
                        byte[] chiffre;
                        try (InputStream in = storageService.download(doc.getStorageKey()))
                        {
                            chiffre = in.readAllBytes();
                        }
                        byte[] clair = documentEncryptionService.decrypt(chiffre);

                        zos.putNextEntry(new ZipEntry(construireCheminEntree(doc, job.isSeparateProjects())));
                        zos.write(clair);
                        zos.closeEntry();
                        traites++;
                    }
                    catch (Exception e)
                    {
                        echecs++;
                        log.warn("[Export] Échec export document {} (job {}) : {}",
                            doc.getId(), jobId, e.getMessage());
                    }

                    job.setDocumentsTraites(traites);
                    job.setDocumentsEnEchec(echecs);
                    exportJobRepository.save(job);
                }
            }

            job.setStatut(ExportJobStatus.PRET);
            job.setCheminZip(zipPath.toString());
            job.setCompletedAt(LocalDateTime.now());
            exportJobRepository.save(job);

            notificationService.notifier(List.of(job.getDemandePar()), NotificationType.EXPORT_PRET,
                "Votre export de " + traites + " document(s)"
                    + (echecs > 0 ? " (" + echecs + " échec(s))" : "") + " est prêt à télécharger.");

            log.info("[Export] Job {} terminé : {}/{} document(s), {} échec(s)",
                jobId, traites, documents.size(), echecs);
        }
        catch (Exception e)
        {
            log.error("[Export] Échec génération job {} : {}", jobId, e.getMessage(), e);
            job.setStatut(ExportJobStatus.ECHEC);
            exportJobRepository.save(job);
        }
    }

    /**
     * UO/[projet/]{id}_{titre}.pdf — même arborescence que l'ancien script
     * export_uo_documents.py, pour que les habitudes des personnes qui
     * l'utilisaient déjà ne changent pas.
     */
    private String construireCheminEntree(Document doc, boolean separateProjects)
    {
        String uoFolder = nettoyer(
            doc.getUniteOrganisationnelle() != null ? doc.getUniteOrganisationnelle().getNom() : null, "UO");

        StringBuilder chemin = new StringBuilder(uoFolder).append('/');

        if (separateProjects)
        {
            String projetFolder = doc.getProjet() != null
                ? nettoyer(doc.getProjet().getNom(), "Sans_projet")
                : "Sans_projet";
            chemin.append(projetFolder).append('/');
        }

        chemin.append(doc.getId()).append('_').append(nettoyer(doc.getTitre(), doc.getId().toString())).append(".pdf");
        return chemin.toString();
    }

    private String nettoyer(String nom, String repli)
    {
        if (nom == null)
        {
            return repli;
        }
        String propre = nom.replaceAll("[^\\w\\-. ]", "_").trim();
        if (propre.isEmpty())
        {
            return repli;
        }
        return propre.length() > 120 ? propre.substring(0, 120) : propre;
    }
}
