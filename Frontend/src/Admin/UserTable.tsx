// UserTable.tsx — 3 boutons visibles + menu déroulant
import { memo, useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import '../Style/Admin/UserTable.css';

interface RoleField {
    name: "ADMIN" | "ADMIN_UO" | "EDITOR" | "USER";
}

interface User {
    id: string;
    nom: string;
    prenom: string;
    email: string;
    telephone: string;
    actif: boolean | string;
    roles: RoleField[];
    uoId?: number | null;
    uoNom?: string | null;
}

interface UserTableProps {
    user: User[];
    onAction: (id: string, actionType: 'edit' | 'block-unblock' | 'delete' | 'view') => void;
    actionInProgress: boolean;
    onRemoveFromUO?: (userId: string, uoId: number) => void;
    onRemoveAdminUO?: (userId: string, uoId: number) => void;
    onAssignToUO?: (userId: string) => void;
    onTransfer?: (userId: string) => void;
}

interface MenuPosition {
    top: number;
    left: number;
}

const UserTable = memo(({ user, onAction, actionInProgress, onRemoveFromUO, onRemoveAdminUO, onAssignToUO, onTransfer }: UserTableProps) => {
    const [openMenuId, setOpenMenuId] = useState<string | null>(null);
    const [menuPos, setMenuPos] = useState<MenuPosition | null>(null);

    const menuRef = useRef<HTMLDivElement | null>(null);
    const buttonRefs = useRef<Record<string, HTMLButtonElement | null>>({});

    const normalizeRole = (role: string) => {
        if (!role || typeof role !== 'string') return '';
        return role.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toUpperCase().trim();
    };

    const getRoleLabel = (role: string) => {
        switch (normalizeRole(role)) {
            case 'ADMIN': return 'Administrateur';
            case 'ADMIN_UO': return "Administrateur d'unité";
            case 'EDITOR': return 'Éditeur';
            case 'USER': return 'Utilisateur';
            default: return role;
        }
    };

    const rolesOf = (u: User) => (u.roles || []).map(r => normalizeRole(r.name));
    const hasUO = (u: User) => u.uoId !== null && u.uoId !== undefined;
    const isAdminGlobal = (u: User) => rolesOf(u).includes('ADMIN');
    const isAdminUO = (u: User) => rolesOf(u).includes('ADMIN_UO');

    const closeMenu = () => {
        setOpenMenuId(null);
        setMenuPos(null);
    };

    const toggleMenu = (id: string) => {
        if (openMenuId === id) {
            closeMenu();
            return;
        }
        const btn = buttonRefs.current[id];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setMenuPos({
                top: rect.bottom + window.scrollY + 4,
                left: rect.right + window.scrollX, // ancré au bord droit du bouton
            });
        }
        setOpenMenuId(id);
    };

    // Fermeture du menu au clic en dehors (menu OU bouton toggle),
    // et au scroll/resize pour éviter un menu mal positionné.
    useEffect(() => {
        if (!openMenuId) return;

        const handleClickOutside = (event: MouseEvent) => {
            const target = event.target as Node;
            const clickedToggle = buttonRefs.current[openMenuId]?.contains(target);
            const clickedMenu = menuRef.current?.contains(target);
            if (!clickedToggle && !clickedMenu) closeMenu();
        };

        const handleScrollOrResize = () => closeMenu();

        document.addEventListener('mousedown', handleClickOutside);
        window.addEventListener('scroll', handleScrollOrResize, true);
        window.addEventListener('resize', handleScrollOrResize);

        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            window.removeEventListener('scroll', handleScrollOrResize, true);
            window.removeEventListener('resize', handleScrollOrResize);
        };
    }, [openMenuId]);

    if (!Array.isArray(user) || user.length === 0) {
        return (
            <div className='empty-state'>
                <h3 className='mt-4 text-lg font-medium'>Aucun utilisateur trouvé</h3>
                <p className="mt-1 text-sm">Modifiez vos critères de recherche </p>
            </div>
        );
    }

    return (
        <div className="table-container">
            <table className="user-table">
                <thead>
                    <tr>
                        <th>Utilisateur</th>
                        <th>Email</th>
                        <th>Rôle</th>
                        <th>Téléphone</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {user.map((singleUser) => {
                        const userHasUO = hasUO(singleUser);
                        const showRetirerAttribuer = userHasUO
                            ? (isAdminUO(singleUser) ? !!onRemoveAdminUO : !!onRemoveFromUO)
                            : (!isAdminGlobal(singleUser) && !!onAssignToUO);
                        const showTransferer = userHasUO && !isAdminGlobal(singleUser) && !!onTransfer;
                        const showModifier = true;

                        const handleRetirerAttribuer = () => {
                            closeMenu();
                            if (userHasUO) {
                                if (isAdminUO(singleUser)) onRemoveAdminUO!(singleUser.id, singleUser.uoId!);
                                else onRemoveFromUO!(singleUser.id, singleUser.uoId!);
                            } else {
                                onAssignToUO!(singleUser.id);
                            }
                        };

                        const isMenuOpen = openMenuId === singleUser.id;

                        return (
                            <tr key={singleUser.id}>
                                <td>{singleUser.nom} {singleUser.prenom}</td>
                                <td>{singleUser.email}</td>
                                <td>
                                    {singleUser.roles && singleUser.roles.length > 0
                                        ? singleUser.roles.map(r => getRoleLabel(r.name)).join(', ')
                                        : 'Aucun rôle'}
                                </td>
                                <td>{singleUser.telephone}</td>
                                <td>
                                    <div className="actions-cell-container">
                                        <button
                                            onClick={() => onAction(singleUser.id, 'block-unblock')}
                                            disabled={actionInProgress}
                                            className={`block-unblock ${singleUser.actif === true || singleUser.actif === 'true' ? 'is-active' : 'is-blocked'}`}
                                        >
                                            {singleUser.actif === true || singleUser.actif === 'true' ? 'Active' : 'Bloquer'}
                                        </button>

                                        <button
                                            onClick={() => onAction(singleUser.id, 'view')}
                                            disabled={actionInProgress}
                                            className="action-button view"
                                        >
                                            Voir
                                        </button>

                                        <div className="action-menu-wrapper">
                                            <button
                                                ref={(el) => { buttonRefs.current[singleUser.id] = el; }}
                                                onClick={() => toggleMenu(singleUser.id)}
                                                disabled={actionInProgress}
                                                className="action-button menu-toggle"
                                                aria-label="Plus d'actions"
                                                aria-expanded={isMenuOpen}
                                            >
                                                <i className="fa-solid fa-ellipsis"></i>
                                            </button>

                                            {isMenuOpen && menuPos && createPortal(
                                                <div
                                                    ref={menuRef}
                                                    className="action-menu"
                                                    style={{
                                                        position: 'fixed',
                                                        top: menuPos.top,
                                                        left: menuPos.left,
                                                        transform: 'translateX(-100%)',
                                                    }}
                                                >
                                                    {showRetirerAttribuer && (
                                                        <button onClick={handleRetirerAttribuer} className="action-menu-item">
                                                            {userHasUO ? 'Retirer' : 'Attribuer'}
                                                        </button>
                                                    )}
                                                    {showTransferer && (
                                                        <button onClick={() => { closeMenu(); onTransfer!(singleUser.id); }} className="action-menu-item">
                                                            Transférer
                                                        </button>
                                                    )}
                                                    {showModifier && (
                                                        <button onClick={() => { closeMenu(); onAction(singleUser.id, 'edit'); }} className="action-menu-item">
                                                            Modifier
                                                        </button>
                                                    )}
                                                </div>,
                                                document.body
                                            )}
                                        </div>
                                    </div>
                                </td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
});

export default UserTable;