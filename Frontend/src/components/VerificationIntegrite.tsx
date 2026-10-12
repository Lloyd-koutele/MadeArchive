import { useState } from 'react';
import {
    verifierDocument,
    telechargerRapportVerification,
    type RapportVerification,
    type VerdictVerification,
} from '../services/document/DocumentService';
import '../Style/components/VerificationIntegrite.css';

interface Props {
    documentId: string;
    titre: string;
}

const VERDICTS: Record<VerdictVerification, { titre: string; classe: string; icone: string }> = {
    CONFORME:         { titre: 'Document conforme',               classe: 'ok',     icone: 'fa-circle-check' },
    PREUVES_ALTEREES: { titre: 'Fichier sain, preuves altérées',  classe: 'alerte', icone: 'fa-triangle-exclamation' },
    FICHIER_ALTERE:   { titre: 'Fichier altéré',                  classe: 'echec',  icone: 'fa-circle-xmark' },
    NON_VERIFIABLE:   { titre: 'Vérification impossible',         classe: 'alerte', icone: 'fa-circle-question' },
};

const ICONES_ETAPE: Record<string, string> = {
    OK: 'fa-circle-check', ALERTE: 'fa-triangle-exclamation', ECHEC: 'fa-circle-xmark', INFO: 'fa-circle-info',
};

const formatDate = (iso: string) => new Date(iso).toLocaleString('fr-FR');

/**
 * Bouton « Vérifier l'intégrité » de la consultation d'un document : relance à la demande le contrôle complet
 * (fichier rehashé, signatures, horodatage RFC 3161, chaînage du journal) et affiche le rapport, téléchargeable
 * en PDF. Lecture seule côté serveur — ne modifie ni le document ni son statut.
 */
function VerificationIntegrite({ documentId, titre }: Props) {
    const [loading, setLoading] = useState(false);
    const [exporting, setExporting] = useState(false);
    const [rapport, setRapport] = useState<RapportVerification | null>(null);
    const [erreur, setErreur] = useState<string | null>(null);
    const [ouvert, setOuvert] = useState(false);

    const lancer = async () => {
        setLoading(true);
        setErreur(null);
        setOuvert(true);
        try {
            setRapport(await verifierDocument(documentId));
        } catch (e: any) {
            setRapport(null);
            setErreur(e?.message ?? 'La vérification a échoué.');
        } finally {
            setLoading(false);
        }
    };

    const exporter = async () => {
        setExporting(true);
        try {
            await telechargerRapportVerification(documentId, titre);
        } catch {
            setErreur('Le rapport PDF n\'a pas pu être généré.');
        } finally {
            setExporting(false);
        }
    };

    return (
        <span className="verif-root">
            <button type="button" className="verif-btn" onClick={lancer} disabled={loading}>
                {loading
                    ? <><i className="fa-solid fa-spinner fa-spin" /> Vérification…</>
                    : <><i className="fa-solid fa-shield-halved" /> Vérifier l'intégrité</>}
            </button>

            {ouvert && (
                <div className="verif-panel" role="dialog" aria-label="Rapport de vérification d'intégrité">
                    <div className="verif-panel-head">
                        <strong>Vérification d'intégrité</strong>
                        <button type="button" className="verif-close" onClick={() => setOuvert(false)} aria-label="Fermer">
                            <i className="fa-solid fa-xmark" />
                        </button>
                    </div>

                    {loading && <p className="verif-wait"><i className="fa-solid fa-spinner fa-spin" /> Relecture du fichier et contrôle des preuves…</p>}
                    {erreur && <p className="verif-erreur">{erreur}</p>}

                    {rapport && !loading && (
                        <>
                            <div className={`verif-verdict ${VERDICTS[rapport.verdict].classe}`}>
                                <i className={`fa-solid ${VERDICTS[rapport.verdict].icone}`} />
                                <div>
                                    <strong>{VERDICTS[rapport.verdict].titre}{rapport.degrade ? ' (vérification dégradée)' : ''}</strong>
                                    <p>{rapport.conclusion}</p>
                                </div>
                            </div>

                            <ul className="verif-etapes">
                                {rapport.etapes.map(e => (
                                    <li key={e.code} className={`verif-etape ${e.statut.toLowerCase()}`}>
                                        <i className={`fa-solid ${ICONES_ETAPE[e.statut] ?? ICONES_ETAPE.INFO}`} />
                                        <div>
                                            <span className="verif-etape-titre">{e.libelle}</span>
                                            <span className="verif-etape-detail">{e.detail}</span>
                                        </div>
                                    </li>
                                ))}
                            </ul>

                            <div className="verif-empreintes">
                                <div><span>SHA-256 enregistré</span><code>{rapport.empreinteEnregistree ?? '—'}</code></div>
                                <div><span>SHA-256 recalculé</span><code>{rapport.empreinteRecalculee ?? '—'}</code></div>
                                {rapport.horodatageAttesteLe && (
                                    <div><span>Date attestée (RFC 3161)</span><code>{formatDate(rapport.horodatageAttesteLe)}</code></div>
                                )}
                            </div>

                            <div className="verif-pied">
                                <small>Vérifié le {formatDate(rapport.verifieLe)} par {rapport.verifiePar}</small>
                                <button type="button" className="verif-btn" onClick={exporter} disabled={exporting}>
                                    {exporting
                                        ? <><i className="fa-solid fa-spinner fa-spin" /> Génération…</>
                                        : <><i className="fa-solid fa-file-pdf" /> Télécharger le rapport (PDF)</>}
                                </button>
                            </div>
                        </>
                    )}
                </div>
            )}
        </span>
    );
}

export default VerificationIntegrite;
