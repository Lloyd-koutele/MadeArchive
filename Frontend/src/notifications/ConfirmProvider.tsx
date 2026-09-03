import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import '../Style/notifications/Notifications.css';

export interface ConfirmOptions {
    title?: string;
    message: string;
    confirmLabel?: string;
    cancelLabel?: string;
    /** true = action destructive (bouton de confirmation en rouge). */
    danger?: boolean;
}

type ConfirmFn = (options: ConfirmOptions | string) => Promise<boolean>;

interface PendingConfirm extends ConfirmOptions {
    resolve: (value: boolean) => void;
}

const ConfirmContext = createContext<ConfirmFn | null>(null);

/**
 * Remplace window.confirm() par une modale au style de l'application — API
 * identique en esprit (Promise<boolean>) mais cohérente visuellement avec le
 * reste (voir NotificationStack pour les messages non bloquants).
 *
 * Montée une seule fois à la racine (App.tsx). Une confirmation à la fois :
 * un second appel pendant qu'une modale est déjà ouverte annule silencieusement
 * la précédente (résolue à false) — cas normalement jamais rencontré en usage réel.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
    const [pending, setPending] = useState<PendingConfirm | null>(null);

    const confirm = useCallback<ConfirmFn>((options) => {
        const opts: ConfirmOptions = typeof options === 'string' ? { message: options } : options;
        return new Promise<boolean>((resolve) => {
            setPending(prev => {
                prev?.resolve(false);
                return { ...opts, resolve };
            });
        });
    }, []);

    const repondre = (valeur: boolean) => {
        pending?.resolve(valeur);
        setPending(null);
    };

    return (
        <ConfirmContext.Provider value={confirm}>
            {children}
            {pending && (
                <div className="confirm-overlay" onClick={() => repondre(false)}>
                    <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
                        <div className="confirm-dialog-header">
                            <i className={`fa-solid ${pending.danger ? 'fa-triangle-exclamation' : 'fa-circle-question'}`} />
                            <h3>{pending.title ?? (pending.danger ? 'Confirmer la suppression' : 'Confirmer')}</h3>
                        </div>
                        <p className="confirm-dialog-message">{pending.message}</p>
                        <div className="confirm-dialog-actions">
                            <button type="button" className="confirm-btn-cancel" onClick={() => repondre(false)}>
                                {pending.cancelLabel ?? 'Annuler'}
                            </button>
                            <button
                                type="button"
                                className={pending.danger ? 'confirm-btn-danger' : 'confirm-btn-confirm'}
                                onClick={() => repondre(true)}
                                autoFocus
                            >
                                {pending.confirmLabel ?? 'Confirmer'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </ConfirmContext.Provider>
    );
}

/** const confirm = useConfirm(); if (!await confirm('Supprimer ?')) return; */
export function useConfirm(): ConfirmFn {
    const ctx = useContext(ConfirmContext);
    if (!ctx) throw new Error('useConfirm doit être utilisé sous ConfirmProvider');
    return ctx;
}
