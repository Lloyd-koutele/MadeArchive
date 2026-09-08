package made.archive.service.user;

import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Suppression d'utilisateur — en deux temps depuis l'ajout du délai de grâce de
 * 2 jours (raison de sécurité : un ADMIN malveillant ou compromis ne doit pas
 * pouvoir détruire un compte de façon instantanée et irréversible) :
 *   1. demanderSuppression — bloque immédiatement (réversible), programme
 *      l'exécution ;
 *   2. executerSuppressionsEnAttente — appelée par le scheduler une fois le
 *      délai écoulé, exécute réellement (DELETE si jamais connecté, sinon
 *      logique et irréversible).
 * annulerSuppression permet à un AUTRE administrateur de contrer l'étape 1
 * avant que l'étape 2 ne s'exécute.
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

    // ───────────────────────── demanderSuppression ─────────────────────────

    @Test
    void refuseDeSeSupprimerSoiMeme()
    {
        User admin = utilisateur(Role_Name.ADMIN);

        assertThatThrownBy(() -> service.demanderSuppression(admin.getId(), admin))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void refuseDeSupprimerLeDernierAdmin()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User dernierAdmin = utilisateur(Role_Name.ADMIN);

        when(userRepository.findById(dernierAdmin.getId())).thenReturn(Optional.of(dernierAdmin));
        when(userRepository.findByRoleName(Role_Name.ADMIN)).thenReturn(List.of(dernierAdmin));

        assertThatThrownBy(() -> service.demanderSuppression(dernierAdmin.getId(), admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("dernier administrateur");

        assertThat(dernierAdmin.getSuppressionPrevueLe()).isNull();
    }

    @Test
    void unAdminUoNePeutPasDemanderLaSuppressionDUnAdmin()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdmin = utilisateur(Role_Name.ADMIN);

        when(userRepository.findById(cibleAdmin.getId())).thenReturn(Optional.of(cibleAdmin));

        assertThatThrownBy(() -> service.demanderSuppression(cibleAdmin.getId(), adminUo))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unAdminUoNePeutPasSupprimerUnAutreAdminUoHorsDeSonAutorite()
    {
        // Un ADMIN_UO n'est PLUS bloqué inconditionnellement sur un autre
        // ADMIN_UO — il reste scopé par autorité (sa UO ou une UO descendante),
        // exactement comme pour n'importe quelle autre cible. Ici l'autorité
        // est explicitement absente (hors de son sous-arbre).
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdminUo = utilisateur(Role_Name.ADMIN_UO);

        when(userRepository.findById(cibleAdminUo.getId())).thenReturn(Optional.of(cibleAdminUo));
        when(uniteOrganisationnelleService.aAutoriteSurUtilisateur(cibleAdminUo.getId(), adminUo))
            .thenReturn(false);

        assertThatThrownBy(() -> service.demanderSuppression(cibleAdminUo.getId(), adminUo))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unAdminUoPeutSupprimerUnAutreAdminUoDeSaPropreAutorite()
    {
        // Nouveau comportement demandé : un ADMIN_UO PEUT désormais demander la
        // suppression d'un autre ADMIN_UO tant que celui-ci relève de sa UO ou
        // d'une UO descendante — seul un ADMIN global reste hors de portée
        // (voir unAdminUoNePeutPasDemanderLaSuppressionDUnAdmin ci-dessus).
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdminUo = utilisateur(Role_Name.ADMIN_UO);

        when(userRepository.findById(cibleAdminUo.getId())).thenReturn(Optional.of(cibleAdminUo));
        when(uniteOrganisationnelleService.aAutoriteSurUtilisateur(cibleAdminUo.getId(), adminUo))
            .thenReturn(true);
        when(journalAuditRepository.existsByActeurIdAndAction(cibleAdminUo.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(true);

        boolean immediat = service.demanderSuppression(cibleAdminUo.getId(), adminUo);

        assertThat(immediat).isFalse();
        assertThat(cibleAdminUo.isActif()).isFalse();
        assertThat(cibleAdminUo.getSuppressionPrevueLe()).isEqualTo(LocalDate.now().plusDays(2));
    }

    @Test
    void refuseSiLeCompteEstDejaSupprime()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setSupprimeLe(java.time.Instant.now());

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));

        assertThatThrownBy(() -> service.demanderSuppression(cible.getId(), admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà supprimé");
    }

    @Test
    void refuseSiUneSuppressionEstDejaEnAttente()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setSuppressionPrevueLe(LocalDate.now().plusDays(1));

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));

        assertThatThrownBy(() -> service.demanderSuppression(cible.getId(), admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà en attente");
    }

    @Test
    void demanderSuppressionBloqueImmediatementSansRienDetruire()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setPkiKeyStatus(PkiKeyStatus.ACTIVE);
        cible.setPassword("hash-original");
        cible.setEmail("cible@esp.sn");

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));
        // Compte déjà connecté : c'est le seul cas qui passe par le délai de
        // grâce — voir demanderSuppressionSupprimeImmediatementSiJamaisConnecte
        // pour le cas contraire.
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(true);

        boolean immediat = service.demanderSuppression(cible.getId(), admin);

        assertThat(immediat).isFalse();

        // Bloqué tout de suite, comme un blocage classique — entièrement réversible.
        assertThat(cible.isActif()).isFalse();
        assertThat(cible.getSuppressionPrevueLe()).isEqualTo(LocalDate.now().plusDays(2));
        verify(sessionInvalidationService).invalider(eq(cible), eq(SessionInvalidationService.RAISON_COMPTE_SUPPRIME));

        // Rien d'IRRÉVERSIBLE tant que le délai n'est pas écoulé.
        assertThat(cible.getPassword()).isEqualTo("hash-original");
        assertThat(cible.getPkiKeyStatus()).isEqualTo(PkiKeyStatus.ACTIVE);
        assertThat(cible.getSupprimeLe()).isNull();
        verify(userRepository, never()).delete(any());
    }

    @Test
    void demanderSuppressionSupprimeImmediatementSiJamaisConnecte()
    {
        // Automatique : pas de délai de grâce pour un compte qui n'a jamais servi
        // — rien à protéger d'un ADMIN malveillant, voir la Javadoc de la méthode.
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.USER);
        MembreUniteOrganisationnelle adhesion = new MembreUniteOrganisationnelle();

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(false);
        when(membreUORepository.findByUserId(cible.getId())).thenReturn(List.of(adhesion));

        boolean immediat = service.demanderSuppression(cible.getId(), admin);

        assertThat(immediat).isTrue();
        verify(membreUORepository).deleteAll(List.of(adhesion));
        verify(userRepository).delete(cible);
        // Aucune des étapes du chemin "en attente" — jamais bloqué, jamais programmé.
        assertThat(cible.getSuppressionPrevueLe()).isNull();
        verify(sessionInvalidationService, never()).invalider(any(), any());
        verify(userRepository, never()).save(any());
    }

    // ───────────────────────── annulerSuppression ─────────────────────────

    @Test
    void refuseDAnnulerSiAucuneSuppressionEnAttente()
    {
        User admin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));

        assertThatThrownBy(() -> service.annulerSuppression(cible.getId(), admin))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Aucune suppression en attente");
    }

    @Test
    void annulerSuppressionReactiveLeCompteEtEffaceLEcheance()
    {
        // Un AUTRE admin que celui qui a demandé la suppression, précisément le
        // scénario de sécurité visé : contrer une suppression malveillante.
        User autreAdmin = utilisateur(Role_Name.ADMIN);
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setSuppressionPrevueLe(LocalDate.now().plusDays(1));
        cible.setActif(false);

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));

        service.annulerSuppression(cible.getId(), autreAdmin);

        assertThat(cible.getSuppressionPrevueLe()).isNull();
        assertThat(cible.isActif()).isTrue();
    }

    @Test
    void unAdminUoNePeutPasAnnulerUneSuppressionDUnAdmin()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdmin = utilisateur(Role_Name.ADMIN);
        cibleAdmin.setSuppressionPrevueLe(LocalDate.now().plusDays(1));

        when(userRepository.findById(cibleAdmin.getId())).thenReturn(Optional.of(cibleAdmin));

        assertThatThrownBy(() -> service.annulerSuppression(cibleAdmin.getId(), adminUo))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unAdminUoNePeutPasAnnulerUneSuppressionDUnAutreAdminUoHorsDeSonAutorite()
    {
        // Même modèle que demanderSuppression : un ADMIN_UO n'est plus bloqué
        // par principe sur un autre ADMIN_UO, mais reste scopé par autorité.
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdminUo = utilisateur(Role_Name.ADMIN_UO);
        cibleAdminUo.setSuppressionPrevueLe(LocalDate.now().plusDays(1));

        when(userRepository.findById(cibleAdminUo.getId())).thenReturn(Optional.of(cibleAdminUo));
        when(uniteOrganisationnelleService.aAutoriteSurUtilisateur(cibleAdminUo.getId(), adminUo))
            .thenReturn(false);

        assertThatThrownBy(() -> service.annulerSuppression(cibleAdminUo.getId(), adminUo))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unAdminUoPeutAnnulerUneSuppressionDUnAutreAdminUoDeSaPropreAutorite()
    {
        User adminUo = utilisateur(Role_Name.ADMIN_UO);
        User cibleAdminUo = utilisateur(Role_Name.ADMIN_UO);
        cibleAdminUo.setSuppressionPrevueLe(LocalDate.now().plusDays(1));
        cibleAdminUo.setActif(false);

        when(userRepository.findById(cibleAdminUo.getId())).thenReturn(Optional.of(cibleAdminUo));
        when(uniteOrganisationnelleService.aAutoriteSurUtilisateur(cibleAdminUo.getId(), adminUo))
            .thenReturn(true);

        service.annulerSuppression(cibleAdminUo.getId(), adminUo);

        assertThat(cibleAdminUo.getSuppressionPrevueLe()).isNull();
        assertThat(cibleAdminUo.isActif()).isTrue();
    }

    // ─────────────────────── executerSuppressionsEnAttente ───────────────────────

    @Test
    void unCompteJamaisConnecteRestantEnAttenteEstQuandMemeSupprimeParSecurite()
    {
        // Cas défensif — voir Javadoc d'executerSuppressionsEnAttente : ne devrait
        // normalement jamais se produire (demanderSuppression supprime un compte
        // jamais connecté tout de suite, sans jamais passer par suppressionPrevueLe).
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setSuppressionPrevueLe(LocalDate.now().minusDays(1));
        MembreUniteOrganisationnelle adhesion = new MembreUniteOrganisationnelle();

        when(userRepository.findBySuppressionPrevueLeLessThanEqualAndSupprimeLeIsNull(LocalDate.now()))
            .thenReturn(List.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(false);
        when(membreUORepository.findByUserId(cible.getId())).thenReturn(List.of(adhesion));

        service.executerSuppressionsEnAttente();

        // La ligne d'adhésion (créée à la création du compte, jamais utilisée par
        // la cible elle-même) est retirée AVANT le DELETE pour ne pas violer la FK.
        verify(membreUORepository).deleteAll(List.of(adhesion));
        verify(userRepository).delete(cible);
        // Chemin "jamais servi" : aucune des étapes de la suppression logique,
        // et pas de second appel à invalider() — déjà fait à la demande.
        verify(sessionInvalidationService, never()).invalider(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void unCompteDejaConnecteEstSupprimeLogiquementSansHardDelete()
    {
        User cible = utilisateur(Role_Name.EDITOR);
        cible.setSuppressionPrevueLe(LocalDate.now().minusDays(1));
        cible.setPkiKeyStatus(PkiKeyStatus.ACTIVE);
        cible.setEmail("cible@esp.sn");
        cible.setNom("Dupont");
        cible.setPrenom("Awa");

        when(userRepository.findBySuppressionPrevueLeLessThanEqualAndSupprimeLeIsNull(LocalDate.now()))
            .thenReturn(List.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(true);
        lenient().when(membreUORepository.findByUserIdAndActifTrue(cible.getId())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("un-hash-aleatoire-impossible-a-deviner");

        service.executerSuppressionsEnAttente();

        // Jamais de DELETE réel une fois que le compte a servi.
        verify(userRepository, never()).delete(any());

        // Identité conservée — c'est la demande explicite : rester lisible sur les
        // documents/projets/exports déjà réalisés par ce compte.
        assertThat(cible.getEmail()).isEqualTo("cible@esp.sn");
        assertThat(cible.getNom()).isEqualTo("Dupont");
        assertThat(cible.getPrenom()).isEqualTo("Awa");

        // Ce qui devient IRRÉVERSIBLE une fois le délai écoulé : mot de passe + PKI.
        assertThat(cible.getPassword()).isEqualTo("un-hash-aleatoire-impossible-a-deviner");
        assertThat(cible.isActif()).isFalse();
        assertThat(cible.getPkiKeyStatus()).isEqualTo(PkiKeyStatus.REVOKED);
        assertThat(cible.getSupprimeLe()).isNotNull();
        assertThat(cible.getSuppressionPrevueLe()).isNull();
    }

    @Test
    void unCompteDejaConnecteSansClePkiActiveNestPasRevoque()
    {
        User cible = utilisateur(Role_Name.USER);
        cible.setSuppressionPrevueLe(LocalDate.now().minusDays(1));
        cible.setPkiKeyStatus(PkiKeyStatus.NONE);

        when(userRepository.findBySuppressionPrevueLeLessThanEqualAndSupprimeLeIsNull(LocalDate.now()))
            .thenReturn(List.of(cible));
        when(journalAuditRepository.existsByActeurIdAndAction(cible.getId(), AuditAction.LOGIN_REUSSI))
            .thenReturn(true);
        lenient().when(membreUORepository.findByUserIdAndActifTrue(cible.getId())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hash");

        service.executerSuppressionsEnAttente();

        assertThat(cible.getPkiKeyStatus()).isEqualTo(PkiKeyStatus.NONE);
    }

    @Test
    void nExecuteRienSiAucuneSuppressionNEstEchue()
    {
        when(userRepository.findBySuppressionPrevueLeLessThanEqualAndSupprimeLeIsNull(LocalDate.now()))
            .thenReturn(List.of());

        service.executerSuppressionsEnAttente();

        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).save(any());
    }
}
