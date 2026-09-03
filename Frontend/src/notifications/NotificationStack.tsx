import { useNotificationContext } from './NotificationProvider';
import type { NotificationType } from './NotificationProvider';
import '../Style/notifications/Notifications.css';

const ICONES: Record<NotificationType, string> = {
    success: 'fa-solid fa-circle-check',
    error: 'fa-solid fa-circle-exclamation',
    warning: 'fa-solid fa-triangle-exclamation',
    info: 'fa-solid fa-circle-info',
};

const LIBELLES: Record<NotificationType, string> = {
    success: 'Succès',
    error: 'Erreur',
    warning: 'Attention',
    info: 'Information',
};

/**
 * Fenêtre unique de l'application pour tous les messages serveur (succès,
 * erreur, info) — montée une seule fois à la racine (voir App.tsx). Les
 * écrans n'affichent plus jamais leur propre bandeau d'alerte, ils appellent
 * useNotify() (voir NotificationProvider).
 */
function NotificationStack() {
    const { items, dismiss } = useNotificationContext();

    if (items.length === 0) return null;

    return (
        <div className="notif-stack" role="status" aria-live="polite">
            {items.map(n => (
                <div key={n.id} className={`notif-card notif-${n.type}`}>
                    <i className={ICONES[n.type]} aria-hidden="true" />
                    <div className="notif-body">
                        <span className="notif-label">{LIBELLES[n.type]}</span>
                        <span className="notif-message">{n.message}</span>
                    </div>
                    <button
                        type="button"
                        className="notif-close"
                        onClick={() => dismiss(n.id)}
                        aria-label="Fermer la notification"
                    >
                        ✕
                    </button>
                </div>
            ))}
        </div>
    );
}

export default NotificationStack;
