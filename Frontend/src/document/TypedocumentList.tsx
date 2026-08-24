// document/TypedocumentList.tsx
import { useState, useEffect } from 'react';
import { getAllTypeDocuments, getTypeDocumentsByUO, deleteTypeDocument, deleteTypeDocumentList } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import TypeDocumentDetail from './Typedocumentdetail';
import UpdateTypeDocument from './Updatetypedocument';
import Modal from '../Page/Modal';
import Confirme from '../Page/Confirme';
import { TYPE_DOCUMENT_DRAG_MIME } from '../hooks/dragTypes';
import '../Style/document/Typedocument.css';

interface TypeDocumentListProps {
    refreshTrigger?: number;
    uoId: number | null;
}

function TypeDocumentList({ refreshTrigger, uoId }: TypeDocumentListProps) {
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);
    const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const [viewingTd, setViewingTd] = useState<TypeDocumentDto | null>(null);
    const [isViewModalOpen, setIsViewModalOpen] = useState(false);

    const [editingTd, setEditingTd] = useState<TypeDocumentDto | null>(null);
    const [isUpdateModalOpen, setIsUpdateModalOpen] = useState(false);

    const [confirmOpen, setConfirmOpen] = useState(false);
    const [tdToDelete, setTdToDelete] = useState<TypeDocumentDto | 'selection' | null>(null);
    const [deleteInProgress, setDeleteInProgress] = useState(false);

    useEffect(() => { fetchAll(); }, [refreshTrigger, uoId]);

    useEffect(() => {
        if (error || success) {
            const t = setTimeout(() => { setError(''); setSuccess(''); }, 3000);
            return () => clearTimeout(t);
        }
    }, [error, success]);

    const fetchAll = async () => {
        setIsLoading(true);
        setError('');
        setSelectedIds(new Set());
        try {
            const data = uoId === null ? await getAllTypeDocuments() : await getTypeDocumentsByUO(uoId);
            setTypeDocuments(data);
        } catch (err: any) {
            setError(err.message || "Erreur lors du chargement");
        } finally {
            setIsLoading(false);
        }
    };

    const toggleSelect = (id: number) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const handleDeleteRequest = (td: TypeDocumentDto) => {
        setTdToDelete(td);
        setConfirmOpen(true);
    };

    const handleBulkDeleteRequest = () => {
        if (selectedIds.size === 0) return;
        setTdToDelete('selection');
        setConfirmOpen(true);
    };

    const handleDeleteConfirm = async () => {
        setDeleteInProgress(true);
        try {
            if (tdToDelete === 'selection') {
                await deleteTypeDocumentList(Array.from(selectedIds));
                setSuccess(`${selectedIds.size} type(s) de document supprimé(s) avec succès`);
            } else if (tdToDelete?.id) {
                await deleteTypeDocument(tdToDelete.id);
                setSuccess(`"${tdToDelete.nom}" supprimé avec succès`);
            }
            await fetchAll();
        } catch (err: any) {
            setError(err.message || "Erreur lors de la suppression");
        } finally {
            setDeleteInProgress(false);
            setConfirmOpen(false);
            setTdToDelete(null);
        }
    };

    const handleEditSuccess = async () => {
        setIsUpdateModalOpen(false);
        setEditingTd(null);
        setSuccess("Type de document mis à jour avec succès");
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

    // TypedocumentList.tsx — confirmMessage simplifié, narrowing déjà acquis
    const confirmMessage = tdToDelete === 'selection'
        ? `Supprimer les ${selectedIds.size} types de documents sélectionnés ? Cette action est irréversible.`
        : `Supprimer le type "${tdToDelete ? tdToDelete.nom : ''}" ? Cette action est irréversible.`;

    return (
        <div className="td-list-wrapper">

            {error && <div className="td-alert td-alert-error">{error}</div>}
            {success && <div className="td-alert td-alert-success">{success}</div>}

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
            ) : (
                <div className="td-table-container">
                    <table className="td-table">
                        <thead>
                            <tr>
                                <th></th>
                                <th>Nom</th>
                                <th>Rétention (ans)</th>
                                <th>Période de grâce (j)</th>
                                <th>Métadonnées</th>
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
                                    <td>{td.periodGrace ?? '—'}</td>
                                    <td>
                                        <span className="td-meta-count">
                                            {td.metaData?.length ?? 0} champ{(td.metaData?.length ?? 0) > 1 ? 's' : ''}
                                        </span>
                                    </td>
                                    <td>
                                        <div className="td-actions">
                                            <button
                                                className="action-button view"
                                                onClick={() => { setViewingTd(td); setIsViewModalOpen(true); }}
                                            >
                                                Voir
                                            </button>
                                            <button
                                                className="action-button edit"
                                                onClick={() => { setEditingTd(td); setIsUpdateModalOpen(true); }}
                                            >
                                                Modifier
                                            </button>
                                            <button
                                                className="td-delete-btn"
                                                onClick={() => handleDeleteRequest(td)}
                                                disabled={deleteInProgress}
                                            >
                                                Supprimer
                                            </button>
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

            <Confirme
                isOpen={confirmOpen}
                message={confirmMessage}
                onConfirm={handleDeleteConfirm}
                onCancel={() => { setConfirmOpen(false); setTdToDelete(null); }}
            />
        </div>
    );
}

export default TypeDocumentList;