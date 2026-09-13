import { useEffect, useMemo, useRef, useState } from 'react';
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

/** Un type de document, avec l'UO à laquelle il appartient — un type
 *  appartient à EXACTEMENT une UO (voir TypeDocumentService.getTypeDocumentsByUO,
 *  backend), ces deux champs sont donc toujours connus sans ambiguïté au moment
 *  où on le récupère via getTypeDocumentsByUO(uoId). Sert à regrouper visuellement
 *  l'étape 2 par UO d'origine — sans ça, deux UO différentes ayant chacune un
 *  type nommé pareil (ou simplement une longue liste mélangée) rendaient facile
 *  de se tromper sur ce qu'on cochait réellement. */
interface TypeDocumentAvecUo extends TypeDocumentDto {
    uoId: number;
    uoNom: string;
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

/** Regroupe une liste plate d'UO par parentId — base commune pour construire
 *  l'arbre (racines + enfants de chaque nœud), même principe que UOTree.tsx
 *  côté sidebar. */
function regrouperParParent(uos: UOOption[]): Map<number | null, UOOption[]> {
    const map = new Map<number | null, UOOption[]>();
    for (const uo of uos) {
        const cle = uo.parentId ?? null;
        if (!map.has(cle)) map.set(cle, []);
        map.get(cle)!.push(uo);
    }
    return map;
}

/**
 * Déclenchement MANUEL du contrôle d'intégrité (fixity check) — voir
 * FixityCheckTriggerService (backend). La tâche planifiée vérifie déjà TOUT
 * une fois par jour à 3h du matin ; ce panneau sert pour un doute ponctuel
 * (ex. incident MinIO suspecté sur une UO précise) sans attendre la nuit.
 *
 * Flux en deux temps plutôt que trois périmètres indépendants : on choisit
 * D'ABORD la ou les UO (obligatoire — cocher une UO mère coche aussi TOUS ses
 * descendants automatiquement, à l'utilisateur de décocher ensuite ce qu'il
 * ne veut pas garder), puis on peut éventuellement AFFINER en décochant des
 * types de documents précis PARMI ceux de ces UO — un type appartient à
 * EXACTEMENT une UO, jamais hérité du sous-arbre (voir
 * TypeDocumentService.getTypeDocumentsByUO, backend), donc les types
 * proposés à l'étape 2 (groupés par UO d'origine, pour ne pas les confondre)
 * sont recalculés à chaque changement de sélection d'UO — et TOUS cochés par
 * défaut dès qu'une UO devient sélectionnée (cohérent avec le comportement
 * "tout coché, à décocher" de l'étape 1). Tout coché à l'étape 2 → périmètre
 * UO envoyé au backend (équivalent, plus simple) ; au moins un décoché →
 * périmètre TYPES avec ce qui reste coché.
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
    const [typesDisponibles, setTypesDisponibles] = useState<TypeDocumentAvecUo[]>([]);
    const [selectedTypeIds, setSelectedTypeIds] = useState<Set<number>>(new Set());
    const [loadingUos, setLoadingUos] = useState(true);
    const [loadingTypes, setLoadingTypes] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [expanded, setExpanded] = useState<Set<number>>(new Set());
    const expansionInitialisee = useRef(false);
    // UO déjà vues à l'étape précédente — sert à ne cocher par défaut QUE les
    // types d'une UO qui vient d'être ajoutée à la sélection, sans re-cocher
    // à tort un type qu'on avait déjà décoché manuellement pour une UO qui,
    // elle, était déjà sélectionnée avant ce recalcul (ex : sélection d'une
    // 2e UO alors qu'on avait déjà affiné la 1ère).
    const uoIdsPrecedents = useRef<Set<number>>(new Set());

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

    // Types proposés à l'étape 2 = union des types des UO cochées à l'étape 1,
    // étiquetés avec leur UO d'origine (pour le regroupement à l'affichage —
    // voir typesParUo ci-dessous). Recalculé à chaque changement de sélection :
    // une UO décochée fait disparaître ses types de la liste ET de la
    // sélection s'ils y étaient ; une UO NOUVELLEMENT cochée voit tous ses
    // types cochés par défaut (à l'utilisateur de décocher ce qu'il exclut) ;
    // une UO déjà sélectionnée avant ce recalcul garde les coches/décoches
    // déjà faites par l'utilisateur, sans y toucher.
    useEffect(() => {
        if (selectedUoIds.size === 0) {
            setTypesDisponibles([]);
            setSelectedTypeIds(new Set());
            uoIdsPrecedents.current = new Set();
            return;
        }
        let annule = false;
        setLoadingTypes(true);
        const idsNouvellementCoches = new Set(
            Array.from(selectedUoIds).filter(id => !uoIdsPrecedents.current.has(id))
        );
        Promise.all(Array.from(selectedUoIds).map(async id => {
            const types = await getTypeDocumentsByUO(id);
            const uoNom = uos.find(u => u.id === id)?.nom ?? '?';
            return types.map((t): TypeDocumentAvecUo => ({ ...t, uoId: id, uoNom }));
        }))
            .then(listes => {
                if (annule) return;
                const parId = new Map<number, TypeDocumentAvecUo>();
                for (const liste of listes) {
                    for (const t of liste) {
                        if (t.id !== undefined) parId.set(t.id, t);
                    }
                }
                setTypesDisponibles(Array.from(parId.values()));
                setSelectedTypeIds(prev => {
                    const next = new Set(Array.from(prev).filter(id => parId.has(id)));
                    for (const t of parId.values()) {
                        if (t.id !== undefined && idsNouvellementCoches.has(t.uoId)) {
                            next.add(t.id);
                        }
                    }
                    return next;
                });
                uoIdsPrecedents.current = new Set(selectedUoIds);
            })
            .catch(err => notify.error(err.message ?? 'Erreur chargement des types de documents'))
            .finally(() => { if (!annule) setLoadingTypes(false); });
        return () => { annule = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selectedUoIds]);

    const enfantsParParent = useMemo(() => regrouperParParent(uos), [uos]);
    const idsConnus = useMemo(() => new Set(uos.map(u => u.id)), [uos]);
    // Racines : parentId absent/null, OU dont le parent n'est pas dans la liste
    // (cas d'une UO racine de son propre sous-arbre, parentId pointant hors du
    // périmètre visible — arrive pour un ADMIN_UO dont la racine a elle-même
    // un parent qu'il n'a pas l'autorité de voir).
    const racines = useMemo(
        () => uos.filter(u => u.parentId == null || !idsConnus.has(u.parentId)),
        [uos, idsConnus]
    );

    // Toutes les UO démarrent dépliées (même défaut que UOTree.tsx côté
    // sidebar) — initialisé une seule fois dès que la liste arrive, pas
    // resynchronisé ensuite : un repli manuel de l'utilisateur ne doit pas
    // être annulé par un rafraîchissement de la liste (voir l'effet de
    // synchronisation avec le parent ci-dessus).
    useEffect(() => {
        if (!expansionInitialisee.current && uos.length > 0) {
            setExpanded(new Set(uos.filter(u => enfantsParParent.has(u.id)).map(u => u.id)));
            expansionInitialisee.current = true;
        }
    }, [uos, enfantsParParent]);

    const toggle = (set: Set<number>, setSet: (s: Set<number>) => void, id: number) => {
        const next = new Set(set);
        if (next.has(id)) next.delete(id); else next.add(id);
        setSet(next);
    };

    /** Tous les descendants (récursif) d'une UO — sert à la cascade de coche. */
    const collecterDescendants = (id: number): number[] => {
        const enfants = enfantsParParent.get(id) ?? [];
        return enfants.flatMap(e => [e.id, ...collecterDescendants(e.id)]);
    };

    /** Cocher/décocher une UO coche/décoche AUSSI tous ses descendants — cocher
     *  une UO mère revient donc à tout inclure sous elle par défaut, à
     *  l'utilisateur de décocher ensuite ce qu'il ne veut pas garder. */
    const toggleUo = (id: number) => {
        setSelectedUoIds(prev => {
            const next = new Set(prev);
            const idsACascader = [id, ...collecterDescendants(id)];
            if (prev.has(id)) {
                for (const i of idsACascader) next.delete(i);
            } else {
                for (const i of idsACascader) next.add(i);
            }
            return next;
        });
    };

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const renderUoNode = (uo: UOOption, profondeur: number) => {
        const enfants = enfantsParParent.get(uo.id) ?? [];
        const aDesEnfants = enfants.length > 0;
        const estDeplie = expanded.has(uo.id);
        const inputId = `fixity-uo-${uo.id}`;
        return (
            <div key={uo.id}>
                <div className="fixity-checklist-item" style={{ paddingLeft: `${profondeur * 20}px` }}>
                    {aDesEnfants ? (
                        <button
                            type="button"
                            className="fixity-tree-toggle"
                            onClick={() => toggleExpand(uo.id)}
                            aria-label={estDeplie ? 'Réduire' : 'Développer'}
                        >
                            {estDeplie ? '▾' : '▸'}
                        </button>
                    ) : (
                        <span className="fixity-tree-toggle-spacer" />
                    )}
                    <input
                        type="checkbox"
                        id={inputId}
                        checked={selectedUoIds.has(uo.id)}
                        onChange={() => toggleUo(uo.id)}
                    />
                    <label htmlFor={inputId}>{uo.nom}</label>
                </div>
                {aDesEnfants && estDeplie && enfants.map(enfant => renderUoNode(enfant, profondeur + 1))}
            </div>
        );
    };

    // Regroupement des types disponibles par UO d'origine, pour l'affichage de
    // l'étape 2 — sans ça, une liste fusionnée de plusieurs UO ne permettait
    // pas de savoir quel type appartenait à laquelle (demande explicite : "un
    // mécanisme de distinguer les types de documents de chaque UO").
    const typesParUo = useMemo(() => {
        const map = new Map<number, { uoNom: string; types: TypeDocumentAvecUo[] }>();
        for (const t of typesDisponibles) {
            if (!map.has(t.uoId)) map.set(t.uoId, { uoNom: t.uoNom, types: [] });
            map.get(t.uoId)!.types.push(t);
        }
        return map;
    }, [typesDisponibles]);

    const handleDeclencher = async () => {
        let scope: FixityCheckScope;
        let typeDocumentIds: number[] | undefined;
        let uoIds: number[] | undefined;

        if (modeTout) {
            scope = 'TOUT';
        } else if (selectedUoIds.size === 0) {
            notify.error('Sélectionnez au moins une UO');
            return;
        } else if (typesDisponibles.length > 0 && selectedTypeIds.size === 0) {
            // Tous les types ont été décochés à l'étape 2 : PAS un no-op silencieux
            // qui retomberait à tort sur "toute l'UO" en ignorant ces décoches
            // délibérées — il n'y a alors littéralement plus rien à vérifier.
            notify.error(
                'Vous avez décoché tous les types de document de la sélection — cochez-en au moins un à '
                + 'l\'étape 2, ou décochez entièrement l\'UO à l\'étape 1 si vous ne voulez pas la vérifier.'
            );
            return;
        } else if (selectedTypeIds.size < typesDisponibles.length) {
            // Au moins un type décoché (mais pas tous) : seul ce qui reste coché
            // doit être vérifié.
            scope = 'TYPES';
            typeDocumentIds = Array.from(selectedTypeIds);
        } else {
            // Tout coché à l'étape 2 (ou aucun type disponible du tout, ex. UO
            // sans aucun type défini) équivaut à ne rien avoir affiné : envoyer
            // le périmètre UO tel quel est plus simple et strictement équivalent.
            scope = 'UO';
            uoIds = Array.from(selectedUoIds);
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
                            {racines.length === 0 && <p className="fixity-empty">Aucune UO.</p>}
                            {racines.map(racine => renderUoNode(racine, 0))}
                        </div>
                    )}

                    {selectedUoIds.size > 0 && (
                        <>
                            <h3 className="fixity-step-title">2. Affiner par type de document (optionnel)</h3>
                            {loadingTypes ? (
                                <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
                            ) : (
                                <div className="fixity-checklist">
                                    {typesParUo.size === 0 && (
                                        <p className="fixity-empty">Aucun type de document dans cette sélection.</p>
                                    )}
                                    {Array.from(typesParUo.entries()).map(([uoId, { uoNom, types }]) => (
                                        <div key={uoId} className="fixity-types-group">
                                            <p className="fixity-types-group-title">{uoNom}</p>
                                            {types.map(t => (
                                                <label key={t.id} className="fixity-checklist-item fixity-checklist-item-indent">
                                                    <input
                                                        type="checkbox"
                                                        checked={t.id !== undefined && selectedTypeIds.has(t.id)}
                                                        onChange={() => t.id !== undefined && toggle(selectedTypeIds, setSelectedTypeIds, t.id)}
                                                    />
                                                    {t.nom}
                                                </label>
                                            ))}
                                        </div>
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
