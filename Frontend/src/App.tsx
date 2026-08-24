// App.tsx
import { createBrowserRouter, RouterProvider, Navigate } from 'react-router-dom';
import Login from './auth/Login.tsx';
import Home from './Page/Home.tsx';
import AdminDashboard from './Admin/AdminDahboard.tsx';
import EditorDashboard from './Editor/EditorDasboard.tsx';
import UserDashboard from './User/UserDashboard.tsx';
import './Style/global.css'

import { getUserRole, hasRole, ROUTES } from './auth/authService';
import AdminUoDashboard from './Admin/AdminUoDashboard.tsx';
import SessionGuard from './auth/SessionGuard.tsx';
import AttestationPublique from './Page/AttestationPublique.tsx';

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

// App principal
function App() {
  return <RouterProvider router={router} />;
}

export default App;