import { useState, useEffect } from 'react';
import Sidebar from '../Page/Sidebar';
import Profile from '../Page/Profil';
import Modal from '../Page/Modal';
import UploadSimple from '../document/UploadSimple';
import UploadBulkSameType from '../document/UploadeBlukSameType';
import DownloadTemplate from '../document/DownloadTemplate';
import MesDocumentsEditor from './MesDocumentsEditor';
import DocumentsAccessibles from '../document/DocumentsAccessible';
import ProjetsPanel from '../organisation/ProjetsPanel';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import '../Style/Editor/Editor.css';

// L'import en masse multi-type (UploadeBlukMultiType.tsx) appelait
// POST /api/editor/docs/bulk/multi-type, qui n'a jamais existé côté backend
// (seul /docs/bulk/same-type/* existe) — bouton retiré tant que cette
// fonctionnalité n'a pas de véritable implémentation serveur. Le composant
// reste dans le repo (document/UploadeBlukMultiType.tsx) si besoin de le
// reprendre plus tard.
type EditorView  = 'documents' | 'accessibles' | 'projets' | 'profile';
type UploadMode  = 'simple' | 'bulk-same';

function EditorDashboard() {
    const userInfo = getCurrentUserInfo();

    const [currentView, setCurrentView] = useState<EditorView>('documents');
    const [uploadMode,  setUploadMode]  = useState<UploadMode>('simple');

    // Nom + id de l'UO de rattachement — affichés dans le titre du Sidebar,
    // et l'id sert de scope pour le panneau Projets ci-dessous.
    const [uoNom, setUoNom] = useState<string>('');
    const [uoId,  setUoId]  = useState<number | null>(null);

    // Modales sidebar
    const [isUploadModalOpen,   setIsUploadModalOpen]   = useState(false);
    const [isTemplateModalOpen, setIsTemplateModalOpen] = useState(false);

    // Refresh de la grille après upload
    const [refreshDocs, setRefreshDocs] = useState(0);

    // Type pré-sélectionné transmis depuis la grille (bouton "+")
    // null = pas de pré-sélection, undefined = consommé
    const [preselectedTypeId, setPreselectedTypeId] = useState<number | null>(null);

    // Alertes sidebar
    const [success, setSuccess] = useState('');
    const [error,   setError]   = useState('');

    useEffect(() => {
        getMyUO().then(uo => { setUoNom(uo.nom); setUoId(uo.id); }).catch(() => {});
    }, []);

    // ── Handlers ────────────────────────────────────────────────────────────

    const handleUploadSuccess = () => {
        setIsUploadModalOpen(false);
        setSuccess("Document(s) uploadé(s) avec succès");
        setRefreshDocs(r => r + 1);
        setTimeout(() => setSuccess(''), 3000);
    };

    /** Ouvre le modal d'upload depuis la sidebar (sans pré-sélection de type) */
    const openUpload = (mode: UploadMode) => {
        setUploadMode(mode);
        setIsUploadModalOpen(true);
    };

    const UPLOAD_TITLES: Record<UploadMode, string> = {
        'simple':     'Uploader un document',
        'bulk-same':  'Import en masse — même type',
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

                            {/* Upload */}
                            <div className="sidebar-section-label">Upload</div>
                            <div className="main-header">
                                <button className="sidebar-btn" onClick={() => openUpload('simple')}>
                                    <i className="fa-solid fa-file-arrow-up" /> Uploader un document
                                </button>
                            </div>
                            <div className="main-header">
                                <button className="sidebar-btn" onClick={() => openUpload('bulk-same')}>
                                    <i className="fa-solid fa-layer-group" /> Import masse — même type
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
                                    <i className="fa-solid fa-folder-open" /> Documents accessibles
                                </button>
                            </div>
                            <div className="main-header">
                                <button
                                    className="sidebar-btn"
                                    onClick={() => setIsTemplateModalOpen(true)}
                                >
                                    <i className="fa-solid fa-file-export" /> Télécharger template
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

                            {/* Alertes */}
                            {error   && <div className="alert alert-error">{error}</div>}
                            {success && <div className="alert alert-success">{success}</div>}
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
                </div>

                {/* Modal upload sidebar */}
                <Modal
                    isOpen={isUploadModalOpen}
                    onClose={() => setIsUploadModalOpen(false)}
                    title={UPLOAD_TITLES[uploadMode]}
                >
                    {uploadMode === 'simple' && (
                        <UploadSimple onsuccess={handleUploadSuccess} />
                    )}
                    {uploadMode === 'bulk-same' && (
                        <UploadBulkSameType onsuccess={handleUploadSuccess} />
                    )}
                </Modal>

                {/* Modal template */}
                <Modal
                    isOpen={isTemplateModalOpen}
                    onClose={() => setIsTemplateModalOpen(false)}
                    title="Télécharger un template d'import"
                >
                    <DownloadTemplate />
                </Modal>
            </div>
        </div>
    );
}

export default EditorDashboard;