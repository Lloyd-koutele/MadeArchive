import { useState, useEffect } from 'react';
import {
    getMembresProjet, getDisponiblesProjet, ajouterMembreProjet, retirerMembreProjet
} from '../services/organisation/ProjetGroupeService';
import type { MembreDto } from '../services/document/GroupeService';
import Confirme from '../Page/Confirme';
import '../Style/Editor/Editor.css';

interface GestionGroupeProjetProps {
    projetId: number;
    projetNom: string;
    onClose?: () => void;
}

/**
 * Gestion des membres du groupe d'accès d'un projet privé — même comportement
 * que document/GestionGroupe.tsx : tout membre voit la liste, seul le
 * créateur (peutGerer) voit les contrôles d'ajout/retrait, et le créateur
 * lui-même n'apparaît jamais avec un bouton "Retirer".
 */
function GestionGroupeProjet({ projetId, projetNom, onClose }: GestionGroupeProjetProps) {
    const [membres, setMembres] = useState<MembreDto[]>([]);
    const [createurId, setCreateurId] = useState('');
    const [peutGerer, setPeutGerer] = useState(false);
    const [disponibles, setDisponibles] = useState<MembreDto[]>([]);
    const [selectedToAdd, setSelectedToAdd] = useState('');
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [membreToRemove, setMembreToRemove] = useState<MembreDto | null>(null);

    useEffect(() => {
        loadAll();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [projetId]);

    useEffect(() => {
        if (error || success) {
            const t = setTimeout(() => { setError(''); setSuccess(''); }, 3000);
            return () => clearTimeout(t);
        }
    }, [error, success]);

    const loadAll = async () => {
        setIsLoading(true);
        try {
            const reponse = await getMembresProjet(projetId);
            setMembres(reponse.membres);
            setCreateurId(reponse.uploadeurId);
            setPeutGerer(reponse.peutGerer);

            if (reponse.peutGerer) {
                const d = await getDisponiblesProjet(projetId);
                setDisponibles(d);
            } else {
                setDisponibles([]);
            }
        } catch (err: any) {
            setError(err.message || "Erreur chargement du groupe");
        } finally {
            setIsLoading(false);
        }
    };

    const handleAjouter = async () => {
        if (!selectedToAdd) return;
        try {
            await ajouterMembreProjet(projetId, selectedToAdd);
            setSuccess("Membre ajouté avec succès");
            setSelectedToAdd('');
            await loadAll();
        } catch (err: any) {
            setError(err.message || "Erreur lors de l'ajout");
        }
    };

    const handleRetirer = async () => {
        if (!membreToRemove) return;
        try {
            await retirerMembreProjet(projetId, membreToRemove.id);
            setSuccess(`${membreToRemove.prenom} ${membreToRemove.nom} retiré du groupe`);
            setMembreToRemove(null);
            setConfirmOpen(false);
            await loadAll();
        } catch (err: any) {
            setError(err.message || "Erreur lors du retrait");
        }
    };

    if (isLoading) return <div className="groupe-loading">Chargement du groupe...</div>;

    return (
        <div className="groupe-wrapper">
            {error && <div className="up-alert up-alert-error">{error}</div>}
            {success && <div className="up-alert up-alert-success">{success}</div>}

            <p className="groupe-doc-titre">
                <i className="fa-solid fa-folder-open"></i> {projetNom}
            </p>

            {!peutGerer && (
                <p className="groupe-readonly-hint">
                    Seul le créateur de ce projet peut ajouter ou retirer des membres.
                </p>
            )}

            <div className="groupe-section">
                <h4 className="groupe-section-title">
                    Membres ({membres.length})
                </h4>
                {membres.length === 0 ? (
                    <p className="groupe-empty">Aucun membre dans ce groupe.</p>
                ) : (
                    <div className="groupe-membres">
                        {membres.map(m => (
                            <div key={m.id} className="groupe-membre-item">
                                <div className="groupe-membre-avatar">
                                    {m.prenom.charAt(0)}{m.nom.charAt(0)}
                                </div>
                                <div className="groupe-membre-info">
                                    <span className="groupe-membre-nom">
                                        {m.prenom} {m.nom}
                                        {m.id === createurId && (
                                            <span className="groupe-membre-badge"> · Créateur</span>
                                        )}
                                    </span>
                                    <span className="groupe-membre-email">{m.email}</span>
                                </div>
                                {peutGerer && m.id !== createurId && (
                                    <button
                                        className="td-delete-btn"
                                        onClick={() => { setMembreToRemove(m); setConfirmOpen(true); }}
                                    >
                                        Retirer
                                    </button>
                                )}
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {peutGerer && disponibles.length > 0 && (
                <div className="groupe-section">
                    <h4 className="groupe-section-title">Ajouter un membre</h4>
                    <div className="groupe-add-row">
                        <select
                            className="form-field-input up-select"
                            value={selectedToAdd}
                            onChange={e => setSelectedToAdd(e.target.value)}
                            aria-label="Choisir un utilisateur à ajouter"
                        >
                            <option value="">-- Choisir un utilisateur --</option>
                            {disponibles.map(d => (
                                <option key={d.id} value={d.id}>
                                    {d.prenom} {d.nom} — {d.email}
                                </option>
                            ))}
                        </select>
                        <button
                            className="form-submit-btn"
                            onClick={handleAjouter}
                            disabled={!selectedToAdd}
                        >
                            Ajouter
                        </button>
                    </div>
                </div>
            )}

            <Confirme
                isOpen={confirmOpen}
                message={`Retirer ${membreToRemove?.prenom} ${membreToRemove?.nom} du groupe ?`}
                onConfirm={handleRetirer}
                onCancel={() => { setConfirmOpen(false); setMembreToRemove(null); }}
            />

            {onClose && (
                <button className="details-close-btn" onClick={onClose} style={{ marginTop: '0.5rem' }}>
                    Fermer
                </button>
            )}
        </div>
    );
}

export default GestionGroupeProjet;
