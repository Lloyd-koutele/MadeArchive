import { useState, useEffect } from 'react';
import Modal from '../Page/Modal';
import TypeDocumentFormFields from './TypeDocumentFormFields';
import { createTypeDocument } from '../services/document/TypedocumentService';
import type { MetaDataDto, TypeDocumentDto } from '../services/document/TypedocumentService';

interface QuickCreateTypeDocumentsModalProps {
    isOpen: boolean;
    targetUO: { id: number; nom: string } | null;
    sourceTypeDocuments: TypeDocumentDto[];
    onClose: () => void;
    onCreated: () => void;
}

interface DraftEntry {
    nom: string;
    retentionYears: number | null;
    periodGrace: number | null;
    metaData: MetaDataDto[];
}

const toDraft = (td: TypeDocumentDto): DraftEntry => ({
    nom: td.nom,
    retentionYears: td.retentionYears ?? null,
    periodGrace: td.periodGrace ?? null,
    metaData: td.metaData.map(m => ({ nom: m.nom, obligatoire: m.obligatoire }))
});

function QuickCreateTypeDocumentsModal({ isOpen, targetUO, sourceTypeDocuments, onClose, onCreated }: QuickCreateTypeDocumentsModalProps) {
    const [drafts, setDrafts] = useState<DraftEntry[]>([]);
    const [activeIndex, setActiveIndex] = useState(0);
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (isOpen) {
            setDrafts(sourceTypeDocuments.map(toDraft));
            setActiveIndex(0);
            setError('');
        }
    }, [isOpen, sourceTypeDocuments]);

    if (!targetUO) return null;

    const updateActive = (patch: Partial<DraftEntry>) => {
        setDrafts(prev => prev.map((d, i) => (i === activeIndex ? { ...d, ...patch } : d)));
    };

    const handleCreateAll = async () => {
        for (const d of drafts) {
            if (!d.nom.trim()) { setError("Chaque type de document doit avoir un nom"); return; }
            for (const m of d.metaData) {
                if (!m.nom.trim()) { setError("Chaque métadonnée doit avoir un nom"); return; }
            }
        }

        setSubmitting(true);
        setError('');
        try {
            for (const d of drafts) {
                const dto: TypeDocumentDto = {
                    nom: d.nom.trim(),
                    retentionYears: d.retentionYears,
                    periodGrace: d.retentionYears !== null ? d.periodGrace : null,
                    uoId: targetUO.id,
                    metaData: d.metaData.map(m => ({ nom: m.nom.trim(), obligatoire: m.obligatoire || false }))
                };
                await createTypeDocument(dto);
            }
            onCreated();
        } catch (err: any) {
            setError(err.message || "Erreur lors de la création");
        } finally {
            setSubmitting(false);
        }
    };

    const active = drafts[activeIndex];

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={`Créer dans ${targetUO.nom}`}>
            {error && <div className="td-alert td-alert-error">{error}</div>}

            {drafts.length > 1 && (
                <div className="td-quickcreate-tabs">
                    {drafts.map((d, i) => (
                        <button
                            key={i}
                            type="button"
                            className={`uo-tab ${i === activeIndex ? 'active' : ''}`}
                            onClick={() => setActiveIndex(i)}
                        >
                            {d.nom || `Type ${i + 1}`}
                        </button>
                    ))}
                </div>
            )}

            {active && (
                <TypeDocumentFormFields
                    idPrefix={`qc-${activeIndex}`}
                    nom={active.nom} onNomChange={(v) => updateActive({ nom: v })}
                    retentionYears={active.retentionYears} onRetentionYearsChange={(v) => updateActive({ retentionYears: v })}
                    periodGrace={active.periodGrace} onPeriodGraceChange={(v) => updateActive({ periodGrace: v })}
                    metaData={active.metaData} onMetaDataChange={(v) => updateActive({ metaData: v })}
                />
            )}

            <button
                type="button"
                className="form-submit-btn td-submit"
                onClick={handleCreateAll}
                disabled={submitting}
            >
                {submitting
                    ? 'Création en cours...'
                    : drafts.length > 1
                        ? `Créer les ${drafts.length} types dans ${targetUO.nom}`
                        : `Créer dans ${targetUO.nom}`}
            </button>
        </Modal>
    );
}

export default QuickCreateTypeDocumentsModal;