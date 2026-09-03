import { useEffect, useRef } from 'react';

/**
 * Relance `refetch` chaque fois que l'onglet redevient visible/actif après
 * avoir été en arrière-plan — couvre "une autre interface a modifié une
 * donnée pendant que je regardais autre chose" (UO, projets, emplacements,
 * types de documents, utilisateurs...) sans infrastructure temps réel
 * (WebSocket) : pas de push serveur, juste un rechargement au retour sur
 * l'écran, ce qui couvre la quasi-totalité des cas réels de désynchronisation
 * entre interfaces.
 *
 * `visibilitychange` ET `focus` sont écoutés ensemble : selon le navigateur,
 * revenir depuis une autre appli déclenche parfois l'un sans l'autre. Un
 * appel en trop (les deux se déclenchant pour le même retour) est inoffensif
 * et juste ignoré par le anti-rebond ci-dessous — jamais un retour manqué.
 *
 * Ne se déclenche jamais au montage initial (ces événements ne se déclenchent
 * que sur un VRAI changement de visibilité/focus, pas à l'enregistrement du
 * listener) — le chargement initial du composant appelant reste inchangé.
 */
export function useRefetchOnFocus(refetch: () => void): void {
    const dernierAppel = useRef(0);

    useEffect(() => {
        const onVisible = () => {
            if (document.visibilityState !== 'visible') return;
            const maintenant = Date.now();
            if (maintenant - dernierAppel.current < 300) return; // anti-rebond visibilitychange+focus
            dernierAppel.current = maintenant;
            refetch();
        };
        document.addEventListener('visibilitychange', onVisible);
        window.addEventListener('focus', onVisible);
        return () => {
            document.removeEventListener('visibilitychange', onVisible);
            window.removeEventListener('focus', onVisible);
        };
    }, [refetch]);
}
