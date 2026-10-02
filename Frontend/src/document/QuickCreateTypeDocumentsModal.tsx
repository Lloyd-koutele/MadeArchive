// document/QuickCreateTypeDocumentsModal.tsx
import { useState } from 'react';
import Modal from '../Page/Modal';
import { createTypeDocument } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { useNotify } from '../notifications/NotificationProvider';

interface QuickCreateTypeDocumentsModalProps {
    isOpen: boolean;
    targetUO: { id: number; nom: string } | null;
    /** Types glissés sur targetUO (voir AdminDahboard.handleDropTypeDocuments) —
     *  jamais modifiés ici, servent uniquement de modèle (nom + métadonnées +
     *  rétention + sort final) pour les nouveaux types créés dans targetUO. */
    sourceTypeDocuments: TypeDocumentDto[];
    onClose: () => void;
    onCreated: () => void;
}

/**
 * Création rapide, dans targetUO, d'un type de document par source glissée —
 * même nom/métadonnées/rétention/sort final que la source, un NOUVEAU type
 * (jamais un déplacement : la source reste intacte dans son UO d'origine).
 * Chaque création est indépendante : l'échec de l'une (ex. nom déjà pris dans
 * targetUO) n'empêche pas les suivantes — voir handleConfirmer.
 */
function QuickCreateTypeDocumentsModal({
    isOpen, targetUO, sourceTypeDocuments, onClose, onCreated,
}: QuickCreateTypeDocumentsModalProps) {
    const notify = useNotify();
    const [submitting, setSubmitting] = useState(false);

    if (!isOpen || !targetUO) {
        return null;
    }

    const handleConfirmer = async () => {
        setSubmitting(true);
        let reussis = 0;
        const echecs: string[] = [];

        for (const source of sourceTypeDocuments) {
            try {
                await createTypeDocument({
                    nom: source.nom,
                    retentionYears: source.retentionYears,
                    sortFinal: source.sortFinal,
                    uoId: targetUO.id,
                    metaData: source.metaData.map(m => ({ nom: m.nom, obligatoire: m.obligatoire })),
                });
                reussis++;
            } catch (err: any) {
                echecs.push(`${source.nom} (${err.message || 'erreur inconnue'})`);
            }
        }

        setSubmitting(false);

        if (echecs.length > 0) {
            notify.error(`${echecs.length} type(s) non créé(s) : ${echecs.join(', ')}`);
        }
        if (reussis > 0) {
            onCreated();
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={`Créer dans "${targetUO.nom}"`}>
            <p>
                {sourceTypeDocuments.length} type{sourceTypeDocuments.length > 1 ? 's' : ''} de document
                {sourceTypeDocuments.length > 1 ? 's seront créés' : ' sera créé'} dans <strong>{targetUO.nom}</strong>,
                avec les mêmes métadonnées, rétention et sort final que la source (la source elle-même n'est pas modifiée) :
            </p>
            <ul>
                {sourceTypeDocuments.map((t, i) => (
                    <li key={i}>
                        {t.nom} — {t.metaData.length} métadonnée{t.metaData.length > 1 ? 's' : ''}
                    </li>
                ))}
            </ul>
            <button
                type="button"
                className="form-submit-btn"
                onClick={handleConfirmer}
                disabled={submitting || sourceTypeDocuments.length === 0}
                style={{ marginTop: '1rem' }}
            >
                {submitting ? 'Création en cours...' : 'Créer'}
            </button>
        </Modal>
    );
}

export default QuickCreateTypeDocumentsModal;
