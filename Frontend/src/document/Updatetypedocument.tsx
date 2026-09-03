// document/Updatetypedocument.tsx
import React, { useState, useEffect } from 'react';
import { updateTypeDocument } from '../services/document/TypedocumentService';
import type { MetaDataDto, TypeDocumentDto } from '../services/document/TypedocumentService';
import TypeDocumentFormFields from './TypeDocumentFormFields';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/document/Typedocument.css';

interface UpdateTypeDocumentProps {
    initialData: TypeDocumentDto;
    onsuccess?: () => void;
}

function UpdateTypeDocument({ initialData, onsuccess }: UpdateTypeDocumentProps) {
    const notify = useNotify();
    const [nom, setNom] = useState('');
    const [retentionYears, setRetentionYears] = useState<number | null>(null);
    const [periodGrace, setPeriodGrace] = useState<number | null>(null);
    const [metaData, setMetaData] = useState<MetaDataDto[]>([{ nom: '', obligatoire: false }]);
    const [isLoading, setIsLoading] = useState(false);

    useEffect(() => {
        if (initialData) {
            setNom(initialData.nom || '');
            setRetentionYears(initialData.retentionYears ?? null);
            setPeriodGrace(initialData.periodGrace ?? null);
            setMetaData(
                initialData.metaData && initialData.metaData.length > 0
                    ? initialData.metaData.map(m => ({ id: m.id, nom: m.nom, obligatoire: m.obligatoire }))
                    : [{ nom: '', obligatoire: false }]
            );
        }
    }, [initialData]);

    const validate = (): boolean => {
        if (!nom.trim()) { notify.error("Le nom du type de document est obligatoire"); return false; }
        if (retentionYears !== null && retentionYears < 1) {
            notify.error("La durée de rétention doit être d'au moins 1 an, ou laissée indéfinie");
            return false;
        }
        for (const m of metaData) {
            if (!m.nom.trim()) { notify.error("Chaque métadonnée doit avoir un nom"); return false; }
        }
        return true;
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!validate()) return;
        if (!initialData.id) { notify.error("ID du type de document manquant"); return; }

        setIsLoading(true);
        try {
            const dto: TypeDocumentDto = {
                nom: nom.trim(),
                retentionYears,
                periodGrace: retentionYears !== null ? periodGrace : null,
                uoId: initialData.uoId, // inchangé — plus de déplacement via ce formulaire
                metaData
            };
            await updateTypeDocument(initialData.id, dto);
            notify.success("Type de document mis à jour avec succès");
            setTimeout(() => onsuccess?.(), 1500);
        } catch (err: any) {
            notify.error(err.message || "Erreur lors de la mise à jour");
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="td-form-wrapper">
            <form onSubmit={handleSubmit}>
                <TypeDocumentFormFields
                    idPrefix="tdu"
                    nom={nom} onNomChange={setNom}
                    retentionYears={retentionYears} onRetentionYearsChange={setRetentionYears}
                    periodGrace={periodGrace} onPeriodGraceChange={setPeriodGrace}
                    metaData={metaData} onMetaDataChange={setMetaData}
                />
                <button type="submit" className="form-submit-btn td-submit" disabled={isLoading}>
                    {isLoading ? 'Mise à jour en cours...' : 'Mettre à jour'}
                </button>
            </form>
        </div>
    );
}

export default UpdateTypeDocument;
