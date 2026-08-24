import { useEffect, useState } from 'react';
import '../Style/Page/Sidebar.css';
import { getCurrentUserRole, getUserRoles, logout, ROUTES } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import NotificationBell from './NotificationBell';

interface SidebarProps {
    title: string;
    children: React.ReactNode;
}

const ROLE_LABELS: Record<string, string> = {
    ADMIN: 'Administrateur',
    ADMIN_UO: "Administrateur d'unité",
    EDITOR: 'Éditeur',
    USER: 'Utilisateur'
};

// Mapping rôle → route : une seule source de vérité (authService.ROUTES), pas de
// copie locale — c'est justement cette duplication (trois copies désynchronisées :
// ici, App.tsx, authService.tsx) qui avait laissé /editeur trainer alors que la
// route réelle était devenue /editor.
const ROLE_ROUTES = ROUTES;

// Libellé du panneau. ADMIN et ADMIN_UO partagent le même intitulé : la
// distinction se fait par l'UO affichée juste en dessous (un ADMIN global n'en a pas).
const PANEL_LABELS: Record<string, string> = {
    ADMIN:    'Admin Panel',
    ADMIN_UO: 'Admin Panel',
    EDITOR:   'Éditeur Panel',
    USER:     'User Panel'
};

// Monogramme affiché au-dessus du libellé.
const PANEL_INITIALES: Record<string, string> = {
    ADMIN:    'A',
    ADMIN_UO: 'A',
    EDITOR:   'E',
    USER:     'U'
};

function Sidebar({ title, children }: SidebarProps) {
    const [open, setOpen] = useState(true);

    const roles = getUserRoles();
    const currentPath = window.location.pathname;
    const currentRole = Object.entries(ROLE_ROUTES).find(([, path]) => currentPath.startsWith(path))?.[0] || '';

    // Le rôle affiché suit l'interface réellement ouverte (utile quand l'utilisateur
    // cumule plusieurs rôles et bascule) ; à défaut, son rôle principal.
    const roleAffiche = currentRole || getCurrentUserRole() || '';
    const panelLabel  = PANEL_LABELS[roleAffiche] ?? (title ? `${title} Panel` : 'Panel');
    const initiale    = PANEL_INITIALES[roleAffiche] ?? panelLabel.charAt(0).toUpperCase();

    // L'UO n'est affichée que pour les éditeurs et les utilisateurs : un ADMIN est
    // global et un ADMIN_UO navigue déjà dans son arbre, la ligne serait redondante.
    const afficheUO = roleAffiche === 'EDITOR' || roleAffiche === 'USER';

    // undefined = en cours de chargement, null = aucune UO rattachée
    const [uoNom, setUoNom] = useState<string | null | undefined>(undefined);

    useEffect(() => {
        if (!afficheUO) return;          // pas d'appel réseau inutile côté admin
        let annule = false;
        getMyUO()
            .then((uo: { nom?: string } | null) => {
                if (!annule) setUoNom(uo?.nom ?? null);
            })
            .catch(() => {
                // 404 / pas de rattachement : cas normal pour un ADMIN global
                if (!annule) setUoNom(null);
            });
        return () => { annule = true; };
    }, [afficheUO]);

    const handleRoleSwitch = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selected = e.target.value;
    console.log('selected:', selected, 'currentRole:', currentRole);
    console.log('route cible:', ROLE_ROUTES[selected]);
    console.log('redirection dans 3s...');
    
    if (selected && selected !== currentRole) {
        setTimeout(() => {
            console.log('redirection vers:', ROLE_ROUTES[selected]);
            window.location.replace(ROLE_ROUTES[selected]);
        }, 3000);
    } else {
        console.log('blocked — selected === currentRole ou selected vide');
    }
};

    const handleLogout = async () => {
        await logout();
        window.location.replace('/login');
    };

    console.log('pathname:', window.location.pathname);
    console.log('roles:', roles);
    console.log('currentRole détecté:', currentRole);

    return (
        <aside className={open ? 'sidebar sidebar-open' : 'sidebar sidebar-closed'}>
            <div className='sidebar-header'>
                <NotificationBell />
                <button
                    onClick={() => setOpen(!open)}
                    className='toggle-button'
                    aria-label={open ? "Fermer le menu" : "Ouvrir le menu"}
                >
                    <i className={open ? 'fa-solid fa-xmark' : 'fa-solid fa-bars'}></i>
                </button>
            </div>

            {open && title && (
                <div className='sidebar-profile-header'>
                    <div className='sidebar-logo-mark' aria-hidden='true'>{initiale}</div>
                    <div className='sidebar-identity'>
                        <h2 className='sidebar-panel-title'>{panelLabel}</h2>
                        {afficheUO && (
                            <span className='sidebar-panel-uo' title={uoNom ?? undefined}>
                                {uoNom === undefined ? '…' : (uoNom || 'Pas de UO')}
                            </span>
                        )}
                    </div>
                </div>
            )}

            {/* Liste déroulante de switch — uniquement si 2 rôles ou plus */}
            {open && roles.length >= 2 && (
                <div className='sidebar-role-switcher'>
                    <select
                        value={currentRole}
                        onChange={handleRoleSwitch}
                        className='role-select'
                        aria-label="Changer d'interface"
                    >
                        {roles.map(role => (
                            <option key={role} value={role}>
                                {ROLE_LABELS[role] || role}
                            </option>
                        ))}
                    </select>
                </div>
            )}

            {open && (
                <div className='sidebar-content'>
                    {children}
                </div>
            )}

            {open && (
                <button
                    className='logout-button'
                    onClick={handleLogout}
                >
                    Se déconnecter
                </button>
            )}
        </aside>
    );
}

export default Sidebar;