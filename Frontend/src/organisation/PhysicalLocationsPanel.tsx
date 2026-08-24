import { useState, useEffect, useCallback } from 'react';
import Modal from '../Page/Modal';
import {
    getArbreEmplacements,
    creerEmplacement,
    modifierEmplacement,
    changerTypeStockage,
    desactiverEmplacement,
    reactiverEmplacement,
    supprimerEmplacement,
} from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationNodeDto, PhysicalLocationCreateDto } from '../services/organisation/PhysicalLocationService';
import '../Style/organisation/PhysicalLocationsPanel.css';

interface PhysicalLocationsPanelProps {
    /** null = pas d'UO sélectionnée (vue globale admin) — l'arbre est par UO, pas d'affichage possible. */
    uoId: number | null;
}

interface FormState {
    parentId: string | null;
    id?: string; // édition si présent
    code: string;
    name: string;
    description: string;
    storagePoint: boolean;
}

const FORM_VIDE: FormState = { parentId: null, code: '', name: '', description: '', storagePoint: true };

function PhysicalLocationsPanel({ uoId }: PhysicalLocationsPanelProps) {
    const [arbre, setArbre] = useState<PhysicalLocationNodeDto[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [actionError, setActionError] = useState('');
    const [busyId, setBusyId] = useState<string | null>(null);

    const [isFormOpen, setIsFormOpen] = useState(false);
    const [form, setForm] = useState<FormState>(FORM_VIDE);
    const [saving, setSaving] = useState(false);

    const charger = useCallback(async () => {
        if (uoId == null) { setArbre([]); return; }
        setLoading(true);
        setError('');
        try {
            const data = await getArbreEmplacements(uoId);
            setArbre(data);
        } catch (err: any) {
            setError(err.message ?? 'Erreur de chargement');
        } finally {
            setLoading(false);
        }
    }, [uoId]);

    useEffect(() => { charger(); }, [charger]);

    const ouvrirCreation = (parentId: string | null) => {
        setForm({ ...FORM_VIDE, parentId });
        setActionError('');
        setIsFormOpen(true);
    };

    const ouvrirEdition = (node: PhysicalLocationNodeDto) => {
        setForm({
            id: node.id,
            parentId: null,
            code: node.code,
            name: node.name,
            description: '',
            storagePoint: node.storagePoint,
        });
        setActionError('');
        setIsFormOpen(true);
    };

    const soumettreForm = async () => {
        if (!form.code.trim() || !form.name.trim()) {
            setActionError('Le code et le nom sont obligatoires');
            return;
        }
        setSaving(true);
        setActionError('');
        try {
            if (form.id) {
                await modifierEmplacement(form.id, { code: form.code, name: form.name, description: form.description });
            } else {
                if (uoId == null) return;
                const dto: PhysicalLocationCreateDto = {
                    code: form.code,
                    name: form.name,
                    description: form.description || undefined,
                    storagePoint: form.storagePoint,
                    parentId: form.parentId,
                    uniteOrganisationnelleId: uoId,
                };
                await creerEmplacement(dto);
            }
            setIsFormOpen(false);
            await charger();
        } catch (err: any) {
            setActionError(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    const withBusy = async (id: string, action: () => Promise<void>) => {
        setBusyId(id);
        setActionError('');
        try {
            await action();
            await charger();
        } catch (err: any) {
            setActionError(err.message ?? 'Erreur');
        } finally {
            setBusyId(null);
        }
    };

    const handleToggleType = (node: PhysicalLocationNodeDto) =>
        withBusy(node.id, () => changerTypeStockage(node.id, !node.storagePoint).then(() => {}));

    const handleToggleStatus = (node: PhysicalLocationNodeDto) =>
        withBusy(node.id, () =>
            (node.status === 'ACTIVE' ? desactiverEmplacement(node.id) : reactiverEmplacement(node.id)).then(() => {}));

    const handleSupprimer = (node: PhysicalLocationNodeDto) => {
        if (!window.confirm(`Supprimer définitivement "${node.name}" ? Impossible s'il a des enfants ou des documents rattachés.`)) return;
        withBusy(node.id, () => supprimerEmplacement(node.id));
    };

    if (uoId == null) {
        return (
            <div className="pl-empty">
                <i className="fa-solid fa-building-circle-exclamation" />
                <p>Sélectionnez une unité organisationnelle pour gérer ses emplacements physiques.</p>
            </div>
        );
    }

    return (
        <div className="pl-panel">
            <div className="main-header">
                <button className="sidebar-btn" onClick={() => ouvrirCreation(null)}>
                    <i className="fa-solid fa-plus" /> Créer un emplacement racine
                </button>
            </div>

            {error && <div className="alert alert-error">{error}</div>}
            {actionError && <div className="alert alert-error">{actionError}</div>}

            {loading ? (
                <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
            ) : arbre.length === 0 ? (
                <div className="pl-empty">
                    <i className="fa-solid fa-box-open" />
                    <p>Aucun emplacement physique pour cette UO.</p>
                </div>
            ) : (
                <div className="pl-tree">
                    {arbre.map((n) => (
                        <PlNode
                            key={n.id}
                            node={n}
                            depth={0}
                            busyId={busyId}
                            onAddChild={ouvrirCreation}
                            onEdit={ouvrirEdition}
                            onToggleType={handleToggleType}
                            onToggleStatus={handleToggleStatus}
                            onDelete={handleSupprimer}
                        />
                    ))}
                </div>
            )}

            <Modal
                isOpen={isFormOpen}
                onClose={() => setIsFormOpen(false)}
                title={form.id ? 'Modifier l\'emplacement' : 'Nouvel emplacement'}
            >
                <div className="pl-form">
                    <label>
                        Code
                        <input type="text" value={form.code}
                            onChange={(e) => setForm(f => ({ ...f, code: e.target.value }))} />
                    </label>
                    <label>
                        Nom
                        <input type="text" value={form.name}
                            onChange={(e) => setForm(f => ({ ...f, name: e.target.value }))} />
                    </label>
                    <label>
                        Description
                        <textarea value={form.description} rows={2}
                            onChange={(e) => setForm(f => ({ ...f, description: e.target.value }))} />
                    </label>

                    {!form.id && (
                        <fieldset className="pl-type-choice">
                            <legend>Nature du nœud (définitive tant qu'il contient des documents/enfants)</legend>
                            <label>
                                <input type="radio" checked={form.storagePoint}
                                    onChange={() => setForm(f => ({ ...f, storagePoint: true }))} />
                                Point de stockage — recevra des documents, ne pourra pas avoir d'enfants
                            </label>
                            <label>
                                <input type="radio" checked={!form.storagePoint}
                                    onChange={() => setForm(f => ({ ...f, storagePoint: false }))} />
                                Nœud chemin — pourra avoir des enfants, ne recevra pas de document directement
                            </label>
                        </fieldset>
                    )}

                    {actionError && <p className="pl-form-error">{actionError}</p>}

                    <div className="pl-form-actions">
                        <button type="button" className="sidebar-btn" disabled={saving} onClick={soumettreForm}>
                            {saving ? <><i className="fa-solid fa-spinner fa-spin" /> Enregistrement…</> : 'Enregistrer'}
                        </button>
                    </div>
                </div>
            </Modal>
        </div>
    );
}

function PlNode({
    node, depth, busyId, onAddChild, onEdit, onToggleType, onToggleStatus, onDelete,
}: {
    node: PhysicalLocationNodeDto;
    depth: number;
    busyId: string | null;
    onAddChild: (parentId: string) => void;
    onEdit: (node: PhysicalLocationNodeDto) => void;
    onToggleType: (node: PhysicalLocationNodeDto) => void;
    onToggleStatus: (node: PhysicalLocationNodeDto) => void;
    onDelete: (node: PhysicalLocationNodeDto) => void;
}) {
    const isBusy = busyId === node.id;
    const isInactive = node.status === 'INACTIVE';

    return (
        <div className="pl-branch">
            <div className={`pl-node ${isInactive ? 'pl-node-inactive' : ''}`} style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}>
                <span className={`pl-type-tag ${node.storagePoint ? 'stockage' : 'chemin'}`}>
                    <i className={`fa-solid ${node.storagePoint ? 'fa-box' : 'fa-diagram-project'}`} />
                    {node.storagePoint ? 'Stockage' : 'Chemin'}
                </span>
                <span className="pl-name">{node.name}</span>
                <span className="pl-code">{node.code}</span>
                {isInactive && <span className="pl-status-tag">Inactif</span>}

                <div className="pl-actions">
                    {!node.storagePoint && !isInactive && (
                        <button title="Ajouter un enfant" onClick={() => onAddChild(node.id)} disabled={isBusy}>
                            <i className="fa-solid fa-plus" />
                        </button>
                    )}
                    <button title="Modifier" onClick={() => onEdit(node)} disabled={isBusy}>
                        <i className="fa-solid fa-pen" />
                    </button>
                    <button title={node.storagePoint ? 'Convertir en chemin' : 'Convertir en stockage'}
                        onClick={() => onToggleType(node)} disabled={isBusy}>
                        <i className="fa-solid fa-shuffle" />
                    </button>
                    <button title={isInactive ? 'Réactiver' : 'Désactiver'}
                        onClick={() => onToggleStatus(node)} disabled={isBusy}>
                        <i className={`fa-solid ${isInactive ? 'fa-toggle-off' : 'fa-toggle-on'}`} />
                    </button>
                    <button title="Supprimer" className="pl-delete-btn"
                        onClick={() => onDelete(node)} disabled={isBusy}>
                        <i className="fa-solid fa-trash" />
                    </button>
                </div>
            </div>
            {node.children.length > 0 && (
                <div className="pl-children">
                    {node.children.map((c) => (
                        <PlNode
                            key={c.id}
                            node={c}
                            depth={depth + 1}
                            busyId={busyId}
                            onAddChild={onAddChild}
                            onEdit={onEdit}
                            onToggleType={onToggleType}
                            onToggleStatus={onToggleStatus}
                            onDelete={onDelete}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}

export default PhysicalLocationsPanel;
