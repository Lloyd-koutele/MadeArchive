package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limite le nombre de téléchargements HTTP simultanés effectués par l'import
 * via lien web (WebImportService — chemin fichier direct / page web, PAS le
 * navigateur headless qui a sa propre limite, voir WebImportHeadlessProperties).
 *
 * Chaque téléchargement est bufferisé entièrement en mémoire (jusqu'à 50 Mo
 * par fichier) — sans cette limite, un afflux de demandes simultanées (100 à
 * quelques milliers, sous le plafond de threads Tomcat) pourrait à lui seul
 * consommer plusieurs Go de tas JVM et dégrader TOUTE l'application (upload,
 * consultation de documents...), qui partage le même processus. Plus permissif
 * que la limite du navigateur headless (3 par défaut) car un téléchargement
 * HTTP simple coûte bien moins cher en ressources qu'un onglet Chromium.
 *
 * Défaut 100 → ~5 Go de pire cas pour cette seule fonctionnalité (100 × 50 Mo).
 * Ce chiffre suppose un serveur dimensionné en conséquence pour TOUT le reste
 * de la pile (Ollama à lui seul — un modèle 7B chargé — consomme déjà
 * couramment 4,5-5,5 Go) : viser au moins 16 Go de RAM pour le serveur, pas
 * 8 Go, si ce défaut est conservé. Sur un serveur plus modeste, redescendre
 * cette valeur (ex. 30-40, ~1,5-2 Go) plutôt que de la garder à 100.
 */
@Data
@Component
@ConfigurationProperties(prefix = "web-import.http")
public class WebImportHttpProperties
{
    private int maxConcurrent       = 100;
    private int attenteSlotSecondes = 10;
}
