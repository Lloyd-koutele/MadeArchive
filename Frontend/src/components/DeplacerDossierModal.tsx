import { useState } from 'react';
import { modifierDossierDocument, verifierFusionGroupeDossier } from '../services/document/DocumentService';
import type { DocumentListItemDto } from '../services/document/DocumentService';
import DossierTreePicker from '../organisation/DossierTreePicker';
import Modal from '../Page/Modal';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';

interface DeplacerDossierModalProps {
    isOpen: boolean;
    onClose: () => void;
    uoId: number;
    /** Documents ciblés — déjà filtrés par l'appelant sur peutModifierDossier
     *  (même prédicat serveur que peutGererCorbeille, voir DocumentService
     *  côté backend : estEditeur(user) && accès au document). */
    documents: DocumentListItemDto[];
    onSuccess: () => void;
}

/**
 * Déplace en lot les documents sélectionnés vers un dossier — ouvert depuis
 * le menu contextuel (Cmd/Ctrl+clic + clic droit, voir
 * DocumentsAccessible.tsx/MesDocumentsEditor.tsx). Reprend la logique de
 * vérification de fusion de groupe (documents privés) qui vivait auparavant
 * dans DossierAttachSection avant son passage en lecture seule — appliquée
 * ici DOCUMENT PAR DOCUMENT puisqu'un lot peut mélanger des documents publics
 * et privés, chacun avec son propre groupe.
 *
 * Un seul bouton "Déplacer ici", désactivé tant qu'aucun dossier n'est
 * choisi — le détachement (retirer un document de son dossier sans le
 * déplacer ailleurs) a été retiré de ce modal à la demande explicite de
 * l'utilisateur (10/2026) : plus aucun écran de l'app n'offre cette action.
 */
function DeplacerDossierModal({ isOpen, onClose, uoId, documents, onSuccess }: DeplacerDossierModalProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [dossierId, setDossierId] = useState<number | null>(null);
    const [saving, setSaving] = useState(false);

    const fermer = () => {
        if (saving) return;
        setDossierId(null);
        onClose();
    };

    const executer = async (cibleDossierId: number) => {
        setSaving(true);
        let succes = 0;
        let echecs = 0;

        for (const doc of documents) {
            let fusionnerGroupes = false;
            if (doc.access === 'PRIVE') {
                try {
                    const verif = await verifierFusionGroupeDossier(doc.documentId, cibleDossierId);
                    if (verif.groupesDifferents) {
                        const liste = verif.membresQuiSerontAjoutes.join(', ');
                        const accepte = await confirm(
                            `"${doc.titre}" : le groupe de ce document et celui du dossier n'ont pas les mêmes membres. `
                            + 'En continuant, les deux groupes seront fusionnés (union des membres)'
                            + (liste ? ` — ${liste} sera${verif.membresQuiSerontAjoutes.length > 1 ? 'ont' : ''} `
                                + `ajouté${verif.membresQuiSerontAjoutes.length > 1 ? 's' : ''} au groupe du dossier.` : '.')
                        );
                        if (!accepte) continue;
                        fusionnerGroupes = true;
                    }
                } catch {
                    echecs++;
                    continue;
                }
            }

            try {
                await modifierDossierDocument(doc.documentId, cibleDossierId, fusionnerGroupes);
                succes++;
            } catch {
                echecs++;
            }
        }

        setSaving(false);
        setDossierId(null);
        onClose();
        onSuccess();

        if (succes > 0) {
            notify.success(`${succes} document${succes > 1 ? 's' : ''} déplacé${succes > 1 ? 's' : ''} avec succès`);
        }
        if (echecs > 0) {
            notify.error(`${echecs} échec${echecs > 1 ? 's' : ''} sur ${documents.length}`);
        }
    };

    return (
        <Modal
            isOpen={isOpen}
            onClose={fermer}
            title={documents.length > 1 ? `Déplacer ${documents.length} documents` : 'Déplacer le document'}
        >
            <div className="dossier-attach-edit">
                <DossierTreePicker uoId={uoId} value={dossierId} onChange={setDossierId} />
                <div className="dossier-attach-edit-actions">
                    <button
                        type="button"
                        className="attestation-generer-btn"
                        disabled={saving || dossierId == null}
                        onClick={() => { if (dossierId != null) executer(dossierId); }}
                    >
                        {saving ? '…' : 'Déplacer ici'}
                    </button>
                    <button type="button" className="details-close-btn" onClick={fermer} disabled={saving}>
                        Annuler
                    </button>
                </div>
            </div>
        </Modal>
    );
}

export default DeplacerDossierModal;
