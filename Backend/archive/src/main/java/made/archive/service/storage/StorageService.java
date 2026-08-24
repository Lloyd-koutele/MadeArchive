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
     * Supprime un fichier
     */
    void delete(String key);

    void deleteMultiple(List<String> keysToDelete);

}