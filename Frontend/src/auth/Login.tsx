import { useState } from 'react';
import { loginUser } from './authService';
import { FaEye, FaEyeSlash } from 'react-icons/fa';
import '../Style/auth/login.css';

function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (!email || !password) {
      setError("Veuillez remplir tous les champs.");
      return;
    }
    setIsLoading(true);
    try {
      await loginUser(email, password);
      
    } catch (error) {
      console.error('Erreur de connexion:', error);
      setIsLoading(false);
      const errorMessage = error.response?.data?.message || error.message || "Erreur de connexion.";
      setError(errorMessage);
    }
  };

  return (
    <div className="login-container">
      <div className="login-card">
        <div className="login-header">
          <h2 className="login-title">Connexion</h2>
          <p className="login-subtitle">Connectez-vous pour accéder à votre espace</p>
        </div>

        {error && <div className="error-message">{error}</div>}

        <form className="login-form" onSubmit={handleSubmit}>

          {/* EMAIL */}
          <div className="field">
            <input
              id="email"
              type="email"
              className="field-input"
              placeholder=" "
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              disabled={isLoading}
            />
            <label htmlFor="email">Email</label>
          </div>

          {/* MOT DE PASSE */}
          <div className="field">
            <input
              id="password"
              type={showPassword ? 'text' : 'password'}
              className="field-input"
              placeholder=" "
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              disabled={isLoading}
            />
            <label htmlFor="password">Mot de passe</label>
            <button
              type="button"
              className="password-toggle"
              onClick={() => setShowPassword(!showPassword)}
              disabled={isLoading}
              aria-label={showPassword ? 'Cacher' : 'Afficher'}
            >
              {showPassword ? <FaEyeSlash /> : <FaEye />}
            </button>
          </div>

          <div className="container">
            <button type="submit" className="login-button" disabled={isLoading}>
              {isLoading ? 'Connexion...' : 'Se connecter'}
            </button>
            <button
              type="button"
              className="button-retour"
              onClick={() => { window.location.href = '/'; }}
            >
              Retour
            </button>
          </div>

        </form>
      </div>
    </div>
  );
}

export default Login;