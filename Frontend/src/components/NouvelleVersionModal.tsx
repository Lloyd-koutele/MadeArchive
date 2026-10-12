import Modal from '../Page/Modal';
import ImportDocuments from '../document/ImportDocuments';
import { useNotify } from '../notifications/NotificationProvider';
import type { BulkUploadReportDto, DocumentListItemDto } from '../services/document/DocumentService';

export interface SourceNouvelleVersion {
    documentId: string;
    typeDocumentId: number;
    titre: string;
}

/**
 * Une nouvelle version se dépose sur la DERNIÈRE version d'un document (jamais versionné, ou « Final »), par un
 * éditeur qui y a accès (même prédicat serveur que la corbeille : peutGererCorbeille), tant qu'il n'est pas en corbeille.
 */
export const peutDeposerVersion = (doc: DocumentListItemDto): boolean =>
    doc.peutGererCorbeille === true
    && (!doc.versionLabel || doc.versionLabel === 'Final')
    && doc.status !== 'CORBEILLE' && doc.status !== 'DELETED';

export const sourceDe = (doc: DocumentListItemDto): SourceNouvelleVersion => ({
    documentId: doc.documentId, typeDocumentId: doc.typeDocumentId, titre: doc.titre,
});

interface Props {
    /** Document à remplacer ; null = modal fermé. */
    source: SourceNouvelleVersion | null;
    onClose: () => void;
    /** Appelé après le dépôt (réussi ou non) pour que la liste se recharge. */
    onTermine: () => void;
}

/** Même interface d'import que « Archiver », adaptée à une nouvelle version (voir ImportDocuments.precedentDocument). */
function NouvelleVersionModal({ source, onClose, onTermine }: Props) {
    const notify = useNotify();

    const apresDepot = (report: BulkUploadReportDto) => {
        onClose();
        onTermine();
        if (report.failed === 0) {
            notify.success('Nouvelle version déposée');
        } else if (report.success === 0) {
            const premiereErreur = report.details.find(d => d.status === 'FAILED')?.erreur;
            notify.error(`Échec du dépôt${premiereErreur ? ` : ${premiereErreur}` : ''}`);
        } else {
            notify.warning(`${report.success} version(s) déposée(s), ${report.failed} échec(s)`);
        }
    };

    return (
        <Modal isOpen={source != null} onClose={onClose} title="Déposer une nouvelle version" size="large">
            {source && (
                <ImportDocuments
                    onsuccess={apresDepot}
                    preselectedTypeId={source.typeDocumentId}
                    precedentDocument={source}
                />
            )}
        </Modal>
    );
}

export default NouvelleVersionModal;
