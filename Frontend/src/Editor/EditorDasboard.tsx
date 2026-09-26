import { useState, useEffect } from 'react';
import Sidebar from '../Page/Sidebar';
import Profile from '../Page/Profil';
import Modal from '../Page/Modal';
import ImportDocuments from '../document/ImportDocuments';
import type { BulkUploadReportDto } from '../services/document/DocumentService';
import MesDocumentsEditor from './MesDocumentsEditor';
import DocumentsAccessibles from '../document/DocumentsAccessible';
import Corbeille from '../document/Corbeille';
import DossiersPanel from '../organisation/DossiersPanel';
import PhysicalLocationsPanel from '../organisation/PhysicalLocationsPanel';
import TypeDocumentList from '../document/TypedocumentList';
import CreateTypeDocument from '../document/Createtypedocument';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import '../Style/Editor/Editor.css';
// .uo-tabs/.uo-tab — même barre d'onglets horizontale que dans l'espace de
// travail admin (AdminUoDashboard), réutilisée telle quelle plutôt que
// dupliquée, pour rester visuellement identique si son style évolue un jour.
import '../Style/Admin/AdminDashboard.css';
import { useNotify } from '../notifications/NotificationProvider';

type EditorView = 'documents' | 'profile';

// Sous-onglets de la vue "Documents" — voir la barre .uo-tabs plus bas.
// Ordre demandé : Documents, puis Mes documents, puis le reste. "Types de
// documents" : gestion (créer/lire/modifier/supprimer) désormais réservée
// aux EDITOR de leur propre UO — retirée des dashboards ADMIN/ADMIN_UO (voir
// DocumentController /api/editor, backend).
type DocumentsTab = 'accessibles' | 'mesDocuments' | 'dossiers' | 'typesDocuments' | 'emplacements' | 'corbeille';

function EditorDashboard() {
    const userInfo = getCurrentUserInfo();
    const notify = useNotify();

    const [currentView, setCurrentView] = useState<EditorView>('documents');
    const [documentsTab, setDocumentsTab] = useState<DocumentsTab>('accessibles');

    // Nom + id de l'UO de rattachement — affichés dans le titre du Sidebar,
    // et l'id sert de scope pour le panneau Dossiers ci-dessous.
    const [uoNom, setUoNom] = useState<string>('');
    const [uoId,  setUoId]  = useState<number | null>(null);

    // Modales sidebar
    const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);
    const [isCreateTdModalOpen, setIsCreateTdModalOpen] = useState(false);

    // Refresh de la grille après upload
    const [refreshDocs, setRefreshDocs] = useState(0);

    // Refresh de la liste des types de documents après création/modification/suppression
    const [tdRefresh, setTdRefresh] = useState(0);

    // Type pré-sélectionné transmis depuis la grille (bouton "+")
    // null = pas de pré-sélection, undefined = consommé
    const [preselectedTypeId, setPreselectedTypeId] = useState<number | null>(null);

    useEffect(() => {
        getMyUO().then(uo => { setUoNom(uo.nom); setUoId(uo.id); }).catch(() => {});
    }, []);

    // ── Handlers ────────────────────────────────────────────────────────────

    // Avant : ignorait le rapport et affichait "Succès" inconditionnellement,
    // y compris quand TOUS les documents avaient échoué (ex. signature PKI
    // impossible — keystore HSM désynchronisé du mot de passe courant,
    // constaté en conditions réelles) — l'échec réel n'apparaissait alors
    // nulle part côté utilisateur. Le rapport (déjà renvoyé par
    // ImportDocuments, juste jamais lu ici) distingue maintenant succès
    // total / partiel / échec total.
    const handleUploadSuccess = (report: BulkUploadReportDto) => {
        setIsUploadModalOpen(false);
        setRefreshDocs(r => r + 1);

        if (report.failed === 0)
        {
            notify.success(
                report.success > 1
                    ? `${report.success} documents uploadés avec succès`
                    : "Document uploadé avec succès"
            );
        }
        else if (report.success === 0)
        {
            const premiereErreur = report.details.find(d => d.status === 'FAILED')?.erreur;
            notify.error(
                `Échec de l'upload — ${report.failed} document(s) non archivé(s)`
                + (premiereErreur ? ` : ${premiereErreur}` : '')
            );
        }
        else
        {
            notify.warning(
                `${report.success} document(s) archivé(s), ${report.failed} échec(s) — voir le détail`
            );
        }
    };

    const handleTdCreated = () => {
        setIsCreateTdModalOpen(false);
        setTdRefresh(r => r + 1);
        notify.success("Type de document créé avec succès");
    };

    const sidebarTitle = `${userInfo?.role || "ÉDITEUR"}${uoNom ? ` — ${uoNom}` : ''}`;

    return (
        <div className="admin-dashboard">
            <div className="admin-body">

                <Sidebar title={sidebarTitle} onTitleClick={() => setCurrentView('documents')}>
                    <nav className="sidebar-nav">
                        <div>
                            {/* Profil */}
                            <div className="main-header">
                                <button
                                    onClick={() => setCurrentView('profile')}
                                    className={`sidebar-btn ${currentView === 'profile' ? 'active-tab' : ''}`}
                                >
                                    👤 Mon Profil
                                </button>
                            </div>

                            {/* Import */}
                            <div className="sidebar-section-label">Import</div>
                            <div className="main-header">
                                <button className="sidebar-btn" onClick={() => setIsUploadModalOpen(true)}>
                                    <i className="fa-solid fa-file-arrow-up" /> Archiver
                                </button>
                            </div>

                            {/* Plus de bouton "Documents" ici — les 5 sous-vues
                                (Documents/Mes documents/Dossiers/Emplacements
                                physiques/Corbeille) vivent dans la barre
                                d'onglets .uo-tabs de l'espace de travail (voir
                                plus bas), comme côté admin. "documents" reste
                                la vue par défaut au chargement, et l'en-tête
                                de la sidebar (logo + libellé, onTitleClick
                                ci-dessus) permet d'y revenir depuis "Mon
                                Profil". */}

                        </div>
                    </nav>
                </Sidebar>

                {/* Contenu principal */}
                <div className="main-content">
                    {currentView === 'profile' && (
                        <Profile userId={userInfo?.id} />
                    )}

                    {currentView === 'documents' && (
                        <>
                            {/* Barre d'onglets — identique à celle de l'admin
                                (.uo-tabs/.uo-tab), ordre demandé : Documents,
                                Mes documents, puis le reste. */}
                            <div className="uo-tabs">
                                <button
                                    className={`uo-tab ${documentsTab === 'accessibles' ? 'active' : ''}`}
                                    onClick={() => setDocumentsTab('accessibles')}
                                >
                                    Documents
                                </button>
                                <button
                                    className={`uo-tab ${documentsTab === 'mesDocuments' ? 'active' : ''}`}
                                    onClick={() => setDocumentsTab('mesDocuments')}
                                >
                                    Mes documents
                                </button>
                                <button
                                    className={`uo-tab ${documentsTab === 'dossiers' ? 'active' : ''}`}
                                    onClick={() => setDocumentsTab('dossiers')}
                                >
                                    Dossiers
                                </button>
                                <button
                                    className={`uo-tab ${documentsTab === 'typesDocuments' ? 'active' : ''}`}
                                    onClick={() => setDocumentsTab('typesDocuments')}
                                >
                                    Types de documents
                                </button>
                                <button
                                    className={`uo-tab ${documentsTab === 'emplacements' ? 'active' : ''}`}
                                    onClick={() => setDocumentsTab('emplacements')}
                                >
                                    Emplacements physiques
                                </button>
                                <button
                                    className={`uo-tab ${documentsTab === 'corbeille' ? 'active' : ''}`}
                                    onClick={() => setDocumentsTab('corbeille')}
                                >
                                    <i className="fa-solid fa-trash-can" /> Corbeille
                                </button>
                            </div>

                            {documentsTab === 'accessibles' && (
                                <DocumentsAccessibles />
                            )}
                            {documentsTab === 'mesDocuments' && (
                                <MesDocumentsEditor
                                    refreshTrigger={refreshDocs}
                                    preselectedTypeId={preselectedTypeId}
                                    onPreselectedConsumed={() => setPreselectedTypeId(null)}
                                />
                            )}
                            {documentsTab === 'dossiers' && (
                                <DossiersPanel uoId={uoId} />
                            )}
                            {documentsTab === 'typesDocuments' && uoId !== null && (
                                <>
                                    <div className="main-header">
                                        <button
                                            className="sidebar-btn"
                                            onClick={() => setIsCreateTdModalOpen(true)}
                                        >
                                            Créer un type
                                        </button>
                                    </div>
                                    <TypeDocumentList refreshTrigger={tdRefresh} uoId={uoId} />
                                </>
                            )}
                            {documentsTab === 'emplacements' && (
                                <PhysicalLocationsPanel uoId={uoId} mode="gestion" />
                            )}
                            {documentsTab === 'corbeille' && (
                                <Corbeille />
                            )}
                        </>
                    )}

                    {/* Modal import sidebar — DANS .main-content, pas à côté :
                        c'est cette imbrication qui fait que le modal se centre
                        par rapport à la zone de contenu (voir le transform sur
                        .main-content dans AdminDashboard.css), pas sur toute la
                        fenêtre sidebar comprise. Un modal frère de .main-content
                        au lieu d'un descendant perdait ce centrage — c'était le
                        bug ici. */}
                    <Modal
                        isOpen={isUploadModalOpen}
                        onClose={() => setIsUploadModalOpen(false)}
                        title="Importer des documents"
                        size="large"
                    >
                        <ImportDocuments onsuccess={handleUploadSuccess} />
                    </Modal>

                    <Modal
                        isOpen={isCreateTdModalOpen}
                        onClose={() => setIsCreateTdModalOpen(false)}
                        title="Créer un type de document"
                    >
                        {uoId !== null && (
                            <CreateTypeDocument onsuccess={handleTdCreated} restrictToUO={{ id: uoId, nom: uoNom }} />
                        )}
                    </Modal>
                </div>
            </div>
        </div>
    );
}

export default EditorDashboard;