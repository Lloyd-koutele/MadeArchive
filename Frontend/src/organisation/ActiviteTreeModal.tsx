import { useEffect, useState } from 'react';
import Modal from '../Page/Modal';
import {
    creerArborescencePlanClassement, mettreAJourArborescencePlanClassement,
} from '../services/organisation/PlanClassementService';
import type { PlanClassementBrouillon, PlanClassementNoeudDto } from '../services/organisation/PlanClassementService';
import { useNotify } from '../notifications/NotificationProvider';
// Mêmes styles que l'organigramme de création des emplacements physiques (.tbo-*, .tb-name-input, .pl-form).
import '../Style/organisation/PhysicalLocationsPanel.css';

/** Nœud de brouillon local — `key` sert à le retrouver dans l'arbre immuable ; `id` absent = nouvelle activité. */
interface DraftNode {
    key: string;
    id?: number;
    /** Code d'une activité EXISTANTE (affiché, jamais modifiable) ; une nouvelle activité reçoit son code du serveur. */
    code?: string;
    libelle: string;
    verrouille: boolean;
    children: DraftNode[];
}

const nouvelleKey = () =>
    (typeof crypto !== 'undefined' && crypto.randomUUID) ? crypto.randomUUID() : `n${Date.now()}-${Math.random()}`;

const noeudVide = (): DraftNode => ({ key: nouvelleKey(), libelle: '', verrouille: false, children: [] });

const versDraft = (n: PlanClassementNoeudDto): DraftNode => ({
    key: nouvelleKey(), id: n.id, code: n.code, libelle: n.libelle, verrouille: n.verrouille,
    children: n.children.map(versDraft),
});

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

const aUnLibelleManquant = (n: DraftNode): boolean => !n.libelle.trim() || n.children.some(aUnLibelleManquant);

const compterNouveaux = (n: DraftNode): number =>
    (n.id === undefined ? 1 : 0) + n.children.reduce((t, c) => t + compterNouveaux(c), 0);

const versRequete = (n: DraftNode): PlanClassementBrouillon => ({
    id: n.id, libelle: n.libelle.trim(), children: n.children.map(versRequete),
});

interface Props {
    isOpen: boolean;
    onClose: () => void;
    uoId: number;
    mode: 'create' | 'update';
    /** mode="create" — activité sous laquelle accrocher la nouvelle (null = racine du plan). */
    parentId?: number | null;
    parentLabel?: string | null;
    /** mode="update" — l'activité cliquée avec sa descendance actuelle. */
    existingNode?: PlanClassementNoeudDto;
    onSaved: () => void;
}

/**
 * Organigramme de création/modification d'activités — même expérience que celui des emplacements physiques :
 * UNE racine avec toute sa descendance dans un seul arbre éditable, envoyée en un seul appel.
 * Les codes (01, 01.1, 01.1.1…) sont attribués par le serveur, jamais saisis.
 * Une activité existante verrouillée (des documents y sont classés) garde son libellé, mais on peut toujours lui
 * ajouter des sous-activités. On ne supprime pas d'activité existante d'ici (action dédiée, avec confirmation).
 */
function ActiviteTreeModal({ isOpen, onClose, uoId, mode, parentId = null, parentLabel = null, existingNode, onSaved }: Props) {
    const notify = useNotify();
    const [root, setRoot] = useState<DraftNode | null>(null);
    const [saving, setSaving] = useState(false);
    const [focusedKey, setFocusedKey] = useState<string | null>(null);

    useEffect(() => {
        if (!isOpen) return;
        const r = mode === 'update' && existingNode ? versDraft(existingNode) : noeudVide();
        setRoot(r);
        setFocusedKey(r.key);
    }, [isOpen, mode, existingNode]);

    const handleSubmit = async () => {
        if (!root) return;
        if (aUnLibelleManquant(root)) {
            notify.error('Chaque activité doit avoir un libellé');
            return;
        }
        setSaving(true);
        try {
            if (mode === 'create') {
                const cree = await creerArborescencePlanClassement(uoId, parentId, versRequete(root));
                notify.success(`"${cree.code} ${cree.libelle}" créée — ${compterNouveaux(root)} activité(s)`);
            } else {
                const maj = await mettreAJourArborescencePlanClassement(root.id!, versRequete(root));
                notify.success(`"${maj.code} ${maj.libelle}" mise à jour`);
            }
            onSaved();
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'enregistrement");
        } finally {
            setSaving(false);
        }
    };

    const titre = mode === 'create'
        ? `Créer une activité${parentLabel ? ` — sous "${parentLabel}"` : ' — à la racine'}`
        : `Modifier "${existingNode ? `${existingNode.code} ${existingNode.libelle}` : ''}"`;

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={titre} size="large">
            <div className="pl-form">
                {!root ? (
                    <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement…</div>
                ) : (
                    <>
                        <div className="tbo-wrap">
                            <div className="tbo-root">
                                <ActiviteCard
                                    node={root}
                                    isRoot
                                    focusedKey={focusedKey}
                                    onFocus={setFocusedKey}
                                    onPatch={(key, patch) => setRoot(r => r && mettreAJourNoeud(r, key, patch))}
                                    onAddChild={key => setRoot(r => r && ajouterEnfant(r, key))}
                                    onRemove={key => setRoot(r => r && supprimerDescendant(r, key))}
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

function ActiviteCard({ node, isRoot, focusedKey, onFocus, onPatch, onAddChild, onRemove }: {
    node: DraftNode;
    isRoot: boolean;
    focusedKey: string | null;
    onFocus: (key: string) => void;
    onPatch: (key: string, patch: Partial<DraftNode>) => void;
    onAddChild: (parentKey: string) => void;
    onRemove: (key: string) => void;
}) {
    const estExistant = node.id !== undefined;
    // Une nouvelle activité (non-racine) se retire librement du brouillon ; une existante ou la racine, jamais d'ici.
    const supprimable = !isRoot && !estExistant;

    return (
        <div className="tbo-branch-content">
            <div
                className={`tbo-card chemin ${focusedKey === node.key ? 'focused' : ''}`}
                onClick={() => onFocus(node.key)}
            >
                <div className="tbo-card-header">
                    <span className="tbo-icon-chip" title={estExistant ? 'Code de l\'activité' : 'Code attribué automatiquement'}>
                        {estExistant ? node.code : <i className="fa-solid fa-sitemap" />}
                    </span>
                    <input
                        type="text"
                        className="tb-name-input"
                        placeholder="Libellé"
                        value={node.libelle}
                        maxLength={150}
                        disabled={node.verrouille}
                        title={node.verrouille ? 'Verrouillée : des documents sont classés dans cette activité' : undefined}
                        onChange={(e) => onPatch(node.key, { libelle: e.target.value })}
                    />
                    {node.verrouille && <i className="fa-solid fa-lock" title="Verrouillée : des documents y sont classés" />}
                </div>

                <div className="tbo-card-controls">
                    <button type="button" className="tbo-footer-btn" title="Ajouter une sous-activité" onClick={() => onAddChild(node.key)}>
                        <i className="fa-solid fa-plus" /> Activité
                    </button>
                    {supprimable && (
                        <button type="button" className="tbo-footer-btn danger" title="Retirer" onClick={() => onRemove(node.key)}>
                            <i className="fa-solid fa-trash" />
                        </button>
                    )}
                </div>
            </div>
            {node.children.length > 0 && (
                <div className="tbo-children" style={{ gridTemplateColumns: `repeat(${node.children.length}, 1fr)` }}>
                    {node.children.map(c => (
                        <div key={c.key} className="tbo-branch">
                            <ActiviteCard
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

export default ActiviteTreeModal;
