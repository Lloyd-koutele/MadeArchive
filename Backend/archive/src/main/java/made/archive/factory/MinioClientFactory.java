package made.archive.factory;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MinioProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
public class MinioClientFactory
{
    private final MinioProperties props;
    private MinioClient client;

    public MinioClientFactory(MinioProperties props)
    {
        this.props = props;
    }

    /**
     * Retourne le client MinIO.
     * Construit à partir des credentials stockés dans MinioProperties.
     * Peut être appelé après mise à jour des credentials (setup web).
     */
    public MinioClient getClient()
    {
        if (client == null)
        {
            client = buildClient();
            ensureBucketExists();
        }
        return client;
    }
    
    private void ensureBucketExists()
    {
        try
        {
            boolean exists = client.bucketExists(
                io.minio.BucketExistsArgs.builder()
                    .bucket(props.getBucket())
                    .build()
            );
            if (!exists)
            {
                client.makeBucket(
                    io.minio.MakeBucketArgs.builder()
                        .bucket(props.getBucket())
                        .build()
                );
                log.info("[MinIO] Bucket '{}' créé", props.getBucket());
            }
            else
            {
                log.info("[MinIO] Bucket '{}' existant", props.getBucket());
            }
        }
        catch (Exception e)
        {
            log.error("[MinIO] Erreur vérification bucket : {}", e.getMessage());
        }
    }

    /**
     * Force la reconstruction du client — utile après
     * mise à jour des credentials via l'interface de setup.
     */
    public void refresh()
    {
        log.info("[MinIO] Reconstruction du client MinIO...");
        client = buildClient();
    }

    private MinioClient buildClient()
    {
        log.info("[MinIO] Initialisation du client MinIO : {}", props.getEndpoint());
        return MinioClient.builder()
            .endpoint(props.getEndpoint())
            .credentials(props.getAccessKey(), props.getSecretKey())
            .build();
    }
}