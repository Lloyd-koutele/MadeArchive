package made.archive.service.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.MeilisearchProperties;
import made.archive.dto.MeilisearchDocumentDto;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.util.DocumentVersionLabels;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.TaskInfo;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeilisearchService
{
    private final MeilisearchProperties meilisearchProperties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final Client meiliClient;

    private static final String INDEX_NAME = "documents";

    @PostConstruct
    public void init()
    {
        try
        {
            fetchAndStoreSearchKey();
            if (!indexExists())
            {
                createIndex();
            }
            log.info("[Meilisearch] Index '{}' prêt", INDEX_NAME);
        }
        catch (Exception e)
        {
            log.error("[Meilisearch] Échec initialisation : {}", e.getMessage());
        }
    }

    private void fetchAndStoreSearchKey()
    {
        try
        {
            String response = buildAdminClient().get()
                .uri("/keys")
                .retrieve()
                .bodyToMono(String.class)
                .block();

            Map<String, Object> json = objectMapper.readValue(
                response, new TypeReference<Map<String, Object>>(){});

            List<Map<String, Object>> results = objectMapper.convertValue(
                json.get("results"),
                new TypeReference<List<Map<String, Object>>>(){});

            String searchKey = results.stream()
                .filter(k -> "Default Search API Key".equals(k.get("name")))
                .map(k -> (String) k.get("key"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                    "Default Search API Key introuvable"));

            meilisearchProperties.setSearchKey(searchKey);
            log.info("[Meilisearch] Search API Key récupérée automatiquement");
        }
        catch (Exception e)
        {
            log.warn("[Meilisearch] Impossible de récupérer la Search Key : {}",
                     e.getMessage());
        }
    }

    public void indexDocument(Document document, String extractedText, List<DataType> metaData)
    {
        try
        {
            List<String> metaDataValues = metaData == null
                ? List.of()
                : metaData.stream()
                    .map(DataType::getValeur)
                    .filter(Objects::nonNull)
                    .filter(v -> !v.isBlank())
                    .toList();

            MeilisearchDocumentDto dto = MeilisearchDocumentDto.builder()
                .id(document.getId().toString())
                .titre(document.getTitre())
                .typeDocument(document.getTypeDocument().getNom())
                .typeDocumentId(document.getTypeDocument().getId())
                .extractedText(extractedText)
                .status(document.getStatus().name())
                .access(document.getAccess().name())
                .retentionUntil(document.getRetentionUntil())
                .uploadedBy(document.getUploadedBy().getId().toString())
                .groupeId(document.getGroupe() != null
                    ? document.getGroupe().getId().toString() : null)
                .metaDataValues(metaDataValues)
                .versionLabel(DocumentVersionLabels.compute(document))
                .build();

            String json = objectMapper.writeValueAsString(List.of(dto));

            buildAdminClient().post()
                .uri("/indexes/" + INDEX_NAME + "/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            log.info("[Meilisearch] Document indexé : {}", document.getId());
        }
        catch (Exception e)
        {
            log.error("[Meilisearch] Échec indexation document {} : {}",
                      document.getId(), e.getMessage());
        }
    }

    public void updateDocumentStatus(Document document)
    {
        try
        {
            List<Map<String, String>> update = List.of(Map.of(
                "id", document.getId().toString(),
                "status", document.getStatus().name()
            ));

            buildAdminClient().put()
                .uri("/indexes/" + INDEX_NAME + "/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(update))
                .retrieve()
                .bodyToMono(String.class)
                .block();

            log.info("[Meilisearch] Statut mis à jour : {} → {}",
                     document.getId(), document.getStatus());
        }
        catch (Exception e)
        {
            log.error("[Meilisearch] Échec mise à jour statut {} : {}",
                      document.getId(), e.getMessage());
        }
    }

    public void deleteDocument(String documentId)
    {
        try
        {
            Index index = meiliClient.index(INDEX_NAME);
            index.deleteDocument(documentId);
            log.info("[Meilisearch] Document supprimé de l'index : {}", documentId);
        }
        catch (Exception e)
        {
            log.error("[Meilisearch] Échec suppression index {} : {}",
                      documentId, e.getMessage());
            throw new RuntimeException("Erreur lors de la suppression du document Meilisearch : " + documentId, e);
        }
    }

    public void deleteDocuments(List<String> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return;
        }

        try
        {
            Index index = meiliClient.index(INDEX_NAME);
            TaskInfo task = index.deleteDocuments(ids);
            
            log.info("[Meilisearch] Batch delete envoyé pour {} documents. Task UID: {}", 
                     ids.size(), task.getTaskUid());
        }
        catch (Exception e)
        {
            log.error("[Meilisearch] Échec de la suppression par lot pour les IDs: {}", ids, e);
            throw new RuntimeException("Impossible de supprimer le lot de documents dans Meilisearch", e);
        }
    }

    /**
     * Tous les IDs actuellement indexés dans Meilisearch — comparé à
     * DocumentRepository.findAllIdsNonSupprimes() par
     * DocumentRetentionService.resynchroniserMeilisearch() pour repérer les
     * entrées fantômes (indexées mais absentes/supprimées en base).
     */
    public List<String> listerTousLesIdsIndexes()
    {
        List<String> ids = new java.util.ArrayList<>();
        int offset = 0;
        final int limite = 1000;

        while (true)
        {
            String reponse = buildAdminClient().get()
                .uri("/indexes/" + INDEX_NAME + "/documents?limit=" + limite
                    + "&offset=" + offset + "&fields=id")
                .retrieve()
                .bodyToMono(String.class)
                .block();

            java.util.Map<String, Object> page;
            try
            {
                page = objectMapper.readValue(reponse, new TypeReference<>() {});
            }
            catch (Exception e)
            {
                log.error("[Meilisearch] Réponse illisible lors du listage des IDs : {}", e.getMessage());
                break;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultats = (List<Map<String, Object>>) page.get("results");
            if (resultats == null || resultats.isEmpty())
            {
                break;
            }

            resultats.forEach(doc -> ids.add(String.valueOf(doc.get("id"))));

            if (resultats.size() < limite)
            {
                break;
            }
            offset += limite;
        }

        return ids;
    }

    private boolean indexExists()
    {
        try
        {
            buildAdminClient().get()
                .uri("/indexes/" + INDEX_NAME)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private void createIndex()
    {
        try
        {
            Map<String, String> body = Map.of(
                "uid", INDEX_NAME,
                "primaryKey", "id"
            );

            buildAdminClient().post()
                .uri("/indexes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

            // Attendre que l'index soit prêt avant de configurer
            Thread.sleep(500);

            List<String> filterableAttributes = List.of(
                "typeDocument", "typeDocumentId", "status",
                "access", "uploadedBy", "groupeId"
            );

            buildAdminClient().put()
                .uri("/indexes/" + INDEX_NAME + "/settings/filterable-attributes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(filterableAttributes))
                .retrieve()
                .bodyToMono(String.class)
                .block();

            log.info("[Meilisearch] Index '{}' créé", INDEX_NAME);
        }
        catch (Exception e)
        {
            log.error("[Meilisearch] Erreur création index : {}", e.getMessage());
        }
    }

    private WebClient buildAdminClient()
    {
        return webClientBuilder
            .baseUrl(meilisearchProperties.getHost())
            .defaultHeader("Authorization",
                "Bearer " + meilisearchProperties.getApiKey())
            .build();
    }
}