package made.archive.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Data;
import java.util.Set;
import java.util.UUID;

import made.archive.entite.Role;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDto 
{
    private UUID id;
    
    @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
    private String nom;
    
    @Size(min = 2, max = 100, message = "Le prénom doit contenir entre 2 et 100 caractères")
    private String prenom;
    
    @Email(message = "L'email n'est pas valide")
    private String email;
    
    @Size(min = 6, message = "Le mot de passe doit contenir au moins 6 caractères")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    
    // E.164 : '+' suivi de 8 à 15 chiffres, jamais un 0 en tête (indicatif pays
    // compris — voir PhoneNumberField.tsx côté client, qui produit ce format).
    // N'impose ce format qu'aux numéros SAISIS/MODIFIÉS désormais : les valeurs
    // déjà en base avant ce changement (formats hétérogènes, un indicatif
    // manquant, etc.) ne sont pas retouchées rétroactivement.
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Le numéro de téléphone doit être valide (avec l'indicatif du pays)")
    private String telephone;

    private Boolean actif =true;

    private Set<Role> roles;

    public Boolean isActif() 
    {
        return this.actif;
    }
}
