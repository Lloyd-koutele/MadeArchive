package made.archive.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.config.StorageEncryptionProperties;
import made.archive.entite.CleChiffrementReference;
import made.archive.exception.CleChiffrementException;
import made.archive.repository.CleChiffrementReferenceRepository;
import made.archive.security.ControleCleChiffrementService.Etat;

/**
 * L'empreinte de la clé permet de distinguer « mauvaise clé » (configuration) de « fichier altéré » : c'est ce qui
 * évite qu'une clé erronée fasse basculer toutes les archives en « corrompu ».
 */
@Tag("unit")
class ControleCleChiffrementServiceTest
{
    private StorageEncryptionProperties proprietes;
    private CleChiffrementReferenceRepository repository;
    private ControleCleChiffrementService service;

    private static String cleAleatoire()
    {
        byte[] cle = new byte[32];
        new SecureRandom().nextBytes(cle);
        return Base64.getEncoder().encodeToString(cle);
    }

    private ControleCleChiffrementService nouveau()
    {
        return new ControleCleChiffrementService(proprietes, repository);
    }

    @BeforeEach
    void setUp()
    {
        proprietes = new StorageEncryptionProperties();
        proprietes.setKey(cleAleatoire());
        repository = mock(CleChiffrementReferenceRepository.class);
        when(repository.findById(CleChiffrementReference.ID_UNIQUE)).thenReturn(Optional.empty());
        service = nouveau();
    }

    @Test
    void sansReference_laCleEstToleree_etLEtatLeDit()
    {
        assertThat(service.etat()).isEqualTo(Etat.REFERENCE_NON_INITIALISEE);
        service.exigerCleValide(); // ne lève pas
    }

    @Test
    void apresEnregistrement_laMemeCleEstValide_etSeuleLEmpreinteEstStockee()
    {
        service.enregistrerReference();

        org.mockito.ArgumentCaptor<CleChiffrementReference> capt =
            org.mockito.ArgumentCaptor.forClass(CleChiffrementReference.class);
        verify(repository).save(capt.capture());
        assertThat(capt.getValue().getId()).isEqualTo(1L);
        assertThat(capt.getValue().getEmpreinte()).hasSize(64).doesNotContain(proprietes.getKey());
        assertThat(service.etat()).isEqualTo(Etat.VALIDE);
    }

    @Test
    void uneCleDifferenteApresArchivage_estRefusee_sansRienConclureSurLesFichiers()
    {
        service.enregistrerReference();
        org.mockito.ArgumentCaptor<CleChiffrementReference> capt =
            org.mockito.ArgumentCaptor.forClass(CleChiffrementReference.class);
        verify(repository).save(capt.capture());

        // Redémarrage avec une AUTRE clé : la référence vient de la base.
        when(repository.findById(CleChiffrementReference.ID_UNIQUE)).thenReturn(Optional.of(capt.getValue()));
        proprietes.setKey(cleAleatoire());
        ControleCleChiffrementService apresRedemarrage = nouveau();

        assertThat(apresRedemarrage.etat()).isEqualTo(Etat.CLE_DIFFERENTE);
        assertThatThrownBy(apresRedemarrage::exigerCleValide)
            .isInstanceOf(CleChiffrementException.class)
            .hasMessageContaining("n'est plus celle utilisée");
    }

    @Test
    void cleAbsente_ouMalFormee_estRefusee()
    {
        proprietes.setKey("  ");
        assertThat(service.etat()).isEqualTo(Etat.CLE_ABSENTE);
        assertThatThrownBy(service::exigerCleValide).isInstanceOf(CleChiffrementException.class);

        proprietes.setKey("pas-du-base64!!");
        assertThat(service.etat()).isEqualTo(Etat.CLE_MAL_FORMEE);

        proprietes.setKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(service.etat()).isEqualTo(Etat.CLE_MAL_FORMEE);
    }

    @Test
    void enregistrerReference_estIdempotent_etNeRemplaceJamaisUneReferenceExistante()
    {
        service.enregistrerReference();
        service.enregistrerReference();
        verify(repository, org.mockito.Mockito.times(1)).save(any());

        // Une référence déjà en base (autre instance) n'est jamais réécrite.
        when(repository.findById(CleChiffrementReference.ID_UNIQUE))
            .thenReturn(Optional.of(new CleChiffrementReference(1L, "a".repeat(64), Instant.now())));
        ControleCleChiffrementService autre = nouveau();
        autre.enregistrerReference();
        verify(repository, org.mockito.Mockito.times(1)).save(any());
        assertThat(autre.etat()).isEqualTo(Etat.CLE_DIFFERENTE);
    }

    @Test
    void enregistrerReference_refuseUneCleAbsente()
    {
        proprietes.setKey(null);
        assertThatThrownBy(service::enregistrerReference).isInstanceOf(CleChiffrementException.class);
        verify(repository, never()).save(any());
    }
}
