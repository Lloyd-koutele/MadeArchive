import { useState, useEffect } from 'react';
import Modal from '../Page/Modal';
import {
    creerArborescence,
    mettreAJourArborescence,
    changerTypeStockage,
} from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationTreeNodeDto, PhysicalLocationNodeDto } from '../services/organisation/PhysicalLocationService';
import { useNotify } from '../notifications/NotificationProvider';
// .pl-form/.pl-form-actions — mêmes styles que l'ancien formulaire simple,
// réutilisés tels quels. .tb-*/.tbo-* : styles propres à l'arbre de
// brouillon (voir PhysicalLocationsPanel.css).
import '../Style/organisation/PhysicalLocationsPanel.css';

/**
 * Nœud de brouillon local — jamais envoyé tel quel, converti en
 * PhysicalLocationTreeNodeDto à la soumission. `key` sert uniquement à
 * retrouver/modifier un nœud dans l'arbre immuable côté client. `id` absent
 * = nouveau nœud (jamais en base) ; défini = nœud EXISTANT (modification
 * uniquement) — voir Javadoc backend PhysicalLocationTreeNodeDto. Pas de
 * description ici — jugée superflue/encombrante pour ce constructeur
 * d'arborescence, retirée de l'interface (reste modifiable individuellement
 * via l'ancien formulaire simple si jamais utile).
 */
interface DraftNode {
    key: string;
    id?: string;
    name: string;
    storagePoint: boolean;
    children: DraftNode[];
}

const nouvelleKey = () =>
    (typeof crypto !== 'undefined' && crypto.randomUUID) ? crypto.randomUUID() : `n${Date.now()}-${Math.random()}`;

const noeudVide = (): DraftNode =>
    ({ key: nouvelleKey(), name: '', storagePoint: true, children: [] });

/** Convertit récursivement l'arbre RÉEL (lecture) en brouillon éditable. */
const versDraftExistant = (n: PhysicalLocationNodeDto): DraftNode => ({
    key: nouvelleKey(),
    id: n.id,
    name: n.name,
    storagePoint: n.storagePoint,
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

function compterNoeuds(node: DraftNode): number {
    return 1 + node.children.reduce((total, c) => total + compterNoeuds(c), 0);
}

function versRequeteNode(n: DraftNode): PhysicalLocationTreeNodeDto {
    return {
        id: n.id,
        name: n.name.trim(),
        storagePoint: n.storagePoint,
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
 *   dédié, avec sa confirmation.
 */
function EmplacementTreeModal({ isOpen, onClose, uoId, mode, parentId = null, parentLabel = null, existingNode, onSaved }: EmplacementTreeModalProps) {
    const notify = useNotify();
    const [root, setRoot] = useState<DraftNode | null>(null);
    const [saving, setSaving] = useState(false);
    // Nœud "en focus" (dernier cliqué) — un simple surlignage visuel, voir
    // recap : rien ne se replie/disparaît, tout l'arbre reste affiché.
    const [focusedKey, setFocusedKey] = useState<string | null>(null);

    useEffect(() => {
        if (!isOpen) return;

        if (mode === 'create') {
            const r = noeudVide();
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
function EmplacementNodeCard({ node, isRoot, focusedKey, onFocus, onPatch, onAddChild, onRemove }: {
    node: DraftNode;
    isRoot: boolean;
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
    const type = node.storagePoint ? 'stockage' : 'chemin';
    // Le changement de type d'un nœud EXISTANT part vers le serveur tout de
    // suite (voir Javadoc du composant) — ce spinner local évite un double
    // clic pendant l'aller-retour.
    const [convertingType, setConvertingType] = useState(false);

    // Un seul contrôle de type — pill colorée (icône + libellé texte, jamais
    // l'icône seule : trop ambiguë à cette échelle, voir recap) — toujours
    // cliquable, qu'il s'agisse d'un nouveau nœud (patch local, envoyé avec
    // le reste à "Créer"/"Enregistrer") ou d'un nœud existant (appel
    // immédiat à changerTypeStockage, voir Javadoc du composant).
    const handleToggleType = async () => {
        if (!node.storagePoint && !peutDevenirStockage) return;
        if (!estExistant) {
            onPatch(node.key, { storagePoint: !node.storagePoint });
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
    const pillTitle = node.storagePoint
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
                        disabled={convertingType || (!node.storagePoint && !peutDevenirStockage)}
                        title={pillTitle} onClick={handleToggleType}>
                        {convertingType ? <i className="fa-solid fa-spinner fa-spin" /> : (node.storagePoint ? 'Stockage' : 'Chemin')}
                    </button>
                    {!node.storagePoint && (
                        <button type="button" className="tbo-footer-btn" title="Ajouter un enfant" onClick={() => onAddChild(node.key)}>
                            <i className="fa-solid fa-plus" /> Enfant
                        </button>
                    )}
                    {supprimable && (
                        <button type="button" className="tbo-footer-btn danger" title="Supprimer" onClick={() => onRemove(node.key)}>
                            <i className="fa-solid fa-trash" />
                        </button>
                    )}
                </div>
            </div>
            {node.children.length > 0 && (
                <div className="tbo-children" style={{ gridTemplateColumns: `repeat(${node.children.length}, 1fr)` }}>
                    {node.children.map(c => (
                        <div key={c.key} className="tbo-branch">
                            <EmplacementNodeCard
                                node={c}
                                isRoot={false}
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
