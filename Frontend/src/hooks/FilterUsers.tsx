// FilterUsers.tsx — ADMIN_UO ajouté au filtre par rôle
import React from 'react';
import '../Style/hooks/FilterUsers.css';

interface UserFilters {
    nom: string;
    prenom: string;
    email: string;
    telephone: string;
    roles: string[];
}

interface FilterUsersProps {
    filters: UserFilters;
    onChange: (updatedFilters: UserFilters) => void;
}

function FilterUsers({ filters, onChange }: FilterUsersProps) {

    const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        onChange({ ...filters, [e.target.name]: e.target.value });
    };

    // `roles` reste un tableau (le filtrage en aval fait un .includes) : la liste
    // déroulante le remplit avec zéro ou un rôle.
    const handleRoleSelect = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const role = e.target.value;
        onChange({ ...filters, roles: role ? [role] : [] });
    };

    const labelMap: Record<string, string> = {
        ADMIN: 'Administrateur',
        ADMIN_UO: "Administrateur d'unité",
        EDITOR: 'Éditeur',
        USER: 'Utilisateur'
    };

    return (
        <div className="filter-bar">

            <div className="filter-inputs-row">
                <input
                    className="filter-input"
                    name="nom"
                    placeholder="Nom"
                    aria-label="Filtrer par nom"
                    value={filters.nom}
                    onChange={handleChange}
                />
                <input
                    className="filter-input"
                    name="prenom"
                    placeholder="Prénom"
                    aria-label="Filtrer par prénom"
                    value={filters.prenom}
                    onChange={handleChange}
                />
                <input
                    className="filter-input"
                    name="email"
                    placeholder="Email"
                    aria-label="Filtrer par email"
                    value={filters.email}
                    onChange={handleChange}
                />
                <input
                    className="filter-input"
                    name="telephone"
                    placeholder="Téléphone"
                    aria-label="Filtrer par téléphone"
                    value={filters.telephone}
                    onChange={handleChange}
                />
            </div>

            <div className="filter-bottom-row">
                <select
                    className="filter-role-select"
                    aria-label="Filtrer par rôle"
                    value={filters.roles[0] ?? ''}
                    onChange={handleRoleSelect}
                >
                    <option value="">Tous les rôles</option>
                    {['ADMIN', 'ADMIN_UO', 'EDITOR', 'USER'].map((role) => (
                        <option key={role} value={role}>{labelMap[role]}</option>
                    ))}
                </select>

                <button
                    className="filter-reset-btn"
                    type="button"
                    title="Réinitialiser les filtres"
                    aria-label="Réinitialiser les filtres"
                    onClick={() => onChange({ nom: '', prenom: '', email: '', telephone: '', roles: [] })}
                >
                    <i className="fa-solid fa-rotate-left" />
                </button>
            </div>

        </div>
    );
}

export default FilterUsers;