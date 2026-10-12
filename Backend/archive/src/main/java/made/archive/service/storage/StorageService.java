package made.archive.service.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.List;

public interface StorageService
{
    /**
     * Upload un fichier et retourne la clé de stockage
     */
    String upload(MultipartFile file, String typeDocument);

    /**
     * Upload des bytes bruts (texte OCR, fichiers convertis...)
     */
    String uploadBytes(byte[] bytes, String key, String contentType);

    /**
     * Télécharge le contenu brut (pour vérification SHA-256, OCR...)
     */
    InputStream download(String key);

    /**
     * Une clé est-elle déjà occupée ? À vérifier AVANT tout upload dont la clé
     * contient une part générée aléatoirement (UUID.randomUUID()) — un PUT sur
     * une clé existante écrase silencieusement l'objet précédent, sans erreur.
     * Collision extrêmement improbable (UUID v4 : ~5,3×10^36 valeurs possibles)
     * mais vérifiable à coût quasi nul ; voir DocumentUploadeService pour la
     * boucle de nouvelle tentative qui s'appuie dessus.
     */
    boolean exists(String key);

    /**
     * Vérifie que l'objet qui vient d'être déposé sous {@code key} est bien arrivé tel qu'il a été envoyé : présent,
     * de la même taille et, selon la configuration, de même contenu (SHA-256 recalculé sur ce qu'on relit). À appeler
     * juste après le dépôt et AVANT d'écrire la moindre ligne en base ou d'indexer quoi que ce soit : un fichier
     * qui échoue ici ne doit jamais être référencé.
     *
     * @param attendu les octets exacts qui ont été envoyés
     * @throws DepotStockageException si l'objet est absent, de taille différente ou de contenu différent, ou si la
     *                                vérification elle-même n'a pas pu s'effectuer (dans le doute, on n'archive pas)
     */
    void verifierDepot(String key, byte[] attendu);

    /**
     * Supprime un fichier
     */
    void delete(String key);

    void deleteMultiple(List<String> keysToDelete);

}