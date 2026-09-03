package made.archive.service.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.MetaData;
import made.archive.entite.TypeDocument;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegexGenerationService
{
    private final TypeDocumentRepository typeDocumentRepository;
    private final OllamaService ollamaService;

    @Async
    @Transactional
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
    @Transactional
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
}
