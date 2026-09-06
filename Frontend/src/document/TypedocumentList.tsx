// document/TypedocumentList.tsx
import { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { getAllTypeDocuments, getTypeDocumentsByUO, deleteTypeDocument, deleteTypeDocumentList } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import TypeDocumentDetail from './Typedocumentdetail';
import UpdateTypeDocument from './Updatetypedocument';
import Modal from '../Page/Modal';
import { TYPE_DOCUMENT_DRAG_MIME } from '../hooks/dragTypes';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import '../Style/document/Typedocument.css';

interface TypeDocumentListProps {
    refreshTrigger?: number;
    uoId: number | null;
}

type ViewMode = 'list' | 'grid';

interface MenuPosition {
    top: number;
    left: number;
}

function TypeDocumentList({ refreshTrigger, uoId }: TypeDocumentListProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);
    const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
    const [isLoading, setIsLoading] = useState(true);

    // Vue liste (tableau) / grille (cartes) — même bascule que côté éditeur
    // pour les documents (voir document/DocumentsAccessible.tsx), adaptée ici
    // pour des types de document (pas d'aperçu PDF, juste les métadonnées).
    const [viewMode, setViewMode] = useState<ViewMode>('list');

    const [viewingTd, setViewingTd] = useState<TypeDocumentDto | null>(null);
    const [isViewModalOpen, setIsViewModalOpen] = useState(false);

    const [editingTd, setEditingTd] = useState<TypeDocumentDto | null>(null);
    const [isUpdateModalOpen, setIsUpdateModalOpen] = useState(false);

    const [deleteInProgress, setDeleteInProgress] = useState(false);

    // Menu d'actions compact ("..."), affiché à la place des 3 boutons sous
    // 1100px (voir Typedocument.css) — même mécanique que UserTable.tsx.
    const [openMenuId, setOpenMenuId] = useState<number | null>(null);
    const [menuPos, setMenuPos] = useState<MenuPosition | null>(null);
    const menuRef = useRef<HTMLDivElement | null>(null);
    const buttonRefs = useRef<Record<number, HTMLButtonElement | null>>({});

    useEffect(() => { fetchAll(); }, [refreshTrigger, uoId]);

    const fetchAll = async () => {
        setIsLoading(true);
        setSelectedIds(new Set());
        try {
            const data = uoId === null ? await getAllTypeDocuments() : await getTypeDocumentsByUO(uoId);
            setTypeDocuments(data);
        } catch (err: any) {
            notify.error(err.message || "Erreur lors du chargement");
        } finally {
            setIsLoading(false);
        }
    };

    // Type créé/modifié depuis une autre interface pendant qu'on reste sur cet
    // écran → rechargé au retour de focus.
    useRefetchOnFocus(fetchAll);

    const toggleSelect = (id: number) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const executerSuppression = async (cible: TypeDocumentDto | 'selection') => {
        setDeleteInProgress(true);
        try {
            if (cible === 'selection') {
                await deleteTypeDocumentList(Array.from(selectedIds));
                notify.success(`${selectedIds.size} type(s) de document supprimé(s) avec succès`);
            } else if (cible.id) {
                await deleteTypeDocument(cible.id);
                notify.success(`"${cible.nom}" supprimé avec succès`);
            }
            await fetchAll();
        } catch (err: any) {
            notify.error(err.message || "Erreur lors de la suppression");
        } finally {
            setDeleteInProgress(false);
        }
    };

    const handleDeleteRequest = async (td: TypeDocumentDto) => {
        if (!(await confirm(`Supprimer le type "${td.nom}" ? Cette action est irréversible.`))) return;
        await executerSuppression(td);
    };

    const handleBulkDeleteRequest = async () => {
        if (selectedIds.size === 0) return;
        if (!(await confirm(`Supprimer les ${selectedIds.size} types de documents sélectionnés ? Cette action est irréversible.`))) return;
        await executerSuppression('selection');
    };

    const handleEditSuccess = async () => {
        setIsUpdateModalOpen(false);
        setEditingTd(null);
        notify.success("Type de document mis à jour avec succès");
        await fetchAll();
    };

    const handleCloseModals = () => {
        setIsViewModalOpen(false);
        setIsUpdateModalOpen(false);
        setViewingTd(null);
        setEditingTd(null);
    };

    const handleDragStart = (e: React.DragEvent, td: TypeDocumentDto) => {
        const payload = selectedIds.size > 0 && selectedIds.has(td.id!)
            ? typeDocuments.filter(t => selectedIds.has(t.id!))
            : [td];
        e.dataTransfer.setData(TYPE_DOCUMENT_DRAG_MIME, JSON.stringify(payload));
        e.dataTransfer.effectAllowed = 'copy';
    };

    const closeMenu = () => {
        setOpenMenuId(null);
        setMenuPos(null);
    };

    const toggleMenu = (id: number) => {
        if (openMenuId === id) {
            closeMenu();
            return;
        }
        const btn = buttonRefs.current[id];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setMenuPos({
                top: rect.bottom + window.scrollY + 4,
                left: rect.right + window.scrollX, // ancré au bord droit du bouton
            });
        }
        setOpenMenuId(id);
    };

    // Fermeture du menu au clic en dehors (menu OU bouton toggle), et au
    // scroll/resize pour éviter un menu mal positionné — même logique que
    // UserTable.tsx.
    useEffect(() => {
        if (openMenuId === null) return;

        const handleClickOutside = (event: MouseEvent) => {
            const target = event.target as Node;
            const clickedToggle = buttonRefs.current[openMenuId]?.contains(target);
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

    // Menu déroulant "..." — portalé dans <body> pour échapper à
    // overflow:hidden/auto des conteneurs ancêtres (même raison que
    // NotificationBell/UserTable). Partagé entre la vue liste et la vue
    // grille, mais pas avec le même comportement : en LISTE, ces 3 entrées
    // ne servent que de repli compact sous 1100px (le reste du temps, 3
    // boutons autonomes suffisent, voir plus bas) — en GRILLE, une carte n'a
    // JAMAIS de boutons autonomes, ce menu est son SEUL point d'action, donc
    // toujours visible quelle que soit la largeur d'écran. "compact" contrôle
    // laquelle des deux classes CSS s'applique aux entrées.
    const renderMenu = (td: TypeDocumentDto, compact: boolean) => (
        <div className="action-menu-wrapper">
            <button
                ref={(el) => { buttonRefs.current[td.id!] = el; }}
                onClick={() => toggleMenu(td.id!)}
                className="action-button menu-toggle"
                aria-label="Plus d'actions"
                aria-expanded={openMenuId === td.id}
            >
                <i className="fa-solid fa-ellipsis"></i>
            </button>

            {openMenuId === td.id && menuPos && createPortal(
                <div
                    ref={menuRef}
                    className="action-menu"
                    style={{
                        position: 'fixed',
                        top: menuPos.top,
                        left: menuPos.left,
                        transform: 'translateX(-100%)',
                    }}
                >
                    <button
                        onClick={() => { closeMenu(); setViewingTd(td); setIsViewModalOpen(true); }}
                        className={`action-menu-item ${compact ? 'td-menu-item-compact' : ''}`}
                    >
                        Voir
                    </button>
                    <button
                        onClick={() => { closeMenu(); setEditingTd(td); setIsUpdateModalOpen(true); }}
                        className={`action-menu-item ${compact ? 'td-menu-item-compact' : ''}`}
                    >
                        Modifier
                    </button>
                    <button
                        onClick={() => { closeMenu(); handleDeleteRequest(td); }}
                        disabled={deleteInProgress}
                        className={`action-menu-item ${compact ? 'td-menu-item-compact' : ''}`}
                    >
                        Supprimer
                    </button>
                </div>,
                document.body
            )}
        </div>
    );

    return (
        <div className="td-list-wrapper">

            {typeDocuments.length > 0 && (
                <div className="td-list-header">
                    <div className="td-view-toggle" role="group" aria-label="Mode d'affichage">
                        <button
                            type="button"
                            className={`td-view-toggle-btn ${viewMode === 'list' ? 'active' : ''}`}
                            onClick={() => setViewMode('list')}
                            title="Vue liste"
                            aria-label="Afficher en liste"
                        >
                            <i className="fa-solid fa-list" />
                        </button>
                        <button
                            type="button"
                            className={`td-view-toggle-btn ${viewMode === 'grid' ? 'active' : ''}`}
                            onClick={() => setViewMode('grid')}
                            title="Vue grille"
                            aria-label="Afficher en grille"
                        >
                            <i className="fa-solid fa-table-cells-large" />
                        </button>
                    </div>
                </div>
            )}

            {selectedIds.size > 0 && (
                <div className="td-bulk-bar">
                    <span>{selectedIds.size} sélectionné(s)</span>
                    <button className="td-delete-btn" onClick={handleBulkDeleteRequest} disabled={deleteInProgress}>
                        Supprimer la sélection
                    </button>
                    <span className="td-bulk-hint">Glissez la sélection vers une UO pour la dupliquer là-bas</span>
                </div>
            )}

            {isLoading ? (
                <div className="td-loading">Chargement...</div>
            ) : typeDocuments.length === 0 ? (
                <div className="td-empty">
                    <p>Aucun type de document créé.</p>
                    <span>Utilisez le bouton "Créer un type" pour commencer.</span>
                </div>
            ) : viewMode === 'grid' ? (
                <div className="td-grid">
                    {typeDocuments.map(td => (
                        <div
                            key={td.id}
                            draggable
                            onDragStart={(e) => handleDragStart(e, td)}
                            className={`td-folder-card ${selectedIds.has(td.id!) ? 'td-row-selected' : ''}`}
                        >
                            {/* Case à cocher (sélection groupée) — même
                                emplacement que le "+" du modèle éditeur
                                (Mes documents), réutilisé pour un usage
                                différent ici. stopPropagation : la carte
                                elle-même n'a pas d'action au clic (contrairement
                                au dossier éditeur, qui navigue à l'intérieur),
                                mais autant éviter toute ambiguïté future. */}
                            <input
                                type="checkbox"
                                className="td-folder-checkbox"
                                checked={selectedIds.has(td.id!)}
                                onChange={() => toggleSelect(td.id!)}
                                onClick={(e) => e.stopPropagation()}
                            />

                            <div className="td-folder-icon-wrap">
                                <div className="td-folder-tab" />
                                <div className="td-folder-back" />
                                <div className="td-folder-sheet">
                                    <div className="td-folder-doc-line short" />
                                    <div className="td-folder-doc-line" />
                                    <div className="td-folder-doc-line" />
                                </div>
                                <div className="td-folder-glass" />
                                {/* Pastille = nombre de champs de métadonnées
                                    (pas un nombre de documents, il n'y en a
                                    pas ici — c'est un TYPE, pas un dossier de
                                    documents). */}
                                <span className="td-folder-count">
                                    {td.metaData?.length ?? 0}
                                </span>
                            </div>

                            <span className="td-folder-name" title={td.nom}>{td.nom}</span>
                            <span className="td-folder-meta">
                                Rétention : {td.retentionYears ?? 'Indéfinie'} · Grâce : {td.periodGrace ?? '—'} j
                            </span>

                            {/* Seul point d'action de la carte — pas de
                                boutons autonomes en vue grille, contrairement
                                à la vue liste (voir "compact" plus haut). */}
                            {renderMenu(td, false)}
                        </div>
                    ))}
                </div>
            ) : (
                <div className="td-table-container">
                    <table className="td-table">
                        <thead>
                            <tr>
                                <th></th>
                                <th>Nom</th>
                                <th>Rétention (ans)</th>
                                <th className="td-col-grace">Période de grâce (j)</th>
                                <th className="td-col-meta">Métadonnées</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {typeDocuments.map(td => (
                                <tr
                                    key={td.id}
                                    draggable
                                    onDragStart={(e) => handleDragStart(e, td)}
                                    className={selectedIds.has(td.id!) ? 'td-row-selected' : ''}
                                >
                                    <td>
                                        <input
                                            type="checkbox"
                                            checked={selectedIds.has(td.id!)}
                                            onChange={() => toggleSelect(td.id!)}
                                        />
                                    </td>
                                    <td className="td-nom">{td.nom}</td>
                                    <td>{td.retentionYears ?? 'Indéfinie'}</td>
                                    <td className="td-col-grace">{td.periodGrace ?? '—'}</td>
                                    <td className="td-col-meta">
                                        <span className="td-meta-count">
                                            {td.metaData?.length ?? 0} champ{(td.metaData?.length ?? 0) > 1 ? 's' : ''}
                                        </span>
                                    </td>
                                    <td>
                                        <div className="td-actions">
                                            {/* Masqués sous 1100px (td-actions-standalone, voir
                                                Typedocument.css) — repris comme entrées du menu
                                                "..." juste en dessous plutôt que disparaître. */}
                                            <button
                                                className="action-button view td-actions-standalone"
                                                onClick={() => { setViewingTd(td); setIsViewModalOpen(true); }}
                                            >
                                                Voir
                                            </button>
                                            <button
                                                className="action-button edit td-actions-standalone"
                                                onClick={() => { setEditingTd(td); setIsUpdateModalOpen(true); }}
                                            >
                                                Modifier
                                            </button>
                                            <button
                                                className="td-delete-btn td-actions-standalone"
                                                onClick={() => handleDeleteRequest(td)}
                                                disabled={deleteInProgress}
                                            >
                                                Supprimer
                                            </button>
                                            {renderMenu(td, true)}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            <Modal isOpen={isViewModalOpen} onClose={handleCloseModals} title="Détail du type de document">
                {viewingTd && <TypeDocumentDetail td={viewingTd} />}
            </Modal>

            <Modal isOpen={isUpdateModalOpen} onClose={handleCloseModals} title="Modifier le type de document">
                {editingTd && (
                    <UpdateTypeDocument
                        initialData={editingTd}
                        onsuccess={handleEditSuccess}
                    />
                )}
            </Modal>
        </div>
    );
}

export default TypeDocumentList;
