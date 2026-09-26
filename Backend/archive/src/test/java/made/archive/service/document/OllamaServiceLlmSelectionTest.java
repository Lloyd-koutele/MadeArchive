package made.archive.service.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import made.archive.config.ExternalLlmProperties;
import made.archive.config.OllamaProperties;
import made.archive.config.RedisCacheConfig;
import made.archive.entite.MetaData;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.UserRepository;
import made.archive.service.notification.NotificationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Couvre la sélection LLM (Ollama local et API externe, tous deux OPTIONNELS —
 * voir OllamaService.genererViaLlm) et l'alerte admin avec cooldown (voir
 * alerterAdminLlmInvalide) — ajoutés pour rendre la génération automatique de
 * regex entièrement configurable via .env, jamais bloquante si mal ou pas
 * configurée.
 *
 * Cache réel (ConcurrentMapCache), pas un mock bête : le cooldown doit rester
 * stateful d'un appel à l'autre pour vérifier honnêtement la dé-duplication
 * des alertes (voir ollamaInjoignable_alerteUneSeuleFoisMemeSurPlusieursAppels
 * — sans état réel, un simple mock masquerait un double-comptage puisque
 * generateRegexForMetaData retente une 2e fois en interne avant même un 2e
 * appel externe).
 */
@Tag("unit")
class OllamaServiceLlmSelectionTest
{
    private final UserRepository userRepository = mock(UserRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    private OllamaService creerService(OllamaProperties ollamaProps, ExternalLlmProperties externalProps, Cache cache)
    {
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache(RedisCacheConfig.CACHE_LLM_INVALIDE_COOLDOWN)).thenReturn(cache);

        OllamaService service = new OllamaService(
            ollamaProps, externalProps, WebClient.builder(), new ObjectMapper(),
            userRepository, notificationService, cacheManager);
        service.init();
        return service;
    }

    private List<MetaData> unChamp()
    {
        MetaData m = new MetaData();
        m.setNom("Nom");
        return List.of(m);
    }

    @Test
    void aucunLlmConfigure_neRenvoieRienEtNAlerteJamais()
    {
        OllamaService service = creerService(
            new OllamaProperties(), new ExternalLlmProperties(),
            new ConcurrentMapCache("test"));

        Map<String, String> resultat = service.generateRegexForMetaData(unChamp(), "texte", Map.of());

        assertThat(resultat).isEmpty();
        verifyNoInteractions(notificationService);
    }

    @Test
    void ollamaInjoignable_neRenvoieRienEtNeStockeJamaisDeFallback()
    {
        when(userRepository.findByRoleName(Role_Name.ADMIN)).thenReturn(List.of(new User()));

        OllamaProperties ollamaProps = new OllamaProperties();
        ollamaProps.setBaseUrl("http://127.0.0.1:1"); // port fermé — échec de connexion immédiat
        ollamaProps.setModel("qwen-test");

        OllamaService service = creerService(
            ollamaProps, new ExternalLlmProperties(), new ConcurrentMapCache("test"));

        Map<String, String> resultat = service.generateRegexForMetaData(unChamp(), "texte", Map.of());

        // Vide, PAS {"Nom": ".+"} — voir generateRegexForMetaData : un ".+" de
        // complaisance ici figerait le type sur regexGenerated=true à tort.
        assertThat(resultat).isEmpty();
    }

    @Test
    void ollamaInjoignable_alerteUneSeuleFoisMemeSurPlusieursAppels()
    {
        when(userRepository.findByRoleName(Role_Name.ADMIN)).thenReturn(List.of(new User()));

        OllamaProperties ollamaProps = new OllamaProperties();
        ollamaProps.setBaseUrl("http://127.0.0.1:1");
        ollamaProps.setModel("qwen-test");

        OllamaService service = creerService(
            ollamaProps, new ExternalLlmProperties(), new ConcurrentMapCache("test"));

        // 2 appels distincts (2 "uploads" simulés) — chacun retente déjà 2 fois en
        // interne (1er passage + retry groupé), donc 4 tentatives de connexion au
        // total, mais UNE seule alerte grâce au cooldown partagé.
        service.generateRegexForMetaData(unChamp(), "texte", Map.of());
        service.generateRegexForMetaData(unChamp(), "texte", Map.of());

        verify(notificationService, times(1))
            .notifier(anyCollection(), eq(NotificationType.LLM_GENERATION_INDISPONIBLE), anyString());
    }
}
