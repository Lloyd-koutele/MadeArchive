import { useState, useEffect, useCallback } from 'react';
import Modal from '../Page/Modal';
import GestionGroupe from '../document/GestionGroupe';
import UploadSimple from '../document/UploadSimple';
import VersionBadge from '../document/VersionBadge';
import { genererAttestation } from '../services/document/AttestationService';
import { modifierEmplacementPhysique } from '../services/document/DocumentService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import {
    getMesFolders,
    getMesDocumentsByType,
    rechercherDocuments,
    getDocumentDetail,
    downloadPdfA,
    streamPdfAAsBlob,
    planifierSuppressionDocument

 } from '../services/document/DocumentService';
import type { 
    DocumentFolderDto,
    DocumentListItemDto,
    DocumentDetailDto
} from '../services/document/DocumentService';
import '../Style/Editor/Editor.css';

// ─────────────────────────────────────────────────────────────────────────────
// Constantes
// ─────────────────────────────────────────────────────────────────────────────

const FOLDER_COLORS = [
    '#4A90D9', '#E67E3F', '#2ECC8F', '#9B59B6',
    '#E74C3C', '#F1C40F', '#1ABC9C', '#E91E8C',
    '#3498DB', '#FF6B35',
];

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

function folderColor(nom: string): string {
    let hash = 0;
    for (let i = 0; i < nom.length; i++) hash = nom.charCodeAt(i) + ((hash << 5) - hash);
    return FOLDER_COLORS[Math.abs(hash) % FOLDER_COLORS.length];
}

function formatDate(iso: string | null): string {
    if (!iso) return '—';
    try { return new Date(iso).toLocaleDateString('fr-FR'); }
    catch { return iso; }
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface MesDocumentsEditorProps {
    refreshTrigger?:     number;
    /** typeDocumentId pré-sélectionné (venant du bouton "+" d'un dossier) */
    preselectedTypeId?:  number | null;
    onPreselectedConsumed?: () => void;
}

// ─────────────────────────────────────────────────────────────────────────────
// Composant principal
// ─────────────────────────────────────────────────────────────────────────────

function MesDocumentsEditor({
    refreshTrigger,
    preselectedTypeId,
    onPreselectedConsumed,
}: MesDocumentsEditorProps) {

    // ── Vue courante ──────────────────────────────────────────────────────────
    type View = 'folders' | 'list';
    const [view, setView] = useState<View>('folders');

    // ── Dossiers ──────────────────────────────────────────────────────────────
    const [folders, setFolders]         = useState<DocumentFolderDto[]>([]);
    const [folderSearch, setFolderSearch] = useState('');
    const [foldersLoading, setFoldersLoading] = useState(false);
    const [foldersError, setFoldersError]     = useState('');

    // ── Dossier courant ───────────────────────────────────────────────────────
    const [activeFolder, setActiveFolder] = useState<DocumentFolderDto | null>(null);

    // ── Liste documents ───────────────────────────────────────────────────────
    const [documents, setDocuments]   = useState<DocumentListItemDto[]>([]);
    const [listPage, setListPage]     = useState(1);
    const [listTotal, setListTotal]   = useState(0);
    const [listPages, setListPages]   = useState(1);
    const [listLoading, setListLoading] = useState(false);
    const [listError, setListError]   = useState('');

    // ── Recherche ─────────────────────────────────────────────────────────────
    const [searchQuery, setSearchQuery] = useState('');
    const [isSearching, setIsSearching] = useState(false);

    // ── Détail document ───────────────────────────────────────────────────────
    const [detail, setDetail]         = useState<DocumentDetailDto | null>(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [isDetailOpen, setIsDetailOpen]   = useState(false);

    // ── Lecteur PDF ───────────────────────────────────────────────────────────
    const [pdfBlobUrl, setPdfBlobUrl]   = useState<string | null>(null);
    const [pdfLoading, setPdfLoading]   = useState(false);
    const [isPdfOpen, setIsPdfOpen]     = useState(false);

    // ── Téléchargement ────────────────────────────────────────────────────────
    const [downloadingId, setDownloadingId] = useState<string | null>(null);

    // ── Upload depuis dossier ─────────────────────────────────────────────────
    const [uploadTypeId, setUploadTypeId]   = useState<number | null>(null);
    const [isUploadOpen, setIsUploadOpen]   = useState(false);

    // ── Upload d'une nouvelle version d'un document existant ─────────────────
    const [versionSource, setVersionSource] = useState<{ documentId: string; typeDocumentId: number; titre: string } | null>(null);

    // ── Groupe accès ──────────────────────────────────────────────────────────
    const [groupeDocId, setGroupeDocId]     = useState<string | null>(null);
    const [groupeDocTitre, setGroupeDocTitre] = useState('');
    const [isGroupeOpen, setIsGroupeOpen]   = useState(false);

    // ─────────────────────────────────────────────────────────────────────────
    // Chargement dossiers
    // ─────────────────────────────────────────────────────────────────────────

    const loadFolders = useCallback(async () => {
        setFoldersLoading(true);
        setFoldersError('');
        try {
            const data = await getMesFolders();
            setFolders(data);
        } catch (err: any) {
            setFoldersError(err.message ?? 'Erreur chargement dossiers');
        } finally {
            setFoldersLoading(false);
        }
    }, []);

    useEffect(() => { loadFolders(); }, [loadFolders, refreshTrigger]);

    // Pré-sélection d'un type (venant du bouton "+" de EditorDashboard)
    useEffect(() => {
        if (preselectedTypeId && folders.length > 0) {
            const folder = folders.find(f => f.typeDocumentId === preselectedTypeId);
            if (folder) {
                openFolder(folder);
                onPreselectedConsumed?.();
            }
        }
    }, [preselectedTypeId, folders]);

    // ─────────────────────────────────────────────────────────────────────────
    // Chargement liste documents d'un dossier
    // ─────────────────────────────────────────────────────────────────────────

    const loadDocuments = useCallback(async (
        folder: DocumentFolderDto,
        page: number,
        q?: string,
    ) => {
        setListLoading(true);
        setListError('');
        try {
            const result = q && q.trim()
                ? await rechercherDocuments(q.trim(), folder.typeDocumentId, page, 10)
                : await getMesDocumentsByType(folder.typeDocumentId, page, 10);

            setDocuments(result.content);
            setListTotal(result.totalElements);
            setListPages(result.totalPages);
            setListPage(page);
        } catch (err: any) {
            setListError(err.message ?? 'Erreur chargement documents');
        } finally {
            setListLoading(false);
        }
    }, []);

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────────

    const openFolder = (folder: DocumentFolderDto) => {
        setActiveFolder(folder);
        setSearchQuery('');
        setIsSearching(false);
        setView('list');
        loadDocuments(folder, 1);
    };

    const backToFolders = () => {
        setView('folders');
        setActiveFolder(null);
        setDocuments([]);
        setSearchQuery('');
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Recherche dans le dossier courant
    // ─────────────────────────────────────────────────────────────────────────

    const handleSearch = (e: React.FormEvent) => {
        e.preventDefault();
        if (!activeFolder) return;
        setIsSearching(!!searchQuery.trim());
        loadDocuments(activeFolder, 1, searchQuery);
    };

    // Recherche au fil de la frappe — Meilisearch répond en quelques ms, pas la
    // peine d'attendre la soumission du formulaire. Debounce léger (250ms) pour
    // ne pas envoyer une requête par caractère ; le bouton/Entrée restent
    // utilisables pour une recherche immédiate.
    useEffect(() => {
        if (!activeFolder) return;
        if (!searchQuery.trim()) {
            if (isSearching) { setIsSearching(false); loadDocuments(activeFolder, 1); }
            return;
        }
        const timer = setTimeout(() => {
            setIsSearching(true);
            loadDocuments(activeFolder, 1, searchQuery);
        }, 250);
        return () => clearTimeout(timer);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [searchQuery, activeFolder]);

    const clearSearch = () => {
        setSearchQuery('');
        setIsSearching(false);
        if (activeFolder) loadDocuments(activeFolder, 1);
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Détail document
    // ─────────────────────────────────────────────────────────────────────────

    const openDetail = async (doc: DocumentListItemDto) => openDetailById(doc.documentId);

    /** Aussi utilisé pour naviguer entre versions depuis l'historique. */
    const openDetailById = async (documentId: string) => {
        setDetailLoading(true);
        setIsDetailOpen(true);
        setAttestationUrl(null);
        setAttestationError('');
        try {
            const d = await getDocumentDetail(documentId);
            setDetail(d);
        } catch (err: any) {
            setDetail(null);
        } finally {
            setDetailLoading(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Lecteur PDF
    // ─────────────────────────────────────────────────────────────────────────

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
        if (pdfBlobUrl) {
            URL.revokeObjectURL(pdfBlobUrl);
            setPdfBlobUrl(null);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargements
    // ─────────────────────────────────────────────────────────────────────────

    const handleDownloadPdfA = async (doc: DocumentListItemDto) => {
        setDownloadingId(doc.documentId + '_pdfa');
        try { await downloadPdfA(doc.documentId, doc.titre); }
        finally { setDownloadingId(null); }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Upload depuis bouton "+"
    // ─────────────────────────────────────────────────────────────────────────

    const openUploadForFolder = (folder: DocumentFolderDto, e: React.MouseEvent) => {
        e.stopPropagation();
        setVersionSource(null);
        setUploadTypeId(folder.typeDocumentId);
        setIsUploadOpen(true);
    };

    /** Ouvre l'upload en verrouillant le type et en liant le nouveau fichier comme version suivante. */
    const openNewVersion = (doc: DocumentListItemDto) => {
        setVersionSource({ documentId: doc.documentId, typeDocumentId: doc.typeDocumentId, titre: doc.titre });
        setUploadTypeId(doc.typeDocumentId);
        setIsUploadOpen(true);
    };

    const closeUpload = () => {
        setIsUploadOpen(false);
        setVersionSource(null);
    };

    /** Depuis le panneau de détail d'un document corrompu — ferme le détail et ouvre l'upload de remplacement. */
    const handleRemplacerCorrompu = (d: DocumentDetailDto) => {
        setIsDetailOpen(false);
        setDetail(null);
        openNewVersion({ documentId: d.documentId, typeDocumentId: d.typeDocumentId, titre: d.titre } as DocumentListItemDto);
    };

    const [suppressionLoading, setSuppressionLoading] = useState(false);

    // ── Attestation d'archivage ───────────────────────────────────────────
    const [attestationUrl, setAttestationUrl]         = useState<string | null>(null);
    const [attestationLoading, setAttestationLoading] = useState(false);
    const [attestationError, setAttestationError]     = useState('');

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

    const handleUploadSuccess = () => {
        closeUpload();
        loadFolders();
        if (activeFolder) loadDocuments(activeFolder, listPage);
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Dossiers filtrés (filtrage local)
    // ─────────────────────────────────────────────────────────────────────────

    const visibleFolders = folders.filter(f =>
        f.typeDocumentNom.toLowerCase().includes(folderSearch.toLowerCase())
    );
    const displayedFolders = folderSearch ? visibleFolders : visibleFolders.slice(0, 10);
    const hasMore = !folderSearch && folders.length > 10;

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU — Vue Grille de dossiers
    // ─────────────────────────────────────────────────────────────────────────

    if (view === 'folders') {
        return (
            <div className="mes-docs-wrapper">
                <div className="mes-docs-header">
                    <h2 className="mes-docs-title">Mes documents</h2>
                    {folders.length > 10 && (
                        <div className="folder-search-bar">
                            <i className="fa-solid fa-magnifying-glass folder-search-icon" />
                            <input
                                type="text"
                                className="filter-input folder-search-input"
                                placeholder="Rechercher un type..."
                                value={folderSearch}
                                onChange={e => setFolderSearch(e.target.value)}
                            />
                        </div>
                    )}
                </div>

                {foldersError && (
                    <div className="up-alert up-alert-error">{foldersError}</div>
                )}

                {foldersLoading ? (
                    <div className="td-loading">
                        <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                    </div>
                ) : folders.length === 0 ? (
                    <div className="td-empty">
                        <i className="fa-solid fa-folder-open" style={{ fontSize: '2.5rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }} />
                        <p>Aucun document archivé.</p>
                        <span>Uploadez votre premier document via le menu de gauche.</span>
                    </div>
                ) : (
                    <>
                        <div className="folders-grid">
                            {displayedFolders.map(folder => (
                                <div
                                    key={folder.typeDocumentId}
                                    className="folder-card"
                                    onClick={() => openFolder(folder)}
                                    role="button"
                                    tabIndex={0}
                                    onKeyDown={e => e.key === 'Enter' && openFolder(folder)}
                                    aria-label={`Ouvrir le dossier ${folder.typeDocumentNom}`}
                                >
                                    {/* Bouton "+" */}
                                    <button
                                        className="folder-add-btn"
                                        onClick={e => openUploadForFolder(folder, e)}
                                        aria-label={`Ajouter un document de type ${folder.typeDocumentNom}`}
                                        title="Ajouter un document"
                                    >
                                        <i className="fa-solid fa-plus" />
                                    </button>

                                    {/* Icône dossier SVG colorée */}
                                    <div className="folder-icon-wrap">
                                        <svg
                                            viewBox="0 0 64 52"
                                            fill="none"
                                            xmlns="http://www.w3.org/2000/svg"
                                            className="folder-svg"
                                            aria-hidden="true"
                                        >
                                            {/* Onglet du dossier */}
                                            <path
                                                d="M2 10 Q2 4 8 4 L24 4 L28 10 L60 10 Q62 10 62 12 L62 48 Q62 50 60 50 L4 50 Q2 50 2 48 Z"
                                                fill={folderColor(folder.typeDocumentNom)}
                                            />
                                            {/* Onglet avant (légèrement plus clair) */}
                                            <path
                                                d="M2 10 L28 10 L24 4 L8 4 Q2 4 2 10 Z"
                                                fill={folderColor(folder.typeDocumentNom)}
                                                opacity="0.75"
                                            />
                                            {/* Reflet */}
                                            <path
                                                d="M8 14 L56 14 L56 20 Q32 22 8 20 Z"
                                                fill="white"
                                                opacity="0.12"
                                            />
                                        </svg>

                                        {/* Compteur superposé */}
                                        <span className="folder-count">
                                            {folder.count}
                                        </span>
                                    </div>

                                    {/* Nom du type */}
                                    <span className="folder-name">
                                        {folder.typeDocumentNom}
                                    </span>
                                </div>
                            ))}
                        </div>

                        {hasMore && (
                            <p className="folders-more-hint">
                                <i className="fa-solid fa-circle-info" />
                                {folders.length - 10} autre{folders.length - 10 > 1 ? 's' : ''} type{folders.length - 10 > 1 ? 's' : ''} —
                                utilisez la recherche ci-dessus pour les trouver.
                            </p>
                        )}
                    </>
                )}

                {/* Modal upload depuis dossier */}
                <Modal
                    isOpen={isUploadOpen}
                    onClose={closeUpload}
                    title="Ajouter un document"
                >
                    <UploadSimple
                        onsuccess={handleUploadSuccess}
                        preselectedTypeId={uploadTypeId}
                    />
                </Modal>
            </div>
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU — Vue Liste (documents d'un dossier)
    // ─────────────────────────────────────────────────────────────────────────

    return (
        <div className="mes-docs-wrapper">

            {/* ── Fil d'ariane ── */}
            <div className="docs-breadcrumb">
                <button className="breadcrumb-back" onClick={backToFolders}>
                    <i className="fa-solid fa-arrow-left" /> Mes documents
                </button>
                <i className="fa-solid fa-chevron-right breadcrumb-sep" />
                <span
                    className="breadcrumb-folder-dot"
                    style={{ background: activeFolder ? folderColor(activeFolder.typeDocumentNom) : '#ccc' }}
                />
                <span className="breadcrumb-current">
                    {activeFolder?.typeDocumentNom}
                </span>
                <span className="breadcrumb-count">
                    ({listTotal} document{listTotal > 1 ? 's' : ''})
                </span>

                {/* Bouton ajouter dans ce dossier */}
                <button
                    className="breadcrumb-add-btn"
                    onClick={() => {
                        if (activeFolder) {
                            setVersionSource(null);
                            setUploadTypeId(activeFolder.typeDocumentId);
                            setIsUploadOpen(true);
                        }
                    }}
                >
                    <i className="fa-solid fa-plus" />
                    Ajouter
                </button>
            </div>

            {/* ── Barre de recherche dans ce type ── */}
            <form className="mes-docs-search" onSubmit={handleSearch}>
                <input
                    type="text"
                    className="filter-input"
                    placeholder={`Rechercher dans ${activeFolder?.typeDocumentNom ?? 'ce dossier'}...`}
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                />
                {isSearching && (
                    <button
                        type="button"
                        className="search-clear-btn"
                        onClick={clearSearch}
                        aria-label="Effacer la recherche"
                    >
                        <i className="fa-solid fa-xmark" />
                    </button>
                )}
                <button
                    type="submit"
                    className="form-submit-btn mes-docs-search-btn"
                    disabled={listLoading}
                    aria-label="Lancer la recherche"
                >
                    <i className={`fa-solid ${listLoading ? 'fa-spinner fa-spin' : 'fa-magnifying-glass'}`} />
                </button>
            </form>

            {listError && <div className="up-alert up-alert-error">{listError}</div>}

            {/* ── Table documents ── */}
            {listLoading && documents.length === 0 ? (
                <div className="td-loading">
                    <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                </div>
            ) : documents.length === 0 ? (
                <div className="td-empty">
                    <p>{isSearching ? 'Aucun résultat pour cette recherche.' : 'Aucun document dans ce dossier.'}</p>
                    {!isSearching && (
                        <button
                            className="form-submit-btn"
                            style={{ marginTop: '0.75rem' }}
                            onClick={() => {
                                if (activeFolder) {
                                    setVersionSource(null);
                                    setUploadTypeId(activeFolder.typeDocumentId);
                                    setIsUploadOpen(true);
                                }
                            }}
                        >
                            <i className="fa-solid fa-plus" /> Ajouter un document
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
                                                {/* Voir PDF */}
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
                                                    title="Détail et métadonnées"
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

                                                {/* Groupe accès */}
                                                {doc.access === 'PRIVE' && (
                                                    <button
                                                        className="action-button"
                                                        onClick={() => {
                                                            setGroupeDocId(doc.documentId);
                                                            setGroupeDocTitre(doc.titre);
                                                            setIsGroupeOpen(true);
                                                        }}
                                                        title="Gérer le groupe d'accès"
                                                    >
                                                        <i className="fa-solid fa-users" />
                                                    </button>
                                                )}

                                                {/* Nouvelle version — uniquement sur la dernière version (jamais versionné ou "Final") */}
                                                {(!doc.versionLabel || doc.versionLabel === 'Final') && (
                                                    <button
                                                        className="action-button"
                                                        onClick={() => openNewVersion(doc)}
                                                        title="Déposer une nouvelle version"
                                                    >
                                                        <i className="fa-solid fa-code-branch" />
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {/* Pagination */}
                    {listPages > 1 && (
                        <div className="pagination">
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => activeFolder && loadDocuments(activeFolder, listPage - 1, searchQuery || undefined)}
                                disabled={listPage === 1 || listLoading}
                            >‹</button>
                            <span className="pagination-btn pagination-active">
                                {listPage} / {listPages}
                            </span>
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => activeFolder && loadDocuments(activeFolder, listPage + 1, searchQuery || undefined)}
                                disabled={listPage === listPages || listLoading}
                            >›</button>
                        </div>
                    )}
                </>
            )}

            {/* ── Modal lecteur PDF ── */}
            <Modal
                isOpen={isPdfOpen}
                onClose={closePdfViewer}
                title="Lecture du document"
            >
                <div className="pdf-viewer-wrapper">
                    {pdfLoading ? (
                        <div className="pdf-viewer-loading">
                            <i className="fa-solid fa-spinner fa-spin" />
                            <span>Chargement du document...</span>
                        </div>
                    ) : pdfBlobUrl ? (
                        <iframe
                            src={pdfBlobUrl}
                            className="pdf-viewer-iframe"
                            title="Lecteur PDF"
                        />
                    ) : (
                        <div className="td-empty">
                            <p>Impossible de charger le document.</p>
                        </div>
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
                    <div className="td-loading">
                        <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                    </div>
                ) : detail ? (
                    <DocumentDetailPanel
                        detail={detail}
                        onSelectVersion={openDetailById}
                        onRemplacer={handleRemplacerCorrompu}
                        onSupprimer={handleSupprimerCorrompu}
                        suppressionLoading={suppressionLoading}
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

            {/* ── Modal groupe ── */}
            <Modal
                isOpen={isGroupeOpen}
                onClose={() => setIsGroupeOpen(false)}
                title="Gestion du groupe d'accès"
            >
                {groupeDocId && (
                    <GestionGroupe
                        documentId={groupeDocId}
                        documentTitre={groupeDocTitre}
                        onClose={() => setIsGroupeOpen(false)}
                    />
                )}
            </Modal>

            {/* ── Modal upload ── */}
            <Modal
                isOpen={isUploadOpen}
                onClose={closeUpload}
                title={versionSource ? 'Déposer une nouvelle version' : 'Ajouter un document'}
            >
                <UploadSimple
                    onsuccess={handleUploadSuccess}
                    preselectedTypeId={uploadTypeId}
                    precedentDocument={versionSource}
                />
            </Modal>
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : panneau de détail
// ─────────────────────────────────────────────────────────────────────────────

function DocumentDetailPanel({
    detail,
    onSelectVersion,
    onRemplacer,
    onSupprimer,
    suppressionLoading,
    onGenererAttestation,
    attestationUrl,
    attestationLoading,
    attestationError,
    onEmplacementChange,
}: {
    detail: DocumentDetailDto;
    onSelectVersion?: (documentId: string) => void;
    onRemplacer?: (detail: DocumentDetailDto) => void;
    onSupprimer?: (documentId: string) => void;
    suppressionLoading?: boolean;
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
            <div className="details-row">
                <strong>Type :</strong> {detail.typeDocumentNom}
            </div>
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
                                className="form-submit-btn up-submit"
                                onClick={() => onRemplacer?.(detail)}
                            >
                                <i className="fa-solid fa-file-arrow-up" /> Corriger en remplaçant le fichier
                            </button>
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
            </div>
            <div className="details-row">
                <strong>Archivé le :</strong> {detail.createAt ? new Date(detail.createAt).toLocaleDateString('fr-FR') : '—'}
            </div>
            <div className="details-row">
                <strong>Rétention jusqu'au :</strong> {detail.retentionUntil ?? '—'}
            </div>
            <div className="details-row">
                <strong>Version :</strong> {detail.version}
            </div>

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

export default MesDocumentsEditor;