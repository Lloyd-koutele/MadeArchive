import type { DocumentDetailDto } from '../services/document/DocumentService';

/**
 * Affiche l'emplacement physique d'un document — LECTURE SEULE (partagé
 * entre document/DocumentsAccessible.tsx et Editor/MesDocumentsEditor.tsx).
 * Le changement d'emplacement se fait désormais depuis la liste des
 * documents (sélection Cmd/Ctrl+clic + clic droit → "Changer l'emplacement
 * physique"), jamais depuis le détail d'un document — un seul point
 * d'action, cohérent avec le même choix fait pour le dossier (voir
 * DossierAttachSection).
 */
function EmplacementPhysiqueSection({ detail }: { detail: DocumentDetailDto }) {
    if (!detail.physicalLocationPath) {
        return null;
    }

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Emplacement physique :</strong>
            <span>{detail.physicalLocationPath}</span>
        </div>
    );
}

export default EmplacementPhysiqueSection;
