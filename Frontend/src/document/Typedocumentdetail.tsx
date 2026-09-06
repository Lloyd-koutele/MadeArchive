import { useState } from 'react';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { resetTypeDocumentRegex } from '../services/document/TypedocumentService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/document/Typedocument.css';

interface TypeDocumentDetailProps {
    td: TypeDocumentDto;
}

function TypeDocumentDetail({ td }: TypeDocumentDetailProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    // État local (pas de refetch parent) : reflète le reset immédiatement
    // sans dépendre d'un callback de rafraîchissement côté liste.
    const [regexGenerated, setRegexGenerated] = useState(td.regexGenerated ?? false);
    const [regexJson, setRegexJson] = useState(td.extractionRegexJson ?? null);
    const [resetting, setResetting] = useState(false);

    let regexMap: Record<string, string> = {};
    try { regexMap = regexJson ? JSON.parse(regexJson) : {}; } catch { regexMap = {}; }

    const handleReset = async () => {
        if (!td.id) return;
        if (!(await confirm(
            "Réinitialiser les règles d'extraction OCR de ce type ? "
            + "Elles seront régénérées automatiquement au prochain document archivé de ce type."
        ))) return;

        setResetting(true);
        try {
            await resetTypeDocumentRegex(td.id);
            setRegexGenerated(false);
            setRegexJson(null);
            notify.success("Règles d'extraction réinitialisées");
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la réinitialisation');
        } finally {
            setResetting(false);
        }
    };

    return (
        <div className="td-detail">

            {/* Infos générales */}
            <div className="td-detail-section">
                <div className="details-row">
                    <strong>Nom :</strong> {td.nom}
                </div>
                <div className="details-row">
                    <strong>Rétention :</strong>{' '}
                    {td.retentionYears != null
                        ? `${td.retentionYears} an${td.retentionYears > 1 ? 's' : ''}`
                        : 'Indéfinie'}
                </div>
                <div className="details-row">
                    <strong>Période de grâce :</strong>{' '}
                    {td.periodGrace != null
                        ? `${td.periodGrace} jour${td.periodGrace > 1 ? 's' : ''}`
                        : 'Indéfinie'}
                </div>
            </div>

            {/* Métadonnées */}
            <div className="td-detail-section">
                <h4 className="td-detail-subtitle">Métadonnées</h4>

                {!td.metaData || td.metaData.length === 0 ? (
                    <p className="td-detail-empty">Aucune métadonnée définie.</p>
                ) : (
                    <table className="td-meta-table">
                        <thead>
                            <tr>
                                <th>#</th>
                                <th>Nom</th>
                                <th>Obligatoire</th>
                            </tr>
                        </thead>
                        <tbody>
                            {td.metaData.map((m, i) => (
                                <tr key={i}>
                                    <td>{i + 1}</td>
                                    <td>{m.nom}</td>
                                    <td>
                                        <span className={`td-oblig-badge ${m.obligatoire ? 'yes' : 'no'}`}>
                                            {m.obligatoire ? 'Oui' : 'Non'}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>

            {/* Règles d'extraction OCR */}
            <div className="td-detail-section">
                <h4 className="td-detail-subtitle">Règles d'extraction OCR</h4>

                {!regexGenerated ? (
                    <p className="td-detail-empty">
                        Pas encore générées — elles le seront automatiquement au premier document
                        archivé de ce type (en arrière-plan, sans bloquer l'archivage).
                    </p>
                ) : (
                    <>
                        <p className="td-regex-hint">
                            Générées à partir d'un document déjà archivé. Si les suggestions OCR
                            se trompent systématiquement, réinitialise-les ci-dessous — elles
                            seront régénérées au prochain document de ce type.
                        </p>
                        {Object.keys(regexMap).length > 0 && (
                            <table className="td-meta-table">
                                <thead>
                                    <tr><th>Champ</th><th>Regex</th></tr>
                                </thead>
                                <tbody>
                                    {Object.entries(regexMap).map(([champ, regex]) => (
                                        <tr key={champ}>
                                            <td>{champ}</td>
                                            <td><code className="td-regex-code">{regex}</code></td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                        <button
                            type="button"
                            className="td-regex-reset-btn"
                            onClick={handleReset}
                            disabled={resetting}
                        >
                            {resetting
                                ? <><i className="fa-solid fa-spinner fa-spin" /> Réinitialisation…</>
                                : <><i className="fa-solid fa-rotate-left" /> Réinitialiser les règles d'extraction</>}
                        </button>
                    </>
                )}
            </div>
        </div>
    );
}

export default TypeDocumentDetail;
