import { useState, useEffect } from 'react';
import { modifierEmplacementPhysique } from '../services/document/DocumentService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import Modal from '../Page/Modal';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/Editor/Editor.css';

/** Valeur de sélection distincte de "" (placeholder non choisi) pour un
 *  détachement volontaire — évite qu'ouvrir la modale puis cliquer tout de
 *  suite sur "Enregistrer" ne détache silencieusement tout le lot. */
const AUCUN = '__AUCUN__';

interface EmplacementPhysiqueModalProps {
    isOpen: boolean;
    onClose: () => void;
    uoId: number;
    /** Ids déjà filtrés par l'appelant sur peutModifierEmplacement (même
     *  prédicat serveur que peutGererCorbeille, voir DocumentService côté
     *  backend). */
    documentIds: string[];
    onSuccess: () => void;
}

/**
 * Réassigne en lot l'emplacement physique des documents sélectionnés —
 * ouvert depuis le menu contextuel (Cmd/Ctrl+clic + clic droit, voir
 * DocumentsAccessible.tsx/MesDocumentsEditor.tsx). Reprend la logique qui
 * vivait auparavant dans EmplacementPhysiqueSection avant son passage en
 * lecture seule.
 */
function EmplacementPhysiqueModal({ isOpen, onClose, uoId, documentIds, onSuccess }: EmplacementPhysiqueModalProps) {
    const notify = useNotify();
    const [options, setOptions] = useState<PhysicalLocationDto[]>([]);
    const [optionsLoading, setOptionsLoading] = useState(false);
    const [selected, setSelected] = useState('');
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!isOpen) return;
        setSelected('');
        setOptionsLoading(true);
        getEmplacementsDisponibles(uoId)
            .then(setOptions)
            .catch(() => notify.error('Impossible de charger les emplacements disponibles'))
            .finally(() => setOptionsLoading(false));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isOpen, uoId]);

    const fermer = () => {
        if (saving) return;
        onClose();
    };

    const enregistrer = async () => {
        setSaving(true);
        const cible = selected === AUCUN ? null : selected;
        const resultats = await Promise.allSettled(
            documentIds.map(id => modifierEmplacementPhysique(id, cible))
        );
        const succes = resultats.filter(r => r.status === 'fulfilled').length;
        const echecs = resultats.length - succes;

        setSaving(false);
        onClose();
        onSuccess();

        if (succes > 0) {
            notify.success(`Emplacement mis à jour pour ${succes} document${succes > 1 ? 's' : ''}`);
        }
        if (echecs > 0) {
            notify.error(`${echecs} échec${echecs > 1 ? 's' : ''} sur ${documentIds.length}`);
        }
    };

    return (
        <Modal
            isOpen={isOpen}
            onClose={fermer}
            title={documentIds.length > 1
                ? `Changer l'emplacement physique de ${documentIds.length} documents`
                : "Changer l'emplacement physique"}
        >
            <div className="emplacement-edit">
                {optionsLoading ? (
                    <i className="fa-solid fa-spinner fa-spin" />
                ) : (
                    <select value={selected} onChange={e => setSelected(e.target.value)}>
                        <option value="" disabled>— Choisir un emplacement —</option>
                        <option value={AUCUN}>Aucun (détacher)</option>
                        {options.map(o => (
                            <option key={o.id} value={o.id}>{o.cheminComplet}</option>
                        ))}
                    </select>
                )}
                <button type="button" className="attestation-generer-btn" disabled={saving || !selected} onClick={enregistrer}>
                    {saving ? '…' : 'Enregistrer'}
                </button>
                <button type="button" className="details-close-btn" onClick={fermer} disabled={saving}>Annuler</button>
            </div>
        </Modal>
    );
}

export default EmplacementPhysiqueModal;
