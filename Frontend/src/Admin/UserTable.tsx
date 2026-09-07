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
    /** Non-null = suppression en attente (délai de grâce de 2 jours, annulable
     *  jusque-là) — voir User.suppressionPrevueLe côté serveur. */
    suppressionPrevueLe?: string | null;
}

interface UserTableProps {
    user: User[];
    onAction: (id: string, actionType: 'edit' | 'block-unblock' | 'delete' | 'annuler-suppression' | 'view') => void;
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

    // Liste déroulante des rôles — indépendante du menu d'actions ci-dessus
    // (même mécanique : bouton + portail positionné en fixed, fermeture au clic
    // extérieur/scroll/resize), affichée dès 2 rôles (voir le rendu de la
    // colonne "Rôle" plus bas) — avec 0 ou 1 seul rôle, rien à replier.
    const [openRolesId, setOpenRolesId] = useState<string | null>(null);
    const [rolesMenuPos, setRolesMenuPos] = useState<MenuPosition | null>(null);
    const rolesMenuRef = useRef<HTMLDivElement | null>(null);
    const rolesButtonRefs = useRef<Record<string, HTMLButtonElement | null>>({});

    // Info-bulle "suppression en attente" — même mécanique encore, déclenchée par
    // le badge poubelle rouge dans la colonne Actions (voir plus bas).
    const [openDeletionId, setOpenDeletionId] = useState<string | null>(null);
    const [deletionMenuPos, setDeletionMenuPos] = useState<MenuPosition | null>(null);
    const deletionMenuRef = useRef<HTMLDivElement | null>(null);
    const deletionButtonRefs = useRef<Record<string, HTMLButtonElement | null>>({});

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

    // Même hiérarchie que côté serveur (voir SecurityConfig.roleHierarchy) —
    // détermine quel rôle afficher en titre du bouton (le plus "élevé"), les
    // autres allant dans la liste déroulante plutôt qu'un compte brut (voir
    // le rendu de la colonne "Rôle" plus bas).
    const ROLE_PRIORITY = ['ADMIN', 'ADMIN_UO', 'EDITOR', 'USER'];
    const rolesParPriorite = (u: User) =>
        [...(u.roles || [])].sort(
            (a, b) => ROLE_PRIORITY.indexOf(normalizeRole(a.name)) - ROLE_PRIORITY.indexOf(normalizeRole(b.name))
        );

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

    const closeRolesMenu = () => {
        setOpenRolesId(null);
        setRolesMenuPos(null);
    };

    const toggleRolesMenu = (id: string) => {
        if (openRolesId === id) {
            closeRolesMenu();
            return;
        }
        const btn = rolesButtonRefs.current[id];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setRolesMenuPos({
                top: rect.bottom + window.scrollY + 4,
                left: rect.left + window.scrollX, // ancré au bord gauche du bouton
            });
        }
        setOpenRolesId(id);
    };

    useEffect(() => {
        if (!openRolesId) return;

        const handleClickOutside = (event: MouseEvent) => {
            const target = event.target as Node;
            const clickedToggle = rolesButtonRefs.current[openRolesId]?.contains(target);
            const clickedMenu = rolesMenuRef.current?.contains(target);
            if (!clickedToggle && !clickedMenu) closeRolesMenu();
        };

        const handleScrollOrResize = () => closeRolesMenu();

        document.addEventListener('mousedown', handleClickOutside);
        window.addEventListener('scroll', handleScrollOrResize, true);
        window.addEventListener('resize', handleScrollOrResize);

        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            window.removeEventListener('scroll', handleScrollOrResize, true);
            window.removeEventListener('resize', handleScrollOrResize);
        };
    }, [openRolesId]);

    const closeDeletionMenu = () => {
        setOpenDeletionId(null);
        setDeletionMenuPos(null);
    };

    const toggleDeletionMenu = (id: string) => {
        if (openDeletionId === id) {
            closeDeletionMenu();
            return;
        }
        const btn = deletionButtonRefs.current[id];
        if (btn) {
            const rect = btn.getBoundingClientRect();
            setDeletionMenuPos({
                top: rect.bottom + window.scrollY + 4,
                left: rect.left + window.scrollX,
            });
        }
        setOpenDeletionId(id);
    };

    useEffect(() => {
        if (!openDeletionId) return;

        const handleClickOutside = (event: MouseEvent) => {
            const target = event.target as Node;
            const clickedToggle = deletionButtonRefs.current[openDeletionId]?.contains(target);
            const clickedMenu = deletionMenuRef.current?.contains(target);
            if (!clickedToggle && !clickedMenu) closeDeletionMenu();
        };

        const handleScrollOrResize = () => closeDeletionMenu();

        document.addEventListener('mousedown', handleClickOutside);
        window.addEventListener('scroll', handleScrollOrResize, true);
        window.addEventListener('resize', handleScrollOrResize);

        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
            window.removeEventListener('scroll', handleScrollOrResize, true);
            window.removeEventListener('resize', handleScrollOrResize);
        };
    }, [openDeletionId]);

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
                        {/* Masquées sur écran réduit (voir UserTable.css) — le
                            rôle et le téléphone restent consultables via
                            "Voir", pas indispensables dans la liste elle-même. */}
                        <th className="col-role">Rôle</th>
                        <th className="col-telephone">Téléphone</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {user.map((singleUser) => {
                        // Suppression en attente (délai de grâce de 2 jours) : le serveur
                        // refuse déjà block/unblock, transfert, retrait et modification sur
                        // ce compte (voir UserService) — on les masque ici pour ne pas
                        // exposer des boutons qui échoueraient systématiquement.
                        const suppressionEnAttente = !!singleUser.suppressionPrevueLe;
                        const userHasUO = hasUO(singleUser);
                        const showRetirerAttribuer = !suppressionEnAttente && (userHasUO
                            ? (isAdminUO(singleUser) ? !!onRemoveAdminUO : !!onRemoveFromUO)
                            : (!isAdminGlobal(singleUser) && !!onAssignToUO));
                        const showTransferer = !suppressionEnAttente && userHasUO && !isAdminGlobal(singleUser) && !!onTransfer;
                        const showModifier = !suppressionEnAttente;

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
                                <td className="col-role">
                                    {!singleUser.roles || singleUser.roles.length === 0 ? (
                                        'Aucun rôle'
                                    ) : singleUser.roles.length < 2 ? (
                                        singleUser.roles.map(r => getRoleLabel(r.name)).join(', ')
                                    ) : (() => {
                                        // 2 rôles ou plus : un seul affiché en titre (le plus élevé
                                        // dans la hiérarchie), les autres repliés dans la liste
                                        // déroulante plutôt que d'allonger la ligne indéfiniment.
                                        const [rolePrincipal, ...autres] = rolesParPriorite(singleUser);
                                        return (
                                            <div className="roles-dropdown-wrapper">
                                                <button
                                                    ref={(el) => { rolesButtonRefs.current[singleUser.id] = el; }}
                                                    onClick={() => toggleRolesMenu(singleUser.id)}
                                                    className="roles-trigger"
                                                    aria-label={`${getRoleLabel(rolePrincipal.name)}, et ${autres.length} autre(s) rôle(s)`}
                                                    aria-expanded={openRolesId === singleUser.id}
                                                >
                                                    {getRoleLabel(rolePrincipal.name)} <i className="fa-solid fa-chevron-down" />
                                                </button>

                                                {openRolesId === singleUser.id && rolesMenuPos && createPortal(
                                                    <div
                                                        ref={rolesMenuRef}
                                                        className="action-menu roles-menu"
                                                        style={{
                                                            position: 'fixed',
                                                            top: rolesMenuPos.top,
                                                            left: rolesMenuPos.left,
                                                        }}
                                                    >
                                                        {autres.map((r, i) => (
                                                            <div key={i} className="roles-menu-item">
                                                                {getRoleLabel(r.name)}
                                                            </div>
                                                        ))}
                                                    </div>,
                                                    document.body
                                                )}
                                            </div>
                                        );
                                    })()}
                                </td>
                                <td className="col-telephone">{singleUser.telephone}</td>
                                <td>
                                    <div className="actions-cell-container">
                                        {suppressionEnAttente ? (
                                            // Badge poubelle : indique une suppression en cours sans
                                            // encombrer la ligne — le détail (date, annulation) est
                                            // dans l'info-bulle au clic, et dans "Voir" (voir plus bas).
                                            <div className="deletion-badge-wrapper">
                                                <button
                                                    ref={(el) => { deletionButtonRefs.current[singleUser.id] = el; }}
                                                    onClick={() => toggleDeletionMenu(singleUser.id)}
                                                    className="deletion-badge"
                                                    aria-label="Suppression en cours, cliquer pour plus d'informations"
                                                    aria-expanded={openDeletionId === singleUser.id}
                                                >
                                                    <i className="fa-solid fa-trash" />
                                                </button>

                                                {openDeletionId === singleUser.id && deletionMenuPos && createPortal(
                                                    <div
                                                        ref={deletionMenuRef}
                                                        className="action-menu deletion-menu"
                                                        style={{
                                                            position: 'fixed',
                                                            top: deletionMenuPos.top,
                                                            left: deletionMenuPos.left,
                                                        }}
                                                    >
                                                        <p className="deletion-menu-message">
                                                            L'utilisateur sera supprimé le{' '}
                                                            {new Date(singleUser.suppressionPrevueLe as string).toLocaleDateString('fr-FR')}.
                                                        </p>
                                                        <button
                                                            onClick={() => { closeDeletionMenu(); onAction(singleUser.id, 'annuler-suppression'); }}
                                                            disabled={actionInProgress}
                                                            className="deletion-menu-cancel-btn"
                                                        >
                                                            Annuler la suppression
                                                        </button>
                                                    </div>,
                                                    document.body
                                                )}
                                            </div>
                                        ) : (
                                            /* Masqué à taille réduite (voir UserTable.css,
                                               .actions-standalone) — repris comme entrée du
                                               menu "..." juste en dessous plutôt que
                                               disparaître : rien n'est perdu, juste regroupé. */
                                            <button
                                                onClick={() => onAction(singleUser.id, 'block-unblock')}
                                                disabled={actionInProgress}
                                                className={`block-unblock actions-standalone ${singleUser.actif === true || singleUser.actif === 'true' ? 'is-active' : 'is-blocked'}`}
                                            >
                                                {singleUser.actif === true || singleUser.actif === 'true' ? 'Active' : 'Bloquer'}
                                            </button>
                                        )}

                                        <button
                                            onClick={() => onAction(singleUser.id, 'view')}
                                            disabled={actionInProgress}
                                            // En attente de suppression : le menu "..." disparaît (plus
                                            // rien à y mettre, Annuler vit désormais dans le badge
                                            // poubelle ci-dessus) — "Voir" doit donc rester accessible à
                                            // toute largeur, pas seulement au-delà de 1100px.
                                            className={`action-button view ${suppressionEnAttente ? '' : 'actions-standalone'}`}
                                        >
                                            Voir
                                        </button>

                                        {!suppressionEnAttente && (
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
                                                    {/* Doublon de "Voir", réservé à la taille réduite
                                                        (action-menu-item-compact, display: none par
                                                        défaut — voir UserTable.css) pour ne jamais le
                                                        dupliquer avec le bouton autonome ci-dessus quand
                                                        celui-ci est visible. Le statut Actif/Bloquer n'a
                                                        PAS de doublon ici — retiré du menu à la demande,
                                                        il reste uniquement accessible en bouton autonome
                                                        au-delà de 1100px. */}
                                                    <button
                                                        onClick={() => { closeMenu(); onAction(singleUser.id, 'view'); }}
                                                        className="action-menu-item action-menu-item-compact"
                                                    >
                                                        Voir
                                                    </button>
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
                                                    {/* Ce menu ne se rend plus du tout tant qu'une suppression est
                                                        en attente (voir plus haut) — "Annuler" vit désormais dans
                                                        le badge poubelle, "Supprimer" est donc toujours pertinent ici. */}
                                                    <button
                                                        onClick={() => { closeMenu(); onAction(singleUser.id, 'delete'); }}
                                                        className="action-menu-item action-menu-item-danger"
                                                    >
                                                        Supprimer
                                                    </button>
                                                </div>,
                                                document.body
                                            )}
                                        </div>
                                        )}
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