package made.archive.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import made.archive.entite.Role;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserResponseDto 
{
    private UUID id;

    @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
    private String nom;
    
    @Size(min = 2, max = 100, message = "Le prénom doit contenir entre 2 et 100 caractères")
    private String prenom;
    
    @Email(message = "L'email n'est pas valide")
    private String email;
    
    @Size(min = 8, max = 20, message = "Le numéro de téléphone doit contenir entre 8 et 20 caractères")
    private String telephone;

    private Boolean actif = true;

    private Set<Role> roles;

    Long uoId;

    String uoNom;
}
