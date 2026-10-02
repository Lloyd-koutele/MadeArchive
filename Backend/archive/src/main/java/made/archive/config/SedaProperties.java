package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Valeurs propres au service d'archives DESTINATAIRE d'un paquet SEDA — voir
 * SedaExportGenerationService. Les défauts sont VOLONTAIREMENT des marqueurs
 * à remplacer (le schéma SEDA les exige non vides, mais ils n'ont de sens que
 * dans le référentiel du destinataire : accord de versement, identifiant du
 * service d'archives, codes de règles de gestion).
 */
@Data
@Component
@ConfigurationProperties(prefix = "seda")
public class SedaProperties
{
    /** ArchivalAgency/Identifier — le service d'archives qui reçoit le paquet. */
    private String archivalAgencyIdentifier = "SERVICE_ARCHIVES_A_RENSEIGNER";

    /** ArchivalAgreement — identifiant du contrat/accord de versement convenu avec lui. */
    private String archivalAgreement = "ACCORD_VERSEMENT_A_RENSEIGNER";

    /** TransferringAgency quand l'export couvre PLUSIEURS UO (une seule UO : c'est elle). */
    private String transferringAgencyDefault = "MADEARCHIVE";

    /** Préfixe du code de règle de durée d'utilité administrative : "<préfixe><N>ANS"
     *  (ex. DUA-5ANS). Doit exister dans le référentiel de règles du destinataire (Vitam). */
    private String appraisalRulePrefix = "DUA-";
}
