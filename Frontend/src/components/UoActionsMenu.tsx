import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import '../Style/components/UoActionsMenu.css';

interface UoActionsMenuProps {
    onRenommer: () => void;
    onSupprimer: () => void;
    disabled?: boolean;
}

/**
 * Bouton "..." compact remplaçant les deux boutons Renommer/Supprimer pleine
 * taille à côté du nom de l'UO (AdminDahboard.tsx/AdminUoDashboard.tsx) —
 * mêmes actions, juste moins encombrant à côté du titre. Même mécanique
 * (portail + position calculée depuis le bouton) que les autres menus "..."
 * déjà dans l'app (TypeDocumentList, PhysicalLocationsPanel...).
 */
function UoActionsMenu({ onRenommer, onSupprimer, disabled }: UoActionsMenuProps) {
    const [open, setOpen] = useState(false);
    const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
    const btnRef = useRef<HTMLButtonElement | null>(null);
    const menuRef = useRef<HTMLDivElement | null>(null);

    const toggle = () => {
        if (open) { setOpen(false); return; }
        const rect = btnRef.current?.getBoundingClientRect();
        if (rect) setPos({ top: rect.bottom + window.scrollY + 4, left: rect.left + window.scrollX });
        setOpen(true);
    };

    useEffect(() => {
        if (!open) return;
        const handleClickOutside = (e: MouseEvent) => {
            const target = e.target as Node;
            if (!btnRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
        };
        const handleScrollOrResize = () => setOpen(false);
        document.addEventListener('mousedown', handleClickOutside);
        window.addEventListener('scroll', handleScrollOrResize, true);
        window.addEventListener('resize', handleScrollOrResize);
        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            window.removeEventListener('scroll', handleScrollOrResize, true);
            window.removeEventListener('resize', handleScrollOrResize);
        };
    }, [open]);

    return (
        <div className="uo-actions-menu-wrapper">
            <button
                ref={btnRef}
                type="button"
                className="uo-actions-menu-toggle"
                onClick={toggle}
                disabled={disabled}
                aria-label="Options de l'UO"
                aria-expanded={open}
            >
                <i className="fa-solid fa-ellipsis" />
            </button>

            {open && pos && createPortal(
                <div
                    ref={menuRef}
                    className="uo-actions-menu"
                    style={{ top: pos.top, left: pos.left }}
                >
                    <button type="button" onClick={() => { setOpen(false); onRenommer(); }}>
                        <i className="fa-solid fa-pen" /> Renommer
                    </button>
                    <button type="button" onClick={() => { setOpen(false); onSupprimer(); }}>
                        <i className="fa-solid fa-trash" /> Supprimer
                    </button>
                </div>,
                document.body
            )}
        </div>
    );
}

export default UoActionsMenu;
