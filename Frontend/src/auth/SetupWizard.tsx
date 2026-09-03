import { useState } from 'react';
import { FaEye, FaEyeSlash } from 'react-icons/fa';
import { useNotify } from '../notifications/NotificationProvider';
import api from '../services/api';
import '../Style/auth/login.css';

/**
 * Assistant de première configuration — affiché par App.tsx UNIQUEMENT tant
 * qu'aucun administrateur n'existe (voir GET /api/public/setup/status).
 * Remplace l'admin auparavant codé en dur dans le code source du backend —
 * voir config.InitialAdminCreation / config.InitialAdminProperties.
 *
 * Une fois soumis avec succès, redirige vers /login — pas de connexion
 * automatique : l'admin fraîchement créé se connecte normalement, comme
 * n'importe quel autre compte par la suite.
 */
function SetupWizard() {
  const notify = useNotify();
  const [nom, setNom] = useState('');
  const [prenom, setPrenom] = useState('');
  const [email, setEmail] = useState('');
  const [telephone, setTelephone] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!nom || !prenom || !email || !telephone || !password) {
      notify.error('Veuillez remplir tous les champs.');
      return;
    }
    if (password !== confirmation) {
      notify.error('Les deux mots de passe ne correspondent pas.');
      return;
    }
    if (password.length < 6) {
      notify.error('Le mot de passe doit contenir au moins 6 caractères.');
      return;
    }

    setIsLoading(true);
    try {
      await api.post('/public/setup/admin', { nom, prenom, email, telephone, password });
      notify.success('Administrateur créé — vous pouvez maintenant vous connecter.');
      window.location.replace('/login');
    } catch (error: any) {
      const message = error.response?.data || error.message || 'Erreur lors de la création du compte.';
      notify.error(typeof message === 'string' ? message : 'Erreur lors de la création du compte.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-card">
        <div className="login-header">
          <h2 className="login-title">Configuration initiale</h2>
          <p className="login-subtitle">
            Aucun administrateur n'existe encore — créez le premier compte pour démarrer.
          </p>
        </div>

        <form className="login-form" onSubmit={handleSubmit}>
          <div className="field">
            <input
              id="nom" type="text" className="field-input" placeholder=" "
              value={nom} onChange={(e) => setNom(e.target.value)} required disabled={isLoading}
            />
            <label htmlFor="nom">Nom</label>
          </div>

          <div className="field">
            <input
              id="prenom" type="text" className="field-input" placeholder=" "
              value={prenom} onChange={(e) => setPrenom(e.target.value)} required disabled={isLoading}
            />
            <label htmlFor="prenom">Prénom</label>
          </div>

          <div className="field">
            <input
              id="email" type="email" className="field-input" placeholder=" " autoComplete="email"
              value={email} onChange={(e) => setEmail(e.target.value)} required disabled={isLoading}
            />
            <label htmlFor="email">Email</label>
          </div>

          <div className="field">
            <input
              id="telephone" type="tel" className="field-input" placeholder=" "
              value={telephone} onChange={(e) => setTelephone(e.target.value)} required disabled={isLoading}
            />
            <label htmlFor="telephone">Téléphone</label>
          </div>

          <div className="field">
            <input
              id="password" type={showPassword ? 'text' : 'password'} className="field-input"
              placeholder=" " autoComplete="new-password"
              value={password} onChange={(e) => setPassword(e.target.value)} required disabled={isLoading}
            />
            <label htmlFor="password">Mot de passe</label>
            <button
              type="button" className="password-toggle" onClick={() => setShowPassword(!showPassword)}
              disabled={isLoading} aria-label={showPassword ? 'Cacher' : 'Afficher'}
            >
              {showPassword ? <FaEyeSlash /> : <FaEye />}
            </button>
          </div>

          <div className="field">
            <input
              id="confirmation" type={showPassword ? 'text' : 'password'} className="field-input"
              placeholder=" " autoComplete="new-password"
              value={confirmation} onChange={(e) => setConfirmation(e.target.value)} required disabled={isLoading}
            />
            <label htmlFor="confirmation">Confirmer le mot de passe</label>
          </div>

          <div className="container">
            <button type="submit" className="login-button" disabled={isLoading}>
              {isLoading ? 'Création...' : "Créer l'administrateur"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default SetupWizard;
