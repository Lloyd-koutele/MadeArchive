package made.archive.service.integrite;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MinioProperties;
import made.archive.config.PreuvesManifesteProperties;
import made.archive.entite.Document;

/**
 * Manifeste des preuves d'un document, écrit dans un bucket MinIO séparé et verrouillé (Object Lock) — voir
 * PreuvesManifesteProperties. Contient les mêmes valeurs signées que la base (empreintes, signatures) : si la
 * base est réécrite, le manifeste d'origine reste intact et sert de référence indépendante.
 *
 * Toujours best-effort : une panne du bucket de preuves ne bloque jamais l'archivage — le document n'a alors
 * simplement pas (encore) de manifeste.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestePreuveService
{
    /** Contenu d'un manifeste (version du format incluse pour pouvoir le faire évoluer). */
    public record Manifeste(int format, String documentId, String pdfaSha256, String originalSha256, Long uoId,
                             Long version, String createAt, String storageKey, String pkiSignature,
                             String signatureEnregistrement, String signatureEnregistrementAlias)
    {
    }

    private final PreuvesManifesteProperties props;
    private final MinioProperties minioProps;
    private final ObjectMapper objectMapper;

    private volatile MinioClient client;
    private volatile boolean bucketPret;

    private boolean configure()
    {
        return props.isActif() && minioProps.getEndpoint() != null && !minioProps.getEndpoint().isBlank();
    }

    private String bucket()
    {
        return props.getBucket() != null && !props.getBucket().isBlank()
            ? props.getBucket() : minioProps.getBucket() + "-preuves";
    }

    private static String cle(UUID documentId)
    {
        return "manifestes/" + documentId + ".json";
    }

    private synchronized MinioClient client() throws Exception
    {
        if (client == null)
        {
            String ak = props.getAccessKey() != null && !props.getAccessKey().isBlank()
                ? props.getAccessKey() : minioProps.getAccessKey();
            String sk = props.getSecretKey() != null && !props.getSecretKey().isBlank()
                ? props.getSecretKey() : minioProps.getSecretKey();
            MinioClient c = MinioClient.builder().endpoint(minioProps.getEndpoint()).credentials(ak, sk).build();
            c.setTimeout(10_000, 30_000, 30_000);
            client = c;
        }
        if (!bucketPret)
        {
            MinioClient c = client;
            if (!c.bucketExists(BucketExistsArgs.builder().bucket(bucket()).build()))
            {
                // Le verrou d'objet ne peut s'activer qu'à la création du bucket.
                c.makeBucket(MakeBucketArgs.builder().bucket(bucket()).objectLock(true).build());
                log.info("[Preuves] Bucket '{}' créé avec verrou d'objet", bucket());
            }
            bucketPret = true;
        }
        return client;
    }

    /** Construit le manifeste d'un document scellé. */
    public Manifeste construire(Document d)
    {
        return new Manifeste(1, d.getId().toString(), d.getPdfaSha256(), d.getOriginalSha256(),
            d.getUniteOrganisationnelle() != null ? d.getUniteOrganisationnelle().getId() : null,
            d.getVersion(), PreuveIntegriteService.dateCanonique(d), d.getStorageKey(), d.getPkiSignature(),
            d.getSignatureEnregistrement(), d.getSignatureEnregistrementAlias());
    }

    /**
     * Écrit le manifeste s'il n'existe pas déjà (jamais d'écrasement : un manifeste existant fait foi).
     *
     * @return true si le manifeste est en place à l'issue de l'appel
     */
    public boolean ecrire(Document d)
    {
        if (!configure())
        {
            return false;
        }
        try
        {
            MinioClient c = client();
            String cle = cle(d.getId());
            try
            {
                c.statObject(StatObjectArgs.builder().bucket(bucket()).object(cle).build());
                return true;
            }
            catch (io.minio.errors.ErrorResponseException e)
            {
                // absent : on l'écrit ci-dessous
            }

            byte[] contenu = objectMapper.writeValueAsBytes(construire(d));
            try
            {
                c.putObject(construirePut(cle, contenu, true));
            }
            catch (Exception e)
            {
                // Bucket déjà existant mais créé sans verrou d'objet : on garde au moins la copie séparée.
                log.warn("[Preuves] Écriture verrouillée impossible ({}) — manifeste écrit sans verrou", e.getMessage());
                c.putObject(construirePut(cle, contenu, false));
            }
            return true;
        }
        catch (Exception e)
        {
            log.warn("[Preuves] Manifeste non écrit pour {} (best-effort) : {}", d.getId(), e.getMessage());
            return false;
        }
    }

    private PutObjectArgs construirePut(String cle, byte[] contenu, boolean verrou)
    {
        PutObjectArgs.Builder put = PutObjectArgs.builder().bucket(bucket()).object(cle)
            .stream(new ByteArrayInputStream(contenu), contenu.length, -1)
            .contentType("application/json");
        if (verrou)
        {
            put.retention(new Retention(RetentionMode.valueOf(props.getMode().toUpperCase()),
                ZonedDateTime.now().plusDays(props.getRetentionJours())));
        }
        return put.build();
    }

    /** Lit le manifeste d'un document ; vide s'il n'existe pas ou si le bucket est inaccessible. */
    public Optional<Manifeste> lire(UUID documentId)
    {
        if (!configure())
        {
            return Optional.empty();
        }
        try (InputStream in = client().getObject(
                GetObjectArgs.builder().bucket(bucket()).object(cle(documentId)).build()))
        {
            return Optional.of(objectMapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                Manifeste.class));
        }
        catch (io.minio.errors.ErrorResponseException e)
        {
            return Optional.empty();
        }
        catch (Exception e)
        {
            log.debug("[Preuves] Manifeste de {} illisible : {}", documentId, e.getMessage());
            return Optional.empty();
        }
    }
}
