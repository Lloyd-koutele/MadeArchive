import { useEffect, useMemo, useState } from 'react';
import Modal from '../Page/Modal';
import DossierTreePicker from '../organisation/DossierTreePicker';
import { reclasserDocument } from '../services/document/DocumentService';
import type { DocumentDetailDto } from '../services/document/DocumentService';
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

interface Props {
    detail: DocumentDetailDto;
    /** Appelé avec le détail mis à jour une fois le reclassement enregistré. */
    onUpdated?: (updated: DocumentDetailDto) => void;
}

/**
 * "Reclasser ce document" — corrige un document archivé par erreur dans le mauvais type : nouveau type (avec
 * les métadonnées de ce type, préremplies depuis les valeurs actuelles quand le champ porte le même nom),
 * et si besoin nouveau dossier / emplacement, en UNE opération. Le fichier, la signature et l'horodatage ne
 * changent pas ; l'échéance de conservation est recalculée avec la règle du nouveau type ; tout est tracé
 * dans le journal du document, valeurs de l'ancien type comprises. Réservé à l'éditeur ayant accès au
 * document (voir DocumentService.reclasser côté serveur).
 */
function ReclasserSection({ detail, onUpdated }: Props) {
    const notify = useNotify();
    const [ouvert, setOuvert] = useState(false);
    const [types, setTypes] = useState<TypeDocumentDto[]>([]);
    const [typeId, setTypeId] = useState<number>(detail.typeDocumentId);
    const [valeurs, setValeurs] = useState<Record<string, string>>({});
    const [changerDossier, setChangerDossier] = useState(false);
    const [dossierId, setDossierId] = useState<number | null>(detail.dossierId);
    const [fusionner, setFusionner] = useState(false);
    const [changerEmplacement, setChangerEmplacement] = useState(false);
    const [emplacement, setEmplacement] = useState('');
    const [emplacements, setEmplacements] = useState<PhysicalLocationDto[]>([]);
    const [changerActivite, setChangerActivite] = useState(false);
    const [activiteId, setActiviteId] = useState('');          // '' = suit l'activité par défaut du type
    const [activites, setActivites] = useState<PlanClassementOption[]>([]);
    const [saving, setSaving] = useState(false);

    const uoId = detail.uniteOrganisationnelleId;
    const peutReclasser = detail.peutGererCorbeille && detail.status !== 'CORBEILLE' && detail.status !== 'DELETED' && uoId != null;
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
        if (!ouvert || uoId == null) return;
        setTypeId(detail.typeDocumentId);
        setChangerDossier(false); setDossierId(detail.dossierId); setFusionner(false);
        setChangerEmplacement(false); setEmplacement('');
        setChangerActivite(false); setActiviteId(detail.activiteSurDocument && detail.activiteNoeudId != null ? String(detail.activiteNoeudId) : '');
        getPlanClassement(uoId).then(a => setActivites(aplatirPlanClassement(a))).catch(() => setActivites([]));
        getTypeDocumentsByUOEditor(uoId).then(setTypes)
            .catch(() => notify.error('Impossible de charger les types de documents'));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [ouvert]);

    // Nouveau type : champs repris par nom depuis les valeurs actuelles
    useEffect(() => {
        if (!typeChoisi) return;
        const init: Record<string, string> = {};
        typeChoisi.metaData.forEach(def => {
            init[def.nom] = valeursActuelles.get(normaliser(def.nom))?.valeur ?? '';
        });
        setValeurs(init);
    }, [typeChoisi, valeursActuelles]);

    // Emplacements compatibles avec le (nouveau) type et le (nouveau) dossier
    useEffect(() => {
        if (!ouvert || !changerEmplacement || uoId == null) return;
        getEmplacementsDisponibles(uoId, typeId, changerDossier ? dossierId : detail.dossierId)
            .then(setEmplacements)
            .catch(() => notify.error('Impossible de charger les emplacements disponibles'));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [ouvert, changerEmplacement, typeId, dossierId, changerDossier]);

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

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setSaving(true);
        try {
            const maj = await reclasserDocument(detail.documentId, {
                typeDocumentId: typeChange ? typeId : undefined,
                metaData: typeChange ? Object.entries(valeurs).map(([nom, valeur]) => ({ nom, valeur })) : undefined,
                modifierActivite: changerActivite,
                planClassementNoeudId: changerActivite && activiteId ? Number(activiteId) : null,
                modifierDossier: changerDossier,
                dossierId: changerDossier ? dossierId : undefined,
                fusionnerGroupes: changerDossier ? fusionner : undefined,
                modifierEmplacement: changerEmplacement,
                physicalLocationId: changerEmplacement && emplacement && emplacement !== AUCUN ? emplacement : null,
            });
            notify.success('Document reclassé');
            setOuvert(false);
            onUpdated?.(maj);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du reclassement');
        } finally {
            setSaving(false);
        }
    };

    if (!peutReclasser) return null;

    const rienADemander = !typeChange && !changerActivite && !changerDossier && !changerEmplacement;

    return (
        <div className="reclasser-section">
            <button type="button" className="reclasser-btn" onClick={() => setOuvert(true)}>
                <i className="fa-solid fa-right-left" /> Reclasser ce document
            </button>

            <Modal isOpen={ouvert} onClose={() => setOuvert(false)} title="Reclasser le document" size="medium">
                <form className="reclasser-form" onSubmit={handleSubmit}>

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

                    {activites.length > 0 && (
                        <>
                            <label className="reclasser-check">
                                <input type="checkbox" checked={changerActivite} onChange={e => setChangerActivite(e.target.checked)} />
                                Changer l'activité du document
                                {detail.activite && <span className="reclasser-note"> (actuelle : {detail.activite}{detail.activiteSurDocument ? '' : ', celle de son type'})</span>}
                            </label>
                            {changerActivite && (
                                <div className="form-field">
                                    <select className="form-field-input up-select" value={activiteId} aria-label="Activité"
                                        onChange={e => setActiviteId(e.target.value)}>
                                        <option value="">Suivre l'activité par défaut du type{typeChoisi?.activite ? ` (${typeChoisi.activite})` : ''}</option>
                                        {activites.map(a => <option key={a.id} value={a.id}>{a.label}</option>)}
                                    </select>
                                </div>
                            )}
                        </>
                    )}

                    <label className="reclasser-check">
                        <input type="checkbox" checked={changerDossier} onChange={e => setChangerDossier(e.target.checked)} />
                        Changer de dossier
                    </label>
                    {changerDossier && uoId != null && (
                        <div className="form-field">
                            <DossierTreePicker uoId={uoId} value={dossierId} onChange={setDossierId} />
                            <label className="reclasser-check">
                                <input type="checkbox" checked={fusionner} onChange={e => setFusionner(e.target.checked)} />
                                Fusionner les groupes d'accès si le dossier est privé
                            </label>
                        </div>
                    )}

                    <label className="reclasser-check">
                        <input type="checkbox" checked={changerEmplacement} onChange={e => setChangerEmplacement(e.target.checked)} />
                        Changer l'emplacement physique
                    </label>
                    {changerEmplacement && (
                        <div className="form-field">
                            <select className="form-field-input up-select" value={emplacement}
                                aria-label="Emplacement physique" onChange={e => setEmplacement(e.target.value)}>
                                <option value="" disabled>— Choisir un emplacement —</option>
                                <option value={AUCUN}>Aucun (détacher)</option>
                                {emplacements.map(o => <option key={o.id} value={o.id}>{o.cheminComplet}</option>)}
                            </select>
                        </div>
                    )}

                    <button type="submit" className="form-submit-btn"
                        disabled={saving || rienADemander || (changerEmplacement && !emplacement)}>
                        {saving ? 'Reclassement…' : 'Reclasser'}
                    </button>
                </form>
            </Modal>
        </div>
    );
}

export default ReclasserSection;
