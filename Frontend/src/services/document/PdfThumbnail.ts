import * as pdfjsLib from 'pdfjs-dist';
// Vite résout ce fichier binaire en une URL statique servie telle quelle —
// c'est le worker qui fait le décodage/rendu du PDF hors du thread principal.
import pdfjsWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorker;

/**
 * Rend la PREMIÈRE page d'un PDF en image (PNG en data URL) — utilisé pour
 * les aperçus en grille de "Documents accessibles".
 *
 * Pourquoi rasteriser plutôt que d'embarquer le PDF directement (<embed>/
 * <iframe>) : le lecteur PDF natif du navigateur impose sa propre barre
 * d'outils (pagination, zoom, recherche...) par-dessus le document, et
 * aucun paramètre d'URL (#toolbar=0...) ne permet de la masquer de façon
 * fiable d'un navigateur à l'autre (Firefox l'ignore superbement,
 * contrairement à Chrome). Une image, en revanche, est un aperçu qu'on
 * contrôle entièrement : on peut la recadrer en CSS pour ne montrer QUE
 * l'en-tête du document, sans aucun chrome de lecteur.
 */
export async function renderPdfFirstPageThumbnail(
    pdfUrl: string,
    targetWidth = 360,
): Promise<string> {
    const loadingTask = pdfjsLib.getDocument({ url: pdfUrl });
    try {
        const pdf = await loadingTask.promise;
        const page = await pdf.getPage(1);
        const largeurNaturelle = page.getViewport({ scale: 1 }).width;
        // x1.5 par rapport à la largeur d'affichage visée — reste net sur
        // les écrans à forte densité de pixels (Retina...).
        const scale = (targetWidth / largeurNaturelle) * 1.5;
        const viewport = page.getViewport({ scale });

        const canvas = document.createElement('canvas');
        canvas.width = Math.round(viewport.width);
        canvas.height = Math.round(viewport.height);

        const renderTask = page.render({ canvas, viewport });
        await renderTask.promise;

        return canvas.toDataURL('image/png');
    } finally {
        // Libère le worker/la mémoire associée — PDFDocumentLoadingTask (pas
        // le PDFDocumentProxy résolu) porte la méthode destroy().
        await loadingTask.destroy();
    }
}
