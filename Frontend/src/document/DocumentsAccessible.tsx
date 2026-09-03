import { useState, useEffect, useCallback, useRef } from 'react';
import {
    getDocumentsAccessibles,
    streamPdfAAsBlob,
    getDocumentDetail,
    downloadPdfA,
    envoyerDocumentCorbeille,
    restaurerDocumentDepuisCorbeille
} from '../services/document/DocumentService';
import { genererAttestation } from '../services/document/AttestationService';
import { modifierEmplacementPhysique, modifierMetaDataDocument, modifierProjetDocument, verifierFusionGroupeProjet, getTypeDocumentById } from '../services/document/DocumentService';
import type { TypeDocumentDto as TypeDocumentEditorDto } from '../services/document/DocumentService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import { getProjetsDeUO } from '../services/organisation/ProjetService';
import type { ProjetDto } from '../services/organisation/ProjetService';
import MetaDataField from './MetadaField';
import type { DocumentListItemDto, DocumentDetailDto } from '../services/document/DocumentService';
import { getTypeDocumentsVisibles } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import Modal from '../Page/Modal';
import VersionBadge from './VersionBadge';
import GestionGroupe from './GestionGroupe';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import { renderPdfFirstPageThumbnail } from '../services/document/PdfThumbnail';
import '../Style/document/Filtre.css';
import '../Style/Admin/DocumentsArchivesPanel.css';
// .docs-breadcrumb / .breadcrumb-back / .pdf-viewer-wrapper (lecteur PDF
// intégré à la page, voir plus bas) — garanti disponible quel que soit le
// tableau de bord qui monte ce composant.
import '../Style/Editor/Editor.css';

interface DocumentsAccessiblesProps {
    uoId?: number | null;
}

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
    const notify = useNotify();
    const confirm = useConfirm();
    // ── Données ───────────────────────────────────────────────────────────
    const [documents, setDocuments]     = useState<DocumentListItemDto[]>([]);
    const [totalElements, setTotal]     = useState(0);
    const [totalPages, setTotalPages]   = useState(1);
    const [page, setPage]               = useState(1);

    // ── Sélection multiple — pour l'envoi en masse à la corbeille. Ne
    // retient que des documents réellement gérables (peutGererCorbeille) ;
    // vidée à chaque changement de page/filtre pour éviter une sélection
    // fantôme sur des documents qui ne sont plus affichés. Les cases à
    // cocher ne s'affichent QUE quand selectionModeActive est vrai — activé
    // par un clic droit (PC) ou un appui prolongé (tactile) sur une ligne,
    // jamais visible par défaut (voir la vue tableau plus bas). ─────────────
    const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());
    const [selectionModeActive, setSelectionModeActive] = useState(false);
    const [suppressionMasseEnCours, setSuppressionMasseEnCours] = useState(false);
    const longPressTimer = useRef<number | null>(null);

    // ── Filtres ───────────────────────────────────────────────────────────
    // Une seule source de vérité — la recherche se déclenche toute seule
    // (debounce) à chaque changement, plus de distinction brouillon/appliqué
    // ni de bouton "Appliquer".
    const [filtres, setFiltres]         = useState<Filtres>(FILTRES_VIDES);
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);

    // ── États UI ──────────────────────────────────────────────────────────
    const [isLoading, setIsLoading]     = useState(false);
    const [filtresOuverts, setFiltresOuverts] = useState(true);

    // ── Mode d'affichage : liste (tableau) ou grille (aperçus PDF) ────────
    type ViewMode = 'list' | 'grid';
    const [viewMode, setViewMode] = useState<ViewMode>('list');

    // ── Aperçus PDF pour la vue grille — chargés à la demande, uniquement
    // pour les documents de la page courante et uniquement en vue grille
    // (inutile de payer le coût réseau/rendu d'un aperçu qu'on n'affiche
    // jamais en vue liste). Chaque valeur est une image (data URL PNG de la
    // première page, voir PdfThumbnail.ts) — rien à révoquer explicitement,
    // contrairement à un blob URL.
    const [previews, setPreviews] = useState<Record<string, string>>({});
    const [previewsEnCours, setPreviewsEnCours] = useState<Set<string>>(new Set());
    // Distingue "en cours" de "abandonné après échec" — sans ça, une carte
    // dont l'aperçu échoue (fichier corrompu, erreur réseau...) affichait un
    // spinner qui tournait indéfiniment, indiscernable d'un aperçu réellement
    // encore en train de charger.
    const [previewsEchec, setPreviewsEchec] = useState<Set<string>>(new Set());

    // ── Détail ────────────────────────────────────────────────────────────
    const [detail, setDetail]           = useState<DocumentDetailDto | null>(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [isDetailOpen, setIsDetailOpen]   = useState(false);

    // ── Lecteur PDF — intégré à la page (pas un modal), voir le rendu plus
    // bas : lectureDoc non-null bascule toute la vue vers le lecteur, avec
    // un bouton "Retour" façon fil d'ariane, même principe que la
    // navigation dossier→documents déjà en place ailleurs dans l'app. ────
    const [pdfBlobUrl, setPdfBlobUrl]   = useState<string | null>(null);
    const [pdfLoading, setPdfLoading]   = useState(false);
    const [lectureDoc, setLectureDoc]   = useState<DocumentListItemDto | null>(null);

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

    // ─────────────────────────────────────────────────────────────────────
    // Chargement des types pour le select
    // ─────────────────────────────────────────────────────────────────────

    useEffect(() => {
        getTypeDocumentsVisibles(uoId)
            .then(setTypeDocuments)
            .catch(err => notify.error(err.message ?? 'Erreur chargement des types de documents'));
    }, [uoId, notify]);

    // ─────────────────────────────────────────────────────────────────────
    // Chargement des documents
    // ─────────────────────────────────────────────────────────────────────

    const loadDocuments = useCallback(async (f: Filtres, p: number) => {
        setIsLoading(true);
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

            // Nouvelle page/filtre → les aperçus déjà générés, et toute
            // sélection en cours, ne correspondent plus forcément aux
            // documents affichés ; on les vide et on laisse l'effet de la
            // vue grille en régénérer au besoin.
            setPreviews({});
            setPreviewsEchec(new Set());
            setSelectedDocIds(new Set());
            setSelectionModeActive(false);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur chargement');
        } finally {
            setIsLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId]);

    // ─────────────────────────────────────────────────────────────────────
    // Aperçus PDF pour la vue grille
    // ─────────────────────────────────────────────────────────────────────

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
                // blob: intermédiaire — sert uniquement de source à pdf.js pour
                // rasteriser la première page, jamais affiché tel quel (voir
                // PdfThumbnail.ts : pas de chrome de lecteur PDF natif en grille).
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
    }, [viewMode, documents]);

    // Chargement initial + à chaque changement de filtre (saisie, select,
    // date...) ou d'UO sélectionnée (navigation Admin/Admin_UO) — un léger
    // debounce évite une requête par caractère tapé, même pattern que la
    // recherche de "Mes documents".
    useEffect(() => {
        const timer = setTimeout(() => {
            loadDocuments(filtres, 1);
        }, 300);
        return () => clearTimeout(timer);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [loadDocuments, filtres]);
    // Document archivé/modifié depuis une autre interface pendant qu'on reste
    // sur cet écran → rechargé (filtres/page courants) au retour de focus.
    useRefetchOnFocus(useCallback(() => loadDocuments(filtres, page), [loadDocuments, filtres, page]));

    // ─────────────────────────────────────────────────────────────────────
    // Handlers filtres
    // ─────────────────────────────────────────────────────────────────────

    const handleFiltreChange = (key: keyof Filtres, value: string) => {
        setFiltres(prev => ({ ...prev, [key]: value }));
    };

    const reinitialiserFiltres = () => {
        setFiltres(FILTRES_VIDES);
    };

    const nbFiltresActifs = Object.values(filtres).filter(v => v !== '').length;

    // ─────────────────────────────────────────────────────────────────────
    // Lecteur PDF
    // ─────────────────────────────────────────────────────────────────────

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
        try {
            const dto = await genererAttestation(documentId);
            setAttestationUrl(dto.url);
        } catch (err: any) {
            notify.error(err.response?.data?.message ?? 'Erreur lors de la génération de l\'attestation');
        } finally {
            setAttestationLoading(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Corbeille — suppression volontaire (délai de grâce de 3 jours, restaurable)
    // ─────────────────────────────────────────────────────────────────────

    const handleEnvoyerCorbeille = async (documentId: string) => {
        if (!(await confirm(
            'Envoyer ce document à la corbeille ? Il sera supprimé définitivement dans 3 jours — '
            + 'vous pourrez le restaurer avant cette échéance.'
        ))) return;

        setSuppressionLoading(true);
        try {
            await envoyerDocumentCorbeille(documentId);
            await openDetailById(documentId); // recharge pour afficher la date planifiée
            loadDocuments(filtres, page);
            notify.success('Document envoyé à la corbeille');
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
            loadDocuments(filtres, page);
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
            loadDocuments(filtres, page);
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
            loadDocuments(filtres, page);
        } finally {
            setSuppressionMasseEnCours(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // RENDU
    // ─────────────────────────────────────────────────────────────────────

    // ── Lecture d'un document — intégrée à la page, pas un modal ──────────
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

    return (
        <div className="mes-docs-wrapper">

            {/* ── En-tête ── */}
            <div className="mes-docs-header">
                <h2 className="mes-docs-title">Documents accessibles</h2>
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
            </div>

            {/* ── Panneau filtres — recherche live, pas de bouton "Appliquer" :
                 chaque changement (saisie, select, date) relance la recherche
                 tout seul (debounce, voir l'effet sur `filtres`). ── */}
            {filtresOuverts && (
                <div className="filtres-panel">
                    <div className="filtres-grid">

                        {/* Titre + contenu (recherche plein texte via Meilisearch côté
                            serveur, voir DocumentAccessService.rechercherIdsMeilisearch) */}
                        <div className="filtre-field filtre-field-titre">
                            <input
                                type="text"
                                className="filter-input"
                                placeholder="Titre ou contenu du document"
                                aria-label="Filtrer par titre ou contenu"
                                value={filtres.titre}
                                onChange={e => handleFiltreChange('titre', e.target.value)}
                            />
                        </div>

                        {/* Type de document */}
                        <div className="filtre-field">
                            <select
                                id="typeDocumentSelect"
                                className="filter-input"
                                aria-label="Filtrer par type de document"
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
                            <select
                                id="acces-select"
                                className="filter-input"
                                aria-label="Filtrer par accès"
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
                            <select
                                id="statut-select"
                                className="filter-input"
                                aria-label="Filtrer par statut"
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
                        <div className="filtre-field filtre-field-date">
                            <input
                                id="date-debut"
                                type={filtres.dateDebut ? 'date' : 'text'}
                                placeholder="Archivé depuis"
                                aria-label="Archivé depuis"
                                className="filter-input"
                                value={filtres.dateDebut}
                                max={filtres.dateFin || undefined}
                                onChange={e => handleFiltreChange('dateDebut', e.target.value)}
                                onFocus={e => {
                                    e.target.type = 'date';
                                    try { e.target.showPicker?.(); } catch { /* geste utilisateur requis */ }
                                }}
                                onBlur={e => { if (!e.target.value) e.target.type = 'text'; }}
                            />
                        </div>
                        
                        {/* Date fin */}
                        <div className="filtre-field filtre-field-date">
                            <input
                                id="date-fin"
                                type={filtres.dateFin ? 'date' : 'text'}
                                placeholder="Archivé jusqu'au"
                                aria-label="Archivé jusqu'au"
                                className="filter-input"
                                value={filtres.dateFin}
                                min={filtres.dateDebut || undefined}
                                onChange={e => handleFiltreChange('dateFin', e.target.value)}
                                onFocus={e => {
                                    e.target.type = 'date';
                                    try { e.target.showPicker?.(); } catch { /* geste utilisateur requis */ }
                                }}
                                onBlur={e => { if (!e.target.value) e.target.type = 'text'; }}
                            />
                        </div>
                    </div>

                    {/* Actions filtres */}
                    <div className="filtres-actions">
                        <button
                            type="button"
                            className="filtres-reset-btn"
                            onClick={reinitialiserFiltres}
                            title="Réinitialiser les filtres"
                            aria-label="Réinitialiser les filtres"
                            disabled={nbFiltresActifs === 0}
                        >
                            <i className="fa-solid fa-rotate-left" />
                        </button>
                        {isLoading && (
                            <span className="filtres-loading-hint" aria-live="polite">
                                <i className="fa-solid fa-spinner fa-spin" /> Recherche...
                            </span>
                        )}
                    </div>
                </div>
            )}

            {/* ── Résumé filtres actifs ── */}
            {nbFiltresActifs > 0 && (
                <div className="filtres-actifs-bar">
                    <span className="filtres-actifs-label">
                        <i className="fa-solid fa-filter" />
                        {nbFiltresActifs} filtre{nbFiltresActifs > 1 ? 's' : ''} actif{nbFiltresActifs > 1 ? 's' : ''} —
                    </span>
                    {filtres.titre && (
                        <span className="filtre-tag">Recherche : «{filtres.titre}»</span>
                    )}
                    {filtres.typeDocumentId && (
                        <span className="filtre-tag">
                            Type : {typeDocuments.find(t => String(t.id) === filtres.typeDocumentId)?.nom ?? filtres.typeDocumentId}
                        </span>
                    )}
                    {filtres.access && (
                        <span className="filtre-tag">
                            Accès : {filtres.access === 'PUBLIC' ? 'Public' : 'Privé'}
                        </span>
                    )}
                    {filtres.statut && (
                        <span className="filtre-tag">
                            Statut : {STATUS_LABELS[filtres.statut] ?? filtres.statut}
                        </span>
                    )}
                    {filtres.dateDebut && (
                        <span className="filtre-tag">Depuis : {filtres.dateDebut}</span>
                    )}
                    {filtres.dateFin && (
                        <span className="filtre-tag">Jusqu'au : {filtres.dateFin}</span>
                    )}
                    <button className="filtres-actifs-clear" onClick={reinitialiserFiltres}>
                        <i className="fa-solid fa-xmark" /> Tout effacer
                    </button>
                </div>
            )}

            {/* ── Compteur ── */}
            {!isLoading && (
                <p className="users-count">
                    <span>{totalElements}</span> document{totalElements > 1 ? 's' : ''}
                    {nbFiltresActifs > 0 && ' trouvé' + (totalElements > 1 ? 's' : '')}
                </p>
            )}

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
                                    <p className="doc-grid-type">{doc.typeDocumentNom}</p>
                                    {/* Statut, accès (public/privé) et date d'archivage retirés de la
                                        carte — déjà visibles dans le détail (bouton "i" ci-dessous),
                                        pas besoin de les dupliquer ici. */}

                                    <div className="td-actions doc-grid-actions">
                                        <button
                                            className="action-button edit"
                                            onClick={() => openDetail(doc)}
                                            title="Détail"
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
                                                onClick={() => openGroupe(doc)}
                                                title="Voir qui a accès à ce document"
                                            >
                                                <i className="fa-solid fa-user-group" />
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

                    {/* ── Pagination ── */}
                    {totalPages > 1 && (
                        <div className="pagination">
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => loadDocuments(filtres, page - 1)}
                                disabled={page === 1 || isLoading}
                            >‹</button>
                            <span className="pagination-btn pagination-active">
                                {page} / {totalPages}
                            </span>
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => loadDocuments(filtres, page + 1)}
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
                                    <th>Type</th>
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
                                        <td>{doc.retentionUntil ? formatDate(doc.retentionUntil) : 'Indéfinie'}</td>
                                        <td onClick={e => e.stopPropagation()} onDoubleClick={e => e.stopPropagation()}>
                                            <div className="td-actions">
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

                    {/* ── Pagination ── */}
                    {totalPages > 1 && (
                        <div className="pagination">
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => loadDocuments(filtres, page - 1)}
                                disabled={page === 1 || isLoading}
                            >‹</button>
                            <span className="pagination-btn pagination-active">
                                {page} / {totalPages}
                            </span>
                            <button
                                className="pagination-btn pagination-nav"
                                onClick={() => loadDocuments(filtres, page + 1)}
                                disabled={page === totalPages || isLoading}
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
                    <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
                ) : detail ? (
                    <DocumentDetailPanel
                        detail={detail}
                        onSelectVersion={openDetailById}
                        onSupprimer={handleEnvoyerCorbeille}
                        onRestaurer={handleRestaurerCorbeille}
                        suppressionLoading={suppressionLoading}
                        onVoirAcces={() => {
                            setGroupeDoc({ id: detail.documentId, titre: detail.titre });
                            setIsGroupeOpen(true);
                        }}
                        onGenererAttestation={handleGenererAttestation}
                        attestationUrl={attestationUrl}
                        attestationLoading={attestationLoading}
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
    onRestaurer,
    suppressionLoading,
    onVoirAcces,
    onGenererAttestation,
    attestationUrl,
    attestationLoading,
    onEmplacementChange,
}: {
    detail: DocumentDetailDto;
    onSelectVersion?: (documentId: string) => void;
    onSupprimer?: (documentId: string) => void;
    onRestaurer?: (documentId: string) => void;
    suppressionLoading?: boolean;
    onVoirAcces?: () => void;
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
            <div className="details-row"><strong>Type :</strong> {detail.typeDocumentNom}</div>
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
                {detail.access === 'PRIVE' && onVoirAcces && (
                    <button type="button" className="details-close-btn" onClick={onVoirAcces} style={{ marginLeft: '0.6rem' }}>
                        <i className="fa-solid fa-user-group" /> Voir qui a accès
                    </button>
                )}
            </div>
            <div className="details-row">
                <strong>Archivé le :</strong> {detail.createAt ? new Date(detail.createAt).toLocaleDateString('fr-FR') : '—'}
            </div>
            <div className="details-row"><strong>Rétention :</strong> {detail.retentionUntil ?? 'Indéfinie'}</div>
            <div className="details-row"><strong>Version :</strong> {detail.version}</div>

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
    const notify = useNotify();
    const [editing, setEditing] = useState(false);
    const [typeDef, setTypeDef] = useState<TypeDocumentEditorDto | null>(null);
    const [loadingType, setLoadingType] = useState(false);
    const [values, setValues] = useState<Record<string, string>>({});
    const [saving, setSaving] = useState(false);

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
            notify.success('Métadonnées mises à jour');
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
    const notify = useNotify();
    const [editing, setEditing] = useState(false);
    const [options, setOptions] = useState<PhysicalLocationDto[]>([]);
    const [optionsLoading, setOptionsLoading] = useState(false);
    const [selected, setSelected] = useState('');
    const [saving, setSaving] = useState(false);

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
            notify.success('Emplacement physique mis à jour');
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
    const notify = useNotify();
    const confirm = useConfirm();
    const [editing, setEditing] = useState(false);
    const [options, setOptions] = useState<ProjetDto[]>([]);
    const [optionsLoading, setOptionsLoading] = useState(false);
    const [selected, setSelected] = useState('');
    const [saving, setSaving] = useState(false);

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
            notify.success('Projet mis à jour');
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

export default DocumentsAccessibles;