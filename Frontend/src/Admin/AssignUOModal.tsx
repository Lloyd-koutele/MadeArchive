// Admin/AssignUOModal.tsx — mode assign|transfer
import { useState, useEffect } from 'react';
import Modal from "../Page/Modal";
import UOTreeSelect from "../organisation/UOTreeSelect";
import { getAllUOs, ajouterMembreUO, transfererMembreUO } from "../services/organisation/UOService";
import { useNotify } from '../notifications/NotificationProvider';

interface UONode {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

interface AssignUOModalProps {
    isOpen: boolean;
    userId: string | null;
    mode: 'assign' | 'transfer';
    onClose: () => void;
    onAssigned: () => void;
}

function AssignUOModal({ isOpen, userId, mode, onClose, onAssigned }: AssignUOModalProps) {
    const notify = useNotify();
    const [uos, setUos] = useState<UONode[]>([]);
    const [selectedUO, setSelectedUO] = useState<number | null>(null);
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (isOpen) {
            setSelectedUO(null);
            getAllUOs().then(setUos).catch(() => notify.error("Erreur lors du chargement des UO"));
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isOpen]);

    const handleSubmit = async () => {
        if (!userId || selectedUO === null) {
            notify.error("Sélectionnez une unité organisationnelle");
            return;
        }
        setSubmitting(true);
        try {
            if (mode === 'transfer') {
                await transfererMembreUO(selectedUO, userId);
            } else {
                await ajouterMembreUO(selectedUO, userId);
            }
            notify.success(mode === 'transfer' ? 'Utilisateur transféré avec succès' : 'Utilisateur affecté avec succès');
            onAssigned();
        } catch (err: any) {
            notify.error(err.message || "Erreur lors de l'opération");
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={mode === 'transfer' ? "Transférer vers une autre UO" : "Affecter à une unité organisationnelle"}>
            <UOTreeSelect nodes={uos} value={selectedUO} onChange={setSelectedUO} />
            <button
                type="button"
                className="form-submit-btn"
                onClick={handleSubmit}
                disabled={submitting || selectedUO === null}
                style={{ marginTop: '1rem' }}
            >
                {mode === 'transfer' ? 'Transférer' : 'Affecter'}
            </button>
        </Modal>
    );
}

export default AssignUOModal;