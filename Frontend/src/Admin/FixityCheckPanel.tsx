import { useEffect, useState } from 'react';
import { getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getAllUOs, getMyUO, getSousArbre } from '../services/organisation/UOService';
import { declencherFixityCheck } from '../services/document/FixityCheckService';
import type { FixityCheckScope } from '../services/document/FixityCheckService';
import { hasRole } from '../auth/authService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/Admin/FixityCheckPanel.css';

interface UOOption {
    id: number;
    nom: string;
    parentId?: number | null;
}

interface FixityCheckPanelProps {
    /**
     * ADMIN_UO uniquement : liste déjà tenue à jour par AdminUoDashboard
     * (voir treeNodes/fetchSousArbre là-bas — rafraîchie juste après chaque
     * création/renommage d'UO, et sur le retour de focus de l'onglet
     * navigateur, via useRefetchOnFocus). Sans ce prop, ce panneau faisait
     * son propre chargement UNE SEULE FOIS au montage : une UO créée depuis
     * la sidebar pendant qu'on était déjà sur ce panneau restait invisible
     * ici tant qu'on ne le refermait/rouvrait pas.
     */
    uos?: UOOption[];
}

/**
 * Trie une liste plate d'UO (avec parentId) en ordre hiérarchique
 * (parent immédiatement suivi de ses enfants, récursivement) et calcule la
 * profondeur de chacune — sert à l'indentation visuelle de la liste, pour
 * refléter la même arborescence que celle de la sidebar (voir UOTree.tsx,
 * même principe de regroupement par parentId).
 */
function trierParHierarchie(uos: UOOption[]): Array<{ uo: UOOption; profondeur: number }> {
    const enfantsParParent = new Map<number | null, UOOption[]>();
    for (const uo of uos) {
        const cle = uo.parentId ?? null;
        if (!enfantsParParent.has(cle)) enfantsParParent.set(cle, []);
        enfantsParParent.get(cle)!.push(uo);
    }

    const resultat: Array<{ uo: UOOption; profondeur: number }> = [];
    const visiter = (parentId: number | null, profondeur: number) => {
        for (const uo of enfantsParParent.get(parentId) ?? []) {
            resultat.push({ uo, profondeur });
            visiter(uo.id, profondeur + 1);
        }
    };
    // Racines : parentId absent/null, OU dont le parent n'est pas dans la liste
    // (cas d'une UO racine de son propre sous-arbre, parentId pointant hors du
    // périmètre visible — arrive pour un ADMIN_UO dont la racine a elle-même
    // un parent qu'il n'a pas l'autorité de voir).
    const idsConnus = new Set(uos.map(u => u.id));
    const racines = uos.filter(u => u.parentId == null || !idsConnus.has(u.parentId));
    for (const racine of racines) {
        if (!resultat.some(r => r.uo.id === racine.id)) {
            resultat.push({ uo: racine, profondeur: 0 });
            visiter(racine.id, 1);
        }
    }
    return resultat;
}

/**
 * Déclenchement MANUEL du contrôle d'intégrité (fixity check) — voir
 * FixityCheckTriggerService (backend). La tâche planifiée vérifie déjà TOUT
 * une fois par jour à 3h du matin ; ce panneau sert pour un doute ponctuel
 * (ex. incident MinIO suspecté sur une UO précise) sans attendre la nuit.
 *
 * Flux en deux temps plutôt que trois périmètres indépendants : on choisit
 * D'ABORD la ou les UO (obligatoire), puis on peut éventuellement AFFINER en
 * cochant des types de documents précis PARMI ceux de ces UO — un type
 * appartient à EXACTEMENT une UO, jamais hérité du sous-arbre (voir
 * TypeDocumentService.getTypeDocumentsByUO, backend), donc les types
 * proposés à l'étape 2 sont recalculés à chaque changement de sélection
 * d'UO. Rien coché à l'étape 2 → toute la sélection d'UO est vérifiée ;
 * un ou plusieurs types cochés → seuls ceux-là le sont (le périmètre envoyé
 * au backend devient alors TYPES plutôt que UO, mais ça reste transparent
 * pour l'utilisateur).
 *
 * "Tout le système" reste un mode à part, réservé à ROLE_ADMIN (le backend
 * le refuse de toute façon à un ADMIN_UO — ce n'est qu'un filtre
 * d'affichage, pas la garde réelle) : mutuellement exclusif avec la
 * sélection UO/types ci-dessus, pas une simple case du même arbre.
 * Chaque périmètre individuel a son propre cooldown de 6h côté serveur ; le
 * message d'erreur renvoyé (déjà formulé côté backend) est affiché tel quel.
 */
function FixityCheckPanel({ uos: uosExternes }: FixityCheckPanelProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    const estAdmin = hasRole('ADMIN');

    const [modeTout, setModeTout] = useState(false);
    const [uos, setUos] = useState<UOOption[]>([]);
    const [selectedUoIds, setSelectedUoIds] = useState<Set<number>>(new Set());
    const [typesDisponibles, setTypesDisponibles] = useState<TypeDocumentDto[]>([]);
    const [selectedTypeIds, setSelectedTypeIds] = useState<Set<number>>(new Set());
    const [loadingUos, setLoadingUos] = useState(true);
    const [loadingTypes, setLoadingTypes] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    // Chargement initial des UO — ADMIN voit tout, ADMIN_UO son propre
    // sous-arbre (getAllUOs() est réservé ROLE_ADMIN côté backend, voir
    // UOController — un ADMIN_UO y prenait un 403 avant ce correctif).
    useEffect(() => {
        const charger = async () => {
            if (estAdmin) {
                setUos(await getAllUOs());
            } else {
                const monUO = await getMyUO();
                setUos(await getSousArbre(monUO.id));
            }
        };
        charger()
            .catch(err => notify.error(err.message ?? 'Erreur chargement des UO'))
            .finally(() => setLoadingUos(false));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Synchronisation avec la liste du parent (voir FixityCheckPanelProps.uos) —
    // se déclenche à chaque nouvelle référence (créée par setTreeNodes côté
    // parent après une création/un renommage, ou son propre useRefetchOnFocus),
    // pas seulement au montage. Ignore un tableau vide : le parent peut encore
    // être en train de charger sa propre liste au premier rendu, un tableau
    // vide écraserait alors à tort la liste déjà obtenue par ce composant
    // lui-même juste au-dessus.
    useEffect(() => {
        if (uosExternes && uosExternes.length > 0) {
            setUos(uosExternes);
        }
    }, [uosExternes]);

    // Types proposés à l'étape 2 = union des types des UO cochées à l'étape 1.
    // Recalculé à chaque changement de sélection ; une UO décochée fait
    // disparaître ses types de la liste ET de la sélection s'ils y étaient.
    useEffect(() => {
        if (selectedUoIds.size === 0) {
            setTypesDisponibles([]);
            setSelectedTypeIds(new Set());
            return;
        }
        let annule = false;
        setLoadingTypes(true);
        Promise.all(Array.from(selectedUoIds).map(id => getTypeDocumentsByUO(id)))
            .then(listes => {
                if (annule) return;
                const parId = new Map<number, TypeDocumentDto>();
                for (const liste of listes) {
                    for (const t of liste) {
                        if (t.id !== undefined) parId.set(t.id, t);
                    }
                }
                setTypesDisponibles(Array.from(parId.values()));
                setSelectedTypeIds(prev => new Set(Array.from(prev).filter(id => parId.has(id))));
            })
            .catch(err => notify.error(err.message ?? 'Erreur chargement des types de documents'))
            .finally(() => { if (!annule) setLoadingTypes(false); });
        return () => { annule = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selectedUoIds]);

    const uosHierarchisees = trierParHierarchie(uos);

    const toggle = (set: Set<number>, setSet: (s: Set<number>) => void, id: number) => {
        const next = new Set(set);
        if (next.has(id)) next.delete(id); else next.add(id);
        setSet(next);
    };

    const handleDeclencher = async () => {
        let scope: FixityCheckScope;
        let typeDocumentIds: number[] | undefined;
        let uoIds: number[] | undefined;

        if (modeTout) {
            scope = 'TOUT';
        } else if (selectedTypeIds.size > 0) {
            scope = 'TYPES';
            typeDocumentIds = Array.from(selectedTypeIds);
        } else if (selectedUoIds.size > 0) {
            scope = 'UO';
            uoIds = Array.from(selectedUoIds);
        } else {
            notify.error('Sélectionnez au moins une UO');
            return;
        }

        if (scope === 'TOUT') {
            if (!(await confirm(
                'Vérifier l\'intégrité de TOUT le catalogue ? Cette opération télécharge et déchiffre chaque '
                + 'document archivé — potentiellement longue selon le volume. Vous serez notifié à la fin.'
            ))) return;
        }

        setSubmitting(true);
        try {
            const message = await declencherFixityCheck({ scope, typeDocumentIds, uoIds });
            notify.success(message);
            setSelectedTypeIds(new Set());
            setSelectedUoIds(new Set());
            setModeTout(false);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du déclenchement du contrôle');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="fixity-panel">
            <div className="fixity-panel-header">
                <h2>Contrôle d'intégrité</h2>
                <p className="fixity-panel-sub">
                    Recalcule l'empreinte SHA-256 de chaque document archivé et la compare à celle enregistrée à
                    l'archivage — la même vérification tourne déjà automatiquement chaque nuit à 3h. Un
                    déclenchement manuel est utile en cas de doute ponctuel, pas pour un usage courant : chaque
                    périmètre (une UO, un type de document, ou tout le système) ne peut être relancé qu'une fois
                    toutes les 6 heures, pour ne pas surcharger le stockage.
                </p>
            </div>

            {estAdmin && (
                <label className="fixity-scope-btn fixity-tout-toggle">
                    <input
                        type="checkbox"
                        checked={modeTout}
                        onChange={e => setModeTout(e.target.checked)}
                    />
                    <i className="fa-solid fa-globe" /> Tout le système (tous types, toutes UO)
                </label>
            )}

            {modeTout ? (
                <p className="fixity-empty">
                    Vérifiera l'intégralité des documents archivés, tous types et UO confondus.
                </p>
            ) : (
                <>
                    <h3 className="fixity-step-title">1. Choisir la ou les UO à vérifier</h3>
                    {loadingUos ? (
                        <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
                    ) : (
                        <div className="fixity-checklist">
                            {uosHierarchisees.length === 0 && <p className="fixity-empty">Aucune UO.</p>}
                            {uosHierarchisees.map(({ uo, profondeur }) => (
                                <label
                                    key={uo.id}
                                    className="fixity-checklist-item"
                                    style={{ paddingLeft: `${profondeur * 20}px` }}
                                >
                                    <input
                                        type="checkbox"
                                        checked={selectedUoIds.has(uo.id)}
                                        onChange={() => toggle(selectedUoIds, setSelectedUoIds, uo.id)}
                                    />
                                    {uo.nom}
                                </label>
                            ))}
                        </div>
                    )}

                    {selectedUoIds.size > 0 && (
                        <>
                            <h3 className="fixity-step-title">2. Affiner par type de document (optionnel)</h3>
                            <p className="fixity-panel-sub">
                                Rien de coché ici : toute la sélection d'UO ci-dessus sera vérifiée en entier.
                            </p>
                            {loadingTypes ? (
                                <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
                            ) : (
                                <div className="fixity-checklist">
                                    {typesDisponibles.length === 0 && (
                                        <p className="fixity-empty">Aucun type de document dans cette sélection.</p>
                                    )}
                                    {typesDisponibles.map(t => (
                                        <label key={t.id} className="fixity-checklist-item">
                                            <input
                                                type="checkbox"
                                                checked={t.id !== undefined && selectedTypeIds.has(t.id)}
                                                onChange={() => t.id !== undefined && toggle(selectedTypeIds, setSelectedTypeIds, t.id)}
                                            />
                                            {t.nom}
                                        </label>
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </>
            )}

            <button
                type="button"
                className="form-submit-btn up-submit"
                onClick={handleDeclencher}
                disabled={submitting}
            >
                {submitting
                    ? <><i className="fa-solid fa-spinner fa-spin" /> Déclenchement…</>
                    : <><i className="fa-solid fa-shield-halved" /> Lancer la vérification</>}
            </button>
        </div>
    );
}

export default FixityCheckPanel;
