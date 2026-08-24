import api from '../services/api';

// Décodage JWT
const parseJwt = (token: string) => {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(
      decodeURIComponent(
        atob(base64).split('').map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)).join('')
      )
    );
  } catch { return null; }
};

// Priorité des rôles : ADMIN > ADMIN_UO > EDITOR > USER — reflète la hiérarchie
// des rôles côté serveur (SecurityConfig.roleHierarchy : ADMIN > ADMIN_UO > USER,
// EDITOR > USER — ADMIN_UO est plus "élevé" qu'EDITOR, pas l'inverse).
const getPrimaryRole = (rolesString: string): string => {
  const rolesList = rolesString.split(',').map(r => r.trim());
  if (rolesList.includes('ADMIN')) return 'ADMIN';
  if (rolesList.includes('ADMIN_UO')) return 'ADMIN_UO';
  if (rolesList.includes('EDITOR')) return 'EDITOR';
  return 'USER';
};

// Seule source de vérité pour le mapping rôle → route — App.tsx (PrivateRoute)
// la réutilise plutôt que de dupliquer sa propre copie (elles avaient fini par
// diverger : /editeur ici vs /editor là-bas).
export const ROUTES: Record<string, string> = {
  ADMIN: '/admin',
  ADMIN_UO: '/admin_uo',
  EDITOR: '/editor',
  USER: '/user'
};

export const loginUser = async (email: string, password: string) => {
  try {
    const { data } = await api.post('/login', { email, password });

    if (data.success === false || data.status === 'error') {
      throw new Error(data.message || "Échec lors de l'authentification");
    }
    if (!data.token) throw new Error('Token non reçu du serveur');

    const token = data.token;
    const decoded = parseJwt(token);
    if (!decoded) throw new Error('Token invalide');

    const rawRoles: string = decoded.role || decoded.roles || '';
    const primaryRole = getPrimaryRole(rawRoles);

    const userInfo = {
      id: decoded.id,
      email: decoded.email || email,
      nom: decoded.nom || '',
      prenom: decoded.prenom || '',
      role: primaryRole,   // rôle principal pour la redirection et l'affichage
      roles: rawRoles      // tous les rôles pour le switch d'interface
    };

    localStorage.setItem('token', token);
    localStorage.setItem('refreshToken', data.refreshToken ?? '');
    localStorage.setItem('userInfo', JSON.stringify(userInfo));

    if (!ROUTES[primaryRole]) {
      throw new Error("Rôle non reconnu");
    }

    window.location.replace(ROUTES[primaryRole]);
    return primaryRole;

  } catch (error) {
    localStorage.removeItem('token');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('userInfo');
    throw error;
  }
};

export const logout = async () => {
  const refreshToken = localStorage.getItem('refreshToken');
  try {
    if (refreshToken) await api.post('/logout', { refreshToken });
  } catch {
  } finally {
    localStorage.clear();
  }
};

export const isAuthenticated = (): boolean => {
  const token = localStorage.getItem('token');
  if (!token) return false;
  const decoded = parseJwt(token);
  return decoded ? (!decoded.exp || decoded.exp * 1000 > Date.now()) : false;
};

export const getCurrentUserInfo = () => {
  try {
    return JSON.parse(localStorage.getItem('userInfo') || 'null');
  } catch { return null; }
};

export const getCurrentUserRole = (): string | null => {
  return getCurrentUserInfo()?.role || null;
};

// Retourne la liste des rôles sous forme de tableau
export const getUserRoles = (): string[] => {
  const raw: string = getCurrentUserInfo()?.roles || '';
  return raw ? raw.split(',').map((r: string) => r.trim()) : [];
};

// Vérifie si l'utilisateur a un rôle donné
export const hasRole = (role: string): boolean => {
  return getUserRoles().includes(role);
};

// Alias
export const getUserRole = getCurrentUserRole;