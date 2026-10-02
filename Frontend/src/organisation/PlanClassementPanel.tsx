import { useCallback, useEffect, useMemo, useState } from 'react';
import Modal from '../Page/Modal';
import {
    getPlanClassement, creerNoeudPlanClassement, modifierNoeudPlanClassement,
    deplacerNoeudPlanClassement, supprimerNoeudPlanClassement, aplatirPlanClassement,
} from '../services/organisation/PlanClassementService';
import type { PlanClassementNoeudDto } from '../services/organisation/PlanClassementService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/Admin/PlanClassementPanel.css';

interface Props {
    uoId: number | null;
}

type FormState =
    | { mode: 'creer'; parentId: number | null }
    | { mode: 'modifier'; noeud: PlanClassementNoeudDto }
    | { mode: 'deplacer'; noeud: PlanClassementNoeudDto };

/**
 * Plan de classement de l'UO de l'éditeur : arborescence d'activités (code +
 * libellé) indépendante de l'organigramme. Les TYPES de documents s'y
 * rattachent (depuis le formulaire du type) — un document hérite de
 * l'activité de son type. Voir PlanClassementService côté serveur.
 */
function PlanClassementPanel({ uoId }: Props) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [arbre, setArbre] = useState<PlanClassementNoeudDto[]>([]);
    const [loading, setLoading] = useState(false);
    const [form, setForm] = useState<FormState | null>(null);
    const [code, setCode] = useState('');
    const [libelle, setLibelle] = useState('');
    const [parentChoisi, setParentChoisi] = useState<string>('');
    const [saving, setSaving] = useState(false);

    const charger = useCallback(async () => {
        if (uoId == null) return;
        setLoading(true);
        try {
            setArbre(await getPlanClassement(uoId));
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du chargement du plan de classement');
        } finally {
            setLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId]);

    useEffect(() => { charger(); }, [charger]);

    const options = useMemo(() => aplatirPlanClassement(arbre), [arbre]);

    /** Ids du nœud + de tous ses descendants — exclus des parents possibles d'un déplacement. */
    const idsAvecDescendants = (n: PlanClassementNoeudDto): Set<number> => {
        const ids = new Set<number>([n.id]);
        n.children.forEach(c => idsAvecDescendants(c).forEach(i => ids.add(i)));
        return ids;
    };

    const ouvrir = (f: FormState) => {
        setForm(f);
        setCode(f.mode === 'modifier' ? f.noeud.code : '');
        setLibelle(f.mode === 'modifier' ? f.noeud.libelle : '');
        setParentChoisi(f.mode === 'deplacer'
            ? (f.noeud.parentId != null ? String(f.noeud.parentId) : '')
            : f.mode === 'creer' && f.parentId != null ? String(f.parentId) : '');
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!form || uoId == null) return;
        setSaving(true);
        try {
            if (form.mode === 'creer') {
                await creerNoeudPlanClassement(uoId, code, libelle, parentChoisi ? Number(parentChoisi) : null);
                notify.success('Activité ajoutée');
            } else if (form.mode === 'modifier') {
                await modifierNoeudPlanClassement(form.noeud.id, code, libelle);
                notify.success('Activité modifiée');
            } else {
                await deplacerNoeudPlanClassement(form.noeud.id, parentChoisi ? Number(parentChoisi) : null);
                notify.success('Activité déplacée');
            }
            setForm(null);
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'enregistrement");
        } finally {
            setSaving(false);
        }
    };

    const handleSupprimer = async (n: PlanClassementNoeudDto) => {
        const ok = await confirm({
            title: 'Supprimer cette activité',
            message: `Supprimer "${n.code} ${n.libelle}" ? Possible seulement si elle n'a ni sous-activité ni type de document rattaché.`,
            confirmLabel: 'Supprimer',
        });
        if (!ok) return;
        try {
            await supprimerNoeudPlanClassement(n.id);
            notify.success('Activité supprimée');
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la suppression');
        }
    };

    const renderNoeuds = (noeuds: PlanClassementNoeudDto[]) => (
        <ul className="pc-tree">
            {noeuds.map(n => (
                <li key={n.id}>
                    <div className="pc-node-row">
                        <span className="pc-code">{n.code}</span>
                        <span className="pc-libelle">{n.libelle}</span>
                        <span className="pc-count">
                            {n.nbTypes} type{n.nbTypes > 1 ? 's' : ''}
                        </span>
                        <span className="pc-actions">
                            <button type="button" className="action-button" title="Ajouter une sous-activité"
                                onClick={() => ouvrir({ mode: 'creer', parentId: n.id })}>
                                <i className="fa-solid fa-plus" />
                            </button>
                            <button type="button" className="action-button edit" title="Renommer"
                                onClick={() => ouvrir({ mode: 'modifier', noeud: n })}>
                                <i className="fa-solid fa-pen" />
                            </button>
                            <button type="button" className="action-button" title="Déplacer"
                                onClick={() => ouvrir({ mode: 'deplacer', noeud: n })}>
                                <i className="fa-solid fa-arrows-up-down-left-right" />
                            </button>
                            <button type="button" className="action-button delete" title="Supprimer"
                                onClick={() => handleSupprimer(n)}>
                                <i className="fa-solid fa-trash-can" />
                            </button>
                        </span>
                    </div>
                    {n.children.length > 0 && renderNoeuds(n.children)}
                </li>
            ))}
        </ul>
    );

    const exclus = form?.mode === 'deplacer' ? idsAvecDescendants(form.noeud) : new Set<number>();
    const titreModal = form?.mode === 'creer' ? 'Ajouter une activité'
        : form?.mode === 'modifier' ? "Modifier l'activité" : "Déplacer l'activité";

    return (
        <div className="pc-panel">
            <div className="main-header">
                <button className="sidebar-btn" onClick={() => ouvrir({ mode: 'creer', parentId: null })}>
                    Ajouter une activité
                </button>
            </div>
            <p className="pc-intro">
                Le plan de classement décrit à quoi servent vos documents (fonctions et activités de l'UO),
                indépendamment de l'organigramme. Rattachez ensuite chaque type de document à une activité
                depuis le formulaire du type : ses documents en héritent.
            </p>

            {loading ? (
                <p className="pc-empty"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</p>
            ) : arbre.length === 0 ? (
                <p className="pc-empty">Aucune activité pour le moment.</p>
            ) : renderNoeuds(arbre)}

            <Modal isOpen={form !== null} onClose={() => setForm(null)} title={titreModal}>
                <form className="pc-form" onSubmit={handleSubmit}>
                    {form?.mode !== 'deplacer' && (
                        <>
                            <div className="form-field">
                                <input className="form-field-input" placeholder="Code (ex. 03.2)" aria-label="Code"
                                    value={code} onChange={e => setCode(e.target.value)} maxLength={30} required />
                            </div>
                            <div className="form-field">
                                <input className="form-field-input" placeholder="Libellé (ex. Factures et paiements)" aria-label="Libellé"
                                    value={libelle} onChange={e => setLibelle(e.target.value)} maxLength={150} required />
                            </div>
                        </>
                    )}
                    {form?.mode !== 'modifier' && (
                        <div className="form-field">
                            <label className="form-field-label" htmlFor="pc-parent">Activité parente</label>
                            <select id="pc-parent" className="form-field-input up-select"
                                value={parentChoisi} onChange={e => setParentChoisi(e.target.value)}>
                                <option value="">— Racine du plan —</option>
                                {options.filter(o => !exclus.has(o.id)).map(o => (
                                    <option key={o.id} value={o.id}>{o.label}</option>
                                ))}
                            </select>
                        </div>
                    )}
                    <button type="submit" className="form-submit-btn" disabled={saving}>
                        {saving ? 'Enregistrement...' : 'Enregistrer'}
                    </button>
                </form>
            </Modal>
        </div>
    );
}

export default PlanClassementPanel;
