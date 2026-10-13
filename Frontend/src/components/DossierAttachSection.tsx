import type { DocumentDetailDto } from '../services/document/DocumentService';

interface DossierAttachSectionProps {
    detail: DocumentDetailDto;
}

/**
 * Dossier d'un document (partagé entre MesDocumentsEditor.tsx et DocumentsAccessible.tsx) : toujours affiché, y
 * compris « Aucun dossier ». Le changement de dossier se fait depuis « Reclasser » (Changer de dossier, voir
 * ReclasserSection) — un seul point d'action.
 */
function DossierAttachSection({ detail }: DossierAttachSectionProps) {
    return (
        <div className="details-row emplacement-physique-row">
            <strong>Dossier :</strong>
            <span>{detail.dossierNom ? (detail.dossierCheminComplet ?? detail.dossierNom) : 'Aucun dossier'}</span>
        </div>
    );
}

export default DossierAttachSection;
