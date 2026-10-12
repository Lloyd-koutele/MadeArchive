import { useState } from 'react';
import { getDocumentDetail } from '../services/document/DocumentService';
import type { DocumentDetailDto, DocumentListItemDto } from '../services/document/DocumentService';
import DeplacerDossierModal from './DeplacerDossierModal';
import { useNotify } from '../notifications/NotificationProvider';

interface DossierAttachSectionProps {
    detail: DocumentDetailDto;
    /** Reçoit le détail rafraîchi après un changement de dossier. Absent = lecture seule. */
    onUpdated?: (updated: DocumentDetailDto) => void;
}

/**
 * Dossier d'un document (partagé entre MesDocumentsEditor.tsx et DocumentsAccessible.tsx) : toujours affiché, y
 * compris « Aucun dossier ». Si l'utilisateur peut le modifier (peutModifierDossier : éditeur ayant accès au
 * document), un bouton ouvre le même modal « Déplacer » que le menu de la liste (DeplacerDossierModal) — avec sa
 * vérification de fusion de groupe pour un document privé.
 */
function DossierAttachSection({ detail, onUpdated }: DossierAttachSectionProps) {
    const notify = useNotify();
    const [ouvert, setOuvert] = useState(false);

    const peutChanger = detail.peutModifierDossier && detail.uniteOrganisationnelleId != null && onUpdated != null;

    // Le modal attend des éléments de liste : on lui en fabrique un à partir du détail.
    const document: DocumentListItemDto = {
        documentId: detail.documentId,
        titre: detail.titre,
        typeDocumentId: detail.typeDocumentId,
        typeDocumentNom: detail.typeDocumentNom,
        status: detail.status,
        access: detail.access,
        retentionUntil: detail.retentionUntil,
        createAt: detail.createAt,
    };

    const apresChangement = async () => {
        try {
            onUpdated?.(await getDocumentDetail(detail.documentId));
        } catch (err: any) {
            notify.error(err.message ?? 'Impossible de rafraîchir le document');
        }
    };

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Dossier :</strong>
            <span>{detail.dossierNom ? (detail.dossierCheminComplet ?? detail.dossierNom) : 'Aucun dossier'}</span>
            {peutChanger && (
                <button type="button" className="reclasser-btn" onClick={() => setOuvert(true)}>
                    <i className="fa-solid fa-folder-tree" /> {detail.dossierNom ? 'Changer de dossier' : 'Choisir un dossier'}
                </button>
            )}
            {peutChanger && (
                <DeplacerDossierModal
                    isOpen={ouvert}
                    onClose={() => setOuvert(false)}
                    uoId={detail.uniteOrganisationnelleId as number}
                    documents={[document]}
                    onSuccess={apresChangement}
                />
            )}
        </div>
    );
}

export default DossierAttachSection;
