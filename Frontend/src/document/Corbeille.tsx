import { useState, useEffect, useCallback } from 'react';
import {
    getDocumentsCorbeille,
    restaurerDocumentDepuisCorbeille,
    streamPdfAAsBlob,
} from '../services/document/DocumentService';
import type { DocumentListItemDto } from '../services/document/DocumentService';
import { hasRole } from '../auth/authService';
import Modal from '../Page/Modal';
import VersionBadge from './VersionBadge';
import { useNotify } from '../notifications/NotificationProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import '../Style/document/Filtre.css';
import '../Style/Editor/Editor.css';

function formatDate(iso: string | null | undefined): string {
    if (!iso) return '—';
    try { return new Date(iso).toLocaleDateString('fr-FR'); }
    catch { return iso; }
}

/**
 * Corbeille — documents envoyés à la corbeille (n'importe quel document,
 * plus seulement un corrompu, voir DocumentService.envoyerCorbeille côté
 * serveur), en attente de purge définitive dans 3 jours.
 *
 * Visibilité et droits déjà tranchés côté serveur (DocumentAccessService.
 * getDocumentsCorbeille) : ADMIN voit tout, ADMIN_UO voit son UO en lecture
 * seule, ÉDITEUR voit ce à quoi il a normalement accès et peut restaurer.
 * Le bouton "Restaurer" n'est affiché ici que pour un éditeur — un
 * admin/admin_uo sans le rôle éditeur se le verrait de toute façon refuser
 * par le serveur (403), mais autant ne pas l'afficher pour ce qu'il ne peut
 * pas faire.
 */
function Corbeille() {
    const notify = useNotify();
    const peutRestaurer = hasRole('EDITOR');

    const [documents, setDocuments] = useState<DocumentListItemDto[]>([]);
    const [total, setTotal]         = useState(0);
    const [page, setPage]           = useState(1);
    const [totalPages, setTotalPages] = useState(1);
    const [isLoading, setIsLoading] = useState(false);

    const [restaurationEnCoursId, setRestaurationEnCoursId] = useState<string | null>(null);

    const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
    const [pdfLoading, setPdfLoading] = useState(false);
    const [isPdfOpen, setIsPdfOpen]   = useState(false);

    const charger = useCallback(async (p: number) => {
        setIsLoading(true);
        try {
            const result = await getDocumentsCorbeille(p, 10);
            setDocuments(result.content);
            setTotal(result.totalElements);
            setTotalPages(result.totalPages);
            setPage(p);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur chargement de la corbeille');
        } finally {
            setIsLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => { charger(1); }, [charger]);
    useRefetchOnFocus(useCallback(() => charger(page), [charger, page]));

    const handleRestaurer = async (doc: DocumentListItemDto) => {
        setRestaurationEnCoursId(doc.documentId);
        try {
            await restaurerDocumentDepuisCorbeille(doc.documentId);
            notify.success(`"${doc.titre}" restauré`);
            charger(page);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la restauration');
        } finally {
            setRestaurationEnCoursId(null);
        }
    };

    const openPdfViewer = async (doc: DocumentListItemDto) => {
        setPdfLoading(true);
        setIsPdfOpen(true);
        setPdfBlobUrl(null);
        try {
            const url = await streamPdfAAsBlob(doc.documentId);
            setPdfBlobUrl(url);
        } catch {
            setIsPdfOpen(false);
        } finally {
            setPdfLoading(false);
        }
    };

    const closePdfViewer = () => {
        setIsPdfOpen(false);
        if (pdfBlobUrl) { URL.revokeObjectURL(pdfBlobUrl); setPdfBlobUrl(null); }
    };

    return (
        <div className="mes-docs-wrapper">
            <div className="mes-docs-header">
                <h2 className="mes-docs-title">Corbeille</h2>
            </div>

            <p className="users-count" style={{ marginBottom: '0.5rem' }}>
                <i className="fa-solid fa-circle-info" style={{ marginRight: '0.4rem', color: 'var(--text-light)' }} />
                Les documents ci-dessous seront supprimés définitivement à la date indiquée
                {peutRestaurer ? ', sauf restauration avant cette échéance.' : '.'}
                {!peutRestaurer && ' Vue en lecture seule.'}
            </p>

            {!isLoading && (
                <p className="users-count">
                    <span>{total}</span> document{total > 1 ? 's' : ''} dans la corbeille
                </p>
            )}

            {isLoading && documents.length === 0 ? (
                <div className="td-loading">
                    <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                </div>
            ) : documents.length === 0 ? (
                <div className="td-empty">
                    <i className="fa-solid fa-trash-can" style={{ fontSize: '2.5rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }} />
                    <p>La corbeille est vide.</p>
                </div>
            ) : (
                <>
                    <div className="td-table-container">
                        <table className="td-table">
                            <thead>
                                <tr>
                                    <th>Titre</th>
                                    <th>Type</th>
                                    <th>Accès</th>
                                    <th>Statut d'origine</th>
                                    <th>Suppression définitive</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {documents.map(doc => (
                                    <tr key={doc.documentId}>
                                        <td className="td-nom">
                                            {doc.titre}
                                            <VersionBadge label={doc.versionLabel} />
                                        </td>
                                        <td>{doc.typeDocumentNom}</td>
                                        <td>
                                            <span className={`doc-access-tag ${doc.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                                                {doc.access === 'PUBLIC' ? 'Public' : 'Privé'}
                                            </span>
                                        </td>
                                        <td>
                                            {doc.statutAvantCorbeille === 'CORRUPTED' ? (
                                                <span className="status-tag corrupted">
                                                    <i className="fa-solid fa-triangle-exclamation" /> Corrompu
                                                </span>
                                            ) : '—'}
                                        </td>
                                        <td>{formatDate(doc.suppressionPrevueLe)}</td>
                                        <td>
                                            <div className="td-actions">
                                                <button
                                                    className="action-button view"
                                                    onClick={() => openPdfViewer(doc)}
                                                    title="Lire le document"
                                                >
                                                    <i className="fa-solid fa-eye" />
                                                </button>
                                                {peutRestaurer && (
                                                    <button
                                                        className="action-button edit"
                                                        onClick={() => handleRestaurer(doc)}
                                                        disabled={restaurationEnCoursId === doc.documentId}
                                                        title="Restaurer"
                                                    >
                                                        {restaurationEnCoursId === doc.documentId
                                                            ? <i className="fa-solid fa-spinner fa-spin" />
                                                            : <i className="fa-solid fa-clock-rotate-left" />
                                                        }
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {totalPages > 1 && (
                        <div className="pagination">
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => charger(page - 1)}
                                disabled={page === 1 || isLoading}
                            >‹</button>
                            <span className="pagination-btn pagination-active">
                                {page} / {totalPages}
                            </span>
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => charger(page + 1)}
                                disabled={page === totalPages || isLoading}
                            >›</button>
                        </div>
                    )}
                </>
            )}

            <Modal isOpen={isPdfOpen} onClose={closePdfViewer} title="Lecture du document">
                <div className="pdf-viewer-wrapper">
                    {pdfLoading ? (
                        <div className="pdf-viewer-loading">
                            <i className="fa-solid fa-spinner fa-spin" />
                            <span>Chargement du document...</span>
                        </div>
                    ) : pdfBlobUrl ? (
                        <iframe src={pdfBlobUrl} className="pdf-viewer-iframe" title="Lecteur PDF" />
                    ) : (
                        <div className="td-empty"><p>Impossible de charger le document.</p></div>
                    )}
                </div>
            </Modal>
        </div>
    );
}

export default Corbeille;
