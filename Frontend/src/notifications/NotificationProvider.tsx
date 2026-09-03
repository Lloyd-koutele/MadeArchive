import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';

export type NotificationType = 'success' | 'error' | 'info' | 'warning';

export interface NotificationItem {
    id: string;
    type: NotificationType;
    message: string;
}

export interface Notify {
    success: (message: string) => void;
    error: (message: string) => void;
    info: (message: string) => void;
    warning: (message: string) => void;
}

interface NotificationContextValue {
    items: NotificationItem[];
    notify: Notify;
    dismiss: (id: string) => void;
}

const NotificationContext = createContext<NotificationContextValue | null>(null);

// Erreurs affichées plus longtemps que les succès/infos — l'utilisateur a
// besoin de plus de temps pour lire/comprendre un message d'échec.
const DUREE_PAR_TYPE: Record<NotificationType, number> = {
    success: 4000,
    info: 4000,
    warning: 5500,
    error: 7000,
};

export function NotificationProvider({ children }: { children: ReactNode }) {
    const [items, setItems] = useState<NotificationItem[]>([]);
    const compteur = useRef(0);

    const dismiss = useCallback((id: string) => {
        setItems(prev => prev.filter(n => n.id !== id));
    }, []);

    const push = useCallback((type: NotificationType, message: string) => {
        const id = `n${++compteur.current}-${Date.now()}`;
        setItems(prev => [...prev, { id, type, message }]);
        window.setTimeout(() => dismiss(id), DUREE_PAR_TYPE[type]);
    }, [dismiss]);

    const notify: Notify = {
        success: (message: string) => push('success', message),
        error: (message: string) => push('error', message),
        info: (message: string) => push('info', message),
        warning: (message: string) => push('warning', message),
    };

    return (
        <NotificationContext.Provider value={{ items, notify, dismiss }}>
            {children}
        </NotificationContext.Provider>
    );
}

/** Accès direct à la liste + dismiss — réservé à NotificationStack. */
export function useNotificationContext(): NotificationContextValue {
    const ctx = useContext(NotificationContext);
    if (!ctx) throw new Error('useNotificationContext doit être utilisé sous NotificationProvider');
    return ctx;
}

/**
 * Point d'entrée pour tous les écrans : notify.success/error/info/warning(message).
 * Remplace les <div className="alert..."> locaux à chaque composant — un seul
 * endroit affiche réellement les notifications (voir NotificationStack), monté
 * une fois à la racine de l'appli (App.tsx).
 */
export function useNotify(): Notify {
    return useNotificationContext().notify;
}
