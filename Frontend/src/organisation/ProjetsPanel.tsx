import { useEffect, useState } from 'react';
import {
    creerProjet,
    getProjetsDeUO,
    getProjetDetail,
    ajouterTypesAttendus,
    retirerTypeAttendu,
    supprimerProjet,
} from '../services/organisation/ProjetService';
import type { ProjetDto, ProjetDetailDto } from '../services/organisation/ProjetService';
import { getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getAllUsers } from '../services/document/DocumentService';
import type { UserDto } from '../services/document/DocumentService';
import Modal from '../Page/Modal';
import GestionGroupeProjet from './GestionGroupeProjet';
import '../Style/Admin/ProjetsPanel.css';

interface ProjetsPanelProps {
    uoId: number | null;
    /** Affiche le bouton de création — réservé à ROLE_EDITOR (vérifié aussi côté serveur). */
    canCreate?: boolean;
}

function ProjetsPanel({ uoId, canCreate = true }: ProjetsPanelProps) {
    const [projets, setProjets]           = useState<ProjetDto[]>([]);
    const [loading, setLoading]           = useState(false);
    const [error, setError]               = useState('');

    const [showCreateForm, setShowCreateForm] = useState(false);
    const [nom, setNom]                       = useState('');
    const [description, setDescription]       = useState('');
    const [typesUO, setTypesUO]               = useState<TypeDocumentDto[]>([]);
    const [selectedTypeIds, setSelectedTypeIds] = useState<number[]>([]);
    const [accessCreation, setAccessCreation] = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [usersUO, setUsersUO]               = useState<UserDto[]>([]);
    const [selectedMembreIds, setSelectedMembreIds] = useState<string[]>([]);
    const [creating, setCreating]             = useState(false);

    const [detailOuvert, setDetailOuvert] = useState<ProjetDetailDto | null>(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [isGroupeOpen, setIsGroupeOpen] = useState(false);

    const chargerProjets = () => {
        if (!uoId) return;
        setLoading(true);
        setError('');
        getProjetsDeUO(uoId)
            .then(setProjets)
            .catch(err => setError(err.message))
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

    const ouvrirFormulaireCreation = () => {
        setShowCreateForm(true);
    };

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

    const handleCreer = async () => {
        if (!uoId || !nom.trim()) return;
        setCreating(true);
        setError('');
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
            setNom('');
            setDescription('');
            setSelectedTypeIds([]);
            setAccessCreation('PUBLIC');
            setSelectedMembreIds([]);
            setShowCreateForm(false);
            chargerProjets();
        } catch (err: any) {
            setError(err.message);
        } finally {
            setCreating(false);
        }
    };

    const ouvrirDetail = (id: number) => {
        setDetailLoading(true);
        getProjetDetail(id)
            .then(setDetailOuvert)
            .catch(err => setError(err.message))
            .finally(() => setDetailLoading(false));
    };

    const handleAjouterType = async (typeId: number) => {
        if (!detailOuvert) return;
        try {
            await ajouterTypesAttendus(detailOuvert.id, [typeId]);
            ouvrirDetail(detailOuvert.id);
        } catch (err: any) {
            setError(err.message);
        }
    };

    const handleRetirerType = async (typeId: number) => {
        if (!detailOuvert) return;
        try {
            await retirerTypeAttendu(detailOuvert.id, typeId);
            ouvrirDetail(detailOuvert.id);
        } catch (err: any) {
            setError(err.message);
        }
    };

    const handleSupprimer = async (id: number) => {
        try {
            await supprimerProjet(id);
            setDetailOuvert(null);
            chargerProjets();
        } catch (err: any) {
            setError(err.message);
        }
    };

    if (!uoId) {
        return <div className="projets-panel-empty">Sélectionnez une unité organisationnelle.</div>;
    }

    return (
        <div className="projets-panel">
            {error && <div className="projets-panel-error">{error}</div>}

            {canCreate && (
                <div className="projets-panel-header">
                    <button className="sidebar-btn" onClick={ouvrirFormulaireCreation}>
                        <i className="fa-solid fa-folder-plus" /> Créer un projet
                    </button>
                </div>
            )}

            {showCreateForm && (
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

                    {accessCreation === 'PRIVE' && usersUO.length > 0 && (
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
                            <p>Types de documents attendus (optionnel — modifiable plus tard) :</p>
                            <div className="projets-types-list">
                                {typesUO.map(t => (
                                    <label key={t.id} className="projets-type-checkbox">
                                        <input
                                            type="checkbox"
                                            checked={selectedTypeIds.includes(t.id!)}
                                            onChange={() => toggleType(t.id!)}
                                        />
                                        <span>{t.nom}</span>
                                    </label>
                                ))}
                            </div>
                        </div>
                    )}

                    <div className="projets-create-actions">
                        <button
                            className="sidebar-btn"
                            disabled={!nom.trim() || creating}
                            onClick={handleCreer}
                        >
                            {creating ? 'Création…' : 'Créer'}
                        </button>
                        <button className="projets-cancel-btn" onClick={() => setShowCreateForm(false)}>
                            Annuler
                        </button>
                    </div>
                </div>
            )}

            {loading ? (
                <p>Chargement…</p>
            ) : projets.length === 0 ? (
                <p className="projets-panel-empty">Aucun projet pour cette unité organisationnelle.</p>
            ) : (
                <ul className="projets-list">
                    {projets.map(p => (
                        <li key={p.id} className="projets-list-item" onClick={() => ouvrirDetail(p.id)}>
                            <div className="projets-list-item-main">
                                <span className="projets-list-item-nom">{p.nom}</span>
                                {p.description && <span className="projets-list-item-desc">{p.description}</span>}
                            </div>
                            <span className="projets-list-item-meta">
                                {p.creePar?.prenom} {p.creePar?.nom} · {new Date(p.createAt).toLocaleDateString('fr-FR')}
                            </span>
                        </li>
                    ))}
                </ul>
            )}

            {detailOuvert && (
                <div className="projets-detail-overlay" onClick={() => setDetailOuvert(null)}>
                    <div className="projets-detail-panel" onClick={e => e.stopPropagation()}>
                        <div className="projets-detail-header">
                            <h3>
                                {detailOuvert.nom}
                                {detailOuvert.access === 'PRIVE' && (
                                    <span className="doc-access-tag prive projets-access-badge">Privé</span>
                                )}
                            </h3>
                            <button onClick={() => setDetailOuvert(null)} aria-label="Fermer">
                                <i className="fa-solid fa-xmark" />
                            </button>
                        </div>

                        {detailLoading ? (
                            <p>Chargement…</p>
                        ) : (
                            <>
                                {detailOuvert.description && <p>{detailOuvert.description}</p>}
                                <p className="projets-detail-meta">
                                    Créé par {detailOuvert.creePar} — {new Date(detailOuvert.createAt).toLocaleDateString('fr-FR')}
                                </p>

                                {detailOuvert.access === 'PRIVE' && (
                                    <button
                                        className="projets-add-type-btn"
                                        onClick={() => setIsGroupeOpen(true)}
                                    >
                                        <i className="fa-solid fa-user-group" /> Voir qui a accès à ce projet
                                    </button>
                                )}

                                <h4>Types de documents attendus</h4>
                                {detailOuvert.typesAttendus.length === 0 ? (
                                    <p className="projets-panel-empty">Aucun type déclaré pour l'instant.</p>
                                ) : (
                                    <ul className="projets-checklist">
                                        {detailOuvert.typesAttendus.map(t => (
                                            <li key={t.typeDocumentId} className={t.fourni ? 'checklist-ok' : 'checklist-manquant'}>
                                                <span>
                                                    <i className={t.fourni ? 'fa-solid fa-circle-check' : 'fa-regular fa-circle'} />
                                                    {t.nom} ({t.nombreDocuments})
                                                </span>
                                                {detailOuvert.peutGererTypes && !t.fourni && (
                                                    <button
                                                        className="projets-retirer-type-btn"
                                                        onClick={() => handleRetirerType(t.typeDocumentId)}
                                                        title="Retirer ce type attendu"
                                                    >
                                                        <i className="fa-solid fa-xmark" />
                                                    </button>
                                                )}
                                            </li>
                                        ))}
                                    </ul>
                                )}

                                {detailOuvert.peutGererTypes &&
                                    typesUO.filter(t => !detailOuvert.typesAttendus.some(a => a.typeDocumentId === t.id)).length > 0 && (
                                    <div className="projets-add-type">
                                        <p>Ajouter un type attendu :</p>
                                        {typesUO
                                            .filter(t => !detailOuvert.typesAttendus.some(a => a.typeDocumentId === t.id))
                                            .map(t => (
                                                <button key={t.id} className="projets-add-type-btn" onClick={() => handleAjouterType(t.id!)}>
                                                    + {t.nom}
                                                </button>
                                            ))}
                                    </div>
                                )}

                                {detailOuvert.peutGererAcces && (
                                    <button
                                        className="projets-delete-btn"
                                        onClick={() => handleSupprimer(detailOuvert.id)}
                                    >
                                        <i className="fa-solid fa-trash" /> Supprimer ce projet
                                    </button>
                                )}
                            </>
                        )}
                    </div>
                </div>
            )}

            <Modal
                isOpen={isGroupeOpen}
                onClose={() => setIsGroupeOpen(false)}
                title="Accès au projet"
            >
                {detailOuvert && (
                    <GestionGroupeProjet
                        projetId={detailOuvert.id}
                        projetNom={detailOuvert.nom}
                        onClose={() => setIsGroupeOpen(false)}
                    />
                )}
            </Modal>
        </div>
    );
}

export default ProjetsPanel;
