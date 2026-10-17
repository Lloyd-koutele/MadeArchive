package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeDocument;
import made.archive.entite.User;
import made.archive.repository.TypeDocumentRepository;
import made.archive.service.organisation.UniteOrganisationnelleService;

/** Le filtre « Type de document » ne propose le type système des procès-verbaux qu'à ceux qui peuvent voir ces documents. */
@ExtendWith(MockitoExtension.class)
class TypeDocumentVisiblesTest
{
    @Mock private TypeDocumentRepository typeDocumentRepository;
    @Mock private UniteOrganisationnelleService uniteOrganisationnelleService;
    @InjectMocks private TypeDocumentService service;

    private static TypeDocument type(String nom, boolean systeme)
    {
        TypeDocument t = new TypeDocument();
        t.setNom(nom);
        t.setSysteme(systeme);
        return t;
    }

    private static User avecRole(Role_Name role)
    {
        User u = new User();
        Set<Role> roles = new HashSet<>();
        roles.add(new Role(null, role));
        u.setRoles(roles);
        return u;
    }

    private void deuxTypes(User u)
    {
        when(uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(u)).thenReturn(null);   // ADMIN : tout le périmètre
        when(typeDocumentRepository.findAllWithRetentionAndMetaData())
            .thenReturn(List.of(type("Facture", false), type("Procès-verbal d'élimination", true)));
    }

    @Test
    void unSimpleUtilisateurNeVoitPasLeTypeDesProcesVerbaux()
    {
        User u = avecRole(Role_Name.USER);
        deuxTypes(u);

        assertThat(service.getTypeDocumentsVisibles(u, null)).extracting(TypeDocument::getNom).containsExactly("Facture");
    }

    @Test
    void lesEditeursEtLesAdministrateursVoientLeTypeDesProcesVerbaux()
    {
        for (Role_Name r : List.of(Role_Name.EDITOR, Role_Name.ADMIN_UO, Role_Name.ADMIN))
        {
            User u = avecRole(r);
            deuxTypes(u);
            assertThat(service.getTypeDocumentsVisibles(u, null)).extracting(TypeDocument::getNom)
                .containsExactly("Facture", "Procès-verbal d'élimination");
        }
    }
}
