import { useState, useEffect, useMemo } from 'react';
import {
    getAllUOs,
    createUO,
    updateUO,
    deleteUO,
    getMembresUO,
    ajouterMembreUO,
    retirerMembreUO
} from '../services/organisation/UOService';
import { getAllUsers } from '../services/admin/AdminService';
import '../Style/Admin/UniteOrganisationnelle.css';

interface UO {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

interface MembreUO {
    userId: string;
    nom: string;
    prenom: string;
    email: string;
    dateAjout: string;
}

interface UserOption {
    id: string;
    nom: string;
    prenom: string;
    email: string;
}

function UniteOrganisationnelle() {
    const [uos, setUos] = useState<UO[]>([]);
    const [expanded, setExpanded] = useState<Set<number>>(new Set());
    const [selectedUO, setSelectedUO] = useState<UO | null>(null);
    const [membres, setMembres] = useState<MembreUO[]>([]);
    const [allUsers, setAllUsers] = useState<UserOption[]>([]);
    const [userToAdd, setUserToAdd] = useState<string>('');

    const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
    const [isEditModalOpen, setIsEditModalOpen] = useState(false);
    const [parentForCreate, setParentForCreate] = useState<number | null>(null);
    const [formNom, setFormNom] = useState('');

    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');
    const [actionInProgress, setActionInProgress] = useState(false);

    useEffect(() => { fetchUOs(); fetchUsers(); }, []);

    useEffect(() => {
        if (error || success) {
            const t = setTimeout(() => { setError(''); setSuccess(''); }, 2500);
            return () => clearTimeout(t);
        }
    }, [error, success]);

    const fetchUOs = async () => {
        try {
            const data = await getAllUOs();
            setUos(data);
        } catch {
            setError("Erreur lors de la récupération des unités organisationnelles");
        }
    };

    const fetchUsers = async () => {
        try {
            const data = await getAllUsers();
            setAllUsers(data);
        } catch {
            setError("Erreur lors de la récupération des utilisateurs");
        }
    };

    const fetchMembres = async (uoId: number) => {
        try {
            const data = await getMembresUO(uoId);
            setMembres(data);
        } catch {
            setError("Erreur lors de la récupération des membres");
        }
    };

    const tree = useMemo(() => {
        const byParent = new Map<string | number, UO[]>();
        uos.forEach(uo => {
            const key = uo.parentId ?? 'root';
            if (!byParent.has(key)) byParent.set(key, []);
            byParent.get(key)!.push(uo);
        });
        return byParent;
    }, [uos]);

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const selectUO = (uo: UO) => {
        setSelectedUO(uo);
        setUserToAdd('');
        fetchMembres(uo.id);
    };

    const openCreateModal = (parentId: number | null) => {
        setParentForCreate(parentId);
        setFormNom('');
        setIsCreateModalOpen(true);
    };

    const openEditModal = (uo: UO) => {
        setSelectedUO(uo);
        setFormNom(uo.nom);
        setIsEditModalOpen(true);
    };

    const closeModals = () => {
        setIsCreateModalOpen(false);
        setIsEditModalOpen(false);
    };

    const handleCreate = async () => {
        if (!formNom.trim()) { setError('Le nom est obligatoire'); return; }
        setActionInProgress(true);
        try {
            await createUO({ nom: formNom.trim(), parentId: parentForCreate });
            setSuccess("UO créée avec succès");
            closeModals();
            await fetchUOs();
        } catch (err: any) {
            setError(err.message || "Erreur lors de la création de l'UO");
        } finally {
            setActionInProgress(false);
        }
    };

    const handleUpdate = async () => {
        if (!selectedUO) return;
        if (!formNom.trim()) { setError('Le nom est obligatoire'); return; }
        setActionInProgress(true);
        try {
            await updateUO(selectedUO.id, { nom: formNom.trim() });
            setSuccess("UO mise à jour avec succès");
            closeModals();
            await fetchUOs();
        } catch (err: any) {
            setError(err.message || "Erreur lors de la mise à jour de l'UO");
        } finally {
            setActionInProgress(false);
        }
    };

    const handleDelete = async (uo: UO) => {
        if (!window.confirm(`Supprimer l'UO "${uo.nom}" ?`)) return;
        setActionInProgress(true);
        try {
            await deleteUO(uo.id);
            setSuccess("UO supprimée avec succès");
            if (selectedUO?.id === uo.id) setSelectedUO(null);
            await fetchUOs();
        } catch (err: any) {
            setError(err.message || "Erreur lors de la suppression de l'UO");
        } finally {
            setActionInProgress(false);
        }
    };

    const handleAjouterMembre = async () => {
        if (!selectedUO || !userToAdd) return;
        setActionInProgress(true);
        try {
            await ajouterMembreUO(selectedUO.id, userToAdd);
            setSuccess("Membre ajouté avec succès");
            setUserToAdd('');
            await fetchMembres(selectedUO.id);
        } catch (err: any) {
            setError(err.message || "Erreur lors de l'ajout du membre");
        } finally {
            setActionInProgress(false);
        }
    };

    const handleRetirerMembre = async (userId: string) => {
        if (!selectedUO) return;
        setActionInProgress(true);
        try {
            await retirerMembreUO(selectedUO.id, userId);
            setSuccess("Membre retiré avec succès");
            await fetchMembres(selectedUO.id);
        } catch (err: any) {
            setError(err.message || "Erreur lors du retrait du membre");
        } finally {
            setActionInProgress(false);
        }
    };

    const usersDisponibles = allUsers.filter(
        u => !membres.some(m => m.userId === u.id)
    );

    const renderNode = (uo: UO, depth: number) => {
        const enfants = tree.get(uo.id) || [];
        const isExpanded = expanded.has(uo.id);
        const isSelected = selectedUO?.id === uo.id;

        return (
            <div key={uo.id}>
                <div
                    className={`uo-node ${isSelected ? 'is-selected' : ''}`}
                    style={{ paddingLeft: `${depth * 20}px` }}
                >
                    {enfants.length > 0 ? (
                        <button className="uo-toggle" onClick={() => toggleExpand(uo.id)}>
                            {isExpanded ? '▾' : '▸'}
                        </button>
                    ) : (
                        <span className="uo-toggle-placeholder" />
                    )}

                    <span className="uo-nom" onClick={() => selectUO(uo)}>
                        {uo.nom}
                    </span>

                    <div className="uo-node-actions">
                        <button onClick={() => openCreateModal(uo.id)} disabled={actionInProgress}>
                            + Sous-UO
                        </button>
                        <button onClick={() => openEditModal(uo)} disabled={actionInProgress}>
                            Modifier
                        </button>
                        <button onClick={() => handleDelete(uo)} disabled={actionInProgress}>
                            Supprimer
                        </button>
                    </div>
                </div>

                {isExpanded && enfants.map(enfant => renderNode(enfant, depth + 1))}
            </div>
        );
    };

    const racines = tree.get('root') || [];

    return (
        <div className="uo-page">
            {error && <div className="alert alert-error">{error}</div>}
            {success && <div className="alert alert-success">{success}</div>}

            <div className="uo-layout">
                <div className="uo-tree-panel">
                    <div className="uo-tree-header">
                        <h3>Unités organisationnelles</h3>
                        <button onClick={() => openCreateModal(null)} disabled={actionInProgress}>
                            + UO racine
                        </button>
                    </div>
                    <div className="uo-tree">
                        {racines.map(uo => renderNode(uo, 0))}
                    </div>
                </div>

                <div className="uo-detail-panel">
                    {!selectedUO ? (
                        <p className="uo-detail-empty">Sélectionnez une UO pour voir ses membres</p>
                    ) : (
                        <>
                            <h3>{selectedUO.cheminComplet}</h3>

                            <div className="uo-membres-add">
                                <select
                                    value={userToAdd}
                                    onChange={(e) => setUserToAdd(e.target.value)}
                                >
                                    <option value="">-- Choisir un utilisateur --</option>
                                    {usersDisponibles.map(u => (
                                        <option key={u.id} value={u.id}>
                                            {u.nom} {u.prenom} ({u.email})
                                        </option>
                                    ))}
                                </select>
                                <button
                                    onClick={handleAjouterMembre}
                                    disabled={!userToAdd || actionInProgress}
                                >
                                    Ajouter
                                </button>
                            </div>

                            <table className="uo-membres-table">
                                <thead>
                                    <tr>
                                        <th>Membre</th>
                                        <th>Email</th>
                                        <th>Ajouté le</th>
                                        <th>Action</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {membres.map(m => (
                                        <tr key={m.userId}>
                                            <td>{m.nom} {m.prenom}</td>
                                            <td>{m.email}</td>
                                            <td>{new Date(m.dateAjout).toLocaleDateString()}</td>
                                            <td>
                                                <button
                                                    onClick={() => handleRetirerMembre(m.userId)}
                                                    disabled={actionInProgress}
                                                >
                                                    Retirer
                                                </button>
                                            </td>
                                        </tr>
                                    ))}
                                    {membres.length === 0 && (
                                        <tr><td colSpan={4}>Aucun membre</td></tr>
                                    )}
                                </tbody>
                            </table>
                        </>
                    )}
                </div>
            </div>

            {(isCreateModalOpen || isEditModalOpen) && (
                <div className="uo-modal-overlay" onClick={closeModals}>
                    <div className="uo-modal" onClick={(e) => e.stopPropagation()}>
                        <h3>{isCreateModalOpen ? "Créer une UO" : "Modifier l'UO"}</h3>
                        <input
                            type="text"
                            value={formNom}
                            onChange={(e) => setFormNom(e.target.value)}
                            placeholder="Nom de l'UO"
                        />
                        <div className="uo-modal-actions">
                            <button onClick={closeModals}>Annuler</button>
                            <button
                                onClick={isCreateModalOpen ? handleCreate : handleUpdate}
                                disabled={actionInProgress}
                            >
                                {isCreateModalOpen ? "Créer" : "Enregistrer"}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

export default UniteOrganisationnelle;