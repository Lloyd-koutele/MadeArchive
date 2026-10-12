package made.archive.service.storage;

import io.minio.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MinioProperties;
import made.archive.factory.MinioClientFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import io.minio.messages.DeleteObject;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
public class MinioStorageService implements StorageService
{
    private final MinioClientFactory minioClientFactory;
    private final MinioProperties props;

    /**
     * Niveau de la vérification faite après chaque dépôt : CONTENU (défaut) relit l'objet et recalcule son SHA-256 ;
     * TAILLE se contente de vérifier présence et taille (une requête HEAD), pour les très gros volumes.
     */
    @Value("${storage.verification-depot:CONTENU}")
    private String modeVerificationDepot = "CONTENU";

    @Override
    public String upload(MultipartFile file, String typeDocument)
    {
        String key = typeDocument + "/" + LocalDate.now() + "/"
                   + UUID.randomUUID() + "/" + file.getOriginalFilename();
        try (InputStream is = file.getInputStream())
        {
            minioClientFactory.getClient().putObject(
                PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );
            log.info("[MinIO] Fichier uploadé : {}", key);
            return key;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur upload MinIO : " + key, e);
        }
    }

    @Override
    public String uploadBytes(byte[] bytes, String key, String contentType)
    {
        try
        {
            minioClientFactory.getClient().putObject(
                PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build()
            );
            log.info("[MinIO] Bytes uploadés : {}", key);
            return key;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur uploadBytes MinIO : " + key, e);
        }
    }

    @Override
    public boolean exists(String key)
    {
        try
        {
            minioClientFactory.getClient().statObject(
                StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .build()
            );
            return true;
        }
        catch (io.minio.errors.ErrorResponseException e)
        {
            // Code "NoSuchKey" = la clé est libre, c'est le cas attendu à
            // chaque appel — tout autre code (droits, bucket absent...) est
            // une vraie erreur qu'il ne faut pas avaler silencieusement.
            if ("NoSuchKey".equals(e.errorResponse().code()))
            {
                return false;
            }
            throw new RuntimeException("Erreur vérification existence MinIO : " + key, e);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur vérification existence MinIO : " + key, e);
        }
    }

    @Override
    public void verifierDepot(String key, byte[] attendu)
    {
        StatObjectResponse stat;
        try
        {
            stat = minioClientFactory.getClient().statObject(
                StatObjectArgs.builder().bucket(props.getBucket()).object(key).build());
        }
        catch (io.minio.errors.ErrorResponseException e)
        {
            if ("NoSuchKey".equals(e.errorResponse().code()))
            {
                throw new DepotStockageException("Fichier absent du stockage juste après son dépôt : " + key, e);
            }
            throw new DepotStockageException("Vérification du dépôt impossible (" + e.errorResponse().code() + ") : " + key, e);
        }
        catch (Exception e)
        {
            throw new DepotStockageException("Vérification du dépôt impossible : " + key, e);
        }

        if (stat.size() != attendu.length)
        {
            throw new DepotStockageException("Fichier incomplet dans le stockage : " + stat.size()
                + " octet(s) au lieu de " + attendu.length + " pour " + key);
        }
        if (!"CONTENU".equalsIgnoreCase(modeVerificationDepot))
        {
            return;
        }

        try (InputStream relu = minioClientFactory.getClient().getObject(
            GetObjectArgs.builder().bucket(props.getBucket()).object(key).build()))
        {
            MessageDigest lu = MessageDigest.getInstance("SHA-256");
            byte[] tampon = new byte[8192];
            int n;
            while ((n = relu.read(tampon)) > 0)
            {
                lu.update(tampon, 0, n);
            }
            if (!MessageDigest.isEqual(lu.digest(), MessageDigest.getInstance("SHA-256").digest(attendu)))
            {
                throw new DepotStockageException("Le contenu relu dans le stockage ne correspond pas à ce qui a été envoyé : " + key);
            }
        }
        catch (DepotStockageException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new DepotStockageException("Relecture du dépôt impossible : " + key, e);
        }
    }

    @Override
    public InputStream download(String key)
    {
        try
        {
            return minioClientFactory.getClient().getObject(
                GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .build()
            );
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur download MinIO : " + key, e);
        }
    }

    @Override
    public void delete(String key)
    {
        try
        {
            minioClientFactory.getClient().removeObject(
                RemoveObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .build()
            );
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur suppression MinIO : " + key, e);
        }
    }

    public void deleteMultiple(List<String> keys)
    {
        if (keys == null || keys.isEmpty()) return;
    
        List<DeleteObject> objects = keys.stream()
            .filter(Objects::nonNull)
            .map(DeleteObject::new)
            .collect(Collectors.toList());
    
        try {
            Iterable<Result<DeleteError>> results = minioClientFactory.getClient().removeObjects(
                RemoveObjectsArgs.builder()
                    .bucket(props.getBucket())
                    .objects(objects)
                    .build()
            );
            
            // MinIO requiert d'itérer sur les résultats pour déclencher réellement la suppression 
            // et lever les exceptions si erreurs
            for (Result<DeleteError> result : results) {
                DeleteError error = result.get();
                log.error("[MinIO] Erreur lors de la suppression de l'objet {} : {}", error.objectName(), error.message());
            }
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la suppression groupée MinIO", e);
        }
    }
}