import DocumentsAccessibles from '../document/DocumentsAccessible';

interface DocumentsArchivesPanelProps {
    /** null = pas de restriction supplémentaire, tout le périmètre autorisé (Admin : tout ; Admin_UO : son UO + descendantes). */
    uoId: number | null;
}

/**
 * Onglet "Documents archivés" côté Admin/Admin_UO — réutilise le composant
 * complet (filtres, lecteur PDF, téléchargement, gestion d'accès, suppression
 * d'un document corrompu) partagé avec les vues Editor/User, juste restreint
 * à l'UO sélectionnée dans l'arbre.
 */
function DocumentsArchivesPanel({ uoId }: DocumentsArchivesPanelProps) {
    return <DocumentsAccessibles uoId={uoId} />;
}

export default DocumentsArchivesPanel;
