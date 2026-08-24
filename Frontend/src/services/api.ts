import axios from 'axios';

// URL de base de ton serveur Spring Boot
const BASE_URL = 'http://localhost:8080/api';

// Création d'une instance Axios
const api = axios.create({
    baseURL: BASE_URL,
    headers: {
        'Content-Type': 'application/json',
    },
});

// Intercepteur de requête : ajoute le token Bearer
api.interceptors.request.use(
    (config) => {
        const token = localStorage.getItem('token');
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
 * compte bloqué, ou rôle/mot de passe changé — par opposition à un simple
 * jeton absent/expiré. Écouté par SessionGuard pour afficher un message
 * avant la déconnexion, au lieu d'une redirection immédiate et silencieuse.
 */
export const SESSION_INVALIDATED_EVENT = 'session-invalidated';

// Intercepteur de réponse : gère les erreurs 401 (token expiré, invalidé, ou absent)
api.interceptors.response.use(
    (response) => response,
    (error) => {
        const url = error.config?.url || '';
        const isAuthRoute = url.includes('/login') || url.includes('/logout');

        if (error.response?.status === 401 && !isAuthRoute) {
            const reason = error.response?.data?.reason;

            if (reason === 'SESSION_INVALIDATED' || reason === 'ACCOUNT_BLOCKED') {
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
