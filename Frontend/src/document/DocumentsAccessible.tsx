import { useState, useEffect, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import {
    getDocumentsAccessibles,
    streamPdfAAsBlob,
    getThumbnailBlob,
    getDocumentDetail,
    downloadPdfA,
    envoyerDocumentCorbeille,
    restaurerDocumentDepuisCorbeille,
    retentionEstDepassee,
    formaterNouvelleEcheanceRetention
} from '../services/document/DocumentService';
import { genererAttestation } from '../services/document/AttestationService';
import { modifierAcces, modifierMetaDataDocument, getTypeDocumentById } from '../services/document/DocumentService';
import ChangerAccesPanel from '../components/ChangerAccesPanel';
import EmplacementPhysiqueSection from '../components/EmplacementPhysiqueSection';
import DossierAttachSection from '../components/DossierAttachSection';
import DeplacerDossierModal from '../components/DeplacerDossierModal';
import EmplacementPhysiqueModal from '../components/EmplacementPhysiqueModal';
import PdfViewer from '../components/PdfViewer';
import DocumentJournal from '../components/DocumentJournal';
import ReclasserSection from '../components/ReclasserSection';
import { hasRole } from '../auth/authService';
import type { TypeDocumentDto as TypeDocumentEditorDto } from '../services/document/DocumentService';
import MetaDataField from './MetadaField';
import type { DocumentListItemDto, DocumentDetailDto } from '../services/document/DocumentService';
import { getTypeDocumentsVisibles } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getMyUO } from '../services/organisation/UOService';
import { positionSousElement } from '../components/ancrageMenu';
import type { PositionMenu } from '../components/ancrageMenu';
import Modal from '../Page/Modal';
import VersionBadge from './VersionBadge';
import GestionGroupe from './GestionGroupe';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import { useDemandeMotif } from '../notifications/MotifSuppressionProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import '../Style/document/Filtre.css';
import '../Style/Admin/DocumentsArchivesPanel.css';
// .docs-breadcrumb / .breadcrumb-back / .pdf-viewer-wrapper (lecteur PDF
// intégré à la page, voir plus bas) — garanti disponible quel que soit le
// tableau de bord qui monte ce composant.
import '../Style/Editor/Editor.css';
// .td-row-selected (surbrillance carte/ligne sélectionnée) — le menu
// contextuel (.dossier-context-menu*) vient déjà d'Editor.css ci-dessus.
import '../Style/document/Typedocument.css';

interface DocumentsAccessiblesProps {
    uoId?: number | null;
    /**
     * Vue d'administration (Admin / Admin_UO) : consultation seule. Aucun bouton de gestion de document (corbeille,
     * dossier, emplacement, accès, reclassement) n'est affiché, même si le compte porte aussi le rôle ÉDITEUR —
     * ces actions se font depuis l'espace Éditeur.
     */
    modeAdministration?: boolean;
}

/** Retire de ce qui est affiché toute possibilité de gérer un document (vue d'administration). */
function sansGestion<T extends Partial<DocumentListItemDto & DocumentDetailDto>>(d: T): T {
    return {
        ...d,
        peutGererCorbeille: false,
        peutSupprimerDefinitivement: false,
        peutBloquerElimination: false,
        peutDebloquerElimination: false,
        peutModifierEmplacement: false,
        peutModifierDossier: false,
        peutModifierAcces: false,
    };
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
    activiteId:     string;
    access:         string;
    dateDebut:      string;
    dateFin:        string;
    statut:         string;
}

const FILTRES_VIDES: Filtres = {
    titre:          '',
    typeDocumentId: '',
    activiteId:     '',
    access:         '',
    dateDebut:      '',
    dateFin:        '',
    statut:         '',
};

// ─────────────────────────────────────────────────────────────────────────────
// Composant principal
// ─────────────────────────────────────────────────────────────────────────────

function DocumentsAccessibles({ uoId = null, modeAdministration = false }: DocumentsAccessiblesProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const demanderMotif = useDemandeMotif();
    // ── Données ───────────────────────────────────────────────────────────
    const [documents, setDocuments]     = useState<DocumentListItemDto[]>([]);
    const [totalElements, setTotal]     = useState(0);
    const [totalPages, setTotalPages]   = useState(1);
    const [page, setPage]               = useState(1);

    // ── Sélection multiple — Cmd/Ctrl+clic bascule, clic droit ouvre un menu
    // contextuel (Déplacer / Changer l'emplacement physique / Supprimer),
    // même mécanique que organisation/DossiersPanel.tsx et
    // document/TypedocumentList.tsx (handleClickCard/handleContextMenuCard) :
    // pas de case à cocher, pas de mode à activer/désactiver. Vidée à chaque
    // changement de page/filtre pour éviter une sélection fantôme sur des
    // documents qui ne sont plus affichés (voir loadDocuments). ─────────────
    const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());
    const [suppressionMasseEnCours, setSuppressionMasseEnCours] = useState(false);
    const [contextMenu, setContextMenu] = useState<PositionMenu | null>(null);
    // "Modifier" du menu contextuel : ouvre le détail directement en édition des métadonnées.
    const [metaEditAuto, setMetaEditAuto] = useState(false);
    const [isDeplacerOpen, setIsDeplacerOpen] = useState(false);
    const [isEmplacementModalOpen, setIsEmplacementModalOpen] = useState(false);

    // Échap — efface la sélection en cours et ferme le menu contextuel s'il
    // est ouvert, même convention que DossiersPanel. Cmd/Ctrl+A — sélectionne
    // tous les documents actuellement affichés (page courante), SAUF si le
    // focus est dans un champ de saisie (titre, dates...) : on laisse alors
    // le raccourci natif du navigateur sélectionner le texte du champ, pas
    // question de lui voler ce comportement standard.
    useEffect(() => {
        const onKeyDown = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                setSelectedDocIds(new Set());
                setContextMenu(null);
                return;
            }
            const cible = e.target as HTMLElement;
            const champTexte = cible.tagName === 'INPUT' || cible.tagName === 'TEXTAREA' || cible.isContentEditable;
            if (!champTexte && (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'a') {
                e.preventDefault();
                setSelectedDocIds(new Set(documents.map(d => d.documentId)));
            }
        };
        window.addEventListener('keydown', onKeyDown);
        return () => window.removeEventListener('keydown', onKeyDown);
    }, [documents]);

    // ── UO effective pour les modales de déplacement (arbre de dossiers,
    // liste des emplacements physiques) — le prop uoId (Admin/Admin UO
    // pilotant une UO précise) prévaut ; à défaut (Éditeur consultant "les
    // documents accessibles" sans UO explicite), sa PROPRE UO, résolue une
    // fois ici comme le fait déjà ImportDocuments.tsx. Sans portée pratique
    // pour un Admin/Admin UO : ceux-ci ne sont jamais EDITOR, donc
    // peutGererCorbeille (et les prédicats identiques peutModifierDossier /
    // peutModifierEmplacement, voir DocumentService côté serveur) restent
    // toujours faux pour eux — ces modales ne s'ouvrent jamais dans leur cas. */
    const [uoIdEditeur, setUoIdEditeur] = useState<number | null>(null);
    useEffect(() => {
        if (uoId != null) return;
        getMyUO().then((uo: { id?: number } | null) => setUoIdEditeur(uo?.id ?? null)).catch(() => setUoIdEditeur(null));
    }, [uoId]);
    const uoIdEffectif = uoId ?? uoIdEditeur;

    // ── Menu "..." compact (vue liste, écran réduit uniquement — voir
    // DocumentsArchivesPanel.css) : regroupe "Voir le PDF" et "Télécharger
    // PDF/A" derrière un seul point d'entrée quand les boutons autonomes
    // (.doc-actions-standalone) disparaissent sous 1100px. Volontairement
    // limité à ces deux actions — Détail, accès et corbeille ne sont pas
    // dupliqués ici, contrairement au menu de UserTable. ────────────────
    const [openMenuDocId, setOpenMenuDocId] = useState<string | null>(null);
    const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);
    const menuRef = useRef<HTMLDivElement | null>(null);
    const menuButtonRefs = useRef<Record<string, HTMLButtonElement | null>>({});

    const closeCompactMenu = () => {
        setOpenMenuDocId(null);
        setMenuPos(null);
    };

    const toggleCompactMenu = (documentId: string) => {
        if (openMenuDocId === documentId) { closeCompactMenu(); return; }
        const btn = menuButtonRefs.current[documentId];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setMenuPos({ top: rect.bottom + window.scrollY + 4, left: rect.right + window.scrollX });
        }
        setOpenMenuDocId(documentId);
    };

    useEffect(() => {
        if (!openMenuDocId) return;
        const handleClickOutside = (event: MouseEvent) => {
            const target = event.target as Node;
            const clickedToggle = menuButtonRefs.current[openMenuDocId]?.contains(target);
            const clickedMenu = menuRef.current?.contains(target);
            if (!clickedToggle && !clickedMenu) closeCompactMenu();
        };
        const handleScrollOrResize = () => closeCompactMenu();
        document.addEventListener('mousedown', handleClickOutside);
        window.addEventListener('scroll', handleScrollOrResize, true);
        window.addEventListener('resize', handleScrollOrResize);
        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            window.removeEventListener('scroll', handleScrollOrResize, true);
            window.removeEventListener('resize', handleScrollOrResize);
        };
    }, [openMenuDocId]);

    // ── Filtres ───────────────────────────────────────────────────────────
    // Une seule source de vérité — la recherche se déclenche toute seule
    // (debounce) à chaque changement, plus de distinction brouillon/appliqué
    // ni de bouton "Appliquer".
    const [filtres, setFiltres]         = useState<Filtres>(FILTRES_VIDES);
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);
    const activitesFiltre = Array.from(
        new Map(typeDocuments
            .filter(t => t.planClassementNoeudId != null && t.activite)
            .map(t => [t.planClassementNoeudId as number, { id: t.planClassementNoeudId as number, activite: t.activite as string }])
        ).values()
    ).sort((a, b) => a.activite.localeCompare(b.activite));

    // ── États UI ──────────────────────────────────────────────────────────
    const [isLoading, setIsLoading]     = useState(false);
    const [filtresOuverts, setFiltresOuverts] = useState(true);

    // ── Mode d'affichage : liste (tableau) ou grille (aperçus PDF) ────────
    type ViewMode = 'list' | 'grid';
    const [viewMode, setViewMode] = useState<ViewMode>('grid');

    // ── Aperçus PDF pour la vue grille — chargés à la demande, uniquement
    // pour les documents de la page courante et uniquement en vue grille
    // (inutile de payer le coût réseau d'un aperçu qu'on n'affiche jamais en
    // vue liste). Chaque valeur est un blob: URL vers la miniature JPEG
    // générée et mise en cache côté serveur (voir getThumbnailBlob,
    // DocumentService.ts) — À RÉVOQUER explicitement (URL.revokeObjectURL)
    // à chaque remplacement de ce state, voir loadDocuments/l'effet plus bas.
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
                planClassementNoeudId: f.activiteId ? Number(f.activiteId) : undefined,
                access:         f.access         || undefined,
                dateDebut:      f.dateDebut      || undefined,
                dateFin:        f.dateFin        || undefined,
                statut:         f.statut         || undefined,
                uoId:           uoId ?? undefined,
                page:           p,
                size:           10,
            });
            setDocuments(modeAdministration ? result.content.map(sansGestion) : result.content);
            setTotal(result.totalElements);
            setTotalPages(result.totalPages);
            setPage(p);

            // Nouvelle page/filtre → les aperçus déjà générés, et toute
            // sélection en cours, ne correspondent plus forcément aux
            // documents affichés ; on les vide (en libérant leurs blob: URL,
            // voir getThumbnailBlob) et on laisse l'effet de la vue grille en
            // régénérer au besoin.
            setPreviews(prev => { Object.values(prev).forEach(url => URL.revokeObjectURL(url)); return {}; });
            setPreviewsEchec(new Set());
            setSelectedDocIds(new Set());
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
            try {
                // Miniature déjà générée/mise en cache côté serveur — voir
                // getThumbnailBlob (DocumentService.ts) : plus de PDF/A entier
                // téléchargé ni de rendu pdf.js côté client ici, juste
                // quelques Ko d'image. 45s — au-delà, on abandonne plutôt que
                // de laisser la carte tourner indéfiniment.
                const blobUrl = await getThumbnailBlob(id, 45000);
                if (!annule) setPreviews(prev => ({ ...prev, [id]: blobUrl }));
                else URL.revokeObjectURL(blobUrl);
            } catch {
                // La carte retombe sur un placeholder "aperçu indisponible" —
                // PAS le spinner, qui donnerait l'impression trompeuse que le
                // chargement continue indéfiniment.
                if (!annule) setPreviewsEchec(prev => new Set([...prev, id]));
            } finally {
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
            setDetail(modeAdministration ? sansGestion(d) : d);
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
    // Corbeille — suppression volontaire (délai de grâce de 6 jours, restaurable)
    // ─────────────────────────────────────────────────────────────────────

    const handleEnvoyerCorbeille = async (documentId: string) => {
        const raison = await demanderMotif({
            title: 'Supprimer ce document',
            message: 'Pourquoi supprimer ce document ? Il sera supprimé définitivement à l\'échéance du délai '
                + 'de grâce — vous pourrez le restaurer d\'ici là.',
        });
        if (!raison) return;

        setSuppressionLoading(true);
        try {
            await envoyerDocumentCorbeille(documentId, raison.motif, raison.commentaire);
            await openDetailById(documentId); // recharge pour afficher la date planifiée
            loadDocuments(filtres, page);
            notify.success('Document envoyé à la corbeille');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'envoi à la corbeille');
        } finally {
            setSuppressionLoading(false);
        }
    };

    /** Seul appelant : DocumentDetailPanel.onRestaurer, toujours pour le
     *  document actuellement affiché — voir handleRestaurer de Corbeille.tsx
     *  pour la même logique de confirmation. */
    const handleRestaurerCorbeille = async (documentId: string) => {
        const depassee = retentionEstDepassee(detail?.retentionUntil);
        if (depassee) {
            const nouvelle = formaterNouvelleEcheanceRetention(detail?.retentionYearsType);
            const accepte = await confirm({
                title: 'Rétention dépassée',
                message: `La date de rétention de ce document est dépassée (${formatDate(detail?.retentionUntil ?? null)}). `
                    + (nouvelle
                        ? `Le restaurer avec une nouvelle rétention de ${nouvelle.annees} an${nouvelle.annees > 1 ? 's' : ''} `
                            + `(jusqu'au ${nouvelle.dateAffichee}) ?`
                        : `Son type de document n'a pas de limite de rétention — le restaurer sans limite ?`),
                confirmLabel: 'Restaurer',
            });
            if (!accepte) return;
        }

        setSuppressionLoading(true);
        try {
            await restaurerDocumentDepuisCorbeille(documentId, depassee);
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
        const raison = await demanderMotif({
            title: 'Supprimer ce document',
            message: `Pourquoi supprimer "${doc.titre}" ? Il sera supprimé définitivement à l'échéance du délai `
                + 'de grâce — vous pourrez le restaurer d\'ici là.',
        });
        if (!raison) return;

        try {
            await envoyerDocumentCorbeille(doc.documentId, raison.motif, raison.commentaire);
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
            return next;
        });
    };

    /** Clic normal = rien (ouvre déjà l'aperçu PDF via son propre gestionnaire,
     *  voir doc-grid-preview) ; Cmd/Ctrl+clic = bascule la sélection — même
     *  logique que DossiersPanel.handleClickDossierCard /
     *  TypeDocumentList.handleClickCard. */
    const handleClickCard = (e: React.MouseEvent, documentId: string) => {
        if (e.metaKey || e.ctrlKey) {
            e.preventDefault();
            toggleDocSelection(documentId);
            return;
        }
        if (selectedDocIds.size > 0) {
            setSelectedDocIds(new Set());
        }
    };

    /** Clic droit — sélectionne SEULEMENT la carte/ligne cliquée si elle
     *  n'était pas déjà dans la sélection courante, même logique que
     *  DossiersPanel.handleContextMenuDossier. En vue GRILLE seulement, ouvre
     *  en plus le petit menu (Déplacer/Changer l'emplacement/Supprimer) —
     *  posé directement au point de clic pour une sélection unique (comme un
     *  menu contextuel natif), centré à l'écran pour une sélection multiple
     *  (pas de carte unique à désigner). En vue LISTE, la barre de sélection
     *  déjà affichée suffit — pas de menu flottant en plus, qui la
     *  recouvrait et encombrait l'écran. */
    const handleContextMenuCard = (e: React.MouseEvent, documentId: string) => {
        e.preventDefault();
        const dejaSelectionne = selectedDocIds.has(documentId);
        if (!dejaSelectionne) {
            setSelectedDocIds(new Set([documentId]));
        }
        if (viewMode !== 'grid') return;
        // Ancré à la carte cliquée (comme son menu "..."), pour une sélection unique comme multiple.
        setContextMenu(positionSousElement(e.currentTarget as HTMLElement, 190));
    };

    const annulerSelection = () => {
        setSelectedDocIds(new Set());
    };

    // Sous-ensemble de la sélection RÉELLEMENT gérable (même prédicat serveur
    // que peutGererCorbeille pour peutModifierDossier/peutModifierEmplacement,
    // voir DocumentService côté serveur — les trois sont interchangeables) —
    // un Cmd+clic peut désormais sélectionner N'IMPORTE QUEL document affiché,
    // contrairement à l'ancienne case à cocher qui n'existait que sur les
    // documents gérables ; filtré ici plutôt que dans handleClickCard pour que
    // ce sous-ensemble reste à jour même si les documents rechargent entre
    // deux clics.
    const docsSelectionnesGerables = documents.filter(d => selectedDocIds.has(d.documentId) && d.peutGererCorbeille);

    const handleEnvoyerCorbeilleMasse = async () => {
        const ids = docsSelectionnesGerables.map(d => d.documentId);
        if (ids.length === 0) return;

        const raison = await demanderMotif({
            title: `Supprimer ${ids.length} document${ids.length > 1 ? 's' : ''}`,
            message: `Pourquoi supprimer ${ids.length > 1 ? 'ces ' + ids.length + ' documents' : 'ce document'} ? `
                + 'Le même motif sera enregistré pour chacun. Ils seront supprimés définitivement à l\'échéance du '
                + 'délai de grâce — vous pourrez les restaurer d\'ici là.',
        });
        if (!raison) return;

        setSuppressionMasseEnCours(true);
        try {
            const resultats = await Promise.allSettled(ids.map(id => envoyerDocumentCorbeille(id, raison.motif, raison.commentaire)));
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
                        <PdfViewer url={pdfBlobUrl} className="pdf-viewer-iframe" />
                    ) : (
                        <div className="td-empty"><p>Impossible de charger le document.</p></div>
                    )}
                </div>
            </div>
        );
    }

    // ── Menu contextuel (clic droit sur une carte, vue GRILLE seulement — voir
    // handleContextMenuCard, qui ne l'ouvre jamais en vue liste). Overlay plein
    // écran transparent pour fermer au clic/clic-droit ailleurs, comme un menu
    // contextuel natif. Ancré juste au-dessus de la carte pour une sélection
    // unique (dossier-context-menu-anchored, voir Editor.css) ; centré à
    // l'écran pour une sélection multiple (dossier-context-menu-centre) —
    // aucune carte unique à désigner dans ce cas. ───────────────────────────
    const contextMenuJsx = contextMenu && (
        <div
            className="dossier-context-menu-overlay"
            onClick={() => setContextMenu(null)}
            onContextMenu={e => { e.preventDefault(); setContextMenu(null); }}
        >
            <div
                className="dossier-context-menu doc-context-menu dossier-context-menu-anchored"
                style={{ top: contextMenu.top, bottom: contextMenu.bottom, left: contextMenu.left }}
                onClick={e => e.stopPropagation()}
            >
                {/* "Modifier" : un seul document sélectionné — ouvre son détail en édition des métadonnées. */}
                {selectedDocIds.size === 1 && (
                    <button
                        type="button"
                        disabled={docsSelectionnesGerables.length !== 1}
                        onClick={() => {
                            setContextMenu(null);
                            setMetaEditAuto(true);
                            openDetailById([...selectedDocIds][0]);
                        }}
                    >
                        <i className="fa-solid fa-pen" />
                        Modifier
                    </button>
                )}
                <button
                    type="button"
                    disabled={docsSelectionnesGerables.length === 0}
                    onClick={() => { setContextMenu(null); setIsDeplacerOpen(true); }}
                >
                    <i className="fa-solid fa-folder-tree" />
                    Déplacer{docsSelectionnesGerables.length > 1 ? ` (${docsSelectionnesGerables.length})` : ''}
                </button>
                <button
                    type="button"
                    disabled={docsSelectionnesGerables.length === 0}
                    onClick={() => { setContextMenu(null); setIsEmplacementModalOpen(true); }}
                >
                    <i className="fa-solid fa-map-location-dot" />
                    Changer l'emplacement physique{docsSelectionnesGerables.length > 1 ? ` (${docsSelectionnesGerables.length})` : ''}
                </button>
                <button
                    type="button"
                    className="danger"
                    disabled={docsSelectionnesGerables.length === 0 || suppressionMasseEnCours}
                    onClick={() => { setContextMenu(null); handleEnvoyerCorbeilleMasse(); }}
                >
                    <i className="fa-solid fa-trash" />
                    Supprimer{docsSelectionnesGerables.length > 1 ? ` (${docsSelectionnesGerables.length})` : ''}
                </button>
            </div>
        </div>
    );

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

                        {/* Activité (plan de classement) — options tirées des types déjà
                            chargés ci-dessus : seules les activités qui ont au moins un
                            type visible apparaissent ; le serveur inclut leurs sous-activités. */}
                        {activitesFiltre.length > 0 && (
                            <div className="filtre-field">
                                <select
                                    id="activiteSelect"
                                    className="filter-input"
                                    aria-label="Filtrer par activité"
                                    value={filtres.activiteId}
                                    onChange={e => handleFiltreChange('activiteId', e.target.value)}
                                >
                                    <option value="">Toutes les activités</option>
                                    {activitesFiltre.map(a => (
                                        <option key={a.id} value={a.id}>{a.activite}</option>
                                    ))}
                                </select>
                            </div>
                        )}

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
                    {filtres.activiteId && (
                        <span className="filtre-tag">
                            Activité : {activitesFiltre.find(a => String(a.id) === filtres.activiteId)?.activite ?? filtres.activiteId}
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

            {/* ── Barre de sélection multiple — vue LISTE seulement (n'apparaît
                que si au moins un document est sélectionné, Cmd/Ctrl+clic ou
                clic droit, voir handleClickCard/handleContextMenuCard). En
                vue GRILLE, ces mêmes actions vivent dans le petit menu
                contextuel ancré à la carte (contextMenuJsx) — les deux
                affichés en même temps encombraient l'écran, voir historique. */}
            {viewMode === 'list' && selectedDocIds.size > 0 && (
                <div className="selection-toolbar selection-toolbar-compact">
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
                        className="action-button"
                        onClick={() => setIsDeplacerOpen(true)}
                        disabled={docsSelectionnesGerables.length === 0}
                    >
                        <i className="fa-solid fa-folder-tree" /> Déplacer
                    </button>
                    <button
                        type="button"
                        className="action-button"
                        onClick={() => setIsEmplacementModalOpen(true)}
                        disabled={docsSelectionnesGerables.length === 0}
                    >
                        <i className="fa-solid fa-map-location-dot" /> Changer l'emplacement physique
                    </button>
                    <button
                        type="button"
                        className="action-button delete"
                        onClick={handleEnvoyerCorbeilleMasse}
                        disabled={suppressionMasseEnCours || docsSelectionnesGerables.length === 0}
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
                    <div
                        className="documents-grid"
                        onClick={e => { if (e.target === e.currentTarget) setSelectedDocIds(new Set()); }}
                    >
                        {documents.map(doc => (
                            <div
                                key={doc.documentId}
                                className={`doc-grid-card ${selectedDocIds.has(doc.documentId) ? 'td-row-selected' : ''}`}
                                onClick={e => handleClickCard(e, doc.documentId)}
                                onContextMenu={e => handleContextMenuCard(e, doc.documentId)}
                            >
                                <div
                                    className="doc-grid-preview"
                                    onClick={e => { if (e.metaKey || e.ctrlKey) return; openPdfViewer(doc); }}
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
                                    <th>Titre</th>
                                    <th>Type</th>
                                    {/* Masquées sur écran réduit (voir DocumentsArchivesPanel.css) —
                                        seuls Titre / Type / Actions restent, le reste est consultable
                                        via le détail ("i"). */}
                                    <th className="doc-col-acces">Accès</th>
                                    <th className="doc-col-statut">Statut</th>
                                    <th className="doc-col-archive">Archivé le</th>
                                    <th className="doc-col-retention">Rétention</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {/* Cmd/Ctrl+clic = sélection multiple, clic droit = menu contextuel
                                    (Déplacer / Changer l'emplacement physique / Supprimer), voir
                                    handleClickCard/handleContextMenuCard. Double-clic pour lire un
                                    document, plus d'icône "œil" dédiée. */}
                                {documents.map(doc => (
                                    <tr
                                        key={doc.documentId}
                                        className={selectedDocIds.has(doc.documentId) ? 'td-row-selected' : undefined}
                                        onDoubleClick={() => openPdfViewer(doc)}
                                        onClick={e => handleClickCard(e, doc.documentId)}
                                        onContextMenu={e => handleContextMenuCard(e, doc.documentId)}
                                    >
                                        <td className="td-nom">
                                            {doc.titre}
                                            <VersionBadge label={doc.versionLabel} />
                                        </td>
                                        <td>{doc.typeDocumentNom}</td>
                                        <td className="doc-col-acces">
                                            <span className={`doc-access-tag ${doc.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                                                {doc.access === 'PUBLIC' ? 'Public' : 'Privé'}
                                            </span>
                                        </td>
                                        <td className="doc-col-statut">
                                            <span className={`status-tag ${STATUS_CLASS[doc.status] ?? 'inactive'}`}>
                                                {STATUS_LABELS[doc.status] ?? doc.status}
                                            </span>
                                        </td>
                                        <td className="doc-col-archive">{formatDate(doc.createAt)}</td>
                                        <td className="doc-col-retention">{doc.retentionUntil ? formatDate(doc.retentionUntil) : 'Indéfinie'}</td>
                                        <td onClick={e => e.stopPropagation()} onDoubleClick={e => e.stopPropagation()}>
                                            <div className="td-actions">
                                                {/* Masqués sur écran réduit (voir DocumentsArchivesPanel.css,
                                                    .doc-actions-standalone) — repris dans le menu "..."
                                                    juste en dessous (Voir le PDF / Télécharger uniquement,
                                                    volontairement limité à ces deux actions). */}
                                                <button
                                                    className="action-button edit doc-actions-standalone"
                                                    onClick={() => openDetail(doc)}
                                                    title="Détail"
                                                >
                                                    <i className="fa-solid fa-circle-info" />
                                                </button>

                                                <button
                                                    className="action-button doc-actions-standalone"
                                                    onClick={() => handleDownloadPdfA(doc)}
                                                    disabled={downloadingId === doc.documentId + '_pdfa'}
                                                    title="Télécharger PDF/A"
                                                >
                                                    {downloadingId === doc.documentId + '_pdfa'
                                                        ? <i className="fa-solid fa-spinner fa-spin" />
                                                        : <i className="fa-solid fa-file-pdf" />
                                                    }
                                                </button>

                                                {/* Envoyer à la corbeille */}
                                                {doc.peutGererCorbeille && (
                                                    <button
                                                        className="action-button delete doc-actions-standalone"
                                                        onClick={() => handleEnvoyerCorbeilleRapide(doc)}
                                                        title="Envoyer à la corbeille"
                                                    >
                                                        <i className="fa-solid fa-trash" />
                                                    </button>
                                                )}

                                                {/* Menu "..." compact — visible uniquement sous 1100px
                                                    (voir DocumentsArchivesPanel.css) : regroupe Voir le
                                                    PDF et Télécharger, les deux seules actions reprises
                                                    ici pour ne pas surcharger un écran déjà réduit. */}
                                                <div className="action-menu-wrapper doc-actions-compact">
                                                    <button
                                                        ref={(el) => { menuButtonRefs.current[doc.documentId] = el; }}
                                                        onClick={() => toggleCompactMenu(doc.documentId)}
                                                        className="action-button menu-toggle"
                                                        aria-label="Plus d'actions"
                                                        aria-expanded={openMenuDocId === doc.documentId}
                                                    >
                                                        <i className="fa-solid fa-ellipsis" />
                                                    </button>

                                                    {openMenuDocId === doc.documentId && menuPos && createPortal(
                                                        <div
                                                            ref={menuRef}
                                                            className="action-menu"
                                                            style={{ position: 'fixed', top: menuPos.top, left: menuPos.left, transform: 'translateX(-100%)' }}
                                                        >
                                                            <button
                                                                onClick={() => { closeCompactMenu(); openPdfViewer(doc); }}
                                                                className="action-menu-item"
                                                            >
                                                                <i className="fa-solid fa-eye" /> Voir le PDF
                                                            </button>
                                                            <button
                                                                onClick={() => { closeCompactMenu(); handleDownloadPdfA(doc); }}
                                                                className="action-menu-item"
                                                            >
                                                                <i className="fa-solid fa-file-pdf" /> Télécharger
                                                            </button>
                                                        </div>,
                                                        document.body
                                                    )}
                                                </div>
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
                onClose={() => { setIsDetailOpen(false); setDetail(null); setMetaEditAuto(false); }}
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
                        onEmplacementChange={(d) => setDetail(modeAdministration ? sansGestion(d) : d)}
                        demarrerEditionMeta={metaEditAuto}
                        onEditionMetaDemarree={() => setMetaEditAuto(false)}
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

            {contextMenuJsx}

            {uoIdEffectif != null && (
                <>
                    <DeplacerDossierModal
                        isOpen={isDeplacerOpen}
                        onClose={() => setIsDeplacerOpen(false)}
                        uoId={uoIdEffectif}
                        documents={docsSelectionnesGerables}
                        onSuccess={() => { annulerSelection(); loadDocuments(filtres, page); }}
                    />
                    <EmplacementPhysiqueModal
                        isOpen={isEmplacementModalOpen}
                        onClose={() => setIsEmplacementModalOpen(false)}
                        uoId={uoIdEffectif}
                        documentIds={docsSelectionnesGerables.map(d => d.documentId)}
                        onSuccess={() => { annulerSelection(); loadDocuments(filtres, page); }}
                    />
                </>
            )}
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
    demarrerEditionMeta,
    onEditionMetaDemarree,
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
    demarrerEditionMeta?: boolean;
    onEditionMetaDemarree?: () => void;
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
            <div className="details-row"><strong>Activité :</strong> {detail.activite ?? 'Non classé'}{detail.activite && !detail.activiteSurDocument ? ' (celle de son type)' : ''}</div>
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
            <AccesToggleSection detail={detail} onUpdated={onEmplacementChange} />
            <div className="details-row">
                <strong>Archivé le :</strong> {detail.createAt ? new Date(detail.createAt).toLocaleDateString('fr-FR') : '—'}
            </div>
            <div className="details-row"><strong>Rétention :</strong> {detail.retentionUntil ?? 'Indéfinie'}</div>
            <div className="details-row"><strong>Version :</strong> {detail.version}</div>

            <EmplacementPhysiqueSection detail={detail} />

            <DossierAttachSection detail={detail} />

            <MetaDataEditSection
                detail={detail}
                peutModifier={detail.peutModifierEmplacement}
                onUpdated={onEmplacementChange}
                demarrerEnEdition={demarrerEditionMeta}
                onEditionDemarree={onEditionMetaDemarree}
            />

            <ReclasserSection detail={detail} onUpdated={onEmplacementChange} />
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

            {/* Journal réservé aux ADMIN, ADMIN_UO et EDITOR — le serveur le refuse aux autres (403/erreur). */}
            {(hasRole('ADMIN') || hasRole('ADMIN_UO') || hasRole('EDITOR')) && (
                <DocumentJournal documentId={detail.documentId} />
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
    demarrerEnEdition,
    onEditionDemarree,
}: {
    detail: DocumentDetailDto;
    peutModifier?: boolean;
    onUpdated?: (updated: DocumentDetailDto) => void;
    /** Ouvre directement le formulaire (action "Modifier" du menu contextuel) — une seule fois. */
    demarrerEnEdition?: boolean;
    onEditionDemarree?: () => void;
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

    useEffect(() => {
        if (demarrerEnEdition && peutModifier) {
            ouvrirEdition();
            onEditionDemarree?.();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

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

// EmplacementPhysiqueSection : voir components/EmplacementPhysiqueSection.tsx
// — partagé avec Editor/MesDocumentsEditor.tsx, plus de copie locale ici.

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : bascule PUBLIC ↔ PRIVÉ après coup
// ─────────────────────────────────────────────────────────────────────────────

function AccesToggleSection({
    detail,
    onUpdated,
}: {
    detail: DocumentDetailDto;
    onUpdated?: (updated: DocumentDetailDto) => void;
}) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [saving, setSaving] = useState(false);

    if (!detail.peutModifierAcces) {
        return null;
    }

    const rendrePrive = async (groupeMembresIds: string[]) => {
        setSaving(true);
        try {
            const updated = await modifierAcces(detail.documentId, 'PRIVE', groupeMembresIds);
            onUpdated?.(updated);
            notify.success('Document rendu privé');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du changement d\'accès');
        } finally {
            setSaving(false);
        }
    };

    const rendrePublic = async () => {
        if (!(await confirm('Rendre ce document public ? Il deviendra visible par tous les membres de son UO.'))) return;
        setSaving(true);
        try {
            const updated = await modifierAcces(detail.documentId, 'PUBLIC');
            onUpdated?.(updated);
            notify.success('Document rendu public');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du changement d\'accès');
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="details-row">
            <ChangerAccesPanel
                accesActuel={detail.access as 'PUBLIC' | 'PRIVE'}
                uoId={detail.uniteOrganisationnelleId}
                saving={saving}
                onRendrePrive={rendrePrive}
                onRendrePublic={rendrePublic}
            />
        </div>
    );
}

// DossierAttachSection : voir components/DossierAttachSection.tsx — partagé
// avec Editor/MesDocumentsEditor.tsx, plus de copie locale ici.

export default DocumentsAccessibles;