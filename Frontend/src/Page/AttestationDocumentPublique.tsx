import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getAttestationDocumentViewUrl } from '../services/document/AttestationService';
import '../Style/AttestationPublique.css';

/**
 * Page PUBLIQUE (aucune authentification) — destination réelle du lien/QR
 * code imprimé sur une attestation d'archivage (voir
 * AttestationService.genererPdfPourToken côté serveur). Affiche le PDF/A
 * ORIGINAL archivé, pas l'attestation elle-même (voir AttestationPublique
 * pour celle-ci) — décision produit assumée : accessible même si le document
 * est PRIVÉ, le jeton d'attestation vaut autorisation. CONSULTATION
 * UNIQUEMENT depuis cette source (revu le 09/2026) : pas de bouton de
 * téléchargement, contrairement à AttestationPublique pour l'attestation
 * elle-même.
 */
function AttestationDocumentPublique() {
    const { token } = useParams<{ token: string }>();

    const [blobUrl, setBlobUrl] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [erreur, setErreur]   = useState('');

    useEffect(() => {
        if (!token) {
            setErreur('Lien invalide.');
            setLoading(false);
            return;
        }

        let objectUrl: string | null = null;
        let annule = false;

        (async () => {
            try {
                const res = await fetch(getAttestationDocumentViewUrl(token));
                if (!res.ok) {
                    throw new Error('introuvable');
                }
                const blob = await res.blob();
                if (annule) return;
                objectUrl = URL.createObjectURL(blob);
                setBlobUrl(objectUrl);
            } catch {
                if (!annule) {
                    setErreur('Ce document est introuvable, ou l\'attestation associée a expiré.');
                }
            } finally {
                if (!annule) setLoading(false);
            }
        })();

        return () => {
            annule = true;
            if (objectUrl) URL.revokeObjectURL(objectUrl);
        };
    }, [token]);

    return (
        <div className="attest-page">
            <header className="attest-header">
                <div className="attest-brand">
                    <i className="fa-solid fa-box-archive" />
                    <span>MadeArchive</span>
                </div>
                <span className="attest-subtitle">Document archivé</span>
            </header>

            <main className="attest-main">
                {loading ? (
                    <div className="attest-state">
                        <i className="fa-solid fa-spinner fa-spin" />
                        <p>Chargement du document…</p>
                    </div>
                ) : erreur ? (
                    <div className="attest-state attest-error">
                        <i className="fa-solid fa-circle-exclamation" />
                        <p>{erreur}</p>
                    </div>
                ) : (
                    <>
                        <div className="attest-viewer">
                            <iframe src={blobUrl ?? undefined} title="Document archivé" />
                        </div>
                        {token && (
                            <Link className="attest-lien-secondaire" to={`/attestation/${token}`}>
                                Voir l'attestation d'archivage
                            </Link>
                        )}
                    </>
                )}
            </main>
        </div>
    );
}

export default AttestationDocumentPublique;
