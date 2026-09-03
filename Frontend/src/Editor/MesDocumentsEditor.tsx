import { useState, useEffect, useCallback, useRef } from 'react';
import Modal from '../Page/Modal';
import GestionGroupe from '../document/GestionGroupe';
import ImportDocuments from '../document/ImportDocuments';
import VersionBadge from '../document/VersionBadge';
import { genererAttestation } from '../services/document/AttestationService';
import { modifierEmplacementPhysique, modifierMetaDataDocument, modifierProjetDocument, verifierFusionGroupeProjet, getTypeDocumentById } from '../services/document/DocumentService';
import type { TypeDocumentDto } from '../services/document/DocumentService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import { getProjetsDeUO } from '../services/organisation/ProjetService';
import type { ProjetDto } from '../services/organisation/ProjetService';
import MetaDataField from '../document/MetadaField';
import {
    getMesFolders,
    getMesDocumentsByType,
    rechercherDocuments,
    getDocumentDetail,
    downloadPdfA,
    streamPdfAAsBlob,
    envoyerDocumentCorbeille,
    restaurerDocumentDepuisCorbeille

 } from '../services/document/DocumentService';
import type { 
    DocumentFolderDto,
    DocumentListItemDto,
    DocumentDetailDto
} from '../services/document/DocumentService';
import { renderPdfFirstPageThumbnail } from '../services/document/PdfThumbnail';
import '../Style/Editor/Editor.css';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';

// ─────────────────────────────────────────────────────────────────────────────
// Constantes
// ─────────────────────────────────────────────────────────────────────────────

// Teinte unique pour tous les dossiers — reprend l'accent de la charte
// chocolat (--accent, voir global.css) plutôt que l'ancienne palette qui
// variait par type (bleu, rouge...). Dupliquée ici en JS (au lieu de lire
// la variable CSS) uniquement pour le point d'ariane ci-dessous, seul
// endroit où la couleur est encore posée en style inline plutôt qu'en CSS.
const FOLDER_GLASS_COLOR = '#8B5E3C';

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
    const notify = useNotify();
    const confirm = useConfirm();

    // ── Vue courante ──────────────────────────────────────────────────────────
    type View = 'folders' | 'list';
    const [view, setView] = useState<View>('folders');

    // ── Dossiers ──────────────────────────────────────────────────────────────
    const [folders, setFolders]         = useState<DocumentFolderDto[]>([]);
    const [folderSearch, setFolderSearch] = useState('');
    const [foldersLoading, setFoldersLoading] = useState(false);

    // Types dont au moins un DOCUMENT (titre/contenu) correspond à la recherche —
    // recherche plein texte Meilisearch, tous types confondus (pas de typeId),
    // en complément du filtre local sur le seul NOM du type. null = pas de
    // recherche en cours (champ vide) ; Set vide = recherche terminée, rien trouvé.
    const [typesAvecDocumentTrouve, setTypesAvecDocumentTrouve] = useState<Set<number> | null>(null);
    const [rechercheContenuEnCours, setRechercheContenuEnCours] = useState(false);

    useEffect(() => {
        const q = folderSearch.trim();
        if (!q) { setTypesAvecDocumentTrouve(null); setRechercheContenuEnCours(false); return; }

        let annule = false;
        setRechercheContenuEnCours(true);
        const timer = setTimeout(async () => {
            try {
                // Même recherche hybride (Meilisearch → BD) que celle utilisée à
                // l'intérieur d'un dossier — sans typeId, elle porte sur TOUS les
                // types accessibles à l'éditeur.
                const result = await rechercherDocuments(q, undefined, 1, 50);
                if (!annule) setTypesAvecDocumentTrouve(new Set(result.content.map(d => d.typeDocumentId)));
            } catch {
                if (!annule) setTypesAvecDocumentTrouve(new Set()); // échec réseau — filtre local seul
            } finally {
                if (!annule) setRechercheContenuEnCours(false);
            }
        }, 250);
        return () => { annule = true; clearTimeout(timer); };
    }, [folderSearch]);

    // ── Dossier courant ───────────────────────────────────────────────────────
    const [activeFolder, setActiveFolder] = useState<DocumentFolderDto | null>(null);

    // ── Liste documents ───────────────────────────────────────────────────────
    const [documents, setDocuments]   = useState<DocumentListItemDto[]>([]);
    const [listPage, setListPage]     = useState(1);
    const [listTotal, setListTotal]   = useState(0);
    const [listPages, setListPages]   = useState(1);
    const [listLoading, setListLoading] = useState(false);

    // ── Sélection multiple — pour l'envoi en masse à la corbeille. Ne
    // retient que des documents réellement gérables (peutGererCorbeille) ;
    // vidée à chaque changement de dossier/page pour éviter une sélection
    // fantôme sur des documents qui ne sont plus affichés. Les cases à
    // cocher ne s'affichent QUE quand selectionModeActive est vrai — activé
    // par un clic droit (PC) ou un appui prolongé (tactile) sur une ligne,
    // jamais visible par défaut (voir la vue tableau plus bas). ─────────────
    const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());
    const [selectionModeActive, setSelectionModeActive] = useState(false);
    const [suppressionMasseEnCours, setSuppressionMasseEnCours] = useState(false);
    const longPressTimer = useRef<number | null>(null);

    // ── Mode d'affichage des documents d'un dossier : liste (tableau) ou
    // grille (aperçus PDF) — même bascule que "Documents accessibles". ────
    type ViewMode = 'list' | 'grid';
    const [docsViewMode, setDocsViewMode] = useState<ViewMode>('grid');

    // Aperçus PDF pour la vue grille — chargés à la demande, uniquement pour
    // les documents de la page courante et uniquement en vue grille (voir
    // DocumentsAccessible.tsx, même logique). Chaque valeur est une image
    // (data URL PNG de la première page, voir PdfThumbnail.ts).
    const [previews, setPreviews] = useState<Record<string, string>>({});
    const [previewsEnCours, setPreviewsEnCours] = useState<Set<string>>(new Set());
    // Distingue "en cours" de "abandonné après échec" — sans ça, une carte
    // dont l'aperçu échoue (fichier corrompu, erreur réseau...) affichait un
    // spinner qui tournait indéfiniment, indiscernable d'un aperçu réellement
    // encore en train de charger.
    const [previewsEchec, setPreviewsEchec] = useState<Set<string>>(new Set());

    // ── Recherche ─────────────────────────────────────────────────────────────
    const [searchQuery, setSearchQuery] = useState('');
    const [isSearching, setIsSearching] = useState(false);

    // ── Détail document ───────────────────────────────────────────────────────
    const [detail, setDetail]         = useState<DocumentDetailDto | null>(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [isDetailOpen, setIsDetailOpen]   = useState(false);

    // ── Lecteur PDF — intégré à la page (pas un modal) : lectureDoc non-null
    // bascule la vue "documents d'un dossier" vers le lecteur, avec un
    // bouton "Retour" façon fil d'ariane. ───────────────────────────────────
    const [pdfBlobUrl, setPdfBlobUrl]   = useState<string | null>(null);
    const [pdfLoading, setPdfLoading]   = useState(false);
    const [lectureDoc, setLectureDoc]   = useState<DocumentListItemDto | null>(null);

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
        try {
            const data = await getMesFolders();
            setFolders(data);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur chargement dossiers');
        } finally {
            setFoldersLoading(false);
        }
    }, [notify]);

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
        try {
            const result = q && q.trim()
                ? await rechercherDocuments(q.trim(), folder.typeDocumentId, page, 10)
                : await getMesDocumentsByType(folder.typeDocumentId, page, 10);

            setDocuments(result.content);
            setListTotal(result.totalElements);
            setListPages(result.totalPages);
            setListPage(page);

            // Nouvelle page/recherche → les aperçus déjà générés, et toute
            // sélection en cours, ne correspondent plus forcément aux
            // documents affichés.
            setPreviews({});
            setPreviewsEchec(new Set());
            setSelectedDocIds(new Set());
            setSelectionModeActive(false);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur chargement documents');
        } finally {
            setListLoading(false);
        }
    }, [notify]);

    // ─────────────────────────────────────────────────────────────────────────
    // Aperçus PDF pour la vue grille
    // ─────────────────────────────────────────────────────────────────────────

    useEffect(() => {
        if (docsViewMode !== 'grid' || documents.length === 0) return;
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
                // La carte retombe sur un placeholder "aperçu indisponible" —
                // PAS le spinner, qui donnerait l'impression trompeuse que le
                // chargement continue indéfiniment.
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
    }, [docsViewMode, documents]);

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
        setLectureDoc(doc);
        setPdfLoading(true);
        setPdfBlobUrl(null);
        try {
            const url = await streamPdfAAsBlob(doc.documentId);
            setPdfBlobUrl(url);
        } catch {
            setLectureDoc(null);
        } finally {
            setPdfLoading(false);
        }
    };

    const closePdfViewer = () => {
        setLectureDoc(null);
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

    const handleGenererAttestation = async (documentId: string) => {
        setAttestationLoading(true);
        try {
            const dto = await genererAttestation(documentId);
            setAttestationUrl(dto.url);
        } catch (err: any) {
            notify.error(err.response?.data?.message ?? 'Erreur lors de la génération de l\'attestation');
        } finally {
            setAttestationLoading(false);
        }
    };

    const handleEnvoyerCorbeille = async (documentId: string) => {
        if (!(await confirm(
            'Envoyer ce document à la corbeille ? Il sera supprimé définitivement dans 3 jours — '
            + 'vous pourrez le restaurer avant cette échéance.'
        ))) return;

        setSuppressionLoading(true);
        try {
            await envoyerDocumentCorbeille(documentId);
            await openDetailById(documentId); // recharge pour afficher la date planifiée
            loadFolders();
            if (activeFolder) loadDocuments(activeFolder, listPage);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'envoi à la corbeille');
        } finally {
            setSuppressionLoading(false);
        }
    };

    const handleRestaurerCorbeille = async (documentId: string) => {
        setSuppressionLoading(true);
        try {
            await restaurerDocumentDepuisCorbeille(documentId);
            await openDetailById(documentId);
            loadFolders();
            if (activeFolder) loadDocuments(activeFolder, listPage);
            notify.success('Document restauré');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de la restauration');
        } finally {
            setSuppressionLoading(false);
        }
    };

    // Envoi à la corbeille depuis la liste (grille ou tableau) — contrairement
    // à handleEnvoyerCorbeille (déclenché depuis le détail déjà ouvert), ne
    // rouvre pas le détail : juste rafraîchir la liste sur place.
    const handleEnvoyerCorbeilleRapide = async (doc: DocumentListItemDto) => {
        if (!(await confirm(
            `Envoyer "${doc.titre}" à la corbeille ? Il sera supprimé définitivement dans 3 jours — `
            + 'vous pourrez le restaurer avant cette échéance.'
        ))) return;

        try {
            await envoyerDocumentCorbeille(doc.documentId);
            notify.success(`"${doc.titre}" envoyé à la corbeille`);
            setSelectedDocIds(prev => {
                const next = new Set(prev);
                next.delete(doc.documentId);
                return next;
            });
            loadFolders();
            if (activeFolder) loadDocuments(activeFolder, listPage);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'envoi à la corbeille');
        }
    };

    const toggleDocSelection = (documentId: string) => {
        setSelectedDocIds(prev => {
            const next = new Set(prev);
            if (next.has(documentId)) next.delete(documentId); else next.add(documentId);
            // Plus rien coché → on quitte le mode sélection tout seul, pas
            // besoin de rester avec des cases vides à l'écran.
            if (next.size === 0) setSelectionModeActive(false);
            return next;
        });
    };

    // Coche/décoche tous les documents sélectionnables de la page courante —
    // seuls ceux avec peutGererCorbeille peuvent être envoyés à la corbeille,
    // les autres ne sont jamais inclus dans la sélection.
    const toggleSelectAllDocs = () => {
        const selectionnables = documents.filter(d => d.peutGererCorbeille).map(d => d.documentId);
        setSelectedDocIds(prev => {
            const toutCoche = selectionnables.length > 0 && selectionnables.every(id => prev.has(id));
            if (toutCoche) setSelectionModeActive(false);
            return toutCoche ? new Set() : new Set(selectionnables);
        });
    };

    // Active le mode sélection (cases à cocher visibles) — déclenché par un
    // clic droit ou un appui prolongé sur une ligne, jamais par défaut.
    const activateSelectionMode = (documentId: string) => {
        setSelectionModeActive(true);
        setSelectedDocIds(prev => new Set(prev).add(documentId));
    };

    const annulerSelection = () => {
        setSelectedDocIds(new Set());
        setSelectionModeActive(false);
    };

    const LONG_PRESS_MS = 500;

    const handleRowTouchStart = (documentId: string) => {
        if (longPressTimer.current) window.clearTimeout(longPressTimer.current);
        longPressTimer.current = window.setTimeout(() => {
            activateSelectionMode(documentId);
            longPressTimer.current = null;
        }, LONG_PRESS_MS);
    };

    const handleRowTouchEnd = () => {
        if (longPressTimer.current) {
            window.clearTimeout(longPressTimer.current);
            longPressTimer.current = null;
        }
    };

    const handleEnvoyerCorbeilleMasse = async () => {
        const ids = Array.from(selectedDocIds);
        if (ids.length === 0) return;

        if (!(await confirm(
            `Envoyer ${ids.length} document${ids.length > 1 ? 's' : ''} à la corbeille ? `
            + `Ils seront supprimés définitivement dans 3 jours — vous pourrez les restaurer avant cette échéance.`
        ))) return;

        setSuppressionMasseEnCours(true);
        try {
            const resultats = await Promise.allSettled(ids.map(id => envoyerDocumentCorbeille(id)));
            const succes = resultats.filter(r => r.status === 'fulfilled').length;
            const echecs = resultats.length - succes;

            resultats.forEach((r, i) => {
                if (r.status === 'rejected') {
                    console.error(`Échec de l'envoi à la corbeille pour ${ids[i]} :`, r.reason);
                }
            });

            if (succes > 0) {
                notify.success(`${succes} document${succes > 1 ? 's' : ''} envoyé${succes > 1 ? 's' : ''} à la corbeille`);
            }
            if (echecs > 0) {
                notify.error(`${echecs} échec${echecs > 1 ? 's' : ''} sur ${ids.length} — voir la console pour le détail`);
            }

            annulerSelection();
            loadFolders();
            if (activeFolder) loadDocuments(activeFolder, listPage);
        } finally {
            setSuppressionMasseEnCours(false);
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
        || (typesAvecDocumentTrouve?.has(f.typeDocumentId) ?? false)
    );
    const displayedFolders = folderSearch ? visibleFolders : visibleFolders.slice(0, 10);
    const hasMore = !folderSearch && folders.length > 10;
    const rechercheSansResultat = !!folderSearch.trim() && !rechercheContenuEnCours
        && typesAvecDocumentTrouve !== null && visibleFolders.length === 0;

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU — Vue Grille de dossiers
    // ─────────────────────────────────────────────────────────────────────────

    if (view === 'folders') {
        return (
            <div className="mes-docs-wrapper">
                <div className="mes-docs-header">
                    <h2 className="mes-docs-title">Mes documents</h2>
                    {folders.length > 0 && (
                        <div className="folder-search-bar">
                            <i className={`fa-solid ${rechercheContenuEnCours ? 'fa-spinner fa-spin' : 'fa-magnifying-glass'} folder-search-icon`} />
                            <input
                                type="text"
                                className="filter-input folder-search-input"
                                placeholder="Rechercher un type ou un contenu de document..."
                                value={folderSearch}
                                onChange={e => setFolderSearch(e.target.value)}
                            />
                            {folderSearch && (
                                <button
                                    type="button"
                                    className="search-clear-btn folder-search-clear"
                                    onClick={() => setFolderSearch('')}
                                    aria-label="Effacer la recherche"
                                >
                                    <i className="fa-solid fa-xmark" />
                                </button>
                            )}
                        </div>
                    )}
                </div>

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
                ) : rechercheSansResultat ? (
                    <div className="td-empty">
                        <i className="fa-solid fa-magnifying-glass" style={{ fontSize: '2.5rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }} />
                        <p>Aucun type ni document ne correspond à «{folderSearch}».</p>
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

                                    {/* Icône dossier — reproduit le modèle de référence fourni : un
                                        dossier plein (onglet + pochette arrière), une page nette qui
                                        dépasse par-dessus, puis une pochette en VRAI verre dépoli
                                        (backdrop-filter) posée par-dessus le bas de l'ensemble — c'est
                                        elle qui floute optiquement ce qu'il y a DERRIÈRE elle (le
                                        dossier, le bas de la page), pas un flou appliqué à l'icône
                                        elle-même. Structure DOM empilée, pas du SVG : backdrop-filter
                                        a besoin d'un vrai contenu en dessous pour avoir quelque chose
                                        à flouter. */}
                                    <div className="folder-icon-wrap">
                                        <div className="folder-tab" />
                                        <div className="folder-back" />
                                        <div className="document-sheet">
                                            <div className="doc-line short" />
                                            <div className="doc-line" />
                                            <div className="doc-line" />
                                        </div>
                                        <div className="glass-pocket" />

                                        {/* Compteur — pastille en coin */}
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

                {/* Modal upload depuis dossier — même composant que "Archiver" dans
                    la barre latérale (voir EditorDasboard.tsx), pour une interface
                    identique partout ; seule différence volontaire : le type du
                    dossier courant est pré-rempli, mais reste modifiable. */}
                <Modal
                    isOpen={isUploadOpen}
                    onClose={closeUpload}
                    title="Ajouter un document"
                    size="large"
                >
                    <ImportDocuments
                        onsuccess={handleUploadSuccess}
                        preselectedTypeId={uploadTypeId}
                    />
                </Modal>
            </div>
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU — Lecture d'un document, intégrée à la page (pas un modal)
    // ─────────────────────────────────────────────────────────────────────────

    if (lectureDoc) {
        return (
            <div className="mes-docs-wrapper">
                <div className="docs-breadcrumb">
                    <button className="breadcrumb-back" onClick={closePdfViewer}>
                        <i className="fa-solid fa-arrow-left" /> Retour
                    </button>
                    <i className="fa-solid fa-chevron-right breadcrumb-sep" />
                    <span className="breadcrumb-current">{lectureDoc.titre}</span>
                </div>
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
                    style={{ background: activeFolder ? FOLDER_GLASS_COLOR : '#ccc' }}
                />
                <span className="breadcrumb-current">
                    {activeFolder?.typeDocumentNom}
                </span>
                <span className="breadcrumb-count">
                    ({listTotal} document{listTotal > 1 ? 's' : ''})
                </span>

                {/* Bascule liste / grille */}
                <div className="docs-view-toggle" role="group" aria-label="Mode d'affichage">
                    <button
                        type="button"
                        className={`view-toggle-btn ${docsViewMode === 'list' ? 'active' : ''}`}
                        onClick={() => setDocsViewMode('list')}
                        title="Vue liste"
                        aria-label="Afficher en liste"
                    >
                        <i className="fa-solid fa-list" />
                    </button>
                    <button
                        type="button"
                        className={`view-toggle-btn ${docsViewMode === 'grid' ? 'active' : ''}`}
                        onClick={() => setDocsViewMode('grid')}
                        title="Vue grille"
                        aria-label="Afficher en grille"
                    >
                        <i className="fa-solid fa-table-cells-large" />
                    </button>
                </div>

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

            {/* ── Barre de sélection multiple — n'apparaît que si au moins un
                document est coché. ────────────────────────────────────────── */}
            {selectedDocIds.size > 0 && (
                <div className="selection-toolbar">
                    <span className="selection-toolbar-count">
                        {selectedDocIds.size} document{selectedDocIds.size > 1 ? 's' : ''} sélectionné{selectedDocIds.size > 1 ? 's' : ''}
                    </span>
                    <button
                        type="button"
                        className="details-close-btn"
                        onClick={annulerSelection}
                    >
                        Annuler la sélection
                    </button>
                    <button
                        type="button"
                        className="action-button delete"
                        onClick={handleEnvoyerCorbeilleMasse}
                        disabled={suppressionMasseEnCours}
                    >
                        {suppressionMasseEnCours
                            ? <><i className="fa-solid fa-spinner fa-spin" /> Envoi…</>
                            : <><i className="fa-solid fa-trash" /> Envoyer à la corbeille</>}
                    </button>
                </div>
            )}

            {/* ── Documents du dossier (grille ou table selon docsViewMode) ── */}
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
            ) : docsViewMode === 'grid' ? (
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
                                    {doc.peutGererCorbeille && (
                                        <input
                                            type="checkbox"
                                            className="doc-grid-select"
                                            checked={selectedDocIds.has(doc.documentId)}
                                            onClick={e => e.stopPropagation()}
                                            onChange={() => toggleDocSelection(doc.documentId)}
                                            aria-label={`Sélectionner ${doc.titre}`}
                                        />
                                    )}
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
                                    {/* Statut, accès et date d'archivage volontairement absents ici —
                                        déjà visibles dans le détail (bouton "i" ci-dessous). */}

                                    <div className="td-actions doc-grid-actions">
                                        <button
                                            className="action-button edit"
                                            onClick={() => openDetail(doc)}
                                            title="Détail et métadonnées"
                                        >
                                            <i className="fa-solid fa-circle-info" />
                                        </button>
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
                                        {(!doc.versionLabel || doc.versionLabel === 'Final') && (
                                            <button
                                                className="action-button"
                                                onClick={() => openNewVersion(doc)}
                                                title="Déposer une nouvelle version"
                                            >
                                                <i className="fa-solid fa-code-branch" />
                                            </button>
                                        )}
                                        {doc.peutGererCorbeille && (
                                            <button
                                                className="action-button delete"
                                                onClick={() => handleEnvoyerCorbeilleRapide(doc)}
                                                title="Envoyer à la corbeille"
                                            >
                                                <i className="fa-solid fa-trash" />
                                            </button>
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
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
            ) : (
                <>
                    <div className="td-table-container">
                        <table className="td-table">
                            <thead>
                                <tr>
                                    {selectionModeActive && (
                                        <th className="td-select-col">
                                            {documents.some(d => d.peutGererCorbeille) && (
                                                <input
                                                    type="checkbox"
                                                    checked={
                                                        documents.some(d => d.peutGererCorbeille)
                                                        && documents.filter(d => d.peutGererCorbeille).every(d => selectedDocIds.has(d.documentId))
                                                    }
                                                    onChange={toggleSelectAllDocs}
                                                    onClick={e => e.stopPropagation()}
                                                    aria-label="Tout sélectionner"
                                                    title="Tout sélectionner"
                                                />
                                            )}
                                        </th>
                                    )}
                                    <th>Titre</th>
                                    <th>Accès</th>
                                    <th>Statut</th>
                                    <th>Archivé le</th>
                                    <th>Rétention</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {/* Cases à cocher jamais visibles par défaut — seulement après
                                    un clic droit ou un appui prolongé sur une ligne (voir
                                    activateSelectionMode). Double-clic pour lire un document,
                                    plus d'icône "œil" dédiée. */}
                                {documents.map(doc => (
                                    <tr
                                        key={doc.documentId}
                                        className={selectionModeActive ? 'td-row-selectable' : undefined}
                                        onDoubleClick={() => openPdfViewer(doc)}
                                        onClick={() => { if (selectionModeActive) toggleDocSelection(doc.documentId); }}
                                        onContextMenu={e => { e.preventDefault(); activateSelectionMode(doc.documentId); }}
                                        onTouchStart={() => handleRowTouchStart(doc.documentId)}
                                        onTouchEnd={handleRowTouchEnd}
                                        onTouchMove={handleRowTouchEnd}
                                    >
                                        {selectionModeActive && (
                                            <td className="td-select-col" onClick={e => e.stopPropagation()}>
                                                {doc.peutGererCorbeille && (
                                                    <input
                                                        type="checkbox"
                                                        checked={selectedDocIds.has(doc.documentId)}
                                                        onChange={() => toggleDocSelection(doc.documentId)}
                                                        aria-label={`Sélectionner ${doc.titre}`}
                                                    />
                                                )}
                                            </td>
                                        )}
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
                                        <td>{doc.retentionUntil ? formatDate(doc.retentionUntil) : 'Indéfinie'}</td>
                                        <td onClick={e => e.stopPropagation()} onDoubleClick={e => e.stopPropagation()}>
                                            <div className="td-actions">
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

                                                {/* Envoyer à la corbeille */}
                                                {doc.peutGererCorbeille && (
                                                    <button
                                                        className="action-button delete"
                                                        onClick={() => handleEnvoyerCorbeilleRapide(doc)}
                                                        title="Envoyer à la corbeille"
                                                    >
                                                        <i className="fa-solid fa-trash" />
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
                        onSupprimer={handleEnvoyerCorbeille}
                        onRestaurer={handleRestaurerCorbeille}
                        suppressionLoading={suppressionLoading}
                        onGenererAttestation={handleGenererAttestation}
                        attestationUrl={attestationUrl}
                        attestationLoading={attestationLoading}
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

            {/* ── Modal upload — TOUJOURS le même composant ImportDocuments,
                que ce soit "Archiver" (barre latérale), le "+" d'un dossier,
                ou "Déposer une nouvelle version" : une seule interface
                d'import dans toute l'application. versionSource bascule
                juste son mode interne (type verrouillé, un seul fichier,
                pas de "Lien"/dossier entier) via la prop precedentDocument —
                voir ImportDocuments.tsx pour le détail de cette adaptation. */}
            <Modal
                isOpen={isUploadOpen}
                onClose={closeUpload}
                title={versionSource ? 'Déposer une nouvelle version' : 'Ajouter un document'}
                size="large"
            >
                <ImportDocuments
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
    onRestaurer,
    suppressionLoading,
    onGenererAttestation,
    attestationUrl,
    attestationLoading,
    onEmplacementChange,
}: {
    detail: DocumentDetailDto;
    onSelectVersion?: (documentId: string) => void;
    onRemplacer?: (detail: DocumentDetailDto) => void;
    onSupprimer?: (documentId: string) => void;
    onRestaurer?: (documentId: string) => void;
    suppressionLoading?: boolean;
    onGenererAttestation?: (documentId: string) => void;
    attestationUrl?: string | null;
    attestationLoading?: boolean;
    onEmplacementChange?: (updated: DocumentDetailDto) => void;
}) {
    const STATUS_LABELS: Record<string, string> = {
        ACTIVE: 'Actif', PENDING: 'En attente',
        ACTIVE_WARNING: 'Avertissement', CORRUPTED: 'Corrompu', CORBEILLE: 'Dans la corbeille', DELETED: 'Supprimé',
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

            {/* Dans la corbeille — restaurable jusqu'à la purge définitive.
                Reste identifiable comme "corrompu" s'il l'était avant d'y être
                envoyé (statutAvantCorbeille), badge inclus. */}
            {detail.status === 'CORBEILLE' && (
                <div className="corruption-banner">
                    <div className="corruption-banner-header">
                        <i className="fa-solid fa-trash" />
                        <span>
                            Document dans la corbeille
                            {detail.statutAvantCorbeille === 'CORRUPTED' && ' — corrompu'}
                            {detail.corruptionRaison ? ` (${detail.corruptionRaison})` : ''}
                        </span>
                    </div>
                    <p className="corruption-banner-note">
                        Seuls les administrateurs ayant autorité sur son UO et les éditeurs y ayant accès
                        peuvent encore consulter ou télécharger ce document.
                    </p>
                    {detail.suppressionPrevueLe && (
                        <p className="corruption-banner-suppression">
                            <i className="fa-solid fa-clock" /> Suppression définitive prévue le{' '}
                            {new Date(detail.suppressionPrevueLe).toLocaleDateString('fr-FR')}.
                        </p>
                    )}
                    {detail.peutGererCorbeille && (
                        <div className="corruption-banner-actions">
                            <button
                                type="button"
                                className="form-submit-btn up-submit"
                                onClick={() => onRestaurer?.(detail.documentId)}
                                disabled={suppressionLoading}
                            >
                                {suppressionLoading
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> …</>
                                    : <><i className="fa-solid fa-clock-rotate-left" /> Restaurer</>}
                            </button>
                        </div>
                    )}
                </div>
            )}

            {/* Corrompu, pas encore envoyé à la corbeille. */}
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

                    {detail.peutGererCorbeille && (
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
                                    : <><i className="fa-solid fa-trash" /> Envoyer à la corbeille</>}
                            </button>
                        </div>
                    )}
                </div>
            )}

            {/* Document sain, pas dans la corbeille — suppression volontaire
                disponible pour n'importe quel document, plus seulement un
                corrompu. */}
            {detail.status !== 'CORRUPTED' && detail.status !== 'CORBEILLE' && detail.peutGererCorbeille && (
                <div className="details-row">
                    <button
                        type="button"
                        className="corruption-delete-btn"
                        onClick={() => onSupprimer?.(detail.documentId)}
                        disabled={suppressionLoading}
                    >
                        {suppressionLoading
                            ? <><i className="fa-solid fa-spinner fa-spin" /> …</>
                            : <><i className="fa-solid fa-trash" /> Envoyer à la corbeille</>}
                    </button>
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
                <strong>Rétention jusqu'au :</strong> {detail.retentionUntil ?? 'Indéfinie'}
            </div>
            <div className="details-row">
                <strong>Version :</strong> {detail.version}
            </div>

            <EmplacementPhysiqueSection detail={detail} onUpdated={onEmplacementChange} />

            <ProjetAttachSection detail={detail} onUpdated={onEmplacementChange} />

            {detail.pdfaSha256 && (
                <div className="details-row">
                    <strong>Hash PDF/A :</strong>
                    <span className="up-hash">{detail.pdfaSha256.slice(0, 16)}…</span>
                </div>
            )}

            <MetaDataEditSection
                detail={detail}
                peutModifier={detail.peutModifierEmplacement}
                onUpdated={onEmplacementChange}
            />

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
    const notify = useNotify();

    const ouvrirEdition = async () => {
        setEditing(true);
        setSelected(detail.physicalLocationId ?? '');
        if (detail.uniteOrganisationnelleId == null) return;
        setOptionsLoading(true);
        try {
            const data = await getEmplacementsDisponibles(detail.uniteOrganisationnelleId);
            setOptions(data);
        } catch {
            notify.error('Impossible de charger les emplacements disponibles');
        } finally {
            setOptionsLoading(false);
        }
    };

    const enregistrer = async () => {
        setSaving(true);
        try {
            const updated = await modifierEmplacementPhysique(detail.documentId, selected || null);
            onUpdated?.(updated);
            setEditing(false);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'enregistrement');
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

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : projet (rattacher, migrer, détacher un document après coup)
// ─────────────────────────────────────────────────────────────────────────────

function ProjetAttachSection({
    detail,
    onUpdated,
}: {
    detail: DocumentDetailDto;
    onUpdated?: (updated: DocumentDetailDto) => void;
}) {
    const [editing, setEditing] = useState(false);
    const [options, setOptions] = useState<ProjetDto[]>([]);
    const [optionsLoading, setOptionsLoading] = useState(false);
    const [selected, setSelected] = useState('');
    const [saving, setSaving] = useState(false);
    const notify = useNotify();
    const confirm = useConfirm();

    const ouvrirEdition = async () => {
        setEditing(true);
        setSelected(detail.projetId ? String(detail.projetId) : '');
        if (detail.uniteOrganisationnelleId == null) return;
        setOptionsLoading(true);
        try {
            const data = await getProjetsDeUO(detail.uniteOrganisationnelleId);
            setOptions(data);
        } catch {
            notify.error('Impossible de charger les projets disponibles');
        } finally {
            setOptionsLoading(false);
        }
    };

    const enregistrer = async () => {
        const projetIdSelectionne = selected ? Number(selected) : null;
        let fusionnerGroupes = false;

        // Document privé rattaché à un projet privé : vérifier AVANT
        // d'écrire si les deux groupes diffèrent, pour avertir l'éditeur
        // qu'un rattachement les fusionnera (union des membres, lien
        // permanent — voir DocumentService.modifierProjetDocument).
        if (projetIdSelectionne && detail.access === 'PRIVE') {
            try {
                const verif = await verifierFusionGroupeProjet(detail.documentId, projetIdSelectionne);
                if (verif.groupesDifferents) {
                    const liste = verif.membresQuiSerontAjoutes.join(', ');
                    const accepte = await confirm(
                        'Le groupe de ce document et celui du projet n\'ont pas les mêmes membres. '
                        + 'En continuant, les deux groupes seront fusionnés (union des membres)'
                        + (liste ? ` — ${liste} sera${verif.membresQuiSerontAjoutes.length > 1 ? 'ont' : ''} `
                            + `ajouté${verif.membresQuiSerontAjoutes.length > 1 ? 's' : ''} au groupe du projet.` : '.')
                    );
                    if (!accepte) return;
                    fusionnerGroupes = true;
                }
            } catch (err: any) {
                notify.error(err.message ?? 'Erreur lors de la vérification des groupes');
                return;
            }
        }

        setSaving(true);
        try {
            const updated = await modifierProjetDocument(
                detail.documentId, projetIdSelectionne, fusionnerGroupes
            );
            onUpdated?.(updated);
            setEditing(false);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    if (!detail.peutModifierProjet && !detail.projetNom) {
        return null;
    }

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Projet :</strong>
            {editing ? (
                <div className="emplacement-edit">
                    {optionsLoading ? (
                        <i className="fa-solid fa-spinner fa-spin" />
                    ) : (
                        <select value={selected} onChange={(e) => setSelected(e.target.value)}>
                            <option value="">— Aucun (hors projet) —</option>
                            {options.map((p) => (
                                <option key={p.id} value={p.id}>{p.nom}</option>
                            ))}
                        </select>
                    )}
                    <button type="button" className="attestation-generer-btn" disabled={saving} onClick={enregistrer}>
                        {saving ? '…' : 'Enregistrer'}
                    </button>
                    <button type="button" className="details-close-btn" onClick={() => setEditing(false)}>Annuler</button>
                </div>
            ) : (
                <>
                    <span>{detail.projetNom ?? '—'}</span>
                    {detail.peutModifierProjet && (
                        <button type="button" className="details-close-btn" onClick={ouvrirEdition}>
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                </>
            )}
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : métadonnées (affichage + correction)
// ─────────────────────────────────────────────────────────────────────────────

function MetaDataEditSection({
    detail,
    peutModifier,
    onUpdated,
}: {
    detail: DocumentDetailDto;
    peutModifier?: boolean;
    onUpdated?: (updated: DocumentDetailDto) => void;
}) {
    const [editing, setEditing] = useState(false);
    const [typeDef, setTypeDef] = useState<TypeDocumentDto | null>(null);
    const [loadingType, setLoadingType] = useState(false);
    const [values, setValues] = useState<Record<string, string>>({});
    const [saving, setSaving] = useState(false);
    const notify = useNotify();

    const ouvrirEdition = async () => {
        setEditing(true);
        setLoadingType(true);
        try {
            const td = await getTypeDocumentById(detail.typeDocumentId);
            setTypeDef(td);
            // detail.metaData[i].typeValeur porte le NOM du champ (voir
            // DocumentService.getDetail côté serveur), pas son type — on
            // s'en sert ici pour retrouver la valeur actuelle de chaque champ.
            const seed: Record<string, string> = {};
            td.metaData.forEach(m => {
                const actuel = detail.metaData.find(dm => dm.typeValeur === m.nom);
                seed[m.nom] = actuel?.valeur ?? '';
            });
            setValues(seed);
        } catch {
            notify.error('Impossible de charger la définition des métadonnées de ce type');
        } finally {
            setLoadingType(false);
        }
    };

    const enregistrer = async () => {
        if (!typeDef) return;
        setSaving(true);
        try {
            const payload = typeDef.metaData.map(m => ({ nom: m.nom, valeur: values[m.nom] ?? '' }));
            const updated = await modifierMetaDataDocument(detail.documentId, payload);
            onUpdated?.(updated);
            setEditing(false);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    if (!editing) {
        if (detail.metaData.length === 0 && !peutModifier) return null;
        return (
            <div className="detail-meta-section">
                <div className="detail-meta-header-row">
                    <p className="detail-meta-title">Métadonnées</p>
                    {peutModifier && (
                        <button type="button" className="details-close-btn" onClick={ouvrirEdition}>
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                </div>
                {detail.metaData.length > 0 ? (
                    <div className="detail-meta-grid">
                        {detail.metaData.map((m, i) => (
                            <div key={i} className="detail-meta-item">
                                <span className="detail-meta-type">{m.typeValeur}</span>
                                <span className="detail-meta-value">{m.valeur ?? '—'}</span>
                            </div>
                        ))}
                    </div>
                ) : (
                    <p className="td-detail-empty">Aucune métadonnée renseignée.</p>
                )}
            </div>
        );
    }

    return (
        <div className="detail-meta-section">
            <p className="detail-meta-title">Modifier les métadonnées</p>
            {loadingType ? (
                <i className="fa-solid fa-spinner fa-spin" />
            ) : typeDef ? (
                <div className="meta-fields">
                    {typeDef.metaData.map(m => (
                        <MetaDataField
                            key={m.nom}
                            nom={m.nom}
                            type={m.metaDataType}
                            obligatoire={m.obligatoire}
                            value={values[m.nom] ?? ''}
                            onChange={v => setValues(prev => ({ ...prev, [m.nom]: v }))}
                        />
                    ))}
                </div>
            ) : null}
            <div className="pl-form-actions">
                <button type="button" className="attestation-generer-btn" disabled={saving} onClick={enregistrer}>
                    {saving ? 'Enregistrement…' : 'Enregistrer'}
                </button>
                <button type="button" className="details-close-btn" onClick={() => setEditing(false)}>Annuler</button>
            </div>
        </div>
    );
}

export default MesDocumentsEditor;