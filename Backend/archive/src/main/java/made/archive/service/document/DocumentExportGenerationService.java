package made.archive.service.document;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.DocumentExportProperties;
import made.archive.dto.DocumentExportRow;
import made.archive.dto.UOCheminProjection;
import made.archive.entite.ExportJob;
import made.archive.entite.ExportJobStatus;
import made.archive.entite.NotificationType;
import made.archive.repository.DocumentRepository;
import made.archive.repository.ExportJobRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
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
    private final ExportJobRepository               exportJobRepository;
    private final DocumentRepository                documentRepository;
    private final StorageService                    storageService;
    private final DocumentEncryptionService         documentEncryptionService;
    private final NotificationService               notificationService;
    private final DocumentExportProperties          properties;
    private final UniteOrganisationnelleRepository  uniteOrganisationnelleRepository;

    private static final DateTimeFormatter FORMAT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * PAS de @Transactional englobant toute la méthode ici — deux raisons,
     * les deux constatées en conditions réelles :
     *   - Chaque appel exportJobRepository.save(job) DOIT rester sa propre
     *     petite transaction (comportement Spring Data par défaut), commitée
     *     immédiatement, pour que documentsTraites avance réellement aux
     *     yeux d'un client qui sonde getStatut() PENDANT la génération —
     *     un seul @Transactional autour de toute la méthode aurait retardé
     *     tous ces commits jusqu'à la toute fin, rendant le suivi de
     *     progression inutile.
     *   - Cette même méthode envoie une notification (notifierService, une
     *     écriture) à la fin : @Transactional(readOnly = true), essayé un
     *     temps pour l'unique raison ci-dessous, faisait échouer cet INSERT
     *     ("cannot execute INSERT in a read-only transaction").
     *
     * Un problème séparé, plus profond, s'est posé en résolvant le premier :
     * charger l'entité Document complète (JOIN FETCH y compris) matérialise
     * AUSSI horodatageToken (@Lob) — Postgres exige alors une transaction
     * explicite pour streamer ce Large Object ("Large Objects may not be
     * used in auto-commit mode"), qu'un traitement @Async sans transaction
     * n'a pas. Résolu à la racine : findAllByIdPourExport ne charge plus
     * l'entité du tout, juste une projection (DocumentExportRow) des
     * colonnes réellement nécessaires — jamais le champ @Lob.
     */
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

        List<DocumentExportRow> documents = documentRepository.findAllByIdPourExport(job.getDocumentIds());

        // Chemins complets des UO (id -> "Ucad/Faculté Sciences/Département
        // Info") calculés en une seule requête récursive pour tout l'arbre —
        // même technique que UniteOrganisationnelleService.chargerCheminComplets,
        // pour que l'arborescence du ZIP reflète la vraie hiérarchie
        // organisationnelle plutôt qu'un dossier par nom d'UO isolé (deux UO
        // homonymes dans des branches différentes ne se marchaient plus dessus).
        Map<Long, String> cheminsUO = new HashMap<>();
        for (UOCheminProjection p : uniteOrganisationnelleRepository.findAllCheminsComplets())
        {
            cheminsUO.put(p.getId(), p.getChemin());
        }

        // Évite qu'un titre dupliqué dans la même UO/type/projet n'écrase
        // silencieusement le fichier précédent dans le ZIP.
        Set<String> cheminsUtilises = new HashSet<>();
        List<String[]> lignesManifest = new ArrayList<>();

        try
        {
            Path dir = Path.of(properties.getTempDir());
            Files.createDirectories(dir);
            Path zipPath = dir.resolve(jobId + ".zip");

            int traites = 0;
            int echecs  = 0;

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath)))
            {
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

                        String cheminUO = doc.uoId() != null
                            ? cheminsUO.getOrDefault(doc.uoId(), doc.uoNom())
                            : null;
                        String cheminEntree = construireCheminEntree(
                            doc, cheminUO, job.isSeparateProjects(), cheminsUtilises);

                        zos.putNextEntry(new ZipEntry(cheminEntree));
                        zos.write(clair);
                        zos.closeEntry();
                        traites++;

                        lignesManifest.add(new String[] {
                            doc.id().toString(),
                            doc.titre(),
                            cheminUO != null ? cheminUO : "",
                            doc.projetNom() != null ? doc.projetNom() : "",
                            doc.typeDocumentNom() != null ? doc.typeDocumentNom() : "",
                            doc.access() != null ? doc.access().name() : "",
                            doc.createAt() != null ? doc.createAt().format(FORMAT_DATE) : "",
                            cheminEntree,
                        });
                    }
                    catch (Exception e)
                    {
                        echecs++;
                        log.warn("[Export] Échec export document {} (job {}) : {}",
                            doc.id(), jobId, e.getMessage());
                    }

                    job.setDocumentsTraites(traites);
                    job.setDocumentsEnEchec(echecs);
                    exportJobRepository.save(job);
                }

                // Manifeste en dernier — une fois tous les chemins définitifs
                // (dédupliqués) connus. Sert à retrouver un document précis
                // sans reparcourir l'arborescence à la main, notamment utile
                // pour une migration vers un autre système d'archivage.
                zos.putNextEntry(new ZipEntry("manifest.csv"));
                zos.write(genererManifestCsv(lignesManifest));
                zos.closeEntry();
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
     * <chemin complet UO>/<type>/[<projet>/]<titre>.pdf — reflète la vraie
     * hiérarchie des UO (dossiers imbriqués, pas un nom aplati) et regroupe
     * TOUJOURS par type de document, comme partout ailleurs dans l'app
     * ("Mes documents" côté éditeur). Avant cette version, ni le type ni la
     * hiérarchie n'apparaissaient : tous les documents d'une UO se
     * retrouvaient à plat dans un seul dossier, types mélangés. La
     * séparation par projet reste optionnelle (job.isSeparateProjects) et
     * s'imbrique désormais SOUS le type plutôt qu'à sa place.
     *
     * Le préfixe UUID du nom de fichier disparaît (redondant avec le
     * manifeste) au profit d'un titre lisible ; en cas de collision entre
     * deux documents au même chemin (même UO/type/[projet]/titre),
     * dédupliqué via un suffixe " (2)", " (3)"...
     */
    private String construireCheminEntree(
        DocumentExportRow doc, String cheminUO, boolean separateProjects, Set<String> cheminsUtilises)
    {
        String uoDossier = nettoyerChemin(cheminUO, "UO");
        String typeDossier = nettoyer(doc.typeDocumentNom(), "Sans_type");

        StringBuilder chemin = new StringBuilder(uoDossier).append('/').append(typeDossier).append('/');

        // Pas de dossier "Sans_projet" — inutile : un document sans projet
        // reste identifiable par sa seule présence dans le dossier de type,
        // pas besoin d'un niveau de plus qui ne dirait rien de plus. Le
        // sous-dossier projet n'apparaît QUE pour un document qui en a
        // réellement un.
        if (separateProjects && doc.projetNom() != null && !doc.projetNom().isBlank())
        {
            chemin.append(nettoyer(doc.projetNom(), "Sans_projet")).append('/');
        }

        // Le titre archivé inclut déjà ".pdf" (ex. "invoice_..._36652.pdf") —
        // retiré avant d'ajouter l'extension pour ne pas se retrouver avec
        // un ".pdf.pdf" (constaté en conditions réelles).
        String titreSansExtension = nettoyer(doc.titre(), doc.id().toString())
            .replaceAll("(?i)\\.pdf$", "");
        chemin.append(titreSansExtension).append(".pdf");
        return dedupliquer(chemin.toString(), cheminsUtilises);
    }

    /** Nettoie chaque segment d'un chemin UO ("Ucad/Faculté Sciences") indépendamment,
     *  pour garder les "/" comme séparateurs de dossiers imbriqués dans le ZIP. */
    private String nettoyerChemin(String chemin, String repli)
    {
        if (chemin == null || chemin.isBlank())
        {
            return repli;
        }
        return java.util.Arrays.stream(chemin.split("/"))
            .map(segment -> nettoyer(segment, repli))
            .collect(java.util.stream.Collectors.joining("/"));
    }

    /** Ajoute " (2)", " (3)"... avant l'extension tant que le chemin est déjà pris. */
    private String dedupliquer(String chemin, Set<String> utilises)
    {
        if (utilises.add(chemin))
        {
            return chemin;
        }
        int point = chemin.lastIndexOf('.');
        String base = point > 0 ? chemin.substring(0, point) : chemin;
        String extension = point > 0 ? chemin.substring(point) : "";
        String candidat;
        int n = 2;
        do
        {
            candidat = base + " (" + n + ")" + extension;
            n++;
        }
        while (!utilises.add(candidat));
        return candidat;
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

    /** En-têtes + une ligne par document réellement inclus dans le ZIP — le chemin
     *  final (déjà dédupliqué) permet de retrouver un fichier sans reparcourir
     *  l'arborescence à la main. UTF-8 avec BOM : Excel ouvre sinon les accents
     *  français comme du charabia par défaut. */
    private byte[] genererManifestCsv(List<String[]> lignes)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }); // BOM UTF-8

        String entete = String.join(",",
            "document_id", "titre", "uo", "projet", "type", "acces", "archive_le", "chemin_dans_zip");
        out.writeBytes((entete + "\n").getBytes(StandardCharsets.UTF_8));

        for (String[] ligne : lignes)
        {
            StringBuilder l = new StringBuilder();
            for (int i = 0; i < ligne.length; i++)
            {
                if (i > 0) l.append(',');
                l.append(echapperCsv(ligne[i]));
            }
            l.append('\n');
            out.writeBytes(l.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private String echapperCsv(String valeur)
    {
        if (valeur == null || valeur.isEmpty())
        {
            return "";
        }
        if (valeur.contains(",") || valeur.contains("\"") || valeur.contains("\n"))
        {
            return "\"" + valeur.replace("\"", "\"\"") + "\"";
        }
        return valeur;
    }
}
