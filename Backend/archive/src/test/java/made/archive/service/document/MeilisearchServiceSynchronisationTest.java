package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import made.archive.config.MeilisearchProperties;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.GroupeAccess;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Resynchronisation de l'index Meilisearch après une modification de document : champs envoyés, documents
 * écartés (CORRUPTED / DELETED), envoi différé après commit et découpage en lots. Un petit serveur HTTP local
 * remplace Meilisearch et enregistre les requêtes reçues.
 */
class MeilisearchServiceSynchronisationTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private HttpServer serveur;
    private final List<String> methodes = new CopyOnWriteArrayList<>();
    private final List<String> chemins = new CopyOnWriteArrayList<>();
    private final List<String> corps = new CopyOnWriteArrayList<>();
    private MeilisearchService service;

    @BeforeEach
    void demarrer() throws Exception
    {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", echange ->
        {
            methodes.add(echange.getRequestMethod());
            chemins.add(echange.getRequestURI().getPath());
            corps.add(new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reponse = "{\"taskUid\":1}".getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(202, reponse.length);
            echange.getResponseBody().write(reponse);
            echange.close();
        });
        serveur.start();

        MeilisearchProperties props = new MeilisearchProperties();
        props.setHost("http://127.0.0.1:" + serveur.getAddress().getPort());
        props.setApiKey("cle-test");
        service = new MeilisearchService(props, WebClient.builder(), MAPPER, mock(Client.class));
    }

    @AfterEach
    void arreter()
    {
        serveur.stop(0);
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Document document(DocumentStatus statut)
    {
        TypeDocument type = new TypeDocument();
        type.setId(7L);
        type.setNom("Facture");

        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setTitre("Facture mars");
        doc.setTypeDocument(type);
        doc.setStatus(statut);
        doc.setAccess(TypeAccess.PUBLIC);
        doc.setRetentionUntil(LocalDate.of(2030, 5, 1));
        doc.setVersion(2L);
        doc.setDerniereVersion(false);
        return doc;
    }

    private List<Map<String, Object>> premierCorps() throws Exception
    {
        return MAPPER.readValue(corps.get(0), new TypeReference<List<Map<String, Object>>>() {});
    }

    @Test
    void envoieTousLesChampsDepuisLaBaseEtLesValeursDeMetadonnees() throws Exception
    {
        Document doc = document(DocumentStatus.ACTIVE);
        GroupeAccess groupe = new GroupeAccess();
        groupe.setId(12L);
        doc.setAccess(TypeAccess.PRIVE);
        doc.setGroupe(groupe);

        service.synchroniserDocument(doc, List.of("ACME", "2026-03"));

        assertThat(methodes).containsExactly("PUT");
        assertThat(chemins).containsExactly("/indexes/documents/documents");
        Map<String, Object> envoye = premierCorps().get(0);
        assertThat(envoye)
            .containsEntry("id", doc.getId().toString())
            .containsEntry("titre", "Facture mars")
            .containsEntry("typeDocument", "Facture")
            .containsEntry("typeDocumentId", 7)
            .containsEntry("status", "ACTIVE")
            .containsEntry("access", "PRIVE")
            .containsEntry("groupeId", "12")
            .containsEntry("retentionUntil", "2030-05-01")
            .containsEntry("versionLabel", "Version 2")
            .containsEntry("metaDataValues", List.of("ACME", "2026-03"));
        assertThat(envoye).doesNotContainKey("extractedText");
    }

    @Test
    void effaceExplicitementLeGroupeEtGardeLesMetadonneesInchangeesQuandOnNeLesDonnePas() throws Exception
    {
        Document doc = document(DocumentStatus.ACTIVE);

        service.synchroniserDocument(doc, null);

        Map<String, Object> envoye = premierCorps().get(0);
        assertThat(envoye).containsKey("groupeId");
        assertThat(envoye.get("groupeId")).isNull();
        assertThat(envoye).doesNotContainKey("metaDataValues");
    }

    @Test
    void neRecreePasDansLIndexUnDocumentCorrompuOuSupprime()
    {
        service.synchroniserDocument(document(DocumentStatus.CORRUPTED), null);
        service.synchroniserDocument(document(DocumentStatus.DELETED), null);

        assertThat(methodes).isEmpty();
    }

    @Test
    void envoieLesDocumentsEnCorbeilleAvecLeurStatut() throws Exception
    {
        service.synchroniserDocument(document(DocumentStatus.CORBEILLE), null);

        assertThat(premierCorps().get(0)).containsEntry("status", "CORBEILLE");
    }

    @Test
    void attendLeCommitDeLaTransactionAvantDEnvoyer()
    {
        TransactionSynchronizationManager.initSynchronization();
        service.synchroniserDocument(document(DocumentStatus.ACTIVE), null);

        assertThat(methodes).as("rien n'est envoyé avant le commit").isEmpty();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations())
        {
            sync.afterCommit();
        }
        assertThat(methodes).containsExactly("PUT");
    }

    @Test
    void nEnvoieRienSiLaTransactionEstAnnulee()
    {
        TransactionSynchronizationManager.initSynchronization();
        service.synchroniserDocument(document(DocumentStatus.ACTIVE), null);

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations())
        {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(methodes).isEmpty();
    }

    @Test
    void renommageDeTypeMetAJourChaqueDocumentParLotsDe500() throws Exception
    {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1200; i++)
        {
            ids.add(UUID.randomUUID());
        }

        service.mettreAJourTypeDocument(7L, "Facture client", ids);

        assertThat(methodes).hasSize(3);
        List<Map<String, Object>> lot1 = premierCorps();
        assertThat(lot1).hasSize(500);
        assertThat(lot1.get(0))
            .containsEntry("typeDocument", "Facture client")
            .containsEntry("typeDocumentId", 7)
            .containsOnlyKeys("id", "typeDocument", "typeDocumentId");
        int total = 0;
        for (String c : corps)
        {
            total += MAPPER.readValue(c, new TypeReference<List<Map<String, Object>>>() {}).size();
        }
        assertThat(total).isEqualTo(1200);
    }

    @Test
    void renommageSansDocumentNEnvoieRien()
    {
        service.mettreAJourTypeDocument(7L, "Facture client", List.of());

        assertThat(methodes).isEmpty();
    }
}
