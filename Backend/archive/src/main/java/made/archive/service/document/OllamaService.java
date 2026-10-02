package made.archive.service.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.ExternalLlmProperties;
import made.archive.config.OllamaProperties;
import made.archive.config.RedisCacheConfig;
import made.archive.entite.MetaData;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.UserRepository;
import made.archive.service.notification.NotificationService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
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
 * Service de génération automatique de regex via un LLM — local (Ollama) OU
 * distant (API externe générique), au choix de l'administrateur via .env, les
 * deux étant ENTIÈREMENT OPTIONNELS (voir genererViaLlm : aucun des deux
 * configuré = fonctionnalité silencieusement désactivée, jamais une erreur).
 *
 * Priorité si les deux sont renseignés : l'API externe l'emporte (un choix
 * explicite de l'admin — remplir une clé + une URL externes n'a de sens que
 * s'il veut vraiment s'en servir). ATTENTION confidentialité : passer par une
 * API externe envoie le texte OCR des documents à un tiers — c'est un choix
 * assumé de l'administrateur, pas un défaut imposé.
 *
 * Utilisé lors de la PHASE 2 (finalisation) du PREMIER document d'un type
 * pour auto-générer les regex d'extraction des champs de métadonnées.
 *
 * Fonctionnement (v2) :
 *   1. UN SEUL appel au LLM pour TOUS les champs du type (JSON forcé quand le
 *      fournisseur le permet), au lieu d'un appel par champ — moins de
 *      round-trips, moins de texte OCR répété inutilement.
 *   2. Chaque regex reçue est d'abord réparée mécaniquement (voir
 *      repairCandidate) pour corriger les défauts récurrents qu'aucun réglage
 *      de prompt/température ni changement de modèle local n'élimine de façon
 *      fiable (investigation de septembre 2026 — qwen2.5-coder:7b, gemma4:e4b,
 *      gemma4:12b testés), puis validée avant d'être acceptée :
 *      a. syntaxiquement (Pattern.compile, avec Pattern.MULTILINE — un ^/$ que
 *         le modèle emploie en pensant qu'il ancre chaque LIGNE, comme il le
 *         ferait dans la plupart des autres langages, échouerait silencieusement
 *         sinon : par défaut Java n'ancre que le DÉBUT/FIN du texte entier) ;
 *      b. anti-ReDoS (exécution bornée dans le temps contre le texte OCR
 *         réel — cette regex tournera sur CHAQUE futur document du type) ;
 *      c. fonctionnellement (si une valeur connue existe pour ce champ, la
 *         regex doit effectivement la retrouver dans le texte) ;
 *      d. anti-sur-ajustement (la regex ne doit pas se contenter de recopier
 *         littéralement la valeur connue — sinon elle ne retrouvera jamais
 *         rien sur un futur document où seule cette valeur diffère).
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
    private final ExternalLlmProperties externalLlmProperties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final CacheManager cacheManager;
    private WebClient webClient;
    private WebClient externalWebClient;

    /** Clé fixe unique dans CACHE_LLM_INVALIDE_COOLDOWN — le cooldown est GLOBAL,
     *  pas par type de document (voir alerterAdminLlmInvalide). */
    private static final String CLE_COOLDOWN_LLM_INVALIDE = "llm-invalide";

    private static final int    MAX_TEXT_CHARS     = 3000;
    // 30s était trop juste pour un appel groupé (5 champs, ~3000 caractères de contexte)
    // sur un modèle tournant en CPU/Metal — la génération est best-effort (voir
    // generateRegexIfFirstDocument), un timeout raté ne bloque jamais l'upload, mais
    // échouait systématiquement avant même d'avoir une chance d'aboutir. Porté à 150s
    // pour le 7B (remplaçait alors un 3B), puis à 300s pour le 14B (mesuré jusqu'à 278s
    // de réponse sur un appel groupé réel en CPU — 150s aurait coupé l'appel en cours).
    private static final int    TIMEOUT_SECONDS    = 300;
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
        if (ollamaProperties.estConfiguree())
        {
            this.webClient = webClientBuilder.baseUrl(ollamaProperties.getBaseUrl()).build();
        }
        if (externalLlmProperties.estConfiguree())
        {
            this.externalWebClient = webClientBuilder.baseUrl(externalLlmProperties.getApiUrl()).build();
        }

        if (externalLlmProperties.estConfiguree())
        {
            log.info("[LLM] API externe configurée : {} (modèle {}) — prioritaire sur Ollama local si les deux sont renseignés",
                externalLlmProperties.getApiUrl(), externalLlmProperties.getModel());
        }
        else if (ollamaProperties.estConfiguree())
        {
            log.info("[LLM] Ollama local configuré : {} (modèle {})",
                ollamaProperties.getBaseUrl(), ollamaProperties.getModel());
        }
        else
        {
            log.info("[LLM] Aucun LLM configuré (ni OLLAMA_BASE_URL ni LLM_API_URL dans .env) — "
                + "génération automatique de regex désactivée, aucun impact sur l'archivage.");
        }
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
            Pattern.compile(regex, Pattern.MULTILINE);
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
            Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
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
        return generateRegexForMetaData(metaDataList, ocrText, fieldValues, Map.of());
    }

    /**
     * Variante utilisée par RegexGenerationService.corrigerSiDivergence : `regexesEnEchec`
     * donne, pour les champs concernés, la regex ACTUELLEMENT stockée qui vient de se
     * révéler fausse sur ce nouveau document — injectée dans le prompt pour que le
     * modèle sache explicitement qu'un premier essai a déjà échoué, plutôt que de
     * retenter à l'aveugle comme s'il s'agissait d'un champ jamais vu (voir
     * buildBatchPrompt). Vide lors du tout premier appel (genererSiPremierUsage) —
     * il n'y a alors encore rien à signaler comme ayant échoué.
     */
    public Map<String, String> generateRegexForMetaData(
        List<MetaData>      metaDataList,
        String              ocrText,
        Map<String, String> fieldValues,
        Map<String, String> regexesEnEchec)
    {
        if (metaDataList == null || metaDataList.isEmpty())
        {
            log.debug("[Ollama] Aucune métadonnée à traiter");
            return Map.of();
        }

        String truncatedText = (ocrText != null && ocrText.length() > MAX_TEXT_CHARS)
            ? ocrText.substring(0, MAX_TEXT_CHARS)
            : ocrText;

        Map<String, String> fieldValuesSafe    = fieldValues != null ? fieldValues : Map.of();
        Map<String, String> regexesEnEchecSafe = regexesEnEchec != null ? regexesEnEchec : Map.of();

        log.info("[LLM] Génération de regex (1 appel groupé) pour {} métadonnée(s) "
            + "avec {} valeur(s) saisie(s), {} tentative(s) précédente(s) en échec)",
            metaDataList.size(), fieldValuesSafe.size(), regexesEnEchecSafe.size());

        // ── 1er passage : tous les champs ────────────────────────────────────
        LlmBatchResult premierPassage = callLlmBatch(metaDataList, truncatedText, fieldValuesSafe, regexesEnEchecSafe);
        boolean llmJoignable = premierPassage.llmJoignable();
        Map<String, String> result   = new LinkedHashMap<>();
        List<MetaData>       toRetry = new ArrayList<>();

        for (MetaData metaData : metaDataList)
        {
            String candidate = premierPassage.regexParChamp().get(metaData.getNom());
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
            log.info("[LLM] {} champ(s) invalide(s) au 1er passage, retry groupé : {}",
                toRetry.size(),
                toRetry.stream().map(MetaData::getNom).collect(Collectors.joining(", ")));

            LlmBatchResult retryPassage = callLlmBatch(toRetry, truncatedText, fieldValuesSafe, regexesEnEchecSafe);
            llmJoignable = llmJoignable || retryPassage.llmJoignable();

            for (MetaData metaData : toRetry)
            {
                String candidate = retryPassage.regexParChamp().get(metaData.getNom());
                String validated = validateCandidate(candidate, truncatedText,
                    fieldValuesSafe.get(metaData.getNom()));

                if (validated != null)
                {
                    result.put(metaData.getNom(), validated);
                }
                else if (llmJoignable)
                {
                    // Le LLM a bien répondu (à ce passage ou au précédent) mais n'a jamais
                    // produit de motif exploitable pour ce champ — un repli ".+" a plus de
                    // valeur qu'aucune regex. Voir TypeDocumentService.resetRegex() pour
                    // repartir à zéro si besoin.
                    log.warn("[LLM] ❌ Aucune regex valide pour '{}' après retry — fallback '{}'",
                        metaData.getNom(), FALLBACK_REGEX);
                    result.put(metaData.getNom(), FALLBACK_REGEX);
                }
                // else : le LLM n'a JAMAIS répondu (ni ce passage ni le précédent) — pas de
                // ".+" ici, voir le garde-fou juste en dessous : on préfère laisser le champ
                // totalement absent, pour que l'appelant (RegexGenerationService) sache qu'il
                // doit réessayer plus tard plutôt que de considérer le type comme résolu.
            }
        }

        if (!llmJoignable)
        {
            // Panne d'infrastructure RÉELLE : ni le 1er passage ni le retry n'ont pu
            // joindre le LLM (llmJoignable est un OU des deux — voir sa Javadoc). C'est
            // ICI, et seulement ici, qu'on alerte l'admin : un retry qui échoue seul
            // après un 1er passage réussi ne doit JAMAIS déclencher cette alerte — le
            // LLM était bien joignable, il a juste raté un second appel restreint à
            // quelques champs, et le résultat final contient déjà de vraies regex pour
            // les autres. Alerter dans ce cas ferait croire à une panne totale
            // ("indisponible... pas joignable") alors que la génération a partiellement
            // réussi — constaté en conditions réelles quand l'alerte était encore
            // déclenchée directement depuis appellerOllama/appellerLlmExterne à chaque
            // appel individuel en échec, sans tenir compte des appels précédents.
            //
            // Ne JAMAIS renvoyer de ".+" de complaisance ici : un Map vide signale
            // clairement à l'appelant qu'il n'y a rien d'exploitable à stocker,
            // pour qu'il laisse regexGenerated=false et retente plus tard (voir
            // RegexGenerationService.retenterEchecs) plutôt que de figer le type
            // sur des regex inutiles qui ne seront plus jamais régénérées.
            String description = externalLlmProperties.estConfiguree()
                ? "l'API LLM externe configurée (" + externalLlmProperties.getApiUrl() + ")"
                : "Ollama local (" + ollamaProperties.getBaseUrl() + ")";
            alerterAdminLlmInvalide(description);

            log.warn("[LLM] Aucune réponse du LLM sur les {} champ(s) — rien stocké, à retenter plus tard",
                metaDataList.size());
            return Map.of();
        }

        log.info("[LLM] Génération terminée : {} regex", result.size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation d'un candidat (syntaxe + anti-ReDoS + correspondance)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classe de caractères contenant \s (donc capable, contrairement à un espace
     * littéral, de traverser un retour à ligne) suivie d'un quantificateur GOURMAND
     * (+ ou *) pas déjà rendu paresseux. Repérée le 09/2026 : sur une valeur multi-
     * lignes suivie du libellé du champ SUIVANT, une telle classe continue au-delà
     * de la valeur et avale ce libellé suivant tout entier (ex : "Prénom et nom"
     * capturant "Lloyd Marvin KOUTELE\nDate de naissance " sur le document
     * "attestation" — confirmé en ré-exécutant la regex). Demander au modèle
     * d'utiliser +?/*? dans le prompt (voir buildBatchPrompt) ne suffit pas à le
     * garantir de façon fiable (ignoré par le modèle dans une partie des essais,
     * testé sur qwen2.5-coder:7b ET gemma4:e4b/12b) — corrigé ici mécaniquement.
     */
    private static final Pattern GREEDY_MULTILINE_CLASS =
        Pattern.compile("(\\[[^\\]]*\\\\s[^\\]]*\\])([+*])(?!\\?)");

    /**
     * Anticipation ajoutée quand un groupe capturant réparé n'a RIEN après lui dans
     * le motif d'origine. Rendre un quantificateur paresseux SANS cette borne le fait
     * dégénérer vers une capture d'un seul caractère (rien ne le force à s'étendre
     * plus loin) — constaté en testant : pire que le comportement gourmand d'origine,
     * pas juste "plus prudent". La borne s'arrête à la première ligne qui ressemble à
     * un NOUVEAU libellé ("Xxx...:"), une ligne vide, ou la fin du texte.
     */
    private static final String BOUNDARY_LOOKAHEAD = "(?=\\n\\s*\\S.{0,60}?:|\\n\\s*\\n|$)";

    /**
     * Répare mécaniquement, AVANT validation, les défauts récurrents observés dans
     * les candidats générés par le LLM (voir investigation de septembre 2026 :
     * aucun réglage de température/format ni changement de modèle local —
     * qwen2.5-coder:7b, gemma4:e4b, gemma4:12b — ne les élimine de façon fiable,
     * alors qu'ils sont mécaniquement détectables et corrigeables sans dépendre
     * du LLM). Chaque règle est volontairement conservatrice : elle ne peut que
     * RESTREINDRE ce que capture une regex, jamais l'étendre — une regex qui
     * matchait déjà correctement avant réparation continue de matcher correctement
     * après.
     */
    String repairCandidate(String regex)
    {
        if (regex == null)
        {
            return null;
        }

        Matcher matcher = GREEDY_MULTILINE_CLASS.matcher(regex);
        StringBuilder repare = new StringBuilder();
        int position = 0;

        while (matcher.find())
        {
            repare.append(regex, position, matcher.start());

            String classe          = matcher.group(1); // ex : "[A-Za-zÀ-ÿ\s]"
            String quantificateur   = matcher.group(2); // + ou *
            String resteDuMotif     = regex.substring(matcher.end());

            repare.append(classe).append(quantificateur).append('?');

            // Rien de significatif après ce groupe dans tout le reste du motif
            // (seulement d'éventuelles parenthèses fermantes de groupes englobants) :
            // c'est le cas sans borne, potentiellement dangereux — voir Javadoc. La
            // borne est ajoutée APRÈS ces parenthèses (donc hors du groupe capturant
            // lui-même), sans effet sur ce qui est réellement capturé.
            if (resteDuMotif.matches("\\)*"))
            {
                repare.append(resteDuMotif).append(BOUNDARY_LOOKAHEAD);
                position = regex.length();
            }
            else
            {
                position = matcher.end();
            }
        }
        repare.append(regex, position, regex.length());

        return repare.toString();
    }

    /** Retourne la regex nettoyée si elle passe toutes les validations, sinon null. */
    String validateCandidate(String rawCandidate, String ocrText, String knownValue)
    {
        String regex = cleanRegex(rawCandidate);
        if (regex == null)
        {
            return null;
        }

        regex = repairCandidate(regex);

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

        if (estTropLitterale(regex, knownValue))
        {
            // La regex "fonctionne" (elle retrouve bien la valeur connue), mais
            // seulement parce qu'elle recopie cette valeur littéralement au lieu
            // de s'appuyer sur un motif structurel (libellé précédent, format...).
            // Une telle regex ne retrouvera JAMAIS un futur document dont seule
            // cette valeur diffère (ex : un autre nom de client) — inutile de la
            // stocker, mieux vaut retenter ou tomber sur le fallback ".+" qui,
            // lui au moins, ne donne pas une fausse impression de fiabilité.
            log.warn("[Ollama] Regex rejetée (trop littérale — recopie '{}' au lieu d'un motif "
                + "générique) : {}", knownValue, regex);
            return null;
        }

        return regex;
    }

    /**
     * Détecte une regex qui ne fait que ré-encoder la valeur connue elle-même
     * (ex : "(Aaron Bergman)" pour un champ "Bill to") plutôt qu'un motif
     * capable d'extraire une AUTRE valeur sur un document similaire. On
     * compare le motif "dépouillé" de toute syntaxe regex à la valeur connue
     * normalisée : s'ils coïncident, la regex n'a aucune valeur générique.
     */
    private boolean estTropLitterale(String regex, String knownValue)
    {
        if (knownValue == null || knownValue.isBlank())
        {
            return false;
        }
        String texteBrut = regex.replaceAll("[\\\\(){}\\[\\]^$.*+?|]", "");
        return !texteBrut.isBlank() && normalize(texteBrut).equals(normalize(knownValue));
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
            Pattern.compile(regex, Pattern.MULTILINE).matcher(text).find();
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
     *
     * ÉGALITÉ EXACTE (après normalisation) — pas un simple "contains" dans un
     * sens ou l'autre. Un "contains" bidirectionnel laisse passer une capture
     * tronquée ("Très" ⊂ "Très Bien") ou qui déborde sur le texte suivant
     * ("Fatou Ndiaye Né" ⊃ "Fatou Ndiaye") : dans les deux cas l'une contient
     * l'autre, donc l'ancienne vérification acceptait à tort — vu en pratique
     * en testant la diversification des exemples sur un document non-facture
     * (03/2026) : 3 regex sur 3 étaient fausses (tronquées ou débordantes) et
     * passaient quand même cette vérification. Une capture correcte est
     * TOUJOURS strictement égale à la valeur connue une fois normalisée —
     * aucune raison légitime d'accepter un chevauchement partiel.
     */
    private boolean matchesKnownValue(String regex, String ocrText, String knownValue)
    {
        if (knownValue == null || knownValue.isBlank() || ocrText == null || ocrText.isBlank())
        {
            return true;
        }
        try
        {
            Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(ocrText);
            if (!matcher.find())
            {
                return false;
            }
            String matched = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
            if (matched == null || matched.isBlank())
            {
                return false;
            }
            return normalize(matched).equals(normalize(knownValue));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Normalisation AVANT comparaison (matchesKnownValue/estTropLitterale) — doit
     * neutraliser tout ce qui distingue légitimement le texte brut du document de
     * la valeur saisie par l'utilisateur SANS rien dire de la qualité de la regex
     * elle-même. Les accents en sont un cas réel et fréquent sur les documents
     * français : un titre/intitulé en MAJUSCULES y perd presque toujours ses
     * accents ("DEPARTEMENT GENIE INFORMATIQUE", "DIPLOME DE MASTER"), alors que
     * l'utilisateur tape naturellement la valeur avec ("Département Génie
     * Informatique") en Phase 2 — sans dépouillement des diacritiques, une regex
     * par ailleurs PARFAITE (elle capture exactement le bon segment du texte) est
     * rejetée à tort par une simple différence d'accentuation, jamais par un
     * défaut réel du motif (vu en pratique le 10/2026 : tous les candidats
     * Gemini, syntaxiquement corrects et bien ancrés sur le bon libellé,
     * rejetés en boucle pour cette seule raison).
     */
    private String normalize(String s)
    {
        String sansAccents = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return sansAccents.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Appel groupé au LLM (tous les champs d'un coup, réponse JSON si possible)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param regexParChamp résultat exploitable (peut être vide même si llmJoignable=true,
     *                      ex. réponse reçue mais non-JSON)
     * @param llmJoignable  false UNIQUEMENT en cas de panne d'infrastructure (voir
     *                      appellerOllama/appellerLlmExterne) — jamais pour une réponse
     *                      reçue mais mal formée, qui compte comme "joignable" malgré tout.
     *                      Distingue "le LLM n'a rien à proposer pour ce champ" de "le LLM
     *                      n'a jamais pu être contacté" — voir generateRegexForMetaData.
     */
    private record LlmBatchResult(Map<String, String> regexParChamp, boolean llmJoignable) {}

    private LlmBatchResult callLlmBatch(
        List<MetaData>      metaDataList,
        String              ocrText,
        Map<String, String> fieldValues,
        Map<String, String> regexesEnEchec)
    {
        String prompt = buildBatchPrompt(metaDataList, ocrText, fieldValues, regexesEnEchec);

        String jsonBody = genererViaLlm(prompt);
        if (jsonBody == null)
        {
            return new LlmBatchResult(Map.of(), false);
        }

        try
        {
            Map<String, String> parsed = objectMapper.readValue(jsonBody, new TypeReference<Map<String, String>>() {});
            return new LlmBatchResult(parsed, true);
        }
        catch (Exception parseError)
        {
            // Distingué de l'échec de connexion ci-dessus : le modèle a bien répondu, mais pas
            // avec le JSON attendu (ex : prompt trop complexe pour un petit modèle) — pas une
            // panne d'infrastructure, jamais d'alerte admin pour ça (llmJoignable reste true).
            // Le corps brut est indispensable pour diagnostiquer SANS avoir à reproduire l'appel.
            log.error("[LLM] Réponse non-JSON du modèle pour {} champ(s) — {} : {}",
                metaDataList.size(), parseError.getMessage(), jsonBody);
            return new LlmBatchResult(Map.of(), true);
        }
    }

    /**
     * Choisit le LLM à interroger (externe prioritaire, voir Javadoc de classe)
     * et retourne sa réponse texte brute, ou null si rien n'est configuré OU si
     * l'appel a échoué (déjà loggé/alerté par la méthode appelée le cas échéant).
     */
    private String genererViaLlm(String prompt)
    {
        if (externalLlmProperties.estConfiguree())
        {
            return appellerLlmExterne(prompt);
        }
        if (ollamaProperties.estConfiguree())
        {
            return appellerOllama(prompt);
        }
        return null;
    }

    private String appellerOllama(String prompt)
    {
        Map<String, Object> requestBody = Map.of(
            "model",       ollamaProperties.getModel(),
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
                log.warn("[LLM] Réponse Ollama invalide ou vide — pas une panne réseau, pas d'alerte");
                return null;
            }
            return (String) response.get("response");
        }
        catch (Exception e)
        {
            // Pas d'alerte admin ICI — un seul appel (1er passage OU retry) en échec ne
            // dit rien sur la réussite globale du cycle. Voir generateRegexForMetaData,
            // seul endroit qui alerte, une fois les deux passages connus.
            log.error("[LLM] Échec de connexion à Ollama ({}) : {}",
                ollamaProperties.getBaseUrl(), e.getMessage());
            return null;
        }
    }

    /**
     * Format "Chat Completions" — voir Javadoc d'ExternalLlmProperties pour la
     * liste (non limitative) des fournisseurs compatibles. response_format en
     * JSON forcé (supporté par OpenAI et la plupart des compatibles ; ignoré
     * sans erreur par un fournisseur qui ne le supporte pas, l'instruction JSON
     * du prompt lui-même reste alors le seul filet).
     */
    private String appellerLlmExterne(String prompt)
    {
        Map<String, Object> requestBody = Map.of(
            "model",           externalLlmProperties.getModel(),
            "messages",        List.of(Map.of("role", "user", "content", prompt)),
            "temperature",     0.3,
            "response_format", Map.of("type", "json_object")
        );

        try
        {
            Map<?, ?> response = externalWebClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + externalLlmProperties.getApiKey())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .block();

            List<?> choices = response != null ? (List<?>) response.get("choices") : null;
            if (choices == null || choices.isEmpty())
            {
                log.warn("[LLM] Réponse de l'API externe invalide ou vide — pas une panne réseau, pas d'alerte");
                return null;
            }

            Object messageObj = ((Map<?, ?>) choices.get(0)).get("message");
            if (!(messageObj instanceof Map<?, ?> message))
            {
                log.warn("[LLM] Réponse de l'API externe sans champ 'message' exploitable");
                return null;
            }
            return (String) message.get("content");
        }
        catch (Exception e)
        {
            // Pas d'alerte admin ICI — voir la même remarque dans appellerOllama.
            log.error("[LLM] Échec de connexion à l'API externe ({}) : {}",
                externalLlmProperties.getApiUrl(), e.getMessage());
            return null;
        }
    }

    /**
     * Alerte best-effort à tous les ADMIN globaux — jamais plus d'une fois par
     * cooldown (voir RedisCacheConfig.CACHE_LLM_INVALIDE_COOLDOWN, 8h), même si
     * plusieurs types de documents échouent dans la même fenêtre : un seul
     * message suffit, l'admin n'a pas besoin d'un par type pour comprendre que
     * c'est LE LIEN vers le LLM qui est cassé, pas tel ou tel document.
     */
    private void alerterAdminLlmInvalide(String description)
    {
        try
        {
            Cache cache = cacheManager.getCache(RedisCacheConfig.CACHE_LLM_INVALIDE_COOLDOWN);
            if (cache != null && cache.get(CLE_COOLDOWN_LLM_INVALIDE) != null)
            {
                return;
            }

            List<User> admins = userRepository.findByRoleName(Role_Name.ADMIN);
            notificationService.notifier(admins, NotificationType.LLM_GENERATION_INDISPONIBLE,
                "La génération automatique de regex par IA est actuellement indisponible : "
                    + description + " n'est pas joignable ou a refusé la connexion. "
                    + "La génération automatique de regex est simplement suspendue (aucun document "
                    + "n'est bloqué) — vérifiez la configuration LLM dans le fichier .env du serveur.");

            if (cache != null)
            {
                cache.put(CLE_COOLDOWN_LLM_INVALIDE, Instant.now());
            }
        }
        catch (Exception e)
        {
            // Best-effort — une alerte manquée ne doit jamais faire échouer la génération
            // elle-même, déjà en cours d'échec pour une tout autre raison.
            log.warn("[LLM] Échec de la notification d'indisponibilité (best-effort) : {}", e.getMessage());
        }
    }

    private String buildBatchPrompt(
        List<MetaData>      metaDataList,
        String              ocrText,
        Map<String, String> fieldValues,
        Map<String, String> regexesEnEchec)
    {
        String fieldsSection = metaDataList.stream()
            .map(m -> {
                String value = fieldValues.get(m.getNom());
                String display = (value != null && !value.isBlank())
                    ? "\"" + value + "\""
                    : "(non fournie)";

                String echec = regexesEnEchec.get(m.getNom());
                String suffixeEchec = (echec != null && !echec.isBlank())
                    ? String.format(" [regex actuelle \"%s\" — NE FONCTIONNE PAS sur ce document, "
                        + "proposez-en une meilleure qui retrouve la valeur connue ci-dessus]", echec)
                    : "";

                return String.format("- %s : valeur connue = %s%s", m.getNom(), display, suffixeEchec);
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

            IMPORTANT : la valeur connue sert seulement à repérer où elle se trouve dans le \
            texte — ne la recopiez jamais littéralement dans le motif, cette regex sera \
            réutilisée sur de futurs documents où elle sera différente. Basez-vous sur ce qui \
            reste stable : un libellé qui précède la valeur, sa position, son format.

            EXEMPLES (volontairement issus de types de documents TRÈS différents — la \
            stratégie doit s'adapter à la structure réelle du document traité, pas supposer \
            qu'il s'agit toujours d'une facture) :
            - "Numéro de facture" (valeur "2024-INV-0042") -> "FACTURE N°\\s*([\\w-]+)"
            - "Montant" (valeur "1 500,00") -> "([\\d\\s]+(?:[,.]\\d{2})?)\\s*(?:EUR|€|FCFA)"
            - "Date" (valeur "2025-06-15") -> "(\\d{4}-\\d{2}-\\d{2})"
            - "Nom du titulaire" (valeur "Marie Dupont", carte d'identité) -> "(?:Nom\\s*:?\\s*)([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ\\s\\-]+)" \
            (le libellé "Nom" est entre (?:...) — NON capturant — seule la valeur est entre (...))
            - "Numéro de série" (valeur "SN-88213X", diplôme — AUCUN libellé identifiable, \
            juste un code isolé dans le texte) -> "([A-Z]{2}-\\d{4,}[A-Z]?)"

            RÈGLES :
            - Répondez UNIQUEMENT avec un objet JSON à plat : une clé par nom de champ \
            EXACTEMENT comme ci-dessus, la valeur étant le motif regex brut.
            - Aucun texte hors du JSON, aucun bloc markdown, aucune explication.
            - Chaque regex DOIT être valide pour Java Pattern.compile() et utiliser \
            EXACTEMENT UN SEUL groupe capturant (...) — celui de la valeur à extraire. Le \
            libellé qui précède la valeur (ex : "Nom", "Bill To", "FACTURE N°") doit TOUJOURS \
            être entre (?:...), jamais entre (...) — voir l'exemple "Nom du titulaire" \
            ci-dessous, qui montre exactement cette syntaxe.
            - Le libellé du champ apparaît souvent tel quel juste avant sa valeur, quel que \
            soit le type de document (facture, identité, certificat, contrat...) — utilisez-le \
            comme ancre plutôt que la valeur connue. S'il n'y a AUCUN libellé visible à \
            proximité, appuyez-vous sur le format propre de la valeur elle-même (ex : un \
            numéro qui suit toujours la même forme).
            - Si un champ est numérique : capturez uniquement les chiffres.
            - Si un champ est une date : reconnaissez les formats usuels (JJ/MM/AAAA, AAAA-MM-JJ...).
            - Pour une valeur pouvant contenir PLUSIEURS MOTS séparés par des espaces (une \
            ville, une adresse, un nom composé...), n'utilisez JAMAIS une classe qui exclut \
            l'espace comme [A-Za-z0-9]+ — elle ne matchera qu'un seul mot. Préférez une classe \
            qui autorise l'espace explicitement (ex : [A-Za-zÀ-ÿ ]+) ou, pour une valeur délimitée \
            par une virgule, "tout sauf ce séparateur" (ex : [^,]+).
            - IMPORTANT, valeur répartie sur PLUSIEURS LIGNES (ex : une adresse coupée après \
            une virgule, un intitulé qui continue à la ligne suivante) : un espace littéral ' ' \
            dans une classe [...] NE traverse JAMAIS un retour à la ligne, seule une valeur \
            tenant sur une seule ligne serait alors capturée en entier. Dès qu'une valeur risque \
            de s'étendre sur plusieurs lignes, utilisez \\s au lieu de l'espace littéral dans vos \
            classes (ex : [A-Za-zÀ-ÿ0-9,\\s]+? plutôt que [A-Za-zÀ-ÿ0-9, ]+) — \\s couvre à la \
            fois l'espace ET le retour à la ligne, jamais \\n seul entre crochets [...] (inutile, \
            \\s le couvre déjà). TOUJOURS avec un quantificateur NON GOURMAND (+? au lieu de +) \
            dans ce cas précis : \\s englobant aussi le retour à la ligne, une classe gourmande \
            continuerait au-delà de la valeur et avalerait le champ suivant tout entier.
            - Un champ annoté [regex actuelle "..." — NE FONCTIONNE PAS...] a déjà été tenté sans \
            succès sur un AUTRE document du même type — ne proposez pas exactement la même regex, \
            trouvez pourquoi elle échoue probablement (trop spécifique ? mauvaise ancre ?) et \
            corrigez ce défaut plutôt que de répéter l'essai précédent.
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
