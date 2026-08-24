import type { MetaDataDto } from '../services/document/TypedocumentService';

interface TypeDocumentFormFieldsProps {
    nom: string;
    onNomChange: (v: string) => void;
    retentionYears: number | null;
    onRetentionYearsChange: (v: number | null) => void;
    periodGrace: number | null;
    onPeriodGraceChange: (v: number | null) => void;
    metaData: MetaDataDto[];
    onMetaDataChange: (metaData: MetaDataDto[]) => void;
    idPrefix: string;
}

function TypeDocumentFormFields({
    nom, onNomChange,
    retentionYears, onRetentionYearsChange,
    periodGrace, onPeriodGraceChange,
    metaData, onMetaDataChange,
    idPrefix
}: TypeDocumentFormFieldsProps) {

    const handleMetaChange = (index: number, field: keyof MetaDataDto, value: any) => {
        const updated = [...metaData];
        updated[index] = { ...updated[index], [field]: value };
        onMetaDataChange(updated);
    };

    const addMeta = () => onMetaDataChange([...metaData, { nom: '', obligatoire: false }]);

    const removeMeta = (index: number) => {
        if (metaData.length === 1) return;
        onMetaDataChange(metaData.filter((_, i) => i !== index));
    };

    const retentionDefinie = retentionYears !== null;

    return (
        <>
            <div className="td-section">
                <h3 className="td-section-title">Informations générales</h3>
                <div className="td-form-grid">
                    <div className="form-field td-span2">
                        <input
                            id={`${idPrefix}-nom`}
                            type="text"
                            className="form-field-input"
                            placeholder="Nom du type de document" aria-label="Nom du type de document"
                            value={nom}
                            onChange={e => onNomChange(e.target.value)}
                            required
                        />
                    </div>

                    <label className="td-toggle td-span2">
                        <input
                            type="checkbox"
                            checked={retentionDefinie}
                            onChange={e => onRetentionYearsChange(e.target.checked ? 1 : null)}
                        />
                        <span className="td-toggle-track"><span className="td-toggle-thumb" /></span>
                        <span className="td-toggle-label">Durée de rétention définie (sinon indéfinie)</span>
                    </label>

                    {retentionDefinie && (
                        <>
                            <div className="form-field">
                                <input
                                    id={`${idPrefix}-retention`}
                                    type="number"
                                    className="form-field-input"
                                    placeholder="Durée de rétention (années)" aria-label="Durée de rétention (années)"
                                    min={1}
                                    value={retentionYears ?? ''}
                                    onChange={e => onRetentionYearsChange(Number(e.target.value))}
                                />
                            </div>
                            <div className="form-field">
                                <input
                                    id={`${idPrefix}-grace`}
                                    type="number"
                                    className="form-field-input"
                                    placeholder="Période de grâce (jours)" aria-label="Période de grâce (jours)"
                                    min={0}
                                    value={periodGrace ?? ''}
                                    onChange={e => onPeriodGraceChange(Number(e.target.value))}
                                />
                            </div>
                        </>
                    )}
                </div>
            </div>

            <div className="td-section">
                <div className="td-section-header">
                    <h3 className="td-section-title">Métadonnées</h3>
                    <button type="button" className="td-add-btn" onClick={addMeta}>
                        + Ajouter un champ
                    </button>
                </div>

                <div className="td-meta-list">
                    {metaData.map((meta, index) => (
                        <div key={index} className="td-meta-row">
                            <div className="td-meta-index">{index + 1}</div>

                            <div className="form-field td-meta-nom">
                                <input
                                    type="text"
                                    id={`${idPrefix}-meta-nom-${index}`}
                                    className="form-field-input"
                                    placeholder="Nom du champ" aria-label="Nom du champ"
                                    value={meta.nom}
                                    onChange={e => handleMetaChange(index, 'nom', e.target.value)}
                                    required
                                />
                            </div>

                            <label className="td-toggle">
                                <input
                                    type="checkbox"
                                    checked={meta.obligatoire || false}
                                    onChange={e => handleMetaChange(index, 'obligatoire', e.target.checked)}
                                />
                                <span className="td-toggle-track"><span className="td-toggle-thumb" /></span>
                                <span className="td-toggle-label">Obligatoire</span>
                            </label>

                            <button
                                type="button"
                                className="td-remove-btn"
                                onClick={() => removeMeta(index)}
                                disabled={metaData.length === 1}
                                aria-label={`Supprimer le champ de métadonnée ${index + 1}`}
                            >
                                ✕
                            </button>
                        </div>
                    ))}
                </div>
            </div>
        </>
    );
}

export default TypeDocumentFormFields;