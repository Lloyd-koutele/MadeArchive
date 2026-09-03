import { useState, useEffect, useCallback, useMemo } from 'react';
import Modal from '../Page/Modal';
import {
    getArbreEmplacements,
    creerEmplacement,
    modifierEmplacement,
    changerTypeStockage,
    desactiverEmplacement,
    reactiverEmplacement,
    supprimerEmplacement,
    deplacerEmplacement,
} from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationNodeDto, PhysicalLocationCreateDto } from '../services/organisation/PhysicalLocationService';
import '../Style/organisation/PhysicalLocationsPanel.css';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';

interface PhysicalLocationsPanelProps {
    /** null = pas d'UO sélectionnée (vue globale admin) — l'arbre est par UO, pas d'affichage possible. */
    uoId: number | null;
}

interface FormState {
    parentId: string | null;
    id?: string; // édition si présent
    name: string;
    description: string;
    storagePoint: boolean;
}

const FORM_VIDE: FormState = { parentId: null, name: '', description: '', storagePoint: true };

function PhysicalLocationsPanel({ uoId }: PhysicalLocationsPanelProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [arbre, setArbre] = useState<PhysicalLocationNodeDto[]>([]);
    const [loading, setLoading] = useState(false);
    const [busyId, setBusyId] = useState<string | null>(null);

    const [isFormOpen, setIsFormOpen] = useState(false);
    const [form, setForm] = useState<FormState>(FORM_VIDE);
    const [saving, setSaving] = useState(false);

    // ── Pliement/dépliement ──────────────────────────────────────────────
    const [expanded, setExpanded] = useState<Set<string>>(new Set());

    const allExpandableIds = useMemo(() => {
        const ids = new Set<string>();
        const visiter = (n: PhysicalLocationNodeDto) => {
            if (n.children.length > 0) ids.add(n.id);
            n.children.forEach(visiter);
        };
        arbre.forEach(visiter);
        return ids;
    }, [arbre]);

    // Nouveaux nœuds dépliables ajoutés par défaut (ex. après création d'un
    // enfant) — sans jamais re-plier un nœud que l'utilisateur a replié à la main.
    useEffect(() => {
        setExpanded(prev => {
            const next = new Set(prev);
            allExpandableIds.forEach(id => next.add(id));
            return next;
        });
    }, [allExpandableIds]);

    const toggleExpand = (id: string) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    // ── Recherche / filtre ───────────────────────────────────────────────
    const [searchTerm, setSearchTerm] = useState('');
    const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
    const filterActive = searchTerm.trim() !== '' || statusFilter !== 'ALL';

    const { visibleIds, matchIds } = useMemo(() => {
        if (!filterActive) return { visibleIds: null as Set<string> | null, matchIds: new Set<string>() };

        const terme = searchTerm.trim().toLowerCase();
        const nodeMatches = (n: PhysicalLocationNodeDto) =>
            (terme === '' || n.name.toLowerCase().includes(terme))
            && (statusFilter === 'ALL' || n.status === statusFilter);

        const visible = new Set<string>();
        const matches = new Set<string>();
        const visiter = (n: PhysicalLocationNodeDto): boolean => {
            const selfMatch = nodeMatches(n);
            const enfantMatch = n.children.map(visiter).some(Boolean);
            if (selfMatch) matches.add(n.id);
            if (selfMatch || enfantMatch) { visible.add(n.id); return true; }
            return false;
        };
        arbre.forEach(visiter);
        return { visibleIds: visible, matchIds: matches };
    }, [arbre, searchTerm, statusFilter, filterActive]);

    const arbreAffiche = visibleIds ? arbre.filter(n => visibleIds.has(n.id)) : arbre;

    // ── Glisser-déposer (déplacement) ────────────────────────────────────
    const [draggedId, setDraggedId] = useState<string | null>(null);
    const [dragOverId, setDragOverId] = useState<string | 'ROOT' | null>(null);

    // Sous-arborescence du nœud en cours de déplacement (lui-même inclus) —
    // aucun de ces id n'est une cible valide (créerait un cycle).
    const idsInvalides = useMemo(() => {
        if (!draggedId) return new Set<string>();
        const trouver = (nodes: PhysicalLocationNodeDto[]): PhysicalLocationNodeDto | null => {
            for (const n of nodes) {
                if (n.id === draggedId) return n;
                const dans = trouver(n.children);
                if (dans) return dans;
            }
            return null;
        };
        const collecter = (n: PhysicalLocationNodeDto, acc: Set<string>) => {
            acc.add(n.id);
            n.children.forEach(c => collecter(c, acc));
        };
        const noeud = trouver(arbre);
        const acc = new Set<string>();
        if (noeud) collecter(noeud, acc);
        return acc;
    }, [arbre, draggedId]);

    const charger = useCallback(async () => {
        if (uoId == null) { setArbre([]); return; }
        setLoading(true);
        try {
            const data = await getArbreEmplacements(uoId);
            setArbre(data);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur de chargement');
        } finally {
            setLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId]);

    useEffect(() => { charger(); }, [charger]);
    // Rattaché depuis une autre interface pendant qu'on est resté sur cet
    // écran (un autre onglet, un autre admin...) → rechargé au retour de focus.
    useRefetchOnFocus(charger);

    const ouvrirCreation = (parentId: string | null) => {
        setForm({ ...FORM_VIDE, parentId });
        setIsFormOpen(true);
    };

    const ouvrirEdition = (node: PhysicalLocationNodeDto) => {
        setForm({
            id: node.id,
            parentId: null,
            name: node.name,
            description: '',
            storagePoint: node.storagePoint,
        });
        setIsFormOpen(true);
    };

    const soumettreForm = async () => {
        if (!form.name.trim()) {
            notify.error('Le nom est obligatoire');
            return;
        }
        setSaving(true);
        try {
            if (form.id) {
                await modifierEmplacement(form.id, { name: form.name, description: form.description });
            } else {
                if (uoId == null) return;
                const dto: PhysicalLocationCreateDto = {
                    name: form.name,
                    description: form.description || undefined,
                    storagePoint: form.storagePoint,
                    parentId: form.parentId,
                    uniteOrganisationnelleId: uoId,
                };
                await creerEmplacement(dto);
            }
            setIsFormOpen(false);
            notify.success(form.id ? 'Emplacement modifié avec succès' : 'Emplacement créé avec succès');
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    const withBusy = async (id: string, action: () => Promise<void>) => {
        setBusyId(id);
        try {
            await action();
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur');
        } finally {
            setBusyId(null);
        }
    };

    const handleToggleType = (node: PhysicalLocationNodeDto) =>
        withBusy(node.id, () => changerTypeStockage(node.id, !node.storagePoint).then(() => {}));

    const handleToggleStatus = (node: PhysicalLocationNodeDto) =>
        withBusy(node.id, () =>
            (node.status === 'ACTIVE' ? desactiverEmplacement(node.id) : reactiverEmplacement(node.id)).then(() => {}));

    const compterSousArbre = (n: PhysicalLocationNodeDto): number =>
        1 + n.children.reduce((total, c) => total + compterSousArbre(c), 0);

    const handleSupprimer = async (node: PhysicalLocationNodeDto) => {
        const nbDescendants = compterSousArbre(node) - 1;
        const message = nbDescendants > 0
            ? `Supprimer définitivement "${node.name}" ET ses ${nbDescendants} descendant(s) ? `
              + `Impossible si l'un d'eux (lui-même ou l'un de ses descendants) a des documents rattachés.`
            : `Supprimer définitivement "${node.name}" ? Impossible s'il a des documents rattachés.`;
        if (!(await confirm({ message, danger: true }))) return;
        await withBusy(node.id, () => supprimerEmplacement(node.id));
    };

    // ── Glisser-déposer ────────────────────────────────────────────────────

    const handleDragStart = (e: React.DragEvent, id: string) => {
        setDraggedId(id);
        e.dataTransfer.setData('text/plain', id);
        e.dataTransfer.effectAllowed = 'move';
    };

    const handleDragEnd = () => {
        setDraggedId(null);
        setDragOverId(null);
    };

    /** target null = zone racine ; sinon le nœud cible. */
    const estCibleValide = (target: PhysicalLocationNodeDto | null): boolean => {
        if (!draggedId) return false;
        if (target === null) return true; // devenir racine — toujours valide
        if (idsInvalides.has(target.id)) return false; // lui-même ou son propre descendant
        if (target.storagePoint) return false; // ne peut pas avoir d'enfant
        if (target.status !== 'ACTIVE') return false; // pas sous une branche désactivée
        return true;
    };

    const handleDragOver = (e: React.DragEvent, target: PhysicalLocationNodeDto | null) => {
        if (!estCibleValide(target)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        const key = target ? target.id : 'ROOT';
        if (dragOverId !== key) setDragOverId(key);
    };

    const handleDragLeave = (key: string | 'ROOT') => {
        setDragOverId(prev => (prev === key ? null : prev));
    };

    const handleDrop = async (e: React.DragEvent, target: PhysicalLocationNodeDto | null) => {
        e.preventDefault();
        setDragOverId(null);
        const id = draggedId;
        setDraggedId(null);
        if (!id || !estCibleValide(target)) return;

        setBusyId(id);
        try {
            await deplacerEmplacement(id, target ? target.id : null);
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du déplacement');
        } finally {
            setBusyId(null);
        }
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

            <div className="pl-filters">
                <div className="pl-search-field">
                    <i className="fa-solid fa-magnifying-glass" />
                    <input
                        type="text"
                        placeholder="Rechercher par nom…"
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                    />
                    {searchTerm && (
                        <button type="button" className="pl-search-clear" onClick={() => setSearchTerm('')} aria-label="Effacer">
                            ✕
                        </button>
                    )}
                </div>
                <select
                    className="pl-status-filter"
                    value={statusFilter}
                    onChange={(e) => setStatusFilter(e.target.value as 'ALL' | 'ACTIVE' | 'INACTIVE')}
                >
                    <option value="ALL">Tous les statuts</option>
                    <option value="ACTIVE">Actifs seulement</option>
                    <option value="INACTIVE">Inactifs seulement</option>
                </select>
            </div>

            {loading ? (
                <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
            ) : (
                <div
                    className={`pl-tree ${draggedId && dragOverId === 'ROOT' ? 'pl-tree-drag-over' : ''}`}
                    onDragOver={(e) => handleDragOver(e, null)}
                    onDragLeave={() => handleDragLeave('ROOT')}
                    onDrop={(e) => handleDrop(e, null)}
                >
                    {/* Le fond du conteneur (espaces entre nœuds, sous le dernier
                        nœud...) est LUI-MÊME une cible "racine" — chaque nœud
                        stoppe la propagation de ses propres événements, donc un
                        dépôt qui n'atterrit pas précisément sur un nœud retombe
                        forcément ici. Ce bandeau reste comme repère visuel explicite. */}
                    {draggedId && (
                        <div
                            className={`pl-root-dropzone ${dragOverId === 'ROOT' ? 'drag-over' : ''}`}
                            onDragOver={(e) => handleDragOver(e, null)}
                            onDragLeave={() => handleDragLeave('ROOT')}
                            onDrop={(e) => handleDrop(e, null)}
                        >
                            <i className="fa-solid fa-arrow-turn-up" /> Déposer ici pour en faire une racine
                        </div>
                    )}
                    {arbre.length === 0 ? (
                        <div className="pl-empty">
                            <i className="fa-solid fa-box-open" />
                            <p>Aucun emplacement physique pour cette UO.</p>
                        </div>
                    ) : arbreAffiche.length === 0 ? (
                        <div className="pl-empty">
                            <i className="fa-solid fa-magnifying-glass" />
                            <p>Aucun emplacement ne correspond à la recherche.</p>
                        </div>
                    ) : (
                        arbreAffiche.map((n) => (
                            <PlNode
                                key={n.id}
                                node={n}
                                depth={0}
                                busyId={busyId}
                                draggedId={draggedId}
                                dragOverId={dragOverId}
                                expanded={expanded}
                                onToggleExpand={toggleExpand}
                                filterActive={filterActive}
                                visibleIds={visibleIds}
                                matchIds={matchIds}
                                onAddChild={ouvrirCreation}
                                onEdit={ouvrirEdition}
                                onToggleType={handleToggleType}
                                onToggleStatus={handleToggleStatus}
                                onDelete={handleSupprimer}
                                onDragStart={handleDragStart}
                                onDragEnd={handleDragEnd}
                                onDragOver={handleDragOver}
                                onDragLeave={handleDragLeave}
                                onDrop={handleDrop}
                            />
                        ))
                    )}
                </div>
            )}

            <Modal
                isOpen={isFormOpen}
                onClose={() => setIsFormOpen(false)}
                title={form.id ? 'Modifier l\'emplacement' : 'Nouvel emplacement'}
            >
                <div className="pl-form">
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
    node, depth, busyId, draggedId, dragOverId,
    expanded, onToggleExpand, filterActive, visibleIds, matchIds,
    onAddChild, onEdit, onToggleType, onToggleStatus, onDelete,
    onDragStart, onDragEnd, onDragOver, onDragLeave, onDrop,
}: {
    node: PhysicalLocationNodeDto;
    depth: number;
    busyId: string | null;
    draggedId: string | null;
    dragOverId: string | 'ROOT' | null;
    expanded: Set<string>;
    onToggleExpand: (id: string) => void;
    filterActive: boolean;
    visibleIds: Set<string> | null;
    matchIds: Set<string>;
    onAddChild: (parentId: string) => void;
    onEdit: (node: PhysicalLocationNodeDto) => void;
    onToggleType: (node: PhysicalLocationNodeDto) => void;
    onToggleStatus: (node: PhysicalLocationNodeDto) => void;
    onDelete: (node: PhysicalLocationNodeDto) => void;
    onDragStart: (e: React.DragEvent, id: string) => void;
    onDragEnd: () => void;
    onDragOver: (e: React.DragEvent, target: PhysicalLocationNodeDto | null) => void;
    onDragLeave: (key: string | 'ROOT') => void;
    onDrop: (e: React.DragEvent, target: PhysicalLocationNodeDto | null) => void;
}) {
    const isBusy = busyId === node.id;
    const isInactive = node.status === 'INACTIVE';
    const isBeingDragged = draggedId === node.id;
    const isDropTarget = dragOverId === node.id;
    const isDraggable = !isBusy;
    const isMatch = matchIds.has(node.id);

    const enfantsAffiches = visibleIds ? node.children.filter(c => visibleIds.has(c.id)) : node.children;
    const hasChildren = enfantsAffiches.length > 0;
    // Pendant une recherche, tout ce qui reste affiché est déplié — inutile
    // de forcer l'utilisateur à déplier manuellement pour voir un résultat.
    const isExpanded = filterActive || expanded.has(node.id);

    return (
        <div className="pl-branch">
            <div
                className={`pl-node ${isInactive ? 'pl-node-inactive' : ''} ${isBeingDragged ? 'dragging' : ''} ${isDropTarget ? 'drag-over' : ''} ${isMatch ? 'pl-match' : ''}`}
                style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}
                draggable={isDraggable}
                onDragStart={(e) => onDragStart(e, node.id)}
                onDragEnd={onDragEnd}
                onDragOver={(e) => { e.stopPropagation(); onDragOver(e, node); }}
                onDragLeave={() => onDragLeave(node.id)}
                onDrop={(e) => { e.stopPropagation(); onDrop(e, node); }}
            >
                {hasChildren ? (
                    <button
                        type="button"
                        className="pl-toggle"
                        onClick={() => onToggleExpand(node.id)}
                        aria-label={isExpanded ? 'Replier' : 'Déplier'}
                    >
                        {isExpanded ? '▾' : '▸'}
                    </button>
                ) : (
                    <span className="pl-toggle-spacer" />
                )}
                <i className="fa-solid fa-grip-vertical pl-drag-handle" title="Glisser pour déplacer" />
                <span className={`pl-type-tag ${node.storagePoint ? 'stockage' : 'chemin'}`}>
                    <i className={`fa-solid ${node.storagePoint ? 'fa-box' : 'fa-diagram-project'}`} />
                    {node.storagePoint ? 'Stockage' : 'Chemin'}
                </span>
                <span className="pl-name">{node.name}</span>
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
                    <button
                        title={node.children.length > 0 ? 'Supprimer avec sa sous-arborescence' : 'Supprimer'}
                        className="pl-delete-btn"
                        onClick={() => onDelete(node)} disabled={isBusy}>
                        <i className="fa-solid fa-trash" />
                    </button>
                </div>
            </div>
            {hasChildren && isExpanded && (
                <div className="pl-children">
                    {enfantsAffiches.map((c) => (
                        <PlNode
                            key={c.id}
                            node={c}
                            depth={depth + 1}
                            busyId={busyId}
                            draggedId={draggedId}
                            dragOverId={dragOverId}
                            expanded={expanded}
                            onToggleExpand={onToggleExpand}
                            filterActive={filterActive}
                            visibleIds={visibleIds}
                            matchIds={matchIds}
                            onAddChild={onAddChild}
                            onEdit={onEdit}
                            onToggleType={onToggleType}
                            onToggleStatus={onToggleStatus}
                            onDelete={onDelete}
                            onDragStart={onDragStart}
                            onDragEnd={onDragEnd}
                            onDragOver={onDragOver}
                            onDragLeave={onDragLeave}
                            onDrop={onDrop}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}

export default PhysicalLocationsPanel;
