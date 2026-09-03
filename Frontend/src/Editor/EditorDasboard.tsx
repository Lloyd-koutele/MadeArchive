import { useState, useEffect } from 'react';
import Sidebar from '../Page/Sidebar';
import Profile from '../Page/Profil';
import Modal from '../Page/Modal';
import ImportDocuments from '../document/ImportDocuments';
import MesDocumentsEditor from './MesDocumentsEditor';
import DocumentsAccessibles from '../document/DocumentsAccessible';
import Corbeille from '../document/Corbeille';
import ProjetsPanel from '../organisation/ProjetsPanel';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import '../Style/Editor/Editor.css';
import { useNotify } from '../notifications/NotificationProvider';

type EditorView  = 'documents' | 'accessibles' | 'corbeille' | 'projets' | 'profile';

function EditorDashboard() {
    const userInfo = getCurrentUserInfo();
    const notify = useNotify();

    const [currentView, setCurrentView] = useState<EditorView>('documents');

    // Nom + id de l'UO de rattachement — affichés dans le titre du Sidebar,
    // et l'id sert de scope pour le panneau Projets ci-dessous.
    const [uoNom, setUoNom] = useState<string>('');
    const [uoId,  setUoId]  = useState<number | null>(null);

    // Modales sidebar
    const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);

    // Refresh de la grille après upload
    const [refreshDocs, setRefreshDocs] = useState(0);

    // Type pré-sélectionné transmis depuis la grille (bouton "+")
    // null = pas de pré-sélection, undefined = consommé
    const [preselectedTypeId, setPreselectedTypeId] = useState<number | null>(null);

    useEffect(() => {
        getMyUO().then(uo => { setUoNom(uo.nom); setUoId(uo.id); }).catch(() => {});
    }, []);

    // ── Handlers ────────────────────────────────────────────────────────────

    const handleUploadSuccess = () => {
        setIsUploadModalOpen(false);
        notify.success("Document(s) uploadé(s) avec succès");
        setRefreshDocs(r => r + 1);
    };

    const sidebarTitle = `${userInfo?.role || "ÉDITEUR"}${uoNom ? ` — ${uoNom}` : ''}`;

    return (
        <div className="admin-dashboard">
            <div className="admin-body">

                <Sidebar title={sidebarTitle}>
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

                            {/* Documents */}
                            <div className="sidebar-section-label">Documents</div>
                            <div className="main-header">
                                <button
                                    className={`sidebar-btn ${currentView === 'documents' ? 'active-tab' : ''}`}
                                    onClick={() => setCurrentView('documents')}
                                >
                                    <i className="fa-solid fa-folder-open" /> Mes documents
                                </button>
                            </div>
                            <div className="main-header">
                                <button
                                    className={`sidebar-btn ${currentView === 'accessibles' ? 'active-tab' : ''}`}
                                    onClick={() => setCurrentView('accessibles')}
                                >
                                    <i className="fa-solid fa-folder-open" /> Documents
                                </button>
                            </div>
                            <div className="main-header">
                                <button
                                    className={`sidebar-btn ${currentView === 'projets' ? 'active-tab' : ''}`}
                                    onClick={() => setCurrentView('projets')}
                                >
                                    <i className="fa-solid fa-folder-tree" /> Projets
                                </button>
                            </div>
                            <div className="main-header">
                                <button
                                    className={`sidebar-btn ${currentView === 'corbeille' ? 'active-tab' : ''}`}
                                    onClick={() => setCurrentView('corbeille')}
                                >
                                    <i className="fa-solid fa-trash-can" /> Corbeille
                                </button>
                            </div>

                        </div>
                    </nav>
                </Sidebar>

                {/* Contenu principal */}
                <div className="main-content">
                    {currentView === 'profile' && (
                        <Profile userId={userInfo?.id} />
                    )}
                    {currentView === 'documents' && (
                        <MesDocumentsEditor
                            refreshTrigger={refreshDocs}
                            preselectedTypeId={preselectedTypeId}
                            onPreselectedConsumed={() => setPreselectedTypeId(null)}
                        />
                    )}
                    {currentView === 'accessibles' && (
                        <DocumentsAccessibles />
                    )}
                    {currentView === 'projets' && (
                        <ProjetsPanel uoId={uoId} />
                    )}
                    {currentView === 'corbeille' && (
                        <Corbeille />
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
                </div>
            </div>
        </div>
    );
}

export default EditorDashboard;