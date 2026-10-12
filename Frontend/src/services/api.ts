import axios from 'axios';

// URL de base du serveur Spring Boot — configurable via VITE_API_URL (fichier
// .env du frontend, ou variable d'environnement au build) pour pointer vers
// le domaine HTTPS réel en production ; par défaut, accès local direct au
// backend en HTTP (pas de Traefik en dev). En local à travers Traefik/HTTPS,
// définir VITE_API_URL=https://localhost/api (ou le domaine configuré).
const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api';

// Création d'une instance Axios
const api = axios.create({
    baseURL: BASE_URL,
    headers: {
        'Content-Type': 'application/json',
    },
});

/** Renouvelle le jeton quand il lui reste moins que ça (le serveur en émet un nouveau sous 2/5 de sa durée). */
const MARGE_RENOUVELLEMENT_MS = 5 * 60 * 1000;

const estRouteAuth = (url: string) =>
    url.includes('/login') || url.includes('/logout') || url.includes('/refresh');

/** Échéance du jeton (ms epoch) lue dans son payload, ou null s'il est illisible. */
const echeanceJeton = (token: string): number | null => {
    try {
        const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        return payload.exp ? payload.exp * 1000 : null;
    } catch {
        return null;
    }
};

// Un seul renouvellement à la fois : les requêtes concurrentes attendent le même résultat.
let renouvellementEnCours: Promise<string | null> | null = null;

/**
 * Échange le jeton de rafraîchissement contre un nouveau jeton d'accès (et repousse l'échéance de la session).
 * Instance Axios nue : pas d'intercepteurs, donc pas de boucle sur un 401 de /refresh.
 */
const renouvelerJeton = (): Promise<string | null> => {
    if (renouvellementEnCours) return renouvellementEnCours;

    const refreshToken = localStorage.getItem('refreshToken');
    if (!refreshToken) return Promise.resolve(null);

    renouvellementEnCours = axios
        .post(`${BASE_URL}/refresh`, { refreshToken }, { headers: { 'Content-Type': 'application/json' } })
        .then((res) => {
            const nouveau: string | undefined = res.data?.token;
            if (!nouveau) return null;
            localStorage.setItem('token', nouveau);
            // Rotation : l'ancien jeton de rafraîchissement ne sert plus, on garde son remplaçant.
            if (res.data?.refreshToken) localStorage.setItem('refreshToken', res.data.refreshToken);
            return nouveau;
        })
        .catch(() => null)
        .finally(() => { renouvellementEnCours = null; });

    return renouvellementEnCours;
};

// Intercepteur de requête : ajoute le token Bearer, après l'avoir renouvelé s'il est proche de son échéance
api.interceptors.request.use(
    async (config) => {
        const url = config.url || '';
        let token = localStorage.getItem('token');

        if (token && !estRouteAuth(url)) {
            const echeance = echeanceJeton(token);
            if (echeance !== null && echeance - Date.now() < MARGE_RENOUVELLEMENT_MS) {
                token = (await renouvelerJeton()) ?? token;
            }
        }

        if (token) {
            config.headers['Authorization'] = `Bearer ${token}`;
        }
        return config;
    },
    (error) => Promise.reject(error)
);

/**
 * Nom de l'événement déclenché quand le serveur signale explicitement (voir
 * `reason` dans le corps 401) que la session a été invalidée côté serveur —
 * compte bloqué, rôle/mot de passe changé, ou UO changée (transfert) — par
 * opposition à un simple jeton absent/expiré. Écouté par SessionGuard pour
 * afficher un message avant la déconnexion, au lieu d'une redirection
 * immédiate et silencieuse.
 */
export const SESSION_INVALIDATED_EVENT = 'session-invalidated';

// Intercepteur de réponse : gère les erreurs 401 (token expiré, invalidé, ou absent)
api.interceptors.response.use(
    (response) => response,
    async (error) => {
        const url = error.config?.url || '';
        const isAuthRoute = estRouteAuth(url);

        if (error.response?.status === 401 && !isAuthRoute) {
            const reason = error.response?.data?.reason;
            const invalidationExplicite = reason === 'SESSION_INVALIDATED' || reason === 'ACCOUNT_BLOCKED'
                || reason === 'UO_CHANGEE' || reason === 'COMPTE_SUPPRIME';

            // Jeton simplement expiré : une seule tentative de renouvellement, puis on rejoue la requête.
            if (!invalidationExplicite && !error.config?._dejaRejouee) {
                const nouveau = await renouvelerJeton();
                if (nouveau) {
                    error.config._dejaRejouee = true;
                    error.config.headers['Authorization'] = `Bearer ${nouveau}`;
                    return api(error.config);
                }
            }

            if (invalidationExplicite) {
                // Le serveur a explicitement invalidé cette session (elle ne redeviendra
                // jamais valide) : on laisse SessionGuard afficher le message et gérer
                // la déconnexion différée, plutôt que de rediriger immédiatement.
                window.dispatchEvent(new CustomEvent(SESSION_INVALIDATED_EVENT, {
                    detail: {
                        reason,
                        message: error.response?.data?.message
                            ?? 'Votre session n\'est plus valide, veuillez vous reconnecter.',
                    },
                }));
            } else {
                // Jeton simplement absent ou expiré naturellement : comportement inchangé.
                localStorage.clear();
                window.location.replace('/login');
            }
        }
        return Promise.reject(error);
    }
);

export default api;
