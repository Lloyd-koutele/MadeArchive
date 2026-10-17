import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { MetaDataDto, SortFinal } from '../services/document/TypedocumentService';
import type { PlanClassementOption } from '../services/organisation/PlanClassementService';

// Libellés courts dans la liste ; le détail n'apparaît qu'au survol (attribut title).
const SORT_FINAL_LABELS: Record<SortFinal, string> = {
    CONSERVER: 'Conserver',
    TRIER: 'Trier',
    DETRUIRE: 'Détruire',
};

const SORT_FINAL_DETAILS: Record<SortFinal, string> = {
    CONSERVER: 'Jamais purgé automatiquement',
    TRIER: 'Pas de purge automatique, tri à faire plus tard',
    DETRUIRE: 'Purge automatique après le délai de grâce',
};

interface TypeDocumentFormFieldsProps {
    nom: string;
    onNomChange: (v: string) => void;
    retentionYears: number | null;
    onRetentionYearsChange: (v: number | null) => void;
    metaData: MetaDataDto[];
    onMetaDataChange: (metaData: MetaDataDto[]) => void;
    idPrefix: string;
    /** Optionnels : absents, le sélecteur de sort final n'est pas rendu (utile
     *  si un écran appelant veut le gérer séparément — voir Updatetypedocument.tsx,
     *  qui le modifie via un appel serveur dédié, indépendant du reste du formulaire). */
    sortFinal?: SortFinal;
    onSortFinalChange?: (v: SortFinal) => void;
    /** Activités du plan de classement de l'UO (aplaties) + choix courant (null = non classé).
     *  Absent : le sélecteur n'est pas rendu. */
    /** Délai de grâce (jours) avant suppression définitive en corbeille — null = défaut (6). Absent : champ non rendu. */
    delaiGrace?: number | null;
    onDelaiGraceChange?: (v: number | null) => void;
    activites?: PlanClassementOption[];
    activiteId?: number | null;
    onActiviteChange?: (v: number | null) => void;
    /** Bouton(s) d'action du formulaire (création / mise à jour), affiché au bas de la liste des champs, à côté
     *  de « + Ajouter un champ ». */
    actions?: ReactNode;
}

function TypeDocumentFormFields({
    nom, onNomChange,
    retentionYears, onRetentionYearsChange,
    metaData, onMetaDataChange,
    idPrefix,
    sortFinal, onSortFinalChange,
    delaiGrace, onDelaiGraceChange,
    activites, activiteId, onActiviteChange,
    actions,
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

    // ── Réordonner les champs : glisser-déposer sur le numéro (ou flèches haut/bas au clavier) ──
    const [dragIndex, setDragIndex] = useState<number | null>(null);
    const [overIndex, setOverIndex] = useState<number | null>(null);
    const poignees = useRef<(HTMLDivElement | null)[]>([]);

    const moveMeta = (from: number, to: number) => {
        if (from === to || from < 0 || to < 0 || from >= metaData.length || to >= metaData.length) return;
        const updated = [...metaData];
        const [item] = updated.splice(from, 1);
        updated.splice(to, 0, item);
        onMetaDataChange(updated);
    };

    const finGlisser = () => { setDragIndex(null); setOverIndex(null); };

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
                        <div className="form-field td-span2">
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
                    )}

                    {onDelaiGraceChange && (
                        <div className="form-field td-span2">
                            <label className="form-field-label" htmlFor={`${idPrefix}-delai-grace`}>
                                Délai de grâce (en jours)
                            </label>
                            <input
                                id={`${idPrefix}-delai-grace`}
                                type="number"
                                className="form-field-input"
                                min={1}
                                max={365}
                                placeholder="6"
                                value={delaiGrace ?? ''}
                                onChange={e => onDelaiGraceChange(e.target.value === '' ? null : Number(e.target.value))}
                            />
                        </div>
                    )}

                    {onActiviteChange && activites && (
                        <div className="form-field td-span2">
                            <label className="form-field-label" htmlFor={`${idPrefix}-activite`}>
                                Activité (plan de classement)
                            </label>
                            <select
                                id={`${idPrefix}-activite`}
                                className="form-field-input up-select"
                                value={activiteId ?? ''}
                                onChange={e => onActiviteChange(e.target.value ? Number(e.target.value) : null)}
                            >
                                <option value="">— Non classé —</option>
                                {activites.map(a => (
                                    <option key={a.id} value={a.id}>{a.label}</option>
                                ))}
                            </select>
                        </div>
                    )}

                    {onSortFinalChange && (
                        <div className="form-field td-span2">
                            <label className="form-field-label" htmlFor={`${idPrefix}-sort-final`}>
                                Sort final — une fois en corbeille, délai de grâce écoulé
                            </label>
                            <select
                                id={`${idPrefix}-sort-final`}
                                className="form-field-input up-select"
                                value={sortFinal ?? 'CONSERVER'}
                                onChange={e => onSortFinalChange(e.target.value as SortFinal)}
                                title={SORT_FINAL_DETAILS[sortFinal ?? 'CONSERVER']}
                            >
                                {(Object.keys(SORT_FINAL_LABELS) as SortFinal[]).map(s => (
                                    <option key={s} value={s} title={SORT_FINAL_DETAILS[s]}>{SORT_FINAL_LABELS[s]}</option>
                                ))}
                            </select>
                        </div>
                    )}
                </div>
            </div>

            <div className="td-section">
                <div className="td-section-header">
                    <h3 className="td-section-title">Métadonnées</h3>
                    {metaData.length > 1 && (
                        <span className="td-meta-hint"></span>
                    )}
                </div>

                <div className="td-meta-list">
                    {metaData.map((meta, index) => (
                        <div
                            key={index}
                            className={'td-meta-row'
                                + (dragIndex === index ? ' is-dragging' : '')
                                + (dragIndex !== null && overIndex === index && dragIndex !== index ? ' is-drop-target' : '')}
                            onDragOver={e => {
                                if (dragIndex === null) return;
                                e.preventDefault();
                                e.dataTransfer.dropEffect = 'move';
                                if (overIndex !== index) setOverIndex(index);
                            }}
                            onDrop={e => {
                                e.preventDefault();
                                if (dragIndex !== null) moveMeta(dragIndex, index);
                                finGlisser();
                            }}
                        >
                            <div
                                className="td-meta-index"
                                ref={el => { poignees.current[index] = el; }}
                                draggable
                                role="button"
                                tabIndex={0}
                                title="Glisser pour changer l'ordre"
                                aria-label={`Champ ${index + 1} sur ${metaData.length} : glisser, ou flèches haut et bas, pour changer l'ordre`}
                                onDragStart={e => {
                                    setDragIndex(index);
                                    e.dataTransfer.effectAllowed = 'move';
                                    e.dataTransfer.setData('text/plain', String(index));
                                    const ligne = e.currentTarget.parentElement;
                                    if (ligne) e.dataTransfer.setDragImage(ligne, 24, 24);
                                }}
                                onDragEnd={finGlisser}
                                onKeyDown={e => {
                                    const cible = e.key === 'ArrowUp' ? index - 1 : e.key === 'ArrowDown' ? index + 1 : null;
                                    if (cible === null || cible < 0 || cible >= metaData.length) return;
                                    e.preventDefault();
                                    moveMeta(index, cible);
                                    requestAnimationFrame(() => poignees.current[cible]?.focus());
                                }}
                            >{index + 1}</div>

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

                <div className="td-actions">
                    <button type="button" className="td-add-btn td-add-btn-bas" onClick={addMeta}>
                        + Ajouter un champ
                    </button>
                    {actions}
                </div>
            </div>
        </>
    );
}

export default TypeDocumentFormFields;