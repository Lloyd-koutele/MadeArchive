import React, { useState, useEffect } from "react";
import { updateUser as updateUserAPI } from "../services/admin/AdminService";
import { getAllUOs } from "../services/organisation/UOService";
import UOTreeSelect from "../organisation/UOTreeSelect";
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/Admin/UpdateUser.css';

interface RoleField {
    name: "ADMIN" | "ADMIN_UO" | "EDITOR" | "USER";
}

interface UserForm {
    id: string;
    nom: string;
    prenom: string;
    email: string;
    password?: string;
    telephone: string;
    roles: RoleField[];
}

interface UONode {
    id: number;
    nom: string;
    parentId: number | null;
    cheminComplet: string;
}

interface UpdateUserProps {
    initialData?: UserForm;
    onsuccess?: () => void;
    restrictToUO?: { id: number; nom: string };
}

function UpdateUser({ initialData, onsuccess, restrictToUO }: UpdateUserProps) {
    const notify = useNotify();
    const [user, setUser] = useState<UserForm>({
        id: "", nom: "", prenom: "", email: "", password: "",
        telephone: "", roles: []
    });

    const [showPassword, setShowPassword] = useState<boolean>(false);

    const [uos, setUos] = useState<UONode[]>([]);
    const [selectedUO, setSelectedUO] = useState<number | null>(null);

    useEffect(() => {
        if (initialData) {
            setUser({
                ...initialData,
                password: ""
            });
            setSelectedUO(null);
        }
    }, [initialData]);

    useEffect(() => {
        if (restrictToUO) return;
        getAllUOs().then(setUos).catch(() => {});
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
        if (user.telephone.trim().length < 8) {
            notify.error('Le numéro de téléphone doit contenir au moins 8 caractères');
            return false;
        }
        if (user.password && user.password.length < 6) {
            notify.error('Le mot de passe doit contenir au moins 6 caractères');
            return false;
        }
        if (!/^\S+@\S+\.\S+$/.test(user.email.trim())) {
            notify.error('Email invalide');
            return false;
        }
        if (isGlobalAdmin && selectedUO !== null) {
            notify.error("Un ADMIN ne doit pas être rattaché à une unité organisationnelle");
            return false;
        }
        return true;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();

        if (!validateForm()) return;

        const userId = user.id;
        if (!userId) {
            notify.error('ID utilisateur manquant');
            return;
        }

        const userToSend: Partial<UserForm> = {
            id: user.id,
            nom: user.nom.trim(),
            prenom: user.prenom.trim(),
            email: user.email.trim().toLowerCase(),
            telephone: user.telephone.trim(),
            roles: user.roles
        };

        if (user.password && user.password.trim() !== "") {
            userToSend.password = user.password;
        }

        try {
            await updateUserAPI(userId, userToSend, selectedUO ?? undefined);

            notify.success('Utilisateur mis à jour avec succès');
            setUser({ id: '', nom: '', prenom: '', password: '', email: '', roles: [], telephone: '' });
            setSelectedUO(null);
            setTimeout(() => onsuccess?.(), 1500);
        } catch (err: any) {
            notify.error(err.response?.data?.message || err.message || "Erreur lors de la mise à jour de l'utilisateur");
        }
    };

    return (
        <div>
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
                            placeholder="Mot de passe (laisser vide pour ne pas modifier)"
                            aria-label="Mot de passe (laisser vide pour ne pas modifier)"
                            className="form-field-input"
                            value={user.password || ""}
                            onChange={handleChange}
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
                    ) : !isGlobalAdmin && (
                        <fieldset className="form-field-roles">
                            <legend className="roles-label">
                                Changer l'unité organisationnelle (laisser vide pour ne pas modifier) :
                            </legend>
                            <UOTreeSelect
                                nodes={uos}
                                value={selectedUO}
                                onChange={setSelectedUO}
                            />
                        </fieldset>
                    )}

                    <button type="submit" className="form-submit-btn">
                        Mettre à jour
                    </button>
                </div>
            </form>
        </div>
    );
}

export default UpdateUser;