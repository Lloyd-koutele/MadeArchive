package made.archive.service.organisation;

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

import made.archive.dto.UOParentIdPair;
import made.archive.entite.MembreUniteOrganisationnelle;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.repository.MembreUORepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.security.SessionInvalidationService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie le scoping d'autorité ADMIN / ADMIN_UO / EDITOR-USER (voir 5.8.1 du
 * mémoire et son usage direct dans DocumentExportService) — c'est une pièce
 * de sécurité transversale : toute erreur ici élargit ou restreint
 * silencieusement l'accès au journal d'audit, à l'export administratif, etc.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class UniteOrganisationnelleServiceTest
{
    @Mock private UniteOrganisationnelleRepository uoRepository;
    @Mock private UserRepository                   userRepository;
    @Mock private TypeDocumentRepository            typeDocumentRepository;
    @Mock private MembreUORepository                membreUORepository;
    @Mock private AuditLogService                   auditLogService;
    @Mock private UOTreeCacheService                uoTreeCacheService;
    @Mock private NotificationService               notificationService;
    @Mock private SessionInvalidationService        sessionInvalidationService;

    @InjectMocks
    private UniteOrganisationnelleService service;

    private User utilisateurAvecRole(Role_Name... roles)
    {
        User user = new User();
        user.setId(UUID.randomUUID());
        Set<Role> ensembleRoles = new java.util.HashSet<>();
        for (Role_Name r : roles)
        {
            ensembleRoles.add(new Role(null, r));
        }
        user.setRoles(ensembleRoles);
        return user;
    }

    @Test
    void unAdminGlobalNEstSoumisAAucuneRestriction()
    {
        User admin = utilisateurAvecRole(Role_Name.ADMIN);

        Set<Long> autorisees = service.getUoIdsSousAutorite(admin);

        // null = signal explicite "aucun filtrage", pas un ensemble vide —
        // voir la Javadoc de la méthode : un ensemble vide voudrait dire
        // "aucune UO autorisée", l'exact contraire de l'intention ADMIN.
        assertThat(autorisees).isNull();
    }

    @Test
    void unEditeurOuUnUtilisateurSimpleNAAucuneUOSousAutorite()
    {
        User editeur = utilisateurAvecRole(Role_Name.EDITOR);

        Set<Long> autorisees = service.getUoIdsSousAutorite(editeur);

        assertThat(autorisees).isEmpty();
    }

    @Test
    void unAdminUoEstRestreintASonUoEtSesDescendantes()
    {
        User adminUo = utilisateurAvecRole(Role_Name.ADMIN_UO);

        MembreUniteOrganisationnelle membre = new MembreUniteOrganisationnelle();
        UniteOrganisationnelle sonUo = new UniteOrganisationnelle();
        sonUo.setId(1L);
        membre.setUniteOrganisationnelle(sonUo);

        when(membreUORepository.findByUserIdAndActifTrue(adminUo.getId()))
            .thenReturn(Optional.of(membre));

        // Arbre : 1 (racine, son UO) -> 2, 3 (filles) ; 4 est une UO SANS
        // rapport (ex. une autre institution) — ne doit JAMAIS apparaître.
        lenient().when(uoTreeCacheService.chargerLiaisons()).thenReturn(List.of(
            new UOParentIdPair(2L, 1L),
            new UOParentIdPair(3L, 1L),
            new UOParentIdPair(4L, 99L)
        ));

        Set<Long> autorisees = service.getUoIdsSousAutorite(adminUo);

        assertThat(autorisees).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(autorisees).doesNotContain(4L, 99L);
    }

    @Test
    void unAdminUoSansUoActuelleNAAucuneUoSousAutorite()
    {
        User adminUo = utilisateurAvecRole(Role_Name.ADMIN_UO);

        when(membreUORepository.findByUserIdAndActifTrue(adminUo.getId()))
            .thenReturn(Optional.empty());

        Set<Long> autorisees = service.getUoIdsSousAutorite(adminUo);

        assertThat(autorisees).isEmpty();
    }

    @Test
    void changerUOUtilisateurInvalideLaSessionDeLUtilisateurTransfere()
    {
        User admin = utilisateurAvecRole(Role_Name.ADMIN);
        User cible = utilisateurAvecRole(Role_Name.EDITOR);
        cible.setEmail("cible@esp.sn");

        UniteOrganisationnelle ancienneUO = new UniteOrganisationnelle();
        ancienneUO.setId(1L);
        ancienneUO.setNom("Esp");
        UniteOrganisationnelle nouvelleUO = new UniteOrganisationnelle();
        nouvelleUO.setId(2L);
        nouvelleUO.setNom("MPI");

        MembreUniteOrganisationnelle ancienneMembership = new MembreUniteOrganisationnelle();
        ancienneMembership.setUniteOrganisationnelle(ancienneUO);

        when(userRepository.findById(cible.getId())).thenReturn(Optional.of(cible));
        when(membreUORepository.findByUserIdAndActifTrue(cible.getId()))
            .thenReturn(Optional.of(ancienneMembership));
        when(uoRepository.findById(2L)).thenReturn(Optional.of(nouvelleUO));

        service.changerUOUtilisateur(cible.getId(), 2L, admin);

        // Le transfert doit forcer la reconnexion de l'utilisateur déplacé — pas de
        // celui qui a demandé le transfert — avec la raison dédiée "UO_CHANGEE", pour
        // que SessionGuard côté client affiche le bon message (voir SecurityConfig).
        verify(sessionInvalidationService).invalider(eq(cible), eq(SessionInvalidationService.RAISON_UO_CHANGEE));
    }
}
