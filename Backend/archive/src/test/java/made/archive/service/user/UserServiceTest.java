package made.archive.service.user;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import made.archive.entite.AuditAction;
import made.archive.entite.MembreUniteOrganisationnelle;
import made.archive.entite.PkiKeyStatus;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.JournalAuditRepository;
import made.archive.repository.MembreUORepository;
import made.archive.repository.RoleRepository;
import made.archive.repository.UserActiveTokenRepository;
import made.archive.repository.UserRepository;
import made.archive.security.AuthCacheService;
import made.archive.security.SessionInvalidationService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Suppression d'utilisateur (voir UserService.supprimerUtilisateur) — la pièce la
 * plus sensible de tout le fichier : irréversible, et deux chemins radicalement
 * différents selon que le compte a déjà servi ou non.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class UserServiceTest
{
    @Mock private UserRepository                   userRepository;
    @Mock private PasswordEncoder                  passwordEncoder;
    @Mock private RoleRepository                   roleRepository;
    @Mock private UniteOrganisationnelleService    uniteOrganisationnelleService;
    @Mock private AuditLogService                  auditLogService;
    @Mock private UserActiveTokenRepository        activeTokenRepository;
    @Mock private AuthCacheService                 authCacheService;
    @Mock private SessionInvalidationService       sessionInvalidationService;
    @Mock private MembreUORepository               membreUORepository;
    @Mock private JournalAuditRepository           journalAuditRepository;

    @InjectMocks
    private UserService service;

    private User utilisateur(Role_Name... roles)
    {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("cible@esp.sn");
        Set<Role> ensemble = new HashSet<>();
        for (Role_Name r : roles) ensemble.add(new Role(null, r));
        user.setRoles(ensemble);
        return user;
    }

    @Test
    void refuseDeSeSupprimerSoiMeme()
    {
        User admin = utilisateur(Role_Name.ADMIN);

        assertThatThrownBy(() -> service.supprimerUtilisateur(admin.getId(), admin))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void refuseDeSupprimerLeDernierAdmin()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User dernierAdmin = utilisateur(Role_Name.ADMIN);

        when(userRepository.findById(dernierAdmin.getId())).thenReturn(Optional.of(dernierAdmin));
        when(userRepository.findByRoleName(Role_Name.ADMIN)).thenReturn(List.of(dernierAdmin));

        assertThatThrownBy(() -> service.supprimerUtilisateur(dernierAdmin.getId(), admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("dernier administrateur");

        verify(userRepository, never()).delete(any());
    }

    @Test
    void unAdminUoNePeutPasSupprimerUnAdmin()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdmin = utilisateur(Role_Name.ADMIN);

        when(userRepository.findById(cibleAdmin.getId())).thenReturn(Optional.of(cibleAdmin));

        assertThatThrownBy(() -> service.supprimerUtilisateur(cibleAdmin.getId(), adminUo))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unAdminUoNePeutPasSupprimerUnAutreAdminUo()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdminUo = utilisateur(Role_Name.ADMIN_UO);

        when(userRepository.findById(cibleAdminUo.getId())).thenReturn(Optional.of(cibleAdminUo));

        assertThatThrownBy(() -> service.supprimerUtilisateur(cibleAdminUo.getId(), adminUo))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void refuseSiLeCompteEstDejaSupprime()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setSupprimeLe(java.time.Instant.now());

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));

        assertThatThrownBy(() -> service.supprimerUtilisateur(cible.getId(), admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà supprimé");
    }

    @Test
    void unCompteJamaisConnecteEstReellementSupprime()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        MembreUniteOrganisationnelle adhesion = new MembreUniteOrganisationnelle();

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(false);
        when(membreUORepository.findByUserId(cible.getId())).thenReturn(List.of(adhesion));

        service.supprimerUtilisateur(cible.getId(), admin);

        // La ligne d'adhésion (créée à la création du compte, jamais utilisée par
        // la cible elle-même) est retirée AVANT le DELETE pour ne pas violer la FK.
        verify(membreUORepository).deleteAll(List.of(adhesion));
        verify(userRepository).delete(cible);
        // Chemin "jamais servi" : aucune des étapes de la suppression logique.
        verify(sessionInvalidationService, never()).invalider(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void unCompteDejaConnecteEstSupprimeLogiquementSansHardDelete()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setPkiKeyStatus(PkiKeyStatus.ACTIVE);
        cible.setEmail("cible@esp.sn");
        cible.setNom("Dupont");
        cible.setPrenom("Awa");

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(true);
        lenient().when(membreUORepository.findByUserIdAndActifTrue(cible.getId())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("un-hash-aleatoire-impossible-a-deviner");

        service.supprimerUtilisateur(cible.getId(), admin);

        // Jamais de DELETE réel une fois que le compte a servi.
        verify(userRepository, never()).delete(any());

        // Identité conservée — c'est la demande explicite : rester lisible sur les
        // documents/projets/exports déjà réalisés par ce compte.
        assertThat(cible.getEmail()).isEqualTo("cible@esp.sn");
        assertThat(cible.getNom()).isEqualTo("Dupont");
        assertThat(cible.getPrenom()).isEqualTo("Awa");

        // Ce qui doit être coupé : connexion (mot de passe + actif) et signature (PKI).
        assertThat(cible.getPassword()).isEqualTo("un-hash-aleatoire-impossible-a-deviner");
        assertThat(cible.isActif()).isFalse();
        assertThat(cible.getPkiKeyStatus()).isEqualTo(PkiKeyStatus.REVOKED);
        assertThat(cible.getSupprimeLe()).isNotNull();

        verify(sessionInvalidationService).invalider(eq(cible), eq(SessionInvalidationService.RAISON_COMPTE_SUPPRIME));
    }

    @Test
    void unCompteDejaConnecteSansClePkiActiveNestPasRevoque()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.USER);
        cible.setPkiKeyStatus(PkiKeyStatus.NONE);

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(true);
        lenient().when(membreUORepository.findByUserIdAndActifTrue(cible.getId())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hash");

        service.supprimerUtilisateur(cible.getId(), admin);

        assertThat(cible.getPkiKeyStatus()).isEqualTo(PkiKeyStatus.NONE);
    }
}
