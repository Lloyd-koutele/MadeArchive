package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import made.archive.config.RedisCacheConfig;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.document.FixityCheckTriggerService.Portee;
import made.archive.service.organisation.UniteOrganisationnelleService;

/**
 * Contrôle d'intégrité demandé par un administrateur : le contrôle TOTAL garde son délai de 6 h ; les contrôles
 * ciblés (types, unités) sont possibles à tout moment, plafonnés à 1000 documents, sans jamais toucher au délai du
 * contrôle total ; jamais deux contrôles ciblés simultanés sur le même périmètre ; chaque demande est tracée.
 */
class FixityCheckTriggerServiceTest
{
    private FixityCheckAsyncExecutor executor;
    private DocumentRepository documents;
    private UniteOrganisationnelleService unites;
    private AuditLogService audit;
    private ConcurrentMapCacheManager caches;
    private FixityCheckTriggerService service;
    private UserDetails principal;

    @BeforeEach
    void init()
    {
        executor = mock(FixityCheckAsyncExecutor.class);
        documents = mock(DocumentRepository.class);
        unites = mock(UniteOrganisationnelleService.class);
        audit = mock(AuditLogService.class);
        caches = new ConcurrentMapCacheManager(RedisCacheConfig.CACHE_FIXITY_COOLDOWN);
        UserRepository users = mock(UserRepository.class);

        User admin = new User();
        admin.setEmail("admin@test.local");
        Set<Role> roles = new HashSet<>();
        roles.add(new Role(null, Role_Name.ADMIN));
        admin.setRoles(roles);
        when(users.findByEmail("admin@test.local")).thenReturn(Optional.of(admin));
        when(unites.aAutoriteSur(anyLong(), any())).thenReturn(true);

        principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn("admin@test.local");

        service = new FixityCheckTriggerService(executor, mock(TypeDocumentRepository.class), unites, users, audit,
            caches, documents);
        ReflectionTestUtils.setField(service, "maxDocuments", 1000L);
    }

    @Test
    void une_demande_de_1000_documents_est_acceptee_et_tracee_avec_le_nombre_de_documents()
    {
        when(documents.compterParUnites(List.of(5L))).thenReturn(1000L);

        service.declencher(Portee.UO, null, List.of(5L), principal);

        verify(executor).executer(eq(Portee.UO), any(), eq(List.of(5L)), anyString(), any(), anyList());
        verify(audit).log(any(User.class), eq(AuditAction.FIXITY_CHECK_DEMANDE), eq(AuditCible.DOCUMENT), any(), any(),
            contains("1000 document(s)"), eq(true));
    }

    @Test
    void une_demande_de_plus_de_1000_documents_est_refusee_sans_rien_lancer()
    {
        when(documents.compterParUnites(List.of(5L))).thenReturn(1001L);

        assertThatThrownBy(() -> service.declencher(Portee.UO, null, List.of(5L), principal))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("1001").hasMessageContaining("1000").hasMessageContaining("Réduisez");

        verify(executor, never()).executer(any(), any(), any(), anyString(), any(), anyList());
        assertThat(caches.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN).get("uo:5")).isNull();
    }

    @Test
    void le_controle_total_n_est_pas_plafonne_mais_garde_son_delai_de_6_heures()
    {
        when(documents.count()).thenReturn(250_000L);

        service.declencher(Portee.TOUT, null, null, principal);

        assertThatThrownBy(() -> service.declencher(Portee.TOUT, null, null, principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("contrôle total").hasMessageContaining("6 h");
        verify(executor, times(1)).executer(eq(Portee.TOUT), any(), any(), anyString(), any(), anyList());
    }

    @Test
    void un_controle_cible_ne_change_pas_la_date_du_controle_total()
    {
        when(documents.compterParUnites(anyList())).thenReturn(10L);
        when(documents.count()).thenReturn(5_000L);
        var cache = caches.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN);

        service.declencher(Portee.TOUT, null, null, principal);
        java.time.Instant dateTotal = cache.get("tout", java.time.Instant.class);

        service.declencher(Portee.UO, null, List.of(5L), principal);
        service.declencher(Portee.UO, null, List.of(6L), principal);

        assertThat(cache.get("tout", java.time.Instant.class)).isEqualTo(dateTotal);
    }

    @Test
    void un_controle_cible_ne_declenche_pas_le_delai_du_controle_total()
    {
        when(documents.compterParUnites(anyList())).thenReturn(10L);
        when(documents.count()).thenReturn(5_000L);

        service.declencher(Portee.UO, null, List.of(5L), principal);

        // le contrôle total reste possible juste après, car aucun contrôle total n'a eu lieu
        service.declencher(Portee.TOUT, null, null, principal);
        verify(executor).executer(eq(Portee.TOUT), any(), any(), anyString(), any(), anyList());
    }

    @Test
    void aucun_delai_entre_deux_controles_cibles_une_fois_le_precedent_termine()
    {
        when(documents.compterParUnites(List.of(5L))).thenReturn(10L);

        service.declencher(Portee.UO, null, List.of(5L), principal);
        // fin du contrôle : l'exécuteur libère le périmètre
        caches.getCache(RedisCacheConfig.CACHE_FIXITY_COOLDOWN).evict("uo:5");
        service.declencher(Portee.UO, null, List.of(5L), principal);

        verify(executor, times(2)).executer(eq(Portee.UO), any(), eq(List.of(5L)), anyString(), any(), anyList());
    }

    @Test
    void pas_de_second_controle_tant_que_le_premier_est_en_cours_sur_le_meme_perimetre()
    {
        when(documents.compterParUnites(List.of(5L))).thenReturn(10L);

        service.declencher(Portee.UO, null, List.of(5L), principal);

        assertThatThrownBy(() -> service.declencher(Portee.UO, null, List.of(5L), principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("déjà en cours");
        verify(executor, times(1)).executer(any(), any(), any(), anyString(), any(), anyList());
    }

    @Test
    void un_autre_perimetre_reste_disponible_pendant_qu_un_controle_tourne()
    {
        when(documents.compterParUnites(anyList())).thenReturn(10L);

        service.declencher(Portee.UO, null, List.of(5L), principal);
        service.declencher(Portee.UO, null, List.of(6L), principal);

        verify(executor, times(2)).executer(any(), any(), any(), anyString(), any(), anyList());
    }
}
