import { useState } from 'react';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { resetTypeDocumentRegex, modifierTypeDocumentRegex } from '../services/document/TypedocumentService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/document/Typedocument.css';

interface TypeDocumentDetailProps {
    td: TypeDocumentDto;
}

function TypeDocumentDetail({ td }: TypeDocumentDetailProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    // État local (pas de refetch parent) : reflète le reset/la correction
    // immédiatement sans dépendre d'un callback de rafraîchissement côté liste.
    const [regexGenerated, setRegexGenerated] = useState(td.regexGenerated ?? false);
    const [regexJson, setRegexJson] = useState(td.extractionRegexJson ?? null);
    const [resetting, setResetting] = useState(false);

    let regexMap: Record<string, string> = {};
    try { regexMap = regexJson ? JSON.parse(regexJson) : {}; } catch { regexMap = {}; }

    // ── Correction manuelle des regex ────────────────────────────────────────
    // Un champ texte par métadonnée du type (pas un textarea JSON brut) : une
    // erreur de syntaxe sur une ligne ne remet pas en cause les autres, et le
    // serveur valide quand même chaque regex avant d'enregistrer. Couvre
    // aussi les métadonnées ajoutées après la génération initiale (absentes
    // de regexMap) — pré-remplies avec un champ vide.
    const [editingRegex, setEditingRegex] = useState(false);
    const [editValues, setEditValues] = useState<Record<string, string>>({});
    const [savingRegex, setSavingRegex] = useState(false);

    const champsMeta = (td.metaData ?? []).map(m => m.nom);

    const ouvrirEditionRegex = () => {
        const seed: Record<string, string> = {};
        champsMeta.forEach(nom => { seed[nom] = regexMap[nom] ?? ''; });
        setEditValues(seed);
        setEditingRegex(true);
    };

    const annulerEditionRegex = () => setEditingRegex(false);

    const enregistrerRegex = async () => {
        if (!td.id) return;
        setSavingRegex(true);
        try {
            const updated = await modifierTypeDocumentRegex(td.id, editValues);
            setRegexGenerated(updated.regexGenerated ?? true);
            setRegexJson(updated.extractionRegexJson ?? null);
            setEditingRegex(false);
            notify.success('Règles d\'extraction mises à jour');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la modification des regex');
        } finally {
            setSavingRegex(false);
        }
    };

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
            setEditingRegex(false);
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
                ) : editingRegex ? (
                    <>
                        <p className="td-regex-hint">
                            Une regex par champ — une erreur de syntaxe sur une ligne n'empêche pas
                            d'enregistrer les autres, le serveur valide chacune individuellement.
                        </p>
                        <table className="td-meta-table">
                            <thead>
                                <tr><th>Champ</th><th>Regex</th></tr>
                            </thead>
                            <tbody>
                                {champsMeta.map(champ => (
                                    <tr key={champ}>
                                        <td>{champ}</td>
                                        <td>
                                            <input
                                                type="text"
                                                className="td-regex-input"
                                                value={editValues[champ] ?? ''}
                                                onChange={e => setEditValues(prev => ({ ...prev, [champ]: e.target.value }))}
                                                disabled={savingRegex}
                                                placeholder="ex. [0-9]{4}-[0-9]{2}-[0-9]{2}"
                                            />
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        <div className="td-regex-edit-actions">
                            <button
                                type="button"
                                className="td-regex-reset-btn"
                                onClick={enregistrerRegex}
                                disabled={savingRegex}
                            >
                                {savingRegex
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> Enregistrement…</>
                                    : <><i className="fa-solid fa-check" /> Enregistrer</>}
                            </button>
                            <button
                                type="button"
                                className="details-close-btn"
                                onClick={annulerEditionRegex}
                                disabled={savingRegex}
                            >
                                Annuler
                            </button>
                        </div>
                    </>
                ) : (
                    <>
                        <p className="td-regex-hint">
                            Générées à partir d'un document déjà archivé. Si les suggestions OCR
                            se trompent systématiquement, corrige-les ou réinitialise-les
                            ci-dessous — dans ce dernier cas, elles seront régénérées au prochain
                            document de ce type.
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
                        <div className="td-regex-edit-actions">
                            <button
                                type="button"
                                className="td-regex-reset-btn"
                                onClick={ouvrirEditionRegex}
                            >
                                <i className="fa-solid fa-pen" /> Modifier
                            </button>
                            <button
                                type="button"
                                className="details-close-btn"
                                onClick={handleReset}
                                disabled={resetting}
                            >
                                {resetting
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> Réinitialisation…</>
                                    : <><i className="fa-solid fa-rotate-left" /> Réinitialiser</>}
                            </button>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}

export default TypeDocumentDetail;
