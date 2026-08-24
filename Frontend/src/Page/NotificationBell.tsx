import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
    getMesNotifications,
    countNotificationsNonLues,
    marquerNotificationLue,
    marquerToutesLues,
} from '../services/notification/NotificationService';
import type { NotificationDto } from '../services/notification/NotificationService';
import '../Style/Page/NotificationBell.css';

const TYPE_ICONS: Record<string, string> = {
    DOCUMENT_CORROMPU: 'fa-solid fa-triangle-exclamation notif-icon-corrompu',
    DOCUMENT_AJOUTE:   'fa-solid fa-file-circle-plus notif-icon-ajoute',
    PROJET_CREE:       'fa-solid fa-folder-plus notif-icon-projet',
};

const POLL_INTERVAL_MS = 60_000;

function formatRelatif(dateStr: string): string {
    const date = new Date(dateStr);
    const diffMs = Date.now() - date.getTime();
    const diffMin = Math.floor(diffMs / 60_000);

    if (diffMin < 1) return "à l'instant";
    if (diffMin < 60) return `il y a ${diffMin} min`;
    const diffH = Math.floor(diffMin / 60);
    if (diffH < 24) return `il y a ${diffH} h`;
    const diffJ = Math.floor(diffH / 24);
    if (diffJ < 7) return `il y a ${diffJ} j`;
    return date.toLocaleDateString('fr-FR');
}

function NotificationBell() {
    const [open, setOpen]                 = useState(false);
    const [count, setCount]               = useState(0);
    const [notifications, setNotifications] = useState<NotificationDto[]>([]);
    const [loading, setLoading]           = useState(false);
    const wrapperRef = useRef<HTMLDivElement>(null);
    const panelRef   = useRef<HTMLDivElement>(null);

    // Le panneau est rendu dans <body> : sa position doit être calculée à la main
    // à partir de la cloche (la sidebar est en overflow:hidden, elle rognerait
    // un panneau positionné en absolu à l'intérieur).
    const [pos, setPos] = useState<{ top: number; left: number }>({ top: 0, left: 0 });

    const PANEL_WIDTH = 320;
    const MARGE = 8;

    const placerPanneau = () => {
        const bell = wrapperRef.current;
        if (!bell) return;
        const r = bell.getBoundingClientRect();
        const left = Math.max(
            MARGE,
            Math.min(r.left, window.innerWidth - PANEL_WIDTH - MARGE)
        );
        setPos({ top: r.bottom + MARGE, left });
    };

    useLayoutEffect(() => {
        if (!open) return;
        placerPanneau();
        window.addEventListener('resize', placerPanneau);
        window.addEventListener('scroll', placerPanneau, true);
        return () => {
            window.removeEventListener('resize', placerPanneau);
            window.removeEventListener('scroll', placerPanneau, true);
        };
    }, [open]);

    const refreshCount = () => {
        countNotificationsNonLues().then(setCount).catch(() => {});
    };

    useEffect(() => {
        refreshCount();
        const interval = setInterval(refreshCount, POLL_INTERVAL_MS);
        return () => clearInterval(interval);
    }, []);

    // Fermer au clic extérieur
    useEffect(() => {
        const handleClickOutside = (e: MouseEvent) => {
            const cible = e.target as Node;
            const dansCloche  = wrapperRef.current?.contains(cible);
            const dansPanneau = panelRef.current?.contains(cible);
            if (!dansCloche && !dansPanneau) {
                setOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    const toggleOpen = () => {
        const next = !open;
        setOpen(next);
        if (next) {
            setLoading(true);
            getMesNotifications()
                .then(setNotifications)
                .catch(() => setNotifications([]))
                .finally(() => setLoading(false));
        }
    };

    const handleClickNotification = async (n: NotificationDto) => {
        if (n.read) return;
        try {
            await marquerNotificationLue(n.id);
            setNotifications(prev => prev.map(x => x.id === n.id ? { ...x, read: true } : x));
            setCount(c => Math.max(0, c - 1));
        } catch {
            // silencieux — pas critique
        }
    };

    const handleMarquerToutesLues = async () => {
        try {
            await marquerToutesLues();
            setNotifications(prev => prev.map(x => ({ ...x, read: true })));
            setCount(0);
        } catch {
            // silencieux
        }
    };

    return (
        <div className="notif-bell-wrapper" ref={wrapperRef}>
            <button
                className="notif-bell-button"
                onClick={toggleOpen}
                aria-label="Notifications"
                title="Notifications"
            >
                <i className="fa-solid fa-bell" />
                {count > 0 && (
                    <span className="notif-bell-badge">{count > 99 ? '99+' : count}</span>
                )}
            </button>

            {open && createPortal(
                <div
                    className="notif-bell-panel"
                    ref={panelRef}
                    style={{ top: pos.top, left: pos.left }}
                >
                    <div className="notif-bell-panel-header">
                        <span>Notifications</span>
                        {count > 0 && (
                            <button className="notif-mark-all-btn" onClick={handleMarquerToutesLues}>
                                Tout marquer comme lu
                            </button>
                        )}
                    </div>

                    <div className="notif-bell-panel-list">
                        {loading ? (
                            <div className="notif-bell-empty">
                                <i className="fa-solid fa-spinner fa-spin" /> Chargement…
                            </div>
                        ) : notifications.length === 0 ? (
                            <div className="notif-bell-empty">Aucune notification</div>
                        ) : (
                            notifications.map(n => (
                                <button
                                    key={n.id}
                                    className={`notif-item ${n.read ? '' : 'notif-item-unread'}`}
                                    onClick={() => handleClickNotification(n)}
                                >
                                    <i className={TYPE_ICONS[n.type] ?? 'fa-solid fa-bell'} />
                                    <div className="notif-item-body">
                                        <p className="notif-item-message">{n.message}</p>
                                        <span className="notif-item-date">{formatRelatif(n.createAt)}</span>
                                    </div>
                                    {!n.read && <span className="notif-item-dot" />}
                                </button>
                            ))
                        )}
                    </div>
                </div>,
                document.body
            )}
        </div>
    );
}

export default NotificationBell;
