package made.archive.dto;

import lombok.Builder;
import lombok.Data;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AuditLogDto
{
    private Long id;
    private Instant horodatage;

    private UUID   acteurId;
    private String acteurEmail;
    private String acteurRole;
    private String adresseIp;

    /**
     * Qui est à l'origine d'une entrée sans utilisateur connecté : SYSTEME (tâche planifiée ou traitement interne,
     * aucune requête HTTP, donc aucune adresse IP) ou VISITEUR (personne non connectée, par exemple lors d'une
     * vérification publique : son adresse IP est enregistrée). Null quand un utilisateur est identifié.
     * Calculé à partir de l'entrée d'origine, indépendamment du masquage de l'adresse IP dans cette réponse.
     */
    private String origine;

    private AuditAction action;
    private AuditCible  cibleType;
    private String      cibleId;
    private Long         uoId;

    private String  description;
    private boolean succes;
    private String  details;

    public static final String ORIGINE_SYSTEME = "SYSTEME";
    public static final String ORIGINE_VISITEUR = "VISITEUR";

    public static String origineDe(UUID acteurId, String acteurEmail, String adresseIp)
    {
        if (acteurId != null || (acteurEmail != null && !acteurEmail.isBlank()))
        {
            return null;
        }
        return adresseIp == null || adresseIp.isBlank() ? ORIGINE_SYSTEME : ORIGINE_VISITEUR;
    }

    /** Libellé de l'acteur pour un export : l'e-mail, sinon « Système » ou « Visiteur anonyme ». */
    public String libelleActeur()
    {
        if (acteurEmail != null && !acteurEmail.isBlank()) return acteurEmail;
        return ORIGINE_VISITEUR.equals(origine) ? "Visiteur anonyme" : "Système";
    }
}
