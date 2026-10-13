import { useEffect, useMemo, useState } from 'react';
import Modal from '../Page/Modal';
import DeplacerDossierModal from './DeplacerDossierModal';
import { getDocumentDetail, reclasserDocument } from '../services/document/DocumentService';
import type { DocumentDetailDto, DocumentListItemDto, ReclassementRequest } from '../services/document/DocumentService';
import { getTypeDocumentsByUOEditor } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getPlanClassement, aplatirPlanClassement } from '../services/organisation/PlanClassementService';
import type { PlanClassementOption } from '../services/organisation/PlanClassementService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/components/ReclasserSection.css';

const AUCUN = '__AUCUN__';

/** Même idée que NormalisationNoms côté serveur : casse, accents et espaces ignorés pour rapprocher deux libellés de champ. */
const normaliser = (t: string) =>
    t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\s+/g, ' ').trim();

type Action = 'type' | 'activite' | 'dossier' | 'emplacement';

interface Props {
    detail: DocumentDetailDto;
    /** Appelé avec le détail mis à jour une fois le reclassement enregistré. */
    onUpdated?: (updated: DocumentDetailDto) => void;
}

/**
 * Reclasser un document archivé par erreur : quatre actions indépendantes — changer le type de document (avec ses
 * métadonnées), l'activité, le dossier, l'emplacement physique — chacune dans SON modal. Le fichier, la signature et
 * l'horodatage ne changent pas ; tout est tracé dans le journal du document. Réservé à l'éditeur ayant accès au
 * document (voir DocumentService.reclasser côté serveur).
 */
function ReclasserSection({ detail, onUpdated }: Props) {
    const notify = useNotify();
    const [action, setAction] = useState<Action | null>(null);
    const [saving, setSaving] = useState(false);

    const uoId = detail.uniteOrganisationnelleId;
    const peutReclasser = detail.peutGererCorbeille && detail.status !== 'CORBEILLE' && detail.status !== 'DELETED' && uoId != null;

    const fermer = () => { if (!saving) setAction(null); };

    /** Envoie UNE action de reclassement, puis ferme le modal et remonte le détail mis à jour. */
    const reclasser = async (requete: ReclassementRequest) => {
        setSaving(true);
        try {
            const maj = await reclasserDocument(detail.documentId, requete);
            notify.success('Document reclassé');
            setAction(null);
            onUpdated?.(maj);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du reclassement');
        } finally {
            setSaving(false);
        }
    };

    if (!peutReclasser || uoId == null) return null;

    // Le modal « Déplacer » attend un élément de liste : on en fabrique un à partir du détail.
    const documentListe: DocumentListItemDto = {
        documentId: detail.documentId, titre: detail.titre, typeDocumentId: detail.typeDocumentId,
        typeDocumentNom: detail.typeDocumentNom, status: detail.status, access: detail.access,
        retentionUntil: detail.retentionUntil, createAt: detail.createAt,
    };

    return (
        <div className="reclasser-section">
            <div className="reclasser-actions">
                <button type="button" className="reclasser-btn" onClick={() => setAction('type')}>
                    <i className="fa-solid fa-file-pen" /> Changer le type de document
                </button>
                <button type="button" className="reclasser-btn" onClick={() => setAction('activite')}>
                    <i className="fa-solid fa-sitemap" /> Changer d'activité
                </button>
                <button type="button" className="reclasser-btn" onClick={() => setAction('dossier')}>
                    <i className="fa-solid fa-folder-tree" /> Changer de dossier
                </button>
                <button type="button" className="reclasser-btn" onClick={() => setAction('emplacement')}>
                    <i className="fa-solid fa-box-archive" /> Changer d'emplacement
                </button>
            </div>

            {action === 'type' && (
                <ChangerTypeModal detail={detail} uoId={uoId} saving={saving} onClose={fermer} onSubmit={reclasser} />
            )}
            {action === 'activite' && (
                <ChangerActiviteModal detail={detail} uoId={uoId} saving={saving} onClose={fermer} onSubmit={reclasser} />
            )}
            {action === 'emplacement' && (
                <ChangerEmplacementModal detail={detail} uoId={uoId} saving={saving} onClose={fermer} onSubmit={reclasser} />
            )}

            {/* Dossier : même modal « Déplacer » que partout (avec la confirmation de fusion de groupes si nécessaire). */}
            <DeplacerDossierModal
                isOpen={action === 'dossier'}
                onClose={() => setAction(null)}
                uoId={uoId}
                documents={[documentListe]}
                onSuccess={async () => {
                    try {
                        onUpdated?.(await getDocumentDetail(detail.documentId));
                    } catch (err: any) {
                        notify.error(err.message ?? 'Impossible de rafraîchir le document');
                    }
                }}
            />
        </div>
    );
}

interface ModalProps {
    detail: DocumentDetailDto;
    uoId: number;
    saving: boolean;
    onClose: () => void;
    onSubmit: (requete: ReclassementRequest) => void;
}

// ── Changer le type de document (avec les métadonnées du nouveau type) ───────────────────────────────────────────
function ChangerTypeModal({ detail, uoId, saving, onClose, onSubmit }: ModalProps) {
    const notify = useNotify();
    const [types, setTypes] = useState<TypeDocumentDto[]>([]);
    const [typeId, setTypeId] = useState<number>(detail.typeDocumentId);
    const [valeurs, setValeurs] = useState<Record<string, string>>({});

    const typeChoisi = types.find(t => t.id === typeId);
    const typeChange = typeId !== detail.typeDocumentId;

    // Valeurs actuelles, par libellé normalisé
    const valeursActuelles = useMemo(() => {
        const m = new Map<string, { nom: string; valeur: string }>();
        detail.metaData.forEach(md => {
            if (md.typeValeur && md.valeur) m.set(normaliser(md.typeValeur), { nom: md.typeValeur, valeur: md.valeur });
        });
        return m;
    }, [detail.metaData]);

    useEffect(() => {
        getTypeDocumentsByUOEditor(uoId).then(setTypes)
            .catch(() => notify.error('Impossible de charger les types de documents'));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Nouveau type : champs repris par nom depuis les valeurs actuelles
    useEffect(() => {
        if (!typeChoisi) return;
        const init: Record<string, string> = {};
        typeChoisi.metaData.forEach(def => {
            init[def.nom] = valeursActuelles.get(normaliser(def.nom))?.valeur ?? '';
        });
        setValeurs(init);
    }, [typeChoisi, valeursActuelles]);

    const nonReprises = typeChange && typeChoisi
        ? Array.from(valeursActuelles.entries())
            .filter(([cle]) => !typeChoisi.metaData.some(d => normaliser(d.nom) === cle))
            .map(([, v]) => `${v.nom} : ${v.valeur}`)
        : [];

    const nouvelleEcheance = typeChange && typeChoisi && detail.createAt
        ? (typeChoisi.retentionYears
            ? (() => { const d = new Date(detail.createAt!); d.setFullYear(d.getFullYear() + typeChoisi.retentionYears!); return d.toLocaleDateString('fr-FR'); })()
            : 'illimitée')
        : null;

    return (
        <Modal isOpen onClose={onClose} title="Changer le type de document" size="medium">
            <form className="reclasser-form" onSubmit={e => {
                e.preventDefault();
                onSubmit({ typeDocumentId: typeId, metaData: Object.entries(valeurs).map(([nom, valeur]) => ({ nom, valeur })) });
            }}>
                <div className="form-field">
                    <label className="form-field-label" htmlFor="reclasser-type">Type de document</label>
                    <select id="reclasser-type" className="form-field-input up-select" value={typeId}
                        onChange={e => setTypeId(Number(e.target.value))}>
                        {types.map(t => <option key={t.id} value={t.id}>{t.nom}</option>)}
                    </select>
                </div>

                {typeChange && typeChoisi && (
                    <>
                        {typeChoisi.metaData.map(def => (
                            <div className="form-field" key={def.nom}>
                                <label className="form-field-label" htmlFor={`reclasser-meta-${def.nom}`}>
                                    {def.nom}{def.obligatoire ? ' *' : ''}
                                </label>
                                <input id={`reclasser-meta-${def.nom}`} className="form-field-input" value={valeurs[def.nom] ?? ''}
                                    required={!!def.obligatoire}
                                    onChange={e => setValeurs(v => ({ ...v, [def.nom]: e.target.value }))} />
                            </div>
                        ))}
                        {nonReprises.length > 0 && (
                            <p className="reclasser-note">
                                Valeurs de l'ancien type sans champ équivalent (retirées de la fiche, conservées dans le journal) :{' '}
                                {nonReprises.join(' ; ')}
                            </p>
                        )}
                        <p className="reclasser-note">
                            Nouvelle échéance de conservation : <strong>{nouvelleEcheance}</strong> (depuis la date d'archivage).
                        </p>
                    </>
                )}

                <button type="submit" className="form-submit-btn" disabled={saving || !typeChange}>
                    {saving ? 'Enregistrement…' : 'Changer le type'}
                </button>
            </form>
        </Modal>
    );
}

// ── Changer d'activité ───────────────────────────────────────────────────────────────────────────────────────────
function ChangerActiviteModal({ detail, uoId, saving, onClose, onSubmit }: ModalProps) {
    const [activites, setActivites] = useState<PlanClassementOption[]>([]);
    const [chargement, setChargement] = useState(true);
    // '' = suit l'activité par défaut du type
    const [activiteId, setActiviteId] = useState(detail.activiteSurDocument && detail.activiteNoeudId != null ? String(detail.activiteNoeudId) : '');

    useEffect(() => {
        getPlanClassement(uoId).then(a => setActivites(aplatirPlanClassement(a))).catch(() => setActivites([]))
            .finally(() => setChargement(false));
    }, [uoId]);

    return (
        <Modal isOpen onClose={onClose} title="Changer d'activité" size="medium">
            <form className="reclasser-form" onSubmit={e => {
                e.preventDefault();
                onSubmit({ modifierActivite: true, planClassementNoeudId: activiteId ? Number(activiteId) : null });
            }}>
                {detail.activite && (
                    <p className="reclasser-note">
                        Activité actuelle : {detail.activite}{detail.activiteSurDocument ? '' : ' (celle de son type)'}
                    </p>
                )}
                {chargement ? (
                    <p className="reclasser-note">Chargement…</p>
                ) : activites.length === 0 ? (
                    <p className="reclasser-note">Aucune activité n'est définie dans cette unité.</p>
                ) : (
                    <div className="form-field">
                        <select className="form-field-input up-select" value={activiteId} aria-label="Activité"
                            onChange={e => setActiviteId(e.target.value)}>
                            <option value="">Suivre l'activité par défaut du type</option>
                            {activites.map(a => <option key={a.id} value={a.id}>{a.label}</option>)}
                        </select>
                    </div>
                )}
                <button type="submit" className="form-submit-btn" disabled={saving || chargement || activites.length === 0}>
                    {saving ? 'Enregistrement…' : 'Changer d\'activité'}
                </button>
            </form>
        </Modal>
    );
}

// ── Changer d'emplacement physique ───────────────────────────────────────────────────────────────────────────────
function ChangerEmplacementModal({ detail, uoId, saving, onClose, onSubmit }: ModalProps) {
    const notify = useNotify();
    const [emplacements, setEmplacements] = useState<PhysicalLocationDto[]>([]);
    const [emplacement, setEmplacement] = useState('');

    useEffect(() => {
        getEmplacementsDisponibles(uoId, detail.typeDocumentId, detail.dossierId)
            .then(setEmplacements)
            .catch(() => notify.error('Impossible de charger les emplacements disponibles'));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <Modal isOpen onClose={onClose} title="Changer d'emplacement physique" size="medium">
            <form className="reclasser-form" onSubmit={e => {
                e.preventDefault();
                onSubmit({ modifierEmplacement: true, physicalLocationId: emplacement && emplacement !== AUCUN ? emplacement : null });
            }}>
                <p className="reclasser-note">
                    Emplacement actuel : {detail.physicalLocationPath ?? 'aucun'}
                </p>
                <div className="form-field">
                    <select className="form-field-input up-select" value={emplacement}
                        aria-label="Emplacement physique" onChange={e => setEmplacement(e.target.value)}>
                        <option value="" disabled>— Choisir un emplacement —</option>
                        <option value={AUCUN}>Aucun (détacher)</option>
                        {emplacements.map(o => <option key={o.id} value={o.id}>{o.cheminComplet}</option>)}
                    </select>
                </div>
                <button type="submit" className="form-submit-btn" disabled={saving || !emplacement}>
                    {saving ? 'Enregistrement…' : 'Changer d\'emplacement'}
                </button>
            </form>
        </Modal>
    );
}

export default ReclasserSection;
