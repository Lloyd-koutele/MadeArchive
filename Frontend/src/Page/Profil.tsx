// Profil.tsx — RoleField complété
import React, { useState, useEffect } from 'react';
import { getMe } from "../services/user/User"; 
import { updateMe } from '../services/user/User';
import { FaEye, FaEyeSlash } from 'react-icons/fa';
import { getCurrentUserInfo } from "../auth/authService";
import "../Style/Page/Profil.css"

interface RoleField {
    name: "ADMIN" | "ADMIN_UO" | "EDITOR" | "USER";
}

interface UserProfile {
    id: string;
    nom: string;
    prenom: string;
    email: string;
    telephone: string;
    roles: RoleField[];
}

interface ProfileProps {
    userId?: string; 
}

function Profile({ userId: propUserId }: ProfileProps) {
    const [profile, setProfile] = useState<UserProfile | null>(null);
    const [isEditing, setIsEditing] = useState<boolean>(false);
    
    const [nom, setNom] = useState('');
    const [prenom, setPrenom] = useState('');
    const [telephone, setTelephone] = useState('');
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [isLoading, setIsLoading] = useState<boolean>(true);
    const [error, setError] = useState<string>("");
    const [success, setSuccess] = useState<string>("");

    useEffect(() => {
        const checkAndLoadProfile = () => {
            const userInfo = getCurrentUserInfo();
            
            const targetId = userInfo?.id;
            
            if (targetId && targetId !== 'undefined') {
                loadProfile(targetId);
            } else {
                setIsLoading(true);
            }
        };

        checkAndLoadProfile();
        
    }, [propUserId]);

    const loadProfile = async (idToLoad: string) => {
        setIsLoading(true);
        setError("");
        try {
            const data = await getMe(idToLoad);
            setProfile(data);
            setNom(data.nom || '');
            setPrenom(data.prenom || '');
            setTelephone(data.telephone || '');
        } catch (err: any) {
            setError(err || "Erreur lors du chargement du profil");
        } finally {
            setIsLoading(false);
        }
    };

const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setSuccess("");
    setIsLoading(true);

    try {
        const userInfo = getCurrentUserInfo();
        const currentId = propUserId || userInfo?.id;

        if (!currentId) {
            setError("ID utilisateur introuvable");
            return;
        }

        const payload: any = { nom, prenom, telephone };
        if (password.trim() !== "") {
            payload.password = password;
        }

        await updateMe(currentId, payload);

        const passwordChanged = password.trim() !== "";
        
        if (passwordChanged) {
            // Efface immédiatement le mot de passe tapé de l'état local et masque le champ —
            // sinon il reste affiché en clair à l'écran (si l'œil est activé) pendant les
            // 3 secondes qui précèdent la redirection, alors que le serveur, lui, ne l'a
            // jamais renvoyé (voir UserService.updateMe → convertToDtoWithoutPassword).
            setPassword("");
            setShowPassword(false);
            setSuccess("Profil mis à jour. Votre mot de passe a changé, vous allez être redirigé vers la page de connexion...");
            setTimeout(() => {
                localStorage.clear();
                window.location.replace('/login');
            }, 3000);
        } else {
            setSuccess("Profil mis à jour avec succès");
            setIsEditing(false);
            setPassword("");
            await loadProfile(currentId);
        }

    } catch (err: any) {
        setError(err.message || "Erreur lors de la modification");
    } finally {
        setIsLoading(false);
    }
};

    return (
        <div className="profile-view-container">
            <h2 className="profile-title">Mon Profil</h2>
            
            {error && <div className="profile-alert profile-alert-error">{error}</div>}
            {success && <div className="profile-alert profile-alert-success">{success}</div>}

            <div className="profile-card">
                {!isEditing ? (
                    <>
                        <div className="details-row"><strong>Nom :</strong> {profile?.nom}</div>
                        <div className="details-row"><strong>Prénom :</strong> {profile?.prenom}</div>
                        <div className="details-row"><strong>Email :</strong> {profile?.email}</div>
                        <div className="details-row"><strong>Téléphone :</strong> {profile?.telephone}</div>
                        <div className="details-row">
                            <strong>Rôles :</strong> {profile?.roles?.map(r => r.name).join(', ')}
                        </div>
                        <button onClick={() => setIsEditing(true)} className="profile-edit-btn">
                            Modifier mes informations
                        </button>
                    </>

                ) : (
                    <form onSubmit={handleSubmit} className="form-grid">
                        <div className="form-field">
                            <input id="p-nom" type="text" className="form-field-input" placeholder="Nom" aria-label="Nom" value={nom} onChange={e => setNom(e.target.value)} required />
                        </div>
                        <div className="form-field">
                            <input id="p-prenom" type="text" className="form-field-input" placeholder="Prénom" aria-label="Prénom" value={prenom} onChange={e => setPrenom(e.target.value)} required />
                        </div>
                        <div className="form-field">
                            <input id="p-tel" type="text" className="form-field-input" placeholder="Téléphone" aria-label="Téléphone" value={telephone} onChange={e => setTelephone(e.target.value)} required />
                        </div>
                        
                        <div className="form-field form-field-password profile-field-fullwidth">
                            <input id="p-pass" type={showPassword ? 'text' : 'password'} className="form-field-input" placeholder="Nouveau mot de passe (laisser vide si inchangé)" aria-label="Nouveau mot de passe (laisser vide si inchangé)" value={password} onChange={e => setPassword(e.target.value)} autoComplete="new-password" />
                            <button type="button" className="password-toggle-btn" onClick={() => setShowPassword(!showPassword)}>
                                {showPassword ? <FaEyeSlash /> : <FaEye />}
                            </button>
                        </div>
                        
                        <div className="profile-form-actions">
                            <button type="submit" className="form-submit-btn profile-btn-reset">
                                Sauvegarder
                            </button>
                            <button type="button" onClick={() => setIsEditing(false)} className="details-close-btn profile-btn-reset">
                                Annuler
                            </button>
                        </div>
                    </form>
                )}
            </div>
        </div>
    );
}

export default Profile;