import { useState, useEffect } from 'react';
import {
    getMembresProjet, getDisponiblesProjet, ajouterMembreProjet, retirerMembreProjet
} from '../services/organisation/ProjetGroupeService';
import type { MembreDto } from '../services/document/GroupeService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
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
    const notify = useNotify();
    const confirm = useConfirm();
    const [membres, setMembres] = useState<MembreDto[]>([]);
    const [createurId, setCreateurId] = useState('');
    const [peutGerer, setPeutGerer] = useState(false);
    const [disponibles, setDisponibles] = useState<MembreDto[]>([]);
    const [selectedToAdd, setSelectedToAdd] = useState('');
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        loadAll();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [projetId]);

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
            notify.error(err.message || "Erreur chargement du groupe");
        } finally {
            setIsLoading(false);
        }
    };

    const handleAjouter = async () => {
        if (!selectedToAdd) return;
        try {
            await ajouterMembreProjet(projetId, selectedToAdd);
            notify.success("Membre ajouté avec succès");
            setSelectedToAdd('');
            await loadAll();
        } catch (err: any) {
            notify.error(err.message || "Erreur lors de l'ajout");
        }
    };

    const handleRetirer = async (membre: MembreDto) => {
        if (!(await confirm(`Retirer ${membre.prenom} ${membre.nom} du groupe ?`))) return;
        try {
            await retirerMembreProjet(projetId, membre.id);
            notify.success(`${membre.prenom} ${membre.nom} retiré du groupe`);
            await loadAll();
        } catch (err: any) {
            notify.error(err.message || "Erreur lors du retrait");
        }
    };

    if (isLoading) return <div className="groupe-loading">Chargement du groupe...</div>;

    return (
        <div className="groupe-wrapper">
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
                                        onClick={() => handleRetirer(m)}
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

            {onClose && (
                <button className="details-close-btn" onClick={onClose} style={{ marginTop: '0.5rem' }}>
                    Fermer
                </button>
            )}
        </div>
    );
}

export default GestionGroupeProjet;
