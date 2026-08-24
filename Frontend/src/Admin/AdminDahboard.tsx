// AdminDashboard.tsx
import { useState, useEffect, useCallback, useMemo, type FormEvent } from 'react';
import Sidebar from "../Page/Sidebar";
import UOTree from "../organisation/UOTree";
import CreateUser from "./CreateUser";
import UserTable from "./UserTable";
import UpdateUser from "./UpdateUser";
import Modal from "../Page/Modal";
import Profile from "../Page/Profil";
import AssignUOModal from './AssignUOModal';
import TypeDocumentList from "../document/TypedocumentList";
import CreateTypeDocument from "../document/Createtypedocument";
import QuickCreateTypeDocumentsModal from '../document/QuickCreateTypeDocumentsModal';
import ProjetsPanel from '../organisation/ProjetsPanel';
import PhysicalLocationsPanel from '../organisation/PhysicalLocationsPanel';
import AuditLogPanel from './AuditLogPanel';
import DocumentsArchivesPanel from './DocumentsArchivesPanel';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getAllUsers, getUsersByUO, updateUserStatus as updateStatus } from "../services/admin/AdminService";
import {
    getAllUOs,
    createUO,
    updateUO,
    deplacerVersRacine,
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

const GLOBAL_VIEW_ID = -1;
const GLOBAL_VIEW_NODE: UONode = {
    id: GLOBAL_VIEW_ID,
    nom: 'Toutes les UO',
    parentId: null,
    cheminComplet: ''
};

function AdminDashboard() {
    const userInfo = getCurrentUserInfo();

    const [mainView, setMainView] = useState<MainView>('contenu');
    const [tab, setTab] = useState<Tab>('utilisateurs');

    const [allUOs, setAllUOs] = useState<UONode[]>([]);
    const [currentUOId, setCurrentUOId] = useState<number>(GLOBAL_VIEW_ID);

    const treeNodes = useMemo<UONode[]>(() => {
        // Les UO racines réelles (parentId null/undefined) sont rattachées à la racine virtuelle
        const reparented = allUOs.map(u =>
            (u.parentId === null || u.parentId === undefined)
                ? { ...u, parentId: GLOBAL_VIEW_ID }
                : u
        );
        return [GLOBAL_VIEW_NODE, ...reparented];
    }, [allUOs]);

    const currentUO = currentUOId === GLOBAL_VIEW_ID
        ? GLOBAL_VIEW_NODE
        : (allUOs.find(n => n.id === currentUOId) ?? null);

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

    // Affectation / transfert d'un utilisateur vers une UO — même modale, deux modes
    const [assigningUserId, setAssigningUserId] = useState<string | null>(null);
    const [assignMode, setAssignMode] = useState<'assign' | 'transfer'>('assign');

    // Création rapide de types de documents par glisser-déposer sur une UO
    const [quickCreateTarget, setQuickCreateTarget] = useState<{ id: number; nom: string } | null>(null);
    const [quickCreateSource, setQuickCreateSource] = useState<TypeDocumentDto[]>([]);

    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const [filters, setFilters] = useState<UserFilters>({
        nom: '', prenom: '', email: '', telephone: '', roles: []
    });
    const [currentPage, setCurrentPage] = useState(1);
    const ITEMS_PER_PAGE = 10;

    const fetchAllUOs = useCallback(async () => {
        try {
            const data = await getAllUOs();
            setAllUOs(data);
        } catch {
            setError("Erreur lors de la récupération des unités organisationnelles");
        }
    }, []);

    useEffect(() => { fetchAllUOs(); }, [fetchAllUOs]);

    // Le nœud racine virtuel (GLOBAL_VIEW_ID) ne correspond à aucune UO réelle :
    // y ajouter un enfant crée une UO racine (parentId: null).
    const openCreateUOModal = (parentId: number) => {
        setCreateUOParentId(parentId === GLOBAL_VIEW_ID ? null : parentId);
        setCreateUONom('');
        setIsCreateUOModalOpen(true);
    };

    const handleCreateUO = async (e: FormEvent) => {
        e.preventDefault();
        if (!createUONom.trim()) { setError('Le nom est obligatoire'); return; }
        setActionInProgress(true);
        try {
            await createUO({ nom: createUONom.trim(), parentId: createUOParentId });
            setSuccess("UO créée avec succès");
            setIsCreateUOModalOpen(false);
            await fetchAllUOs();
        } catch (err: any) {
            setError(err.message || "Erreur lors de la création de l'UO");
        } finally {
            setActionInProgress(false);
        }
    };

    // Glisser-déposer d'une UO : cible = racine virtuelle -> déplacement vers la racine
    // (réservé ADMIN côté serveur) ; sinon reparentage classique.
    const handleMoveUO = async (id: number, targetId: number) => {
        try {
            if (targetId === GLOBAL_VIEW_ID) {
                await deplacerVersRacine(id);
            } else {
                await updateUO(id, { parentId: targetId });
            }
            setSuccess("UO déplacée avec succès");
            await fetchAllUOs();
        } catch (err: any) {
            setError(err.message || "Erreur lors du déplacement de l'UO");
        }
    };

    // Glisser-déposer d'un ou plusieurs types de documents sur une UO : ouvre la
    // modale de création rapide, pré-remplie depuis les types glissés — rien n'est
    // créé tant que l'utilisateur ne confirme pas.
    const handleDropTypeDocuments = (targetUoId: number, payload: TypeDocumentDto[]) => {
        if (targetUoId === GLOBAL_VIEW_ID) return; // pas une UO réelle
        const targetNode = allUOs.find(u => u.id === targetUoId);
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
        fetchUsers(currentUOId);
    };

    const fetchUsers = useCallback(async (uoId: number) => {
        try {
            const data = uoId === GLOBAL_VIEW_ID
                ? await getAllUsers()
                : await getUsersByUO(uoId);
            setUsers(data);
        } catch {
            setError("Erreur lors de la récupération des utilisateurs");
        }
    }, []);

    useEffect(() => {
        if (tab === 'utilisateurs') {
            fetchUsers(currentUOId);
        }
    }, [currentUOId, tab, fetchUsers]);

    useEffect(() => {
        if (error || success) {
            const t = setTimeout(() => { setError(''); setSuccess(''); }, 2500);
            return () => clearTimeout(t);
        }
    }, [error, success]);

    const handleSelectUO = (id: number) => {
        setCurrentUOId(id);
        setCurrentPage(1);
        setMainView('contenu');
    };

    const handleAction = async (userId: string, action: 'edit' | 'block-unblock' | 'delete' | 'view') => {
        setError(''); setSuccess('');
        const targetUser = users.find(u => u.id === userId);
        if (!targetUser) return;

        if (action === 'view') { setViewingUser(targetUser); setIsViewModalOpen(true); return; }
        if (action === 'edit') { setSelectedUser(targetUser); setIsUpdateModalOpen(true); return; }

        if (action === 'block-unblock') {
            setActionInProgress(true);
            try {
                const isActif = targetUser.actif === true || targetUser.actif === 'true';
                await updateStatus(userId, { actif: !isActif });
                setSuccess("Statut mis à jour avec succès");
                fetchUsers(currentUOId);
            } catch {
                setError("Erreur lors du changement de statut");
            } finally {
                setActionInProgress(false);
            }
        }
    };

    // uoId transmis directement par la ligne (UserTable) — plus de dépendance à
    // l'UO actuellement affichée, donc "Retirer" fonctionne aussi en vue globale.
    const handleRemoveFromUO = async (userId: string, uoId: number) => {
        if (!window.confirm("Retirer cet utilisateur de cette UO ?")) return;
        setActionInProgress(true);
        try {
            await retirerMembreUO(uoId, userId);
            setSuccess("Utilisateur retiré de l'UO avec succès");
            fetchUsers(currentUOId);
        } catch (err: any) {
            setError(err.message || "Erreur lors du retrait de l'utilisateur");
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
            fetchUsers(currentUOId);
        } catch (err: any) {
            setError(err.message || "Erreur lors du retrait de l'administrateur d'UO");
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
        fetchUsers(currentUOId);
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

    // Pas de restriction d'UO pour la création/modif tant qu'on est en vue globale ;
    // dès qu'un nœud réel est sélectionné, on pré-remplit comme pour ADMIN_UO.
    const restrictToUO = currentUO && currentUO.id !== GLOBAL_VIEW_ID
        ? { id: currentUO.id, nom: currentUO.nom }
        : undefined;

    return (
        <div className="admin-dashboard">
            <div className="admin-body">

                <Sidebar title={userInfo?.role || "ADMINISTRATEUR"}>
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
                            <UOTree
                                nodes={treeNodes}
                                rootId={GLOBAL_VIEW_ID}
                                currentId={currentUOId}
                                onSelect={handleSelectUO}
                                canManage
                                onAddChild={openCreateUOModal}
                                onMove={handleMoveUO}
                                onDropTypeDocuments={handleDropTypeDocuments}
                            />
                        </div>
                    </nav>
                </Sidebar>

                <div className="main-content">

                    {mainView === 'profile' && (
                        <Profile userId={userInfo?.id} />
                    )}

                    {mainView === 'contenu' && currentUO && (
                        <>
                            <p className="uo-page-path">
                                {currentUO.id === GLOBAL_VIEW_ID ? 'Toute l\'organisation' : currentUO.cheminComplet}
                            </p>
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

                            {error && <div className="alert alert-error">{error}</div>}
                            {success && <div className="alert alert-success">{success}</div>}

                            {tab === 'utilisateurs' && (
                                <>
                                    <div className="main-header">
                                        <button
                                            className="sidebar-btn"
                                            onClick={() => setIsCreateUserModalOpen(true)}
                                        >
                                            <i className="fa-solid fa-user-plus"></i>  Créer un utilisateur
                                        </button>
                                    </div>
                                    <FilterUsers
                                        filters={filters}
                                        onChange={(f) => { setFilters(f); setCurrentPage(1); }}
                                    />
                                    <p className="users-count">
                                        <span>{filteredUsers.length}</span> utilisateur{filteredUsers.length > 1 ? 's' : ''}
                                        {currentUO.id !== GLOBAL_VIEW_ID && <> dans <span>{currentUO.nom}</span></>}
                                        {filteredUsers.length !== users.length && <> sur <span>{users.length}</span></>}
                                    </p>
                                    <UserTable
                                        user={paginatedUsers}
                                        onAction={(id, action) => handleAction(id, action as any)}
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
                                    {currentUO.id !== GLOBAL_VIEW_ID && (
                                        <div className="main-header">
                                            <button
                                                className="sidebar-btn"
                                                onClick={() => setIsCreateTdModalOpen(true)}
                                            >
                                                <i className="fa-solid fa-file-circle-plus"></i>    Créer un type
                                            </button>
                                        </div>
                                    )}
                                    <TypeDocumentList
                                        refreshTrigger={tdRefresh}
                                        uoId={currentUO.id === GLOBAL_VIEW_ID ? null : currentUO.id}
                                    />
                                </>
                            )}

                            {tab === 'archives' && (
                                <DocumentsArchivesPanel uoId={currentUO.id === GLOBAL_VIEW_ID ? null : currentUO.id} />
                            )}

                            {tab === 'projets' && (
                                <ProjetsPanel uoId={currentUO.id === GLOBAL_VIEW_ID ? null : currentUO.id} canCreate={false} />
                            )}

                            {tab === 'emplacements' && (
                                <PhysicalLocationsPanel uoId={currentUO.id === GLOBAL_VIEW_ID ? null : currentUO.id} />
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
                                <span className={`status-tag ${viewingUser.actif === true || viewingUser.actif === 'true' ? 'active' : 'inactive'}`}>
                                    {viewingUser.actif === true || viewingUser.actif === 'true' ? 'Actif' : 'Bloqué'}
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

                <Modal
                    isOpen={isCreateUOModalOpen}
                    onClose={handleCloseModal}
                    title={createUOParentId === null ? "Créer une UO racine" : "Créer une UO enfant"}
                >
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

export default AdminDashboard;