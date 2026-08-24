// document/Createtypedocument.tsx
import React, { useState } from 'react';
import { createTypeDocument } from '../services/document/TypedocumentService';
import type { MetaDataDto, TypeDocumentDto } from '../services/document/TypedocumentService';
import TypeDocumentFormFields from './TypeDocumentFormFields';
import '../Style/document/Typedocument.css';

interface CreateTypeDocumentProps {
    onsuccess?: () => void;
    restrictToUO: { id: number; nom: string };
}

function CreateTypeDocument({ onsuccess, restrictToUO }: CreateTypeDocumentProps) {
    const [nom, setNom] = useState('');
    const [retentionYears, setRetentionYears] = useState<number | null>(null);
    const [periodGrace, setPeriodGrace] = useState<number | null>(30);
    const [metaData, setMetaData] = useState<MetaDataDto[]>([{ nom: '', obligatoire: false }]);
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');
    const [isLoading, setIsLoading] = useState(false);

    const validate = (): boolean => {
        if (!nom.trim()) { setError("Le nom du type de document est obligatoire"); return false; }
        if (retentionYears !== null && retentionYears < 1) {
            setError("La durée de rétention doit être d'au moins 1 an, ou laissée indéfinie");
            return false;
        }
        for (const m of metaData) {
            if (!m.nom.trim()) { setError("Chaque métadonnée doit avoir un nom"); return false; }
        }
        setError('');
        return true;
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(''); setSuccess('');
        if (!validate()) return;

        setIsLoading(true);
        try {
            const dto: TypeDocumentDto = {
                nom: nom.trim(),
                retentionYears,
                periodGrace: retentionYears !== null ? periodGrace : null,
                uoId: restrictToUO.id,
                metaData: metaData.map(m => ({ nom: m.nom.trim(), obligatoire: m.obligatoire || false }))
            };
            await createTypeDocument(dto);
            setSuccess("Type de document créé avec succès");
            setNom('');
            setRetentionYears(null);
            setPeriodGrace(30);
            setMetaData([{ nom: '', obligatoire: false }]);
            setTimeout(() => onsuccess?.(), 1500);
        } catch (err: any) {
            setError(err.message || "Erreur lors de la création du type de document");
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="td-form-wrapper">
            <p className="roles-label">Unité organisationnelle : <strong>{restrictToUO.nom}</strong></p>
            {error && <div className="td-alert td-alert-error">{error}</div>}
            {success && <div className="td-alert td-alert-success">{success}</div>}

            <form onSubmit={handleSubmit}>
                <TypeDocumentFormFields
                    idPrefix="td"
                    nom={nom} onNomChange={setNom}
                    retentionYears={retentionYears} onRetentionYearsChange={setRetentionYears}
                    periodGrace={periodGrace} onPeriodGraceChange={setPeriodGrace}
                    metaData={metaData} onMetaDataChange={setMetaData}
                />
                <button type="submit" className="form-submit-btn td-submit" disabled={isLoading}>
                    {isLoading ? 'Création en cours...' : 'Créer le type de document'}
                </button>
            </form>
        </div>
    );
}

export default CreateTypeDocument;