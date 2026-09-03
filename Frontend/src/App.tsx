// App.tsx
import { useEffect, useState } from 'react';
import { createBrowserRouter, RouterProvider, Navigate } from 'react-router-dom';
import Login from './auth/Login.tsx';
import SetupWizard from './auth/SetupWizard.tsx';
import Home from './Page/Home.tsx';
import AdminDashboard from './Admin/AdminDahboard.tsx';
import EditorDashboard from './Editor/EditorDasboard.tsx';
import UserDashboard from './User/UserDashboard.tsx';
import './Style/global.css'

import { getUserRole, hasRole, ROUTES } from './auth/authService';
import AdminUoDashboard from './Admin/AdminUoDashboard.tsx';
import SessionGuard from './auth/SessionGuard.tsx';
import AttestationPublique from './Page/AttestationPublique.tsx';
import { NotificationProvider } from './notifications/NotificationProvider.tsx';
import NotificationStack from './notifications/NotificationStack.tsx';
import { ConfirmProvider } from './notifications/ConfirmProvider.tsx';
import api from './services/api';

// requiredRole accepte désormais un seul rôle ou une liste (any-of)
const PrivateRoute = ({ children, requiredRole = null }) => {
    // Non authentifié
    if (!getUserRole()) {
        return <Navigate to="/login" replace />;
    }

    const requiredRoles = requiredRole
        ? (Array.isArray(requiredRole) ? requiredRole : [requiredRole])
        : null;

    // Pas de rôle requis, ou a au moins un des rôles requis → OK
    if (!requiredRoles || requiredRoles.some((r) => hasRole(r))) {
        return (
            <>
                {children}
                <SessionGuard />
            </>
        );
    }

    // N'a aucun des rôles requis → redirection vers son interface principale
    // (même mapping que la redirection de connexion — voir authService.ROUTES)
    const primaryRole = getUserRole();
    return <Navigate to={ROUTES[primaryRole] || '/login'} replace />;
};

const router = createBrowserRouter([
  { path: '/', element: <Home /> },
  { path: '/login', element: <Login /> },

  // Publique — aucune authentification (voir Page/AttestationPublique.tsx)
  { path: '/attestation/:token', element: <AttestationPublique /> },

  {
    path: '/admin', element:
      <PrivateRoute requiredRole="ADMIN">
        <AdminDashboard />
      </PrivateRoute>
  },


  {
    path: '/admin_uo', element:
      <PrivateRoute requiredRole={["ADMIN_UO"]}>
        <AdminUoDashboard />
      </PrivateRoute>
  },

  {
    path: '/editor', element:
      <PrivateRoute requiredRole={["EDITOR"]}>
        <EditorDashboard/>
      </PrivateRoute>
  },

  {
    path: '/user', element:
      <PrivateRoute requiredRole={["USER"]}>
        <UserDashboard />
      </PrivateRoute>
  },
]);

// App principal — NotificationProvider/ConfirmProvider montés une seule fois
// ici : une seule fenêtre de notifications et une seule modale de
// confirmation pour toute l'application, quel que soit l'écran affiché.
//
// setupStatus décide QUOI afficher à l'intérieur (assistant de première
// configuration vs application normale) — vérifié une seule fois au chargement,
// voir controller.SetupController côté backend. Échec réseau → 'ready' plutôt
// que de rester bloqué : mieux vaut montrer l'écran de connexion normal (qui
// donnera sa propre erreur explicite) qu'un assistant qui ne pourrait de
// toute façon pas non plus contacter le serveur.
function App() {
  const [setupStatus, setSetupStatus] = useState<'loading' | 'needs-setup' | 'ready'>('loading');

  useEffect(() => {
    api.get('/public/setup/status')
      .then(({ data }) => setSetupStatus(data.needsSetup ? 'needs-setup' : 'ready'))
      .catch(() => setSetupStatus('ready'));
  }, []);

  return (
    <NotificationProvider>
      <ConfirmProvider>
        {setupStatus === 'loading' && <div style={{ minHeight: '100vh' }} />}
        {setupStatus === 'needs-setup' && <SetupWizard />}
        {setupStatus === 'ready' && <RouterProvider router={router} />}
        <NotificationStack />
      </ConfirmProvider>
    </NotificationProvider>
  );
}

export default App;