import { useEffect, useState } from 'react';
import {
    creerDossier,
    modifierDossier,
    modifierAccesDossier,
    cascaderAccesPublicVersDescendants,
    getDossiersDeUO,
    getDossierDetail,
    ajouterTypesAttendus,
    retirerTypeAttendu,
    supprimerDossier,
    previsualiserDeplacement,
    deplacerDossier,
} from '../services/organisation/DossierService';
import ChangerAccesPanel from '../components/ChangerAccesPanel';
import type { DossierDto, DossierDetailDto, TypeAttenduDto } from '../services/organisation/DossierService';
import { getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import {
    getCandidatsGroupe,
    getDocumentsAccessibles,
    getDocumentDetail,
    streamPdfAAsBlob,
    getThumbnailBlob,
    downloadPdfA,
} from '../services/document/DocumentService';
import type { UserDto, DocumentListItemDto, DocumentDetailDto, BulkUploadReportDto } from '../services/document/DocumentService';
import { positionSousElement } from '../components/ancrageMenu';
import type { PositionMenu } from '../components/ancrageMenu';
import Modal from '../Page/Modal';
import VersionBadge from '../document/VersionBadge';
import GestionGroupeDossier from './GestionGroupeDossier';
import ImportDocuments from '../document/ImportDocuments';
import PdfViewer from '../components/PdfViewer';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import '../Style/Admin/DossiersPanel.css';
// Les cartes dossier (types de documents) et la grille de documents
// réutilisent telles quelles les classes de "Mes documents"/"Documents
// accessibles" (.folders-grid, .documents-grid, .doc-grid-card...) — importé
// ici pour que ces styles soient disponibles quel que soit le tableau de
// bord (Admin/Admin UO/User/Éditeur) qui monte ce panneau.
import '../Style/Editor/Editor.css';
// Barre de filtres (.filtres-panel, .filtre-field...) — même style que celle
// de "Documents accessibles".
import '../Style/document/Filtre.css';

interface DossiersPanelProps {
    uoId: number | null;
    /** Affiche le bouton de création — réservé à ROLE_EDITOR (vérifié aussi côté serveur). */
    canCreate?: boolean;
    /** Lien profond — ouvre directement ce dossier (et, si fourni, descend
     *  jusqu'au type de document indiqué) au montage, au lieu de partir de la
     *  racine. Utilisé par PhysicalLocationsPanel ("Ouvrir dans l'emplacement"
     *  depuis un nœud physique — voir EditorDasboard). Consommé une seule
     *  fois : onInitialDossierConsumed prévient l'appelant pour qu'il efface
     *  sa valeur, sinon ce panneau se remonte sur cette même cible à chaque
     *  réaffichage de l'onglet "Dossiers" (il démonte/remonte entre deux
     *  onglets, voir EditorDasboard). */
    initialDossierId?: number | null;
    initialTypeDocumentId?: number | null;
    onInitialDossierConsumed?: () => void;
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

// Même teinte que les dossiers de "Mes documents" — voir MesDocumentsEditor.tsx.
const FOLDER_GLASS_COLOR = '#8B5E3C';

// ─────────────────────────────────────────────────────────────────────────────
// Composant principal
// ─────────────────────────────────────────────────────────────────────────────

function DossiersPanel({ uoId, canCreate = true, initialDossierId = null, initialTypeDocumentId = null, onInitialDossierConsumed }: DossiersPanelProps) {
    const notify = useNotify();
    const confirm = useConfirm();

    // ── Navigation : dossiers → types (dossiers) → documents d'un type ──────
    type PanelView = 'dossiers' | 'types' | 'documents';
    const [panelView, setPanelView] = useState<PanelView>('dossiers');

    const [dossiers, setDossiers]           = useState<DossierDto[]>([]);
    const [loading, setLoading]           = useState(false);

    // ── Modal création/modification — un seul formulaire pour les deux, le
    // mode détermine quels champs s'affichent et quel(s) appel(s) partent au
    // clic sur "Enregistrer" (voir handleEnregistrer). null = fermé.
    type ModalMode = 'create' | 'edit' | null;
    const [modalMode, setModalMode]           = useState<ModalMode>(null);
    const [nom, setNom]                       = useState('');
    const [typesUO, setTypesUO]               = useState<TypeDocumentDto[]>([]);
    const [selectedTypeIds, setSelectedTypeIds] = useState<number[]>([]);
    // Filtre local — recherche dans la liste des types proposés dans le modal.
    const [filtreTypeModal, setFiltreTypeModal] = useState('');
    const [accessCreation, setAccessCreation] = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [usersUO, setUsersUO]               = useState<UserDto[]>([]);
    const [selectedMembreIds, setSelectedMembreIds] = useState<string[]>([]);
    // Filtre local — recherche dans la liste des membres proposés dans le modal.
    const [filtreMembreModal, setFiltreMembreModal] = useState('');
    const [formSaving, setFormSaving]         = useState(false);

    // ── Filtres de la liste des dossiers (purement client — le volume de
    // dossiers par UO reste faible, pas besoin d'un aller-retour serveur) ──
    const [filtreNom, setFiltreNom]             = useState('');
    const [filtreCreateur, setFiltreCreateur]   = useState('');
    const [filtreDateDebut, setFiltreDateDebut] = useState('');
    const [filtreDateFin, setFiltreDateFin]     = useState('');

    const dossiersFiltres = dossiers.filter(p => {
        if (filtreNom.trim() && !p.nom.toLowerCase().includes(filtreNom.trim().toLowerCase())) {
            return false;
        }
        if (filtreCreateur.trim()) {
            const nomComplet = `${p.creePar?.prenom ?? ''} ${p.creePar?.nom ?? ''}`.toLowerCase();
            if (!nomComplet.includes(filtreCreateur.trim().toLowerCase())) return false;
        }
        if (filtreDateDebut && new Date(p.createAt) < new Date(filtreDateDebut)) {
            return false;
        }
        if (filtreDateFin) {
            const fin = new Date(filtreDateFin);
            fin.setHours(23, 59, 59, 999);
            if (new Date(p.createAt) > fin) return false;
        }
        return true;
    });

    const nbFiltresDossiersActifs = [filtreNom, filtreCreateur, filtreDateDebut, filtreDateFin]
        .filter(v => v.trim() !== '').length;

    const reinitialiserFiltresDossiers = () => {
        setFiltreNom('');
        setFiltreCreateur('');
        setFiltreDateDebut('');
        setFiltreDateFin('');
    };

    // ── Dossier ouvert (vue "types") ─────────────────────────────────────────
    const [dossierActif, setDossierActif]         = useState<DossierDetailDto | null>(null);
    const [dossierActifLoading, setDossierActifLoading] = useState(false);
    const [isGroupeOpen, setIsGroupeOpen]       = useState(false);

    // ── Archiver directement dans ce dossier — bouton dans la barre d'outils
    // (dossier cible seul, type à choisir) ET "+" sur chaque carte type de
    // document (dossier ET type déjà pré-remplis, voir ImportDocuments). ────
    const [isUploadOpen, setIsUploadOpen] = useState(false);
    const [uploadTypeId, setUploadTypeId] = useState<number | null>(null);

    // ── Sous-dossiers du dossier ouvert (un dossier peut contenir d'autres
    // dossiers, voir recap) — chargés en parallèle du détail, affichés dans la
    // MÊME grille mélangée que les types attendus, vue "types". ─────────────
    const [sousDossiers, setSousDossiers]               = useState<DossierDto[]>([]);
    const [sousDossiersLoading, setSousDossiersLoading] = useState(false);

    // ── Filtre du CONTENU d'un dossier ouvert (sous-dossiers + types attendus
    // mélangés, voir grille ci-dessous) — un seul champ nom, purement client,
    // réinitialisé à chaque changement de dossier (voir ouvrirDossier). ─────
    const [filtreContenuDossier, setFiltreContenuDossier] = useState('');
    const filtreContenuNormalise = filtreContenuDossier.trim().toLowerCase();
    const sousDossiersFiltres = filtreContenuNormalise
        ? sousDossiers.filter(sd => sd.nom.toLowerCase().includes(filtreContenuNormalise))
        : sousDossiers;
    const typesAttendusFiltres = filtreContenuNormalise
        ? (dossierActif?.typesAttendus ?? []).filter(t => t.nom.toLowerCase().includes(filtreContenuNormalise))
        : (dossierActif?.typesAttendus ?? []);

    // ── Type ouvert (vue "documents") ───────────────────────────────────────
    const [typeActif, setTypeActif] = useState<TypeAttenduDto | null>(null);

    // ── Liste des documents du type ouvert ──────────────────────────────────
    const [documents, setDocuments]     = useState<DocumentListItemDto[]>([]);
    const [docsPage, setDocsPage]       = useState(1);
    const [docsTotal, setDocsTotal]     = useState(0);
    const [docsPages, setDocsPages]     = useState(1);
    const [docsLoading, setDocsLoading] = useState(false);

    type ViewMode = 'list' | 'grid';
    const [docsViewMode, setDocsViewMode] = useState<ViewMode>('grid');

    // Aperçus PDF pour la vue grille — même logique que MesDocumentsEditor/
    // DocumentsAccessible : chargés à la demande, uniquement en vue grille.
    const [previews, setPreviews] = useState<Record<string, string>>({});
    const [previewsEnCours, setPreviewsEnCours] = useState<Set<string>>(new Set());

    // ── Lecteur PDF — intégré à la page (pas un modal) : lectureDoc non-null
    // bascule la vue "documents d'un type" vers le lecteur, avec un bouton
    // "Retour" façon fil d'ariane. ──────────────────────────────────────────
    const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
    const [pdfLoading, setPdfLoading] = useState(false);
    const [lectureDoc, setLectureDoc] = useState<DocumentListItemDto | null>(null);

    // ── Détail document (lecture seule — pas d'édition depuis les dossiers) ─
    const [docDetail, setDocDetail]         = useState<DocumentDetailDto | null>(null);
    const [docDetailLoading, setDocDetailLoading] = useState(false);
    const [isDocDetailOpen, setIsDocDetailOpen]   = useState(false);

    // ── Téléchargement ────────────────────────────────────────────────────
    const [downloadingId, setDownloadingId] = useState<string | null>(null);

    // ─────────────────────────────────────────────────────────────────────
    // Chargement dossiers
    // ─────────────────────────────────────────────────────────────────────

    const chargerDossiers = () => {
        if (!uoId) return;
        setLoading(true);
        getDossiersDeUO(uoId)
            .then(setDossiers)
            .catch(err => notify.error(err.message))
            .finally(() => setLoading(false));
    };

    useEffect(() => {
        chargerDossiers();
        setSelectedDossierIds(new Set());
        if (uoId) {
            getTypeDocumentsByUO(uoId).then(setTypesUO).catch(() => setTypesUO([]));
            getCandidatsGroupe(uoId).then(setUsersUO).catch(() => setUsersUO([]));
        } else {
            setTypesUO([]);
            setUsersUO([]);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId]);

    // Dossier créé/modifié depuis une autre interface pendant qu'on reste sur
    // cet écran → rechargé au retour de focus (liste dossiers uniquement — la
    // vue détail d'un dossier ouvert se recharge elle-même via ses handlers).
    useRefetchOnFocus(chargerDossiers);

    // parentId : où accroche le dossier créé (null = racine de l'UO, sinon
    // sous-dossier de dossierActif). parentPrive : le parent est-il privé ?
    // Si oui, l'accès est FORCÉ privé (invariant — voir DossierService côté
    // serveur), pas de choix PUBLIC/PRIVÉ affiché.
    const [creationParentId, setCreationParentId] = useState<number | null>(null);
    const [creationParentPrive, setCreationParentPrive] = useState(false);

    const ouvrirCreation = (parentId: number | null = null, parentPrive: boolean = false) => {
        setNom('');
        setSelectedTypeIds([]);
        setAccessCreation(parentPrive ? 'PRIVE' : 'PUBLIC');
        setSelectedMembreIds([]);
        setFiltreTypeModal('');
        setCreationParentId(parentId);
        setCreationParentPrive(parentPrive);
        setModalMode('create');
    };

    const ouvrirEdition = (detail: DossierDetailDto | null = dossierActif) => {
        if (!detail) return;
        setNom(detail.nom);
        setSelectedTypeIds(detail.typesAttendus.map(t => t.typeDocumentId));
        setFiltreTypeModal('');
        setModalMode('edit');
    };

    const fermerModal = () => setModalMode(null);

    const toggleType = (id: number) => {
        setSelectedTypeIds(prev =>
            prev.includes(id) ? prev.filter(t => t !== id) : [...prev, id]
        );
    };

    const toggleMembre = (id: string) => {
        setSelectedMembreIds(prev =>
            prev.includes(id) ? prev.filter(m => m !== id) : [...prev, id]
        );
    };

    /**
     * Enregistre le formulaire modal — création ou modification selon
     * modalMode. En modification, le nom part via modifierDossier
     * et les types attendus sont mis à jour par DIFFÉRENCE avec l'état
     * actuel du dossier (un appel ajouterTypesAttendus pour les nouveaux, un
     * retirerTypeAttendu par type retiré — le serveur refuse individuellement
     * un retrait si ce type a déjà des documents dans ce dossier, auquel cas
     * on continue les autres retraits et on le signale à la fin plutôt que
     * de tout annuler).
     */
    const handleEnregistrer = async () => {
        if (!nom.trim()) return;

        if (modalMode === 'create') {
            if (!uoId) return;
            setFormSaving(true);
            try {
                await creerDossier({
                    nom: nom.trim(),
                    uoId,
                    parentId: creationParentId,
                    typeDocumentIds: selectedTypeIds.length > 0 ? selectedTypeIds : undefined,
                    access: accessCreation,
                    groupeMembresIds: accessCreation === 'PRIVE' && selectedMembreIds.length > 0
                        ? selectedMembreIds : undefined,
                });
                fermerModal();
                notify.success('Dossier créé avec succès');
                if (creationParentId != null) {
                    chargerSousDossiers(creationParentId);
                } else {
                    chargerDossiers();
                }
            } catch (err: any) {
                notify.error(err.message);
            } finally {
                setFormSaving(false);
            }
            return;
        }

        if (modalMode === 'edit' && dossierActif) {
            setFormSaving(true);
            try {
                await modifierDossier(dossierActif.id, {
                    nom: nom.trim(),
                });

                const typesActuels = dossierActif.typesAttendus.map(t => t.typeDocumentId);
                const aAjouter = selectedTypeIds.filter(id => !typesActuels.includes(id));
                const aRetirer = typesActuels.filter(id => !selectedTypeIds.includes(id));

                if (aAjouter.length > 0) {
                    await ajouterTypesAttendus(dossierActif.id, aAjouter);
                }

                let retraitsEchoues = 0;
                for (const typeId of aRetirer) {
                    try {
                        await retirerTypeAttendu(dossierActif.id, typeId);
                    } catch {
                        retraitsEchoues++;
                    }
                }

                rafraichirDossierActif();
                chargerDossiers(); // le nom a pu changer, la liste doit suivre
                fermerModal();

                if (retraitsEchoues > 0) {
                    notify.error(
                        `Dossier mis à jour, mais ${retraitsEchoues} type${retraitsEchoues > 1 ? 's' : ''} `
                        + `n'ont pas pu être retiré(s) — des documents de ce type existent déjà dans ce dossier.`
                    );
                } else {
                    notify.success('Dossier mis à jour avec succès');
                }
            } catch (err: any) {
                notify.error(err.message);
            } finally {
                setFormSaving(false);
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Navigation : dossiers → types
    // ─────────────────────────────────────────────────────────────────────

    const ouvrirDossier = (id: number, onCharge?: (detail: DossierDetailDto) => void) => {
        setDossierActifLoading(true);
        setPanelView('types');
        setFiltreContenuDossier('');
        setSelectedDossierIds(new Set());
        getDossierDetail(id)
            .then(detail => { setDossierActif(detail); onCharge?.(detail); })
            .catch(err => { notify.error(err.message); setPanelView('dossiers'); })
            .finally(() => setDossierActifLoading(false));
        chargerSousDossiers(id);
    };

    // ── Lien profond — voir Javadoc DossiersPanelProps.initialDossierId.
    // getDossierDetail(id) renvoie déjà le chemin complet (parentNom, utilisé
    // dans le fil d'Ariane) : ouvrirDossier(id) seul suffit à atterrir
    // exactement sur ce dossier, inutile de remonter la hiérarchie à la main. ──
    useEffect(() => {
        if (initialDossierId == null) return;
        ouvrirDossier(initialDossierId, detail => {
            if (initialTypeDocumentId != null) {
                const type = detail.typesAttendus.find(t => t.typeDocumentId === initialTypeDocumentId);
                if (type) ouvrirType(type, detail.id);
            }
        });
        onInitialDossierConsumed?.();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [initialDossierId, initialTypeDocumentId]);

    const chargerSousDossiers = (parentId: number) => {
        if (!uoId) return;
        setSousDossiersLoading(true);
        getDossiersDeUO(uoId, parentId)
            .then(setSousDossiers)
            .catch(() => setSousDossiers([]))
            .finally(() => setSousDossiersLoading(false));
    };

    /** Recharge le dossier ouvert sans changer de vue — après ajout/retrait d'un type attendu. */
    const rafraichirDossierActif = () => {
        if (!dossierActif) return;
        getDossierDetail(dossierActif.id).then(setDossierActif).catch(() => {});
    };

    // Gestion (archiver, modifier, retirer un type…) : réservée à l'éditeur. Un compte
    // admin/admin_uo qui cumule le rôle EDITOR garde l'interface d'administration en
    // lecture seule (canCreate=false) — la permission serveur reste inchangée.
    const peutGererTypes = canCreate && !!dossierActif?.peutGererTypes;

    // ─────────────────────────────────────────────────────────────────────
    // Archiver directement dans le dossier ouvert — voir ImportDocuments
    // (preselectedDossierId/preselectedTypeId, tous deux modifiables) et le
    // bouton "Archiver ici"/le "+" par carte type dans le rendu plus bas.
    // ─────────────────────────────────────────────────────────────────────
    const ouvrirUpload = (typeId: number | null, e?: React.MouseEvent) => {
        e?.stopPropagation();
        setUploadTypeId(typeId);
        setIsUploadOpen(true);
    };

    const fermerUpload = () => {
        setIsUploadOpen(false);
        setUploadTypeId(null);
    };

    const handleUploadSuccess = (report: BulkUploadReportDto) => {
        fermerUpload();
        rafraichirDossierActif();
        if (dossierActif) chargerSousDossiers(dossierActif.id);

        if (report.failed === 0) {
            notify.success(
                report.success > 1
                    ? `${report.success} documents archivés avec succès`
                    : 'Document archivé avec succès'
            );
        } else if (report.success === 0) {
            const premiereErreur = report.details.find(d => d.status === 'FAILED')?.erreur;
            notify.error(
                `Échec de l'archivage — ${report.failed} document(s) non archivé(s)`
                + (premiereErreur ? ` : ${premiereErreur}` : '')
            );
        } else {
            notify.warning(
                `${report.success} document(s) archivé(s), ${report.failed} échec(s) — voir le détail`
            );
        }
    };

    /**
     * Remonte d'UN niveau — vers le dossier parent s'il y en a un (arbitrairement
     * profond, chaque clic remonte d'un cran), sinon vers la liste racine de
     * l'UO. Utilisé par le fil d'Ariane ET après suppression du dossier ouvert.
     */
    const retourAuNiveauParent = () => {
        if (dossierActif?.parentId != null) {
            ouvrirDossier(dossierActif.parentId);
        } else {
            setPanelView('dossiers');
            setDossierActif(null);
            setSousDossiers([]);
            setSelectedDossierIds(new Set());
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Sélection multiple de dossiers (Cmd+clic sur Mac, Ctrl+clic ailleurs —
    // même convention qu'un gestionnaire de fichiers) : permet de déplacer ou
    // supprimer plusieurs dossiers d'un coup (voir plus bas). Portée limitée
    // au niveau actuellement affiché (racine OU sous-dossiers d'un dossier
    // ouvert) — réinitialisée à chaque navigation pour éviter de traîner une
    // sélection d'un autre niveau (voir ouvrirDossier/retourAuNiveauParent).
    // ─────────────────────────────────────────────────────────────────────
    const [selectedDossierIds, setSelectedDossierIds] = useState<Set<number>>(new Set());

    // Échap — efface la sélection en cours (et ferme le menu contextuel s'il
    // est ouvert, voir contextMenu plus bas), convention standard.
    useEffect(() => {
        const onKeyDown = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                setSelectedDossierIds(new Set());
                setContextMenu(null);
            }
        };
        window.addEventListener('keydown', onKeyDown);
        return () => window.removeEventListener('keydown', onKeyDown);
    }, []);

    const toggleSelectionDossier = (id: number) => {
        setSelectedDossierIds(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const handleClickDossierCard = (e: React.MouseEvent, dossierId: number) => {
        if (e.metaKey || e.ctrlKey) {
            e.preventDefault();
            toggleSelectionDossier(dossierId);
            return;
        }
        // Un clic normal ouvre TOUJOURS le dossier en un seul clic — même
        // s'il y avait une sélection multiple en cours, qui est alors juste
        // effacée au passage plutôt que d'exiger un second clic pour naviguer.
        if (selectedDossierIds.size > 0) {
            setSelectedDossierIds(new Set());
        }
        ouvrirDossier(dossierId);
    };

    // ── Menu contextuel (clic droit) — Supprimer la sélection courante, ou
    // juste le dossier cliqué s'il n'était pas déjà dans la sélection. ──────
    const [contextMenu, setContextMenu] = useState<PositionMenu | null>(null);

    // Ctrl+clic (sur Mac, c'est un clic droit) : le menu s'ouvre sur la carte cliquée, comme son menu "...", et non au
    // curseur. Réservé à l'éditeur (canCreate) — en lecture seule (admin) aucun menu de gestion.
    const handleContextMenuDossier = (e: React.MouseEvent, dossierId: number) => {
        e.preventDefault();
        if (!canCreate) return;
        if (!selectedDossierIds.has(dossierId)) {
            setSelectedDossierIds(new Set([dossierId]));
        }
        setContextMenu(positionSousElement(e.currentTarget as HTMLElement, 130));
    };

    /** "Modifier" depuis le menu d'une carte — ouvre le dossier puis son formulaire de modification. */
    const handleModifierSelection = () => {
        setContextMenu(null);
        const [id] = [...selectedDossierIds];
        if (selectedDossierIds.size !== 1 || id === undefined) return;
        ouvrirDossier(id, detail => ouvrirEdition(detail));
    };

    /** Un dossier de la sélection (niveau affiché) est verrouillé : lui ou un descendant contient des documents. */
    const selectionVerrouillee = [...selectedDossierIds].some(id =>
        [...dossiers, ...sousDossiers].find(d => d.id === id)?.verrouille);

    const handleSupprimerSelection = async () => {
        setContextMenu(null);
        const ids = [...selectedDossierIds];
        if (ids.length === 0) return;

        if (!(await confirm({
            message: ids.length === 1
                ? 'Supprimer définitivement ce dossier et tous ses sous-dossiers ?'
                : `Supprimer définitivement ces ${ids.length} dossiers et tous leurs sous-dossiers ?`,
            danger: true,
        }))) {
            return;
        }

        let succes = 0;
        let echecs = 0;
        for (const id of ids) {
            try {
                await supprimerDossier(id);
                succes++;
            } catch {
                echecs++;
            }
        }

        setSelectedDossierIds(new Set());
        if (dossierActif) {
            chargerSousDossiers(dossierActif.id);
        } else {
            chargerDossiers();
        }

        if (echecs === 0) {
            notify.success(succes > 1 ? `${succes} dossiers supprimés avec succès` : 'Dossier supprimé avec succès');
        } else {
            notify.error(
                `${succes} dossier(s) supprimé(s), ${echecs} échec(s) — un dossier dont la branche `
                + 'contient des documents ne peut pas être supprimé'
            );
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Glisser-déposer un ou plusieurs dossiers vers un nouveau parent (voir
    // DossierService.deplacerDossier côté serveur). Deux cibles possibles :
    //   - une autre carte dossier du même niveau → devient son enfant ;
    //   - le bouton "retour" du fil d'Ariane (vue "types" uniquement) →
    //     remonte au parent du dossier actuellement ouvert.
    // Avant tout déplacement effectif, un aperçu (previsualiserDeplacement)
    // détermine s'il faut alerter l'éditeur (passage forcé en privé, ou
    // membres divergents entre les deux groupes d'accès — voir recap confirmé).
    // Glisser une carte qui fait partie de la sélection multiple courante
    // déplace TOUTE la sélection ; glisser une carte hors sélection déplace
    // seulement celle-ci (et efface la sélection précédente, voir
    // handleDragStartDossier) — même convention qu'un gestionnaire de fichiers.
    // ─────────────────────────────────────────────────────────────────────
    const [draggedDossierId, setDraggedDossierId] = useState<number | null>(null);
    const [dragOverDossierId, setDragOverDossierId] = useState<number | null>(null);
    const [dragOverParentCible, setDragOverParentCible] = useState(false);

    /** Ids réellement déplacés par le glisser-déposer en cours (voir commentaire ci-dessus). */
    const idsEnCoursDeDeplacement = (): number[] => {
        if (draggedDossierId === null) return [];
        if (selectedDossierIds.size > 1 && selectedDossierIds.has(draggedDossierId)) {
            return [...selectedDossierIds];
        }
        return [draggedDossierId];
    };

    const handleDragStartDossier = (e: React.DragEvent, id: number) => {
        if (!selectedDossierIds.has(id)) {
            setSelectedDossierIds(new Set());
        }
        setDraggedDossierId(id);
        e.dataTransfer.setData('text/plain', String(id));
        e.dataTransfer.effectAllowed = 'move';
    };

    const handleDragEndDossier = () => {
        setDraggedDossierId(null);
        setDragOverDossierId(null);
        setDragOverParentCible(false);
    };

    const handleDragOverCarte = (e: React.DragEvent, id: number) => {
        if (draggedDossierId === null || idsEnCoursDeDeplacement().includes(id)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        if (dragOverDossierId !== id) setDragOverDossierId(id);
    };

    const handleDragLeaveCarte = (id: number) => {
        setDragOverDossierId(prev => (prev === id ? null : prev));
    };

    const handleDragOverParentCible = (e: React.DragEvent) => {
        if (draggedDossierId === null) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        setDragOverParentCible(true);
    };

    /**
     * silencieux : true pour un déplacement en LOT (voir
     * executerDeplacementMultiple) — les alertes de confirmation par dossier
     * restent affichées si nécessaire (divergence de groupes, passage forcé
     * en privé), mais le toast de succès/erreur individuel est supprimé au
     * profit d'un seul toast consolidé à la fin du lot.
     */
    const executerDeplacement = async (
        id: number, nouveauParentId: number | null, silencieux = false
    ): Promise<boolean> => {
        try {
            const preview = await previsualiserDeplacement(id, nouveauParentId);

            if (preview.deviendraPrive || preview.divergenceGroupes) {
                const lignes: string[] = [];
                if (preview.deviendraPrive) {
                    lignes.push(
                        'Ce dossier est actuellement public et le dossier cible est privé : il deviendra '
                        + 'PRIVÉ, avec un nouveau groupe d\'accès hérité des membres du dossier cible.'
                    );
                }
                if (preview.divergenceGroupes) {
                    const noms = preview.membresDivergents.map(m => `${m.prenom} ${m.nom}`).join(', ');
                    lignes.push(
                        'Les groupes d\'accès du dossier déplacé et du dossier cible ont des points de '
                        + 'divergence entre leurs utilisateurs. Le dossier déplacé garde son propre groupe, '
                        + `mais ses membres absents du groupe cible y seront ajoutés par défaut : ${noms}.`
                    );
                }
                if (!(await confirm({
                    title: 'Confirmer le déplacement',
                    message: lignes.join('\n\n'),
                    confirmLabel: 'Déplacer quand même',
                }))) {
                    return false;
                }
            }

            await deplacerDossier(id, nouveauParentId);
            if (!silencieux) {
                notify.success('Dossier déplacé avec succès');
            }

            // Toujours rafraîchir la liste racine (même invisible dans la vue
            // courante) : un déplacement peut faire entrer/sortir un dossier de
            // la racine (voir handleDropVersParentCible), sinon elle reste
            // périmée jusqu'au prochain focus de fenêtre (useRefetchOnFocus) —
            // ce qui donnait l'impression qu'il fallait recharger la page.
            chargerDossiers();
            if (dossierActif) {
                chargerSousDossiers(dossierActif.id);
            }
            return true;
        } catch (err: any) {
            if (!silencieux) {
                notify.error(err.message ?? 'Erreur lors du déplacement du dossier');
            }
            return false;
        }
    };

    /** Déplace plusieurs dossiers vers le même nouveau parent — voir commentaire de section ci-dessus. */
    const executerDeplacementMultiple = async (ids: number[], nouveauParentId: number | null) => {
        if (ids.length === 1) {
            await executerDeplacement(ids[0], nouveauParentId);
            setSelectedDossierIds(new Set());
            return;
        }

        let succes = 0;
        for (const id of ids) {
            if (await executerDeplacement(id, nouveauParentId, true)) {
                succes++;
            }
        }

        setSelectedDossierIds(new Set());
        if (succes > 0) {
            notify.success(`${succes} dossier(s) déplacé(s) avec succès`);
        }
        if (succes < ids.length) {
            notify.error(`${ids.length - succes} dossier(s) n'ont pas pu être déplacés`);
        }
    };

    const handleDropSurCarte = async (e: React.DragEvent, targetId: number) => {
        e.preventDefault();
        setDragOverDossierId(null);
        const ids = idsEnCoursDeDeplacement().filter(id => id !== targetId);
        setDraggedDossierId(null);
        if (ids.length === 0) return;
        await executerDeplacementMultiple(ids, targetId);
    };

    const handleDropVersParentCible = async (e: React.DragEvent) => {
        e.preventDefault();
        setDragOverParentCible(false);
        const ids = idsEnCoursDeDeplacement();
        setDraggedDossierId(null);
        if (ids.length === 0 || !dossierActif) return;
        await executerDeplacementMultiple(ids, dossierActif.parentId);
    };

    // ─────────────────────────────────────────────────────────────────────
    // Bascule PUBLIC ↔ PRIVÉ après coup (voir ChangerAccesPanel)
    // ─────────────────────────────────────────────────────────────────────
    const [savingAcces, setSavingAcces] = useState(false);

    const handleRendreDossierPrive = async (groupeMembresIds: string[]) => {
        if (!dossierActif) return;
        setSavingAcces(true);
        try {
            await modifierAccesDossier(dossierActif.id, 'PRIVE', groupeMembresIds);
            rafraichirDossierActif();
            chargerDossiers();
            notify.success('Dossier rendu privé');
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors du changement d'accès");
        } finally {
            setSavingAcces(false);
        }
    };

    const handleRendreDossierPublic = async () => {
        if (!dossierActif) return;
        if (!(await confirm(
            'Rendre ce dossier public ? Il deviendra visible par tous les membres de son UO, ainsi que les documents '
            + 'qu\'il contient et qui partagent encore son groupe d\'accès.'
        ))) return;
        setSavingAcces(true);
        try {
            await modifierAccesDossier(dossierActif.id, 'PUBLIC');
            rafraichirDossierActif();
            chargerDossiers();
            notify.success('Dossier rendu public');

            // Cascade OPTIONNELLE vers les sous-dossiers privés — jamais
            // automatique côté serveur (voir DossierService.modifierAcces) :
            // un enfant privé sous un parent public reste un état valide,
            // donc on demande explicitement plutôt que de forcer.
            if (sousDossiers.length > 0 && await confirm(
                'Rendre aussi PUBLICS tous les sous-dossiers de ce dossier (et leurs documents qui partagent '
                + 'encore leur groupe d\'accès) ? Sans confirmation, les sous-dossiers déjà privés le restent.'
            )) {
                await cascaderAccesPublicVersDescendants(dossierActif.id);
                chargerSousDossiers(dossierActif.id);
                notify.success('Sous-dossiers rendus publics en cascade');
            }
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors du changement d'accès");
        } finally {
            setSavingAcces(false);
        }
    };

    /**
     * Retrait rapide d'un type depuis sa carte-dossier (la croix au survol) —
     * sans passer par le modal de modification, contrairement à
     * handleEnregistrer. Le serveur refuse toujours ce retrait si des
     * documents de ce type existent déjà dans ce dossier.
     */
    const handleRetirerTypeRapide = async (e: React.MouseEvent, typeId: number) => {
        e.stopPropagation();
        if (!dossierActif) return;
        try {
            await retirerTypeAttendu(dossierActif.id, typeId);
            rafraichirDossierActif();
        } catch (err: any) {
            notify.error(err.message);
        }
    };

    /** Déclenché depuis la modale "Modifier" (voir modalCreationEdition) — ferme la modale dans tous les cas. */
    const handleSupprimer = async (id: number, nomDossier: string) => {
        if (!(await confirm({ message: `Supprimer définitivement le dossier "${nomDossier}" et tous ses sous-dossiers ?`, danger: true }))) return;
        try {
            await supprimerDossier(id);
            fermerModal();
            notify.success('Dossier supprimé avec succès');
            retourAuNiveauParent();
            chargerDossiers();
        } catch (err: any) {
            notify.error(err.message);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Navigation : types → documents d'un type
    // ─────────────────────────────────────────────────────────────────────

    // `dossierId` optionnel : par défaut dossierActif.id (cas normal, l'utilisateur
    // a déjà cliqué pour ouvrir le dossier, l'état a eu le temps de se poser avant ce
    // nouvel appel). Paramètre nécessaire pour le lien profond (voir plus bas,
    // useEffect sur initialDossierId) : y appeler ouvrirType juste après
    // setDossierActif(detail) dans le MÊME tick ne garantit PAS que l'état
    // dossierActif soit déjà à jour (React ne l'applique qu'au rendu suivant) —
    // sans ce paramètre, le garde-fou "if (!dossierActif) return" ci-dessous
    // lisait encore l'ancienne fermeture (null) et sortait en silence, d'où le
    // "0 document" alors que la navigation normale (deux clics séparés, avec un
    // rendu entre les deux) fonctionne très bien.
    const chargerDocumentsDuType = (type: TypeAttenduDto, page: number, dossierId?: number) => {
        const idDossier = dossierId ?? dossierActif?.id;
        if (!idDossier) return;
        setDocsLoading(true);
        getDocumentsAccessibles({
            dossierId:       idDossier,
            typeDocumentId: type.typeDocumentId,
            page,
            size: 10,
        })
            .then(result => {
                setDocuments(result.content);
                setDocsTotal(result.totalElements);
                setDocsPages(result.totalPages);
                setDocsPage(page);
                setPreviews(prev => { Object.values(prev).forEach(url => URL.revokeObjectURL(url)); return {}; });
            })
            .catch(err => notify.error(err.message ?? 'Erreur chargement documents'))
            .finally(() => setDocsLoading(false));
    };

    const ouvrirType = (type: TypeAttenduDto, dossierId?: number) => {
        setTypeActif(type);
        setPanelView('documents');
        chargerDocumentsDuType(type, 1, dossierId);
    };

    const retourAuxTypes = () => {
        setPanelView('types');
        setTypeActif(null);
        setDocuments([]);
    };

    // Aperçus PDF pour la vue grille — même schéma que MesDocumentsEditor/DocumentsAccessible.
    useEffect(() => {
        if (docsViewMode !== 'grid' || documents.length === 0) return;
        let annule = false;

        const idsACharger = documents
            .map(d => d.documentId)
            .filter(id => !previews[id] && !previewsEnCours.has(id));
        if (idsACharger.length === 0) return;

        setPreviewsEnCours(prev => new Set([...prev, ...idsACharger]));

        idsACharger.forEach(async (id) => {
            try {
                // Miniature déjà générée/mise en cache côté serveur — voir
                // getThumbnailBlob (DocumentService.ts). 45s — au-delà, on
                // abandonne plutôt que de laisser la carte tourner indéfiniment.
                const blobUrl = await getThumbnailBlob(id, 45000);
                if (!annule) setPreviews(prev => ({ ...prev, [id]: blobUrl }));
                else URL.revokeObjectURL(blobUrl);
            } catch {
                // Silencieux — la carte retombe sur son placeholder générique.
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
    }, [docsViewMode, documents]);

    // ── Lecteur PDF ───────────────────────────────────────────────────────

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

    // ── Détail document (lecture seule) ─────────────────────────────────────

    const openDocDetail = async (doc: DocumentListItemDto) => {
        setDocDetailLoading(true);
        setIsDocDetailOpen(true);
        setDocDetail(null);
        try {
            const d = await getDocumentDetail(doc.documentId);
            setDocDetail(d);
        } catch {
            // le panneau affichera "impossible de charger"
        } finally {
            setDocDetailLoading(false);
        }
    };

    // ── Téléchargement ────────────────────────────────────────────────────

    const handleDownloadPdfA = async (doc: DocumentListItemDto) => {
        setDownloadingId(doc.documentId + '_pdfa');
        try { await downloadPdfA(doc.documentId, doc.titre); }
        finally { setDownloadingId(null); }
    };

    // ─────────────────────────────────────────────────────────────────────
    // RENDU
    // ─────────────────────────────────────────────────────────────────────

    // Modal création/modification — calculé UNE FOIS ici puis réutilisé dans
    // les 3 vues (documents/types/dossiers, voir plus bas) : "Modifier" se
    // déclenche depuis la vue "types", donc le modal doit rester monté dans
    // CETTE vue plutôt que dans la seule vue "dossiers", sans quoi il ne
    // s'affichait qu'après être retourné à la liste des dossiers.
    const modalCreationEdition = (
        <Modal
            isOpen={modalMode !== null}
            onClose={fermerModal}
            title={modalMode === 'edit' ? 'Modifier le dossier' : 'Créer un dossier'}
        >
            <div className="dossiers-create-form">
                <input
                    type="text"
                    placeholder="Nom du dossier *"
                    value={nom}
                    onChange={e => setNom(e.target.value)}
                    disabled={modalMode === 'edit' && !!dossierActif?.verrouille}
                    title={modalMode === 'edit' && dossierActif?.verrouille
                        ? 'Des documents sont classés dans ce dossier ou l\'un de ses sous-dossiers : il ne peut plus être renommé' : undefined}
                />
                {/* Bascule PUBLIC ↔ PRIVÉ après coup — déplacée ici depuis la barre
                    d'outils (revu le 09/2026) : n'a de sens qu'en modification,
                    jamais à la création (voir le picker Public/Privé juste en
                    dessous, dédié à la création). "Voir qui a accès" (gestion des
                    membres d'un dossier déjà privé) reste, lui, dans la barre. */}
                {modalMode === 'edit' && dossierActif?.peutModifierAcces && (
                    <div className="dossiers-types-picker">
                        <p>Accès au dossier :</p>
                        <ChangerAccesPanel
                            accesActuel={dossierActif.access as 'PUBLIC' | 'PRIVE'}
                            uoId={dossierActif.uoId}
                            saving={savingAcces}
                            onRendrePrive={handleRendreDossierPrive}
                            onRendrePublic={handleRendreDossierPublic}
                        />
                    </div>
                )}

                {/* Accès (Public/Privé) — fixé à la création, non modifiable ici
                    (voir "Voir qui a accès" dans la vue du dossier pour gérer les
                    membres d'un dossier déjà privé). Masqué sous un parent privé :
                    l'invariant (un enfant ne peut jamais être plus ouvert que son
                    parent, voir Javadoc backend) force alors PRIVÉ, sans choix. */}
                {modalMode === 'create' && creationParentPrive && (
                    <p className="dossiers-detail-meta">
                        <i className="fa-solid fa-lock" /> Ce sous-dossier sera automatiquement privé (dossier
                        parent privé) — vous pouvez ajouter des membres en plus de ceux déjà présents dans le parent.
                    </p>
                )}
                {modalMode === 'create' && !creationParentPrive && (
                    <div className="dossiers-access-picker">
                        <label className="dossiers-access-radio">
                            <input
                                type="radio"
                                name="dossier-access"
                                checked={accessCreation === 'PUBLIC'}
                                onChange={() => setAccessCreation('PUBLIC')}
                            />
                            <span>Public</span>
                        </label>
                        <label className="dossiers-access-radio">
                            <input
                                type="radio"
                                name="dossier-access"
                                checked={accessCreation === 'PRIVE'}
                                onChange={() => setAccessCreation('PRIVE')}
                            />
                            <span>Privé </span>
                        </label>
                    </div>
                )}

                {modalMode === 'create' && accessCreation === 'PRIVE' && usersUO.length > 0 && (
                    <div className="dossiers-types-picker">
                        <p>
                            {creationParentPrive
                                ? "Membres SUPPLÉMENTAIRES (en plus de ceux déjà présents dans le dossier parent, hérités automatiquement) :"
                                : "Membres du groupe d'accès (vous serez ajouté automatiquement) :"}
                        </p>
                        <input
                            type="text"
                            className="dossiers-type-search"
                            placeholder="Rechercher (nom, email, téléphone)"
                            aria-label="Rechercher un utilisateur"
                            value={filtreMembreModal}
                            onChange={e => setFiltreMembreModal(e.target.value)}
                        />
                        <div className="dossiers-types-list">
                            {usersUO
                                .filter(u => {
                                    const q = filtreMembreModal.trim().toLowerCase();
                                    if (!q) return true;
                                    return `${u.prenom} ${u.nom}`.toLowerCase().includes(q)
                                        || u.email.toLowerCase().includes(q)
                                        || (u.telephone ?? '').toLowerCase().includes(q);
                                })
                                .map(u => (
                                    <label key={u.id} className="dossiers-type-checkbox">
                                        <input
                                            type="checkbox"
                                            checked={selectedMembreIds.includes(u.id)}
                                            onChange={() => toggleMembre(u.id)}
                                        />
                                        <span>{u.prenom} {u.nom} — {u.email}</span>
                                    </label>
                                ))}
                        </div>
                    </div>
                )}

                {typesUO.length > 0 && (
                    <div className="dossiers-types-picker">
                        <p>Types de documents attendus :</p>
                        <input
                            type="text"
                            className="dossiers-type-search"
                            placeholder="Rechercher un type de document..."
                            value={filtreTypeModal}
                            onChange={e => setFiltreTypeModal(e.target.value)}
                        />
                        <div className="dossiers-types-list-vertical">
                            {(() => {
                                const typesAffiches = typesUO.filter(t =>
                                    t.nom.toLowerCase().includes(filtreTypeModal.trim().toLowerCase())
                                );
                                if (typesAffiches.length === 0) {
                                    return <p className="dossiers-types-list-empty">Aucun type ne correspond.</p>;
                                }
                                return typesAffiches.map(t => {
                                    // En modification, un type qui a déjà des documents DANS
                                    // CE DOSSIER ne peut pas être décoché — le serveur le
                                    // refuserait de toute façon (voir handleEnregistrer).
                                    const nonRetirable = modalMode === 'edit' && dossierActif
                                        ? dossierActif.typesAttendus.some(a => a.typeDocumentId === t.id && a.fourni)
                                        : false;
                                    return (
                                        <label key={t.id} className="dossiers-type-row">
                                            <input
                                                type="checkbox"
                                                checked={selectedTypeIds.includes(t.id!)}
                                                disabled={nonRetirable}
                                                onChange={() => toggleType(t.id!)}
                                            />
                                            <span>{t.nom}{nonRetirable ? ' (déjà des documents)' : ''}</span>
                                        </label>
                                    );
                                });
                            })()}
                        </div>
                    </div>
                )}

                <div className="dossiers-create-actions">
                    <button
                        className="sidebar-btn"
                        disabled={!nom.trim() || formSaving}
                        onClick={handleEnregistrer}
                    >
                        {formSaving
                            ? (modalMode === 'edit' ? 'Enregistrement…' : 'Création…')
                            : (modalMode === 'edit' ? 'Enregistrer' : 'Créer')}
                    </button>
                    <button className="dossiers-cancel-btn" onClick={fermerModal}>
                        Annuler
                    </button>
                    {/* Suppression — déplacée ici depuis la barre d'outils (revu le
                        09/2026), uniquement en modification, poussée à l'extrémité
                        droite (voir .dossiers-delete-btn). Gardée par peutGererTypes,
                        PAS peutGererAcces : ce dernier n'est vrai que pour un dossier
                        déjà PRIVÉ (gestion du groupe), alors que supprimerDossier
                        côté serveur utilise la même autorité que peutGererTypes
                        (verifierPeutGererDossier) — refusé de toute façon si le
                        dossier contient des documents ou des sous-dossiers, voir
                        handleSupprimer. */}
                    {modalMode === 'edit' && peutGererTypes && (
                        <button
                            type="button"
                            className="dossiers-delete-btn"
                            onClick={() => handleSupprimer(dossierActif.id, dossierActif.nom)}
                            disabled={dossierActif.verrouille}
                            title={dossierActif.verrouille
                                ? 'Des documents sont classés dans ce dossier ou l\'un de ses sous-dossiers' : undefined}
                        >
                            <i className="fa-solid fa-trash" /> Supprimer
                        </button>
                    )}
                </div>
            </div>
        </Modal>
    );

    // ── Menu contextuel (clic droit sur une carte dossier) — voir
    // handleContextMenuDossier/handleSupprimerSelection. Overlay plein écran
    // transparent pour fermer au clic/clic-droit ailleurs, comme un menu
    // contextuel natif. ──────────────────────────────────────────────────
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
                {/* "Modifier" : une seule carte sélectionnée (pas de modification groupée). */}
                {selectedDossierIds.size === 1 && (
                    <button type="button" onClick={handleModifierSelection}>
                        <i className="fa-solid fa-pen" />
                        Modifier
                    </button>
                )}
                <button
                    type="button"
                    className="danger"
                    onClick={handleSupprimerSelection}
                    disabled={selectionVerrouillee}
                    title={selectionVerrouillee
                        ? 'Des documents sont classés dans ce dossier ou l\'un de ses sous-dossiers' : undefined}
                >
                    <i className="fa-solid fa-trash" />
                    Supprimer{selectedDossierIds.size > 1 ? ` (${selectedDossierIds.size})` : ''}
                </button>
            </div>
        </div>
    );

    if (!uoId) {
        return <div className="dossiers-panel-empty">Sélectionnez une unité organisationnelle.</div>;
    }

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

    // ── Vue "documents" : documents d'un type, à l'intérieur d'un dossier ──
    if (panelView === 'documents' && dossierActif && typeActif) {
        return (
            <div className="mes-docs-wrapper">
                <div className="docs-breadcrumb">
                    <button className="breadcrumb-back" onClick={retourAuxTypes}>
                        <i className="fa-solid fa-arrow-left" /> {dossierActif.nom}
                    </button>
                    <i className="fa-solid fa-chevron-right breadcrumb-sep" />
                    <span
                        className="breadcrumb-folder-dot"
                        style={{ background: FOLDER_GLASS_COLOR }}
                    />
                    <span className="breadcrumb-current">{typeActif.nom}</span>
                    <span className="breadcrumb-count">
                        ({docsTotal} document{docsTotal > 1 ? 's' : ''})
                    </span>

                    {peutGererTypes && (
                        <button
                            className="breadcrumb-add-btn breadcrumb-archive-btn"
                            onClick={e => ouvrirUpload(typeActif.typeDocumentId, e)}
                            title={`Archiver un document de type ${typeActif.nom} dans ce dossier`}
                        >
                            <i className="fa-solid fa-box-archive" /> Archiver
                        </button>
                    )}

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
                </div>

                {docsLoading && documents.length === 0 ? (
                    <div className="td-loading">
                        <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                    </div>
                ) : documents.length === 0 ? (
                    <div className="td-empty">
                        <p>Aucun document de ce type dans ce dossier pour l'instant.</p>
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
                                        {previews[doc.documentId] ? (
                                            <img
                                                src={previews[doc.documentId]}
                                                alt=""
                                                className="doc-grid-preview-frame"
                                            />
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

                                        <div className="td-actions doc-grid-actions">
                                            <button
                                                className="action-button edit"
                                                onClick={() => openDocDetail(doc)}
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
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>

                        {docsPages > 1 && (
                            <div className="pagination">
                                <button
                                    className="pagination-btn pagination-nav"
                                    onClick={() => chargerDocumentsDuType(typeActif, docsPage - 1)}
                                    disabled={docsPage === 1 || docsLoading}
                                >‹</button>
                                <span className="pagination-btn pagination-active">
                                    {docsPage} / {docsPages}
                                </span>
                                <button
                                    className="pagination-btn pagination-nav"
                                    onClick={() => chargerDocumentsDuType(typeActif, docsPage + 1)}
                                    disabled={docsPage === docsPages || docsLoading}
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
                                        <th>Accès</th>
                                        <th>Statut</th>
                                        <th>Archivé le</th>
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
                                            <td>
                                                <div className="td-actions">
                                                    <button
                                                        className="action-button view"
                                                        onClick={() => openPdfViewer(doc)}
                                                        title="Lire le document"
                                                    >
                                                        <i className="fa-solid fa-eye" />
                                                    </button>
                                                    <button
                                                        className="action-button edit"
                                                        onClick={() => openDocDetail(doc)}
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
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>

                        {docsPages > 1 && (
                            <div className="pagination">
                                <button
                                    className="pagination-btn pagination-nav"
                                    onClick={() => chargerDocumentsDuType(typeActif, docsPage - 1)}
                                    disabled={docsPage === 1 || docsLoading}
                                >‹</button>
                                <span className="pagination-btn pagination-active">
                                    {docsPage} / {docsPages}
                                </span>
                                <button
                                    className="pagination-btn pagination-nav"
                                    onClick={() => chargerDocumentsDuType(typeActif, docsPage + 1)}
                                    disabled={docsPage === docsPages || docsLoading}
                                >›</button>
                            </div>
                        )}
                    </>
                )}

                {/* ── Modal archivage — dossier et type déjà pré-remplis (voir ouvrirUpload). ── */}
                <Modal
                    isOpen={isUploadOpen}
                    onClose={fermerUpload}
                    title="Archiver des documents"
                    size="large"
                >
                    <ImportDocuments
                        onsuccess={handleUploadSuccess}
                        preselectedDossierId={dossierActif.id}
                        preselectedTypeId={uploadTypeId}
                    />
                </Modal>

                {/* ── Modal détail (lecture seule) ── */}
                <Modal
                    isOpen={isDocDetailOpen}
                    onClose={() => { setIsDocDetailOpen(false); setDocDetail(null); }}
                    title="Détail du document"
                >
                    {docDetailLoading ? (
                        <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
                    ) : docDetail ? (
                        <DocumentDetailLectureSeule detail={docDetail} />
                    ) : (
                        <div className="td-empty"><p>Impossible de charger le détail.</p></div>
                    )}
                </Modal>
            </div>
        );
    }

    // ── Vue "types" : dossiers des types de documents attendus d'un dossier ──
    if (panelView === 'types' && (dossierActif || dossierActifLoading)) {
        return (
            <div className="mes-docs-wrapper">
                <div className="docs-breadcrumb">
                    <button
                        className={`breadcrumb-back ${dragOverParentCible ? 'dossier-drag-over' : ''}`}
                        onClick={retourAuNiveauParent}
                        onDragOver={handleDragOverParentCible}
                        onDragLeave={() => setDragOverParentCible(false)}
                        onDrop={handleDropVersParentCible}
                    >
                        <i className="fa-solid fa-arrow-left" /> {dossierActif?.parentNom ?? 'Dossiers'}
                    </button>
                    <i className="fa-solid fa-chevron-right breadcrumb-sep" />
                    {peutGererTypes && (
                        <button
                            className="breadcrumb-edit-btn"
                            onClick={() => ouvrirEdition()}
                            aria-label="Modifier le dossier"
                            title="Modifier"
                        >
                            <i className="fa-solid fa-pen" />
                        </button>
                    )}
                    <span className="breadcrumb-current">{dossierActif?.nom}</span>
                    {dossierActif?.access === 'PRIVE' && (
                        <span className="doc-access-tag prive">Privé</span>
                    )}

                    {/* Un seul conteneur poussé à droite (margin-left:auto) pour TOUT
                        ce groupe — chaque bouton pris individuellement avec sa propre
                        margin-left:auto (hérité de .breadcrumb-add-btn) se répartissait
                        l'espace libre restant EN PARTS ÉGALES entre eux (comportement
                        flexbox avec plusieurs marges "auto"), les écartant au lieu de
                        les coller ensemble à droite. */}
                    <div className="docs-breadcrumb-actions">
                        {dossierActif?.access === 'PRIVE' && (
                            <button className="breadcrumb-add-btn" onClick={() => setIsGroupeOpen(true)}>
                                <i className="fa-solid fa-user-group" /> Accès
                            </button>
                        )}
                        {peutGererTypes && (
                            <button
                                className="breadcrumb-add-btn breadcrumb-add-btn-icon-only breadcrumb-archive-btn"
                                onClick={e => ouvrirUpload(null, e)}
                                aria-label="Archiver dans ce dossier"
                                title="Archiver dans ce dossier"
                            >
                                <i className="fa-solid fa-box-archive" />
                            </button>
                        )}
                        {peutGererTypes && (
                            <button
                                className="breadcrumb-add-btn breadcrumb-add-btn-icon-only"
                                onClick={() => ouvrirCreation(dossierActif.id, dossierActif.access === 'PRIVE')}
                                aria-label="Créer un sous-dossier"
                                title="Sous-dossier"
                            >
                                <i className="fa-solid fa-plus" />
                            </button>
                        )}
                    </div>
                </div>

                {dossierActif && (
                    <p className="dossiers-detail-meta" style={{ marginBottom: '0.75rem' }}>
                        Créé par {dossierActif.creePar} le {formatDate(dossierActif.createAt)}
                    </p>
                )}

                {/* Contenu de ce dossier — sous-dossiers (voir DossierCard) ET types
                    de documents attendus MÉLANGÉS dans la même grille, plus
                    séparés en deux sections (un dossier peut désormais contenir
                    d'autres dossiers, voir recap). "Vide" seulement si aucun des
                    deux ; sinon la grille n'affiche que ce qui existe réellement,
                    jamais de message "aucun type attendu" tant qu'il y a au moins
                    un sous-dossier (ou inversement). Filtre nom (ci-dessous)
                    appliqué aux DEUX à la fois, purement client. */}
                {!dossierActifLoading && !sousDossiersLoading && dossierActif
                    && (sousDossiers.length > 0 || dossierActif.typesAttendus.length > 0) && (
                    <div className="filtres-panel" style={{ marginBottom: '1rem' }}>
                        <div className="filtres-grid">
                            <div className="filtre-field filtre-field-titre">
                                <input
                                    type="text"
                                    className="filter-input"
                                    placeholder="Filtrer les sous-dossiers et types de documents"
                                    aria-label="Filtrer le contenu de ce dossier"
                                    value={filtreContenuDossier}
                                    onChange={e => setFiltreContenuDossier(e.target.value)}
                                />
                            </div>
                        </div>
                        <div className="filtres-actions">
                            <button
                                type="button"
                                className="filtres-reset-btn"
                                onClick={() => setFiltreContenuDossier('')}
                                title="Réinitialiser le filtre"
                                aria-label="Réinitialiser le filtre"
                                disabled={!filtreContenuDossier.trim()}
                            >
                                <i className="fa-solid fa-rotate-left" />
                            </button>
                        </div>
                    </div>
                )}

                {dossierActifLoading || sousDossiersLoading ? (
                    <div className="td-loading">
                        <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                    </div>
                ) : !dossierActif || (sousDossiers.length === 0 && dossierActif.typesAttendus.length === 0) ? (
                    <div className="td-empty">
                        <i className="fa-solid fa-folder-open" style={{ fontSize: '2.5rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }} />
                        <p>Vide</p>
                    </div>
                ) : sousDossiersFiltres.length === 0 && typesAttendusFiltres.length === 0 ? (
                    <p className="dossiers-panel-empty">Aucun résultat ne correspond à ce filtre.</p>
                ) : (
                    <div className="folders-grid">
                        {sousDossiersFiltres.map(sd => (
                            <DossierCard
                                key={sd.id}
                                dossier={sd}
                                onClick={e => handleClickDossierCard(e, sd.id)}
                                onContextMenuCarte={e => handleContextMenuDossier(e, sd.id)}
                                isSelected={selectedDossierIds.has(sd.id)}
                                draggable={canCreate && !sd.verrouille}
                                isDragging={idsEnCoursDeDeplacement().includes(sd.id)}
                                isDragOver={dragOverDossierId === sd.id}
                                onDragStartCarte={e => handleDragStartDossier(e, sd.id)}
                                onDragEndCarte={handleDragEndDossier}
                                onDragOverCarte={e => handleDragOverCarte(e, sd.id)}
                                onDragLeaveCarte={() => handleDragLeaveCarte(sd.id)}
                                onDropCarte={e => handleDropSurCarte(e, sd.id)}
                            />
                        ))}
                        {typesAttendusFiltres.map(t => (
                            <div
                                key={t.typeDocumentId}
                                className="folder-card"
                                onClick={() => ouvrirType(t)}
                                role="button"
                                tabIndex={0}
                                onKeyDown={e => e.key === 'Enter' && ouvrirType(t)}
                                aria-label={`Ouvrir le dossier ${t.nom}`}
                            >
                                {/* Retrait rapide — uniquement si ce type n'a encore aucun
                                    document dans ce dossier et si l'utilisateur peut gérer les
                                    types de ce dossier. Pas besoin de passer par "Modifier". */}
                                {peutGererTypes && !t.fourni && (
                                    <button
                                        className="folder-add-btn"
                                        style={{ background: 'var(--error)' }}
                                        onClick={e => handleRetirerTypeRapide(e, t.typeDocumentId)}
                                        aria-label={`Retirer le type ${t.nom}`}
                                        title="Retirer ce type attendu"
                                    >
                                        <i className="fa-solid fa-xmark" />
                                    </button>
                                )}
                                <div className="folder-icon-wrap">
                                    <div className="folder-tab" />
                                    <div className="folder-back" />
                                    <div className="document-sheet">
                                        <div className="doc-line short" />
                                        <div className="doc-line" />
                                        <div className="doc-line" />
                                    </div>
                                    <div className="glass-pocket" />

                                    {/* Archiver directement dans ce type — dossier ET type déjà
                                        pré-remplis (voir ouvrirUpload/ImportDocuments), en
                                        bas-droite pour ne jamais chevaucher le retrait rapide
                                        (haut-gauche) ni le compteur (haut-droite). */}
                                    {peutGererTypes && (
                                        <button
                                            className="folder-quick-add-btn"
                                            onClick={e => ouvrirUpload(t.typeDocumentId, e)}
                                            aria-label={`Archiver un document de type ${t.nom} dans ce dossier`}
                                            title="Archiver ici"
                                        >
                                            <i className="fa-solid fa-plus" />
                                        </button>
                                    )}

                                    <span className="folder-count">
                                        {t.nombreDocuments}
                                    </span>
                                </div>

                                <span className="folder-name">
                                    {t.nom}
                                    {!t.fourni && (
                                        <i
                                            className="fa-regular fa-circle"
                                            title="Aucun document fourni pour l'instant"
                                            style={{ marginLeft: '0.35rem', color: 'var(--text-light)', fontSize: '0.7rem' }}
                                        />
                                    )}
                                </span>
                            </div>
                        ))}
                    </div>
                )}


                {/* ── Modal groupe d'accès du dossier ── */}
                <Modal
                    isOpen={isGroupeOpen}
                    onClose={() => setIsGroupeOpen(false)}
                    title="Accès au dossier"
                >
                    {dossierActif && (
                        <GestionGroupeDossier
                            dossierId={dossierActif.id}
                            dossierNom={dossierActif.nom}
                            onClose={() => setIsGroupeOpen(false)}
                        />
                    )}
                </Modal>

                {/* ── Modal archivage — dossier ET (si ouvert via une carte type)
                    type déjà pré-remplis, voir ouvrirUpload/ImportDocuments. Même
                    composant que "Archiver" dans la barre latérale, pour une
                    interface identique partout. ── */}
                <Modal
                    isOpen={isUploadOpen}
                    onClose={fermerUpload}
                    title="Archiver des documents"
                    size="large"
                >
                    {dossierActif && (
                        <ImportDocuments
                            onsuccess={handleUploadSuccess}
                            preselectedDossierId={dossierActif.id}
                            preselectedTypeId={uploadTypeId}
                        />
                    )}
                </Modal>

                {/* Le bouton "Modifier" est ICI, dans cette vue — le modal doit donc
                    y être monté aussi, pas seulement dans la vue "dossiers". */}
                {modalCreationEdition}
                {contextMenuJsx}
            </div>
        );
    }

    // ── Vue "dossiers" : liste des dossiers de l'UO ──────────────────────────
    return (
        <div className="dossiers-panel">
            {canCreate && (
                <div className="dossiers-panel-header">
                    <button className="sidebar-btn" onClick={() => ouvrirCreation()}>
                        <i className="fa-solid fa-folder-plus" /> Créer un dossier
                    </button>
                </div>
            )}

            {modalCreationEdition}
            {contextMenuJsx}

            {dossiers.length > 0 && (
                <div className="filtres-panel">
                    <div className="filtres-grid">
                        <div className="filtre-field filtre-field-titre">
                            <input
                                type="text"
                                className="filter-input"
                                placeholder="Nom du dossier"
                                aria-label="Filtrer par nom du dossier"
                                value={filtreNom}
                                onChange={e => setFiltreNom(e.target.value)}
                            />
                        </div>
                        <div className="filtre-field">
                            <input
                                type="text"
                                className="filter-input"
                                placeholder="Créé par..."
                                aria-label="Filtrer par créateur"
                                value={filtreCreateur}
                                onChange={e => setFiltreCreateur(e.target.value)}
                            />
                        </div>
                        <div className="filtre-field filtre-field-date">
                            <input
                                type={filtreDateDebut ? 'date' : 'text'}
                                placeholder="Créé depuis"
                                aria-label="Créé depuis"
                                className="filter-input"
                                value={filtreDateDebut}
                                max={filtreDateFin || undefined}
                                onChange={e => setFiltreDateDebut(e.target.value)}
                                onFocus={e => {
                                    e.target.type = 'date';
                                    try { e.target.showPicker?.(); } catch { /* geste utilisateur requis */ }
                                }}
                                onBlur={e => { if (!e.target.value) e.target.type = 'text'; }}
                            />
                        </div>
                        <div className="filtre-field filtre-field-date">
                            <input
                                type={filtreDateFin ? 'date' : 'text'}
                                placeholder="Créé jusqu'au"
                                aria-label="Créé jusqu'au"
                                className="filter-input"
                                value={filtreDateFin}
                                min={filtreDateDebut || undefined}
                                onChange={e => setFiltreDateFin(e.target.value)}
                                onFocus={e => {
                                    e.target.type = 'date';
                                    try { e.target.showPicker?.(); } catch { /* geste utilisateur requis */ }
                                }}
                                onBlur={e => { if (!e.target.value) e.target.type = 'text'; }}
                            />
                        </div>
                    </div>
                    <div className="filtres-actions">
                        <button
                            type="button"
                            className="filtres-reset-btn"
                            onClick={reinitialiserFiltresDossiers}
                            title="Réinitialiser les filtres"
                            aria-label="Réinitialiser les filtres"
                            disabled={nbFiltresDossiersActifs === 0}
                        >
                            <i className="fa-solid fa-rotate-left" />
                        </button>
                    </div>
                </div>
            )}

            {loading ? (
                <p>Chargement…</p>
            ) : dossiers.length === 0 ? (
                <p className="dossiers-panel-empty">Aucun dossier pour cette unité organisationnelle.</p>
            ) : dossiersFiltres.length === 0 ? (
                <p className="dossiers-panel-empty">Aucun dossier ne correspond à ces filtres.</p>
            ) : (
                <div className="folders-grid">
                    {dossiersFiltres.map(p => (
                        <DossierCard
                            key={p.id}
                            dossier={p}
                            onClick={e => handleClickDossierCard(e, p.id)}
                            onContextMenuCarte={e => handleContextMenuDossier(e, p.id)}
                            isSelected={selectedDossierIds.has(p.id)}
                            draggable={canCreate && !p.verrouille}
                            isDragging={idsEnCoursDeDeplacement().includes(p.id)}
                            isDragOver={dragOverDossierId === p.id}
                            onDragStartCarte={e => handleDragStartDossier(e, p.id)}
                            onDragEndCarte={handleDragEndDossier}
                            onDragOverCarte={e => handleDragOverCarte(e, p.id)}
                            onDragLeaveCarte={() => handleDragLeaveCarte(p.id)}
                            onDropCarte={e => handleDropSurCarte(e, p.id)}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : carte DOSSIER — réutilisée à la fois pour la liste racine
// et pour les sous-dossiers (un dossier peut désormais contenir d'autres
// dossiers, voir recap), une seule fois plutôt que dupliquée à chaque niveau.
// Réutilise TELLE QUELLE l'icône des cartes "type de document" (onglet +
// corps chocolat + pochette de verre dépoli, voir .folder-icon-wrap/
// .folder-tab/.folder-back/.glass-pocket dans Editor.css) — seule différence :
// pas de .document-sheet (la feuille blanche), et une petite étiquette
// "Dossier" en bas-droite (voir .folder-type-badge) plutôt que la pastille de
// comptage en haut-droite d'un type. Un dossier peut désormais contenir
// d'autres dossiers (voir DossierCard), "un seul fichier dedans" n'avait
// plus de sens pour cette carte.
// ─────────────────────────────────────────────────────────────────────────────

interface DossierCardProps {
    dossier: DossierDto;
    /** Clic normal = ouvrir ; Cmd/Ctrl+clic = bascule la sélection (voir DossiersPanel.handleClickDossierCard). */
    onClick: (e: React.MouseEvent) => void;
    onContextMenuCarte?: (e: React.MouseEvent) => void;
    /** true = glisser-déposer activé (réservé à l'éditeur, voir DossiersPanel.canCreate). */
    draggable?: boolean;
    isDragging?: boolean;
    isDragOver?: boolean;
    /** true = fait partie de la sélection multiple courante (Cmd/Ctrl+clic). */
    isSelected?: boolean;
    onDragStartCarte?: (e: React.DragEvent) => void;
    onDragEndCarte?: () => void;
    onDragOverCarte?: (e: React.DragEvent) => void;
    onDragLeaveCarte?: () => void;
    onDropCarte?: (e: React.DragEvent) => void;
}

function DossierCard({
    dossier, onClick, onContextMenuCarte, draggable = false, isDragging = false, isDragOver = false, isSelected = false,
    onDragStartCarte, onDragEndCarte, onDragOverCarte, onDragLeaveCarte, onDropCarte,
}: DossierCardProps) {
    return (
        <div
            className={`folder-card ${isDragging ? 'dossier-dragging' : ''} ${isDragOver ? 'dossier-drag-over' : ''} ${isSelected ? 'dossier-selected' : ''}`}
            onClick={onClick}
            onContextMenu={onContextMenuCarte}
            role="button"
            tabIndex={0}
            onKeyDown={e => e.key === 'Enter' && onClick(e as unknown as React.MouseEvent)}
            aria-label={`Ouvrir le dossier ${dossier.nom}`}
            draggable={draggable}
            onDragStart={onDragStartCarte}
            onDragEnd={onDragEndCarte}
            onDragOver={onDragOverCarte}
            onDragLeave={onDragLeaveCarte}
            onDrop={onDropCarte}
        >
            <div className="folder-icon-wrap">
                <div className="folder-tab" />
                <div className="folder-back" />
                <div className="glass-pocket" />
                <span className="folder-type-badge">Dossier</span>
            </div>

            <span className="folder-name">{dossier.nom}</span>
            <span className="folder-meta">
                {dossier.creePar?.prenom} {dossier.creePar?.nom} · {formatDate(dossier.createAt)}
            </span>
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : détail document en LECTURE SEULE (pas d'édition depuis les
// dossiers — emplacement physique, métadonnées, versions... se gèrent depuis
// "Mes documents"/"Documents accessibles").
// ─────────────────────────────────────────────────────────────────────────────

function DocumentDetailLectureSeule({ detail }: { detail: DocumentDetailDto }) {
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
                <span className={`status-tag ${STATUS_CLASS[detail.status] ?? 'inactive'}`}>
                    {STATUS_LABELS[detail.status] ?? detail.status}
                </span>
            </div>
            <div className="details-row">
                <strong>Accès :</strong>
                <span className={`doc-access-tag ${detail.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                    {detail.access === 'PUBLIC' ? 'Public' : 'Privé'}
                </span>
            </div>
            <div className="details-row">
                <strong>Archivé le :</strong> {formatDate(detail.createAt)}
            </div>
            {detail.physicalLocationPath && (
                <div className="details-row">
                    <strong>Emplacement physique :</strong> {detail.physicalLocationPath}
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
        </div>
    );
}

export default DossiersPanel;
