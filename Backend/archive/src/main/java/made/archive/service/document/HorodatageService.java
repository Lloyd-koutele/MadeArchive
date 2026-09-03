package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.HorodatageProperties;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.NotificationType;
import made.archive.repository.DocumentRepository;
import made.archive.service.notification.NotificationService;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Horodatage RFC 3161 (TSA) du hash PDF/A d'un document — voir
 * HorodatageProperties pour l'autorité utilisée (FreeTSA.org par défaut).
 *
 * Complète pkiSignature (DocumentUploadeService, HSM) sans s'y substituer :
 * la signature PKI prouve QUI a archivé et QUE le contenu n'a pas changé
 * depuis ; le jeton d'horodatage prouve QUAND, via un tiers indépendant de
 * cette application — quelqu'un qui ne fait pas confiance à cette base de
 * données peut vérifier le jeton contre le certificat public du TSA.
 *
 * Toujours best-effort et TOUJOURS après coup : le document est déjà
 * archivé et enregistré avant que l'horodatage soit seulement tenté (voir
 * horodaterApresUpload) — même principe que RegexGenerationService, un TSA
 * lent ou injoignable ne doit jamais faire attendre l'archivage lui-même.
 * horodater(...) elle-même ne lève jamais d'exception, elle retourne null
 * en cas d'échec. Jamais d'abandon : un document sans horodatage reste
 * repris indéfiniment par HorodatageRetryScheduler, tant qu'il n'a pas
 * fini par réussir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HorodatageService
{
    private final HorodatageProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final DocumentRepository documentRepository;
    private final NotificationService notificationService;

    private static final List<DocumentStatus> STATUTS_EXCLUS =
        List.of(DocumentStatus.DELETED, DocumentStatus.CORBEILLE);

    /** Le TSA doit répondre vite ou pas du tout — jamais retarder autre chose derrière lui. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record HorodatageResult(byte[] token, Instant date) {}

    /**
     * @param pdfaSha256Hex le hash SHA-256 du PDF/A, en hexadécimal (même
     *                       valeur que Document.pdfaSha256) — jamais le
     *                       fichier lui-même, seul le hash part vers le TSA.
     * @return le jeton + l'heure certifiée, ou null si l'horodatage a échoué
     *         (TSA injoignable, timeout de 5s dépassé, réponse invalide...).
     */
    public HorodatageResult horodater(String pdfaSha256Hex)
    {
        try
        {
            byte[] digest = HexFormat.of().parseHex(pdfaSha256Hex);

            TimeStampRequestGenerator requestGenerator = new TimeStampRequestGenerator();
            requestGenerator.setCertReq(true);
            BigInteger nonce = BigInteger.valueOf(new SecureRandom().nextLong());
            TimeStampRequest tsRequest = requestGenerator.generate(TSPAlgorithms.SHA256, digest, nonce);

            byte[] requestBytes = tsRequest.getEncoded();
            byte[] responseBytes = webClientBuilder.build()
                .post()
                .uri(properties.getTsaUrl())
                .contentType(MediaType.valueOf("application/timestamp-query"))
                .bodyValue(requestBytes)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(TIMEOUT)
                .block();

            TimeStampResponse tsResponse = new TimeStampResponse(responseBytes);
            tsResponse.validate(tsRequest);

            if (tsResponse.getStatus() != PKIStatus.GRANTED && tsResponse.getStatus() != PKIStatus.GRANTED_WITH_MODS)
            {
                log.warn("[Horodatage] TSA a refusé la requête : {}", tsResponse.getStatusString());
                return null;
            }

            TimeStampToken token = tsResponse.getTimeStampToken();
            Instant genTime = token.getTimeStampInfo().getGenTime().toInstant();

            log.info("[Horodatage] Jeton RFC 3161 obtenu, généré le {}", genTime);
            return new HorodatageResult(token.getEncoded(), genTime);
        }
        catch (Exception e)
        {
            // best-effort par nature — voir le javadoc de la classe. Ne
            // jamais faire remonter cette exception à l'appelant. Couvre
            // aussi le dépassement du timeout ci-dessus (TimeoutException).
            log.warn("[Horodatage] Échec de l'horodatage RFC 3161 (non bloquant) : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Premier essai, juste après l'archivage — voir DocumentUploadeService.
     * Bean séparé (pas une méthode privée de DocumentUploadeService) :
     * @Async repose sur un proxy AOP, un auto-appel (this.xxx()) le
     * contournerait silencieusement — même piège que RegexGenerationService
     * (voir sa Javadoc). Recharge le document par id plutôt que de réutiliser
     * l'entité de l'appelant, pour la même raison : elle peut être détachée
     * une fois la transaction d'origine terminée.
     *
     * Un succès ici reste silencieux — c'est le cas normal, attendu, pas la
     * peine de notifier pour ce qui a marché du premier coup. Un échec, en
     * revanche, prévient l'éditeur qui a archivé : le document existe et
     * reste consultable, seul l'horodatage manque pour l'instant, et
     * HorodatageRetryScheduler le complètera automatiquement plus tard.
     */
    @Async
    @Transactional
    public void horodaterApresUpload(UUID documentId)
    {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null)
        {
            log.warn("[Horodatage] Document {} introuvable, horodatage ignoré", documentId);
            return;
        }

        HorodatageResult resultat = horodater(doc.getPdfaSha256());
        if (resultat != null)
        {
            doc.setHorodatageToken(resultat.token());
            doc.setHorodatageDate(resultat.date());
            documentRepository.save(doc);
            return;
        }

        try
        {
            notificationService.notifier(
                List.of(doc.getUploadedBy()),
                NotificationType.DOCUMENT_HORODATAGE_ECHEC,
                "Le document \"" + doc.getTitre() + "\" est enregistré, mais son horodatage n'a pas pu être "
                    + "obtenu pour l'instant (service d'horodatage injoignable). Il sera complété "
                    + "automatiquement dès que possible — aucune action de votre part n'est nécessaire.");
        }
        catch (Exception e)
        {
            log.warn("[Horodatage] Notification d'échec (best-effort) non envoyée pour {} : {}",
                documentId, e.getMessage());
        }
    }

    /**
     * Reprise différée — voir HorodatageRetryScheduler. Aucun abandon : un
     * document sans horodatage reste candidat indéfiniment, tant qu'il n'a
     * pas fini par réussir. Chaque document est traité indépendamment ;
     * l'échec de l'un n'empêche jamais les suivants. Contrairement à un
     * échec initial (silencieux ici, pas de notification répétée à chaque
     * passage — voir horodaterApresUpload pour la seule alerte d'échec), un
     * SUCCÈS ici notifie l'éditeur : il avait été prévenu que ça manquait,
     * il mérite de savoir que c'est réglé.
     */
    @Transactional
    public void retenterEchecs()
    {
        List<Document> aReessayer = documentRepository.findByHorodatageTokenIsNullAndStatusNotIn(STATUTS_EXCLUS);
        if (aReessayer.isEmpty())
        {
            return;
        }

        log.info("[Horodatage] Reprise différée : {} document(s) sans horodatage à traiter", aReessayer.size());
        int reussis = 0;
        for (Document doc : aReessayer)
        {
            HorodatageResult resultat = horodater(doc.getPdfaSha256());
            if (resultat == null)
            {
                continue;
            }

            doc.setHorodatageToken(resultat.token());
            doc.setHorodatageDate(resultat.date());
            documentRepository.save(doc);
            reussis++;

            try
            {
                notificationService.notifier(
                    List.of(doc.getUploadedBy()),
                    NotificationType.DOCUMENT_HORODATAGE_REUSSI,
                    "Le document \"" + doc.getTitre() + "\" est maintenant horodaté.");
            }
            catch (Exception e)
            {
                log.warn("[Horodatage] Notification de succès (best-effort) non envoyée pour {} : {}",
                    doc.getId(), e.getMessage());
            }
        }
        log.info("[Horodatage] Reprise différée terminée : {}/{} document(s) horodaté(s)", reussis, aReessayer.size());
    }
}
