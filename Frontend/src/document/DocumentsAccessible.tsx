import { useState, useEffect, useCallback } from 'react';
import {
    getDocumentsAccessibles,
    streamPdfAAsBlob,
    getDocumentDetail,
    downloadPdfA,
    planifierSuppressionDocument
} from '../services/document/DocumentService';
import { genererAttestation } from '../services/document/AttestationService';
import { modifierEmplacementPhysique } from '../services/document/DocumentService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import type { DocumentListItemDto, DocumentDetailDto } from '../services/document/DocumentService';
import { getAllTypeDocuments } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import Modal from '../Page/Modal';
import VersionBadge from './VersionBadge';
import GestionGroupe from './GestionGroupe';

interface DocumentsAccessiblesProps {
    /** null/absent = pas de restriction supplémentaire, tout le périmètre autorisé (voir DocumentService.getUoIdsVisiblesPourLecture côté serveur). */
    uoId?: number | null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Constantes
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_LABELS: Record<string, string> = {
    ACTIVE:         'Actif',
    PENDING:        'En attente',
    ACTIVE_WARNING: 'Avertissement',
    CORRUPTED:      'Corrompu',
    DELETED:        'Supprimé',
};

const STATUS_CLASS: Record<string, string> = {
    ACTIVE:         'active',
    PENDING:        'pending',
    ACTIVE_WARNING: 'warning',
    CORRUPTED:      'corrupted',
    DELETED:        'deleted',
};

function formatDate(iso: string | null): string {
    if (!iso) return '—';
    try { return new Date(iso).toLocaleDateString('fr-FR'); }
    catch { return iso; }
}

// ─────────────────────────────────────────────────────────────────────────────
// Types filtres
// ─────────────────────────────────────────────────────────────────────────────

interface Filtres {
    titre:          string;
    typeDocumentId: string;
    access:         string;
    dateDebut:      string;
    dateFin:        string;
    statut:         string;
}

const FILTRES_VIDES: Filtres = {
    titre:          '',
    typeDocumentId: '',
    access:         '',
    dateDebut:      '',
    dateFin:        '',
    statut:         '',
};

// ─────────────────────────────────────────────────────────────────────────────
// Composant principal
// ─────────────────────────────────────────────────────────────────────────────

function DocumentsAccessibles({ uoId = null }: DocumentsAccessiblesProps) {
    // ── Données ───────────────────────────────────────────────────────────
    const [documents, setDocuments]     = useState<DocumentListItemDto[]>([]);
    const [totalElements, setTotal]     = useState(0);
    const [totalPages, setTotalPages]   = useState(1);
    const [page, setPage]               = useState(1);

    // ── Filtres ───────────────────────────────────────────────────────────
    const [filtres, setFiltres]         = useState<Filtres>(FILTRES_VIDES);
    const [filtresActifs, setFiltresActifs] = useState<Filtres>(FILTRES_VIDES);
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);

    // ── États UI ──────────────────────────────────────────────────────────
    const [isLoading, setIsLoading]     = useState(false);
    const [error, setError]             = useState('');
    const [filtresOuverts, setFiltresOuverts] = useState(true);

    // ── Détail ────────────────────────────────────────────────────────────
    const [detail, setDetail]           = useState<DocumentDetailDto | null>(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [isDetailOpen, setIsDetailOpen]   = useState(false);

    // ── Lecteur PDF ───────────────────────────────────────────────────────
    const [pdfBlobUrl, setPdfBlobUrl]   = useState<string | null>(null);
    const [pdfLoading, setPdfLoading]   = useState(false);
    const [isPdfOpen, setIsPdfOpen]     = useState(false);

    // ── Téléchargement ────────────────────────────────────────────────────
    const [downloadingId, setDownloadingId] = useState<string | null>(null);

    // ── Gestion du groupe d'accès (documents privés) ─────────────────────
    const [groupeDoc, setGroupeDoc]         = useState<{ id: string; titre: string } | null>(null);
    const [isGroupeOpen, setIsGroupeOpen]   = useState(false);

    // ── Suppression d'un document corrompu ───────────────────────────────
    const [suppressionLoading, setSuppressionLoading] = useState(false);

    // ── Attestation d'archivage ───────────────────────────────────────────
    const [attestationUrl, setAttestationUrl]         = useState<string | null>(null);
    const [attestationLoading, setAttestationLoading] = useState(false);
    const [attestationError, setAttestationError]     = useState('');

    // ─────────────────────────────────────────────────────────────────────
    // Chargement des types pour le select
    // ─────────────────────────────────────────────────────────────────────

    useEffect(() => {
        getAllTypeDocuments()
            .then(setTypeDocuments)
            .catch(() => {});
    }, []);

    // ─────────────────────────────────────────────────────────────────────
    // Chargement des documents
    // ─────────────────────────────────────────────────────────────────────

    const loadDocuments = useCallback(async (f: Filtres, p: number) => {
        setIsLoading(true);
        setError('');
        try {
            const result = await getDocumentsAccessibles({
                titre:          f.titre          || undefined,
                typeDocumentId: f.typeDocumentId ? Number(f.typeDocumentId) : undefined,
                access:         f.access         || undefined,
                dateDebut:      f.dateDebut      || undefined,
                dateFin:        f.dateFin        || undefined,
                statut:         f.statut         || undefined,
                uoId:           uoId ?? undefined,
                page:           p,
                size:           10,
            });
            setDocuments(result.content);
            setTotal(result.totalElements);
            setTotalPages(result.totalPages);
            setPage(p);
        } catch (err: any) {
            setError(err.message ?? 'Erreur chargement');
        } finally {
            setIsLoading(false);
        }
    }, [uoId]);

    // Chargement initial + rechargement si l'UO sélectionnée change (navigation Admin/Admin_UO)
    useEffect(() => {
        loadDocuments(FILTRES_VIDES, 1);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [loadDocuments]);

    // ─────────────────────────────────────────────────────────────────────
    // Handlers filtres
    // ─────────────────────────────────────────────────────────────────────

    const handleFiltreChange = (key: keyof Filtres, value: string) => {
        setFiltres(prev => ({ ...prev, [key]: value }));
    };

    const appliquerFiltres = (e: React.FormEvent) => {
        e.preventDefault();
        setFiltresActifs(filtres);
        loadDocuments(filtres, 1);
    };

    const reinitialiserFiltres = () => {
        setFiltres(FILTRES_VIDES);
        setFiltresActifs(FILTRES_VIDES);
        loadDocuments(FILTRES_VIDES, 1);
    };

    const nbFiltresActifs = Object.values(filtresActifs).filter(v => v !== '').length;

    // ─────────────────────────────────────────────────────────────────────
    // Lecteur PDF
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    // Détail
    // ─────────────────────────────────────────────────────────────────────

    const openDetail = async (doc: DocumentListItemDto) => openDetailById(doc.documentId);

    /** Aussi utilisé pour naviguer entre versions depuis l'historique. */
    const openDetailById = async (documentId: string) => {
        setDetailLoading(true);
        setIsDetailOpen(true);
        setDetail(null);
        setAttestationUrl(null);
        setAttestationError('');
        try {
            const d = await getDocumentDetail(documentId);
            setDetail(d);
        } finally {
            setDetailLoading(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Téléchargements
    // ─────────────────────────────────────────────────────────────────────

    const handleDownloadPdfA = async (doc: DocumentListItemDto) => {
        setDownloadingId(doc.documentId + '_pdfa');
        try { await downloadPdfA(doc.documentId, doc.titre); }
        finally { setDownloadingId(null); }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Groupe d'accès
    // ─────────────────────────────────────────────────────────────────────

    const openGroupe = (doc: DocumentListItemDto) => {
        setGroupeDoc({ id: doc.documentId, titre: doc.titre });
        setIsGroupeOpen(true);
    };

    // ─────────────────────────────────────────────────────────────────────
    // Attestation d'archivage
    // ─────────────────────────────────────────────────────────────────────

    const handleGenererAttestation = async (documentId: string) => {
        setAttestationLoading(true);
        setAttestationError('');
        try {
            const dto = await genererAttestation(documentId);
            setAttestationUrl(dto.url);
        } catch (err: any) {
            setAttestationError(err.response?.data?.message ?? 'Erreur lors de la génération de l\'attestation');
        } finally {
            setAttestationLoading(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Suppression d'un document corrompu (délai de grâce de 3 jours)
    // ─────────────────────────────────────────────────────────────────────

    const handleSupprimerCorrompu = async (documentId: string) => {
        if (!window.confirm(
            'Confirmer la suppression définitive de ce document dans 3 jours ? '
            + 'Il reste consultable et téléchargeable pendant ce délai.'
        )) return;

        setSuppressionLoading(true);
        try {
            await planifierSuppressionDocument(documentId);
            await openDetailById(documentId); // recharge pour afficher la date planifiée
        } catch (err: any) {
            window.alert(err.message ?? 'Erreur lors de la planification de la suppression');
        } finally {
            setSuppressionLoading(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // RENDU
    // ─────────────────────────────────────────────────────────────────────

    return (
        <div className="mes-docs-wrapper">

            {/* ── En-tête ── */}
            <div className="mes-docs-header">
                <h2 className="mes-docs-title">Documents accessibles</h2>
                <button
                    className="filtres-toggle-btn"
                    onClick={() => setFiltresOuverts(o => !o)}
                >
                    <i className="fa-solid fa-sliders" />
                    Filtres
                    {nbFiltresActifs > 0 && (
                        <span className="filtres-badge">{nbFiltresActifs}</span>
                    )}
                    <i className={`fa-solid fa-chevron-${filtresOuverts ? 'up' : 'down'} filtres-chevron`} />
                </button>
            </div>

            {/* ── Panneau filtres ── */}
            {filtresOuverts && (
                <form className="filtres-panel" onSubmit={appliquerFiltres}>
                    <div className="filtres-grid">

                        {/* Titre */}
                        <div className="filtre-field">
                            <label className="filtre-label">Titre</label>
                            <input
                                type="text"
                                className="filter-input"
                                placeholder="Rechercher par titre..."
                                value={filtres.titre}
                                onChange={e => handleFiltreChange('titre', e.target.value)}
                            />
                        </div>

                        {/* Type de document */}
                        <div className="filtre-field">
                            {/* 1. Ajoutez htmlFor qui correspond à l'id du select */}
                            <label htmlFor="typeDocumentSelect" className="filtre-label">
                                Type de document
                            </label>
                            <select
                                id="typeDocumentSelect" // 2. Ajoutez un id unique
                                className="filter-input"
                                value={filtres.typeDocumentId}
                                onChange={e => handleFiltreChange('typeDocumentId', e.target.value)}
                            >
                                <option value="">Tous les types</option>
                                {typeDocuments.map(td => (
                                    <option key={td.id} value={td.id}>{td.nom}</option>
                                ))}
                            </select>
                        </div>

                        {/* Accès */}
                        <div className="filtre-field">
                            <label htmlFor="acces-select" className="filtre-label">Accès</label>
                            <select
                                id="acces-select"
                                className="filter-input"
                                value={filtres.access}
                                onChange={e => handleFiltreChange('access', e.target.value)}
                            >
                                <option value="">Public et privé</option>
                                <option value="PUBLIC">Public uniquement</option>
                                <option value="PRIVE">Privé uniquement</option>
                            </select>
                        </div>

                       {/* Statut */}
                        <div className="filtre-field">
                            <label htmlFor="statut-select" className="filtre-label">Statut</label>
                            <select
                                id="statut-select"
                                className="filter-input"
                                value={filtres.statut}
                                onChange={e => handleFiltreChange('statut', e.target.value)}
                            >
                                <option value="">Tous les statuts</option>
                                <option value="ACTIVE">Actif</option>
                                <option value="PENDING">En attente</option>
                                <option value="ACTIVE_WARNING">Avertissement</option>
                                <option value="CORRUPTED">Corrompu</option>
                            </select>
                        </div>
                        
                        {/* Date début */}
                        <div className="filtre-field">
                            <label htmlFor="date-debut" className="filtre-label">Archivé depuis</label>
                            <input
                                id="date-debut"
                                type="date"
                                className="filter-input"
                                value={filtres.dateDebut}
                                max={filtres.dateFin || undefined}
                                onChange={e => handleFiltreChange('dateDebut', e.target.value)}
                            />
                        </div>
                        
                        {/* Date fin */}
                        <div className="filtre-field">
                            <label htmlFor="date-fin" className="filtre-label">Archivé jusqu'au</label>
                            <input
                                id="date-fin"
                                type="date"
                                className="filter-input"
                                value={filtres.dateFin}
                                min={filtres.dateDebut || undefined}
                                onChange={e => handleFiltreChange('dateFin', e.target.value)}
                            />
                        </div>
                    </div>

                    {/* Actions filtres */}
                    <div className="filtres-actions">
                        <button
                            type="button"
                            className="filtres-reset-btn"
                            onClick={reinitialiserFiltres}
                            disabled={nbFiltresActifs === 0 && Object.values(filtres).every(v => v === '')}
                        >
                            <i className="fa-solid fa-rotate-left" /> Réinitialiser
                        </button>
                        <button
                            type="submit"
                            className="form-submit-btn filtres-apply-btn"
                            disabled={isLoading}
                        >
                            {isLoading
                                ? <><i className="fa-solid fa-spinner fa-spin" /> Recherche...</>
                                : <><i className="fa-solid fa-magnifying-glass" /> Appliquer</>
                            }
                        </button>
                    </div>
                </form>
            )}

            {/* ── Résumé filtres actifs ── */}
            {nbFiltresActifs > 0 && (
                <div className="filtres-actifs-bar">
                    <span className="filtres-actifs-label">
                        <i className="fa-solid fa-filter" />
                        {nbFiltresActifs} filtre{nbFiltresActifs > 1 ? 's' : ''} actif{nbFiltresActifs > 1 ? 's' : ''} —
                    </span>
                    {filtresActifs.titre && (
                        <span className="filtre-tag">Titre : «{filtresActifs.titre}»</span>
                    )}
                    {filtresActifs.typeDocumentId && (
                        <span className="filtre-tag">
                            Type : {typeDocuments.find(t => String(t.id) === filtresActifs.typeDocumentId)?.nom ?? filtresActifs.typeDocumentId}
                        </span>
                    )}
                    {filtresActifs.access && (
                        <span className="filtre-tag">
                            Accès : {filtresActifs.access === 'PUBLIC' ? 'Public' : 'Privé'}
                        </span>
                    )}
                    {filtresActifs.statut && (
                        <span className="filtre-tag">
                            Statut : {STATUS_LABELS[filtresActifs.statut] ?? filtresActifs.statut}
                        </span>
                    )}
                    {filtresActifs.dateDebut && (
                        <span className="filtre-tag">Depuis : {filtresActifs.dateDebut}</span>
                    )}
                    {filtresActifs.dateFin && (
                        <span className="filtre-tag">Jusqu'au : {filtresActifs.dateFin}</span>
                    )}
                    <button className="filtres-actifs-clear" onClick={reinitialiserFiltres}>
                        <i className="fa-solid fa-xmark" /> Tout effacer
                    </button>
                </div>
            )}

            {error && <div className="up-alert up-alert-error">{error}</div>}

            {/* ── Compteur ── */}
            {!isLoading && (
                <p className="users-count">
                    <span>{totalElements}</span> document{totalElements > 1 ? 's' : ''}
                    {nbFiltresActifs > 0 && ' trouvé' + (totalElements > 1 ? 's' : '')}
                </p>
            )}

            {/* ── Tableau ── */}
            {isLoading && documents.length === 0 ? (
                <div className="td-loading">
                    <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                </div>
            ) : documents.length === 0 ? (
                <div className="td-empty">
                    <i className="fa-solid fa-folder-open" style={{ fontSize: '2.5rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }} />
                    <p>{nbFiltresActifs > 0 ? 'Aucun document ne correspond à ces filtres.' : 'Aucun document accessible.'}</p>
                    {nbFiltresActifs > 0 && (
                        <button className="filtres-reset-btn" onClick={reinitialiserFiltres} style={{ marginTop: '0.5rem' }}>
                            Effacer les filtres
                        </button>
                    )}
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
                                    <th>Statut</th>
                                    <th>Archivé le</th>
                                    <th>Rétention</th>
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
                                            <span className={`status-tag ${STATUS_CLASS[doc.status] ?? 'inactive'}`}>
                                                {STATUS_LABELS[doc.status] ?? doc.status}
                                            </span>
                                        </td>
                                        <td>{formatDate(doc.createAt)}</td>
                                        <td>{formatDate(doc.retentionUntil)}</td>
                                        <td>
                                            <div className="td-actions">
                                                {/* Lire */}
                                                <button
                                                    className="action-button view"
                                                    onClick={() => openPdfViewer(doc)}
                                                    title="Lire le document"
                                                >
                                                    <i className="fa-solid fa-eye" />
                                                </button>

                                                {/* Détail */}
                                                <button
                                                    className="action-button edit"
                                                    onClick={() => openDetail(doc)}
                                                    title="Détail"
                                                >
                                                    <i className="fa-solid fa-circle-info" />
                                                </button>

                                                {/* Télécharger PDF/A */}
                                                <button
                                                    className="action-button"
                                                    onClick={() => handleDownloadPdfA(doc)}
                                                    disabled={downloadingId === doc.documentId + '_pdfa'}
                                                    title="Télécharger PDF/A"
                                                >
                                                    {downloadingId === doc.documentId + '_pdfa'
                                                        ? <i className="fa-solid fa-spinner fa-spin" />
                                                        : <i className="fa-solid fa-file-pdf" />
                                                    }
                                                </button>

                                                {/* Qui a accès (documents privés uniquement) */}
                                                {doc.access === 'PRIVE' && (
                                                    <button
                                                        className="action-button"
                                                        onClick={() => openGroupe(doc)}
                                                        title="Voir qui a accès à ce document"
                                                    >
                                                        <i className="fa-solid fa-user-group" />
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {/* ── Pagination ── */}
                    {totalPages > 1 && (
                        <div className="pagination">
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => loadDocuments(filtresActifs, page - 1)}
                                disabled={page === 1 || isLoading}
                            >‹</button>
                            <span className="pagination-btn pagination-active">
                                {page} / {totalPages}
                            </span>
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => loadDocuments(filtresActifs, page + 1)}
                                disabled={page === totalPages || isLoading}
                            >›</button>
                        </div>
                    )}
                </>
            )}

            {/* ── Modal lecteur PDF ── */}
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

            {/* ── Modal détail ── */}
            <Modal
                isOpen={isDetailOpen}
                onClose={() => { setIsDetailOpen(false); setDetail(null); }}
                title="Détail du document"
            >
                {detailLoading ? (
                    <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
                ) : detail ? (
                    <DocumentDetailPanel
                        detail={detail}
                        onSelectVersion={openDetailById}
                        onSupprimer={handleSupprimerCorrompu}
                        suppressionLoading={suppressionLoading}
                        onVoirAcces={() => {
                            setGroupeDoc({ id: detail.documentId, titre: detail.titre });
                            setIsGroupeOpen(true);
                        }}
                        onGenererAttestation={handleGenererAttestation}
                        attestationUrl={attestationUrl}
                        attestationLoading={attestationLoading}
                        attestationError={attestationError}
                        onEmplacementChange={setDetail}
                    />
                ) : (
                    <div className="td-empty"><p>Impossible de charger le détail.</p></div>
                )}
            </Modal>

            {/* ── Modal groupe d'accès ── */}
            <Modal
                isOpen={isGroupeOpen}
                onClose={() => { setIsGroupeOpen(false); setGroupeDoc(null); }}
                title="Accès au document"
            >
                {groupeDoc && (
                    <GestionGroupe
                        documentId={groupeDoc.id}
                        documentTitre={groupeDoc.titre}
                        onClose={() => { setIsGroupeOpen(false); setGroupeDoc(null); }}
                    />
                )}
            </Modal>
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Panneau détail (réutilisé)
// ─────────────────────────────────────────────────────────────────────────────

function DocumentDetailPanel({
    detail,
    onSelectVersion,
    onSupprimer,
    suppressionLoading,
    onVoirAcces,
    onGenererAttestation,
    attestationUrl,
    attestationLoading,
    attestationError,
    onEmplacementChange,
}: {
    detail: DocumentDetailDto;
    onSelectVersion?: (documentId: string) => void;
    onSupprimer?: (documentId: string) => void;
    suppressionLoading?: boolean;
    onVoirAcces?: () => void;
    onGenererAttestation?: (documentId: string) => void;
    attestationUrl?: string | null;
    attestationLoading?: boolean;
    attestationError?: string;
    onEmplacementChange?: (updated: DocumentDetailDto) => void;
}) {
    const STATUS_LABELS: Record<string, string> = {
        ACTIVE: 'Actif', PENDING: 'En attente',
        ACTIVE_WARNING: 'Avertissement', CORRUPTED: 'Corrompu', DELETED: 'Supprimé',
    };
    return (
        <div className="doc-detail">
            <div className="details-row">
                <strong>Titre :</strong> {detail.titre}
                <VersionBadge label={detail.versionLabel} />
            </div>
            <div className="details-row"><strong>Type :</strong> {detail.typeDocumentNom}</div>
            <div className="details-row">
                <strong>Statut :</strong>
                <span className={`status-tag ${detail.status === 'ACTIVE' ? 'active' : 'inactive'}`}>
                    {STATUS_LABELS[detail.status] ?? detail.status}
                </span>
            </div>

            {detail.status === 'CORRUPTED' && (
                <div className="corruption-banner">
                    <div className="corruption-banner-header">
                        <i className="fa-solid fa-triangle-exclamation" />
                        <span>Document corrompu{detail.corruptionRaison ? ` — ${detail.corruptionRaison}` : ''}</span>
                    </div>
                    <p className="corruption-banner-note">
                        Seuls les administrateurs ayant autorité sur son UO et les éditeurs y ayant accès
                        peuvent encore consulter ou télécharger ce document.
                    </p>

                    {detail.suppressionPrevueLe ? (
                        <p className="corruption-banner-suppression">
                            <i className="fa-solid fa-clock" /> Suppression définitive prévue le{' '}
                            {new Date(detail.suppressionPrevueLe).toLocaleDateString('fr-FR')}.
                        </p>
                    ) : detail.peutEtreSupprime && (
                        <div className="corruption-banner-actions">
                            <button
                                type="button"
                                className="corruption-delete-btn"
                                onClick={() => onSupprimer?.(detail.documentId)}
                                disabled={suppressionLoading}
                            >
                                {suppressionLoading
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> …</>
                                    : <><i className="fa-solid fa-trash" /> Supprimer (définitif dans 3 jours)</>}
                            </button>
                        </div>
                    )}
                </div>
            )}

            <div className="details-row">
                <strong>Accès :</strong>
                <span className={`doc-access-tag ${detail.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                    {detail.access === 'PUBLIC' ? 'Public' : 'Privé'}
                </span>
                {detail.access === 'PRIVE' && onVoirAcces && (
                    <button type="button" className="details-close-btn" onClick={onVoirAcces} style={{ marginLeft: '0.6rem' }}>
                        <i className="fa-solid fa-user-group" /> Voir qui a accès
                    </button>
                )}
            </div>
            <div className="details-row">
                <strong>Archivé le :</strong> {detail.createAt ? new Date(detail.createAt).toLocaleDateString('fr-FR') : '—'}
            </div>
            <div className="details-row"><strong>Rétention :</strong> {detail.retentionUntil ?? '—'}</div>
            <div className="details-row"><strong>Version :</strong> {detail.version}</div>

            <EmplacementPhysiqueSection detail={detail} onUpdated={onEmplacementChange} />

            {detail.pdfaSha256 && (
                <div className="details-row">
                    <strong>Hash PDF/A :</strong>
                    <span className="up-hash">{detail.pdfaSha256.slice(0, 16)}…</span>
                </div>
            )}
            {detail.metaData.length > 0 && (
                <div className="detail-meta-section">
                    <p className="detail-meta-title">Métadonnées</p>
                    <div className="detail-meta-grid">
                        {detail.metaData.map((m, i) => (
                            <div key={i} className="detail-meta-item">
                                <span className="detail-meta-type">{m.typeValeur}</span>
                                <span className="detail-meta-value">{m.valeur ?? '—'}</span>
                            </div>
                        ))}
                    </div>
                </div>
            )}
            {detail.historiqueVersions.length > 0 && (
                <div className="version-history">
                    <p className="version-history-title">Historique des versions</p>
                    <ul className="version-history-list">
                        {detail.historiqueVersions.map((v) => (
                            <li key={v.documentId}>
                                <button
                                    type="button"
                                    className={`version-history-item ${v.documentId === detail.documentId ? 'version-history-item-current' : ''}`}
                                    onClick={() => onSelectVersion?.(v.documentId)}
                                    disabled={v.documentId === detail.documentId}
                                    title={v.estVersionActuelle ? 'Version actuelle de la chaîne' : undefined}
                                >
                                    <span>{v.versionLabel ?? `Version ${v.version}`}</span>
                                    <span className="version-history-meta">
                                        {v.uploadedByNom ?? ''}
                                        {v.createAt ? ` · ${new Date(v.createAt).toLocaleDateString('fr-FR')}` : ''}
                                    </span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </div>
            )}

            {onGenererAttestation && detail.status !== 'DELETED' && (
                <div className="attestation-section">
                    <p className="detail-meta-title">Attestation d'archivage</p>
                    {attestationUrl ? (
                        <div className="attestation-lien">
                            <input type="text" readOnly value={attestationUrl}
                                onFocus={(e) => e.currentTarget.select()} />
                            <a href={attestationUrl} target="_blank" rel="noreferrer"
                                className="attestation-ouvrir-btn">
                                <i className="fa-solid fa-arrow-up-right-from-square" /> Ouvrir
                            </a>
                        </div>
                    ) : (
                        <button
                            type="button"
                            className="attestation-generer-btn"
                            onClick={() => onGenererAttestation(detail.documentId)}
                            disabled={attestationLoading}
                        >
                            {attestationLoading
                                ? <><i className="fa-solid fa-spinner fa-spin" /> Génération…</>
                                : <><i className="fa-solid fa-certificate" /> Générer une attestation</>}
                        </button>
                    )}
                    {attestationError && <p className="attestation-erreur">{attestationError}</p>}
                </div>
            )}
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : emplacement physique
// ─────────────────────────────────────────────────────────────────────────────

function EmplacementPhysiqueSection({
    detail,
    onUpdated,
}: {
    detail: DocumentDetailDto;
    onUpdated?: (updated: DocumentDetailDto) => void;
}) {
    const [editing, setEditing] = useState(false);
    const [options, setOptions] = useState<PhysicalLocationDto[]>([]);
    const [optionsLoading, setOptionsLoading] = useState(false);
    const [selected, setSelected] = useState('');
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState('');

    const ouvrirEdition = async () => {
        setEditing(true);
        setError('');
        setSelected(detail.physicalLocationId ?? '');
        if (detail.uniteOrganisationnelleId == null) return;
        setOptionsLoading(true);
        try {
            const data = await getEmplacementsDisponibles(detail.uniteOrganisationnelleId);
            setOptions(data);
        } catch {
            setError('Impossible de charger les emplacements disponibles');
        } finally {
            setOptionsLoading(false);
        }
    };

    const enregistrer = async () => {
        setSaving(true);
        setError('');
        try {
            const updated = await modifierEmplacementPhysique(detail.documentId, selected || null);
            onUpdated?.(updated);
            setEditing(false);
        } catch (err: any) {
            setError(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    if (!detail.peutModifierEmplacement && !detail.physicalLocationPath) {
        return null;
    }

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Emplacement physique :</strong>
            {editing ? (
                <div className="emplacement-edit">
                    {optionsLoading ? (
                        <i className="fa-solid fa-spinner fa-spin" />
                    ) : (
                        <select value={selected} onChange={(e) => setSelected(e.target.value)}>
                            <option value="">— Aucun —</option>
                            {options.map((o) => (
                                <option key={o.id} value={o.id}>{o.cheminComplet}</option>
                            ))}
                        </select>
                    )}
                    <button type="button" className="attestation-generer-btn" disabled={saving} onClick={enregistrer}>
                        {saving ? '…' : 'Enregistrer'}
                    </button>
                    <button type="button" className="details-close-btn" onClick={() => setEditing(false)}>Annuler</button>
                    {error && <p className="attestation-erreur">{error}</p>}
                </div>
            ) : (
                <>
                    <span>{detail.physicalLocationPath ?? '—'}</span>
                    {detail.peutModifierEmplacement && (
                        <button type="button" className="details-close-btn" onClick={ouvrirEdition}>
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                </>
            )}
        </div>
    );
}

export default DocumentsAccessibles;