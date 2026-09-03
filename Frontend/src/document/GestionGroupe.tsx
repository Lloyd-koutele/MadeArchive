import { useState, useEffect } from 'react';
import {
    getMembres, getDisponibles, ajouterMembre, retirerMembre
} from '../services/document/GroupeService';

import type { MembreDto } from '../services/document/GroupeService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/Editor/Editor.css';

interface GestionGroupeProps {
    documentId: string;
    documentTitre: string;
    onClose?: () => void;
}

function GestionGroupe({ documentId, documentTitre, onClose }: GestionGroupeProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [membres, setMembres] = useState<MembreDto[]>([]);
    const [uploadeurId, setUploadeurId] = useState('');
    const [peutGerer, setPeutGerer] = useState(false);
    const [disponibles, setDisponibles] = useState<MembreDto[]>([]);
    const [selectedToAdd, setSelectedToAdd] = useState('');
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        loadAll();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [documentId]);

    const loadAll = async () => {
        setIsLoading(true);
        try {
            // Ouvert à tout membre du groupe — jamais bloqué par un manque de
            // droits de gestion (voir GroupeAccessService.getMembres côté serveur).
            const reponse = await getMembres(documentId);
            setMembres(reponse.membres);
            setUploadeurId(reponse.uploadeurId);
            setPeutGerer(reponse.peutGerer);

            // Seul l'uploadeur peut gérer le groupe — inutile (et refusé côté
            // serveur) de charger la liste des utilisateurs disponibles pour
            // les autres membres, en simple consultation.
            if (reponse.peutGerer) {
                const d = await getDisponibles(documentId);
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
            await ajouterMembre(documentId, selectedToAdd);
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
            await retirerMembre(documentId, membre.id);
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
                <i className="fa-solid fa-file-shield"></i> {documentTitre}
            </p>

            {!peutGerer && (
                <p className="groupe-readonly-hint">
                    Seul l'éditeur ayant archivé ce document peut ajouter ou retirer des membres.
                </p>
            )}

            {/* Membres actuels */}
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
                                        {m.id === uploadeurId && (
                                            <span className="groupe-membre-badge"> · Archiviste</span>
                                        )}
                                    </span>
                                    <span className="groupe-membre-email">{m.email}</span>
                                </div>
                                {peutGerer && m.id !== uploadeurId && (
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

            {/* Ajouter un membre — réservé à l'uploadeur */}
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
        </div>
    );
}

export default GestionGroupe;
