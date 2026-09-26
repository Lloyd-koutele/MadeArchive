package made.archive.service.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.MetaData;
import made.archive.entite.TypeDocument;
import made.archive.repository.DocumentRepository;
import made.archive.repository.TypeDocumentRepository;

/**
 * Génération ET correction asynchrones des regex d'extraction OCR.
 *
 *  - {@link #genererSiPremierUsage} — déclenchée au premier document d'un
 *    type (voir DocumentUploadeService), ou après une réinitialisation
 *    (TypeDocumentService.resetRegex / viderRegexAutomatiquement).
 *  - {@link #corrigerSiDivergence} — déclenchée à CHAQUE document suivant :
 *    compare ce que la regex avait suggéré à ce que l'utilisateur a
 *    finalement validé, et ne régénère QUE les champs qui divergent. Une
 *    regex qui fonctionne déjà ne coûte jamais rien (aucune divergence,
 *    aucun appel Ollama) — le système ne "paie" que là où il y a
 *    effectivement quelque chose à corriger, et s'améliore avec l'usage
 *    plutôt que de rester figé sur le tout premier document.
 *
 * Appel Ollama potentiellement long (jusqu'à ~1 minute) : le document est
 * déjà entièrement archivé et signé AVANT cet appel, un succès ou un échec
 * ici n'affecte plus jamais ce document, donc plus aucune raison de faire
 * attendre le client — voir AsyncConfig.
 *
 * Bean séparé (pas une méthode privée de DocumentUploadeService) : @Async
 * repose sur un proxy AOP, un auto-appel (this.xxx()) le contournerait
 * silencieusement et exécuterait la méthode de façon synchrone sans
 * prévenir — même piège que @Cacheable, voir UOTreeCacheService.
 *
 * VOLONTAIREMENT PAS @Transactional sur genererSiPremierUsage/corrigerSiDivergence
 * (retiré le 09/2026 — présent à l'origine) : une transaction Spring tient sa
 * connexion JDBC ouverte pendant TOUTE la durée de la méthode annotée, y compris
 * ici l'appel Ollama (jusqu'à 150-300s, voir OllamaService.TIMEOUT_SECONDS) qui ne
 * touche pourtant jamais la base. Avec jusqu'à 8 exécutions @Async concurrentes
 * (voir AsyncConfig) contre un pool HikariCP par défaut de 10 connexions, quelques
 * imports concurrents suffisaient à épuiser le pool — bloquant alors TOUTE autre
 * requête ayant besoin de la base (y compris un simple listage de documents) le
 * temps que l'appel Ollama en cours libère enfin sa connexion. Constaté en
 * conditions réelles : les cartes de documents ne s'affichaient plus tant qu'un
 * import était en cours de traitement par Ollama, avec un rechargement de page
 * nécessaire ensuite. Sans @Transactional ici, chaque appel au repository
 * (findByIdWithMetaData, save) ouvre et referme SA PROPRE transaction courte —
 * sûr uniquement parce que findByIdWithMetaData charge déjà metaData en EAGER
 * (LEFT JOIN FETCH, voir TypeDocumentRepository) : rien à charger paresseusement
 * après le retour de la requête, donc aucune session ouverte n'est nécessaire.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegexGenerationService
{
    private final TypeDocumentRepository typeDocumentRepository;
    private final DocumentRepository documentRepository;
    private final OllamaService ollamaService;
    private final OcrService ocrService;

    @Async
    public void genererSiPremierUsage(Long typeDocumentId, String extractedText,
                                       Map<String, String> fieldValues)
    {
        try
        {
            TypeDocument typeDocument = typeDocumentRepository
                .findByIdWithMetaData(typeDocumentId)
                .orElse(null);
            if (typeDocument == null)
            {
                log.warn("[Regex-Async] Type {} introuvable, génération ignorée", typeDocumentId);
                return;
            }

            // Revérifié ici (pas seulement à l'appel) : un autre document du
            // même type a pu déclencher/terminer sa propre génération entre
            // le moment où ce document a été archivé et l'exécution de cette
            // tâche asynchrone.
            if (typeDocument.hasRegexGenerated())
            {
                log.debug("[Regex-Async] Type {} : regex déjà générées entre-temps, réutilisation",
                    typeDocumentId);
                return;
            }

            List<MetaData> metaDataList = typeDocument.getMetaData();
            if (metaDataList == null || metaDataList.isEmpty())
            {
                log.debug("[Regex-Async] Aucune métadonnée définie pour le type {}", typeDocumentId);
                return;
            }

            log.info("[Regex-Async] PREMIÈRE UTILISATION du type {} → génération regex "
                + "pour {} champ(s) avec {} valeur(s) de contexte",
                typeDocumentId, metaDataList.size(), fieldValues.size());

            Map<String, String> generatedRegex = ollamaService.generateRegexForMetaData(
                metaDataList, extractedText, fieldValues);

            // Map vide = le LLM n'a JAMAIS répondu (panne d'infrastructure, ou rien
            // configuré du tout — voir OllamaService.generateRegexForMetaData), pas
            // "aucun champ n'a de motif" (ce cas-là retombe sur ".+", jamais vide).
            // Ne PAS marquer regexGenerated=true ici : retenterEchecs (toutes les
            // 8h, voir RegexGenerationRetryScheduler) doit encore voir ce type.
            if (generatedRegex.isEmpty())
            {
                log.warn("[Regex-Async] Type {} : LLM injoignable, laissé en attente pour reprise différée",
                    typeDocumentId);
                return;
            }

            typeDocument.setExtractionRegexMap(generatedRegex);
            typeDocument.setRegexGenerated(true);
            typeDocumentRepository.save(typeDocument);

            log.info("[Regex-Async] ✅ {} regex générées et stockées dans TypeDocument {}",
                generatedRegex.size(), typeDocumentId);
        }
        catch (Exception e)
        {
            // Best-effort — voir Javadoc de classe : le document concerné est
            // déjà archivé avec succès, un échec ici ne touche que les
            // FUTURES suggestions OCR de son type.
            log.warn("[Regex-Async] Génération regex (best-effort) échouée pour le type {} : {}",
                typeDocumentId, e.getMessage());
        }
    }

    /**
     * Correction ciblée après un document qui n'est PAS le premier du type —
     * voir Javadoc de classe. Ne touche qu'aux champs dont la valeur
     * confirmée par l'utilisateur diverge de ce que la regex actuelle avait
     * suggéré ; les champs déjà corrects gardent leur regex telle quelle.
     */
    @Async
    public void corrigerSiDivergence(Long typeDocumentId, String extractedText,
                                      Map<String, String> suggestionsOriginales,
                                      Map<String, String> valeursConfirmees)
    {
        try
        {
            TypeDocument typeDocument = typeDocumentRepository
                .findByIdWithMetaData(typeDocumentId)
                .orElse(null);
            if (typeDocument == null)
            {
                log.warn("[Regex-Correction] Type {} introuvable, correction ignorée", typeDocumentId);
                return;
            }

            // Rien à corriger tant qu'il n'y a pas encore de base — c'est le
            // rôle de genererSiPremierUsage, pas le nôtre.
            if (!typeDocument.hasRegexGenerated())
            {
                return;
            }

            List<MetaData> metaDataList = typeDocument.getMetaData();
            if (metaDataList == null || metaDataList.isEmpty())
            {
                return;
            }

            Map<String, String> suggestions = suggestionsOriginales != null ? suggestionsOriginales : Map.of();
            Map<String, String> confirmees  = valeursConfirmees    != null ? valeursConfirmees    : Map.of();

            List<MetaData> aCorriger = metaDataList.stream()
                .filter(m -> divergent(suggestions.get(m.getNom()), confirmees.get(m.getNom())))
                .toList();

            if (aCorriger.isEmpty())
            {
                log.debug("[Regex-Correction] Type {} : aucune divergence détectée, regex conservées",
                    typeDocumentId);
                return;
            }

            log.info("[Regex-Correction] Type {} : {} champ(s) divergent(s) ({}) — régénération ciblée",
                typeDocumentId, aCorriger.size(),
                aCorriger.stream().map(MetaData::getNom).collect(Collectors.joining(", ")));

            // Les regex actuelles des champs à corriger sont transmises au modèle comme
            // tentatives déjà en échec (voir OllamaService.buildBatchPrompt) — plus
            // informatif qu'un simple "retente à l'aveugle" ignorant qu'un premier
            // essai a déjà été fait et a raté sur CE document.
            Map<String, String> regexActuellesMap = typeDocument.getExtractionRegexMap();
            Map<String, String> regexesEnEchec = aCorriger.stream()
                .map(MetaData::getNom)
                .filter(regexActuellesMap::containsKey)
                .collect(Collectors.toMap(nom -> nom, regexActuellesMap::get));

            Map<String, String> regexCorrigees = ollamaService.generateRegexForMetaData(
                aCorriger, extractedText, confirmees, regexesEnEchec);

            // Ne remplace QUE les clés corrigées — les regex des champs qui
            // fonctionnaient déjà ne sont jamais touchées.
            Map<String, String> regexMap = new LinkedHashMap<>(typeDocument.getExtractionRegexMap());
            regexMap.putAll(regexCorrigees);
            typeDocument.setExtractionRegexMap(regexMap);
            typeDocumentRepository.save(typeDocument);

            log.info("[Regex-Correction] ✅ {} regex corrigée(s) pour le type {}",
                regexCorrigees.size(), typeDocumentId);
        }
        catch (Exception e)
        {
            // Best-effort — même raisonnement que genererSiPremierUsage : le
            // document concerné est déjà archivé, un échec ici n'affecte que
            // les FUTURES suggestions OCR de son type.
            log.warn("[Regex-Correction] Correction (best-effort) échouée pour le type {} : {}",
                typeDocumentId, e.getMessage());
        }
    }

    /**
     * true si la valeur confirmée par l'utilisateur diverge de ce que la
     * regex avait suggéré — comparaison normalisée (espaces/casse), comme
     * OllamaService.matchesKnownValue. Une valeur confirmée vide n'est
     * jamais un signal exploitable (rien à comparer), donc jamais divergente.
     */
    private boolean divergent(String suggestion, String valeurConfirmee)
    {
        if (valeurConfirmee == null || valeurConfirmee.isBlank())
        {
            return false;
        }
        if (suggestion == null || suggestion.isBlank())
        {
            return true;
        }
        return !normalize(suggestion).equals(normalize(valeurConfirmee));
    }

    private String normalize(String s)
    {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Reprise différée — voir RegexGenerationRetryScheduler (toutes les 8h,
     * best-effort, ne bloque jamais rien). Couvre le cas où genererSiPremierUsage
     * a échoué (LLM injoignable au moment de l'upload, pas encore configuré du
     * tout...) : sans ce filet, un type resterait sans regex pour toujours tant
     * qu'aucun NOUVEAU document du même type n'est uploadé.
     *
     * Contrairement à genererSiPremierUsage (déclenchée à l'upload, avec le
     * texte OCR encore disponible en mémoire), le texte OCR d'origine n'existe
     * plus ici — OcrSessionCache expire après 30 minutes. On relit un document
     * déjà archivé du type et on relance l'OCR dessus via
     * OcrService.processDocument (PHASE 2, prévue pour exactement ce genre de
     * retraitement a posteriori) ; les valeurs de champs confirmées, elles,
     * restent disponibles telles quelles (Document.data).
     *
     * Chaque type est traité indépendamment ; l'échec de l'un n'empêche jamais
     * les suivants. Potentiellement long si plusieurs types sont en attente
     * (jusqu'à OllamaService.TIMEOUT_SECONDS par type) — sans risque de
     * chevauchement avec le prochain cycle vu l'intervalle de 8h.
     */
    public void retenterEchecs()
    {
        List<TypeDocument> aReessayer = typeDocumentRepository.findByRegexGeneratedFalse();
        if (aReessayer.isEmpty())
        {
            return;
        }

        log.info("[Regex-Retry] {} type(s) sans regex à retenter", aReessayer.size());
        int reussis = 0;
        for (TypeDocument type : aReessayer)
        {
            try
            {
                if (retenterUnType(type.getId()))
                {
                    reussis++;
                }
            }
            catch (Exception e)
            {
                log.warn("[Regex-Retry] Échec de la reprise pour le type {} : {}",
                    type.getId(), e.getMessage());
            }
        }
        log.info("[Regex-Retry] Reprise différée terminée : {}/{} type(s) résolu(s)",
            reussis, aReessayer.size());
    }

    private boolean retenterUnType(Long typeDocumentId)
    {
        TypeDocument typeDocument = typeDocumentRepository.findByIdWithMetaData(typeDocumentId).orElse(null);
        if (typeDocument == null || typeDocument.hasRegexGenerated())
        {
            // Résolu (ou supprimé) entre-temps — même revérification que
            // genererSiPremierUsage, voir sa Javadoc.
            return false;
        }

        List<MetaData> metaDataList = typeDocument.getMetaData();
        if (metaDataList == null || metaDataList.isEmpty())
        {
            return false;
        }

        Document document = documentRepository.findByTypeDocument_Id(typeDocumentId).stream()
            .filter(d -> d.getStatus() != DocumentStatus.DELETED && d.getStatus() != DocumentStatus.CORBEILLE)
            .findFirst()
            .orElse(null);
        if (document == null)
        {
            log.debug("[Regex-Retry] Type {} : aucun document exploitable pour l'instant", typeDocumentId);
            return false;
        }

        String extractedText = ocrService.processDocument(document);
        if (extractedText == null || extractedText.isBlank())
        {
            log.warn("[Regex-Retry] Type {} : ré-OCR du document {} vide ou en échec", typeDocumentId, document.getId());
            return false;
        }

        Map<String, String> fieldValues = document.getData() == null ? Map.of()
            : document.getData().stream()
                .filter(d -> d.getMetaData() != null && d.getValeur() != null && !d.getValeur().isBlank())
                .collect(Collectors.toMap(d -> d.getMetaData().getNom(), DataType::getValeur, (a, b) -> a));

        Map<String, String> generatedRegex = ollamaService.generateRegexForMetaData(
            metaDataList, extractedText, fieldValues);

        // Toujours vide = LLM encore injoignable — voir OllamaService.generateRegexForMetaData
        // et genererSiPremierUsage ci-dessus. On laisse regexGenerated=false, ce type sera
        // repris au prochain cycle (dans 8h).
        if (generatedRegex.isEmpty())
        {
            log.debug("[Regex-Retry] Type {} : LLM toujours injoignable", typeDocumentId);
            return false;
        }

        typeDocument.setExtractionRegexMap(generatedRegex);
        typeDocument.setRegexGenerated(true);
        typeDocumentRepository.save(typeDocument);

        log.info("[Regex-Retry] ✅ {} regex générées et stockées (reprise différée) pour le type {}",
            generatedRegex.size(), typeDocumentId);
        return true;
    }
}
