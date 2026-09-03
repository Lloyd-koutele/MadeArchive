package made.archive.service.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache en mémoire pour les fichiers déjà téléchargés lors de l'aperçu d'un
 * lien "dossier" (ex. dossier Google Drive public) — voir
 * HeadlessBrowserImportService.
 *
 * Contrairement à un lien direct ou une page web (où {@link WebImportService}
 * ne fait que découvrir des URLs à l'aperçu, et les télécharge seulement une
 * fois confirmées), un dossier n'expose aucune URL individuelle stable par
 * fichier : le seul moyen fiable de tout récupérer est de cliquer une fois
 * sur "Tout télécharger" et de dézipper l'archive générée par Drive. Il faut
 * donc télécharger dès l'aperçu — les octets sont alors mis ici en attente de
 * la confirmation utilisateur, plutôt que re-téléchargés.
 *
 * Même mécanisme que OcrSessionCache : ConcurrentHashMap + expiration par
 * horodatage, nettoyée périodiquement par WebImportFolderCacheCleanupScheduler.
 */
@Slf4j
@Service
public class WebImportFolderCache
{
    private static final long SESSION_TIMEOUT_MINUTES = 15;

    private record Entry(List<WebImportService.FichierDistant> fichiers, Instant createdAt) {}

    private final Map<UUID, Entry> sessions = new ConcurrentHashMap<>();

    public UUID storer(List<WebImportService.FichierDistant> fichiers)
    {
        UUID sessionId = UUID.randomUUID();
        sessions.put(sessionId, new Entry(fichiers, Instant.now()));
        log.info("[WebImportFolderCache] Session créée : {} ({} fichier(s))", sessionId, fichiers.size());
        return sessionId;
    }

    public WebImportService.FichierDistant recuperer(UUID sessionId, int index)
    {
        Entry entree = sessions.get(sessionId);
        if (entree == null)
        {
            log.warn("[WebImportFolderCache] Session inexistante ou expirée : {}", sessionId);
            return null;
        }

        Instant expiration = entree.createdAt().plusSeconds(SESSION_TIMEOUT_MINUTES * 60);
        if (Instant.now().isAfter(expiration))
        {
            log.warn("[WebImportFolderCache] Session expirée : {}", sessionId);
            sessions.remove(sessionId);
            return null;
        }

        List<WebImportService.FichierDistant> fichiers = entree.fichiers();
        if (index < 0 || index >= fichiers.size()) return null;
        return fichiers.get(index);
    }

    public void supprimer(UUID sessionId)
    {
        if (sessions.remove(sessionId) != null)
        {
            log.info("[WebImportFolderCache] Session supprimée : {}", sessionId);
        }
    }

    public void cleanup()
    {
        Instant now = Instant.now();
        long removed = sessions.entrySet().stream()
            .filter(e -> now.isAfter(e.getValue().createdAt().plusSeconds(SESSION_TIMEOUT_MINUTES * 60)))
            .count();

        sessions.entrySet().removeIf(e ->
            now.isAfter(e.getValue().createdAt().plusSeconds(SESSION_TIMEOUT_MINUTES * 60)));

        if (removed > 0)
            log.info("[WebImportFolderCache] {} session(s) expirée(s) nettoyée(s)", removed);
    }
}
