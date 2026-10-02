import type { DocumentDetailDto } from '../services/document/DocumentService';

interface DossierAttachSectionProps {
    detail: DocumentDetailDto;
}

/**
 * Affiche le dossier d'un document — LECTURE SEULE (partagé entre
 * MesDocumentsEditor.tsx et DocumentsAccessible.tsx). Le déplacement d'un
 * document vers un autre dossier se fait désormais depuis la liste des
 * documents (sélection Cmd/Ctrl+clic + clic droit → "Déplacer"), jamais
 * depuis le détail d'un document — un seul point d'action, cohérent avec
 * le même choix fait pour l'emplacement physique (voir
 * EmplacementPhysiqueSection).
 */
function DossierAttachSection({ detail }: DossierAttachSectionProps) {
    if (!detail.dossierNom) {
        return null;
    }

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Dossier :</strong>
            <span>{detail.dossierCheminComplet ?? detail.dossierNom}</span>
        </div>
    );
}

export default DossierAttachSection;
