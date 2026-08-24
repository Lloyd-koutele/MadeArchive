// Admin/AssignUOModal.tsx — mode assign|transfer
import { useState, useEffect } from 'react';
import Modal from "../Page/Modal";
import UOTreeSelect from "../organisation/UOTreeSelect";
import { getAllUOs, ajouterMembreUO, transfererMembreUO } from "../services/organisation/UOService";

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
    const [uos, setUos] = useState<UONode[]>([]);
    const [selectedUO, setSelectedUO] = useState<number | null>(null);
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (isOpen) {
            setSelectedUO(null);
            setError('');
            getAllUOs().then(setUos).catch(() => setError("Erreur lors du chargement des UO"));
        }
    }, [isOpen]);

    const handleSubmit = async () => {
        if (!userId || selectedUO === null) {
            setError("Sélectionnez une unité organisationnelle");
            return;
        }
        setSubmitting(true);
        setError('');
        try {
            if (mode === 'transfer') {
                await transfererMembreUO(selectedUO, userId);
            } else {
                await ajouterMembreUO(selectedUO, userId);
            }
            onAssigned();
        } catch (err: any) {
            setError(err.message || "Erreur lors de l'opération");
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={mode === 'transfer' ? "Transférer vers une autre UO" : "Affecter à une unité organisationnelle"}>
            {error && <div className="form-error">{error}</div>}
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