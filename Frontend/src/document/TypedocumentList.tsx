// document/TypedocumentList.tsx
import { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { getTypeDocumentsByUOEditor, deleteTypeDocument, deleteTypeDocumentList } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import TypeDocumentDetail from './Typedocumentdetail';
import UpdateTypeDocument from './Updatetypedocument';
import Modal from '../Page/Modal';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import '../Style/document/Typedocument.css';
// .dossier-context-menu(-overlay) — même menu contextuel (clic droit) que
// organisation/DossiersPanel.tsx, réutilisé tel quel plutôt que dupliqué
// (voir contextMenuJsx plus bas).
import '../Style/Editor/Editor.css';

interface TypeDocumentListProps {
    refreshTrigger?: number;
    /** Toujours la propre UO de l'éditeur — ce composant est désormais
     *  exclusivement utilisé dans l'espace éditeur (gestion des types de
     *  documents réservée aux EDITOR, voir DocumentController backend). */
    uoId: number;
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
    // Sélection — Cmd/Ctrl+clic bascule, clic droit ouvre un menu contextuel
    // ("Supprimer"), même mécanique que organisation/DossiersPanel.tsx
    // (handleClickDossierCard/handleContextMenuDossier) : pas de case à
    // cocher, pas de "mode" à activer/désactiver, pas de glisser-déposer.
    const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
    const [isLoading, setIsLoading] = useState(true);
    const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);

    // Vue liste (tableau) / grille (cartes) — même bascule que côté éditeur
    // pour les documents (voir document/DocumentsAccessible.tsx), adaptée ici
    // pour des types de document (pas d'aperçu PDF, juste les métadonnées).
    const [viewMode, setViewMode] = useState<ViewMode>('list');

    // Filtre — entièrement local (pas de requête réseau : la liste des
    // types est déjà chargée en entier, filtrer côté client suffit et
    // reste instantané). Rétention : comparaison EXACTE sur le nombre
    // d'années (pas une vraie "date" au sens calendaire — c'est ainsi que
    // le champ est stocké, voir TypeDocumentDto.retentionYears).
    const [filterNom, setFilterNom] = useState('');
    const [filterRetention, setFilterRetention] = useState('');

    const typeDocumentsFiltres = typeDocuments.filter(td => {
        const nomOk = td.nom.toLowerCase().includes(filterNom.trim().toLowerCase());
        const retentionOk = filterRetention.trim() === ''
            || String(td.retentionYears ?? '') === filterRetention.trim();
        return nomOk && retentionOk;
    });

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
            const data = await getTypeDocumentsByUOEditor(uoId);
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

    /** Clic normal = ouvrir (double-clic, voir plus bas) ; Cmd/Ctrl+clic =
     *  bascule la sélection — même logique que
     *  DossiersPanel.handleClickDossierCard. */
    const handleClickCard = (e: React.MouseEvent, id: number) => {
        if (e.metaKey || e.ctrlKey) {
            e.preventDefault();
            toggleSelect(id);
            return;
        }
        if (selectedIds.size > 0) {
            setSelectedIds(new Set());
        }
    };

    /** Clic droit — sélectionne SEULEMENT la carte cliquée si elle n'était pas
     *  déjà dans la sélection courante, puis ouvre le menu contextuel
     *  ("Supprimer") à la position du curseur — même logique que
     *  DossiersPanel.handleContextMenuDossier. */
    const handleContextMenuCard = (e: React.MouseEvent, id: number) => {
        e.preventDefault();
        if (!selectedIds.has(id)) {
            setSelectedIds(new Set([id]));
        }
        setContextMenu({ x: e.clientX, y: e.clientY });
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

    /** Bouton "Supprimer" du menu contextuel — voir contextMenuJsx. */
    const handleContextMenuSupprimer = async () => {
        setContextMenu(null);
        await handleBulkDeleteRequest();
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
        <div
            className={`action-menu-wrapper ${compact ? 'td-menu-compact-only' : ''}`}
            onDoubleClick={(e) => e.stopPropagation()}
        >
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

    // Menu contextuel (clic droit sur une carte/ligne) — voir
    // handleContextMenuCard/handleContextMenuSupprimer. Overlay plein écran
    // transparent pour fermer au clic/clic-droit ailleurs, comme un menu
    // contextuel natif. Classes réutilisées telles quelles depuis
    // DossiersPanel (voir import Editor.css plus haut).
    const contextMenuJsx = contextMenu && (
        <div
            className="dossier-context-menu-overlay"
            onClick={() => setContextMenu(null)}
            onContextMenu={e => { e.preventDefault(); setContextMenu(null); }}
        >
            <div
                className="dossier-context-menu"
                style={{ top: contextMenu.y, left: contextMenu.x }}
                onClick={e => e.stopPropagation()}
            >
                <button type="button" onClick={handleContextMenuSupprimer} disabled={deleteInProgress}>
                    <i className="fa-solid fa-trash" />
                    Supprimer{selectedIds.size > 1 ? ` (${selectedIds.size})` : ''}
                </button>
            </div>
        </div>
    );

    return (
        <div className="td-list-wrapper">

            {typeDocuments.length > 0 && (
                <div className="td-list-header">
                    <div className="td-filter-bar">
                        <input
                            type="text"
                            className="td-filter-input"
                            placeholder="Nom"
                            aria-label="Filtrer par nom"
                            value={filterNom}
                            onChange={e => setFilterNom(e.target.value)}
                        />
                        <input
                            type="number"
                            className="td-filter-input td-filter-input-narrow"
                            placeholder="Rétention (ans)"
                            aria-label="Filtrer par rétention (années)"
                            min={0}
                            value={filterRetention}
                            onChange={e => setFilterRetention(e.target.value)}
                        />
                        {(filterNom || filterRetention) && (
                            <button
                                type="button"
                                className="td-filter-reset-btn"
                                title="Réinitialiser les filtres"
                                aria-label="Réinitialiser les filtres"
                                onClick={() => { setFilterNom(''); setFilterRetention(''); }}
                            >
                                <i className="fa-solid fa-rotate-left" />
                            </button>
                        )}
                    </div>

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

            {isLoading ? (
                <div className="td-loading">Chargement...</div>
            ) : typeDocuments.length === 0 ? (
                <div className="td-empty">
                    <p>Aucun type de document créé.</p>
                    <span>Utilisez le bouton "Créer un type" pour commencer.</span>
                </div>
            ) : typeDocumentsFiltres.length === 0 ? (
                <div className="td-empty">
                    <p>Aucun type ne correspond à ce filtre.</p>
                    <span>Essayez un autre nom ou une autre rétention.</span>
                </div>
            ) : viewMode === 'grid' ? (
                <div className="td-grid">
                    {typeDocumentsFiltres.map(td => (
                        <div
                            key={td.id}
                            onDoubleClick={() => { setViewingTd(td); setIsViewModalOpen(true); }}
                            onClick={e => handleClickCard(e, td.id!)}
                            onContextMenu={e => handleContextMenuCard(e, td.id!)}
                            className={`td-folder-card ${selectedIds.has(td.id!) ? 'td-row-selected' : ''}`}
                        >
                            <div className="td-folder-icon-wrap">
                                <div className="td-folder-back" />
                                <div className="td-folder-sheet">
                                    <div className="td-folder-doc-line short" />
                                    <div className="td-folder-doc-line" />
                                    <div className="td-folder-doc-line" />
                                </div>
                                {/* Pas de pastille de compteur ici (contrairement
                                    au dossier éditeur, "Mes documents") : elle y
                                    représente un nombre de documents, alors qu'ici
                                    ça aurait été le nombre de champs de
                                    métadonnées — même position visuelle, sens
                                    différent, ambigu pour qui les voit tous les
                                    deux. Retiré à la demande. */}
                            </div>

                            <span className="td-folder-name" title={td.nom}>{td.nom}</span>
                            <span className="td-folder-meta">
                                Rétention : {td.retentionYears ?? 'Indéfinie'}
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
                    <table className="td-table td-types-table">
                        <thead>
                            <tr>
                                <th>Nom</th>
                                <th>Rétention (ans)</th>
                                <th className="td-col-meta">Métadonnées</th>
                                <th className="td-col-actions">Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {typeDocumentsFiltres.map(td => (
                                <tr
                                    key={td.id}
                                    onDoubleClick={() => { setViewingTd(td); setIsViewModalOpen(true); }}
                                    onClick={e => handleClickCard(e, td.id!)}
                                    onContextMenu={e => handleContextMenuCard(e, td.id!)}
                                    className={selectedIds.has(td.id!) ? 'td-row-selected' : ''}
                                >
                                    <td className="td-nom">{td.nom}</td>
                                    <td>{td.retentionYears ?? 'Indéfinie'}</td>
                                    <td className="td-col-meta">
                                        <span className="td-meta-count">
                                            {td.metaData?.length ?? 0} champ{(td.metaData?.length ?? 0) > 1 ? 's' : ''}
                                        </span>
                                    </td>
                                    <td className="td-col-actions" onDoubleClick={(e) => e.stopPropagation()}>
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
                                                className="td-delete-btn td-delete-btn-icon td-actions-standalone"
                                                onClick={() => handleDeleteRequest(td)}
                                                disabled={deleteInProgress}
                                                title="Supprimer"
                                                aria-label="Supprimer"
                                            >
                                                <i className="fa-solid fa-trash" />
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

            {contextMenuJsx}
        </div>
    );
}

export default TypeDocumentList;
