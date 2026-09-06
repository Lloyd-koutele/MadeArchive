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
import { getTypeDocumentsVisibles } from '../services/document/TypedocumentService';
import { getProjetsDeUO } from '../services/organisation/ProjetService';
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
 * Le type et le projet se choisissent DÈS le départ, à côté de l'UO (options
 * réelles, chargées via getTypeDocumentsVisibles/getProjetsDeUO — pas
 * dérivées d'un premier chargement complet) — pas seulement comme des
 * filtres qui n'apparaîtraient qu'après avoir tout listé. Le serveur n'a pas
 * de paramètre dédié type/projet pour l'aperçu : le choix filtre la liste
 * dès qu'elle arrive, côté client, mais la sélection sous-jacente reste sur
 * tout le périmètre chargé (persiste si on change le filtre ensuite) — ça
 * permet d'exporter "un projet précis" ou "un groupe de documents précis"
 * en s'appuyant sur docIds (déjà supporté par l'API), sans aucun paramètre
 * serveur supplémentaire.
 */
function ExportPanel({ isOpen, onClose, uos, defaultUoId }: ExportPanelProps) {
    const notify = useNotify();
    const estAdmin = hasRole('ADMIN');

    // ── Périmètre UO ─────────────────────────────────────────────────────
    const [uoId, setUoId] = useState<number | null>(defaultUoId ?? null);
    const [includeChildren, setIncludeChildren] = useState(true);

    // ── Choix type/projet — dès le départ, options réelles pour cette UO ──
    const [typesOptions, setTypesOptions] = useState<{ nom: string }[]>([]);
    const [projetsOptions, setProjetsOptions] = useState<{ nom: string }[]>([]);
    const [filterType, setFilterType] = useState('');
    const [filterProjet, setFilterProjet] = useState('');
    const [filterTexte, setFilterTexte] = useState('');

    // ── Options de fond ──────────────────────────────────────────────────
    const [separateProjects, setSeparateProjects] = useState(false);
    const [excludeCorbeille, setExcludeCorbeille] = useState(true);
    const [includePriveNonMembre, setIncludePriveNonMembre] = useState(false);
    const [motif, setMotif] = useState('');

    // ── Documents du périmètre + sélection fine ─────────────────────────
    const [apercu, setApercu] = useState<ExportApercuDocumentDto[] | null>(null);
    const [apercuLoading, setApercuLoading] = useState(false);
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

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

    // Choix réels de type/projet pour l'UO sélectionnée — indépendant du
    // chargement des documents, dispo dès qu'on choisit une UO.
    useEffect(() => {
        if (!isOpen || uoId == null) { setTypesOptions([]); setProjetsOptions([]); return; }
        getTypeDocumentsVisibles(uoId).then(setTypesOptions).catch(() => setTypesOptions([]));
        getProjetsDeUO(uoId).then(setProjetsOptions).catch(() => setProjetsOptions([]));
    }, [isOpen, uoId]);

    // Changer d'UO/de périmètre invalide l'aperçu déjà chargé — on ne veut
    // jamais lancer un export sur une liste qui ne correspond plus au
    // périmètre affiché.
    useEffect(() => {
        setApercu(null);
        setSelectedIds(new Set());
        setJob(null);
        setFilterType(''); setFilterProjet(''); setFilterTexte('');
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
            // Tout coché par défaut, y compris ce que le filtre type/projet
            // choisi en amont masque déjà — décocher/re-filtrer reste possible.
            setSelectedIds(new Set(documents.map(d => d.id)));
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'aperçu");
        } finally {
            setApercuLoading(false);
        }
    };

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

    /**
     * Exporte directement dès qu'une UO est choisie — pas besoin de passer
     * par "Charger les documents" avant : l'aperçu détaillé (cases à cocher
     * une par une) reste possible mais devient une manipulation optionnelle,
     * pas une étape obligatoire.
     *
     * Si l'aperçu n'a jamais été chargé, résout silencieusement le périmètre
     * puis applique le type/projet déjà choisis en haut du formulaire pour
     * obtenir la liste de documents à exporter. Si l'aperçu a déjà été
     * chargé (et éventuellement affiné coche par coche), la sélection
     * manuelle prend le dessus — cohérent avec ce que l'utilisateur voit
     * à l'écran à ce moment-là.
     */
    const handleLancer = async () => {
        if (uoId == null) return;
        if (estAdmin && includePriveNonMembre && !motif.trim()) {
            notify.error("Un motif est obligatoire pour inclure des documents privés dont vous n'êtes pas membre");
            return;
        }
        setLancementLoading(true);
        try {
            const uoIds = await resoudrePerimetre();
            let docIds: string[];

            if (apercu) {
                if (selectedIds.size === 0) {
                    notify.error('Sélectionnez au moins un document à exporter');
                    setLancementLoading(false);
                    return;
                }
                docIds = Array.from(selectedIds);
            } else {
                const documents = await apercuExport({
                    uoIds,
                    excludeCorbeille,
                    includePriveNonMembre: estAdmin && includePriveNonMembre,
                });
                const filtres = documents.filter(d =>
                    (!filterType || d.typeDocumentNom === filterType) &&
                    (!filterProjet || d.projetNom === filterProjet)
                );
                if (filtres.length === 0) {
                    notify.error('Aucun document ne correspond à ce périmètre');
                    setLancementLoading(false);
                    return;
                }
                docIds = filtres.map(d => d.id);
            }

            const statut = await lancerExport({
                uoIds,
                docIds,
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
        <Modal isOpen={isOpen} onClose={onClose} title="Exporter des documents">
            <div className="export-panel">
                <p className="export-hint">
                    Génère un ZIP des documents déchiffrés (un dossier par type). Expire 48h après génération.
                </p>

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

                <div className="export-field-pair">
                    <label className="export-field">
                        Type de document
                        <select className="filter-input" value={filterType} onChange={e => setFilterType(e.target.value)}>
                            <option value="">Tous les types</option>
                            {typesOptions.map(t => <option key={t.nom} value={t.nom}>{t.nom}</option>)}
                        </select>
                    </label>
                    <label className="export-field">
                        Projet
                        <select className="filter-input" value={filterProjet} onChange={e => setFilterProjet(e.target.value)}>
                            <option value="">Tous les projets</option>
                            {projetsOptions.map(p => <option key={p.nom} value={p.nom}>{p.nom}</option>)}
                        </select>
                    </label>
                </div>

                <label className="export-option">
                    <input
                        type="checkbox"
                        checked={separateProjects}
                        onChange={e => setSeparateProjects(e.target.checked)}
                        disabled={jobEnCours}
                    />
                    Séparer par projet dans le ZIP
                </label>
                <label className="export-option">
                    <input
                        type="checkbox"
                        checked={excludeCorbeille}
                        onChange={e => setExcludeCorbeille(e.target.checked)}
                        disabled={jobEnCours}
                    />
                    Exclure la corbeille
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

                {/* "Exporter" apparaît dès qu'une UO est choisie — pas besoin
                    de charger l'aperçu au préalable. "Charger les documents"
                    reste disponible à côté pour qui veut affiner sa
                    sélection document par document avant de lancer ; une
                    manipulation optionnelle, pas une étape obligatoire. */}
                {uoId != null && !apercu && (
                    <div className="export-quick-actions">
                        <button
                            type="button"
                            className="action-button"
                            onClick={handleApercu}
                            disabled={apercuLoading || jobEnCours}
                        >
                            {apercuLoading
                                ? <><i className="fa-solid fa-spinner fa-spin" /> Chargement…</>
                                : <><i className="fa-solid fa-magnifying-glass" /> Choisir précisément</>}
                        </button>
                        <button
                            type="button"
                            className="form-submit-btn up-submit"
                            onClick={handleLancer}
                            disabled={lancementLoading || jobEnCours}
                        >
                            {lancementLoading
                                ? <><i className="fa-solid fa-spinner fa-spin" /> Lancement…</>
                                : <><i className="fa-solid fa-file-zipper" /> Exporter</>}
                        </button>
                    </div>
                )}

                {/* ── Sélection fine des documents ── */}
                {apercu && (
                    <div className="export-apercu">
                        <input
                            type="text"
                            className="filter-input"
                            placeholder="Rechercher un titre…"
                            value={filterTexte}
                            onChange={e => setFilterTexte(e.target.value)}
                        />

                        <div className="export-select-all">
                            <label className="export-option">
                                <input type="checkbox" checked={tousVisiblesCoches} onChange={toggleTousVisibles} />
                                Tout ({apercuFiltre.length})
                            </label>
                            <span className="export-count">
                                {selectedIds.size} / {apercu.length} sélectionné{selectedIds.size > 1 ? 's' : ''}
                            </span>
                        </div>

                        {apercuFiltre.length === 0 ? (
                            <div className="td-empty"><p>Aucun document ne correspond à ce filtre.</p></div>
                        ) : (
                            <div className="export-doc-list">
                                {apercuFiltre.map(doc => (
                                    <label key={doc.id} className={`export-doc-card ${!doc.accesNormal ? 'export-row-elevee' : ''}`}>
                                        <input
                                            type="checkbox"
                                            checked={selectedIds.has(doc.id)}
                                            onChange={() => toggleSelection(doc.id)}
                                        />
                                        <div className="export-doc-info">
                                            <p className="export-doc-title">{doc.titre}</p>
                                            <p className="export-doc-meta">
                                                {doc.typeDocumentNom ?? '—'}
                                                {doc.projetNom && <> · {doc.projetNom}</>}
                                                {' · '}
                                                <span className={`doc-access-tag ${doc.access === 'PUBLIC' ? 'public' : 'prive'}`}>
                                                    {doc.access === 'PUBLIC' ? 'Public' : 'Privé'}
                                                </span>
                                                {!doc.accesNormal && (
                                                    <i className="fa-solid fa-triangle-exclamation export-elevation-icon"
                                                        title="Inclus uniquement grâce à l'élévation ADMIN" />
                                                )}
                                            </p>
                                        </div>
                                    </label>
                                ))}
                            </div>
                        )}

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
