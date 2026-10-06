/**
 * Position d'un menu contextuel (clic droit / Ctrl+clic) ancré à l'ÉLÉMENT cliqué — comme le menu d'un bouton "...",
 * jamais au point exact du curseur ni au centre de l'écran : bord droit du menu aligné sur le bord droit de l'élément,
 * juste en dessous (ou juste au-dessus s'il n'y a pas la place). Le menu doit porter la classe
 * `dossier-context-menu-anchored` (translateX(-100%), voir Editor.css) et poser ces valeurs en `position: fixed`.
 */
export interface PositionMenu {
    top?: number;
    bottom?: number;
    left: number;
}

export function positionSousElement(el: HTMLElement, hauteurEstimee = 200, largeurMin = 200): PositionMenu {
    const r = el.getBoundingClientRect();
    const left = Math.min(Math.max(r.right, largeurMin), window.innerWidth - 8);
    return r.bottom + hauteurEstimee < window.innerHeight
        ? { top: r.bottom + 4, left }
        : { bottom: window.innerHeight - r.top + 4, left };
}
