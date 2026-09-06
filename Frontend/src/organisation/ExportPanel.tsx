import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import Modal from '../Page/Modal';
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

interface UOOption {
    id: number;
    nom: string;
    cheminComplet?: string;
}

interface ExportPanelProps {
    isOpen: boolean;
    onClose: () => void;
    /** UO déjà scopées correctement pour le rôle courant (ADMIN : toutes ;
     *  ADMIN_UO : son sous-arbre) — ce composant ne refait aucun filtrage. */
    uos: UOOption[];
    /** Pré-sélectionnée à l'ouverture — l'UO actuellement affichée dans le tableau de bord. */
    defaultUoId?: number | null;
}

const POLL_INTERVAL_MS = 2000;

/**
 * Export administratif de documents — voir DocumentExportService côté
 * serveur. Modal auto-suffisant (état interne réinitialisé à chaque
 * ouverture) plutôt qu'un onglet dédié : l'export est une action ponctuelle,
 * pas une vue qu'on garde affichée.
 *
 * Contrôle fin du périmètre en deux temps : d'abord une UO (+ éventuellement
 * son sous-arbre), puis un filtrage/une sélection PARMI les documents de ce
 * périmètre (par type, par projet, ou coche par coche) — permet d'exporter
 * "un projet précis" ou "un groupe de documents précis" sans qu'aucun
 * paramètre serveur dédié n'existe pour ça : docIds (déjà supporté par
 * l'API) fait tout le travail.
 */
function ExportPanel({ isOpen, onClose, uos, defaultUoId }: ExportPanelProps) {
    const notify = useNotify();
    const estAdmin = hasRole('ADMIN');

    // ── Périmètre UO ─────────────────────────────────────────────────────
    const [uoId, setUoId] = useState<number | null>(defaultUoId ?? null);
    const [includeChildren, setIncludeChildren] = useState(true);

    // ── Options de fond ──────────────────────────────────────────────────
    const [separateProjects, setSeparateProjects] = useState(false);
    const [excludeCorbeille, setExcludeCorbeille] = useState(true);
    const [includePriveNonMembre, setIncludePriveNonMembre] = useState(false);
    const [motif, setMotif] = useState('');

    // ── Documents du périmètre + sélection fine ─────────────────────────
    const [apercu, setApercu] = useState<ExportApercuDocumentDto[] | null>(null);
    const [apercuLoading, setApercuLoading] = useState(false);
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [filterType, setFilterType] = useState('');
    const [filterProjet, setFilterProjet] = useState('');
    const [filterTexte, setFilterTexte] = useState('');

    // ── Job d'export ─────────────────────────────────────────────────────
    const [job, setJob] = useState<ExportJobStatutDto | null>(null);
    const [lancementLoading, setLancementLoading] = useState(false);
    const [telechargementLoading, setTelechargementLoading] = useState(false);
    const pollRef = useRef<number | null>(null);

    // Repart de zéro à chaque ouverture — c'est une action ponctuelle, pas
    // un état qu'on veut retrouver tel quel la fois suivante.
    useEffect(() => {
        if (!isOpen) return;
        setUoId(defaultUoId ?? null);
        setIncludeChildren(true);
        setSeparateProjects(false);
        setExcludeCorbeille(true);
        setIncludePriveNonMembre(false);
        setMotif('');
        setApercu(null);
        setSelectedIds(new Set());
        setFilterType(''); setFilterProjet(''); setFilterTexte('');
        setJob(null);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isOpen]);

    // Changer d'UO/de périmètre invalide l'aperçu déjà chargé — on ne veut
    // jamais lancer un export sur une liste qui ne correspond plus au
    // périmètre affiché.
    useEffect(() => {
        setApercu(null);
        setSelectedIds(new Set());
        setJob(null);
        if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [uoId, includeChildren, excludeCorbeille, includePriveNonMembre]);

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
            // Tout coché par défaut — l'export "sans rien filtrer" reste le
            // cas simple, décocher/filtrer est l'exception.
            setSelectedIds(new Set(documents.map(d => d.id)));
            setFilterType(''); setFilterProjet(''); setFilterTexte('');
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'aperçu");
        } finally {
            setApercuLoading(false);
        }
    };

    const typesDisponibles = useMemo(
        () => Array.from(new Set((apercu ?? []).map(d => d.typeDocumentNom).filter((v): v is string => !!v))).sort(),
        [apercu]
    );
    const projetsDisponibles = useMemo(
        () => Array.from(new Set((apercu ?? []).map(d => d.projetNom).filter((v): v is string => !!v))).sort(),
        [apercu]
    );

    const apercuFiltre = useMemo(() => {
        if (!apercu) return [];
        const texte = filterTexte.trim().toLowerCase();
        return apercu.filter(d =>
            (!filterType || d.typeDocumentNom === filterType) &&
            (!filterProjet || d.projetNom === filterProjet) &&
            (!texte || d.titre.toLowerCase().includes(texte))
        );
    }, [apercu, filterType, filterProjet, filterTexte]);

    const toggleSelection = (id: string) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const tousVisiblesCoches = apercuFiltre.length > 0 && apercuFiltre.every(d => selectedIds.has(d.id));
    const toggleTousVisibles = () => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            if (tousVisiblesCoches) apercuFiltre.forEach(d => next.delete(d.id));
            else apercuFiltre.forEach(d => next.add(d.id));
            return next;
        });
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
        if (uoId == null || !apercu) return;
        if (selectedIds.size === 0) {
            notify.error('Sélectionnez au moins un document à exporter');
            return;
        }
        if (estAdmin && includePriveNonMembre && !motif.trim()) {
            notify.error("Un motif est obligatoire pour inclure des documents privés dont vous n'êtes pas membre");
            return;
        }
        setLancementLoading(true);
        try {
            const uoIds = await resoudrePerimetre();
            // docIds restreint TOUJOURS explicitement à la sélection courante
            // — que ce soit "tout" (coché par défaut) ou un sous-ensemble
            // filtré/décoché à la main, le comportement est identique et
            // sans ambiguïté côté serveur.
            const statut = await lancerExport({
                uoIds,
                docIds: Array.from(selectedIds),
                separateProjects,
                excludeCorbeille,
                includePriveNonMembre: estAdmin && includePriveNonMembre,
                motif: estAdmin && includePriveNonMembre ? motif.trim() : undefined,
            });
            setJob(statut);
            notify.success('Export lancé — génération en cours');
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

    const jobEnCours = job !== null && job.statut !== 'PRET' && job.statut !== 'ECHEC';

    return (
        <Modal isOpen={isOpen} onClose={onClose} title="Exporter des documents" size="large">
            <div className="export-panel">
                <p className="users-count" style={{ marginBottom: '0.75rem' }}>
                    <i className="fa-solid fa-circle-info" style={{ marginRight: '0.4rem', color: 'var(--text-light)' }} />
                    Génère un ZIP des documents déchiffrés (dossiers par UO puis par type, un manifest.csv à la
                    racine) — pensé pour une migration, pas un usage quotidien. Expire automatiquement 48h après
                    génération.
                </p>

                {/* ── Périmètre ── */}
                <div className="export-options">
                    <div className="export-scope-row">
                        <label className="export-field">
                            Unité organisationnelle
                            <select
                                className="filter-input"
                                value={uoId ?? ''}
                                onChange={e => setUoId(e.target.value ? Number(e.target.value) : null)}
                                disabled={jobEnCours}
                            >
                                <option value="">— Choisir une UO —</option>
                                {uos.map(u => (
                                    <option key={u.id} value={u.id}>{u.cheminComplet ?? u.nom}</option>
                                ))}
                            </select>
                        </label>
                        <label className="export-option">
                            <input
                                type="checkbox"
                                checked={includeChildren}
                                onChange={e => setIncludeChildren(e.target.checked)}
                                disabled={jobEnCours}
                            />
                            Inclure les UO enfants
                        </label>
                    </div>

                    <label className="export-option">
                        <input
                            type="checkbox"
                            checked={separateProjects}
                            onChange={e => setSeparateProjects(e.target.checked)}
                            disabled={jobEnCours}
                        />
                        Séparer les documents par projet (dans le ZIP)
                    </label>
                    <label className="export-option">
                        <input
                            type="checkbox"
                            checked={excludeCorbeille}
                            onChange={e => setExcludeCorbeille(e.target.checked)}
                            disabled={jobEnCours}
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
                                    disabled={jobEnCours}
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
                                        disabled={jobEnCours}
                                    />
                                </div>
                            )}
                        </>
                    )}

                    <button
                        type="button"
                        className="action-button"
                        onClick={handleApercu}
                        disabled={uoId == null || apercuLoading || jobEnCours}
                    >
                        {apercuLoading
                            ? <><i className="fa-solid fa-spinner fa-spin" /> Chargement…</>
                            : <><i className="fa-solid fa-magnifying-glass" /> Charger les documents</>}
                    </button>
                </div>

                {/* ── Sélection fine des documents ── */}
                {apercu && (
                    <div className="export-apercu">
                        <div className="export-filter-bar">
                            <input
                                type="text"
                                className="filter-input"
                                placeholder="Rechercher un titre…"
                                value={filterTexte}
                                onChange={e => setFilterTexte(e.target.value)}
                            />
                            <select className="filter-input" value={filterType} onChange={e => setFilterType(e.target.value)}>
                                <option value="">Tous les types</option>
                                {typesDisponibles.map(t => <option key={t} value={t}>{t}</option>)}
                            </select>
                            <select className="filter-input" value={filterProjet} onChange={e => setFilterProjet(e.target.value)}>
                                <option value="">Tous les projets</option>
                                {projetsDisponibles.map(p => <option key={p} value={p}>{p}</option>)}
                            </select>
                        </div>

                        <p className="users-count">
                            <span>{selectedIds.size}</span> / {apercu.length} document{apercu.length > 1 ? 's' : ''}{' '}
                            sélectionné{selectedIds.size > 1 ? 's' : ''}
                            {apercuFiltre.length !== apercu.length && <> — {apercuFiltre.length} affiché{apercuFiltre.length > 1 ? 's' : ''} avec ce filtre</>}
                        </p>

                        {apercuFiltre.length === 0 ? (
                            <div className="td-empty"><p>Aucun document ne correspond à ce filtre.</p></div>
                        ) : (
                            <div className="td-table-container export-table-scroll">
                                <table className="td-table">
                                    <thead>
                                        <tr>
                                            <th className="td-select-col">
                                                <input type="checkbox" checked={tousVisiblesCoches} onChange={toggleTousVisibles} />
                                            </th>
                                            <th>Titre</th>
                                            <th>Type</th>
                                            <th>UO</th>
                                            <th>Projet</th>
                                            <th>Accès</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {apercuFiltre.map(doc => (
                                            <tr key={doc.id} className={!doc.accesNormal ? 'export-row-elevee' : undefined}>
                                                <td className="td-select-col">
                                                    <input
                                                        type="checkbox"
                                                        checked={selectedIds.has(doc.id)}
                                                        onChange={() => toggleSelection(doc.id)}
                                                    />
                                                </td>
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

                        <div className="export-actions">
                            <button
                                type="button"
                                className="form-submit-btn up-submit"
                                onClick={handleLancer}
                                disabled={lancementLoading || jobEnCours || selectedIds.size === 0}
                            >
                                {lancementLoading
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> Lancement…</>
                                    : <><i className="fa-solid fa-file-zipper" /> Exporter la sélection ({selectedIds.size})</>}
                            </button>
                        </div>
                    </div>
                )}

                {/* ── Suivi du job ── */}
                {job && (
                    <div className="export-job">
                        {jobEnCours ? (
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
        </Modal>
    );
}

export default ExportPanel;
