import { useState, useEffect } from 'react';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getAllTypeDocuments } from '../services/document/TypedocumentService';
import { downloadTemplateCsv, downloadTemplateExcel } from '../services/document/TemplateService';
import '../Style/Editor/Editor.css';

function DownloadTemplate() {
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);
    const [selected, setSelected] = useState<number[]>([]);
    const [isLoadingCsv, setIsLoadingCsv] = useState(false);
    const [isLoadingExcel, setIsLoadingExcel] = useState(false);
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    useEffect(() => {
        getAllTypeDocuments()
            .then(setTypeDocuments)
            .catch(() => setError("Impossible de charger les types de documents"));
    }, []);

    useEffect(() => {
        if (error || success) {
            const t = setTimeout(() => { setError(''); setSuccess(''); }, 3000);
            return () => clearTimeout(t);
        }
    }, [error, success]);

    const toggleSelect = (id: number) => {
        setSelected(prev =>
            prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]
        );
    };

    const toggleAll = () => {
        if (selected.length === typeDocuments.length) {
            setSelected([]);
        } else {
            setSelected(typeDocuments.map(td => td.id!));
        }
    };

    const handleCsv = async () => {
        if (selected.length === 0) { setError("Sélectionnez au moins un type"); return; }
        setIsLoadingCsv(true);
        try {
            await downloadTemplateCsv(selected);
            setSuccess("Template CSV téléchargé");
        } catch (err: any) {
            setError(err.message || "Erreur téléchargement CSV");
        } finally {
            setIsLoadingCsv(false);
        }
    };

    const handleExcel = async () => {
        if (selected.length === 0) { setError("Sélectionnez au moins un type"); return; }
        setIsLoadingExcel(true);
        try {
            await downloadTemplateExcel(selected);
            setSuccess("Template Excel téléchargé");
        } catch (err: any) {
            setError(err.message || "Erreur téléchargement Excel");
        } finally {
            setIsLoadingExcel(false);
        }
    };

    return (
        <div className="template-wrapper">
            {error && <div className="up-alert up-alert-error">{error}</div>}
            {success && <div className="up-alert up-alert-success">{success}</div>}

            <div className="template-info">
                <i className="fa-solid fa-circle-info"></i>
                <p>
                    Sélectionnez les types de documents pour lesquels vous voulez
                    générer un template d'import. Le CSV génère une feuille unique,
                    l'Excel génère un onglet par type.
                </p>
            </div>

            {/* Liste des types */}
            <div className="template-list">
                <div className="template-list-header">
                    <label className="checkbox-label">
                        <input
                            type="checkbox"
                            checked={selected.length === typeDocuments.length && typeDocuments.length > 0}
                            onChange={toggleAll}
                        />
                        Tout sélectionner ({typeDocuments.length})
                    </label>
                    <span className="template-selected-count">
                        {selected.length} sélectionné{selected.length > 1 ? 's' : ''}
                    </span>
                </div>

                <div className="template-items">
                    {typeDocuments.map(td => (
                        <label key={td.id} className={`template-item ${selected.includes(td.id!) ? 'selected' : ''}`}>
                            <input
                                type="checkbox"
                                checked={selected.includes(td.id!)}
                                onChange={() => toggleSelect(td.id!)}
                                className="sr-only"
                            />
                            <div className="template-item-check">
                                {selected.includes(td.id!) && <i className="fa-solid fa-check"></i>}
                            </div>
                            <div className="template-item-info">
                                <span className="template-item-nom">{td.nom}</span>
                                <span className="template-item-meta">
                                    {td.metaData?.length ?? 0} champ{(td.metaData?.length ?? 0) > 1 ? 's' : ''}
                                    · Rétention {td.retentionYears} an{(td.retentionYears ?? 0) > 1 ? 's' : ''}
                                </span>
                            </div>
                        </label>
                    ))}
                </div>
            </div>

            {/* Boutons téléchargement */}
            <div className="template-actions">
                <button
                    className="template-btn csv"
                    onClick={handleCsv}
                    disabled={isLoadingCsv || selected.length === 0}
                >
                    {isLoadingCsv
                        ? <><i className="fa-solid fa-spinner fa-spin"></i> Génération...</>
                        : <><i className="fa-solid fa-file-csv"></i> Télécharger CSV</>
                    }
                </button>
                <button
                    className="template-btn excel"
                    onClick={handleExcel}
                    disabled={isLoadingExcel || selected.length === 0}
                >
                    {isLoadingExcel
                        ? <><i className="fa-solid fa-spinner fa-spin"></i> Génération...</>
                        : <><i className="fa-solid fa-file-excel"></i> Télécharger Excel</>
                    }
                </button>
            </div>
        </div>
    );
}

export default DownloadTemplate;