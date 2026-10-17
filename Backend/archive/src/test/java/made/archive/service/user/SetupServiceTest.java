package made.archive.service.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import made.archive.dto.SetupAdminRequestDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;
import made.archive.repository.RoleRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;

/** La création de l'administrateur initial est la première trace du journal d'audit. */
class SetupServiceTest
{
    private UserRepository users;
    private RoleRepository roles;
    private AuditLogService audit;
    private SetupService service;

    @BeforeEach
    void init()
    {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        audit = mock(AuditLogService.class);
        service = new SetupService(users, roles, mock(PasswordEncoder.class), audit);
        when(roles.findByName(Role_Name.ADMIN)).thenReturn(Optional.of(new Role(1L, Role_Name.ADMIN)));
        when(users.save(any(User.class))).thenAnswer(i -> { User u = i.getArgument(0); u.setId(UUID.randomUUID()); return u; });
    }

    private SetupAdminRequestDto demande()
    {
        SetupAdminRequestDto d = new SetupAdminRequestDto();
        d.setNom("Admin"); d.setPrenom("Principal"); d.setEmail("admin@x.sn"); d.setPassword("Mdp12345!"); d.setTelephone("771234567");
        return d;
    }

    @Test
    void creationDeLAdminInitial_estJournalisee()
    {
        service.creerAdminInitial(demande());

        verify(audit).log(eq(null), eq(AuditAction.UTILISATEUR_CREE), eq(AuditCible.UTILISATEUR), any(), eq(null),
            contains("admin@x.sn"), eq(true));
    }

    @Test
    void siUnAdminExisteDeja_rienN_estCreeNiJournalise()
    {
        when(users.existsByRoleName(Role_Name.ADMIN)).thenReturn(true);

        try { service.creerAdminInitial(demande()); } catch (Exception ignoree) { }

        verify(users, never()).save(any(User.class));
        verify(audit, never()).log(any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
