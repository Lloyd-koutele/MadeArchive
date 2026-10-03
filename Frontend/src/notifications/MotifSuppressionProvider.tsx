import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import '../Style/notifications/Notifications.css';

/** Miroir de made.archive.entite.MotifSuppression — FIN_DE_VIE est réservé au système, jamais saisi ici. */
export type MotifSuppression = 'ERREUR_ARCHIVAGE' | 'SUPPRESSION_LEGALE' | 'AUTRE';

export const LIBELLES_MOTIF: Record<string, string> = {
    ERREUR_ARCHIVAGE: "Erreur d'archivage",
    SUPPRESSION_LEGALE: 'Suppression légale',
    AUTRE: 'Autre',
    FIN_DE_VIE: 'Fin de vie du document',
};

export interface MotifReponse {
    motif: MotifSuppression;
    commentaire: string | undefined;
}

export interface DemandeMotifOptions {
    title?: string;
    message: string;
    confirmLabel?: string;
    /** true = pas de liste de motifs, juste un texte OBLIGATOIRE (renvoyé dans `commentaire`) — ex. blocage d'une suppression. */
    texteSeul?: boolean;
}

type DemanderMotifFn = (options: DemandeMotifOptions | string) => Promise<MotifReponse | null>;

interface Pending extends DemandeMotifOptions {
    resolve: (value: MotifReponse | null) => void;
}

const MotifContext = createContext<DemanderMotifFn | null>(null);

/**
 * Demande à l'éditeur POURQUOI il supprime un document (erreur d'archivage / suppression légale /
 * autre + texte obligatoire) — remplace le simple confirm() de toute suppression. Même principe que
 * ConfirmProvider : montée une fois à la racine, API Promise (null = annulé).
 */
export function MotifSuppressionProvider({ children }: { children: ReactNode }) {
    const [pending, setPending] = useState<Pending | null>(null);
    const [motif, setMotif] = useState<MotifSuppression | ''>('');
    const [commentaire, setCommentaire] = useState('');

    const demander = useCallback<DemanderMotifFn>((options) => {
        const opts: DemandeMotifOptions = typeof options === 'string' ? { message: options } : options;
        return new Promise<MotifReponse | null>((resolve) => {
            setMotif('');
            setCommentaire('');
            setPending(prev => {
                prev?.resolve(null);
                return { ...opts, resolve };
            });
        });
    }, []);

    const fermer = (reponse: MotifReponse | null) => {
        pending?.resolve(reponse);
        setPending(null);
    };

    const texteSeul = pending?.texteSeul === true;
    const valide = texteSeul
        ? commentaire.trim().length > 0
        : motif !== '' && (motif !== 'AUTRE' || commentaire.trim().length > 0);

    return (
        <MotifContext.Provider value={demander}>
            {children}
            {pending && (
                <div className="confirm-overlay" onClick={() => fermer(null)}>
                    <div className="confirm-dialog" onClick={(e) => e.stopPropagation()}>
                        <div className="confirm-dialog-header">
                            <i className="fa-solid fa-triangle-exclamation" />
                            <h3>{pending.title ?? 'Motif de la suppression'}</h3>
                        </div>
                        <p className="confirm-dialog-message">{pending.message}</p>

                        {!texteSeul && (
                        <div className="motif-choix" role="radiogroup" aria-label="Motif de la suppression">
                            {(['ERREUR_ARCHIVAGE', 'SUPPRESSION_LEGALE', 'AUTRE'] as MotifSuppression[]).map(m => (
                                <label key={m}>
                                    <input type="radio" name="motif-suppression" checked={motif === m}
                                        onChange={() => setMotif(m)} /> {LIBELLES_MOTIF[m]}
                                </label>
                            ))}
                        </div>
                        )}
                        <textarea
                            className="motif-texte"
                            placeholder={texteSeul || motif === 'AUTRE' ? 'Précisez la raison (obligatoire)' : 'Commentaire (facultatif)'}
                            aria-label="Commentaire"
                            maxLength={500}
                            rows={3}
                            value={commentaire}
                            onChange={(e) => setCommentaire(e.target.value)}
                        />

                        <div className="confirm-dialog-actions">
                            <button type="button" className="confirm-btn-cancel" onClick={() => fermer(null)}>
                                Annuler
                            </button>
                            <button
                                type="button"
                                className="confirm-btn-danger"
                                disabled={!valide}
                                onClick={() => fermer({ motif: (motif || 'AUTRE') as MotifSuppression, commentaire: commentaire.trim() || undefined })}
                            >
                                {pending.confirmLabel ?? 'Supprimer'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </MotifContext.Provider>
    );
}

/** const demanderMotif = useDemandeMotif(); const m = await demanderMotif('…'); if (!m) return; */
export function useDemandeMotif(): DemanderMotifFn {
    const ctx = useContext(MotifContext);
    if (!ctx) throw new Error('useDemandeMotif doit être utilisé sous MotifSuppressionProvider');
    return ctx;
}
