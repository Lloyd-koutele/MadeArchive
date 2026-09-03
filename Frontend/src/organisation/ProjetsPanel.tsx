import { useEffect, useState } from 'react';
import {
    creerProjet,
    modifierProjet,
    getProjetsDeUO,
    getProjetDetail,
    ajouterTypesAttendus,
    retirerTypeAttendu,
    supprimerProjet,
} from '../services/organisation/ProjetService';
import type { ProjetDto, ProjetDetailDto, TypeAttenduDto } from '../services/organisation/ProjetService';
import { getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import {
    getAllUsers,
    getDocumentsAccessibles,
    getDocumentDetail,
    streamPdfAAsBlob,
    downloadPdfA,
} from '../services/document/DocumentService';
import type { UserDto, DocumentListItemDto, DocumentDetailDto } from '../services/document/DocumentService';
import { renderPdfFirstPageThumbnail } from '../services/document/PdfThumbnail';
import Modal from '../Page/Modal';
import VersionBadge from '../document/VersionBadge';
import GestionGroupeProjet from './GestionGroupeProjet';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import { useRefetchOnFocus } from '../hooks/useRefetchOnFocus';
import '../Style/Admin/ProjetsPanel.css';
// Les cartes dossier (types de documents) et la grille de documents
// réutilisent telles quelles les classes de "Mes documents"/"Documents
// accessibles" (.folders-grid, .documents-grid, .doc-grid-card...) — importé
// ici pour que ces styles soient disponibles quel que soit le tableau de
// bord (Admin/Admin UO/User/Éditeur) qui monte ce panneau.
import '../Style/Editor/Editor.css';
// Barre de filtres (.filtres-panel, .filtre-field...) — même style que celle
// de "Documents accessibles".
import '../Style/document/Filtre.css';

interface ProjetsPanelProps {
    uoId: number | null;
    /** Affiche le bouton de création — réservé à ROLE_EDITOR (vérifié aussi côté serveur). */
    canCreate?: boolean;
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

function ProjetsPanel({ uoId, canCreate = true }: ProjetsPanelProps) {
    const notify = useNotify();
    const confirm = useConfirm();

    // ── Navigation : projets → types (dossiers) → documents d'un type ──────
    type PanelView = 'projets' | 'types' | 'documents';
    const [panelView, setPanelView] = useState<PanelView>('projets');

    const [projets, setProjets]           = useState<ProjetDto[]>([]);
    const [loading, setLoading]           = useState(false);

    // ── Modal création/modification — un seul formulaire pour les deux, le
    // mode détermine quels champs s'affichent et quel(s) appel(s) partent au
    // clic sur "Enregistrer" (voir handleEnregistrer). null = fermé.
    type ModalMode = 'create' | 'edit' | null;
    const [modalMode, setModalMode]           = useState<ModalMode>(null);
    const [nom, setNom]                       = useState('');
    const [description, setDescription]       = useState('');
    const [typesUO, setTypesUO]               = useState<TypeDocumentDto[]>([]);
    const [selectedTypeIds, setSelectedTypeIds] = useState<number[]>([]);
    // Filtre local — recherche dans la liste des types proposés dans le modal.
    const [filtreTypeModal, setFiltreTypeModal] = useState('');
    const [accessCreation, setAccessCreation] = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [usersUO, setUsersUO]               = useState<UserDto[]>([]);
    const [selectedMembreIds, setSelectedMembreIds] = useState<string[]>([]);
    const [formSaving, setFormSaving]         = useState(false);

    // ── Filtres de la liste des projets (purement client — le volume de
    // projets par UO reste faible, pas besoin d'un aller-retour serveur) ──
    const [filtreNom, setFiltreNom]             = useState('');
    const [filtreCreateur, setFiltreCreateur]   = useState('');
    const [filtreDateDebut, setFiltreDateDebut] = useState('');
    const [filtreDateFin, setFiltreDateFin]     = useState('');

    const projetsFiltres = projets.filter(p => {
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

    const nbFiltresProjetsActifs = [filtreNom, filtreCreateur, filtreDateDebut, filtreDateFin]
        .filter(v => v.trim() !== '').length;

    const reinitialiserFiltresProjets = () => {
        setFiltreNom('');
        setFiltreCreateur('');
        setFiltreDateDebut('');
        setFiltreDateFin('');
    };

    // ── Projet ouvert (vue "types") ─────────────────────────────────────────
    const [projetActif, setProjetActif]         = useState<ProjetDetailDto | null>(null);
    const [projetActifLoading, setProjetActifLoading] = useState(false);
    const [isGroupeOpen, setIsGroupeOpen]       = useState(false);

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

    // ── Détail document (lecture seule — pas d'édition depuis les projets) ─
    const [docDetail, setDocDetail]         = useState<DocumentDetailDto | null>(null);
    const [docDetailLoading, setDocDetailLoading] = useState(false);
    const [isDocDetailOpen, setIsDocDetailOpen]   = useState(false);

    // ── Téléchargement ────────────────────────────────────────────────────
    const [downloadingId, setDownloadingId] = useState<string | null>(null);

    // ─────────────────────────────────────────────────────────────────────
    // Chargement projets
    // ─────────────────────────────────────────────────────────────────────

    const chargerProjets = () => {
        if (!uoId) return;
        setLoading(true);
        getProjetsDeUO(uoId)
            .then(setProjets)
            .catch(err => notify.error(err.message))
            .finally(() => setLoading(false));
    };

    useEffect(() => {
        chargerProjets();
        if (uoId) {
            getTypeDocumentsByUO(uoId).then(setTypesUO).catch(() => setTypesUO([]));
            getAllUsers(uoId).then(setUsersUO).catch(() => setUsersUO([]));
        } else {
            setTypesUO([]);
            setUsersUO([]);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId]);

    // Projet créé/modifié depuis une autre interface pendant qu'on reste sur
    // cet écran → rechargé au retour de focus (liste projets uniquement — la
    // vue détail d'un projet ouvert se recharge elle-même via ses handlers).
    useRefetchOnFocus(chargerProjets);

    const ouvrirCreation = () => {
        setNom('');
        setDescription('');
        setSelectedTypeIds([]);
        setAccessCreation('PUBLIC');
        setSelectedMembreIds([]);
        setFiltreTypeModal('');
        setModalMode('create');
    };

    const ouvrirEdition = () => {
        if (!projetActif) return;
        setNom(projetActif.nom);
        setDescription(projetActif.description ?? '');
        setSelectedTypeIds(projetActif.typesAttendus.map(t => t.typeDocumentId));
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
     * modalMode. En modification, le nom/description part via modifierProjet
     * et les types attendus sont mis à jour par DIFFÉRENCE avec l'état
     * actuel du projet (un appel ajouterTypesAttendus pour les nouveaux, un
     * retirerTypeAttendu par type retiré — le serveur refuse individuellement
     * un retrait si ce type a déjà des documents dans ce projet, auquel cas
     * on continue les autres retraits et on le signale à la fin plutôt que
     * de tout annuler).
     */
    const handleEnregistrer = async () => {
        if (!nom.trim()) return;

        if (modalMode === 'create') {
            if (!uoId) return;
            setFormSaving(true);
            try {
                await creerProjet({
                    nom: nom.trim(),
                    description: description.trim() || undefined,
                    uoId,
                    typeDocumentIds: selectedTypeIds.length > 0 ? selectedTypeIds : undefined,
                    access: accessCreation,
                    groupeMembresIds: accessCreation === 'PRIVE' && selectedMembreIds.length > 0
                        ? selectedMembreIds : undefined,
                });
                fermerModal();
                notify.success('Projet créé avec succès');
                chargerProjets();
            } catch (err: any) {
                notify.error(err.message);
            } finally {
                setFormSaving(false);
            }
            return;
        }

        if (modalMode === 'edit' && projetActif) {
            setFormSaving(true);
            try {
                await modifierProjet(projetActif.id, {
                    nom: nom.trim(),
                    description: description.trim() || undefined,
                });

                const typesActuels = projetActif.typesAttendus.map(t => t.typeDocumentId);
                const aAjouter = selectedTypeIds.filter(id => !typesActuels.includes(id));
                const aRetirer = typesActuels.filter(id => !selectedTypeIds.includes(id));

                if (aAjouter.length > 0) {
                    await ajouterTypesAttendus(projetActif.id, aAjouter);
                }

                let retraitsEchoues = 0;
                for (const typeId of aRetirer) {
                    try {
                        await retirerTypeAttendu(projetActif.id, typeId);
                    } catch {
                        retraitsEchoues++;
                    }
                }

                rafraichirProjetActif();
                chargerProjets(); // le nom a pu changer, la liste doit suivre
                fermerModal();

                if (retraitsEchoues > 0) {
                    notify.error(
                        `Projet mis à jour, mais ${retraitsEchoues} type${retraitsEchoues > 1 ? 's' : ''} `
                        + `n'ont pas pu être retiré(s) — des documents de ce type existent déjà dans ce projet.`
                    );
                } else {
                    notify.success('Projet mis à jour avec succès');
                }
            } catch (err: any) {
                notify.error(err.message);
            } finally {
                setFormSaving(false);
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Navigation : projets → types
    // ─────────────────────────────────────────────────────────────────────

    const ouvrirProjet = (id: number) => {
        setProjetActifLoading(true);
        setPanelView('types');
        getProjetDetail(id)
            .then(setProjetActif)
            .catch(err => { notify.error(err.message); setPanelView('projets'); })
            .finally(() => setProjetActifLoading(false));
    };

    /** Recharge le projet ouvert sans changer de vue — après ajout/retrait d'un type attendu. */
    const rafraichirProjetActif = () => {
        if (!projetActif) return;
        getProjetDetail(projetActif.id).then(setProjetActif).catch(() => {});
    };

    /**
     * Retrait rapide d'un type depuis sa carte-dossier (la croix au survol) —
     * sans passer par le modal de modification, contrairement à
     * handleEnregistrer. Le serveur refuse toujours ce retrait si des
     * documents de ce type existent déjà dans ce projet.
     */
    const handleRetirerTypeRapide = async (e: React.MouseEvent, typeId: number) => {
        e.stopPropagation();
        if (!projetActif) return;
        try {
            await retirerTypeAttendu(projetActif.id, typeId);
            rafraichirProjetActif();
        } catch (err: any) {
            notify.error(err.message);
        }
    };

    const retourAuxProjets = () => {
        setPanelView('projets');
        setProjetActif(null);
    };

    const handleSupprimer = async (id: number, nomProjet: string) => {
        if (!(await confirm({ message: `Supprimer définitivement le projet "${nomProjet}" ?`, danger: true }))) return;
        try {
            await supprimerProjet(id);
            notify.success('Projet supprimé avec succès');
            retourAuxProjets();
            chargerProjets();
        } catch (err: any) {
            notify.error(err.message);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Navigation : types → documents d'un type
    // ─────────────────────────────────────────────────────────────────────

    const chargerDocumentsDuType = (type: TypeAttenduDto, page: number) => {
        if (!projetActif) return;
        setDocsLoading(true);
        getDocumentsAccessibles({
            projetId:       projetActif.id,
            typeDocumentId: type.typeDocumentId,
            page,
            size: 10,
        })
            .then(result => {
                setDocuments(result.content);
                setDocsTotal(result.totalElements);
                setDocsPages(result.totalPages);
                setDocsPage(page);
                setPreviews({});
            })
            .catch(err => notify.error(err.message ?? 'Erreur chargement documents'))
            .finally(() => setDocsLoading(false));
    };

    const ouvrirType = (type: TypeAttenduDto) => {
        setTypeActif(type);
        setPanelView('documents');
        chargerDocumentsDuType(type, 1);
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
            let blobUrl: string | null = null;
            try {
                blobUrl = await streamPdfAAsBlob(id);
                const thumbnail = await renderPdfFirstPageThumbnail(blobUrl);
                if (!annule) setPreviews(prev => ({ ...prev, [id]: thumbnail }));
            } catch {
                // Silencieux — la carte retombe sur son placeholder générique.
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
    // les 3 vues (documents/types/projets, voir plus bas) : "Modifier" se
    // déclenche depuis la vue "types", donc le modal doit rester monté dans
    // CETTE vue plutôt que dans la seule vue "projets", sans quoi il ne
    // s'affichait qu'après être retourné à la liste des projets.
    const modalCreationEdition = (
        <Modal
            isOpen={modalMode !== null}
            onClose={fermerModal}
            title={modalMode === 'edit' ? 'Modifier le projet' : 'Créer un projet'}
        >
            <div className="projets-create-form">
                <input
                    type="text"
                    placeholder="Nom du projet *"
                    value={nom}
                    onChange={e => setNom(e.target.value)}
                />
                <textarea
                    placeholder="Description (optionnel)"
                    value={description}
                    onChange={e => setDescription(e.target.value)}
                />

                {/* Accès (Public/Privé) — fixé à la création, non modifiable ici
                    (voir "Voir qui a accès" dans la vue du projet pour gérer les
                    membres d'un projet déjà privé). */}
                {modalMode === 'create' && (
                    <div className="projets-access-picker">
                        <label className="projets-access-radio">
                            <input
                                type="radio"
                                name="projet-access"
                                checked={accessCreation === 'PUBLIC'}
                                onChange={() => setAccessCreation('PUBLIC')}
                            />
                            <span>Public — visible par tous les membres de l'UO</span>
                        </label>
                        <label className="projets-access-radio">
                            <input
                                type="radio"
                                name="projet-access"
                                checked={accessCreation === 'PRIVE'}
                                onChange={() => setAccessCreation('PRIVE')}
                            />
                            <span>Privé — visible uniquement par les membres choisis</span>
                        </label>
                    </div>
                )}

                {modalMode === 'create' && accessCreation === 'PRIVE' && usersUO.length > 0 && (
                    <div className="projets-types-picker">
                        <p>Membres du groupe d'accès (vous serez ajouté automatiquement) :</p>
                        <div className="projets-types-list">
                            {usersUO.map(u => (
                                <label key={u.id} className="projets-type-checkbox">
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
                    <div className="projets-types-picker">
                        <p>Types de documents attendus :</p>
                        <input
                            type="text"
                            className="projets-type-search"
                            placeholder="Rechercher un type de document..."
                            value={filtreTypeModal}
                            onChange={e => setFiltreTypeModal(e.target.value)}
                        />
                        <div className="projets-types-list-vertical">
                            {(() => {
                                const typesAffiches = typesUO.filter(t =>
                                    t.nom.toLowerCase().includes(filtreTypeModal.trim().toLowerCase())
                                );
                                if (typesAffiches.length === 0) {
                                    return <p className="projets-types-list-empty">Aucun type ne correspond.</p>;
                                }
                                return typesAffiches.map(t => {
                                    // En modification, un type qui a déjà des documents DANS
                                    // CE PROJET ne peut pas être décoché — le serveur le
                                    // refuserait de toute façon (voir handleEnregistrer).
                                    const nonRetirable = modalMode === 'edit' && projetActif
                                        ? projetActif.typesAttendus.some(a => a.typeDocumentId === t.id && a.fourni)
                                        : false;
                                    return (
                                        <label key={t.id} className="projets-type-row">
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

                <div className="projets-create-actions">
                    <button
                        className="sidebar-btn"
                        disabled={!nom.trim() || formSaving}
                        onClick={handleEnregistrer}
                    >
                        {formSaving
                            ? (modalMode === 'edit' ? 'Enregistrement…' : 'Création…')
                            : (modalMode === 'edit' ? 'Enregistrer' : 'Créer')}
                    </button>
                    <button className="projets-cancel-btn" onClick={fermerModal}>
                        Annuler
                    </button>
                </div>
            </div>
        </Modal>
    );

    if (!uoId) {
        return <div className="projets-panel-empty">Sélectionnez une unité organisationnelle.</div>;
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
                        <iframe src={pdfBlobUrl} className="pdf-viewer-iframe" title="Lecteur PDF" />
                    ) : (
                        <div className="td-empty"><p>Impossible de charger le document.</p></div>
                    )}
                </div>
            </div>
        );
    }

    // ── Vue "documents" : documents d'un type, à l'intérieur d'un projet ──
    if (panelView === 'documents' && projetActif && typeActif) {
        return (
            <div className="mes-docs-wrapper">
                <div className="docs-breadcrumb">
                    <button className="breadcrumb-back" onClick={retourAuxTypes}>
                        <i className="fa-solid fa-arrow-left" /> {projetActif.nom}
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
                        <p>Aucun document de ce type dans ce projet pour l'instant.</p>
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

    // ── Vue "types" : dossiers des types de documents attendus d'un projet ──
    if (panelView === 'types' && (projetActif || projetActifLoading)) {
        return (
            <div className="mes-docs-wrapper">
                <div className="docs-breadcrumb">
                    <button className="breadcrumb-back" onClick={retourAuxProjets}>
                        <i className="fa-solid fa-arrow-left" /> Projets
                    </button>
                    <i className="fa-solid fa-chevron-right breadcrumb-sep" />
                    <span className="breadcrumb-current">{projetActif?.nom}</span>
                    {projetActif?.access === 'PRIVE' && (
                        <span className="doc-access-tag prive">Privé</span>
                    )}

                    {projetActif?.access === 'PRIVE' && (
                        <button className="breadcrumb-add-btn" onClick={() => setIsGroupeOpen(true)}>
                            <i className="fa-solid fa-user-group" /> Accès
                        </button>
                    )}
                    {projetActif?.peutGererTypes && (
                        <button className="breadcrumb-add-btn" onClick={ouvrirEdition}>
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                    {projetActif?.peutGererAcces && (
                        <button
                            className="breadcrumb-add-btn"
                            style={{ background: 'var(--error)' }}
                            onClick={() => handleSupprimer(projetActif.id, projetActif.nom)}
                        >
                            <i className="fa-solid fa-trash" /> Supprimer
                        </button>
                    )}
                </div>

                {projetActif && (
                    <p className="projets-detail-meta" style={{ marginBottom: '0.75rem' }}>
                        {projetActif.description && <>{projetActif.description} — </>}
                        Créé par {projetActif.creePar} le {formatDate(projetActif.createAt)}
                    </p>
                )}

                {projetActifLoading ? (
                    <div className="td-loading">
                        <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                    </div>
                ) : !projetActif || projetActif.typesAttendus.length === 0 ? (
                    <div className="td-empty">
                        <i className="fa-solid fa-folder-open" style={{ fontSize: '2.5rem', color: 'var(--text-muted)', marginBottom: '0.75rem' }} />
                        <p>Aucun type de document attendu déclaré pour l'instant.</p>
                    </div>
                ) : (
                    <div className="folders-grid">
                        {projetActif.typesAttendus.map(t => (
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
                                    document dans ce projet et si l'utilisateur peut gérer les
                                    types de ce projet. Pas besoin de passer par "Modifier". */}
                                {projetActif?.peutGererTypes && !t.fourni && (
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


                {/* ── Modal groupe d'accès du projet ── */}
                <Modal
                    isOpen={isGroupeOpen}
                    onClose={() => setIsGroupeOpen(false)}
                    title="Accès au projet"
                >
                    {projetActif && (
                        <GestionGroupeProjet
                            projetId={projetActif.id}
                            projetNom={projetActif.nom}
                            onClose={() => setIsGroupeOpen(false)}
                        />
                    )}
                </Modal>

                {/* Le bouton "Modifier" est ICI, dans cette vue — le modal doit donc
                    y être monté aussi, pas seulement dans la vue "projets". */}
                {modalCreationEdition}
            </div>
        );
    }

    // ── Vue "projets" : liste des projets de l'UO ──────────────────────────
    return (
        <div className="projets-panel">
            {canCreate && (
                <div className="projets-panel-header">
                    <button className="sidebar-btn" onClick={ouvrirCreation}>
                        <i className="fa-solid fa-folder-plus" /> Créer un projet
                    </button>
                </div>
            )}

            {modalCreationEdition}

            {projets.length > 0 && (
                <div className="filtres-panel">
                    <div className="filtres-grid">
                        <div className="filtre-field filtre-field-titre">
                            <input
                                type="text"
                                className="filter-input"
                                placeholder="Nom du projet"
                                aria-label="Filtrer par nom du projet"
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
                            onClick={reinitialiserFiltresProjets}
                            title="Réinitialiser les filtres"
                            aria-label="Réinitialiser les filtres"
                            disabled={nbFiltresProjetsActifs === 0}
                        >
                            <i className="fa-solid fa-rotate-left" />
                        </button>
                    </div>
                </div>
            )}

            {loading ? (
                <p>Chargement…</p>
            ) : projets.length === 0 ? (
                <p className="projets-panel-empty">Aucun projet pour cette unité organisationnelle.</p>
            ) : projetsFiltres.length === 0 ? (
                <p className="projets-panel-empty">Aucun projet ne correspond à ces filtres.</p>
            ) : (
                <div className="folders-grid">
                    {projetsFiltres.map(p => (
                        <div
                            key={p.id}
                            className="folder-card"
                            onClick={() => ouvrirProjet(p.id)}
                            role="button"
                            tabIndex={0}
                            onKeyDown={e => e.key === 'Enter' && ouvrirProjet(p.id)}
                            aria-label={`Ouvrir le projet ${p.nom}`}
                        >
                            <div className="folder-icon-wrap">
                                <div className="folder-tab" />
                                <div className="folder-back" />
                                <div className="document-sheet">
                                    <div className="doc-line short" />
                                    <div className="doc-line" />
                                    <div className="doc-line" />
                                </div>
                                <div className="glass-pocket">
                                    <i className="fa-solid fa-bars-progress folder-type-watermark" />
                                </div>
                            </div>

                            <span className="folder-name">{p.nom}</span>
                            <span className="folder-meta">
                                {p.creePar?.prenom} {p.creePar?.nom} · {new Date(p.createAt).toLocaleDateString('fr-FR')}
                            </span>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Sous-composant : détail document en LECTURE SEULE (pas d'édition depuis les
// projets — emplacement physique, métadonnées, versions... se gèrent depuis
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

export default ProjetsPanel;
