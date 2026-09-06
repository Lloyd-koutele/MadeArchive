import { useState, useEffect, useRef, useCallback } from 'react';
import {
    apercuExport,
    lancerExport,
    getStatutExport,
    telechargerExport,
} from '../services/document/DocumentExportService';
import type { ExportApercuDocumentDto, ExportJobStatutDto } from '../services/document/DocumentExportService';
import { getSousArbre } from '../services/organisation/UOService';
import { hasRole } from '../auth/authService';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/organisation/ExportPanel.css';

interface ExportPanelProps {
    /** null = pas d'UO sélectionnée — l'export est toujours scopé à une UO précise. */
    uoId: number | null;
    uoNom?: string;
}

const POLL_INTERVAL_MS = 2000;

/**
 * Export administratif des documents d'une UO (et, en option, de tout son
 * sous-arbre) — voir DocumentExportService côté serveur. Jamais utilisé au
 * quotidien : pensé pour une migration ou un changement de système
 * d'archivage, d'où l'aperçu obligatoire avant de lancer quoi que ce soit.
 */
function ExportPanel({ uoId, uoNom }: ExportPanelProps) {
    const notify = useNotify();
    const estAdmin = hasRole('ADMIN');

    // ── Options ──────────────────────────────────────────────────────────
    const [includeChildren, setIncludeChildren] = useState(true);
    const [separateProjects, setSeparateProjects] = useState(false);
    const [excludeCorbeille, setExcludeCorbeille] = useState(true);
    const [includePriveNonMembre, setIncludePriveNonMembre] = useState(false);
    const [motif, setMotif] = useState('');

    // ── Aperçu ───────────────────────────────────────────────────────────
    const [apercu, setApercu] = useState<ExportApercuDocumentDto[] | null>(null);
    const [apercuLoading, setApercuLoading] = useState(false);

    // ── Job d'export en cours/terminé ────────────────────────────────────
    const [job, setJob] = useState<ExportJobStatutDto | null>(null);
    const [lancementLoading, setLancementLoading] = useState(false);
    const [telechargementLoading, setTelechargementLoading] = useState(false);
    const pollRef = useRef<number | null>(null);

    // Changer d'UO ou d'option invalide l'aperçu/job précédents — on ne
    // veut jamais lancer un export sur un périmètre qui ne correspond plus
    // à ce que l'aperçu affiché montrait.
    useEffect(() => {
        setApercu(null);
        setJob(null);
        if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    }, [uoId, includeChildren, separateProjects, excludeCorbeille, includePriveNonMembre]);

    useEffect(() => () => {
        if (pollRef.current) window.clearInterval(pollRef.current);
    }, []);

    const resoudrePerimetre = useCallback(async (): Promise<number[]> => {
        if (uoId == null) return [];
        if (!includeChildren) return [uoId];
        const sousArbre: { id: number }[] = await getSousArbre(uoId);
        return sousArbre.map(u => u.id);
    }, [uoId, includeChildren]);

    const handleApercu = async () => {
        if (uoId == null) return;
        setApercuLoading(true);
        setJob(null);
        try {
            const uoIds = await resoudrePerimetre();
            const documents = await apercuExport({
                uoIds,
                excludeCorbeille,
                includePriveNonMembre: estAdmin && includePriveNonMembre,
            });
            setApercu(documents);
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'aperçu");
        } finally {
            setApercuLoading(false);
        }
    };

    const suivreJob = (jobId: string) => {
        pollRef.current = window.setInterval(async () => {
            try {
                const statut = await getStatutExport(jobId);
                setJob(statut);
                if (statut.statut === 'PRET' || statut.statut === 'ECHEC') {
                    if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
                }
            } catch {
                // Best-effort — un raté ponctuel de sondage ne doit pas arrêter
                // le suivi, le prochain tick réessaiera.
            }
        }, POLL_INTERVAL_MS);
    };

    const handleLancer = async () => {
        if (uoId == null) return;
        if (estAdmin && includePriveNonMembre && !motif.trim()) {
            notify.error("Un motif est obligatoire pour inclure des documents privés dont vous n'êtes pas membre");
            return;
        }
        setLancementLoading(true);
        try {
            const uoIds = await resoudrePerimetre();
            const statut = await lancerExport({
                uoIds,
                separateProjects,
                excludeCorbeille,
                includePriveNonMembre: estAdmin && includePriveNonMembre,
                motif: estAdmin && includePriveNonMembre ? motif.trim() : undefined,
            });
            setJob(statut);
            notify.success("Export lancé — génération en cours");
            suivreJob(statut.id);
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors du lancement de l'export");
        } finally {
            setLancementLoading(false);
        }
    };

    const handleTelecharger = async () => {
        if (!job) return;
        setTelechargementLoading(true);
        try {
            await telechargerExport(job.id);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du téléchargement');
        } finally {
            setTelechargementLoading(false);
        }
    };

    if (uoId == null) {
        return (
            <div className="pl-empty">
                <i className="fa-solid fa-file-zipper" />
                <p>Sélectionnez une unité organisationnelle pour exporter ses documents.</p>
            </div>
        );
    }

    const nbAvecElevation = apercu?.filter(d => !d.accesNormal).length ?? 0;

    return (
        <div className="mes-docs-wrapper export-panel">
            <div className="mes-docs-header">
                <h2 className="mes-docs-title">
                    Export{uoNom ? ` — ${uoNom}` : ''}
                </h2>
            </div>

            <p className="users-count" style={{ marginBottom: '0.75rem' }}>
                <i className="fa-solid fa-circle-info" style={{ marginRight: '0.4rem', color: 'var(--text-light)' }} />
                Génère un ZIP des documents déchiffrés (dossiers par UO puis par type, un manifest.csv à la
                racine) — pensé pour une migration, pas un usage quotidien. Le fichier expire automatiquement
                48h après génération.
            </p>

            <div className="export-options">
                <label className="export-option">
                    <input
                        type="checkbox"
                        checked={includeChildren}
                        onChange={e => setIncludeChildren(e.target.checked)}
                    />
                    Inclure les UO enfants (tout le sous-arbre)
                </label>
                <label className="export-option">
                    <input
                        type="checkbox"
                        checked={separateProjects}
                        onChange={e => setSeparateProjects(e.target.checked)}
                    />
                    Séparer les documents par projet
                </label>
                <label className="export-option">
                    <input
                        type="checkbox"
                        checked={excludeCorbeille}
                        onChange={e => setExcludeCorbeille(e.target.checked)}
                    />
                    Exclure les documents en corbeille
                </label>

                {estAdmin && (
                    <>
                        <label className="export-option export-option-sensible">
                            <input
                                type="checkbox"
                                checked={includePriveNonMembre}
                                onChange={e => setIncludePriveNonMembre(e.target.checked)}
                            />
                            <i className="fa-solid fa-triangle-exclamation" /> Inclure les documents privés dont je
                            ne suis pas membre
                        </label>
                        {includePriveNonMembre && (
                            <div className="export-motif">
                                <label htmlFor="export-motif-input">
                                    Motif (obligatoire — envoyé aux membres concernés)
                                </label>
                                <textarea
                                    id="export-motif-input"
                                    rows={2}
                                    value={motif}
                                    onChange={e => setMotif(e.target.value)}
                                    placeholder="Ex. Migration vers le nouveau système d'archivage"
                                />
                            </div>
                        )}
                    </>
                )}
            </div>

            <div className="export-actions">
                <button
                    type="button"
                    className="action-button"
                    onClick={handleApercu}
                    disabled={apercuLoading}
                >
                    {apercuLoading
                        ? <><i className="fa-solid fa-spinner fa-spin" /> Chargement…</>
                        : <><i className="fa-solid fa-magnifying-glass" /> Aperçu</>}
                </button>
                <button
                    type="button"
                    className="form-submit-btn up-submit"
                    onClick={handleLancer}
                    disabled={lancementLoading || (job !== null && job.statut !== 'PRET' && job.statut !== 'ECHEC')}
                >
                    {lancementLoading
                        ? <><i className="fa-solid fa-spinner fa-spin" /> Lancement…</>
                        : <><i className="fa-solid fa-file-zipper" /> Lancer l'export</>}
                </button>
            </div>

            {/* ── Aperçu ── */}
            {apercu && (
                <div className="export-apercu">
                    <p className="users-count">
                        <span>{apercu.length}</span> document{apercu.length > 1 ? 's' : ''} dans ce périmètre
                        {nbAvecElevation > 0 && (
                            <span className="export-elevation-note">
                                {' '}— dont {nbAvecElevation} privé{nbAvecElevation > 1 ? 's' : ''} inclus
                                uniquement grâce à l'élévation ci-dessus
                            </span>
                        )}
                    </p>
                    {apercu.length === 0 ? (
                        <div className="td-empty"><p>Aucun document dans ce périmètre.</p></div>
                    ) : (
                        <div className="td-table-container">
                            <table className="td-table">
                                <thead>
                                    <tr>
                                        <th>Titre</th>
                                        <th>Type</th>
                                        <th>UO</th>
                                        <th>Projet</th>
                                        <th>Accès</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {apercu.map(doc => (
                                        <tr key={doc.id} className={!doc.accesNormal ? 'export-row-elevee' : undefined}>
                                            <td className="td-nom">{doc.titre}</td>
                                            <td>{doc.typeDocumentNom ?? '—'}</td>
                                            <td>{doc.uoNom ?? '—'}</td>
                                            <td>{doc.projetNom ?? '—'}</td>
                                            <td>
                                                <span className={`doc-access-tag ${doc.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                                                    {doc.access === 'PUBLIC' ? 'Public' : 'Privé'}
                                                </span>
                                                {!doc.accesNormal && (
                                                    <i className="fa-solid fa-triangle-exclamation export-elevation-icon"
                                                        title="Inclus uniquement grâce à l'élévation ADMIN" />
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            )}

            {/* ── Suivi du job ── */}
            {job && (
                <div className="export-job">
                    {job.statut === 'EN_ATTENTE' || job.statut === 'EN_COURS' ? (
                        <>
                            <p className="export-job-statut">
                                <i className="fa-solid fa-spinner fa-spin" /> Génération en cours —{' '}
                                {job.documentsTraites} / {job.documentsTotal} document{job.documentsTotal > 1 ? 's' : ''}
                                {job.documentsEnEchec > 0 && ` (${job.documentsEnEchec} échec(s))`}
                            </p>
                            <div className="export-progress-bar">
                                <div
                                    className="export-progress-fill"
                                    style={{ width: `${job.documentsTotal > 0 ? (job.documentsTraites / job.documentsTotal) * 100 : 0}%` }}
                                />
                            </div>
                        </>
                    ) : job.statut === 'PRET' ? (
                        <div className="export-job-pret">
                            <p className="export-job-statut">
                                <i className="fa-solid fa-circle-check" style={{ color: 'var(--success)' }} />{' '}
                                Export prêt — {job.documentsTraites} document{job.documentsTraites > 1 ? 's' : ''}
                                {job.documentsEnEchec > 0 && ` (${job.documentsEnEchec} échec(s))`}
                            </p>
                            <button
                                type="button"
                                className="form-submit-btn up-submit"
                                onClick={handleTelecharger}
                                disabled={telechargementLoading}
                            >
                                {telechargementLoading
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> Téléchargement…</>
                                    : <><i className="fa-solid fa-download" /> Télécharger le ZIP</>}
                            </button>
                            <p className="export-job-expire">
                                Expire le {new Date(job.expireAt).toLocaleString('fr-FR')}
                            </p>
                        </div>
                    ) : (
                        <p className="export-job-statut export-job-echec">
                            <i className="fa-solid fa-triangle-exclamation" /> Échec de la génération de l'export.
                        </p>
                    )}
                </div>
            )}
        </div>
    );
}

export default ExportPanel;
