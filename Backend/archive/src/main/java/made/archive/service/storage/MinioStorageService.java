package made.archive.service.storage;

import io.minio.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MinioProperties;
import made.archive.factory.MinioClientFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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