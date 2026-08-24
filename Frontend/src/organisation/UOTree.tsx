import { useState, useMemo, useEffect } from 'react';
import { TYPE_DOCUMENT_DRAG_MIME } from '../hooks/dragTypes';
import '../Style/organisation/UOTree.css';

interface UONode {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

interface UOTreeProps {
    nodes: UONode[];
    rootId: number;
    currentId: number;
    onSelect: (id: number) => void;
    canManage?: boolean;
    canCreateRoot?: boolean;
    onAddChild?: (parentId: number) => void;
    onMove?: (draggedId: number, targetId: number) => void;
    onDropTypeDocuments?: (targetUoId: number, payload: any[]) => void;
}

const toNum = (v: unknown): number | null => {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isNaN(n) ? null : n;
};

function UOTree({
    nodes,
    rootId,
    currentId,
    onSelect,
    canManage = false,
    canCreateRoot = true,
    onAddChild,
    onMove,
    onDropTypeDocuments
}: UOTreeProps) {

    const normalizedNodes = useMemo(() => {
        return nodes.map(n => ({
            ...n,
            id: toNum(n.id) as number,
            parentId: toNum(n.parentId)
        }));
    }, [nodes]);

    const childrenByParent = useMemo(() => {
        const map = new Map<number, UONode[]>();
        normalizedNodes.forEach(n => {
            if (n.parentId === null) return;
            if (!map.has(n.parentId)) map.set(n.parentId, []);
            map.get(n.parentId)!.push(n);
        });
        return map;
    }, [normalizedNodes]);

    const ancestorsOfCurrent = useMemo(() => {
        const byId = new Map(normalizedNodes.map(n => [n.id, n]));
        const path = new Set<number>();
        let cursor = byId.get(toNum(currentId) as number);
        while (cursor) {
            path.add(cursor.id);
            cursor = cursor.parentId !== null ? byId.get(cursor.parentId) : undefined;
        }
        return path;
    }, [normalizedNodes, currentId]);

    const allExpandableIds = useMemo(() => new Set(childrenByParent.keys()), [childrenByParent]);

    const [expanded, setExpanded] = useState<Set<number>>(new Set(allExpandableIds));

    useEffect(() => {
        setExpanded(prev => {
            const next = new Set(prev);
            allExpandableIds.forEach(id => next.add(id));
            return next;
        });
    }, [allExpandableIds]);

    const [draggedId, setDraggedId] = useState<number | null>(null);
    const [dragOverId, setDragOverId] = useState<number | null>(null);

    const isWithinSubtree = (subtreeRootId: number, id: number): boolean => {
        if (subtreeRootId === id) return true;
        const children = childrenByParent.get(subtreeRootId) || [];
        return children.some(c => isWithinSubtree(c.id, id));
    };

    const toggle = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const handleAddChild = (e: React.MouseEvent, nodeId: number) => {
        e.stopPropagation();
        onAddChild?.(nodeId);
        setExpanded(prev => new Set(prev).add(nodeId));
    };

    const handleDragStart = (e: React.DragEvent, nodeId: number) => {
        setDraggedId(nodeId);
        e.dataTransfer.setData('text/plain', String(nodeId));
        e.dataTransfer.effectAllowed = 'move';
    };

    const handleDragEnd = () => {
        setDraggedId(null);
        setDragOverId(null);
    };

    const handleDragOver = (e: React.DragEvent, nodeId: number) => {
        const isTypeDocDrag = e.dataTransfer.types.includes(TYPE_DOCUMENT_DRAG_MIME);
        if (draggedId === null && !isTypeDocDrag) return;
        if (draggedId !== null && isWithinSubtree(draggedId, nodeId)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = draggedId !== null ? 'move' : 'copy';
        if (dragOverId !== nodeId) setDragOverId(nodeId);
    };

    const handleDragLeave = (nodeId: number) => {
        setDragOverId(prev => (prev === nodeId ? null : prev));
    };

    const handleDrop = (e: React.DragEvent, targetId: number) => {
        e.preventDefault();
        setDragOverId(null);

        const isTypeDocDrag = e.dataTransfer.types.includes(TYPE_DOCUMENT_DRAG_MIME);
        if (isTypeDocDrag) {
            if (onDropTypeDocuments) {
                const raw = e.dataTransfer.getData(TYPE_DOCUMENT_DRAG_MIME);
                try {
                    onDropTypeDocuments(targetId, JSON.parse(raw));
                } catch { /* payload invalide, ignoré */ }
            }
            setDraggedId(null);
            return;
        }

        if (draggedId === null || isWithinSubtree(draggedId, targetId)) {
            setDraggedId(null);
            return;
        }
        onMove?.(draggedId, targetId);
        setDraggedId(null);
    };

    const renderNode = (node: UONode, depth: number) => {
        const children = childrenByParent.get(node.id) || [];
        const hasChildren = children.length > 0;
        const isExpanded = expanded.has(node.id);
        const isActive = node.id === toNum(currentId);
        const isRoot = node.id === toNum(rootId);
        const isDraggable = !!onMove && !isRoot;
        const isDropTarget = (!!onMove || !!onDropTypeDocuments) && dragOverId === node.id;
        const isBeingDragged = draggedId === node.id;

        const canAddOnThisNode = canManage && !!onAddChild && (!isRoot || canCreateRoot);

        return (
            <div key={node.id} className="uo-tree-branch">
                <div
                    className={`uo-tree-node ${isActive ? 'active' : ''} ${isBeingDragged ? 'dragging' : ''} ${isDropTarget ? 'drag-over' : ''}`}
                    style={{ paddingLeft: `${depth * 0.9 + 0.5}rem` }}
                    draggable={isDraggable}
                    onDragStart={(e) => handleDragStart(e, node.id)}
                    onDragEnd={handleDragEnd}
                    onDragOver={(e) => handleDragOver(e, node.id)}
                    onDragLeave={() => handleDragLeave(node.id)}
                    onDrop={(e) => handleDrop(e, node.id)}
                >
                    {hasChildren ? (
                        <button
                            className="uo-tree-toggle"
                            onClick={() => toggle(node.id)}
                            aria-label={isExpanded ? 'Réduire' : 'Développer'}
                        >
                            {isExpanded ? '▾' : '▸'}
                        </button>
                    ) : (
                        <span className="uo-tree-toggle-spacer" />
                    )}
                    <button className="uo-tree-label" onClick={() => onSelect(node.id)}>
                        <i className="fa-solid fa-diagram-project"></i>
                        <span className="uo-tree-text">{node.nom}</span>
                    </button>
                    {canAddOnThisNode && (
                        <button
                            className="uo-tree-add-btn"
                            onClick={(e) => handleAddChild(e, node.id)}
                            aria-label={`Ajouter une UO sous ${node.nom}`}
                            title={isRoot ? "Ajouter une UO racine" : "Ajouter une UO enfant"}
                        >
                            +
                        </button>
                    )}
                </div>
                {hasChildren && isExpanded && (
                    <div className="uo-tree-children">
                        {children.map(child => renderNode(child, depth + 1))}
                    </div>
                )}
            </div>
        );
    };

    const root = normalizedNodes.find(n => n.id === toNum(rootId));
    if (!root) return null;

    return (
        <div className="uo-tree">
            <p className="uo-tree-heading">UO</p>
            {renderNode(root, 0)}
        </div>
    );
}

export default UOTree;