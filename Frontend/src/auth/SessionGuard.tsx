import { useCallback, useEffect, useRef, useState } from 'react';
import api, { SESSION_INVALIDATED_EVENT } from '../services/api';
import { isAuthenticated, logout } from './authService';
import '../Style/auth/SessionGuard.css';

const POLL_INTERVAL_MS = 20_000;
const COUNTDOWN_SECONDS = 3;

interface SessionInvalidatedDetail {
    reason: 'SESSION_INVALIDATED' | 'ACCOUNT_BLOCKED';
    message: string;
}

/**
 * Monté sur chaque route authentifiée (voir PrivateRoute dans App.tsx).
 *
 * Détecte, même si l'utilisateur reste inactif dans l'onglet, qu'un admin a
 * bloqué son compte ou qu'un rôle/mot de passe a changé — via un heartbeat
 * périodique ET via tout appel API qui échouerait entre-temps (l'intercepteur
 * dans services/api.ts déclenche le même événement). Affiche alors un message
 * et déconnecte l'utilisateur après un court délai, au lieu d'une redirection
 * brutale et silencieuse.
 */
function SessionGuard() {
    const [detail, setDetail] = useState<SessionInvalidatedDetail | null>(null);
    const [secondsLeft, setSecondsLeft] = useState(COUNTDOWN_SECONDS);
    const triggeredRef = useRef(false);

    const trigger = useCallback((d: SessionInvalidatedDetail) => {
        if (triggeredRef.current) return;
        triggeredRef.current = true;
        setDetail(d);
    }, []);

    // ── Heartbeat : détecte l'invalidation même sans requête déclenchée par l'utilisateur ──
    useEffect(() => {
        if (!isAuthenticated()) return;

        const check = () => {
            if (triggeredRef.current) return;
            api.get('/user/session/check').catch(() => {
                // Les 401 pertinents sont déjà transformés en événement par l'intercepteur ;
                // les autres erreurs (réseau, etc.) ne doivent pas déclencher de déconnexion.
            });
        };

        const interval = setInterval(check, POLL_INTERVAL_MS);
        return () => clearInterval(interval);
    }, []);

    // ── Écoute l'événement émis par l'intercepteur Axios sur un 401 explicite ──
    useEffect(() => {
        const handler = (e: Event) => {
            const custom = e as CustomEvent<SessionInvalidatedDetail>;
            if (custom.detail) trigger(custom.detail);
        };
        window.addEventListener(SESSION_INVALIDATED_EVENT, handler);
        return () => window.removeEventListener(SESSION_INVALIDATED_EVENT, handler);
    }, [trigger]);

    // ── Compte à rebours puis déconnexion effective ──
    useEffect(() => {
        if (!detail) return;

        if (secondsLeft <= 0) {
            logout().finally(() => window.location.replace('/login'));
            return;
        }

        const timeout = setTimeout(() => setSecondsLeft(s => s - 1), 1000);
        return () => clearTimeout(timeout);
    }, [detail, secondsLeft]);

    if (!detail) return null;

    return (
        <div className="session-guard-overlay" role="alertdialog" aria-live="assertive">
            <div className="session-guard-box">
                <i className="fa-solid fa-triangle-exclamation session-guard-icon" />
                <p className="session-guard-message">{detail.message}</p>
                <p className="session-guard-countdown">
                    Déconnexion dans {secondsLeft} seconde{secondsLeft > 1 ? 's' : ''}…
                </p>
            </div>
        </div>
    );
}

export default SessionGuard;
