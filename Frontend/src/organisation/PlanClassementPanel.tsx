import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import ActiviteTreeModal from './ActiviteTreeModal';
import {
    getPlanClassement, deplacerNoeudPlanClassement, supprimerNoeudPlanClassement,
} from '../services/organisation/PlanClassementService';
import type { PlanClassementNoeudDto } from '../services/organisation/PlanClassementService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
// Même présentation que les emplacements physiques : arbre indenté (.pl-*) et menu "..." (.action-menu).
import '../Style/organisation/PhysicalLocationsPanel.css';
import '../Style/Admin/UserTable.css';
import '../Style/Admin/PlanClassementPanel.css';

interface Props {
    uoId: number | null;
}

type ModalState =
    | { open: false }
    | { open: true; mode: 'create'; parentId: number | null; parentLabel: string | null }
    | { open: true; mode: 'update'; node: PlanClassementNoeudDto };

const collecterIds = (n: PlanClassementNoeudDto, acc = new Set<number>()): Set<number> => {
    acc.add(n.id);
    n.children.forEach(c => collecterIds(c, acc));
    return acc;
};

const trouver = (noeuds: PlanClassementNoeudDto[], id: number): PlanClassementNoeudDto | null => {
    for (const n of noeuds) {
        if (n.id === id) return n;
        const dans = trouver(n.children, id);
        if (dans) return dans;
    }
    return null;
};

const compterDescendants = (n: PlanClassementNoeudDto): number =>
    n.children.reduce((total, c) => total + 1 + compterDescendants(c), 0);

/**
 * Plan de classement de l'UO de l'éditeur — arborescence d'activités (code généré + libellé), indépendante de
 * l'organigramme. Même ergonomie que les emplacements physiques : création/modification dans un organigramme,
 * déplacement par glisser-déposer, toutes les actions derrière un seul menu "...". Les TYPES de documents s'y
 * rattachent (depuis le formulaire du type) — un document hérite de l'activité de son type.
 * Voir PlanClassementService côté serveur.
 */
function PlanClassementPanel({ uoId }: Props) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [arbre, setArbre] = useState<PlanClassementNoeudDto[]>([]);
    const [loading, setLoading] = useState(false);
    const [modal, setModal] = useState<ModalState>({ open: false });
    const [busyId, setBusyId] = useState<number | null>(null);
    const [searchTerm, setSearchTerm] = useState('');
    // Ids repliés (par défaut tout est déplié).
    const [replies, setReplies] = useState<Set<number>>(new Set());

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

    // ── Recherche (libellé ou code) : garde les correspondances et leurs ancêtres ──
    const filterActive = searchTerm.trim() !== '';
    const { visibleIds, matchIds } = useMemo(() => {
        if (!filterActive) return { visibleIds: null as Set<number> | null, matchIds: new Set<number>() };
        const terme = searchTerm.trim().toLowerCase();
        const visible = new Set<number>();
        const matches = new Set<number>();
        const visiter = (n: PlanClassementNoeudDto): boolean => {
            const self = n.libelle.toLowerCase().includes(terme) || n.code.toLowerCase().includes(terme);
            const enfant = n.children.map(visiter).some(Boolean);
            if (self) matches.add(n.id);
            if (self || enfant) { visible.add(n.id); return true; }
            return false;
        };
        arbre.forEach(visiter);
        return { visibleIds: visible, matchIds: matches };
    }, [arbre, searchTerm, filterActive]);

    const arbreAffiche = visibleIds ? arbre.filter(n => visibleIds.has(n.id)) : arbre;

    // ── Glisser-déposer (déplacement) ──
    const [draggedId, setDraggedId] = useState<number | null>(null);
    const [dragOverId, setDragOverId] = useState<number | 'ROOT' | null>(null);

    // Sous-arbre du nœud déplacé : aucune de ces cibles n'est valide (créerait un cycle).
    const idsInvalides = useMemo(() => {
        if (draggedId == null) return new Set<number>();
        const noeud = trouver(arbre, draggedId);
        return noeud ? collecterIds(noeud) : new Set<number>();
    }, [arbre, draggedId]);

    const estCibleValide = (cible: PlanClassementNoeudDto | null) =>
        draggedId != null && (cible === null || !idsInvalides.has(cible.id));

    const handleDragStart = (e: React.DragEvent, id: number) => {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', String(id));
        setDraggedId(id);
    };
    const handleDragEnd = () => { setDraggedId(null); setDragOverId(null); };
    const handleDragOver = (e: React.DragEvent, cible: PlanClassementNoeudDto | null) => {
        if (!estCibleValide(cible)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        const key = cible ? cible.id : 'ROOT';
        if (dragOverId !== key) setDragOverId(key);
    };
    const handleDragLeave = (key: number | 'ROOT') => setDragOverId(prev => (prev === key ? null : prev));
    const handleDrop = async (e: React.DragEvent, cible: PlanClassementNoeudDto | null) => {
        e.preventDefault();
        setDragOverId(null);
        const id = draggedId;
        setDraggedId(null);
        if (id == null || !estCibleValide(cible)) return;
        setBusyId(id);
        try {
            await deplacerNoeudPlanClassement(id, cible ? cible.id : null);
            notify.success('Activité déplacée — codes recalculés');
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du déplacement');
        } finally {
            setBusyId(null);
        }
    };

    // ── Menu "..." : un seul ouvert à la fois pour tout l'arbre ──
    const [openMenuId, setOpenMenuId] = useState<number | null>(null);
    const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);
    const menuRef = useRef<HTMLDivElement | null>(null);
    const menuButtonRefs = useRef<Record<number, HTMLButtonElement | null>>({});

    const closeMenu = () => { setOpenMenuId(null); setMenuPos(null); };
    const toggleMenu = (id: number) => {
        if (openMenuId === id) { closeMenu(); return; }
        const btn = menuButtonRefs.current[id];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setMenuPos({ top: rect.bottom + window.scrollY + 4, left: rect.right + window.scrollX });
        }
        setOpenMenuId(id);
    };

    useEffect(() => {
        if (openMenuId == null) return;
        const handleClickOutside = (event: MouseEvent) => {
            const target = event.target as Node;
            const clickedToggle = menuButtonRefs.current[openMenuId]?.contains(target);
            const clickedMenu = menuRef.current?.contains(target);
            if (!clickedToggle && !clickedMenu) closeMenu();
        };
        const handleScrollOrResize = () => closeMenu();
        document.addEventListener('mousedown', handleClickOutside);
        window.addEventListener('scroll', handleScrollOrResize, true);
        window.addEventListener('resize', handleScrollOrResize);
        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            window.removeEventListener('scroll', handleScrollOrResize, true);
            window.removeEventListener('resize', handleScrollOrResize);
        };
    }, [openMenuId]);

    // ── Actions ──
    const handleSupprimer = async (n: PlanClassementNoeudDto) => {
        const sous = compterDescendants(n);
        const ok = await confirm({
            title: 'Supprimer cette activité',
            message: `Supprimer "${n.code} ${n.libelle}"`
                + (sous > 0 ? ` et ses ${sous} sous-activité${sous > 1 ? 's' : ''}` : '')
                + ' ? Les types de documents qui y sont rattachés seront détachés.',
            confirmLabel: 'Supprimer',
        });
        if (!ok) return;
        setBusyId(n.id);
        try {
            await supprimerNoeudPlanClassement(n.id);
            notify.success('Activité supprimée');
            await charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la suppression');
        } finally {
            setBusyId(null);
        }
    };

    const toggleReplie = (id: number) => setReplies(prev => {
        const suivant = new Set(prev);
        if (suivant.has(id)) suivant.delete(id); else suivant.add(id);
        return suivant;
    });

    if (uoId == null) {
        return (
            <div className="pl-empty">
                <i className="fa-solid fa-building-circle-exclamation" />
                <p>Chargement de votre unité organisationnelle…</p>
            </div>
        );
    }

    return (
        <div className="pl-panel">
            <div className="main-header">
                <button className="sidebar-btn"
                    onClick={() => setModal({ open: true, mode: 'create', parentId: null, parentLabel: null })}>
                    <i className="fa-solid fa-plus" /> Créer une activité
                </button>
            </div>

            <div className="pl-filters">
                <div className="pl-search-field">
                    <i className="fa-solid fa-magnifying-glass" />
                    <input
                        type="text"
                        placeholder="Rechercher par libellé ou code…"
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                    />
                    {searchTerm && (
                        <button type="button" className="pl-search-clear" onClick={() => setSearchTerm('')} aria-label="Effacer">
                            ✕
                        </button>
                    )}
                </div>
            </div>

            {loading ? (
                <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
            ) : (
                <div
                    className={`pl-tree ${draggedId != null && dragOverId === 'ROOT' ? 'pl-tree-drag-over' : ''}`}
                    onDragOver={(e) => handleDragOver(e, null)}
                    onDragLeave={() => handleDragLeave('ROOT')}
                    onDrop={(e) => handleDrop(e, null)}
                >
                    {draggedId != null && (
                        <div
                            className={`pl-root-dropzone ${dragOverId === 'ROOT' ? 'drag-over' : ''}`}
                            onDragOver={(e) => handleDragOver(e, null)}
                            onDragLeave={() => handleDragLeave('ROOT')}
                            onDrop={(e) => handleDrop(e, null)}
                        >
                            <i className="fa-solid fa-arrow-turn-up" /> Déposer ici pour en faire une activité racine
                        </div>
                    )}
                    {arbre.length === 0 ? (
                        <div className="pl-empty">
                            <i className="fa-solid fa-box-open" />
                            <p>Aucune activité pour le moment.</p>
                        </div>
                    ) : arbreAffiche.length === 0 ? (
                        <div className="pl-empty">
                            <i className="fa-solid fa-magnifying-glass" />
                            <p>Aucune activité ne correspond à la recherche.</p>
                        </div>
                    ) : (
                        arbreAffiche.map(n => (
                            <ActiviteNode
                                key={n.id}
                                node={n}
                                depth={0}
                                busyId={busyId}
                                draggedId={draggedId}
                                dragOverId={dragOverId}
                                replies={replies}
                                onToggleReplie={toggleReplie}
                                filterActive={filterActive}
                                visibleIds={visibleIds}
                                matchIds={matchIds}
                                onAddChild={(parent) => setModal({
                                    open: true, mode: 'create', parentId: parent.id, parentLabel: `${parent.code} ${parent.libelle}`,
                                })}
                                onEdit={(node) => setModal({ open: true, mode: 'update', node })}
                                onDelete={handleSupprimer}
                                onDragStart={handleDragStart}
                                onDragEnd={handleDragEnd}
                                onDragOver={handleDragOver}
                                onDragLeave={handleDragLeave}
                                onDrop={handleDrop}
                                openMenuId={openMenuId}
                                menuPos={menuPos}
                                menuRef={menuRef}
                                menuButtonRefs={menuButtonRefs}
                                onToggleMenu={toggleMenu}
                                onCloseMenu={closeMenu}
                            />
                        ))
                    )}
                </div>
            )}

            {modal.open && (
                <ActiviteTreeModal
                    isOpen
                    onClose={() => setModal({ open: false })}
                    uoId={uoId}
                    mode={modal.mode}
                    parentId={modal.mode === 'create' ? modal.parentId : undefined}
                    parentLabel={modal.mode === 'create' ? modal.parentLabel : undefined}
                    existingNode={modal.mode === 'update' ? modal.node : undefined}
                    onSaved={() => { setModal({ open: false }); charger(); }}
                />
            )}
        </div>
    );
}

interface NodeProps {
    node: PlanClassementNoeudDto;
    depth: number;
    busyId: number | null;
    draggedId: number | null;
    dragOverId: number | 'ROOT' | null;
    replies: Set<number>;
    onToggleReplie: (id: number) => void;
    filterActive: boolean;
    visibleIds: Set<number> | null;
    matchIds: Set<number>;
    onAddChild: (parent: PlanClassementNoeudDto) => void;
    onEdit: (node: PlanClassementNoeudDto) => void;
    onDelete: (node: PlanClassementNoeudDto) => void;
    onDragStart: (e: React.DragEvent, id: number) => void;
    onDragEnd: () => void;
    onDragOver: (e: React.DragEvent, cible: PlanClassementNoeudDto | null) => void;
    onDragLeave: (key: number | 'ROOT') => void;
    onDrop: (e: React.DragEvent, cible: PlanClassementNoeudDto | null) => void;
    openMenuId: number | null;
    menuPos: { top: number; left: number } | null;
    menuRef: React.RefObject<HTMLDivElement | null>;
    menuButtonRefs: React.RefObject<Record<number, HTMLButtonElement | null>>;
    onToggleMenu: (id: number) => void;
    onCloseMenu: () => void;
}

function ActiviteNode(props: NodeProps) {
    const {
        node, depth, busyId, draggedId, dragOverId, replies, onToggleReplie, filterActive, visibleIds, matchIds,
        onAddChild, onEdit, onDelete, onDragStart, onDragEnd, onDragOver, onDragLeave, onDrop,
        openMenuId, menuPos, menuRef, menuButtonRefs, onToggleMenu, onCloseMenu,
    } = props;

    const isBusy = busyId === node.id;
    const isBeingDragged = draggedId === node.id;
    const isDropTarget = dragOverId === node.id;
    // Une activité verrouillée (documents classés) ne se déplace pas : son code, et celui de ses descendants, en dépendent.
    const isDraggable = !isBusy && !node.verrouille;
    const isMatch = matchIds.has(node.id);

    const enfantsAffiches = visibleIds ? node.children.filter(c => visibleIds.has(c.id)) : node.children;
    const hasChildren = enfantsAffiches.length > 0;
    const isExpanded = filterActive || !replies.has(node.id);
    const titreVerrou = 'Verrouillée : des documents sont classés dans cette activité ou une sous-activité';

    return (
        <div className="pl-branch">
            <div
                className={`pl-node ${isBeingDragged ? 'dragging' : ''} ${isDropTarget ? 'drag-over' : ''} ${isMatch ? 'pl-match' : ''}`}
                style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}
                draggable={isDraggable}
                onDragStart={(e) => onDragStart(e, node.id)}
                onDragEnd={onDragEnd}
                onDragOver={(e) => { e.stopPropagation(); onDragOver(e, node); }}
                onDragLeave={() => onDragLeave(node.id)}
                onDrop={(e) => { e.stopPropagation(); onDrop(e, node); }}
            >
                {hasChildren ? (
                    <button type="button" className="pl-toggle" onClick={() => onToggleReplie(node.id)}
                        aria-label={isExpanded ? 'Replier' : 'Déplier'}>
                        {isExpanded ? '▾' : '▸'}
                    </button>
                ) : (
                    <span className="pl-toggle-spacer" />
                )}
                <i className={`fa-solid fa-grip-vertical pl-drag-handle ${node.verrouille ? 'pc-handle-locked' : ''}`}
                    title={node.verrouille ? titreVerrou : 'Glisser pour déplacer'} />
                <span className="pl-type-tag chemin">{node.code}</span>
                <span className="pl-name">{node.libelle}</span>
                <span className="pl-occupation-tag">
                    {node.nbTypes} type{node.nbTypes > 1 ? 's' : ''}
                    {node.nbDocuments > 0 && ` · ${node.nbDocuments} document${node.nbDocuments > 1 ? 's' : ''}`}
                </span>
                {node.verrouille && <i className="fa-solid fa-lock pc-lock" title={titreVerrou} />}

                <div className="pl-actions">
                    <div className="action-menu-wrapper pl-actions-compact">
                        <button
                            ref={(el) => { menuButtonRefs.current[node.id] = el; }}
                            onClick={() => onToggleMenu(node.id)}
                            disabled={isBusy}
                            className="menu-toggle"
                            aria-label="Plus d'actions"
                            aria-expanded={openMenuId === node.id}
                        >
                            <i className="fa-solid fa-ellipsis" />
                        </button>

                        {openMenuId === node.id && menuPos && createPortal(
                            <div
                                ref={menuRef}
                                className="action-menu"
                                style={{ position: 'fixed', top: menuPos.top, left: menuPos.left, transform: 'translateX(-100%)' }}
                            >
                                <button onClick={() => { onCloseMenu(); onAddChild(node); }} className="action-menu-item">
                                    <i className="fa-solid fa-plus" /> Ajouter une sous-activité
                                </button>
                                <button onClick={() => { onCloseMenu(); onEdit(node); }} className="action-menu-item">
                                    <i className="fa-solid fa-pen" /> Modifier
                                </button>
                                <button
                                    onClick={() => { onCloseMenu(); onDelete(node); }}
                                    className="action-menu-item"
                                    disabled={node.verrouille}
                                    title={node.verrouille ? titreVerrou : undefined}
                                >
                                    <i className="fa-solid fa-trash" />{' '}
                                    {node.children.length > 0 ? 'Supprimer avec sa sous-arborescence' : 'Supprimer'}
                                </button>
                            </div>,
                            document.body
                        )}
                    </div>
                </div>
            </div>
            {hasChildren && isExpanded && (
                <div className="pl-children">
                    {enfantsAffiches.map(c => (
                        <ActiviteNode key={c.id} {...props} node={c} depth={depth + 1} />
                    ))}
                </div>
            )}
        </div>
    );
}

export default PlanClassementPanel;
