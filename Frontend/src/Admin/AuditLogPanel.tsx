import { Fragment, useCallback, useEffect, useState } from 'react';
import { rechercherAuditLogs, exporterAuditLogs } from '../services/admin/AuditLogService';
import type { AuditAction, AuditCible, AuditLogDto, AuditLogFiltre, AuditLogExportFormat } from '../services/admin/AuditLogService';
import { getAllUsers, getUsersByUO } from '../services/admin/AdminService';
import { getMyUO } from '../services/organisation/UOService';
import { getCurrentUserInfo, hasRole } from '../auth/authService';
import '../Style/Admin/AuditLogPanel.css';

interface UtilisateurOption {
    id: string;
    nom: string;
    prenom: string;
    email: string;
}

/** Libellés FR — tenus à jour manuellement en miroir de AuditAction (backend). */
const ACTION_LABELS: Record<AuditAction, string> = {
    LOGIN_REUSSI: 'Connexion réussie',
    LOGIN_ECHOUE: 'Connexion échouée',
    LOGOUT: 'Déconnexion',
    TOKEN_RAFRAICHI: 'Token rafraîchi',
    SESSION_INVALIDEE: 'Session invalidée',
    UTILISATEUR_CREE: 'Utilisateur créé',
    UTILISATEUR_MODIFIE: 'Utilisateur modifié',
    UTILISATEUR_BLOQUE: 'Utilisateur bloqué',
    UTILISATEUR_REACTIVE: 'Utilisateur réactivé',
    PROFIL_MODIFIE: 'Profil auto-modifié',
    UO_CREEE: 'UO créée',
    UO_MODIFIEE: 'UO modifiée',
    UO_SUPPRIMEE: 'UO supprimée',
    UO_RACINE_CHANGEE: 'UO déplacée vers la racine',
    UO_MEMBRE_AJOUTE: 'Membre ajouté à une UO',
    UO_MEMBRE_RETIRE: 'Membre retiré d\'une UO',
    UO_MEMBRE_TRANSFERE: 'Membre transféré',
    DOCUMENT_UPLOAD_REUSSI: 'Document archivé',
    DOCUMENT_UPLOAD_ECHOUE: 'Échec d\'archivage',
    DOCUMENT_NOUVELLE_VERSION: 'Nouvelle version de document',
    DOCUMENT_CORRUPTION_DETECTEE: 'Corruption détectée',
    DOCUMENT_CONSULTE: 'Document consulté',
    DOCUMENT_TELECHARGE: 'Document téléchargé',
    DOCUMENT_RECHERCHE: 'Recherche effectuée',
    DOCUMENT_VERIFICATION_PUBLIQUE: 'Vérification publique',
    GROUPE_MEMBRE_AJOUTE: 'Membre ajouté au groupe',
    GROUPE_MEMBRE_RETIRE: 'Membre retiré du groupe',
    TYPE_DOCUMENT_CREE: 'Type de document créé',
    TYPE_DOCUMENT_MODIFIE: 'Type de document modifié',
    TYPE_DOCUMENT_REGEX_REINITIALISEE: 'Regex réinitialisées',
    TYPE_DOCUMENT_SUPPRIME: 'Type de document supprimé',
    PROJET_CREE: 'Projet créé',
    PROJET_TYPES_AJOUTES: 'Types ajoutés au projet',
    PROJET_SUPPRIME: 'Projet supprimé',
};

const CIBLE_LABELS: Record<AuditCible, string> = {
    SESSION: 'Session',
    UTILISATEUR: 'Utilisateur',
    UNITE_ORGANISATIONNELLE: 'UO',
    DOCUMENT: 'Document',
    GROUPE_ACCES: 'Groupe d\'accès',
    TYPE_DOCUMENT: 'Type de document',
    PROJET: 'Projet',
};

function formatHorodatage(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
}

function AuditLogPanel() {
    const [logs, setLogs] = useState<AuditLogDto[]>([]);
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [expandedId, setExpandedId] = useState<number | null>(null);

    const [utilisateurs, setUtilisateurs] = useState<UtilisateurOption[]>([]);

    // ── Filtres ──────────────────────────────────────────────────────────────
    const [acteurId, setActeurId] = useState('');
    const [action, setAction] = useState<AuditAction | ''>('');
    const [cibleType, setCibleType] = useState<AuditCible | ''>('');
    const [dateDebut, setDateDebut] = useState('');
    const [dateFin, setDateFin] = useState('');
    const [texte, setTexte] = useState('');

    // ── Chargement de la liste d'utilisateurs pour le filtre ────────────────
    useEffect(() => {
        const chargerUtilisateurs = async () => {
            try {
                if (hasRole('ADMIN')) {
                    const data = await getAllUsers();
                    setUtilisateurs(data);
                } else {
                    const monUO = await getMyUO();
                    if (monUO?.id) {
                        const data = await getUsersByUO(monUO.id);
                        setUtilisateurs(data);
                    }
                }
            } catch {
                // Non bloquant : le filtre "utilisateur" reste juste vide.
            }
        };
        chargerUtilisateurs();
    }, []);

    /** Filtres actifs, communs à la recherche paginée et à l'export (mêmes critères). */
    const buildFiltre = useCallback((): Omit<AuditLogFiltre, 'page' | 'size'> => ({
        acteurId: acteurId || undefined,
        action: (action as AuditAction) || undefined,
        cibleType: (cibleType as AuditCible) || undefined,
        dateDebut: dateDebut ? new Date(dateDebut).toISOString() : undefined,
        dateFin: dateFin ? new Date(dateFin).toISOString() : undefined,
        texte: texte.trim() || undefined,
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }), [acteurId, action, cibleType, dateDebut, dateFin, texte]);

    const charger = useCallback(async (pageDemandee: number) => {
        setLoading(true);
        setError('');
        try {
            const result = await rechercherAuditLogs({ ...buildFiltre(), page: pageDemandee, size: 25 });
            setLogs(result.content);
            setPage(result.page);
            setTotalPages(result.totalPages);
            setTotalElements(result.totalElements);
        } catch (err: any) {
            setError(err.message ?? 'Erreur lors du chargement du journal');
            setLogs([]);
        } finally {
            setLoading(false);
        }
    }, [buildFiltre]);

    useEffect(() => { charger(0); }, [charger]);

    const handleFiltrer = (e: React.FormEvent) => {
        e.preventDefault();
        charger(0);
    };

    const handleReset = () => {
        setActeurId(''); setAction(''); setCibleType('');
        setDateDebut(''); setDateFin(''); setTexte('');
    };

    const [exportLoading, setExportLoading] = useState<AuditLogExportFormat | null>(null);

    const handleExporter = async (format: AuditLogExportFormat) => {
        setExportLoading(format);
        setError('');
        try {
            await exporterAuditLogs(buildFiltre(), format);
        } catch (err: any) {
            setError(err.message ?? "Erreur lors de l'export du journal");
        } finally {
            setExportLoading(null);
        }
    };

    const userInfo = getCurrentUserInfo();

    return (
        <div className="audit-log-panel">
            <div className="audit-log-header">
                <h3>Journal d'audit</h3>
                <span className="audit-log-count">
                    {totalElements} entrée{totalElements > 1 ? 's' : ''}
                    {!hasRole('ADMIN') && userInfo?.role === 'ADMIN_UO' && ' — restreint à votre UO'}
                </span>
            </div>

            <form className="audit-log-filters" onSubmit={handleFiltrer}>
                <div className="form-field">
                    <select
                        className="form-field-input up-select"
                        value={acteurId}
                        onChange={e => setActeurId(e.target.value)}
                        aria-label="Filtrer par utilisateur"
                    >
                        <option value=""> Utilisateurs </option>
                        {utilisateurs.map(u => (
                            <option key={u.id} value={u.id}>{u.prenom} {u.nom} ({u.email})</option>
                        ))}
                    </select>
                </div>

                <div className="form-field">
                    <select
                        className="form-field-input up-select"
                        value={action}
                        onChange={e => setAction(e.target.value as AuditAction | '')}
                        aria-label="Filtrer par action"
                    >
                        <option value=""> Actions </option>
                        {(Object.keys(ACTION_LABELS) as AuditAction[]).map(a => (
                            <option key={a} value={a}>{ACTION_LABELS[a]}</option>
                        ))}
                    </select>
                </div>

                <div className="form-field">
                    <select
                        className="form-field-input up-select"
                        value={cibleType}
                        onChange={e => setCibleType(e.target.value as AuditCible | '')}
                        aria-label="Filtrer par type de cible"
                    >
                        <option value="">Cible(s)</option>
                        {(Object.keys(CIBLE_LABELS) as AuditCible[]).map(c => (
                            <option key={c} value={c}>{CIBLE_LABELS[c]}</option>
                        ))}
                    </select>
                </div>

                <div className="form-field audit-log-date-field">
                    <input
                        type={dateDebut ? 'date' : 'text'}
                        placeholder="Date début"
                        className="form-field-input"
                        value={dateDebut}
                        onChange={e => setDateDebut(e.target.value)}
                        onFocus={e => {
                            e.target.type = 'date';
                            try { e.target.showPicker?.(); } catch { /* geste utilisateur requis */ }
                        }}
                        onBlur={e => { if (!e.target.value) e.target.type = 'text'; }}
                        aria-label="Date début"
                    />
                </div>
                <div className="form-field audit-log-date-field">
                    <input
                        type={dateFin ? 'date' : 'text'}
                        placeholder="Date fin"
                        className="form-field-input"
                        value={dateFin}
                        onChange={e => setDateFin(e.target.value)}
                        onFocus={e => {
                            e.target.type = 'date';
                            try { e.target.showPicker?.(); } catch { /* geste utilisateur requis */ }
                        }}
                        onBlur={e => { if (!e.target.value) e.target.type = 'text'; }}
                        aria-label="Date fin"
                    />
                </div>

                <div className="form-field audit-log-search">
                    <input
                        type="text"
                        className="form-field-input"
                        placeholder="Rechercher dans la description..."
                        value={texte}
                        onChange={e => setTexte(e.target.value)}
                    />
                </div>

                <div className="audit-log-filter-actions">
                    <button type="submit" className="form-submit-btn" disabled={loading}>
                        <i className="fa-solid fa-magnifying-glass" /> Filtrer
                    </button>
                    <button
                        type="button"
                        className="bulk-back-btn audit-log-reset-btn"
                        onClick={handleReset}
                        title="Réinitialiser les filtres"
                        aria-label="Réinitialiser les filtres"
                    >
                        <i className="fa-solid fa-rotate-left" />
                    </button>
                </div>
            </form>

            {/* Export — porte sur exactement les filtres actifs ci-dessus */}
            <div className="audit-log-export-actions">
                <span className="audit-log-export-label">Exporter la sélection filtrée :</span>
                <button
                    type="button"
                    className="bulk-back-btn"
                    onClick={() => handleExporter('csv')}
                    disabled={exportLoading !== null}
                >
                    {exportLoading === 'csv'
                        ? <><i className="fa-solid fa-spinner fa-spin" /> Export CSV…</>
                        : <><i className="fa-solid fa-file-csv" /> CSV</>}
                </button>
                <button
                    type="button"
                    className="bulk-back-btn"
                    onClick={() => handleExporter('log')}
                    disabled={exportLoading !== null}
                >
                    {exportLoading === 'log'
                        ? <><i className="fa-solid fa-spinner fa-spin" /> Export .log…</>
                        : <><i className="fa-solid fa-file-lines" /> Fichier .log</>}
                </button>
            </div>

            {error && <div className="up-alert up-alert-error">{error}</div>}

            <div className="td-table-container">
                <table className="td-table audit-log-table">
                    <thead>
                        <tr>
                            <th>Date</th>
                            <th>Acteur</th>
                            <th>Action</th>
                            <th>Cible</th>
                            <th>Description</th>
                            <th>Résultat</th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading ? (
                            <tr><td colSpan={6} className="td-loading">
                                <i className="fa-solid fa-spinner fa-spin" /> Chargement...
                            </td></tr>
                        ) : logs.length === 0 ? (
                            <tr><td colSpan={6} className="td-empty">Aucune entrée trouvée.</td></tr>
                        ) : logs.map(log => (
                            <Fragment key={log.id}>
                                <tr
                                    className="audit-log-row"
                                    onClick={() => setExpandedId(expandedId === log.id ? null : log.id)}
                                >
                                    <td className="audit-log-date">{formatHorodatage(log.horodatage)}</td>
                                    <td>
                                        {log.acteurEmail ?? <span className="audit-log-anonyme">anonyme</span>}
                                        {log.acteurRole && <span className="audit-log-role"> · {log.acteurRole}</span>}
                                    </td>
                                    <td>{ACTION_LABELS[log.action] ?? log.action}</td>
                                    <td>{log.cibleType ? CIBLE_LABELS[log.cibleType] : '—'}</td>
                                    <td className="audit-log-description">{log.description}</td>
                                    <td>
                                        <span className={`status-tag ${log.succes ? 'active' : 'inactive'}`}>
                                            {log.succes ? 'Succès' : 'Échec'}
                                        </span>
                                    </td>
                                </tr>
                                {expandedId === log.id && (
                                    <tr className="audit-log-detail-row">
                                        <td colSpan={6}>
                                            <div className="audit-log-detail-grid">
                                                <span><strong>IP :</strong> {log.adresseIp ?? '—'}</span>
                                                <span><strong>Cible ID :</strong> {log.cibleId ?? '—'}</span>
                                                <span><strong>UO :</strong> {log.uoId ?? '—'}</span>
                                                {log.details && (
                                                    <pre className="audit-log-details-json">{
                                                        (() => {
                                                            try { return JSON.stringify(JSON.parse(log.details), null, 2); }
                                                            catch { return log.details; }
                                                        })()
                                                    }</pre>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                )}
                            </Fragment>
                        ))}
                    </tbody>
                </table>
            </div>

            {totalPages > 1 && (
                <div className="pagination">
                    <button
                        className="pagination-btn pagination-nav"
                        onClick={() => charger(page - 1)}
                        disabled={page === 0 || loading}
                    >‹</button>
                    <span className="pagination-btn pagination-active">
                        {page + 1} / {totalPages}
                    </span>
                    <button
                        className="pagination-btn pagination-nav"
                        onClick={() => charger(page + 1)}
                        disabled={page + 1 >= totalPages || loading}
                    >›</button>
                </div>
            )}
        </div>
    );
}

export default AuditLogPanel;
