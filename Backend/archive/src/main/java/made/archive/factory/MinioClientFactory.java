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
        }
        // Revérifié à CHAQUE appel, pas seulement à la première construction
        // du client — sans ça, un bucket qui disparaît sous les pieds de
        // l'application (ex. volume MinIO anonyme effacé par un
        // "docker compose down", constaté en conditions réelles) restait
        // invisible pour le reste de la vie du processus : le client déjà
        // mis en cache n'était plus jamais revérifié, et les échecs de
        // upload/download qui en résultaient n'étaient pas toujours
        // remontés (ex. l'enregistrement du texte OCR, volontairement
        // "best-effort" — voir OcrService — n'aurait jamais signalé un
        // bucket manquant). Le coût d'un aller-retour bucketExists
        // supplémentaire par opération est négligeable face au risque de
        // silence sur un problème de stockage réel.
        ensureBucketExists();
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
        }
        catch (Exception e)
        {
            // Relancée (pas seulement loggée comme avant) : un bucket
            // inaccessible ou impossible à créer doit bloquer l'opération de
            // stockage qui a déclenché cet appel, pas être avalé
            // silencieusement en laissant croire que tout va bien.
            throw new IllegalStateException(
                "Bucket MinIO '" + props.getBucket()
                    + "' inaccessible ou impossible à créer : " + e.getMessage(), e);
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