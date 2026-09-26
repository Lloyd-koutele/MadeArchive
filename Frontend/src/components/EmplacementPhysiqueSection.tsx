import { useState } from 'react';
import { modifierEmplacementPhysique } from '../services/document/DocumentService';
import type { DocumentDetailDto } from '../services/document/DocumentService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/Editor/Editor.css';

/**
 * Réassigne l'emplacement physique d'un document déjà archivé — partagé
 * entre document/DocumentsAccessible.tsx et Editor/MesDocumentsEditor.tsx,
 * qui en avaient chacun une copie quasi identique (dérivées l'une de l'autre
 * au fil du temps, l'une avait fini par perdre le toast de succès que
 * l'autre gardait — symptôme classique de deux copies qui divergent
 * silencieusement).
 */
function EmplacementPhysiqueSection({
    detail,
    onUpdated,
}: {
    detail: DocumentDetailDto;
    onUpdated?: (updated: DocumentDetailDto) => void;
}) {
    const notify = useNotify();
    const [editing, setEditing] = useState(false);
    const [options, setOptions] = useState<PhysicalLocationDto[]>([]);
    const [optionsLoading, setOptionsLoading] = useState(false);
    const [selected, setSelected] = useState('');
    const [saving, setSaving] = useState(false);

    const ouvrirEdition = async () => {
        setEditing(true);
        setSelected(detail.physicalLocationId ?? '');
        if (detail.uniteOrganisationnelleId == null) return;
        setOptionsLoading(true);
        try {
            const data = await getEmplacementsDisponibles(detail.uniteOrganisationnelleId);
            setOptions(data);
        } catch {
            notify.error('Impossible de charger les emplacements disponibles');
        } finally {
            setOptionsLoading(false);
        }
    };

    const enregistrer = async () => {
        setSaving(true);
        try {
            const updated = await modifierEmplacementPhysique(detail.documentId, selected || null);
            onUpdated?.(updated);
            setEditing(false);
            notify.success('Emplacement physique mis à jour');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    if (!detail.peutModifierEmplacement && !detail.physicalLocationPath) {
        return null;
    }

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Emplacement physique :</strong>
            {editing ? (
                <div className="emplacement-edit">
                    {optionsLoading ? (
                        <i className="fa-solid fa-spinner fa-spin" />
                    ) : (
                        <select value={selected} onChange={(e) => setSelected(e.target.value)}>
                            <option value="">— Aucun —</option>
                            {options.map((o) => (
                                <option key={o.id} value={o.id}>{o.cheminComplet}</option>
                            ))}
                        </select>
                    )}
                    <button type="button" className="attestation-generer-btn" disabled={saving} onClick={enregistrer}>
                        {saving ? '…' : 'Enregistrer'}
                    </button>
                    <button type="button" className="details-close-btn" onClick={() => setEditing(false)}>Annuler</button>
                </div>
            ) : (
                <>
                    <span>{detail.physicalLocationPath ?? '—'}</span>
                    {detail.peutModifierEmplacement && (
                        <button type="button" className="details-close-btn" onClick={ouvrirEdition}>
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                </>
            )}
        </div>
    );
}

export default EmplacementPhysiqueSection;
