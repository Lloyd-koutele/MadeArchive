import { useState, useMemo, useEffect } from 'react';
import '../Style/organisation/UOTreeSelect.css';

interface UONode {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

interface UOTreeSelectProps {
    nodes: UONode[];
    value: number | null;
    onChange: (id: number) => void;
    disabled?: boolean;
}

const toNum = (v: unknown): number | null => {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isNaN(n) ? null : n;
};

function UOTreeSelect({ nodes, value, onChange, disabled = false }: UOTreeSelectProps) {

    const normalizedNodes = useMemo(() => {
        return nodes.map(n => ({
            ...n,
            id: toNum(n.id) as number,
            parentId: toNum(n.parentId)
        }));
    }, [nodes]);

    const roots = useMemo(
        () => normalizedNodes.filter(n => n.parentId === null),
        [normalizedNodes]
    );

    const childrenByParent = useMemo(() => {
        const map = new Map<number, UONode[]>();
        normalizedNodes.forEach(n => {
            if (n.parentId === null) return;
            if (!map.has(n.parentId)) map.set(n.parentId, []);
            map.get(n.parentId)!.push(n);
        });
        return map;
    }, [normalizedNodes]);

    // Ancêtres de la sélection courante — dépliés automatiquement pour qu'une
    // valeur déjà choisie reste visible sans clic manuel.
    const ancestorsOfValue = useMemo(() => {
        const byId = new Map(normalizedNodes.map(n => [n.id, n]));
        const path = new Set<number>();
        let cursor = value !== null ? byId.get(toNum(value) as number) : undefined;
        while (cursor) {
            path.add(cursor.id);
            cursor = cursor.parentId !== null ? byId.get(cursor.parentId) : undefined;
        }
        return path;
    }, [normalizedNodes, value]);

    // Replié par défaut — l'utilisateur déplie "au besoin" plutôt que de voir
    // tout l'arbre d'un coup (contrairement à la sidebar de navigation).
    const [expanded, setExpanded] = useState<Set<number>>(new Set(ancestorsOfValue));

    useEffect(() => {
        setExpanded(prev => {
            const next = new Set(prev);
            ancestorsOfValue.forEach(id => next.add(id));
            return next;
        });
    }, [ancestorsOfValue]);

    const toggle = (id: number, e: React.MouseEvent) => {
        e.preventDefault();
        e.stopPropagation();
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const renderNode = (node: UONode, depth: number) => {
        const children = childrenByParent.get(node.id) || [];
        const hasChildren = children.length > 0;
        const isExpanded = expanded.has(node.id);
        const isSelected = value !== null && toNum(value) === node.id;

        return (
            <div key={node.id} className="uo-select-branch">
                <label
                    className={`uo-select-node ${isSelected ? 'selected' : ''}`}
                    style={{ paddingLeft: `${depth * 1.1 + 0.5}rem` }}
                >
                    {hasChildren ? (
                        <button
                            type="button"
                            className="uo-select-toggle"
                            onClick={(e) => toggle(node.id, e)}
                            aria-label={isExpanded ? 'Réduire' : 'Développer'}
                        >
                            {isExpanded ? '▾' : '▸'}
                        </button>
                    ) : (
                        <span className="uo-select-toggle-spacer" />
                    )}
                    <input
                        type="radio"
                        name="uo-select"
                        checked={isSelected}
                        onChange={() => onChange(node.id)}
                        disabled={disabled}
                    />
                    <span className="uo-select-icon" aria-hidden="true">📁</span>
                    <span className="uo-select-text">{node.nom}</span>
                </label>
                {hasChildren && isExpanded && (
                    <div className="uo-select-children">
                        {children.map(child => renderNode(child, depth + 1))}
                    </div>
                )}
            </div>
        );
    };

    if (roots.length === 0) {
        return <p className="uo-select-empty">Aucune unité organisationnelle disponible</p>;
    }

    return (
        <div className="uo-select-tree">
            {roots.map(root => renderNode(root, 0))}
        </div>
    );
}

export default UOTreeSelect;