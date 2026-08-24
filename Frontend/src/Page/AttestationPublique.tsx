import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getAttestationViewUrl, getAttestationDownloadUrl } from '../services/document/AttestationService';
import '../Style/AttestationPublique.css';

/**
 * Page PUBLIQUE (aucune authentification) — destination du lien/QR code
 * imprimé sur une attestation d'archivage. Affiche le PDF de l'attestation
 * dans une visionneuse intégrée (comme le lecteur PDF authentifié) et
 * propose son téléchargement libre. Le document original reste dans son
 * état de confidentialité normal ; cette page ne donne accès qu'au PDF de
 * l'attestation lui-même.
 */
function AttestationPublique() {
    const { token } = useParams<{ token: string }>();

    const [blobUrl, setBlobUrl] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [erreur, setErreur]   = useState('');

    useEffect(() => {
        if (!token) {
            setErreur('Lien d\'attestation invalide.');
            setLoading(false);
            return;
        }

        let objectUrl: string | null = null;
        let annule = false;

        (async () => {
            try {
                const res = await fetch(getAttestationViewUrl(token));
                if (!res.ok) {
                    throw new Error('introuvable');
                }
                const blob = await res.blob();
                if (annule) return;
                objectUrl = URL.createObjectURL(blob);
                setBlobUrl(objectUrl);
            } catch {
                if (!annule) {
                    setErreur('Cette attestation est introuvable, ou le document associé a été supprimé.');
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
                <span className="attest-subtitle">Attestation d'archivage</span>
            </header>

            <main className="attest-main">
                {loading ? (
                    <div className="attest-state">
                        <i className="fa-solid fa-spinner fa-spin" />
                        <p>Chargement de l'attestation…</p>
                    </div>
                ) : erreur ? (
                    <div className="attest-state attest-error">
                        <i className="fa-solid fa-circle-exclamation" />
                        <p>{erreur}</p>
                    </div>
                ) : (
                    <>
                        <div className="attest-viewer">
                            <iframe src={blobUrl ?? undefined} title="Attestation d'archivage" />
                        </div>
                        <a
                            className="attest-download-btn"
                            href={token ? getAttestationDownloadUrl(token) : undefined}
                        >
                            <i className="fa-solid fa-download" /> Télécharger l'attestation
                        </a>
                    </>
                )}
            </main>
        </div>
    );
}

export default AttestationPublique;
