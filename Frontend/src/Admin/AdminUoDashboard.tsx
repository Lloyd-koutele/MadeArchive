// AdminUoDashboard.tsx
import { useState, useEffect, useCallback, type FormEvent } from 'react';
import Sidebar from "../Page/Sidebar";
import UOTree from "../organisation/UOTree";
import UserTable from "./UserTable";
import CreateUser from "./CreateUser";
import UpdateUser from "./UpdateUser";
import Modal from "../Page/Modal";
import Profile from "../Page/Profil";
import TypeDocumentList from "../document/TypedocumentList";
import CreateTypeDocument from "../document/Createtypedocument";
import AssignUOModal from "./AssignUOModal";
import QuickCreateTypeDocumentsModal from '../document/QuickCreateTypeDocumentsModal';
import ProjetsPanel from '../organisation/ProjetsPanel';
import PhysicalLocationsPanel from '../organisation/PhysicalLocationsPanel';
import AuditLogPanel from './AuditLogPanel';
import DocumentsArchivesPanel from './DocumentsArchivesPanel';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getUsersByUO, updateUserStatus as updateStatus } from "../services/admin/AdminService";
import {
    getMyUO,
    getSousArbre,
    createUO,
    updateUO,
    retirerMembreUO,
    retirerMembreEtAdmin,
    transfererMembreUO
} from "../services/organisation/UOService";
import "../Style/Admin/AdminDashboard.css";
import { getCurrentUserInfo } from "../auth/authService";
import FilterUsers from "../hooks/FilterUsers";
import Pagination from "../hooks/Pagination";

interface RoleField {
    name: "ADMIN" | "ADMIN_UO" | "EDITOR" | "USER";
}

interface User {
    id: string;
    nom: string;
    prenom: string;
    email: string;
    telephone: string;
    actif: boolean | string;
    roles: RoleField[];
    uoId?: number | null;
    uoNom?: string | null;
}

interface UserFilters {
    nom: string;
    prenom: string;
    email: string;
    telephone: string;
    roles: string[];
}

interface UONode {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

type MainView = 'profile' | 'contenu';
type Tab = 'utilisateurs' | 'documents' | 'archives' | 'projets' | 'emplacements' | 'journal';

const isUserActive = (user: User): boolean => user.actif === true || user.actif === 'true';

const getErrorMessage = (err: unknown, fallbackMessage: string): string => {
    if (err instanceof Error) return err.message;
    if (typeof err === 'object' && err !== null && 'message' in err) {
        return String((err as { message: unknown }).message);
    }
    return fallbackMessage;
};

// Dashboard dédié ADMIN_UO. Navigation par arbre complet, toujours bornée au
// sous-arbre de son UO racine (getSousArbre) — jamais de vue "globale" ici.
function AdminUoDashboard() {
    const userInfo = getCurrentUserInfo();

    const [mainView, setMainView] = useState<MainView>('contenu');
    const [tab, setTab] = useState<Tab>('utilisateurs');

    const [rootUO, setRootUO] = useState<UONode | null>(null);
    const [treeNodes, setTreeNodes] = useState<UONode[]>([]);
    const [currentUOId, setCurrentUOId] = useState<number | null>(null);

    const currentUO = treeNodes.find(n => n.id === currentUOId) ?? null;

    const [users, setUsers] = useState<User[]>([]);
    const [isCreateUserModalOpen, setIsCreateUserModalOpen] = useState(false);
    const [isUpdateModalOpen, setIsUpdateModalOpen] = useState(false);
    const [isViewModalOpen, setIsViewModalOpen] = useState(false);
    const [isCreateTdModalOpen, setIsCreateTdModalOpen] = useState(false);
    const [isCreateUOModalOpen, setIsCreateUOModalOpen] = useState(false);
    const [createUOParentId, setCreateUOParentId] = useState<number | null>(null);
    const [createUONom, setCreateUONom] = useState('');
    const [selectedUser, setSelectedUser] = useState<User | null>(null);
    const [viewingUser, setViewingUser] = useState<User | null>(null);
    const [actionInProgress, setActionInProgress] = useState(false);
    const [tdRefresh, setTdRefresh] = useState(0);

    const [assigningUserId, setAssigningUserId] = useState<string | null>(null);
    const [assignMode, setAssignMode] = useState<'assign' | 'transfer'>('assign');

    const [quickCreateTarget, setQuickCreateTarget] = useState<{ id: number; nom: string } | null>(null);
    const [quickCreateSource, setQuickCreateSource] = useState<TypeDocumentDto[]>([]);

    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const [filters, setFilters] = useState<UserFilters>({
        nom: '', prenom: '', email: '', telephone: '', roles: []
    });
    const [currentPage, setCurrentPage] = useState(1);
    const ITEMS_PER_PAGE = 10;

    useEffect(() => {
        getMyUO()
            .then(async (uo: UONode) => {
                setRootUO(uo);
                setCurrentUOId(uo.id);
                const sousArbre = await getSousArbre(uo.id);
                setTreeNodes(sousArbre);
            })
            .catch((err: unknown) => {
                setError(getErrorMessage(err, "Impossible de récupérer votre unité organisationnelle"));
            });
    }, []);

    const fetchSousArbre = useCallback(async () => {
        if (!rootUO) return;
        try {
            const sousArbre = await getSousArbre(rootUO.id);
            setTreeNodes(sousArbre);
        } catch {
            setError("Erreur lors de la récupération de l'arborescence");
        }
    }, [rootUO]);

    const fetchUsers = useCallback(async (uoId: number) => {
        try {
            const data = await getUsersByUO(uoId);
            setUsers(data);
        } catch {
            setError("Erreur lors de la récupération des utilisateurs");
        }
    }, []);

    useEffect(() => {
        if (currentUOId && tab === 'utilisateurs') {
            fetchUsers(currentUOId);
        }
    }, [currentUOId, tab, fetchUsers]);

    useEffect(() => {
        if (error || success) {
            const timer = setTimeout(() => {
                setError('');
                setSuccess('');
            }, 2500);
            return () => clearTimeout(timer);
        }
    }, [error, success]);

    const openCreateUOModal = (parentId: number) => {
        setCreateUOParentId(parentId);
        setCreateUONom('');
        setIsCreateUOModalOpen(true);
    };

    const handleCreateUO = async (e: FormEvent) => {
        e.preventDefault();
        if (!createUONom.trim()) {
            setError('Le nom est obligatoire');
            return;
        }
        setActionInProgress(true);
        try {
            await createUO({ nom: createUONom.trim(), parentId: createUOParentId });
            setSuccess("UO créée avec succès");
            setIsCreateUOModalOpen(false);
            await fetchSousArbre();
        } catch (err: unknown) {
            setError(getErrorMessage(err, "Erreur lors de la création de l'UO"));
        } finally {
            setActionInProgress(false);
        }
    };

    // Toujours un reparentage classique — jamais de racine ici (l'ADMIN_UO ne
    // peut pas sortir de son propre sous-arbre, vérifié côté serveur).
    const handleMoveUO = async (id: number, targetId: number) => {
        try {
            await updateUO(id, { parentId: targetId });
            setSuccess("UO déplacée avec succès");
            await fetchSousArbre();
        } catch (err: unknown) {
            setError(getErrorMessage(err, "Erreur lors du déplacement de l'UO"));
        }
    };

    const handleDropTypeDocuments = (targetUoId: number, payload: TypeDocumentDto[]) => {
        const targetNode = treeNodes.find(n => n.id === targetUoId);
        if (!targetNode || payload.length === 0) return;
        setQuickCreateTarget({ id: targetNode.id, nom: targetNode.nom });
        setQuickCreateSource(payload);
    };

    const handleQuickCreated = () => {
        setQuickCreateTarget(null);
        setQuickCreateSource([]);
        setTdRefresh(r => r + 1);
        setSuccess("Type(s) de document créé(s) avec succès");
    };

    const handleSelectUO = (id: number) => {
        setCurrentUOId(id);
        setCurrentPage(1);
        setMainView('contenu');
    };

    const handleAssignToUO = (userId: string) => {
        setAssignMode('assign');
        setAssigningUserId(userId);
    };

    const handleTransfer = (userId: string) => {
        setAssignMode('transfer');
        setAssigningUserId(userId);
    };

    const handleAssigned = () => {
        setAssigningUserId(null);
        if (currentUOId) fetchUsers(currentUOId);
    };

    const handleAction = async (userId: string, action: 'edit' | 'block-unblock' | 'delete' | 'view') => {
        setError('');
        setSuccess('');
        const targetUser = users.find(u => u.id === userId);
        if (!targetUser) return;

        if (action === 'view') { setSelectedUser(null); setViewingUser(targetUser); setIsViewModalOpen(true); return; }
        if (action === 'edit') { setViewingUser(null); setSelectedUser(targetUser); setIsUpdateModalOpen(true); return; }

        if (action === 'block-unblock') {
            setActionInProgress(true);
            try {
                const active = isUserActive(targetUser);
                await updateStatus(userId, { actif: !active });
                setSuccess("Statut mis à jour avec succès");
                if (currentUOId) fetchUsers(currentUOId);
            } catch {
                setError("Erreur lors du changement de statut");
            } finally {
                setActionInProgress(false);
            }
        }
    };

    const handleRemoveFromUO = async (userId: string, uoId: number) => {
        if (!window.confirm("Retirer cet utilisateur de cette UO ?")) return;

        setActionInProgress(true);
        try {
            await retirerMembreUO(uoId, userId);
            setSuccess("Utilisateur retiré de l'UO avec succès");
            if (currentUOId) fetchUsers(currentUOId);
        } catch (err: unknown) {
            setError(getErrorMessage(err, "Erreur lors du retrait de l'utilisateur"));
        } finally {
            setActionInProgress(false);
        }
    };

    const handleRemoveAdminUO = async (userId: string, uoId: number) => {
        if (!window.confirm("Retirer cet administrateur d'UO ? Il perdra son autorité de gestion sur cette UO.")) return;

        setActionInProgress(true);
        try {
            await retirerMembreEtAdmin(uoId, userId);
            setSuccess("Administrateur d'UO retiré avec succès");
            if (currentUOId) fetchUsers(currentUOId);
        } catch (err: unknown) {
            setError(getErrorMessage(err, "Erreur lors du retrait de l'administrateur d'UO"));
        } finally {
            setActionInProgress(false);
        }
    };

    const handleCloseModal = () => {
        setIsCreateUserModalOpen(false);
        setIsUpdateModalOpen(false);
        setIsViewModalOpen(false);
        setIsCreateTdModalOpen(false);
        setIsCreateUOModalOpen(false);
        setSelectedUser(null);
        setViewingUser(null);
    };

    const handleUserUpdated = () => {
        if (currentUOId) fetchUsers(currentUOId);
        handleCloseModal();
        setSuccess("Opération effectuée avec succès");
    };

    const handleTdCreated = () => {
        handleCloseModal();
        setTdRefresh(r => r + 1);
        setSuccess("Type de document créé avec succès");
    };

    const filteredUsers = users.filter(u =>
        (u.nom?.toLowerCase() || '').includes(filters.nom.toLowerCase()) &&
        (u.prenom?.toLowerCase() || '').includes(filters.prenom.toLowerCase()) &&
        (u.email?.toLowerCase() || '').includes(filters.email.toLowerCase()) &&
        (u.telephone?.toLowerCase() || '').includes(filters.telephone.toLowerCase()) &&
        (filters.roles.length === 0 || u.roles?.some(r => filters.roles.includes(r.name)))
    );

    const totalPages = Math.max(1, Math.ceil(filteredUsers.length / ITEMS_PER_PAGE));
    const paginatedUsers = filteredUsers.slice(
        (currentPage - 1) * ITEMS_PER_PAGE,
        currentPage * ITEMS_PER_PAGE
    );

    const sidebarTitle = rootUO ? `Admin ${rootUO.nom}` : (userInfo?.role || "ADMIN_UO");
    const restrictToUO = currentUO ? { id: currentUO.id, nom: currentUO.nom } : undefined;

    return (
        <div className="admin-dashboard">
            <div className="admin-body">
                <Sidebar title={sidebarTitle}>
                    <nav className="sidebar-nav">
                        <div>
                            <div className="main-header">
                                <button
                                    onClick={() => setMainView('profile')}
                                    className={`sidebar-btn ${mainView === 'profile' ? 'active-tab' : ''}`}
                                >
                                    👤 Mon Profil
                                </button>
                            </div>

                            <div className="sidebar-section-label">Organisation</div>
                            {rootUO && currentUOId && (
                                <UOTree
                                    nodes={treeNodes}
                                    rootId={rootUO.id}
                                    currentId={currentUOId}
                                    onSelect={handleSelectUO}
                                    canManage
                                    onAddChild={openCreateUOModal}
                                    onMove={handleMoveUO}
                                    onDropTypeDocuments={handleDropTypeDocuments}
                                />
                            )}
                        </div>
                    </nav>
                </Sidebar>

                <div className="main-content">
                    {mainView === 'profile' && (
                        <Profile userId={userInfo?.id} />
                    )}

                    {error && <div className="alert alert-error">{error}</div>}
                    {success && <div className="alert alert-success">{success}</div>}

                    {mainView === 'contenu' && currentUO && (
                        <>
                            <p className="uo-page-path">{currentUO.cheminComplet}</p>
                            <h2 className="uo-page-title">{currentUO.nom}</h2>

                            <div className="uo-tabs">
                                <button
                                    className={`uo-tab ${tab === 'utilisateurs' ? 'active' : ''}`}
                                    onClick={() => setTab('utilisateurs')}
                                >
                                    Utilisateurs
                                </button>
                                <button
                                    className={`uo-tab ${tab === 'documents' ? 'active' : ''}`}
                                    onClick={() => setTab('documents')}
                                >
                                    Types de Documents
                                </button>
                                <button
                                    className={`uo-tab ${tab === 'archives' ? 'active' : ''}`}
                                    onClick={() => setTab('archives')}
                                >
                                    Documents archivés
                                </button>
                                <button
                                    className={`uo-tab ${tab === 'projets' ? 'active' : ''}`}
                                    onClick={() => setTab('projets')}
                                >
                                    Projets
                                </button>
                                <button
                                    className={`uo-tab ${tab === 'emplacements' ? 'active' : ''}`}
                                    onClick={() => setTab('emplacements')}
                                >
                                    Emplacements physiques
                                </button>
                                <button
                                    className={`uo-tab ${tab === 'journal' ? 'active' : ''}`}
                                    onClick={() => setTab('journal')}
                                >
                                    Journal d'audit
                                </button>
                            </div>

                            {tab === 'utilisateurs' && (
                                <>
                                    <div className="main-header">
                                        <button
                                            className="sidebar-btn"
                                            onClick={() => setIsCreateUserModalOpen(true)}
                                        >
                                            Créer un utilisateur
                                        </button>
                                    </div>
                                    <FilterUsers
                                        filters={filters}
                                        onChange={(f) => { setFilters(f); setCurrentPage(1); }}
                                    />
                                    <p className="users-count">
                                        <span>{filteredUsers.length}</span> utilisateur{filteredUsers.length > 1 ? 's' : ''} dans <span>{currentUO.nom}</span>
                                        {filteredUsers.length !== users.length && <> sur <span>{users.length}</span></>}
                                    </p>
                                    <UserTable
                                        user={paginatedUsers}
                                        onAction={(id, action) => handleAction(id, action as 'edit' | 'block-unblock' | 'delete' | 'view')}
                                        actionInProgress={actionInProgress}
                                        onRemoveFromUO={handleRemoveFromUO}
                                        onRemoveAdminUO={handleRemoveAdminUO}
                                        onAssignToUO={handleAssignToUO}
                                        onTransfer={handleTransfer}
                                    />
                                    <Pagination
                                        currentPage={currentPage}
                                        totalPages={totalPages}
                                        onChange={setCurrentPage}
                                    />
                                </>
                            )}

                            {tab === 'documents' && (
                                <>
                                    <div className="main-header">
                                        <button
                                            className="sidebar-btn"
                                            onClick={() => setIsCreateTdModalOpen(true)}
                                        >
                                            Créer un type
                                        </button>
                                    </div>
                                    <TypeDocumentList refreshTrigger={tdRefresh} uoId={currentUOId} />
                                </>
                            )}

                            {tab === 'archives' && (
                                <DocumentsArchivesPanel uoId={currentUOId} />
                            )}

                            {tab === 'projets' && (
                                <ProjetsPanel uoId={currentUOId} canCreate={false} />
                            )}

                            {tab === 'emplacements' && (
                                <PhysicalLocationsPanel uoId={currentUOId} />
                            )}

                            {tab === 'journal' && <AuditLogPanel />}
                        </>
                    )}
                </div>

                <Modal isOpen={isCreateUserModalOpen} onClose={handleCloseModal} title="Créer un utilisateur">
                    <CreateUser onsuccess={handleUserUpdated} restrictToUO={restrictToUO} />
                </Modal>

                <Modal isOpen={isUpdateModalOpen} onClose={handleCloseModal} title="Mettre à jour un utilisateur">
                    {selectedUser && <UpdateUser initialData={selectedUser} onsuccess={handleUserUpdated} restrictToUO={restrictToUO} />}
                </Modal>

                <Modal isOpen={isViewModalOpen} onClose={handleCloseModal} title="Détails de l'utilisateur">
                    {viewingUser && (
                        <div className="user-details-card">
                            <div className="details-row"><strong>Identifiant :</strong> {viewingUser.id}</div>
                            <div className="details-row"><strong>Nom complet :</strong> {viewingUser.nom} {viewingUser.prenom}</div>
                            <div className="details-row"><strong>Email :</strong> {viewingUser.email}</div>
                            <div className="details-row"><strong>Téléphone :</strong> {viewingUser.telephone}</div>
                            <div className="details-row">
                                <strong>Statut :</strong>
                                <span className={`status-tag ${isUserActive(viewingUser) ? 'active' : 'inactive'}`}>
                                    {isUserActive(viewingUser) ? 'Actif' : 'Bloqué'}
                                </span>
                            </div>
                            <div className="details-row">
                                <strong>Rôles :</strong> {viewingUser.roles?.map(r => r.name).join(', ') || 'Aucun'}
                            </div>
                            <div className="details-row">
                                <strong>Unité organisationnelle :</strong> {viewingUser.uoNom || 'Aucune'}
                            </div>
                            <button onClick={handleCloseModal} className="details-close-btn">Fermer</button>
                        </div>
                    )}
                </Modal>

                <Modal isOpen={isCreateTdModalOpen} onClose={handleCloseModal} title="Créer un type de document">
                    {restrictToUO && <CreateTypeDocument onsuccess={handleTdCreated} restrictToUO={restrictToUO} />}
                </Modal>

                <Modal isOpen={isCreateUOModalOpen} onClose={handleCloseModal} title="Créer une UO enfant">
                    <form onSubmit={handleCreateUO}>
                        <div className="form-field">
                            <input
                                id="uo-nom"
                                type="text"
                                placeholder="Nom de l'UO" aria-label="Nom de l'UO"
                                className="form-field-input"
                                value={createUONom}
                                onChange={(e) => setCreateUONom(e.target.value)}
                                required
                            />
                        </div>
                        <button type="submit" className="form-submit-btn" disabled={actionInProgress}>
                            Créer
                        </button>
                    </form>
                </Modal>

                <AssignUOModal
                    isOpen={assigningUserId !== null}
                    userId={assigningUserId}
                    mode={assignMode}
                    onClose={() => setAssigningUserId(null)}
                    onAssigned={handleAssigned}
                />

                <QuickCreateTypeDocumentsModal
                    isOpen={quickCreateTarget !== null}
                    targetUO={quickCreateTarget}
                    sourceTypeDocuments={quickCreateSource}
                    onClose={() => setQuickCreateTarget(null)}
                    onCreated={handleQuickCreated}
                />

            </div>
        </div>
    );
}

export default AdminUoDashboard;