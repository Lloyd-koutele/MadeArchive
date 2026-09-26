package made.archive.service.document;

import made.archive.config.HorodatageProperties;
import made.archive.config.RedisCacheConfig;
import made.archive.entite.NotificationType;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.DocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.notification.NotificationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Couvre la priorité payant/gratuit (voir HorodatageProperties.urlActive) et
 * l'alerte admin avec cooldown (voir HorodatageService.alerterAdminHorodatageInvalide)
 * — ajoutées pour rendre le TSA configurable via .env, avec un scénario explicite
 * couvert : un abonnement payant coupé par le fournisseur (impayé, quota dépassé).
 *
 * Cache réel (ConcurrentMapCache), pas un mock bête — même raisonnement que
 * OllamaServiceLlmSelectionTest : le cooldown doit rester stateful d'un appel
 * à l'autre pour vérifier honnêtement la dé-duplication des alertes.
 */
@Tag("unit")
class HorodatageServiceInvalideTest
{
    private static final String HASH_FACTICE =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".substring(0, 64);

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private HorodatageService creerService(HorodatageProperties props, Cache cache)
    {
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache(RedisCacheConfig.CACHE_HORODATAGE_INVALIDE_COOLDOWN)).thenReturn(cache);

        return new HorodatageService(
            props, WebClient.builder(), documentRepository, notificationService,
            userRepository, cacheManager);
    }

    @Test
    void urlActive_gratuitParDefautSiPayantNonConfiguree()
    {
        HorodatageProperties props = new HorodatageProperties();
        // tsaUrl garde son défaut Java (FreeTSA), tsaUrlPayant jamais renseignée.

        assertThat(props.payantConfigure()).isFalse();
        assertThat(props.urlActive()).isEqualTo(props.getTsaUrl());
    }

    @Test
    void urlActive_payantPrioritaireDesQuilEstConfiguree()
    {
        HorodatageProperties props = new HorodatageProperties();
        props.setTsaUrlPayant("https://tsa-payant.example.com/tsr");

        assertThat(props.payantConfigure()).isTrue();
        assertThat(props.urlActive()).isEqualTo("https://tsa-payant.example.com/tsr");
        // Gratuit toujours défini en interne, juste plus utilisé tant que le payant l'est.
        assertThat(props.getTsaUrl()).isNotBlank();
    }

    @Test
    void authentificationConfiguree_seulementSiUsernameEtPasswordTousDeuxPresents()
    {
        HorodatageProperties props = new HorodatageProperties();
        assertThat(props.authentificationConfiguree()).isFalse();

        props.setUsername("abonne");
        assertThat(props.authentificationConfiguree()).isFalse(); // password manquant

        props.setPassword("secret");
        assertThat(props.authentificationConfiguree()).isTrue();
    }

    @Test
    void tsaPayantInjoignable_neLevePasEtNAlerteQuUneFoisMemeSurPlusieursAppels()
    {
        when(userRepository.findByRoleName(Role_Name.ADMIN)).thenReturn(List.of(new User()));

        HorodatageProperties props = new HorodatageProperties();
        // Abonnement "coupé" simulé : port fermé, échec de connexion immédiat —
        // même symptôme qu'un abonnement payant impayé dont le fournisseur ne
        // répond plus du tout.
        props.setTsaUrlPayant("http://127.0.0.1:1/tsr");
        props.setUsername("abonne");
        props.setPassword("secret");

        HorodatageService service = creerService(props, new ConcurrentMapCache("test"));

        // 2 appels distincts (2 documents) — une seule alerte grâce au cooldown.
        HorodatageService.HorodatageResult resultat1 = service.horodater(HASH_FACTICE);
        HorodatageService.HorodatageResult resultat2 = service.horodater(HASH_FACTICE);

        assertThat(resultat1).isNull();
        assertThat(resultat2).isNull();
        verify(notificationService, times(1))
            .notifier(anyCollection(), eq(NotificationType.HORODATAGE_INDISPONIBLE), anyString());
    }
}
