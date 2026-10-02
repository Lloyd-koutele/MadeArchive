import { useState, useEffect, useRef, useCallback } from 'react';
import * as pdfjsLib from 'pdfjs-dist';
import type { PDFDocumentProxy, PDFPageProxy, PageViewport, RenderTask, TextLayer } from 'pdfjs-dist';
// ?url : Vite renvoie l'URL finale du fichier (copié tel quel dans le build)
// plutôt que d'essayer de le bundler en JS — obligatoire pour un worker.
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import '../Style/components/PdfViewer.css';

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

interface OutlineNode {
    title: string;
    pageIndex: number | null;
    items: OutlineNode[];
}

interface PdfViewerProps {
    /** blob: URL (ou toute URL chargeable par pdf.js) du PDF à afficher. null = rien à afficher. */
    url: string | null;
    className?: string;
}

/**
 * Lecteur PDF maison, volontairement minimal — juste ce qu'il faut pour LIRE
 * un document archivé : défilement continu entre les pages (comme un lecteur
 * classique — bas/haut, pas de pagination forcée), zoom, recherche plein
 * texte (qui saute à la bonne page), et un sommaire (si le PDF en a un).
 * Aucun outil d'annotation/édition (commentaire, dessin, surlignage,
 * tampon...) : ceux-là viennent du lecteur PDF NATIF du navigateur quand on
 * se contente d'un `<iframe src={blobUrl}>` (voir historique — Firefox/
 * Chrome embarquent leur propre PDF.js avec sa propre barre d'outils, hors du
 * contrôle de l'app) ; ce composant rend plutôt chaque page sur son propre
 * <canvas> via pdfjs-dist (déjà une dépendance du projet), avec une barre
 * d'outils entièrement dessinée ici — rien de plus que ce qui est
 * explicitement codé ci-dessous ne peut jamais apparaître.
 *
 * Rendu PARESSEUX : toutes les pages existent dans le DOM (une <div>/page,
 * dimensionnée à l'avance pour ne pas faire sauter le défilement) mais seul
 * leur <canvas> se peuple quand la page approche du viewport (voir
 * IntersectionObserver ci-dessous) — nécessaire pour des documents de
 * plusieurs dizaines de pages sans tout dessiner d'un coup. Une fois rendue,
 * une page reste en mémoire (pas d'éviction) — compromis raisonnable pour la
 * taille des documents archivés ici.
 *
 * Remplace tel quel n'importe quel `<iframe className="pdf-viewer-iframe">`
 * existant — mêmes conventions de dimensionnement (100% du conteneur parent).
 */
function PdfViewer({ url, className }: PdfViewerProps) {
    const [pdfDoc, setPdfDoc] = useState<PDFDocumentProxy | null>(null);
    const [numPages, setNumPages] = useState(0);
    // Page "courante" affichée dans la barre d'outils — reflète la page la
    // plus visible du défilement (voir l'IntersectionObserver plus bas),
    // sert aussi de cible pour les boutons précédent/suivant, le champ page,
    // la recherche et le sommaire (tous appellent allerPage, qui défile).
    const [pageNum, setPageNum] = useState(1);
    const [pageInputValue, setPageInputValue] = useState('1');
    const [zoomManuel, setZoomManuel] = useState<number | null>(null);
    const [loading, setLoading] = useState(false);
    const [erreur, setErreur] = useState<string | null>(null);

    const [sidebarOuvert, setSidebarOuvert] = useState(false);
    const [outline, setOutline] = useState<OutlineNode[] | null>(null);
    const [outlineLoading, setOutlineLoading] = useState(false);

    const [recherche, setRecherche] = useState('');
    const [resultats, setResultats] = useState<number[]>([]); // index de page (0-based) contenant une correspondance
    const [resultatIndex, setResultatIndex] = useState(0);
    const [rechercheEnCours, setRechercheEnCours] = useState(false);
    const texteParPage = useRef<Map<number, string>>(new Map());

    // Taille de référence (page 1, scale=1) — sert à calculer l'échelle
    // effective et à réserver la place de chaque page AVANT son rendu réel,
    // pour que le défilement ne saute pas quand une page apparaît. Suppose
    // des pages de taille uniforme (vrai pour l'immense majorité des PDF
    // générés par l'app) — un document composite peut causer un léger
    // ajustement visuel au rendu réel de la page concernée.
    const [tailleBase, setTailleBase] = useState<PageViewport | null>(null);
    const [echelle, setEchelle] = useState(1);
    // Pages actuellement dans (ou proches de) la zone visible — déclenche
    // leur rendu paresseux (voir rendrePage) ; alimenté par l'observer.
    const [pagesVisibles, setPagesVisibles] = useState<Set<number>>(new Set());

    const containerRef = useRef<HTMLDivElement | null>(null);
    const pageRefs = useRef<Map<number, HTMLDivElement>>(new Map());
    const canvasRefs = useRef<Map<number, HTMLCanvasElement>>(new Map());
    const renderTasksRef = useRef<Map<number, RenderTask>>(new Map());
    const echelleRenduesRef = useRef<Map<number, number>>(new Map());
    // Couche de texte invisible superposée au canvas, pour la sélection/copie
    // (voir .pdfv-text-layer, PdfViewer.css) — mêmes refs/cycle de vie que le
    // canvas, rendue/re-rendue en même temps (même échelle, voir rendrePage).
    const textLayerRefs = useRef<Map<number, HTMLDivElement>>(new Map());
    const textLayerInstancesRef = useRef<Map<number, TextLayer>>(new Map());

    // ── Chargement du document ──────────────────────────────────────────────
    useEffect(() => {
        setPdfDoc(null);
        setNumPages(0);
        setPageNum(1);
        setPageInputValue('1');
        setZoomManuel(null);
        setOutline(null);
        setSidebarOuvert(false);
        setRecherche('');
        setResultats([]);
        setResultatIndex(0);
        texteParPage.current = new Map();
        setErreur(null);
        setTailleBase(null);
        setPagesVisibles(new Set());
        pageRefs.current = new Map();
        canvasRefs.current = new Map();
        renderTasksRef.current.forEach(t => t.cancel());
        renderTasksRef.current = new Map();
        echelleRenduesRef.current = new Map();
        textLayerInstancesRef.current.forEach(t => t.cancel());
        textLayerInstancesRef.current = new Map();
        textLayerRefs.current = new Map();

        if (!url) return;

        let annule = false;
        setLoading(true);
        const loadingTask = pdfjsLib.getDocument({ url });
        loadingTask.promise
            .then(async pdf => {
                if (annule) { loadingTask.destroy(); return; }
                setPdfDoc(pdf);
                setNumPages(pdf.numPages);
                try {
                    const page1 = await pdf.getPage(1);
                    if (!annule) setTailleBase(page1.getViewport({ scale: 1 }));
                } catch {
                    // Pas bloquant — les pages se dimensionneront à leur propre rendu.
                }
            })
            .catch(() => { if (!annule) setErreur('Impossible de charger le document.'); })
            .finally(() => { if (!annule) setLoading(false); });

        return () => {
            annule = true;
        };
    }, [url]);

    useEffect(() => () => { pdfDoc?.loadingTask.destroy(); }, [pdfDoc]);

    // ── Échelle effective — recalculée à l'ouverture et sur zoom/redimensionnement,
    //    appliquée à TOUTES les pages (rendues ou non) pour un défilement cohérent. ──
    useEffect(() => {
        if (!tailleBase || !containerRef.current) return;
        const recalculer = () => {
            if (!containerRef.current) return;
            const largeurDisponible = containerRef.current.clientWidth - 32; // padding du conteneur
            setEchelle(zoomManuel ?? Math.max(0.25, largeurDisponible / tailleBase.width));
        };
        recalculer();
        if (zoomManuel != null) return; // zoom explicite : ne bouge pas tout seul
        const observer = new ResizeObserver(recalculer);
        observer.observe(containerRef.current);
        return () => observer.disconnect();
    }, [tailleBase, zoomManuel]);

    /** Rend (ou re-rend) la couche de texte invisible superposée au canvas de
     *  la page n, à partir du viewport DÉJÀ calculé pour le canvas — même
     *  échelle, calage pixel pour pixel garanti (voir .pdfv-text-layer,
     *  PdfViewer.css). Indépendant du rendu canvas (texte structuré, pas un
     *  bitmap) : jamais bloqué/annulé par un échec du canvas. */
    const rendreCoucheTexte = useCallback(async (n: number, page: PDFPageProxy, viewport: PageViewport) => {
        const container = textLayerRefs.current.get(n);
        if (!container) return;

        textLayerInstancesRef.current.get(n)?.cancel();
        container.replaceChildren();

        let textContent;
        try {
            textContent = await page.getTextContent();
        } catch {
            return;
        }

        const textLayer = new pdfjsLib.TextLayer({ textContentSource: textContent, container, viewport });
        textLayerInstancesRef.current.set(n, textLayer);
        try {
            await textLayer.render();
        } catch {
            // Annulé par un rendu suivant (zoom/défilement rapide) — normal, pas une erreur.
        }
    }, []);

    // ── Rendu paresseux d'une page sur son canvas, à l'échelle courante ──────
    const rendrePage = useCallback(async (n: number) => {
        if (!pdfDoc) return;
        const canvas = canvasRefs.current.get(n);
        if (!canvas) return;
        if (echelleRenduesRef.current.get(n) === echelle) return; // déjà à jour

        renderTasksRef.current.get(n)?.cancel();

        let page: PDFPageProxy;
        try {
            page = await pdfDoc.getPage(n);
        } catch {
            return;
        }
        const viewport = page.getViewport({ scale: echelle });
        const context = canvas.getContext('2d');
        if (!context) return;

        // Netteté sur écrans HiDPI — le canvas est dessiné en résolution
        // physique (devicePixelRatio) mais affiché à la taille CSS voulue.
        const ratio = window.devicePixelRatio || 1;
        canvas.width = Math.floor(viewport.width * ratio);
        canvas.height = Math.floor(viewport.height * ratio);
        canvas.style.width = `${viewport.width}px`;
        canvas.style.height = `${viewport.height}px`;

        const renderTask = page.render({
            canvas,
            canvasContext: context,
            viewport,
            transform: ratio !== 1 ? [ratio, 0, 0, ratio, 0, 0] : undefined,
        });
        renderTasksRef.current.set(n, renderTask);
        try {
            await renderTask.promise;
            echelleRenduesRef.current.set(n, echelle);
        } catch {
            // Annulé par un rendu suivant (zoom/défilement rapide) — normal, pas une erreur.
        }

        // Couche de texte (sélection/copie) — indépendante du rendu canvas
        // (pas besoin de l'attendre), même viewport pour un calage parfait.
        rendreCoucheTexte(n, page, viewport);
    }, [pdfDoc, echelle, rendreCoucheTexte]);

    // Rend (ou re-rend si l'échelle a changé) toutes les pages actuellement
    // visibles — déclenché par l'observer ci-dessous ou un changement de zoom.
    useEffect(() => {
        pagesVisibles.forEach(n => { rendrePage(n); });
    }, [pagesVisibles, rendrePage]);

    // ── Observateur d'intersection — pilote à la fois le rendu paresseux et la
    //    page "courante" affichée dans la barre d'outils (page la plus visible).
    //    rootMargin : précharge les pages juste avant qu'elles n'entrent dans
    //    le viewport, pour un défilement fluide.
    //    Dépend aussi de `loading` : `numPages` est déjà fixé (donc stable,
    //    sans redéclencher cet effet) PENDANT que loading est encore true —
    //    à ce moment-là, le JSX affiche encore "Chargement..." et les <div
    //    data-page> n'existent pas encore dans le DOM, donc pageRefs.current
    //    est vide et l'observer ne surveillerait jamais rien. Sans `loading`
    //    en dépendance, l'effet ne se redéclenche jamais une fois les pages
    //    réellement montées — c'était le bug : aucune page ne se rendait
    //    jamais, canvas minuscule resté à sa taille intrinsèque par défaut. ──
    useEffect(() => {
        if (!containerRef.current || numPages === 0 || loading) return;
        const observer = new IntersectionObserver((entries) => {
            setPagesVisibles(prev => {
                const next = new Set(prev);
                entries.forEach(e => {
                    const n = Number((e.target as HTMLElement).dataset.page);
                    if (e.isIntersecting) next.add(n); else next.delete(n);
                });
                return next;
            });
            const visibles = entries.filter(e => e.isIntersecting);
            if (visibles.length > 0) {
                const plusVisible = visibles.reduce((a, b) => (b.intersectionRatio > a.intersectionRatio ? b : a));
                setPageNum(Number((plusVisible.target as HTMLElement).dataset.page));
            }
        }, { root: containerRef.current, rootMargin: '400px 0px', threshold: [0, 0.25, 0.5, 0.75, 1] });

        pageRefs.current.forEach(el => observer.observe(el));
        return () => observer.disconnect();
    }, [numPages, loading]);

    useEffect(() => { setPageInputValue(String(pageNum)); }, [pageNum]);

    /** Défile jusqu'à la page demandée — remplace l'ancien "changer la page
     *  affichée" (pagination) : toutes les pages sont déjà dans le DOM, il
     *  suffit de scroller, l'observer se charge du rendu paresseux et de la
     *  mise à jour de pageNum une fois arrivé. */
    const allerPage = (n: number) => {
        const cible = Math.min(Math.max(1, n), numPages || 1);
        pageRefs.current.get(cible)?.scrollIntoView({ behavior: 'auto', block: 'start' });
        setPageNum(cible);
    };

    // ── Sommaire (si le PDF en a un) ─────────────────────────────────────────
    const resoudreDestination = useCallback(async (dest: unknown): Promise<number | null> => {
        if (!pdfDoc) return null;
        try {
            const explicite = typeof dest === 'string' ? await pdfDoc.getDestination(dest) : dest;
            const ref = Array.isArray(explicite) ? explicite[0] : null;
            if (ref == null) return null;
            return await pdfDoc.getPageIndex(ref);
        } catch {
            return null;
        }
    }, [pdfDoc]);

    const ouvrirSidebar = async () => {
        setSidebarOuvert(o => !o);
        if (outline != null || !pdfDoc) return;
        setOutlineLoading(true);
        try {
            const brut = await pdfDoc.getOutline();
            if (!brut) { setOutline([]); return; }
            const convertir = async (items: any[]): Promise<OutlineNode[]> =>
                Promise.all(items.map(async it => ({
                    title: it.title,
                    pageIndex: await resoudreDestination(it.dest),
                    items: it.items?.length ? await convertir(it.items) : [],
                })));
            setOutline(await convertir(brut));
        } catch {
            setOutline([]);
        } finally {
            setOutlineLoading(false);
        }
    };

    const renderOutline = (nodes: OutlineNode[], profondeur = 0): React.ReactNode => (
        <ul className="pdfv-outline-list" style={{ paddingLeft: profondeur ? '0.9rem' : 0 }}>
            {nodes.map((n, i) => (
                <li key={i}>
                    <button
                        type="button"
                        className="pdfv-outline-item"
                        disabled={n.pageIndex == null}
                        onClick={() => { if (n.pageIndex != null) allerPage(n.pageIndex + 1); }}
                    >
                        {n.title}
                    </button>
                    {n.items.length > 0 && renderOutline(n.items, profondeur + 1)}
                </li>
            ))}
        </ul>
    );

    // ── Recherche plein texte — trouve les PAGES contenant le terme, saute
    // d'une correspondance à l'autre (pas de surlignage précis dans la page,
    // volontairement simple : ce lecteur sert à retrouver un endroit, pas à
    // remplacer une visionneuse complète). ────────────────────────────────
    const texteDePage = async (n: number): Promise<string> => {
        const cache = texteParPage.current.get(n);
        if (cache != null) return cache;
        if (!pdfDoc) return '';
        const page = await pdfDoc.getPage(n);
        const contenu = await page.getTextContent();
        const texte = contenu.items.map((it: any) => ('str' in it ? it.str : '')).join(' ').toLowerCase();
        texteParPage.current.set(n, texte);
        return texte;
    };

    const lancerRecherche = async (e?: React.FormEvent) => {
        e?.preventDefault();
        const q = recherche.trim().toLowerCase();
        if (!q || !pdfDoc) { setResultats([]); return; }

        setRechercheEnCours(true);
        try {
            const pages: number[] = [];
            for (let n = 1; n <= numPages; n++) {
                const texte = await texteDePage(n);
                if (texte.includes(q)) pages.push(n);
            }
            setResultats(pages);
            setResultatIndex(0);
            if (pages.length > 0) allerPage(pages[0]);
        } finally {
            setRechercheEnCours(false);
        }
    };

    const allerResultat = (delta: number) => {
        if (resultats.length === 0) return;
        const suivant = (resultatIndex + delta + resultats.length) % resultats.length;
        setResultatIndex(suivant);
        allerPage(resultats[suivant]);
    };

    const zoomActuel = zoomManuel ?? null;

    return (
        <div className={`pdfv-wrapper ${className ?? ''}`}>
            <div className="pdfv-toolbar">
                <button
                    type="button"
                    className={`pdfv-btn ${sidebarOuvert ? 'active' : ''}`}
                    onClick={ouvrirSidebar}
                    title="Sommaire"
                    aria-label="Afficher le sommaire"
                    disabled={!pdfDoc}
                >
                    <i className="fa-solid fa-bars" />
                </button>

                <div className="pdfv-page-nav">
                    <button type="button" className="pdfv-btn" onClick={() => allerPage(pageNum - 1)} disabled={pageNum <= 1} aria-label="Page précédente">
                        <i className="fa-solid fa-chevron-left" />
                    </button>
                    <input
                        type="text"
                        inputMode="numeric"
                        className="pdfv-page-input"
                        value={pageInputValue}
                        onChange={e => setPageInputValue(e.target.value)}
                        onBlur={() => allerPage(parseInt(pageInputValue, 10) || pageNum)}
                        onKeyDown={e => { if (e.key === 'Enter') allerPage(parseInt(pageInputValue, 10) || pageNum); }}
                        aria-label="Numéro de page"
                    />
                    <span className="pdfv-page-total">sur {numPages || '…'}</span>
                    <button type="button" className="pdfv-btn" onClick={() => allerPage(pageNum + 1)} disabled={pageNum >= numPages} aria-label="Page suivante">
                        <i className="fa-solid fa-chevron-right" />
                    </button>
                </div>

                <form className="pdfv-search" onSubmit={lancerRecherche}>
                    <i className="fa-solid fa-magnifying-glass" />
                    <input
                        type="text"
                        placeholder="Rechercher dans le document…"
                        value={recherche}
                        onChange={e => setRecherche(e.target.value)}
                        aria-label="Rechercher dans le document"
                    />
                    {rechercheEnCours ? (
                        <i className="fa-solid fa-spinner fa-spin" />
                    ) : resultats.length > 0 ? (
                        <span className="pdfv-search-count">
                            {resultatIndex + 1}/{resultats.length}
                        </span>
                    ) : recherche.trim() && !rechercheEnCours ? (
                        <span className="pdfv-search-count">0</span>
                    ) : null}
                    <button type="button" className="pdfv-btn" onClick={() => allerResultat(-1)} disabled={resultats.length === 0} aria-label="Occurrence précédente">
                        <i className="fa-solid fa-chevron-up" />
                    </button>
                    <button type="button" className="pdfv-btn" onClick={() => allerResultat(1)} disabled={resultats.length === 0} aria-label="Occurrence suivante">
                        <i className="fa-solid fa-chevron-down" />
                    </button>
                </form>

                <div className="pdfv-zoom">
                    <button
                        type="button"
                        className="pdfv-btn"
                        onClick={() => setZoomManuel(z => Math.max(0.25, (z ?? echelle) - 0.15))}
                        aria-label="Zoom arrière"
                    >
                        <i className="fa-solid fa-magnifying-glass-minus" />
                    </button>
                    <span className="pdfv-zoom-value">{zoomActuel ? `${Math.round(zoomActuel * 100)}%` : 'Ajusté'}</span>
                    <button
                        type="button"
                        className="pdfv-btn"
                        onClick={() => setZoomManuel(z => Math.min(4, (z ?? echelle) + 0.15))}
                        aria-label="Zoom avant"
                    >
                        <i className="fa-solid fa-magnifying-glass-plus" />
                    </button>
                </div>
            </div>

            <div className="pdfv-body">
                {sidebarOuvert && (
                    <div className="pdfv-sidebar">
                        {outlineLoading ? (
                            <div className="pdfv-sidebar-empty"><i className="fa-solid fa-spinner fa-spin" /></div>
                        ) : outline && outline.length > 0 ? (
                            renderOutline(outline)
                        ) : (
                            <div className="pdfv-sidebar-empty">Aucun sommaire disponible.</div>
                        )}
                    </div>
                )}

                <div className="pdfv-canvas-container" ref={containerRef}>
                    {loading ? (
                        <div className="pdfv-status"><i className="fa-solid fa-spinner fa-spin" /><span>Chargement du document…</span></div>
                    ) : erreur ? (
                        <div className="pdfv-status"><i className="fa-solid fa-file-circle-question" /><span>{erreur}</span></div>
                    ) : !url ? (
                        <div className="pdfv-status"><i className="fa-solid fa-file-circle-question" /><span>Aperçu indisponible</span></div>
                    ) : (
                        Array.from({ length: numPages }, (_, i) => i + 1).map(n => (
                            <div
                                key={n}
                                data-page={n}
                                ref={el => { if (el) pageRefs.current.set(n, el); else pageRefs.current.delete(n); }}
                                className="pdfv-page"
                                style={tailleBase ? {
                                    minWidth: tailleBase.width * echelle,
                                    minHeight: tailleBase.height * echelle,
                                } : undefined}
                            >
                                <div className="pdfv-page-canvas">
                                    <canvas
                                        ref={el => { if (el) canvasRefs.current.set(n, el); else canvasRefs.current.delete(n); }}
                                        className="pdfv-canvas"
                                    />
                                    <div
                                        ref={el => { if (el) textLayerRefs.current.set(n, el); else textLayerRefs.current.delete(n); }}
                                        className="pdfv-text-layer"
                                        style={{ '--total-scale-factor': echelle } as React.CSSProperties}
                                    />
                                </div>
                            </div>
                        ))
                    )}
                </div>
            </div>
        </div>
    );
}

export default PdfViewer;
