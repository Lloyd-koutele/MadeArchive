import { useState, useEffect } from 'react';
import Modal from '../Page/Modal';
import {
    creerArborescence,
    mettreAJourArborescence,
    changerTypeStockage,
} from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationTreeNodeDto, PhysicalLocationNodeDto, LocationModeContrainte } from '../services/organisation/PhysicalLocationService';
import { getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getArbreDossiers } from '../services/organisation/DossierService';
import DossierTreePicker from './DossierTreePicker';
import { useNotify } from '../notifications/NotificationProvider';
// .pl-form/.pl-form-actions — mêmes styles que l'ancien formulaire simple,
// réutilisés tels quels. .tb-*/.tbo-* : styles propres à l'arbre de
// brouillon (voir PhysicalLocationsPanel.css).
import '../Style/organisation/PhysicalLocationsPanel.css';

/** Nœud EXISTANT ciblé par un raccourci "Capacité"/"Choisir" — assez
 *  d'info pour préremplir le modal dédié (voir PhysicalLocationsPanel,
 *  ouvrirCapacite/ouvrirContrainte) sans redemander un PhysicalLocationNodeDto
 *  complet, indisponible pour un descendant (seule la racine le porte, voir
 *  EmplacementTreeModalProps.existingNode). */
export interface CibleEditionExistante {
    id: string;
    name: string;
    nombreDocuments: number;
    capaciteMax: number | null;
    modeContrainte: LocationModeContrainte;
    typeDocumentAccepteId: number | null;
    dossierId: number | null;
}

/**
 * Nœud de brouillon local — jamais envoyé tel quel, converti en
 * PhysicalLocationTreeNodeDto à la soumission. `key` sert uniquement à
 * retrouver/modifier un nœud dans l'arbre immuable côté client. `id` absent
 * = nouveau nœud (jamais en base) ; défini = nœud EXISTANT (modification
 * uniquement) — voir Javadoc backend PhysicalLocationTreeNodeDto. Pas de
 * description ici — jugée superflue/encombrante pour ce constructeur
 * d'arborescence, retirée de l'interface (reste modifiable individuellement
 * via l'ancien formulaire simple si jamais utile).
 *
 * capaciteMax/modeContrainte/typeDocumentId/dossierId : UNIQUEMENT
 * éditables pour un NOUVEAU nœud storagePoint=true (id absent) — pour un
 * nœud EXISTANT, ces réglages se changent via les actions dédiées du panneau
 * (definirCapaciteEmplacement/definirContrainteEmplacement), jamais ici.
 */
interface DraftNode {
    key: string;
    id?: string;
    name: string;
    storagePoint: boolean;
    capaciteMax: number | null;
    modeContrainte: LocationModeContrainte;
    typeDocumentId: number | null;
    dossierId: number | null;
    /** Uniquement significatif pour un nœud EXISTANT (voir onEditCapacite/
     *  onEditContrainte) — 0 pour un nouveau nœud, jamais encore de documents. */
    nombreDocuments: number;
    children: DraftNode[];
}

const nouvelleKey = () =>
    (typeof crypto !== 'undefined' && crypto.randomUUID) ? crypto.randomUUID() : `n${Date.now()}-${Math.random()}`;

const noeudVide = (): DraftNode => ({
    key: nouvelleKey(), name: '', storagePoint: true,
    capaciteMax: null, modeContrainte: 'LIBRE', typeDocumentId: null, dossierId: null,
    nombreDocuments: 0,
    children: [],
});

/** Convertit récursivement l'arbre RÉEL (lecture) en brouillon éditable. */
const versDraftExistant = (n: PhysicalLocationNodeDto): DraftNode => ({
    key: nouvelleKey(),
    id: n.id,
    name: n.name,
    storagePoint: n.storagePoint,
    capaciteMax: n.capaciteMax,
    modeContrainte: n.modeContrainte,
    typeDocumentId: n.typeDocumentAccepteId,
    dossierId: n.dossierId,
    nombreDocuments: n.nombreDocuments,
    children: n.children.map(versDraftExistant),
});

// ── Opérations immuables sur l'arbre de brouillon (un seul root, pas un tableau) ──

function mettreAJourNoeud(node: DraftNode, key: string, patch: Partial<DraftNode>): DraftNode {
    if (node.key === key) return { ...node, ...patch };
    return { ...node, children: node.children.map(c => mettreAJourNoeud(c, key, patch)) };
}

function ajouterEnfant(node: DraftNode, parentKey: string): DraftNode {
    if (node.key === parentKey) return { ...node, children: [...node.children, noeudVide()] };
    return { ...node, children: node.children.map(c => ajouterEnfant(c, parentKey)) };
}

function supprimerDescendant(node: DraftNode, key: string): DraftNode {
    return { ...node, children: node.children.filter(c => c.key !== key).map(c => supprimerDescendant(c, key)) };
}

/** true si un nom est manquant quelque part dans l'arbre — validation avant envoi. */
function aUnNomManquant(node: DraftNode): boolean {
    return !node.name.trim() || node.children.some(aUnNomManquant);
}

/** true si un NOUVEAU point de stockage a un mode TYPE_UNIQUE/DOSSIER sans
 *  avoir choisi le type/dossier correspondant — validation avant envoi (le
 *  serveur la refait de toute façon, mais autant l'attraper tout de suite). */
function aUneContrainteIncomplete(node: DraftNode): boolean {
    const incomplete = !node.id && node.storagePoint
        && (node.modeContrainte === 'LIBRE'
            || (node.modeContrainte === 'TYPE_UNIQUE' && node.typeDocumentId == null)
            || (node.modeContrainte === 'DOSSIER' && node.dossierId == null));
    return incomplete || node.children.some(aUneContrainteIncomplete);
}

function compterNoeuds(node: DraftNode): number {
    return 1 + node.children.reduce((total, c) => total + compterNoeuds(c), 0);
}

function versRequeteNode(n: DraftNode): PhysicalLocationTreeNodeDto {
    return {
        id: n.id,
        name: n.name.trim(),
        storagePoint: n.storagePoint,
        // Un nœud chemin n'a ni capacité ni contrainte : rien de résiduel n'est envoyé.
        capaciteMax: n.storagePoint ? n.capaciteMax : null,
        modeContrainte: n.storagePoint ? n.modeContrainte : 'LIBRE',
        typeDocumentId: n.storagePoint ? n.typeDocumentId : null,
        dossierId: n.storagePoint ? n.dossierId : null,
        children: n.children.map(versRequeteNode),
    };
}

interface EmplacementTreeModalProps {
    isOpen: boolean;
    onClose: () => void;
    uoId: number;
    mode: 'create' | 'update';
    /** mode="create" — où accrocher la nouvelle racine (null = racine de l'UO). */
    parentId?: string | null;
    /** mode="create" — nom du parent, pour l'en-tête ("sous X"). */
    parentLabel?: string | null;
    /** mode="update" — nœud cliqué + sa descendance actuelle (déjà en mémoire, pas de re-fetch de l'arbre). */
    existingNode?: PhysicalLocationNodeDto;
    /** mode="update" — true si le nœud modifié est une racine de l'UO (sans parent) : elle reste toujours un nœud chemin. */
    existingEstRacine?: boolean;
    /** mode="update" uniquement — raccourcis "Capacité"/"Choisir" affichés sur
     *  CHAQUE nœud EXISTANT de l'organigramme (racine ET descendants —
     *  capacité/contrainte ne sont pas éditables en brouillon pour un nœud
     *  déjà en base, voir Javadoc du composant) : ferment ce modal et ouvrent
     *  le modal dédié correspondant pour le nœud cliqué, au lieu de laisser
     *  l'utilisateur chercher les boutons ailleurs dans la liste. Absents
     *  (undefined) en mode="create". */
    onEditCapacite?: (cible: CibleEditionExistante) => void;
    onEditContrainte?: (cible: CibleEditionExistante) => void;
    /** Racine créée/mise à jour (avec sa descendance) — permet à l'appelant de
     *  réagir au résultat sans re-fetch (ex. ImportDocuments présélectionne
     *  l'emplacement tout juste créé dans son <select>). */
    onSaved: (node: PhysicalLocationNodeDto) => void;
}

/**
 * Modal UNIQUE pour créer ET modifier un emplacement — même expérience dans
 * les deux cas (voir recap) : on manipule toujours EXACTEMENT UNE racine
 * (nouvelle en création, existante en modification) avec sa descendance
 * complète dans un seul arbre éditable, envoyée en un seul appel.
 *
 * - Un nœud NOUVEAU (jamais en base) : nom + type modifiables, supprimable
 *   du brouillon (rien n'a encore été créé) — ces changements ne sont
 *   envoyés qu'à "Créer"/"Enregistrer", avec tout le reste de l'arbre.
 * - Un nœud EXISTANT (déjà en base, mode="update" uniquement) : le nom se
 *   modifie comme les nouveaux (en brouillon, envoyé au clic sur
 *   "Enregistrer"). Le TYPE, en revanche, change immédiatement au clic — même
 *   endpoint changerTypeStockage que le bouton "Convertir" de la liste
 *   indentée, avec les mêmes contraintes serveur (jamais storagePoint avec
 *   des enfants ; jamais chemin s'il reste des documents rattachés) — un
 *   changement de type a un impact réel immédiat (ex. libère/bloque
 *   l'emplacement pour de futurs documents), il n'a pas de sens de le
 *   laisser en brouillon jusqu'à "Enregistrer" comme un simple renommage.
 *   Il n'est PAS supprimable depuis ce modal — supprimer reste le bouton
 *   dédié, avec sa confirmation. Capacité/contrainte d'un nœud EXISTANT se
 *   modifient via les raccourcis dédiés (onEditCapacite/onEditContrainte),
 *   jamais en brouillon ici (voir EmplacementNodeCard).
 */
function EmplacementTreeModal({ isOpen, onClose, uoId, mode, parentId = null, parentLabel = null, existingNode, existingEstRacine = false, onEditCapacite, onEditContrainte, onSaved }: EmplacementTreeModalProps) {
    const notify = useNotify();
    const [root, setRoot] = useState<DraftNode | null>(null);
    const [saving, setSaving] = useState(false);
    // Nœud "en focus" (dernier cliqué) — un simple surlignage visuel, voir
    // recap : rien ne se replie/disparaît, tout l'arbre reste affiché.
    const [focusedKey, setFocusedKey] = useState<string | null>(null);

    // Types de documents / dossiers de l'UO — pour le sélecteur du mode
    // "Type unique"/"Dossier" (voir EmplacementNodeCard), et pour savoir s'il
    // faut désactiver ces modes quand l'UO n'a encore ni type ni dossier :
    // sans ça, l'utilisateur pouvait choisir "Un seul type de document" avec
    // rien à sélectionner dans la liste, bloqué seulement à l'enregistrement
    // avec une erreur peu claire. Chargé une fois par ouverture, pas par nœud.
    const [typesUO, setTypesUO] = useState<TypeDocumentDto[]>([]);
    const [aDesDossiers, setADesDossiers] = useState(true);
    useEffect(() => {
        if (!isOpen) return;
        getTypeDocumentsByUO(uoId).then(setTypesUO).catch(() => setTypesUO([]));
        getArbreDossiers(uoId).then(d => setADesDossiers(d.length > 0)).catch(() => setADesDossiers(false));
    }, [isOpen, uoId]);

    useEffect(() => {
        if (!isOpen) return;

        if (mode === 'create') {
            // La racine d'une NOUVELLE arborescence ne peut jamais être un
            // point de stockage — elle doit toujours être un nœud chemin
            // (retour utilisateur 10/2026) : un emplacement physique part
            // forcément d'un conteneur organisationnel (bâtiment, salle...),
            // jamais directement d'une boîte isolée à la racine de l'UO.
            // Verrouillé aussi dans EmplacementNodeCard (handleToggleType),
            // pas seulement ici au départ.
            const r = { ...noeudVide(), storagePoint: false };
            setRoot(r);
            setFocusedKey(r.key);
            return;
        }

        if (mode === 'update' && existingNode) {
            // Plus besoin de re-fetch réseau : PhysicalLocationNodeDto porte
            // déjà le nom (seule donnée éditée ici, depuis le retrait de la
            // description) — le brouillon se construit directement depuis
            // l'arbre déjà en mémoire.
            const r: DraftNode = {
                key: nouvelleKey(),
                id: existingNode.id,
                name: existingNode.name,
                storagePoint: existingNode.storagePoint,
                capaciteMax: existingNode.capaciteMax,
                modeContrainte: existingNode.modeContrainte,
                typeDocumentId: existingNode.typeDocumentAccepteId,
                dossierId: existingNode.dossierId,
                nombreDocuments: existingNode.nombreDocuments,
                children: existingNode.children.map(versDraftExistant),
            };
            setRoot(r);
            setFocusedKey(r.key);
        }
    }, [isOpen, mode, existingNode]);

    const handlePatch = (key: string, patch: Partial<DraftNode>) => setRoot(r => r && mettreAJourNoeud(r, key, patch));
    const handleAddChild = (parentKey: string) => setRoot(r => r && ajouterEnfant(r, parentKey));
    const handleRemove = (key: string) => setRoot(r => r && supprimerDescendant(r, key));

    const handleSubmit = async () => {
        if (!root) return;
        if (aUnNomManquant(root)) {
            notify.error('Chaque nœud doit avoir un nom');
            return;
        }
        if (aUneContrainteIncomplete(root)) {
            notify.error('Choisissez un type de document ou un dossier pour chaque point de stockage');
            return;
        }
        setSaving(true);
        try {
            if (mode === 'create') {
                const cree = await creerArborescence({
                    uniteOrganisationnelleId: uoId,
                    parentId,
                    node: versRequeteNode(root),
                });
                notify.success(`"${cree.name}" créé — ${compterNoeuds(root)} emplacement(s)`);
                onSaved(cree);
            } else {
                const maj = await mettreAJourArborescence(root.id!, versRequeteNode(root));
                notify.success(`"${maj.name}" mis à jour`);
                onSaved(maj);
            }
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'enregistrement");
        } finally {
            setSaving(false);
        }
    };

    const titre = mode === 'create'
        ? `Créer un emplacement${parentLabel ? ` — sous "${parentLabel}"` : ' — à la racine'}`
        : `Modifier "${existingNode?.name ?? ''}"`;

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={titre} size="large">
            <div className="pl-form">
                {!root ? (
                    <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement…</div>
                ) : (
                    <>
                        <div className="tbo-wrap">
                            <div className="tbo-root">
                                <EmplacementNodeCard
                                    node={root}
                                    isRoot
                                    racineDeLUO={mode === 'create' ? parentId === null : existingEstRacine}
                                    uoId={uoId}
                                    typesUO={typesUO}
                                    aDesDossiers={aDesDossiers}
                                    onEditCapacite={onEditCapacite}
                                    onEditContrainte={onEditContrainte}
                                    focusedKey={focusedKey}
                                    onFocus={setFocusedKey}
                                    onPatch={handlePatch}
                                    onAddChild={handleAddChild}
                                    onRemove={handleRemove}
                                />
                            </div>
                        </div>

                        <div className="pl-form-actions">
                            <button type="button" className="sidebar-btn" disabled={saving} onClick={handleSubmit}>
                                {saving
                                    ? <><i className="fa-solid fa-spinner fa-spin" /> Enregistrement…</>
                                    : (mode === 'create' ? 'Créer' : 'Enregistrer')}
                            </button>
                        </div>
                    </>
                )}
            </div>
        </Modal>
    );
}

/**
 * Une carte de nœud dans l'organigramme — voir recap ("nœud central avec ses
 * enfants") : nœud parent au-dessus, enfants reliés par des traits en
 * dessous (voir .tbo-children/.tbo-branch dans PhysicalLocationsPanel.css),
 * TOUS les niveaux toujours affichés (aucun repliement). Cliquer une carte la
 * met "en focus" (léger surlignage) sans rien cacher d'autre. Chaque carte
 * visible porte ses propres actions (+ enfant à côté du badge, supprimer si
 * applicable).
 */
function EmplacementNodeCard({ node, isRoot, racineDeLUO = false, uoId, typesUO, aDesDossiers, onEditCapacite, onEditContrainte, focusedKey, onFocus, onPatch, onAddChild, onRemove }: {
    node: DraftNode;
    isRoot: boolean;
    /** true si ce nœud est une racine de l'UO (pas seulement la racine du brouillon) : toujours un nœud chemin. */
    racineDeLUO?: boolean;
    uoId: number;
    typesUO: TypeDocumentDto[];
    aDesDossiers: boolean;
    /** Raccourcis "Modifier" — fournis par le parent en mode="update"
     *  uniquement (voir Javadoc EmplacementTreeModal), propagés à chaque
     *  niveau de l'organigramme. */
    onEditCapacite?: (cible: CibleEditionExistante) => void;
    onEditContrainte?: (cible: CibleEditionExistante) => void;
    focusedKey: string | null;
    onFocus: (key: string) => void;
    onPatch: (key: string, patch: Partial<DraftNode>) => void;
    onAddChild: (parentKey: string) => void;
    onRemove: (key: string) => void;
}) {
    const notify = useNotify();
    const estExistant = node.id !== undefined;
    // Rien n'est encore créé pour un nouveau nœud non-racine : supprimable du
    // brouillon librement. Un nœud existant ou la racine elle-même ne se
    // supprime jamais depuis ce modal (voir Javadoc du composant).
    const supprimable = !isRoot && !estExistant;
    const peutDevenirStockage = node.children.length === 0;
    // La racine d'une arborescence qu'on est en train de créer ne peut
    // jamais devenir un point de stockage (retour utilisateur 10/2026) —
    // forcée "chemin" dès l'initialisation (voir useEffect mode="create"
    // plus haut), verrouillée ici pour que rien ne permette de revenir en
    // arrière. Un nœud racine EXISTANT (mode="update") n'est pas concerné :
    // il a pu être créé avant cette règle, ou la règle serveur sur les
    // nœuds existants (changerTypeStockage) suffit déjà à le protéger.
    const racineNouvelleVerrouillee = isRoot && (!estExistant || racineDeLUO);
    const type = node.storagePoint ? 'stockage' : 'chemin';
    // Le changement de type d'un nœud EXISTANT part vers le serveur tout de
    // suite (voir Javadoc du composant) — ce spinner local évite un double
    // clic pendant l'aller-retour.
    const [convertingType, setConvertingType] = useState(false);
    // Capacité/contrainte d'un NOUVEAU point de stockage — mêmes champs que
    // pour un nœud existant (onEditCapacite/onEditContrainte), mais ouverts
    // en local (rien à sauvegarder côté serveur avant "Créer"/"Enregistrer" :
    // pas encore d'id à passer à definirCapacite/definirContrainte). Chaque
    // carte de nœud a ses propres modaux — plusieurs nouveaux points de
    // stockage dans le même brouillon ne se marchent pas dessus.
    const [capaciteModalOpen, setCapaciteModalOpen] = useState(false);
    const [contrainteModalOpen, setContrainteModalOpen] = useState(false);
    // Descripteur envoyé à onEditCapacite/onEditContrainte pour CE nœud —
    // uniquement pertinent si estExistant (id garanti dans ce cas).
    const cibleEdition: CibleEditionExistante | null = estExistant ? {
        id: node.id!,
        name: node.name,
        nombreDocuments: node.nombreDocuments,
        capaciteMax: node.capaciteMax,
        modeContrainte: node.modeContrainte,
        typeDocumentAccepteId: node.typeDocumentId,
        dossierId: node.dossierId,
    } : null;

    // Un seul contrôle de type — pill colorée (icône + libellé texte, jamais
    // l'icône seule : trop ambiguë à cette échelle, voir recap) — toujours
    // cliquable, qu'il s'agisse d'un nouveau nœud (patch local, envoyé avec
    // le reste à "Créer"/"Enregistrer") ou d'un nœud existant (appel
    // immédiat à changerTypeStockage, voir Javadoc du composant).
    const handleToggleType = async () => {
        if (racineNouvelleVerrouillee) return;
        if (!node.storagePoint && !peutDevenirStockage) return;
        if (!estExistant) {
            // Repasser en chemin efface tout ce qui avait été réglé pour le stockage (capacité, contrainte) : un
            // chemin n'en a pas, rien ne doit rester en arrière-plan.
            onPatch(node.key, node.storagePoint
                ? { storagePoint: false, capaciteMax: null, modeContrainte: 'LIBRE', typeDocumentId: null, dossierId: null }
                : { storagePoint: true });
            return;
        }
        setConvertingType(true);
        try {
            const updated = await changerTypeStockage(node.id!, !node.storagePoint);
            onPatch(node.key, { storagePoint: updated.storagePoint });
            notify.success(`"${updated.name}" — devient ${updated.storagePoint ? 'point de stockage' : 'nœud chemin'}`);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du changement de type');
        } finally {
            setConvertingType(false);
        }
    };
    const pillTitle = racineNouvelleVerrouillee
        ? 'La racine d\'un nouvel emplacement est toujours un nœud chemin'
        : node.storagePoint
            ? 'Point de stockage — cliquer pour passer en chemin'
            : peutDevenirStockage
                ? 'Nœud chemin — cliquer pour passer en stockage'
                : 'Nœud chemin — impossible de passer en stockage (a des enfants)';

    return (
        <div className="tbo-branch-content">
            <div
                className={`tbo-card ${type} ${focusedKey === node.key ? 'focused' : ''}`}
                onClick={() => onFocus(node.key)}
            >
                <div className="tbo-card-header">
                    <span className="tbo-icon-chip">
                        <i className={`fa-solid ${node.storagePoint ? 'fa-box' : 'fa-diagram-project'}`} />
                    </span>
                    <input
                        type="text"
                        className="tb-name-input"
                        placeholder="Nom"
                        value={node.name}
                        onChange={(e) => onPatch(node.key, { name: e.target.value })}
                    />
                </div>

                <div className="tbo-card-controls">
                    <button type="button" className="tbo-type-pill clickable"
                        disabled={convertingType || racineNouvelleVerrouillee || (!node.storagePoint && !peutDevenirStockage)}
                        title={pillTitle} onClick={handleToggleType}>
                        {convertingType ? <i className="fa-solid fa-spinner fa-spin" /> : (node.storagePoint ? 'Stockage' : 'Chemin')}
                    </button>
                    {!node.storagePoint && (
                        <button type="button" className="tbo-footer-btn" title="Ajouter un emplacement" onClick={() => onAddChild(node.key)}>
                            <i className="fa-solid fa-plus" /> Emplacement
                        </button>
                    )}
                    {supprimable && (
                        <button type="button" className="tbo-footer-btn danger" title="Supprimer" onClick={() => onRemove(node.key)}>
                            <i className="fa-solid fa-trash" />
                        </button>
                    )}
                </div>

                {/* Capacité + contrainte d'acceptation d'un NOUVEAU point de
                    stockage — mêmes deux boutons "Capacité"/"Contrainte" que
                    pour un nœud existant (voir bloc estExistant ci-dessous),
                    ouvrant chacun un modal local (rien envoyé au serveur avant
                    "Créer", voir DraftNode) plutôt qu'un panneau toujours
                    déployé dans la carte — évite en particulier que le
                    sélecteur de dossier (arbre complet avec recherche) ne
                    fasse déborder la petite carte. */}
                {node.storagePoint && !estExistant && (
                    <div className="tbo-constraint-summary" onClick={e => e.stopPropagation()}>
                        <p>
                            {node.capaciteMax != null ? `${node.capaciteMax} document(s) max.` : 'Illimitée'}
                            {' — '}
                            {node.modeContrainte === 'LIBRE' && 'contrainte à définir'}
                            {node.modeContrainte === 'TYPE_UNIQUE' && (
                                typesUO.find(t => t.id === node.typeDocumentId)?.nom ?? 'type — à choisir'
                            )}
                            {node.modeContrainte === 'DOSSIER' && (node.dossierId ? 'dossier choisi' : 'dossier — à choisir')}
                        </p>
                        <div className="tbo-summary-actions">
                            <button type="button" className="tbo-footer-btn" onClick={() => setCapaciteModalOpen(true)}>
                                <i className="fa-solid fa-pen" /> Capacité
                            </button>
                            <button type="button" className="tbo-footer-btn" onClick={() => setContrainteModalOpen(true)}>
                                <i className="fa-solid fa-pen" /> Choisir
                            </button>
                        </div>

                        {capaciteModalOpen && (
                            <Modal isOpen onClose={() => setCapaciteModalOpen(false)} title={`Capacité — "${node.name || 'nouveau nœud'}"`}>
                                <div className="pl-form">
                                    <label>
                                        <p style={{ marginBottom: '0.4rem' }}>Nombre maximal de documents — laisser vide pour aucune limite.</p>
                                        <input
                                            type="number"
                                            min={1}
                                            placeholder="Illimitée"
                                            value={node.capaciteMax ?? ''}
                                            onChange={(e) => onPatch(node.key, {
                                                capaciteMax: e.target.value === '' ? null : Math.max(1, Number(e.target.value)),
                                            })}
                                        />
                                    </label>
                                    <div className="pl-form-actions">
                                        <button type="button" className="sidebar-btn" onClick={() => setCapaciteModalOpen(false)}>OK</button>
                                    </div>
                                </div>
                            </Modal>
                        )}

                        {contrainteModalOpen && (
                            <Modal isOpen onClose={() => setContrainteModalOpen(false)} title={`Contrainte — "${node.name || 'nouveau nœud'}"`}>
                                <div className="pl-form">
                                    <div className="tbo-constraint-panel" style={{ borderTop: 'none', paddingTop: 0 }}>
                                        <label className="tbo-constraint-field">
                                            <span>Accepte</span>
                                            <select
                                                value={node.modeContrainte}
                                                onChange={(e) => onPatch(node.key, {
                                                    modeContrainte: e.target.value as DraftNode['modeContrainte'],
                                                    typeDocumentId: null,
                                                    dossierId: null,
                                                })}
                                            >
                                                <option value="LIBRE" disabled hidden>— Choisir —</option>
                                                <option value="TYPE_UNIQUE" disabled={typesUO.length === 0}>
                                                    Type de document{typesUO.length === 0 ? ' (aucun type existant)' : ''}
                                                </option>
                                                <option value="DOSSIER" disabled={!aDesDossiers}>
                                                    Dossier{!aDesDossiers ? ' (aucun dossier existant)' : ''}
                                                </option>
                                            </select>
                                        </label>
                                        {node.modeContrainte === 'TYPE_UNIQUE' && (
                                            <select
                                                className="tbo-constraint-field"
                                                value={node.typeDocumentId ?? ''}
                                                onChange={(e) => onPatch(node.key, { typeDocumentId: e.target.value ? Number(e.target.value) : null })}
                                            >
                                                <option value="">— Choisir un type —</option>
                                                {typesUO.map(t => <option key={t.id} value={t.id}>{t.nom}</option>)}
                                            </select>
                                        )}
                                        {node.modeContrainte === 'DOSSIER' && (
                                            <DossierTreePicker
                                                uoId={uoId}
                                                value={node.dossierId}
                                                onChange={(id) => onPatch(node.key, { dossierId: id })}
                                            />
                                        )}
                                    </div>
                                    <div className="pl-form-actions">
                                        <button type="button" className="sidebar-btn" onClick={() => setContrainteModalOpen(false)}>OK</button>
                                    </div>
                                </div>
                            </Modal>
                        )}
                    </div>
                )}

                {node.storagePoint && estExistant && (
                    <div className="tbo-constraint-summary" onClick={e => e.stopPropagation()}>
                        <p>
                            {node.modeContrainte === 'LIBRE' && 'Accepte tout document'}
                            {node.modeContrainte === 'TYPE_UNIQUE' && 'Type unique accepté'}
                            {node.modeContrainte === 'DOSSIER' && 'Dossier unique accepté'}
                            {node.capaciteMax != null && ` — ${node.capaciteMax} document(s) max.`}
                        </p>
                        {cibleEdition && (onEditCapacite || onEditContrainte) && (
                            <div className="tbo-summary-actions">
                                {onEditCapacite && (
                                    <button type="button" className="tbo-footer-btn" onClick={() => onEditCapacite(cibleEdition)}>
                                        <i className="fa-solid fa-pen" /> Capacité
                                    </button>
                                )}
                                {onEditContrainte && (
                                    <button type="button" className="tbo-footer-btn" onClick={() => onEditContrainte(cibleEdition)}>
                                        <i className="fa-solid fa-pen" /> Choisir
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                )}
            </div>
            {node.children.length > 0 && (
                <div className="tbo-children" style={{ gridTemplateColumns: `repeat(${node.children.length}, 1fr)` }}>
                    {node.children.map(c => (
                        <div key={c.key} className="tbo-branch">
                            <EmplacementNodeCard
                                node={c}
                                isRoot={false}
                                uoId={uoId}
                                typesUO={typesUO}
                                aDesDossiers={aDesDossiers}
                                onEditCapacite={onEditCapacite}
                                onEditContrainte={onEditContrainte}
                                focusedKey={focusedKey}
                                onFocus={onFocus}
                                onPatch={onPatch}
                                onAddChild={onAddChild}
                                onRemove={onRemove}
                            />
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

export default EmplacementTreeModal;
