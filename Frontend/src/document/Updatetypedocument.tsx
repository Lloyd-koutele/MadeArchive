// document/Updatetypedocument.tsx
import React, { useState, useEffect } from 'react';
import { updateTypeDocument, modifierSortFinalTypeDocument, modifierDelaiGraceTypeDocument } from '../services/document/TypedocumentService';
import type { MetaDataDto, SortFinal, TypeDocumentDto } from '../services/document/TypedocumentService';
import TypeDocumentFormFields from './TypeDocumentFormFields';
import { getPlanClassement, aplatirPlanClassement, rattacherTypeAActivite } from '../services/organisation/PlanClassementService';
import type { PlanClassementOption } from '../services/organisation/PlanClassementService';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/document/Typedocument.css';

interface UpdateTypeDocumentProps {
    initialData: TypeDocumentDto;
    onsuccess?: () => void;
}

function UpdateTypeDocument({ initialData, onsuccess }: UpdateTypeDocumentProps) {
    const notify = useNotify();
    const [nom, setNom] = useState('');
    const [retentionYears, setRetentionYears] = useState<number | null>(null);
    const [sortFinal, setSortFinal] = useState<SortFinal>('CONSERVER');
    const [delaiGrace, setDelaiGrace] = useState<number | null>(null);
    const [metaData, setMetaData] = useState<MetaDataDto[]>([{ nom: '', obligatoire: false }]);
    const [isLoading, setIsLoading] = useState(false);
    const [activites, setActivites] = useState<PlanClassementOption[]>([]);
    const [activiteId, setActiviteId] = useState<number | null>(null);

    useEffect(() => {
        if (initialData?.uoId == null) return;
        getPlanClassement(initialData.uoId)
            .then(arbre => setActivites(aplatirPlanClassement(arbre)))
            .catch(() => setActivites([]));
    }, [initialData?.uoId]);

    useEffect(() => {
        if (initialData) {
            setNom(initialData.nom || '');
            setRetentionYears(initialData.retentionYears ?? null);
            setSortFinal(initialData.sortFinal ?? 'CONSERVER');
            setDelaiGrace(initialData.periodGrace ?? null);
            setActiviteId(initialData.planClassementNoeudId ?? null);
            setMetaData(
                initialData.metaData && initialData.metaData.length > 0
                    ? initialData.metaData.map(m => ({ id: m.id, nom: m.nom, obligatoire: m.obligatoire }))
                    : [{ nom: '', obligatoire: false }]
            );
        }
    }, [initialData]);

    const validate = (): boolean => {
        if (!nom.trim()) { notify.error("Le nom du type de document est obligatoire"); return false; }
        if (retentionYears !== null && retentionYears < 1) {
            notify.error("La durée de rétention doit être d'au moins 1 an, ou laissée indéfinie");
            return false;
        }
        for (const m of metaData) {
            if (!m.nom.trim()) { notify.error("Chaque métadonnée doit avoir un nom"); return false; }
        }
        return true;
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!validate()) return;
        if (!initialData.id) { notify.error("ID du type de document manquant"); return; }

        setIsLoading(true);

        // Deux appels INDÉPENDANTS, pas un seul dto fusionné : updateTypeDocument
        // est bloqué par le serveur si des documents sont déjà rattachés à ce type
        // (voir TypeDocumentService.hasLinkedDocuments côté backend), mais le sort
        // final doit rester modifiable MÊME dans ce cas (voir modifierSortFinal,
        // endpoint dédié) — un échec de l'un ne doit jamais empêcher l'autre.
        let succesPrincipal = true;
        let succesSortFinal = true;

        try {
            const dto: TypeDocumentDto = {
                nom: nom.trim(),
                retentionYears,
                uoId: initialData.uoId, // inchangé — plus de déplacement via ce formulaire
                metaData
            };
            await updateTypeDocument(initialData.id, dto);
        } catch (err: any) {
            succesPrincipal = false;
            notify.error(err.message || "Erreur lors de la mise à jour");
        }

        if (sortFinal !== (initialData.sortFinal ?? 'CONSERVER')) {
            try {
                await modifierSortFinalTypeDocument(initialData.id, sortFinal);
            } catch (err: any) {
                succesSortFinal = false;
                notify.error(err.message || "Erreur lors de la modification du sort final");
            }
        }

        // Délai de grâce : appel dédié lui aussi (non verrouillé par les documents rattachés).
        let succesDelai = true;
        if (delaiGrace !== (initialData.periodGrace ?? null)) {
            try {
                await modifierDelaiGraceTypeDocument(initialData.id, delaiGrace);
            } catch (err: any) {
                succesDelai = false;
                notify.error(err.message || "Erreur lors de la modification du délai de grâce");
            }
        }

        // Activité : appel dédié lui aussi — modifiable même si des documents sont rattachés au type.
        let succesActivite = true;
        if (activiteId !== (initialData.planClassementNoeudId ?? null)) {
            try {
                await rattacherTypeAActivite(initialData.id, activiteId);
            } catch (err: any) {
                succesActivite = false;
                notify.error(err.message || "Erreur lors du changement d'activité");
            }
        }

        setIsLoading(false);
        if (succesPrincipal && succesSortFinal && succesDelai && succesActivite) {
            notify.success("Type de document mis à jour avec succès");
            setTimeout(() => onsuccess?.(), 1500);
        }
    };

    return (
        <div className="td-form-wrapper">
            <form onSubmit={handleSubmit}>
                <TypeDocumentFormFields
                    idPrefix="tdu"
                    nom={nom} onNomChange={setNom}
                    retentionYears={retentionYears} onRetentionYearsChange={setRetentionYears}
                    sortFinal={sortFinal} onSortFinalChange={setSortFinal}
                    delaiGrace={delaiGrace} onDelaiGraceChange={setDelaiGrace}
                    activites={activites} activiteId={activiteId} onActiviteChange={setActiviteId}
                    metaData={metaData} onMetaDataChange={setMetaData}
                />
                <button type="submit" className="form-submit-btn td-submit" disabled={isLoading}>
                    {isLoading ? 'Mise à jour en cours...' : 'Mettre à jour'}
                </button>
            </form>
        </div>
    );
}

export default UpdateTypeDocument;
