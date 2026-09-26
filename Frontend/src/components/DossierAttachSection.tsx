import { useState } from 'react';
import { modifierDossierDocument, verifierFusionGroupeDossier } from '../services/document/DocumentService';
import type { DocumentDetailDto } from '../services/document/DocumentService';
import DossierTreePicker from '../organisation/DossierTreePicker';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';

interface DossierAttachSectionProps {
    detail: DocumentDetailDto;
    onUpdated?: (updated: DocumentDetailDto) => void;
}

/**
 * Rattacher/migrer/détacher un document à un dossier APRÈS COUP (dans le
 * détail d'un document déjà archivé) — partagé entre MesDocumentsEditor.tsx
 * et DocumentsAccessible.tsx (dupliqué avant, voir historique du projet).
 *
 * Réutilise DossierTreePicker (arbre COMPLET de l'UO, plié/déplié, filtré
 * par nom — voir ImportDocuments.tsx) plutôt qu'un <select> plat limité aux
 * dossiers RACINE (getDossiersDeUO sans parentId) : les sous-dossiers y
 * étaient tout simplement invisibles, impossible d'y rattacher un document.
 */
function DossierAttachSection({ detail, onUpdated }: DossierAttachSectionProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const [editing, setEditing] = useState(false);
    const [selected, setSelected] = useState<number | null>(null);
    const [saving, setSaving] = useState(false);

    const ouvrirEdition = () => {
        setSelected(detail.dossierId ?? null);
        setEditing(true);
    };

    const enregistrer = async () => {
        const dossierIdSelectionne = selected;
        let fusionnerGroupes = false;

        // Document privé rattaché à un dossier privé : vérifier AVANT
        // d'écrire si les deux groupes diffèrent, pour avertir l'éditeur
        // qu'un rattachement les fusionnera (union des membres, lien
        // permanent — voir DocumentService.modifierDossierDocument).
        if (dossierIdSelectionne && detail.access === 'PRIVE') {
            try {
                const verif = await verifierFusionGroupeDossier(detail.documentId, dossierIdSelectionne);
                if (verif.groupesDifferents) {
                    const liste = verif.membresQuiSerontAjoutes.join(', ');
                    const accepte = await confirm(
                        'Le groupe de ce document et celui du dossier n\'ont pas les mêmes membres. '
                        + 'En continuant, les deux groupes seront fusionnés (union des membres)'
                        + (liste ? ` — ${liste} sera${verif.membresQuiSerontAjoutes.length > 1 ? 'ont' : ''} `
                            + `ajouté${verif.membresQuiSerontAjoutes.length > 1 ? 's' : ''} au groupe du dossier.` : '.')
                    );
                    if (!accepte) return;
                    fusionnerGroupes = true;
                }
            } catch (err: any) {
                notify.error(err.message ?? 'Erreur lors de la vérification des groupes');
                return;
            }
        }

        setSaving(true);
        try {
            const updated = await modifierDossierDocument(
                detail.documentId, dossierIdSelectionne, fusionnerGroupes
            );
            onUpdated?.(updated);
            setEditing(false);
            notify.success('Dossier mis à jour');
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'enregistrement');
        } finally {
            setSaving(false);
        }
    };

    if (!detail.peutModifierDossier && !detail.dossierNom) {
        return null;
    }

    return (
        <div className="details-row emplacement-physique-row">
            <strong>Dossier :</strong>
            {editing ? (
                <div className="dossier-attach-edit">
                    {detail.uniteOrganisationnelleId != null && (
                        <DossierTreePicker
                            uoId={detail.uniteOrganisationnelleId}
                            value={selected}
                            onChange={setSelected}
                        />
                    )}
                    <div className="dossier-attach-edit-actions">
                        <button type="button" className="attestation-generer-btn" disabled={saving} onClick={enregistrer}>
                            {saving ? '…' : 'Enregistrer'}
                        </button>
                        <button type="button" className="details-close-btn" onClick={() => setEditing(false)}>Annuler</button>
                    </div>
                </div>
            ) : (
                <>
                    <span>{detail.dossierCheminComplet ?? detail.dossierNom ?? '—'}</span>
                    {detail.peutModifierDossier && (
                        <button type="button" className="details-close-btn" onClick={ouvrirEdition}>
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                </>
            )}
        </div>
    );
}

export default DossierAttachSection;
