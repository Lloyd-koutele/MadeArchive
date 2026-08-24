import React, { useState, useEffect } from "react";
import { createUser as registerUserAPI } from "../services/admin/AdminService";
import { getAllUOs } from "../services/organisation/UOService";
import UOTreeSelect from "../organisation/UOTreeSelect";
import '../Style/Admin/CreateUser.css';

interface RoleField {
    name: "ADMIN" | "ADMIN_UO" | "EDITOR" | "USER";
}

interface UserForm {
    nom: string;
    prenom: string;
    email: string;
    password: string;
    telephone: string;
    roles: RoleField[];
}

interface UONode {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

interface CreateUserProps {
    onsuccess?: () => void;
    restrictToUO?: { id: number; nom: string };
}

function CreateUser({ onsuccess, restrictToUO }: CreateUserProps) {
    const [user, setUser] = useState<UserForm>({
        nom: "", prenom: "", email: "", password: "",
        telephone: "", roles: []
    });

    const [error, setError] = useState<string>("");
    const [success, setSuccess] = useState<string>("");
    const [showPassword, setShowPassword] = useState<boolean>(false);

    const [uos, setUos] = useState<UONode[]>([]);
    const [selectedUO, setSelectedUO] = useState<number | null>(restrictToUO?.id ?? null);

    useEffect(() => {
        if (restrictToUO) return;
        getAllUOs().then(setUos).catch(() => {});
    }, [restrictToUO]);

    useEffect(() => {
        if (restrictToUO) setSelectedUO(restrictToUO.id);
    }, [restrictToUO]);

    const isGlobalAdmin = !restrictToUO && user.roles.some(r => r.name === "ADMIN");

    const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        setUser({ ...user, [e.target.name]: e.target.value });
    };

    const handleRoleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const value = e.target.value as RoleField["name"];
        const checked = e.target.checked;

        let updatedRoles = [...user.roles];

        if (checked) {
            if (!updatedRoles.some(r => r.name === value)) {
                updatedRoles.push({ name: value });
            }
        } else {
            updatedRoles = updatedRoles.filter(r => r.name !== value);
        }

        setUser({ ...user, roles: updatedRoles });

        if (!restrictToUO && value === "ADMIN" && checked) {
            setSelectedUO(null);
        }
    };

    const validateForm = (): boolean => {
        if (!user.nom.trim() || !user.prenom.trim() || !user.email.trim() || !user.password || user.roles.length === 0 || !user.telephone.trim()) {
            setError('Tous les champs sont obligatoires (sélectionnez au moins un rôle)');
            return false;
        }
        if (user.telephone.trim().length < 8) {
            setError('Le numéro de téléphone doit contenir au moins 8 caractères');
            return false;
        }
        if (user.password.length < 6) {
            setError('Le mot de passe doit contenir au moins 6 caractères');
            return false;
        }
        if (!/^\S+@\S+\.\S+$/.test(user.email.trim())) {
            setError('Email invalide');
            return false;
        }
        if (isGlobalAdmin && selectedUO !== null) {
            setError("Un ADMIN ne doit pas être rattaché à une unité organisationnelle");
            return false;
        }
        if (!isGlobalAdmin && selectedUO === null) {
            setError("Une unité organisationnelle est obligatoire pour ce rôle");
            return false;
        }
        setError('');
        return true;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();

        if (!validateForm()) return;

        const userToSend = {
            ...user,
            nom: user.nom.trim(),
            prenom: user.prenom.trim(),
            email: user.email.trim().toLowerCase(),
            telephone: user.telephone.trim()
        };

        setError('');
        setSuccess('');

        try {
            const uoIds = selectedUO !== null ? [selectedUO] : [];
            await registerUserAPI(userToSend, uoIds);

            setSuccess('Utilisateur créé avec succès');
            setUser({ nom: '', prenom: '', password: '', email: '', roles: [], telephone: '' });
            setSelectedUO(restrictToUO?.id ?? null);
            setTimeout(() => onsuccess?.(), 2000);
        } catch (err: any) {
            setError(err.response?.data?.message || err.message || "Erreur lors de la création de l'utilisateur");
        }
    };

    return (
        <div>
            {error && <div className="form-error">{error}</div>}
            {success && <div className="form-success">{success}</div>}

            <form onSubmit={handleSubmit}>
                <div className="form-grid">

                    <div className="form-field">
                        <input
                            id="user-nom"
                            type="text"
                            name="nom"
                            placeholder="Nom" aria-label="Nom"
                            className="form-field-input"
                            value={user.nom}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <div className="form-field">
                        <input
                            id="user-prenom"
                            type="text"
                            name="prenom"
                            placeholder="Prénom" aria-label="Prénom"
                            className="form-field-input"
                            value={user.prenom}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <div className="form-field">
                        <input
                            id="user-email"
                            type="email"
                            name="email"
                            placeholder="Email" aria-label="Email"
                            className="form-field-input"
                            value={user.email}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <div className="form-field">
                        <input
                            id="user-telephone"
                            type="text"
                            name="telephone"
                            placeholder="Téléphone" aria-label="Téléphone"
                            className="form-field-input"
                            value={user.telephone}
                            onChange={handleChange}
                            required
                        />
                    </div>

                    <div className="form-field form-field-password">
                        <input
                            id="user-password"
                            type={showPassword ? "text" : "password"}
                            name="password"
                            placeholder="Mot de passe" aria-label="Mot de passe"
                            className="form-field-input"
                            value={user.password}
                            onChange={handleChange}
                            required
                            autoComplete="new-password"
                        />

                        <button
                            type="button"
                            className="password-toggle-btn"
                            onClick={() => setShowPassword(!showPassword)}
                            aria-label={showPassword ? "Masquer le mot de passe" : "Afficher le mot de passe"}
                        >
                            {showPassword ? <i className="fa-solid fa-eye"></i> : <i className="fa-regular fa-eye-slash"></i>}
                        </button>
                    </div>

                    <fieldset className="form-field-roles">
                        <legend className="roles-label">Rôles de l'utilisateur :</legend>
                        <div className="checkbox-group">
                            {!restrictToUO && (
                                <label className="checkbox-label">
                                    <input
                                        type="checkbox"
                                        value="ADMIN"
                                        checked={user.roles.some(r => r.name === "ADMIN")}
                                        onChange={handleRoleChange}
                                    />
                                    Administrateur
                                </label>
                            )}

                            <label className="checkbox-label">
                                <input
                                    type="checkbox"
                                    value="ADMIN_UO"
                                    checked={user.roles.some(r => r.name === "ADMIN_UO")}
                                    onChange={handleRoleChange}
                                />
                                Administrateur d'unité
                            </label>

                            <label className="checkbox-label">
                                <input
                                    type="checkbox"
                                    value="EDITOR"
                                    checked={user.roles.some(r => r.name === "EDITOR")}
                                    onChange={handleRoleChange}
                                />
                                Éditeur
                            </label>

                            <label className="checkbox-label">
                                <input
                                    type="checkbox"
                                    value="USER"
                                    checked={user.roles.some(r => r.name === "USER")}
                                    onChange={handleRoleChange}
                                />
                                Utilisateur
                            </label>
                        </div>
                    </fieldset>

                    {restrictToUO ? (
                        <div className="form-field-roles">
                            <p className="roles-label">Unité organisationnelle : <strong>{restrictToUO.nom}</strong></p>
                        </div>
                    ) : (
                        <fieldset className="form-field-roles">
                            <legend className="roles-label">
                                Unité organisationnelle {isGlobalAdmin ? "(désactivée pour un ADMIN)" : "(une seule, obligatoire)"} :
                            </legend>
                            <UOTreeSelect
                                nodes={uos}
                                value={selectedUO}
                                onChange={setSelectedUO}
                                disabled={isGlobalAdmin}
                            />
                        </fieldset>
                    )}

                    <button type="submit" className="form-submit-btn">
                        Créer l'utilisateur
                    </button>
                </div>
            </form>
        </div>
    );
}

export default CreateUser;