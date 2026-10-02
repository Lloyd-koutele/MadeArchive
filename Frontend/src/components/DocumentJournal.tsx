import { useCallback, useEffect, useState } from 'react';
import { getJournalDocument, exporterJournalDocument } from '../services/document/DocumentService';
import type { DocumentJournalDto } from '../services/document/DocumentService';
import { libelleAction } from '../audit/auditLabels';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/components/DocumentJournal.css';

function formatHorodatage(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
}

/**
 * Journal de cycle de vie d'un document : tout ce qu'il a subi dans le système (dépôt, consultations,
 * téléchargements, attestations, changements d'accès/dossier/emplacement, corbeille...). Voir
 * DocumentJournalService côté serveur — c'est le journal d'audit filtré sur ce document. Chargé
 * seulement à l'ouverture de la section.
 */
function DocumentJournal({ documentId }: { documentId: string }) {
    const notify = useNotify();
    const [ouvert, setOuvert] = useState(false);
    const [journal, setJournal] = useState<DocumentJournalDto | null>(null);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(false);
    const [exportEnCours, setExportEnCours] = useState<'csv' | 'log' | null>(null);

    const charger = useCallback(async (p: number) => {
        setLoading(true);
        try {
            const res = await getJournalDocument(documentId, p, 10);
            setJournal(res);
            setPage(res.page);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du chargement du journal');
        } finally {
            setLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [documentId]);

    useEffect(() => { setOuvert(false); setJournal(null); setPage(0); }, [documentId]);
    useEffect(() => { if (ouvert) charger(0); }, [ouvert, charger]);

    const exporter = async (format: 'csv' | 'log') => {
        setExportEnCours(format);
        try {
            await exporterJournalDocument(documentId, format);
            if (ouvert) charger(page); // l'export est lui-même journalisé
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'export du journal");
        } finally {
            setExportEnCours(null);
        }
    };

    return (
        <div className="doc-journal">
            <button type="button" className="doc-journal-toggle" onClick={() => setOuvert(o => !o)} aria-expanded={ouvert}>
                <i className={`fa-solid ${ouvert ? 'fa-chevron-down' : 'fa-chevron-right'}`} /> Journal du document
            </button>

            {ouvert && (
                <div className="doc-journal-body">
                    <div className="doc-journal-actions">
                        <button type="button" className="bulk-back-btn" onClick={() => exporter('csv')} disabled={exportEnCours !== null}>
                            {exportEnCours === 'csv' ? <i className="fa-solid fa-spinner fa-spin" /> : <i className="fa-solid fa-file-csv" />} CSV
                        </button>
                        <button type="button" className="bulk-back-btn" onClick={() => exporter('log')} disabled={exportEnCours !== null}>
                            {exportEnCours === 'log' ? <i className="fa-solid fa-spinner fa-spin" /> : <i className="fa-solid fa-file-lines" />} Fichier .log
                        </button>
                    </div>

                    {journal?.dernierControleLe && (
                        <p className="doc-journal-controle">
                            Dernier contrôle d'intégrité : {formatHorodatage(journal.dernierControleLe)} —{' '}
                            <strong>{journal.dernierControleResultat === 'OK' ? 'conforme' : journal.dernierControleResultat}</strong>{' '}
                            <span className="doc-journal-note">(seuls les échecs sont listés ci-dessous)</span>
                        </p>
                    )}

                    {loading && !journal ? (
                        <p><i className="fa-solid fa-spinner fa-spin" /> Chargement...</p>
                    ) : journal && journal.content.length === 0 ? (
                        <p className="doc-journal-vide">Aucun événement enregistré.</p>
                    ) : (
                        <ul className="doc-journal-liste">
                            {journal?.content.map(e => (
                                <li key={e.id} className={e.succes ? '' : 'doc-journal-echec'}>
                                    <span className="doc-journal-date">{formatHorodatage(e.horodatage)}</span>
                                    <span className="doc-journal-action">{libelleAction(e.action)}{!e.succes && ' (échec)'}</span>
                                    <span className="doc-journal-acteur">
                                        {e.acteurEmail ?? 'système / anonyme'}
                                        {e.acteurRole ? ` · ${e.acteurRole}` : ''}
                                        {e.adresseIp ? ` · IP ${e.adresseIp}` : ''}
                                    </span>
                                    <span className="doc-journal-desc">{e.description}</span>
                                </li>
                            ))}
                        </ul>
                    )}

                    {journal && journal.totalPages > 1 && (
                        <div className="pagination">
                            <button className="pagination-btn pagination-nav" onClick={() => charger(page - 1)} disabled={page === 0 || loading}>‹</button>
                            <span className="pagination-btn pagination-active">{page + 1} / {journal.totalPages}</span>
                            <button className="pagination-btn pagination-nav" onClick={() => charger(page + 1)} disabled={page + 1 >= journal.totalPages || loading}>›</button>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

export default DocumentJournal;
