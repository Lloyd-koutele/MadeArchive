package made.archive.service.user;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import made.archive.entite.User;
import made.archive.repository.UserRepository;

/**
 * Compte TECHNIQUE « Système MadeArchive » : déposant des documents que l'application archive elle-même
 * (procès-verbaux d'élimination — Document.uploadedBy est obligatoire). Désactivé (actif = false) et doté
 * d'un mot de passe impossible à saisir : personne ne peut s'y connecter. Aucun rôle, aucune UO. Recréé
 * automatiquement s'il avait été supprimé ; masqué des listes d'utilisateurs (voir UserService.getAllUsers).
 * Pas de clé PKI : les documents qu'il dépose sont horodatés (RFC 3161) mais non signés.
 */
@Service
@RequiredArgsConstructor
public class UtilisateurSystemeService
{
    public static final String EMAIL = "systeme@madearchive.invalid";

    private final UserRepository userRepository;

    @Transactional
    public User obtenir()
    {
        return userRepository.findByEmail(EMAIL).orElseGet(() ->
        {
            User u = new User();
            u.setNom("Système");
            u.setPrenom("MadeArchive");
            u.setEmail(EMAIL);
            u.setPassword("!" + UUID.randomUUID());
            u.setActif(false);
            u.setTelephone("+00000000000");
            return userRepository.save(u);
        });
    }
}
