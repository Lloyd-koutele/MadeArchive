import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import {
    getArbreEmplacements,
    changerTypeStockage,
    desactiverEmplacement,
    reactiverEmplacement,
    supprimerEmplacement,
    deplacerEmplacement,
    definirCapaciteEmplacement,
    definirContrainteEmplacement,
} from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationNodeDto, LocationModeContrainte } from '../services/organisation/PhysicalLocationService';
import { getDocumentsAccessibles, getDocumentDetail, streamPdfAAsBlob } from '../services/document/DocumentService';
import type { DocumentListItemDto } from '../services/document/DocumentService';
import PdfViewer from '../components/PdfViewer';
import { getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getArbreDossiers } from '../services/organisation/DossierService';
import DossierTreePicker from './DossierTreePicker';
import EmplacementTreeModal from './EmplacementTreeModal';
import type { CibleEditionExistante } from './EmplacementTreeModal';
import Modal from '../Page/Modal';
import '../Style/organisation/PhysicalLocationsPanel.css';
// .td-table/.status-tag/.doc-access-tag/.pagination — modale "Voir les
// documents" en simple tableau (pas de vignettes PDF, juste lister/ouvrir).
import '../Style/document/Typedocument.css';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';

interface PhysicalLocationsPanelProps {
    /** null = pas d'UO sélectionnée (vue globale admin) — l'arbre est par UO, pas d'affichage possible. */
    uoId: number | null;
    /**
     * "gestion" (EDITOR, voir EditorDasboard) : TOUTES les actions — créer,
     * modifier, convertir, activer/désactiver, supprimer, déplacer
     * (glisser-déposer). Entièrement piloté par l'éditeur depuis le 09/2026,
     * même modèle que les Dossiers.
     * "lecture" (défaut, ADMIN/ADMIN_UO) : consultation seule de l'arbre,
     * AUCUNE action — ADMIN/ADMIN_UO n'ont plus aucun droit d'écriture sur
     * les emplacements physiques.
     * Reflète exactement ce que le backend autorise pour ce rôle (voir
     * PhysicalLocationEditorController/PhysicalLocationLectureController) ;
     * une action non permise n'est même pas affichée, plutôt que montrée pour
     * échouer en 403 au clic.
     */
    mode?: 'gestion' | 'lecture';
    /** "Ouvrir dans l'emplacement" (menu "..." d'un document, voir
     *  ouvrirEmplacementDocument) — ce panneau n'a pas lui-même d'écran pour
     *  afficher un document dans son dossier, donc il délègue la navigation
     *  à l'appelant (EditorDasboard bascule sur l'onglet "Dossiers" et passe
     *  cet id à DossiersPanel via initialDossierId). Absent = action non
     *  proposée (ex. mode "lecture" — pas de menu d'actions du tout). */
    onOuvrirDansDossier?: (dossierId: number, typeDocumentId: number) => void;
}

/**
 * État du modal unique de création/modification — voir EmplacementTreeModal.
 * "create" : accroché exactement là où le bouton l'ayant ouvert a été cliqué
 * (racine de l'UO si parentId===null, ou sous le nœud chemin concerné).
 * "update" : porte le nœud cliqué ("Modifier") + sa descendance actuelle.
 */
type TreeModalState =
    | { open: false }
    | { open: true; mode: 'create'; parentId: string | null; parentLabel: string | null }
    | { open: true; mode: 'update'; node: PhysicalLocationNodeDto };

function PhysicalLocationsPanel({ uoId, mode = 'lecture', onOuvrirDansDossier }: PhysicalLocationsPanelProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const estGestionnaire = mode === 'gestion';
    const [arbre, setArbre] = useState<PhysicalLocationNodeDto[]>([]);
    const [loading, setLoading] = useState(false);
    const [busyId, setBusyId] = useState<string | null>(null);

    const [treeModal, setTreeModal] = useState<TreeModalState>({ open: false });

    // ── "Voir les documents" d'un point de stockage — simple liste, lecture
    // seule, disponible dans les DEUX modes (même un ADMIN/ADMIN_UO en lecture
    // seule peut consulter ce qu'un nœud contient). ──────────────────────────
    const [docsModal, setDocsModal] = useState<{ open: boolean; node: PhysicalLocationNodeDto | null }>({ open: false, node: null });
    const [docsModalDocs, setDocsModalDocs] = useState<DocumentListItemDto[]>([]);
    const [docsModalLoading, setDocsModalLoading] = useState(false);
    const [docsModalPage, setDocsModalPage] = useState(1);
    const [docsModalTotalPages, setDocsModalTotalPages] = useState(1);

    const chargerDocumentsDuNoeud = useCallback((node: PhysicalLocationNodeDto, page: number) => {
        setDocsModalLoading(true);
        getDocumentsAccessibles({ physicalLocationId: node.id, uoId, page, size: 10 })
            .then(result => {
                setDocsModalDocs(result.content);
                setDocsModalTotalPages(result.totalPages);
                setDocsModalPage(page);
            })
            .catch(() => setDocsModalDocs([]))
            .finally(() => setDocsModalLoading(false));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId]);

    const ouvrirDocumentsDuNoeud = (node: PhysicalLocationNodeDto) => {
        setDocsModal({ open: true, node });
        chargerDocumentsDuNoeud(node, 1);
    };

    // ── Actions "..." par document dans la liste ci-dessus ("Voir les
    // documents" d'un nœud) — deux raccourcis sans quitter l'écran : lire le
    // document directement (notre lecteur PDF, voir PdfViewer), ou être
    // amené à son VRAI emplacement dans l'app (son dossier, pas juste son nom
    // isolé dans cette liste) — ce panneau n'a pas lui-même d'écran pour ça,
    // il délègue à l'appelant (voir Javadoc onOuvrirDansDossier). ───────────
    const [lectureDocModal, setLectureDocModal] = useState<{ open: boolean; url: string | null; titre: string | null }>({ open: false, url: null, titre: null });

    const ouvrirEmplacementDocument = async (documentId: string) => {
        if (!onOuvrirDansDossier) return;
        try {
            const detail = await getDocumentDetail(documentId);
            if (detail.dossierId == null) {
                notify.error("Ce document n'est rattaché à aucun dossier — pas d'écran dédié pour l'ouvrir ailleurs que dans cette liste.");
                return;
            }
            onOuvrirDansDossier(detail.dossierId, detail.typeDocumentId);
        } catch {
            notify.error("Impossible d'ouvrir ce document dans son emplacement");
        }
    };

    const ouvrirLectureDocument = async (doc: DocumentListItemDto) => {
        try {
            const url = await streamPdfAAsBlob(doc.documentId);
            setLectureDocModal({ open: true, url, titre: doc.titre });
        } catch {
            notify.error("Impossible d'ouvrir ce document");
        }
    };

    const fermerLectureDocument = () => {
        if (lectureDocModal.url) URL.revokeObjectURL(lectureDocModal.url);
        setLectureDocModal({ open: false, url: null, titre: null });
    };

    // ── Capacité maximale — modifiable à tout moment (voir Javadoc backend) ──
    const [capaciteModal, setCapaciteModal] = useState<{ open: boolean; node: PhysicalLocationNodeDto | null }>({ open: false, node: null });
    const [capaciteValeur, setCapaciteValeur] = useState('');
    const [capaciteSaving, setCapaciteSaving] = useState(false);

    const ouvrirCapacite = (node: PhysicalLocationNodeDto) => {
        setCapaciteValeur(node.capaciteMax != null ? String(node.capaciteMax) : '');
        setCapaciteModal({ open: true, node });
    };

    const handleDefinirCapacite = async () => {
        if (!capaciteModal.node) return;
        const valeur = capaciteValeur.trim() === '' ? null : Math.max(1, Number(capaciteValeur));
        setCapaciteSaving(true);
        try {
            await definirCapaciteEmplacement(capaciteModal.node.id, valeur);
            notify.success('Capacité mise à jour');
            setCapaciteModal({ open: false, node: null });
            charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la mise à jour de la capacité');
        } finally {
            setCapaciteSaving(false);
        }
    };

    // ── Contrainte d'acceptation — seulement si le nœud est vide (voir
    // Javadoc backend definirContrainte). ────────────────────────────────────
    const [contrainteModal, setContrainteModal] = useState<{ open: boolean; node: PhysicalLocationNodeDto | null }>({ open: false, node: null });
    const [contrainteMode, setContrainteMode] = useState<LocationModeContrainte>('LIBRE');
    const [contrainteTypeId, setContrainteTypeId] = useState<number | null>(null);
    const [contrainteDossierId, setContrainteDossierId] = useState<number | null>(null);
    const [contrainteSaving, setContrainteSaving] = useState(false);
    const [typesUO, setTypesUO] = useState<TypeDocumentDto[]>([]);
    const [aDesDossiers, setADesDossiers] = useState(true);

    useEffect(() => {
        if (uoId == null) { setTypesUO([]); setADesDossiers(false); return; }
        getTypeDocumentsByUO(uoId).then(setTypesUO).catch(() => setTypesUO([]));
        getArbreDossiers(uoId).then(d => setADesDossiers(d.length > 0)).catch(() => setADesDossiers(false));
    }, [uoId]);

    const ouvrirContrainte = (node: PhysicalLocationNodeDto) => {
        setContrainteMode(node.modeContrainte);
        setContrainteTypeId(node.typeDocumentAccepteId);
        setContrainteDossierId(node.dossierId);
        setContrainteModal({ open: true, node });
    };

    const handleDefinirContrainte = async () => {
        if (!contrainteModal.node) return;
        if (contrainteMode === 'LIBRE') {
            notify.error('Choisissez un type de document ou un dossier');
            return;
        }
        if (contrainteMode === 'TYPE_UNIQUE' && contrainteTypeId == null) {
            notify.error('Choisissez un type de document');
            return;
        }
        if (contrainteMode === 'DOSSIER' && contrainteDossierId == null) {
            notify.error('Choisissez un dossier');
            return;
        }
        setContrainteSaving(true);
        try {
            await definirContrainteEmplacement(contrainteModal.node.id, contrainteMode, contrainteTypeId, contrainteDossierId);
            notify.success('Contrainte mise à jour');
            setContrainteModal({ open: false, node: null });
            charger();
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la mise à jour de la contrainte');
        } finally {
            setContrainteSaving(false);
        }
    };

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

    // ── Menu "..." compact (écran réduit — voir PhysicalLocationsPanel.css) ──
    // Un seul menu ouvert à la fois pour tout l'arbre, géré ici (pas dans
    // PlNode) car il faut une seule position/portail partagés, même pattern
    // que UserTable/TypedocumentList. Le menu reprend TOUS les boutons
    // autonomes (mêmes icônes, mêmes libellés, mêmes conditions d'affichage)
    // — rien n'est perdu, juste regroupé derrière un seul point d'entrée.
    const [openMenuId, setOpenMenuId] = useState<string | null>(null);
    const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);
    const menuRef = useRef<HTMLDivElement | null>(null);
    const menuButtonRefs = useRef<Record<string, HTMLButtonElement | null>>({});

    const closeMenu = () => { setOpenMenuId(null); setMenuPos(null); };

    const toggleMenu = (id: string) => {
        if (openMenuId === id) { closeMenu(); return; }
        const btn = menuButtonRefs.current[id];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setMenuPos({ top: rect.bottom + window.scrollY + 4, left: rect.right + window.scrollX });
        }
        setOpenMenuId(id);
    };

    useEffect(() => {
        if (!openMenuId) return;
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

    const ouvrirCreation = (parentId: string | null, parentLabel: string | null = null) => {
        setTreeModal({ open: true, mode: 'create', parentId, parentLabel });
    };

    const ouvrirEdition = (node: PhysicalLocationNodeDto) => {
        setTreeModal({ open: true, mode: 'update', node });
    };

    const handleTreeModalSaved = () => {
        setTreeModal({ open: false });
        charger();
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
            : `Supprimer définitivement "${node.name}"`;
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
                <p>{estGestionnaire
                    ? 'Chargement de votre unité organisationnelle…'
                    : 'Sélectionnez une unité organisationnelle pour consulter ses emplacements physiques.'}</p>
            </div>
        );
    }

    return (
        <div className="pl-panel">
            {/* Écriture réservée à EDITOR (mode="gestion") — ADMIN/ADMIN_UO n'ont
                plus aucun droit d'écriture (retiré le 09/2026, voir
                PhysicalLocationService côté backend), consultation uniquement. */}
            {estGestionnaire && (
                <div className="main-header">
                    <button className="sidebar-btn" onClick={() => ouvrirCreation(null)}>
                        <i className="fa-solid fa-plus" /> Créer un emplacement
                    </button>
                </div>
            )}

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
                                estGestionnaire={estGestionnaire}
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
                                onViewDocuments={ouvrirDocumentsDuNoeud}
                                onEditCapacite={ouvrirCapacite}
                                onEditContrainte={ouvrirContrainte}
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

            {estGestionnaire && treeModal.open && (
                <EmplacementTreeModal
                    isOpen={treeModal.open}
                    onClose={() => setTreeModal({ open: false })}
                    uoId={uoId}
                    mode={treeModal.mode}
                    parentId={treeModal.mode === 'create' ? treeModal.parentId : undefined}
                    parentLabel={treeModal.mode === 'create' ? treeModal.parentLabel : undefined}
                    existingNode={treeModal.mode === 'update' ? treeModal.node : undefined}
                    onEditCapacite={treeModal.mode === 'update' ? (cible: CibleEditionExistante) => {
                        setTreeModal({ open: false });
                        // Descripteur minimal (id/name/nombreDocuments/capaciteMax/...)
                        // suffisant pour ces modaux — voir CibleEditionExistante, qui vient
                        // d'un nœud quelconque de l'organigramme (racine OU descendant), pas
                        // forcément le PhysicalLocationNodeDto complet de treeModal.node.
                        ouvrirCapacite(cible as PhysicalLocationNodeDto);
                    } : undefined}
                    onEditContrainte={treeModal.mode === 'update' ? (cible: CibleEditionExistante) => {
                        setTreeModal({ open: false });
                        ouvrirContrainte(cible as PhysicalLocationNodeDto);
                    } : undefined}
                    onSaved={handleTreeModalSaved}
                />
            )}

            {/* ── "Voir les documents" d'un point de stockage — lecture seule ── */}
            <Modal
                isOpen={docsModal.open}
                onClose={() => setDocsModal({ open: false, node: null })}
                title={docsModal.node ? `Documents — "${docsModal.node.name}"` : 'Documents'}
                size="large"
            >
                {docsModalLoading ? (
                    <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement…</div>
                ) : docsModalDocs.length === 0 ? (
                    <div className="td-empty"><p>Aucun document dans cet emplacement.</p></div>
                ) : (
                    <>
                        <div className="td-table-container">
                            <table className="td-table">
                                <thead>
                                    <tr>
                                        <th>Titre</th>
                                        <th>Type</th>
                                        <th>Statut</th>
                                        <th>Accès</th>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {docsModalDocs.map(doc => {
                                        const menuId = `doc-${doc.documentId}`;
                                        return (
                                            <tr key={doc.documentId}>
                                                <td className="td-nom">{doc.titre}</td>
                                                <td>{doc.typeDocumentNom}</td>
                                                <td>{doc.status}</td>
                                                <td>{doc.access === 'PUBLIC' ? 'Public' : 'Privé'}</td>
                                                <td>
                                                    <div className="action-menu-wrapper">
                                                        <button
                                                            ref={(el) => { menuButtonRefs.current[menuId] = el; }}
                                                            onClick={() => toggleMenu(menuId)}
                                                            className="menu-toggle"
                                                            aria-label="Plus d'actions"
                                                            aria-expanded={openMenuId === menuId}
                                                        >
                                                            <i className="fa-solid fa-ellipsis" />
                                                        </button>
                                                        {openMenuId === menuId && menuPos && createPortal(
                                                            <div
                                                                ref={menuRef}
                                                                className="action-menu"
                                                                style={{ position: 'fixed', top: menuPos.top, left: menuPos.left, transform: 'translateX(-100%)' }}
                                                            >
                                                                {onOuvrirDansDossier && (
                                                                    <button onClick={() => { closeMenu(); ouvrirEmplacementDocument(doc.documentId); }} className="action-menu-item">
                                                                        <i className="fa-solid fa-folder-open" /> Ouvrir dans l'emplacement
                                                                    </button>
                                                                )}
                                                                <button onClick={() => { closeMenu(); ouvrirLectureDocument(doc); }} className="action-menu-item">
                                                                    <i className="fa-solid fa-eye" /> Lire le document
                                                                </button>
                                                            </div>,
                                                            document.body
                                                        )}
                                                    </div>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                        {docsModalTotalPages > 1 && (
                            <div className="pagination">
                                <button
                                    className="pagination-btn pagination-nav"
                                    onClick={() => docsModal.node && chargerDocumentsDuNoeud(docsModal.node, docsModalPage - 1)}
                                    disabled={docsModalPage === 1 || docsModalLoading}
                                >‹</button>
                                <span className="pagination-btn pagination-active">{docsModalPage} / {docsModalTotalPages}</span>
                                <button
                                    className="pagination-btn pagination-nav"
                                    onClick={() => docsModal.node && chargerDocumentsDuNoeud(docsModal.node, docsModalPage + 1)}
                                    disabled={docsModalPage === docsModalTotalPages || docsModalLoading}
                                >›</button>
                            </div>
                        )}
                    </>
                )}
            </Modal>

            {/* ── "Lire le document" — notre lecteur PDF maison, voir PdfViewer. ── */}
            {lectureDocModal.open && (
                <Modal
                    isOpen
                    onClose={fermerLectureDocument}
                    title={lectureDocModal.titre ?? 'Document'}
                    size="large"
                >
                    <PdfViewer url={lectureDocModal.url} className="import-preview-iframe" />
                </Modal>
            )}

            {/* ── Capacité maximale — voir PhysicalLocationService.definirCapacite ── */}
            <Modal
                isOpen={capaciteModal.open}
                onClose={() => setCapaciteModal({ open: false, node: null })}
                title={capaciteModal.node ? `Capacité — "${capaciteModal.node.name}"` : 'Capacité'}
            >
                <div className="pl-form">
                    <label>
                        <p style={{ marginBottom: '0.4rem' }}>
                            Nombre maximal de documents — laisser vide pour aucune limite.
                            {capaciteModal.node && ` Actuellement ${capaciteModal.node.nombreDocuments} document(s).`}
                        </p>
                        <input
                            type="number"
                            min={1}
                            placeholder="Illimitée"
                            value={capaciteValeur}
                            onChange={(e) => setCapaciteValeur(e.target.value)}
                        />
                    </label>
                    <div className="pl-form-actions">
                        <button type="button" className="sidebar-btn" disabled={capaciteSaving} onClick={handleDefinirCapacite}>
                            {capaciteSaving ? <><i className="fa-solid fa-spinner fa-spin" /> Enregistrement…</> : 'Enregistrer'}
                        </button>
                    </div>
                </div>
            </Modal>

            {/* ── Contrainte d'acceptation — uniquement si le nœud est vide ── */}
            <Modal
                isOpen={contrainteModal.open}
                onClose={() => setContrainteModal({ open: false, node: null })}
                title={contrainteModal.node ? `Contrainte — "${contrainteModal.node.name}"` : 'Contrainte'}
            >
                <div className="pl-form">
                    {contrainteModal.node && contrainteModal.node.nombreDocuments > 0 ? (
                        <p>Ce nœud contient déjà des documents — impossible de changer sa contrainte d'acceptation.</p>
                    ) : (
                        <>
                            <div className="tbo-constraint-panel" style={{ borderTop: 'none', paddingTop: 0 }}>
                                <label className="tbo-constraint-field" style={{ minWidth: '100%' }}>
                                    <span>Accepte</span>
                                    <select
                                        value={contrainteMode}
                                        onChange={(e) => {
                                            setContrainteMode(e.target.value as LocationModeContrainte);
                                            setContrainteTypeId(null);
                                            setContrainteDossierId(null);
                                        }}
                                    >
                                        <option value="LIBRE" disabled hidden>— Choisir —</option>
                                        <option value="TYPE_UNIQUE" disabled={typesUO.length === 0}>
                                            Type de document{typesUO.length === 0 ? ' (aucun type existant)' : ''}
                                        </option>
                                        <option value="DOSSIER" disabled={!aDesDossiers}>
                                            Dossier{!aDesDossiers ? ' (aucun dossier existant)' : ''}
                                        </option>
                                    </select>
                                </label>
                                {contrainteMode === 'TYPE_UNIQUE' && (
                                    <select
                                        className="tbo-constraint-field"
                                        style={{ minWidth: '100%' }}
                                        value={contrainteTypeId ?? ''}
                                        onChange={(e) => setContrainteTypeId(e.target.value ? Number(e.target.value) : null)}
                                    >
                                        <option value="">— Choisir un type —</option>
                                        {typesUO.map(t => <option key={t.id} value={t.id}>{t.nom}</option>)}
                                    </select>
                                )}
                                {contrainteMode === 'DOSSIER' && (
                                    <DossierTreePicker uoId={uoId} value={contrainteDossierId} onChange={setContrainteDossierId} />
                                )}
                            </div>
                            <div className="pl-form-actions">
                                <button type="button" className="sidebar-btn" disabled={contrainteSaving} onClick={handleDefinirContrainte}>
                                    {contrainteSaving ? <><i className="fa-solid fa-spinner fa-spin" /> Enregistrement…</> : 'Enregistrer'}
                                </button>
                            </div>
                        </>
                    )}
                </div>
            </Modal>
        </div>
    );
}

function PlNode({
    node, depth, estGestionnaire, busyId, draggedId, dragOverId,
    expanded, onToggleExpand, filterActive, visibleIds, matchIds,
    onAddChild, onEdit, onToggleType, onToggleStatus, onDelete,
    onViewDocuments, onEditCapacite, onEditContrainte,
    onDragStart, onDragEnd, onDragOver, onDragLeave, onDrop,
    openMenuId, menuPos, menuRef, menuButtonRefs, onToggleMenu, onCloseMenu,
}: {
    node: PhysicalLocationNodeDto;
    depth: number;
    /** true (EDITOR) : toutes les actions — créer/modifier/convertir/activer-désactiver/
     *  supprimer/déplacer. false (ADMIN/ADMIN_UO) : aucune, lecture seule — voir Javadoc
     *  de PhysicalLocationsPanelProps.mode. */
    estGestionnaire: boolean;
    busyId: string | null;
    draggedId: string | null;
    dragOverId: string | 'ROOT' | null;
    expanded: Set<string>;
    onToggleExpand: (id: string) => void;
    filterActive: boolean;
    visibleIds: Set<string> | null;
    matchIds: Set<string>;
    onAddChild: (parentId: string, parentLabel: string) => void;
    onEdit: (node: PhysicalLocationNodeDto) => void;
    onToggleType: (node: PhysicalLocationNodeDto) => void;
    onToggleStatus: (node: PhysicalLocationNodeDto) => void;
    onDelete: (node: PhysicalLocationNodeDto) => void;
    onViewDocuments: (node: PhysicalLocationNodeDto) => void;
    onEditCapacite: (node: PhysicalLocationNodeDto) => void;
    onEditContrainte: (node: PhysicalLocationNodeDto) => void;
    onDragStart: (e: React.DragEvent, id: string) => void;
    onDragEnd: () => void;
    onDragOver: (e: React.DragEvent, target: PhysicalLocationNodeDto | null) => void;
    onDragLeave: (key: string | 'ROOT') => void;
    onDrop: (e: React.DragEvent, target: PhysicalLocationNodeDto | null) => void;
    openMenuId: string | null;
    menuPos: { top: number; left: number } | null;
    menuRef: React.RefObject<HTMLDivElement | null>;
    menuButtonRefs: React.RefObject<Record<string, HTMLButtonElement | null>>;
    onToggleMenu: (id: string) => void;
    onCloseMenu: () => void;
}) {
    const isBusy = busyId === node.id;
    const isInactive = node.status === 'INACTIVE';
    const isBeingDragged = draggedId === node.id;
    const isDropTarget = dragOverId === node.id;
    const isDraggable = !isBusy && estGestionnaire;
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
                {node.storagePoint && (
                    <span
                        className={`pl-occupation-tag ${node.capaciteMax != null && node.nombreDocuments >= node.capaciteMax ? 'plein' : ''}`}
                        title={node.modeContrainte === 'TYPE_UNIQUE' ? `Type accepté : ${node.typeDocumentAccepteNom}`
                            : node.modeContrainte === 'DOSSIER' ? `Dossier accepté : ${node.dossierNom}` : undefined}
                    >
                        {node.nombreDocuments}{node.capaciteMax != null ? `/${node.capaciteMax}` : ''}
                        {node.modeContrainte !== 'LIBRE' && (
                            <i className={`fa-solid ${node.modeContrainte === 'TYPE_UNIQUE' ? 'fa-tag' : 'fa-folder'}`} style={{ marginLeft: '0.3rem' }} />
                        )}
                    </span>
                )}
                <span className="pl-name">{node.name}</span>
                {isInactive && <span className="pl-status-tag">Inactif</span>}

                <div className="pl-actions">
                    {/* "Voir les documents" — lecture seule, disponible dans les DEUX
                        modes (même un ADMIN/ADMIN_UO peut consulter). */}
                    {node.storagePoint && (
                        <button title="Voir les documents" className="pl-actions-standalone"
                            onClick={() => onViewDocuments(node)}>
                            <i className="fa-solid fa-eye" />
                        </button>
                    )}

                    {/* Masqués sur écran réduit (voir PhysicalLocationsPanel.css,
                        .pl-actions-standalone) — repris à l'identique (mêmes icônes,
                        mêmes libellés, mêmes conditions) dans le menu "..." juste
                        en dessous plutôt que disparaître. Tout regroupé derrière
                        estGestionnaire : ADMIN/ADMIN_UO (mode="lecture") ne voient
                        plus AUCUNE de ces actions, même "Ajouter un enfant". */}
                    {estGestionnaire && (
                        <>
                            {!node.storagePoint && !isInactive && (
                                <button title="Ajouter un enfant" className="pl-actions-standalone"
                                    onClick={() => onAddChild(node.id, node.name)} disabled={isBusy}>
                                    <i className="fa-solid fa-plus" />
                                </button>
                            )}
                            {node.storagePoint && (
                                <>
                                    <button title="Modifier la capacité" className="pl-actions-standalone"
                                        onClick={() => onEditCapacite(node)} disabled={isBusy}>
                                        <i className="fa-solid fa-gauge-high" />
                                    </button>
                                    <button
                                        title={node.nombreDocuments > 0
                                            ? "Modifier la contrainte — impossible, nœud non vide"
                                            : "Modifier la contrainte d'acceptation"}
                                        className="pl-actions-standalone"
                                        onClick={() => onEditContrainte(node)} disabled={isBusy || node.nombreDocuments > 0}>
                                        <i className="fa-solid fa-filter" />
                                    </button>
                                </>
                            )}
                            <button title="Modifier" className="pl-actions-standalone"
                                onClick={() => onEdit(node)} disabled={isBusy}>
                                <i className="fa-solid fa-pen" />
                            </button>
                            <button title={node.storagePoint ? 'Convertir en chemin' : 'Convertir en stockage'}
                                className="pl-actions-standalone"
                                onClick={() => onToggleType(node)} disabled={isBusy}>
                                <i className="fa-solid fa-shuffle" />
                            </button>
                            <button title={isInactive ? 'Réactiver' : 'Désactiver'}
                                className="pl-actions-standalone"
                                onClick={() => onToggleStatus(node)} disabled={isBusy}>
                                <i className={`fa-solid ${isInactive ? 'fa-toggle-off' : 'fa-toggle-on'}`} />
                            </button>
                            <button
                                title={node.children.length > 0 ? 'Supprimer' : 'Supprimer'}
                                className="pl-delete-btn pl-actions-standalone"
                                onClick={() => onDelete(node)} disabled={isBusy}>
                                <i className="fa-solid fa-trash" />
                            </button>
                        </>
                    )}

                    {/* Menu "..." compact — visible uniquement sous ~1100px, voir
                        PhysicalLocationsPanel.css. Reprend exactement les mêmes
                        actions/icônes/conditions que les boutons autonomes ci-dessus.
                        Affiché même en mode "lecture" SI storagePoint (pour garder
                        "Voir les documents" accessible sur petit écran) — mais sans
                        aucune des entrées de gestion dans ce cas. */}
                    {(estGestionnaire || node.storagePoint) && (
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
                                    {node.storagePoint && (
                                        <button onClick={() => { onCloseMenu(); onViewDocuments(node); }} className="action-menu-item">
                                            <i className="fa-solid fa-eye" /> Voir les documents
                                        </button>
                                    )}
                                    {estGestionnaire && !node.storagePoint && !isInactive && (
                                        <button onClick={() => { onCloseMenu(); onAddChild(node.id, node.name); }} className="action-menu-item">
                                            <i className="fa-solid fa-plus" /> Ajouter un enfant
                                        </button>
                                    )}
                                    {estGestionnaire && node.storagePoint && (
                                        <>
                                            <button onClick={() => { onCloseMenu(); onEditCapacite(node); }} className="action-menu-item">
                                                <i className="fa-solid fa-gauge-high" /> Modifier la capacité
                                            </button>
                                            <button
                                                onClick={() => { onCloseMenu(); onEditContrainte(node); }}
                                                className="action-menu-item"
                                                disabled={node.nombreDocuments > 0}
                                            >
                                                <i className="fa-solid fa-filter" /> Modifier la contrainte
                                            </button>
                                        </>
                                    )}
                                    {estGestionnaire && (
                                        <>
                                            <button onClick={() => { onCloseMenu(); onEdit(node); }} className="action-menu-item">
                                                <i className="fa-solid fa-pen" /> Modifier
                                            </button>
                                            <button onClick={() => { onCloseMenu(); onToggleType(node); }} className="action-menu-item">
                                                <i className="fa-solid fa-shuffle" />{' '}
                                                {node.storagePoint ? 'Convertir en chemin' : 'Convertir en stockage'}
                                            </button>
                                            <button onClick={() => { onCloseMenu(); onToggleStatus(node); }} className="action-menu-item">
                                                <i className={`fa-solid ${isInactive ? 'fa-toggle-off' : 'fa-toggle-on'}`} />{' '}
                                                {isInactive ? 'Réactiver' : 'Désactiver'}
                                            </button>
                                            <button onClick={() => { onCloseMenu(); onDelete(node); }} className="action-menu-item">
                                                <i className="fa-solid fa-trash" />{' '}
                                                {node.children.length > 0 ? 'Supprimer avec sa sous-arborescence' : 'Supprimer'}
                                            </button>
                                        </>
                                    )}
                                </div>,
                                document.body
                            )}
                        </div>
                    )}
                </div>
            </div>
            {hasChildren && isExpanded && (
                <div className="pl-children">
                    {enfantsAffiches.map((c) => (
                        <PlNode
                            key={c.id}
                            node={c}
                            depth={depth + 1}
                            estGestionnaire={estGestionnaire}
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
                            onViewDocuments={onViewDocuments}
                            onEditCapacite={onEditCapacite}
                            onEditContrainte={onEditContrainte}
                            onDragStart={onDragStart}
                            onDragEnd={onDragEnd}
                            onDragOver={onDragOver}
                            onDragLeave={onDragLeave}
                            onDrop={onDrop}
                            openMenuId={openMenuId}
                            menuPos={menuPos}
                            menuRef={menuRef}
                            menuButtonRefs={menuButtonRefs}
                            onToggleMenu={onToggleMenu}
                            onCloseMenu={onCloseMenu}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}

export default PhysicalLocationsPanel;
