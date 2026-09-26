import { useEffect, useRef } from 'react';

const MAX_SIZE_REM = 0.8;   // taille "normale" des autres cellules du tableau
const MIN_SIZE_REM = 0.6;   // plancher de lisibilité — en dessous, on tronque plutôt
const STEP_REM = 0.02;

/**
 * Affiche tous les rôles d'un utilisateur sur une seule ligne, sans jamais
 * élargir la cellule ni retomber à la ligne : la police rétrécit par petits
 * pas jusqu'à ce que le texte tienne dans la largeur disponible. Si même la
 * taille plancher ne suffit pas (ex. les 4 rôles à la fois), le texte est
 * tronqué avec "…" (voir .roles-fit-text en CSS) plutôt que de casser la
 * mise en page — le nom complet reste consultable via l'infobulle native
 * (title) et le bouton "Voir".
 */
function RolesFitCell({ roles }: { roles: string[] }) {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const textRef = useRef<HTMLSpanElement | null>(null);
    const label = roles.join(' · ');

    useEffect(() => {
        const container = containerRef.current;
        const text = textRef.current;
        if (!container || !text) return;

        const fit = () => {
            let size = MAX_SIZE_REM;
            text.style.fontSize = `${size}rem`;
            while (text.scrollWidth > container.clientWidth && size > MIN_SIZE_REM) {
                size = Math.max(MIN_SIZE_REM, size - STEP_REM);
                text.style.fontSize = `${size}rem`;
            }
        };

        fit();

        // Redéclenché à chaque changement de largeur de la cellule (fenêtre
        // redimensionnée, sidebar repliée/dépliée...), pas seulement au montage.
        const observer = new ResizeObserver(fit);
        observer.observe(container);
        return () => observer.disconnect();
    }, [label]);

    return (
        <div ref={containerRef} className="roles-fit-container" title={label}>
            <span ref={textRef} className="roles-fit-text">{label}</span>
        </div>
    );
}

export default RolesFitCell;
