import { useState, useEffect, useMemo } from 'react';
import { getArbreDossiers } from '../services/organisation/DossierService';
import type { DossierArbreNodeDto } from '../services/organisation/DossierService';
import '../Style/organisation/DossierTreePicker.css';

interface DossierTreePickerProps {
    uoId: number;
    /** null = aucun dossier choisi. */
    value: number | null;
    onChange: (id: number | null) => void;
}

/**
 * Sélecteur de dossier cible (ex : à l'archivage, voir ImportDocuments.tsx) —
 * arbre complet de l'UO chargé en un seul aller-retour (voir
 * DossierService.getArbreDossiers côté serveur), plié/déplié niveau par
 * niveau, avec un filtre par nom qui révèle automatiquement la branche menant
 * à chaque résultat (jamais ses propres descendants — on cherche un nom, pas
 * tout un sous-arbre).
 */
function DossierTreePicker({ uoId, value, onChange }: DossierTreePickerProps) {
    const [noeuds, setNoeuds] = useState<DossierArbreNodeDto[]>([]);
    const [loading, setLoading] = useState(false);
    const [filtre, setFiltre] = useState('');
    const [expanded, setExpanded] = useState<Set<number>>(new Set());

    useEffect(() => {
        setLoading(true);
        getArbreDossiers(uoId)
            // spring.jackson.default-property-inclusion=non_null (voir
            // application.properties) OMET carrément parentId du JSON quand il
            // vaut null côté serveur (dossier racine), plutôt que d'envoyer
            // "parentId":null — il arrive donc ici en `undefined`, jamais en
            // `null`. Normalisé une fois ici pour que tout le reste du composant
            // (childrenByParent, parentById...) puisse compter sur `null`
            // strictement — même pitfall déjà contourné par UOTree.toNum.
            .then(data => setNoeuds(data.map(n => ({ ...n, parentId: n.parentId ?? null }))))
            .catch(() => setNoeuds([]))
            .finally(() => setLoading(false));
    }, [uoId]);

    const childrenByParent = useMemo(() => {
        const map = new Map<number | null, DossierArbreNodeDto[]>();
        noeuds.forEach(n => {
            if (!map.has(n.parentId)) map.set(n.parentId, []);
            map.get(n.parentId)!.push(n);
        });
        for (const liste of map.values()) liste.sort((a, b) => a.nom.localeCompare(b.nom));
        return map;
    }, [noeuds]);

    const parentById = useMemo(
        () => new Map(noeuds.map(n => [n.id, n.parentId])),
        [noeuds]
    );

    const filtreNormalise = filtre.trim().toLowerCase();
    // Nœuds à afficher pendant un filtre actif : chaque résultat ET toute sa
    // chaîne d'ancêtres (pour rester navigable jusqu'à la racine) — jamais
    // ses descendants.
    const idsVisibles = useMemo(() => {
        if (!filtreNormalise) return null;
        const visibles = new Set<number>();
        noeuds.forEach(n => {
            if (n.nom.toLowerCase().includes(filtreNormalise)) {
                let courant: number | null = n.id;
                while (courant != null) {
                    visibles.add(courant);
                    courant = parentById.get(courant) ?? null;
                }
            }
        });
        return visibles;
    }, [filtreNormalise, noeuds, parentById]);

    const toggleExpand = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const renderNode = (node: DossierArbreNodeDto, depth: number) => {
        if (idsVisibles && !idsVisibles.has(node.id)) return null;

        const enfants = childrenByParent.get(node.id) ?? [];
        const aDesEnfants = enfants.length > 0;
        // Un filtre actif force l'ouverture des branches menant à un résultat.
        const estOuvert = idsVisibles ? true : expanded.has(node.id);
        const estSelectionne = value === node.id;

        return (
            <div key={node.id}>
                <div
                    className={`dossier-picker-row ${estSelectionne ? 'selected' : ''}`}
                    style={{ paddingLeft: `${depth * 1.1 + 0.3}rem` }}
                >
                    {aDesEnfants ? (
                        <button
                            type="button"
                            className="dossier-picker-toggle"
                            onClick={() => toggleExpand(node.id)}
                            aria-label={estOuvert ? `Replier ${node.nom}` : `Déplier ${node.nom}`}
                        >
                            {estOuvert ? '▾' : '▸'}
                        </button>
                    ) : (
                        <span className="dossier-picker-toggle-spacer" />
                    )}
                    <button
                        type="button"
                        className="dossier-picker-label"
                        onClick={() => onChange(estSelectionne ? null : node.id)}
                    >
                        <i className="fa-solid fa-folder" />
                        <span>{node.nom}</span>
                    </button>
                </div>
                {aDesEnfants && estOuvert && enfants.map(enfant => renderNode(enfant, depth + 1))}
            </div>
        );
    };

    const racines = childrenByParent.get(null) ?? [];
    const selectedNode = value != null ? noeuds.find(n => n.id === value) ?? null : null;

    return (
        <div className="dossier-picker">
            {value != null && (
                <div className="dossier-picker-selection">
                    <i className="fa-solid fa-folder" />
                    <span>{selectedNode?.nom ?? '…'}</span>
                    <button
                        type="button"
                        onClick={() => onChange(null)}
                        aria-label="Retirer le dossier cible"
                        title="Retirer le dossier cible"
                    >
                        <i className="fa-solid fa-xmark" />
                    </button>
                </div>
            )}
            <input
                type="text"
                className="dossier-picker-filtre"
                placeholder="Rechercher un dossier par nom…"
                aria-label="Filtrer les dossiers par nom"
                value={filtre}
                onChange={e => setFiltre(e.target.value)}
            />
            <div className="dossier-picker-tree">
                {loading ? (
                    <p className="dossier-picker-empty">Chargement…</p>
                ) : racines.length === 0 ? (
                    <p className="dossier-picker-empty">Aucun dossier dans cette UO.</p>
                ) : idsVisibles && idsVisibles.size === 0 ? (
                    <p className="dossier-picker-empty">Aucun dossier ne correspond à ce filtre.</p>
                ) : (
                    racines.map(r => renderNode(r, 0))
                )}
            </div>
        </div>
    );
}

export default DossierTreePicker;
