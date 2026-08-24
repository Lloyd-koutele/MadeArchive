import { useState, useEffect } from 'react';
import Sidebar from '../Page/Sidebar';
import Profile from '../Page/Profil';
import DocumentsAccessibles from '../document/DocumentsAccessible';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import ProjetsPanel from '../organisation/ProjetsPanel';
import '../Style/User/User.css';

type UserView = 'documents' | 'projets' | 'profile';

// ─── UserDashboard principal ───
function UserDashboard() {
    const userInfo = getCurrentUserInfo();
    const [currentView, setCurrentView] = useState<UserView>('documents');

    // UO de rattachement — affichée dans le titre du Sidebar, et l'id sert de
    // scope pour le panneau Projets (lecture seule pour ce rôle).
    const [uoNom, setUoNom] = useState<string>('');
    const [uoId, setUoId] = useState<number | null>(null);

    useEffect(() => {
        getMyUO().then(uo => { setUoNom(uo.nom); setUoId(uo.id); }).catch(() => {});
    }, []);

    const sidebarTitle = `${userInfo?.role || "UTILISATEUR"}${uoNom ? ` — ${uoNom}` : ''}`;

    return (
        <div className="admin-dashboard">
            <div className="admin-body">

                <Sidebar title={sidebarTitle}>
                    <nav className="sidebar-nav">
                        <div>
                            <div className="main-header">
                                <button
                                    onClick={() => setCurrentView('profile')}
                                    className={`sidebar-btn ${currentView === 'profile' ? 'active-tab' : ''}`}
                                >
                                    👤 Mon Profil
                                </button>
                            </div>

                            <div className="sidebar-section-label">Documents</div>
                            <div className="main-header">
                                <button
                                    onClick={() => setCurrentView('documents')}
                                    className={`sidebar-btn ${currentView === 'documents' ? 'active-tab' : ''}`}
                                >
                                    Documents
                                </button>
                            </div>
                            <div className="main-header">
                                <button
                                    onClick={() => setCurrentView('projets')}
                                    className={`sidebar-btn ${currentView === 'projets' ? 'active-tab' : ''}`}
                                >
                                    Projets
                                </button>
                            </div>
                        </div>
                    </nav>
                </Sidebar>

                <div className="main-content">
                    {currentView === 'profile' && <Profile userId={userInfo?.id} />}
                    {currentView === 'documents' && <DocumentsAccessibles />}
                    {currentView === 'projets' && <ProjetsPanel uoId={uoId} canCreate={false} />}
                </div>
            </div>
        </div>
    );
}

export default UserDashboard;
