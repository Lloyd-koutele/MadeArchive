package made.archive.entite;

/**
 * Contrainte d'acceptation d'un point de stockage (storagePoint=true
 * uniquement — sans effet sur un nœud chemin) — voir
 * PhysicalLocationService.resolvePourRattachement pour l'application de
 * cette contrainte à chaque rattachement de document.
 */
public enum LocationModeContrainte
{
    /** Comportement historique — aucune contrainte, accepte n'importe quel document. */
    LIBRE,

    /** N'accepte que des documents d'UN SEUL type de document (voir PhysicalLocation.typeDocumentAccepte). */
    TYPE_UNIQUE,

    /** N'accepte que des documents rattachés à UN SEUL dossier précis (voir
     *  PhysicalLocation.dossier) — appartenance DIRECTE uniquement, jamais un
     *  sous-dossier ni un dossier ancêtre. Plusieurs nœuds PEUVENT pointer
     *  vers le même dossier (un dossier volumineux peut ainsi occuper
     *  plusieurs "boîtes" physiques) — le choix de LAQUELLE utiliser à
     *  chaque archivage reste entièrement manuel (voir recap), jamais
     *  automatique. */
    DOSSIER
}
