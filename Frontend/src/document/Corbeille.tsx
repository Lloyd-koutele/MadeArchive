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
import { renderPdfFirstPageThumbnail } from '../services/document/PdfThumbnail';
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

    // ── Mode d'affichage : liste (tableau) ou grille (aperçus PDF) — même
    // bascule que "Documents accessibles"/"Mes documents". ─────────────────
    type ViewMode = 'list' | 'grid';
    const [viewMode, setViewMode] = useState<ViewMode>('list');

    // Aperçus PDF pour la vue grille — même logique que MesDocumentsEditor :
    // chargés à la demande, uniquement pour les documents de la page
    // courante et uniquement en vue grille.
    const [previews, setPreviews] = useState<Record<string, string>>({});
    const [previewsEnCours, setPreviewsEnCours] = useState<Set<string>>(new Set());
    const [previewsEchec, setPreviewsEchec] = useState<Set<string>>(new Set());

    const charger = useCallback(async (p: number) => {
        setIsLoading(true);
        try {
            const result = await getDocumentsCorbeille(p, 10);
            setDocuments(result.content);
            setTotal(result.totalElements);
            setTotalPages(result.totalPages);
            setPage(p);

            // Nouvelle page → les aperçus déjà générés ne correspondent plus
            // forcément aux documents affichés.
            setPreviews({});
            setPreviewsEchec(new Set());
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur chargement de la corbeille');
        } finally {
            setIsLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => { charger(1); }, [charger]);

    // ── Aperçus PDF pour la vue grille ──────────────────────────────────────
    useEffect(() => {
        if (viewMode !== 'grid' || documents.length === 0) return;
        let annule = false;

        const idsACharger = documents
            .map(d => d.documentId)
            .filter(id => !previews[id] && !previewsEnCours.has(id) && !previewsEchec.has(id));
        if (idsACharger.length === 0) return;

        setPreviewsEnCours(prev => new Set([...prev, ...idsACharger]));

        idsACharger.forEach(async (id) => {
            let blobUrl: string | null = null;
            try {
                blobUrl = await streamPdfAAsBlob(id);
                const thumbnail = await renderPdfFirstPageThumbnail(blobUrl);
                if (!annule) setPreviews(prev => ({ ...prev, [id]: thumbnail }));
            } catch {
                if (!annule) setPreviewsEchec(prev => new Set([...prev, id]));
            } finally {
                if (blobUrl) URL.revokeObjectURL(blobUrl);
                if (!annule) {
                    setPreviewsEnCours(prev => {
                        const next = new Set(prev);
                        next.delete(id);
                        return next;
                    });
                }
            }
        });

        return () => { annule = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [viewMode, documents]);
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
                <div className="docs-header-actions">
                    <div className="docs-view-toggle" role="group" aria-label="Mode d'affichage">
                        <button
                            type="button"
                            className={`view-toggle-btn ${viewMode === 'list' ? 'active' : ''}`}
                            onClick={() => setViewMode('list')}
                            title="Vue liste"
                            aria-label="Afficher en liste"
                        >
                            <i className="fa-solid fa-list" />
                        </button>
                        <button
                            type="button"
                            className={`view-toggle-btn ${viewMode === 'grid' ? 'active' : ''}`}
                            onClick={() => setViewMode('grid')}
                            title="Vue grille"
                            aria-label="Afficher en grille"
                        >
                            <i className="fa-solid fa-table-cells-large" />
                        </button>
                    </div>
                </div>
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
            ) : viewMode === 'grid' ? (
                <>
                    <div className="documents-grid">
                        {documents.map(doc => (
                            <div key={doc.documentId} className="doc-grid-card">
                                <div
                                    className="doc-grid-preview"
                                    onClick={() => openPdfViewer(doc)}
                                    role="button"
                                    tabIndex={0}
                                    onKeyDown={e => e.key === 'Enter' && openPdfViewer(doc)}
                                    aria-label={`Lire ${doc.titre}`}
                                >
                                    {previews[doc.documentId] ? (
                                        <img
                                            src={previews[doc.documentId]}
                                            alt=""
                                            className="doc-grid-preview-frame"
                                        />
                                    ) : previewsEchec.has(doc.documentId) ? (
                                        <div className="doc-grid-preview-loading doc-grid-preview-echec">
                                            <i className="fa-solid fa-file-pdf" />
                                        </div>
                                    ) : (
                                        <div className="doc-grid-preview-loading">
                                            <i className="fa-solid fa-spinner fa-spin" />
                                        </div>
                                    )}
                                    <span className="doc-grid-tag">PDF</span>
                                    <div className="doc-grid-preview-hint">
                                        <i className="fa-solid fa-eye" /> Lire
                                    </div>
                                </div>

                                <div className="doc-grid-body">
                                    <p className="doc-grid-title" title={doc.titre}>
                                        {doc.titre}
                                        <VersionBadge label={doc.versionLabel} />
                                    </p>
                                    <p className="doc-grid-type">
                                        {doc.typeDocumentNom} · Suppression : {formatDate(doc.suppressionPrevueLe)}
                                    </p>

                                    <div className="td-actions doc-grid-actions">
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
                                </div>
                            </div>
                        ))}
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
            ) : (
                <>
                    <div className="td-table-container">
                        <table className="td-table">
                            <thead>
                                <tr>
                                    <th>Titre</th>
                                    <th>Type</th>
                                    {/* Masquée sur écran réduit (voir Editor.css) — reste
                                        consultable en ouvrant le document. */}
                                    <th className="corbeille-col-acces">Accès</th>
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
                                        <td className="corbeille-col-acces">
                                            <span className={`doc-access-tag ${doc.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                                                {doc.access === 'PUBLIC' ? 'Public' : 'Privé'}
                                            </span>
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
