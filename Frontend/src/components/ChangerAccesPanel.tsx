import { useState } from 'react';
import { getCandidatsGroupe } from '../services/document/DocumentService';
import type { UserDto } from '../services/document/DocumentService';
import '../Style/Editor/Editor.css';

interface ChangerAccesPanelProps {
    accesActuel: 'PUBLIC' | 'PRIVE';
    /** UO du document/projet — sert à charger les candidats au groupe (collègues + ADMIN globaux). */
    uoId: number | null;
    saving?: boolean;
    /** PUBLIC → PRIVÉ : membres initiaux choisis, en plus de l'auteur (ajouté automatiquement côté serveur). */
    onRendrePrive: (groupeMembresIds: string[]) => void;
    /** PRIVÉ → PUBLIC : la confirmation elle-même (window.confirm-like) est à la charge de l'appelant,
     *  AVANT d'invoquer ce callback — voir useConfirm dans les composants parents. */
    onRendrePublic: () => void;
}

/**
 * Bascule PUBLIC ↔ PRIVÉ après coup — partagé entre documents
 * (document/DocumentsAccessible.tsx, Editor/MesDocumentsEditor.tsx) et
 * projets (organisation/ProjetsPanel.tsx), pour ne pas dupliquer trois fois
 * le sélecteur de membres.
 *
 * PUBLIC → PRIVÉ ouvre un sélecteur de membres initiaux, même endpoint et
 * même filtre (nom/email/téléphone) qu'à la création (voir
 * ImportDocuments.tsx/UploadSimple.tsx/ProjetsPanel.tsx — getCandidatsGroupe
 * : collègues de l'UO + tous les ADMIN globaux). PRIVÉ → PUBLIC est un
 * simple bouton : la confirmation ("ce document/projet redeviendra visible
 * par tous...") est à la charge de l'appelant, avant d'appeler
 * onRendrePublic, pas de ce composant.
 */
function ChangerAccesPanel({ accesActuel, uoId, saving = false, onRendrePrive, onRendrePublic }: ChangerAccesPanelProps) {
    const [ouvert, setOuvert] = useState(false);
    const [users, setUsers] = useState<UserDto[]>([]);
    const [selectedIds, setSelectedIds] = useState<string[]>([]);
    const [filtre, setFiltre] = useState('');
    const [chargement, setChargement] = useState(false);

    const toggleMembre = (id: string) => {
        setSelectedIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
    };

    const ouvrirSelecteur = () => {
        setOuvert(true);
        if (uoId != null && users.length === 0) {
            setChargement(true);
            getCandidatsGroupe(uoId)
                .then(setUsers)
                .catch(() => {})
                .finally(() => setChargement(false));
        }
    };

    const fermerSelecteur = () => {
        setOuvert(false);
        setSelectedIds([]);
        setFiltre('');
    };

    if (accesActuel === 'PRIVE') {
        return (
            <button
                type="button"
                className="acces-toggle-btn"
                onClick={onRendrePublic}
                disabled={saving}
                title="Rendre ce document/projet visible par tous les membres de l'UO"
            >
                <i className="fa-solid fa-lock-open" /> Rendre public
            </button>
        );
    }

    if (!ouvert) {
        return (
            <button type="button" className="acces-toggle-btn" onClick={ouvrirSelecteur} disabled={saving}>
                <i className="fa-solid fa-lock" /> Rendre privé
            </button>
        );
    }

    const usersFiltres = users.filter(u => {
        const q = filtre.trim().toLowerCase();
        if (!q) return true;
        return `${u.prenom} ${u.nom}`.toLowerCase().includes(q)
            || u.email.toLowerCase().includes(q)
            || (u.telephone ?? '').toLowerCase().includes(q);
    });

    return (
        <div className="acces-toggle-panel">
            <p className="membres-label">Membres initiaux (optionnel — vous serez ajouté automatiquement) :</p>
            {chargement ? (
                <p className="acces-toggle-loading">Chargement...</p>
            ) : users.length > 0 && (
                <>
                    <input
                        type="text"
                        className="membres-filtre-input"
                        placeholder="Rechercher (nom, email, téléphone)"
                        aria-label="Rechercher un utilisateur"
                        value={filtre}
                        onChange={e => setFiltre(e.target.value)}
                    />
                    <div className="membres-list">
                        {usersFiltres.map(u => (
                            <label key={u.id} className="membre-item">
                                <input
                                    type="checkbox"
                                    checked={selectedIds.includes(u.id)}
                                    onChange={() => toggleMembre(u.id)}
                                />
                                <span>{u.prenom} {u.nom}</span>
                                <span className="membre-email">{u.email}</span>
                            </label>
                        ))}
                    </div>
                </>
            )}
            <div className="acces-toggle-actions">
                <button
                    type="button"
                    className="form-submit-btn"
                    onClick={() => onRendrePrive(selectedIds)}
                    disabled={saving}
                >
                    {saving ? <><i className="fa-solid fa-spinner fa-spin" /> …</> : 'Confirmer'}
                </button>
                <button type="button" className="acces-toggle-cancel-btn" onClick={fermerSelecteur} disabled={saving}>
                    Annuler
                </button>
            </div>
        </div>
    );
}

export default ChangerAccesPanel;
