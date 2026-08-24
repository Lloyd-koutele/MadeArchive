package made.archive.service.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.OllamaProperties;
import made.archive.entite.MetaData;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service de génération automatique de regex via Ollama/Qwen.
 *
 * Utilisé lors de la PHASE 2 (finalisation) du PREMIER document d'un type
 * pour auto-générer les regex d'extraction des champs de métadonnées.
 *
 * Fonctionnement (v2) :
 *   1. UN SEUL appel à Qwen pour TOUS les champs du type (format JSON forcé),
 *      au lieu d'un appel par champ — moins de round-trips, moins de texte
 *      OCR répété inutilement.
 *   2. Chaque regex reçue est validée avant d'être acceptée :
 *      a. syntaxiquement (Pattern.compile) ;
 *      b. anti-ReDoS (exécution bornée dans le temps contre le texte OCR
 *         réel — cette regex tournera sur CHAQUE futur document du type) ;
 *      c. fonctionnellement (si une valeur connue existe pour ce champ, la
 *         regex doit effectivement la retrouver dans le texte).
 *   3. Les champs qui échouent la validation sont retentés UNE fois, dans un
 *      second appel groupé restreint à ces champs seulement.
 *   4. Ce qui échoue encore après le retry retombe sur ".+" (accepte tout —
 *      mieux qu'un champ sans regex du tout, mais peu utile ; voir
 *      TypeDocumentService.resetRegex() pour repartir à zéro).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService
{
    private final OllamaProperties ollamaProperties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private WebClient webClient;

    private static final String MODEL             = "qwen2.5-coder:3b";
    private static final int    MAX_TEXT_CHARS     = 3000;
    // 30s était trop juste pour un appel groupé (5 champs, ~3000 caractères de contexte)
    // sur un modèle 3B tournant en CPU/Metal — la génération est best-effort (voir
    // generateRegexIfFirstDocument), un timeout raté ne bloque jamais l'upload, mais
    // échouait systématiquement avant même d'avoir une chance d'aboutir.
    private static final int    TIMEOUT_SECONDS    = 90;
    private static final long   REDOS_TIMEOUT_MS   = 500;
    private static final String FALLBACK_REGEX     = ".+";

    /** Threads démons dédiés au test anti-ReDoS — jamais bloquant pour l'appli si une regex boucle. */
    private final ExecutorService redosGuardExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "redos-guard");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init()
    {
        this.webClient = webClientBuilder
            .baseUrl(ollamaProperties.getBaseUrl())
            .build();
        log.info("[Ollama] Service initialisé avec le modèle : {}", MODEL);
    }

    @PreDestroy
    public void shutdown()
    {
        redosGuardExecutor.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitaires (tests / debug)
    // ─────────────────────────────────────────────────────────────────────────

    public boolean validateRegex(String regex)
    {
        if (regex == null || regex.isEmpty()) return false;
        try
        {
            Pattern.compile(regex);
            return true;
        }
        catch (java.util.regex.PatternSyntaxException e)
        {
            log.warn("[Ollama] Regex invalide : {} (Erreur: {})", regex, e.getMessage());
            return false;
        }
    }

    public String testRegex(String regex, String text)
    {
        if (!validateRegex(regex)) return "Regex invalide";
        try
        {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            return matcher.find() ? matcher.group() : "Aucune correspondance trouvée";
        }
        catch (Exception e)
        {
            return "Erreur lors du test : " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API publique
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Génère une regex par champ de métadonnée, validée (syntaxe + anti-ReDoS
     * + correspondance avec la valeur connue quand disponible).
     *
     * @param metaDataList Liste des MetaData dont la regex doit être générée
     * @param ocrText      Texte extrait par OCR du document (Phase 1)
     * @param fieldValues  Map nom_champ → valeur_saisie (Phase 2)
     * @return             Map nom_champ → regex_générée (et validée, ou ".+" en dernier recours)
     */
    public Map<String, String> generateRegexForMetaData(
        List<MetaData>      metaDataList,
        String              ocrText,
        Map<String, String> fieldValues)
    {
        if (metaDataList == null || metaDataList.isEmpty())
        {
            log.debug("[Ollama] Aucune métadonnée à traiter");
            return Map.of();
        }

        String truncatedText = (ocrText != null && ocrText.length() > MAX_TEXT_CHARS)
            ? ocrText.substring(0, MAX_TEXT_CHARS)
            : ocrText;

        Map<String, String> fieldValuesSafe = fieldValues != null ? fieldValues : Map.of();

        log.info("[Ollama] Génération de regex (1 appel groupé) pour {} métadonnée(s) "
            + "avec {} valeur(s) saisie(s))", metaDataList.size(), fieldValuesSafe.size());

        // ── 1er passage : tous les champs ────────────────────────────────────
        Map<String, String> raw = callQwenBatch(metaDataList, truncatedText, fieldValuesSafe);
        Map<String, String> result   = new LinkedHashMap<>();
        List<MetaData>       toRetry = new ArrayList<>();

        for (MetaData metaData : metaDataList)
        {
            String candidate = raw.get(metaData.getNom());
            String validated = validateCandidate(candidate, truncatedText,
                fieldValuesSafe.get(metaData.getNom()));

            if (validated != null)
            {
                result.put(metaData.getNom(), validated);
            }
            else
            {
                toRetry.add(metaData);
            }
        }

        // ── Retry groupé, une seule fois, uniquement pour les champs en échec ─
        if (!toRetry.isEmpty())
        {
            log.info("[Ollama] {} champ(s) invalide(s) au 1er passage, retry groupé : {}",
                toRetry.size(),
                toRetry.stream().map(MetaData::getNom).collect(Collectors.joining(", ")));

            Map<String, String> retryRaw = callQwenBatch(toRetry, truncatedText, fieldValuesSafe);

            for (MetaData metaData : toRetry)
            {
                String candidate = retryRaw.get(metaData.getNom());
                String validated = validateCandidate(candidate, truncatedText,
                    fieldValuesSafe.get(metaData.getNom()));

                if (validated != null)
                {
                    result.put(metaData.getNom(), validated);
                }
                else
                {
                    log.warn("[Ollama] ❌ Aucune regex valide pour '{}' après retry — fallback '{}'",
                        metaData.getNom(), FALLBACK_REGEX);
                    result.put(metaData.getNom(), FALLBACK_REGEX);
                }
            }
        }

        log.info("[Ollama] Génération terminée : {} regex", result.size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation d'un candidat (syntaxe + anti-ReDoS + correspondance)
    // ─────────────────────────────────────────────────────────────────────────

    /** Retourne la regex nettoyée si elle passe toutes les validations, sinon null. */
    private String validateCandidate(String rawCandidate, String ocrText, String knownValue)
    {
        String regex = cleanRegex(rawCandidate);
        if (regex == null)
        {
            return null;
        }

        if (!validateRegex(regex))
        {
            return null;
        }

        if (!isSafeAgainstRedos(regex, ocrText))
        {
            log.warn("[Ollama] Regex rejetée (ReDoS suspecté) : {}", regex);
            return null;
        }

        if (!matchesKnownValue(regex, ocrText, knownValue))
        {
            log.warn("[Ollama] Regex rejetée (ne retrouve pas la valeur connue '{}') : {}",
                knownValue, regex);
            return null;
        }

        return regex;
    }

    /**
     * Exécute la regex dans un thread borné dans le temps, contre le texte
     * OCR RÉEL (celui sur lequel elle tournera pour de vrai à chaque futur
     * document du type). Un dépassement signale un motif à backtracking
     * catastrophique (ex : quantificateurs imbriqués) — rejeté avant d'être
     * jamais stocké.
     */
    private boolean isSafeAgainstRedos(String regex, String ocrText)
    {
        String text = ocrText != null ? ocrText : "";
        Callable<Boolean> task = () -> {
            Pattern.compile(regex).matcher(text).find();
            return true;
        };

        Future<Boolean> future = redosGuardExecutor.submit(task);
        try
        {
            future.get(REDOS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        }
        catch (TimeoutException e)
        {
            future.cancel(true);
            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Si une valeur connue existe pour ce champ, vérifie que la regex la
     * retrouve bien dans le texte OCR (comparaison normalisée, tolérante à
     * la casse/espaces). Sans valeur connue, on ne peut rien vérifier : accepté.
     */
    private boolean matchesKnownValue(String regex, String ocrText, String knownValue)
    {
        if (knownValue == null || knownValue.isBlank() || ocrText == null || ocrText.isBlank())
        {
            return true;
        }
        try
        {
            Matcher matcher = Pattern.compile(regex).matcher(ocrText);
            if (!matcher.find())
            {
                return false;
            }
            String matched = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
            if (matched == null || matched.isBlank())
            {
                return false;
            }
            String a = normalize(matched);
            String b = normalize(knownValue);
            return a.contains(b) || b.contains(a);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private String normalize(String s)
    {
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Appel groupé à Qwen (tous les champs d'un coup, réponse JSON forcée)
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, String> callQwenBatch(
        List<MetaData>      metaDataList,
        String              ocrText,
        Map<String, String> fieldValues)
    {
        String prompt = buildBatchPrompt(metaDataList, ocrText, fieldValues);

        Map<String, Object> requestBody = Map.of(
            "model",       MODEL,
            "prompt",      prompt,
            "stream",      false,
            "format",      "json",   // force une réponse JSON valide côté Ollama
            "temperature", 0.3
        );

        try
        {
            Map<?, ?> response = webClient.post()
                .uri("/api/generate")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .block();

            if (response == null || !response.containsKey("response"))
            {
                log.warn("[Ollama] Réponse invalide ou vide");
                return Map.of();
            }

            String jsonBody = (String) response.get("response");
            return objectMapper.readValue(jsonBody, new TypeReference<Map<String, String>>() {});
        }
        catch (Exception e)
        {
            log.error("[Ollama] Échec de l'appel groupé Qwen ({} champ(s)) : {}",
                metaDataList.size(), e.getMessage());
            return Map.of();
        }
    }

    private String buildBatchPrompt(
        List<MetaData>      metaDataList,
        String              ocrText,
        Map<String, String> fieldValues)
    {
        String fieldsSection = metaDataList.stream()
            .map(m -> {
                String value = fieldValues.get(m.getNom());
                String display = (value != null && !value.isBlank())
                    ? "\"" + value + "\""
                    : "(non fournie)";
                return String.format("- %s : valeur connue = %s", m.getNom(), display);
            })
            .collect(Collectors.joining("\n"));

        return String.format("""
            Vous êtes un expert en expressions régulières Java. Pour CHAQUE champ listé \
            ci-dessous, générez une expression régulière permettant d'extraire sa valeur \
            depuis le texte du document.

            CHAMPS À TRAITER :
            %s

            TEXTE DU DOCUMENT (extrait) :
            ---
            %s
            ---

            EXEMPLES :
            - "Numéro de facture" (valeur "2024-INV-0042") -> "FACTURE N°\\s*([\\w-]+)"
            - "Montant" (valeur "1 500,00") -> "([\\d\\s]+(?:[,.]\\d{2})?)\\s*(?:EUR|€|FCFA)"
            - "Date" (valeur "2025-06-15") -> "(\\d{4}-\\d{2}-\\d{2})"
            - "Email" (valeur "contact@esp.sn") -> "([\\w\\.-]+@[\\w\\.-]+\\.\\w+)"

            RÈGLES :
            - Répondez UNIQUEMENT avec un objet JSON à plat : une clé par nom de champ \
            EXACTEMENT comme ci-dessus, la valeur étant le motif regex brut.
            - Aucun texte hors du JSON, aucun bloc markdown, aucune explication.
            - Chaque regex DOIT être valide pour Java Pattern.compile() et utiliser un \
            groupe de capture (...) pour la valeur à extraire.
            - Si un champ est numérique : capturez uniquement les chiffres.
            - Si un champ est une date : reconnaissez les formats usuels (JJ/MM/AAAA, AAAA-MM-JJ...).
            - Évitez les quantificateurs imbriqués (ex : (a+)+) — préférez des motifs simples et sûrs.
            - Si aucun motif précis n'est trouvable pour un champ : ".+"

            JSON :
            """, fieldsSection, ocrText);
    }

    /**
     * Nettoie la réponse brute de Qwen pour UNE regex (utilisé en fallback si
     * jamais un champ individuel contient encore du markdown malgré le mode
     * JSON forcé).
     */
    private String cleanRegex(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }

        String cleaned = raw.trim();

        if (cleaned.startsWith("```"))
        {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "")
                             .replaceAll("\\n?```$", "");
        }
        cleaned = cleaned.replaceAll("`", "");
        cleaned = cleaned.replaceAll("(?im)^(?:Regex:|Pattern:|pattern:)\\s*", "");

        String firstLine = cleaned.lines()
            .filter(line -> !line.trim().isEmpty())
            .findFirst()
            .orElse("");

        return firstLine.isEmpty() ? null : firstLine;
    }
}
