import { useEffect, useState } from 'react';
import { getAllTypeDocuments, getTypeDocumentsByUO } from '../services/document/TypedocumentService';
import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import { getAllUOs, getMyUO, getSousArbre } from '../services/organisation/UOService';
import { declencherFixityCheck } from '../services/document/FixityCheckService';
import type { FixityCheckScope } from '../services/document/FixityCheckService';
import { hasRole } from '../auth/authService';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/Admin/FixityCheckPanel.css';

interface UOOption {
    id: number;
    nom: string;
}

/**
 * Déclenchement MANUEL du contrôle d'intégrité (fixity check) — voir
 * FixityCheckTriggerService (backend). La tâche planifiée vérifie déjà TOUT
 * une fois par jour à 3h du matin ; ce panneau sert pour un doute ponctuel
 * (ex. incident MinIO suspecté sur un type précis) sans attendre la nuit.
 *
 * "Tout le système" n'est proposé qu'à ROLE_ADMIN (le backend le refuse de
 * toute façon aux ADMIN_UO — ce n'est qu'un filtre d'affichage, pas la
 * garde réelle). Chaque périmètre individuel (un type, une UO, ou "tout")
 * a son propre cooldown de 6h côté serveur ; le message d'erreur renvoyé
 * (déjà formulé côté backend) est affiché tel quel.
 */
function FixityCheckPanel() {
    const notify = useNotify();
    const confirm = useConfirm();
    const estAdmin = hasRole('ADMIN');

    const [scope, setScope] = useState<FixityCheckScope>('TYPES');
    const [types, setTypes] = useState<TypeDocumentDto[]>([]);
    const [uos, setUos] = useState<UOOption[]>([]);
    const [selectedTypeIds, setSelectedTypeIds] = useState<Set<number>>(new Set());
    const [selectedUoIds, setSelectedUoIds] = useState<Set<number>>(new Set());
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        // getAllTypeDocuments()/getAllUOs() sont réservés à ROLE_ADMIN côté backend
        // (@Secured("ROLE_ADMIN") — voir TypeDocumentController/UOController) : un
        // ADMIN_UO qui arrive ici prenait systématiquement un 403 "Access denied"
        // sur les DEUX appels avant même de voir le panneau, laissant "types"/"uos"
        // vides. Scopé à sa propre UO (+ sous-arbre) via les mêmes endpoints déjà
        // utilisés ailleurs dans l'admin UO pour ce rôle.
        const charger = async () => {
            if (estAdmin)
            {
                const [typesRes, uosRes] = await Promise.all([getAllTypeDocuments(), getAllUOs()]);
                setTypes(typesRes);
                setUos(uosRes);
            }
            else
            {
                const monUO = await getMyUO();
                const [typesRes, uosRes] = await Promise.all([
                    getTypeDocumentsByUO(monUO.id),
                    getSousArbre(monUO.id),
                ]);
                setTypes(typesRes);
                setUos(uosRes);
            }
        };

        charger()
            .catch(err => notify.error(err.message ?? 'Erreur chargement des types/UO'))
            .finally(() => setLoading(false));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const toggle = (set: Set<number>, setSet: (s: Set<number>) => void, id: number) => {
        const next = new Set(set);
        if (next.has(id)) next.delete(id); else next.add(id);
        setSet(next);
    };

    const handleDeclencher = async () => {
        if (scope === 'TYPES' && selectedTypeIds.size === 0) {
            notify.error('Sélectionnez au moins un type de document');
            return;
        }
        if (scope === 'UO' && selectedUoIds.size === 0) {
            notify.error('Sélectionnez au moins une UO');
            return;
        }
        if (scope === 'TOUT') {
            if (!(await confirm(
                'Vérifier l\'intégrité de TOUT le catalogue ? Cette opération télécharge et déchiffre chaque '
                + 'document archivé — potentiellement longue selon le volume. Vous serez notifié à la fin.'
            ))) return;
        }

        setSubmitting(true);
        try {
            const message = await declencherFixityCheck({
                scope,
                typeDocumentIds: scope === 'TYPES' ? Array.from(selectedTypeIds) : undefined,
                uoIds: scope === 'UO' ? Array.from(selectedUoIds) : undefined,
            });
            notify.success(message);
            setSelectedTypeIds(new Set());
            setSelectedUoIds(new Set());
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors du déclenchement du contrôle');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="fixity-panel">
            <div className="fixity-panel-header">
                <h2>Contrôle d'intégrité</h2>
            </div>

            <div className="fixity-scope-toggle" role="group" aria-label="Périmètre de la vérification">
                <button
                    type="button"
                    className={`fixity-scope-btn ${scope === 'TYPES' ? 'active' : ''}`}
                    onClick={() => setScope('TYPES')}
                >
                    <i className="fa-solid fa-tags" /> Par type de document
                </button>
                <button
                    type="button"
                    className={`fixity-scope-btn ${scope === 'UO' ? 'active' : ''}`}
                    onClick={() => setScope('UO')}
                >
                    <i className="fa-solid fa-sitemap" /> Par UO
                </button>
                {estAdmin && (
                    <button
                        type="button"
                        className={`fixity-scope-btn ${scope === 'TOUT' ? 'active' : ''}`}
                        onClick={() => setScope('TOUT')}
                    >
                        <i className="fa-solid fa-globe" /> Tout le système
                    </button>
                )}
            </div>

            {loading ? (
                <div className="td-loading"><i className="fa-solid fa-spinner fa-spin" /> Chargement...</div>
            ) : (
                <>
                    {scope === 'TYPES' && (
                        <div className="fixity-checklist">
                            {types.length === 0 && <p className="fixity-empty">Aucun type de document.</p>}
                            {types.map(t => (
                                <label key={t.id} className="fixity-checklist-item">
                                    <input
                                        type="checkbox"
                                        checked={t.id !== undefined && selectedTypeIds.has(t.id)}
                                        onChange={() => t.id !== undefined && toggle(selectedTypeIds, setSelectedTypeIds, t.id)}
                                    />
                                    {t.nom}
                                </label>
                            ))}
                        </div>
                    )}

                    {scope === 'UO' && (
                        <div className="fixity-checklist">
                            {uos.length === 0 && <p className="fixity-empty">Aucune UO.</p>}
                            {uos.map(u => (
                                <label key={u.id} className="fixity-checklist-item">
                                    <input
                                        type="checkbox"
                                        checked={selectedUoIds.has(u.id)}
                                        onChange={() => toggle(selectedUoIds, setSelectedUoIds, u.id)}
                                    />
                                    {u.nom}
                                </label>
                            ))}
                        </div>
                    )}

                    {scope === 'TOUT' && (
                        <p className="fixity-empty">
                            Vérifiera l'intégralité des documents archivés, tous types et UO confondus.
                        </p>
                    )}

                    <button
                        type="button"
                        className="form-submit-btn up-submit"
                        onClick={handleDeclencher}
                        disabled={submitting}
                    >
                        {submitting
                            ? <><i className="fa-solid fa-spinner fa-spin" /> Déclenchement…</>
                            : <><i className="fa-solid fa-shield-halved" /> Lancer la vérification</>}
                    </button>
                </>
            )}
        </div>
    );
}

export default FixityCheckPanel;
