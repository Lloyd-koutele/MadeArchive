import { useState, useEffect, useRef } from 'react';
import {
    bulkSameTypeOcrPreview,
    bulkSameTypeOcrPreviewFromWeb,
    bulkSameTypeFinalize,
    previewImportWeb,
    getOcrPreviewPdfUrl,
    getAllTypeDocuments,
    getCandidatsGroupe,
    streamPdfAAsBlob,
} from '../services/document/DocumentService';
import type {
    BulkUploadReportDto,
    BulkOcrPreviewResponseDto,
    OcrPreviewItemDto,
    TypeDocumentDto,
    FinalizeUploadRequestDto,
    MetaDataValueDto,
    UserDto,
    WebImportPreviewResponseDto,
    DocumentSimilaireDto,
} from '../services/document/DocumentService';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import { getEmplacementsDisponibles, getArbreEmplacements } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto, PhysicalLocationNodeDto } from '../services/organisation/PhysicalLocationService';
// Même composant que PhysicalLocationsPanel (arbre + création/modification en
// un seul modal) — pas de duplication : une mise à jour du constructeur
// d'arborescence profite aux deux écrans à la fois.
import EmplacementTreeModal from '../organisation/EmplacementTreeModal';
import DossierTreePicker from '../organisation/DossierTreePicker';
import { getPlanClassement, aplatirPlanClassement } from '../services/organisation/PlanClassementService';
import type { PlanClassementOption } from '../services/organisation/PlanClassementService';
import MetaDataField from './MetadaField';
import PdfViewer from '../components/PdfViewer';
import Modal from '../Page/Modal';
import { useNotify } from '../notifications/NotificationProvider';
import { useConfirm } from '../notifications/ConfirmProvider';
import '../Style/Editor/Editor.css';

/** En dessous de ce seuil (points), le texte d'un tableau mis à l'échelle sur
 *  une page (voir singlePageSheets côté serveur) est jugé trop petit pour
 *  rester confortablement lisible — voir handleFinalize. */
const POLICE_MIN_SEUIL_PT = 8;

interface PrecedentDocumentInfo {
    documentId:     string;
    typeDocumentId: number;
    titre:          string;
}

interface ImportDocumentsProps {
    onsuccess?: (report: BulkUploadReportDto) => void;
    /** Type pré-rempli à l'ouverture (ex : bouton "+" depuis un dossier déjà ouvert) — reste modifiable, pas verrouillé. */
    preselectedTypeId?: number | null;
    /** Dossier pré-rempli à l'ouverture (bouton "Archiver ici" depuis un dossier déjà
     *  ouvert, voir DossiersPanel.tsx) — reste modifiable, pas verrouillé, voir DossierTreePicker. */
    preselectedDossierId?: number | null;
    /**
     * Si fourni, cet import devient le dépôt d'une NOUVELLE VERSION de ce
     * document précis — même composant que pour un import normal (voir
     * MesDocumentsEditor.tsx), mais adapté : type verrouillé sur celui du
     * document précédent, un seul fichier à la fois (une version en
     * remplace une seule autre, jamais un lot), pas de "Lien" ni de dossier
     * entier — et documentPrecedentId est joint à la finalisation.
     */
    precedentDocument?: PrecedentDocumentInfo | null;
}

/** Étape globale du wizard */
type WizardStep = 'source' | 'lien-confirm' | 'ocr' | 'validate' | 'done';

/** Fichier(s)/dossier locaux, ou un lien */
type ImportMode = 'local' | 'lien';

/** État de validation pour un fichier : valeurs saisies + sessionId */
interface FileValidationState {
    // Clé React STABLE et UNIQUE pour ce fichier dans le lot — voir
    // construireFileStates. À NE PAS confondre avec sessionId : sessionId
    // vaut '' pour TOUS les fichiers en erreur (pas de session OCR créée),
    // donc react key={fs.sessionId} collisionnait entre tous les fichiers en
    // erreur du lot. React ne peut alors plus distinguer ces éléments d'un
    // rendu à l'autre et réutilise le mauvais nœud DOM après un retrait
    // (retirerDuLotValide) : le fichier retiré reste visible et d'autres,
    // pourtant valides, se retrouvent affichés comme "en erreur" à sa place
    // — constaté en conditions réelles sur un lot de ~1000 fichiers.
    id: string;
    sessionId: string;
    nomFichier: string;
    metaValues: Record<string, string>;
    prefilled: Record<string, boolean>;
    hasError: boolean;
    errorMessage?: string;
    /** Avertissement, jamais un blocage — voir DocumentSimilaireDto (backend). */
    documentSimilaire?: DocumentSimilaireDto;
    /** Absent si non pertinent. Sinon, plus petite taille de police (pt)
     *  mesurée dans le PDF converti — en dessous de 8pt, l'éditeur doit
     *  confirmer explicitement avant l'archivage (voir handleFinalize). */
    policeMinPt?: number;
    /** Avertissements du contrôle du type réel du fichier (ex. .doc au lieu de .docx, sans extension). */
    avertissements?: string[];
}

// ─────────────────────────────────────────────────────────────────────────────

function ImportDocuments({ onsuccess, preselectedTypeId, preselectedDossierId, precedentDocument }: ImportDocumentsProps) {
    const notify = useNotify();
    const confirm = useConfirm();
    // ── Données stables ──────────────────────────────────────────────────────
    const [typeDocuments, setTypeDocuments]   = useState<TypeDocumentDto[]>([]);
    const [typeDocumentId, setTypeDocumentId] = useState<number | ''>('');
    const [selectedType, setSelectedType]     = useState<TypeDocumentDto | null>(null);

    // ── Accès / groupe / emplacement — appliqués à TOUT le lot ──────────────
    const [access, setAccess]                   = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [users, setUsers]                      = useState<UserDto[]>([]);
    const [selectedMembres, setSelectedMembres]  = useState<string[]>([]);
    const [filtreMembre, setFiltreMembre]        = useState('');
    const [emplacements, setEmplacements]        = useState<PhysicalLocationDto[]>([]);
    const [physicalLocationId, setPhysicalLocationId] = useState('');
    /** Activité (plan de classement) de tout le lot — '' = suit l'activité par défaut de son type. */
    const [activiteId, setActiviteId] = useState('');
    const [activites, setActivites] = useState<PlanClassementOption[]>([]);
    /** Dossier cible (optionnel) — voir DossierTreePicker. Si ce dossier a déjà
     *  ce type de document parmi ses types attendus, les fichiers y sont
     *  simplement versés sans rien recréer ; sinon le type y est automatiquement
     *  déclaré (voir DocumentUploadeService côté serveur). */
    const [dossierId, setDossierId] = useState<number | null>(preselectedDossierId ?? null);
    // uoId : pas connue ailleurs dans ce composant jusqu'ici (getMyUO()
    // n'était utilisé que transitoirement pour charger users/emplacements) —
    // nécessaire pour EmplacementTreeModal (création/modification à la volée
    // pendant l'upload, voir le champ "Emplacement physique" plus bas).
    const [uoId, setUoId] = useState<number | null>(null);
    // Même état "mode create/update" que PhysicalLocationsPanel — voir
    // TreeModalState là-bas, même idée ici.
    const [emplacementModal, setEmplacementModal] = useState<
        { open: false } | { open: true; mode: 'create' } | { open: true; mode: 'update'; node: PhysicalLocationNodeDto }
    >({ open: false });

    // ── Fichiers locaux sélectionnés ─────────────────────────────────────────
    const [files, setFiles]           = useState<File[]>([]);
    const [isDragging, setIsDragging] = useState(false);

    // ── Source : fichier(s)/dossier local, ou lien ───────────────────────────
    const [importMode, setImportMode]   = useState<ImportMode>('local');
    const [lienUrl, setLienUrl]         = useState('');
    const [lienLoading, setLienLoading] = useState(false);

    // ── Confirmation lien web (avant téléchargement) ─────────────────────────
    const [webPreview, setWebPreview]       = useState<WebImportPreviewResponseDto | null>(null);
    const [selectedWebUrls, setSelectedWebUrls] = useState<Set<string>>(new Set());

    // ── Wizard ───────────────────────────────────────────────────────────────
    const [step, setStep] = useState<WizardStep>('source');

    // ── Phase 2 : validations ─────────────────────────────────────────────
    const [fileStates, setFileStates]     = useState<FileValidationState[]>([]);
    const [currentIdx, setCurrentIdx]     = useState(0);
    const [isFinalizing, setIsFinalizing] = useState(false);

    // ── Résultat final ───────────────────────────────────────────────────────
    const [report, setReport] = useState<BulkUploadReportDto | null>(null);

    // ── Aperçu du document actif à l'écran de validation ─────────────────────
    const [previewUrl, setPreviewUrl]         = useState<string | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);

    // ── Chargement des types ─────────────────────────────────────────────────
    useEffect(() => {
        getAllTypeDocuments()
            .then(types => {
                setTypeDocuments(types);
                // Une nouvelle version reprend le type du document précédent —
                // verrouillé, voir le <select disabled> plus bas. Sinon,
                // pré-rempli mais jamais verrouillé (bouton "+" depuis un
                // dossier déjà ouvert) : l'utilisateur peut toujours changer
                // d'avis.
                const forcedId = precedentDocument?.typeDocumentId ?? preselectedTypeId;
                if (forcedId != null) {
                    const match = types.find(t => t.id === forcedId);
                    if (match?.id != null) {
                        setTypeDocumentId(match.id);
                        setSelectedType(match);
                    }
                }
            })
            .catch(() => notify.error('Impossible de charger les types de documents'));
        getMyUO()
            .then(uo => {
                setUoId(uo.id);
                getCandidatsGroupe(uo.id).then(setUsers).catch(() => {});
            })
            .catch(() => {});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Liste des emplacements — re-filtrée par compatibilité dès que le type
    // ou le dossier cible change (voir LocationModeContrainte côté serveur) :
    // un point de stockage en mode "type unique"/"dossier" n'apparaît que
    // s'il correspond à ce qui est choisi ici. Si l'emplacement actuellement
    // sélectionné n'est plus dans la liste retournée (devenu incompatible
    // après un changement de type/dossier), la sélection est effacée plutôt
    // que silencieusement envoyée pour un document qu'elle n'accepterait pas.
    useEffect(() => {
        if (uoId == null) return;
        getEmplacementsDisponibles(uoId, typeDocumentId || null, dossierId)
            .then(liste => {
                setEmplacements(liste);
                setPhysicalLocationId(prev => (prev && !liste.some(l => l.id === prev)) ? '' : prev);
            })
            .catch(() => setEmplacements([]));
    }, [uoId, typeDocumentId, dossierId]);

    // ── Déclenchement automatique de l'analyse (local) ───────────────────────
    // Dès que fichier(s)/dossier ET type de document sont fournis, l'analyse
    // démarre seule — plus de bouton "Analyser" à cliquer. La signature (type +
    // noms/tailles des fichiers) évite de relancer une analyse déjà en cours ou
    // déjà faite pour cette même sélection.
    const derniereAnalyseLancee = useRef('');
    useEffect(() => {
        if (importMode !== 'local' || step !== 'source') return;
        if (files.length === 0 || !typeDocumentId) return;

        const signature = `${typeDocumentId}|${files.map(f => `${f.name}:${f.size}`).join(',')}`;
        if (derniereAnalyseLancee.current === signature) return;
        derniereAnalyseLancee.current = signature;
        handleStartOcr();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [importMode, step, files, typeDocumentId]);

    // ── Aperçu du document actif — récupère le PDF déjà généré par l'OCR ─────
    const activeSessionId = fileStates[currentIdx]?.sessionId;
    const activeHasError  = fileStates[currentIdx]?.hasError;
    useEffect(() => {
        if (step !== 'validate' || !activeSessionId || activeHasError) {
            setPreviewUrl(null);
            return;
        }
        let annule = false;
        let objectUrl: string | null = null;
        setPreviewLoading(true);
        getOcrPreviewPdfUrl(activeSessionId)
            .then(url => {
                if (annule) { URL.revokeObjectURL(url); return; }
                objectUrl = url;
                setPreviewUrl(url);
            })
            .catch(() => { if (!annule) setPreviewUrl(null); })
            .finally(() => { if (!annule) setPreviewLoading(false); });

        return () => {
            annule = true;
            if (objectUrl) URL.revokeObjectURL(objectUrl);
        };
    }, [step, activeSessionId, activeHasError]);

    const toggleMembre = (userId: string) => {
        setSelectedMembres(prev =>
            prev.includes(userId) ? prev.filter(id => id !== userId) : [...prev, userId]);
    };

    // ── Gestion fichiers locaux ───────────────────────────────────────────────
    const addFiles = (newFiles: FileList | null) => {
        if (!newFiles) return;
        const arr = Array.from(newFiles);
        // Nouvelle version : un seul fichier remplace l'ancien à chaque
        // sélection, jamais un lot accumulé — une version en remplace une
        // seule autre.
        if (precedentDocument) {
            setFiles(arr.slice(0, 1));
            return;
        }
        setFiles(prev => {
            const existing = prev.map(f => f.name);
            return [...prev, ...arr.filter(f => !existing.includes(f.name))];
        });
    };

    const removeFile = (name: string) =>
        setFiles(prev => prev.filter(f => f.name !== name));

    const handleTypeChange = (id: number) => {
        setTypeDocumentId(id);
        setSelectedType(typeDocuments.find(td => td.id === id) ?? null);
    };

    /**
     * Ouvre le document similaire détecté dans un modal dédié (notre propre
     * lecteur PDF, voir PdfViewer) — jamais un <a href> direct (JWT en
     * header, pas en cookie, voir streamPdfAAsBlob). Un MODAL plutôt que de
     * réutiliser l'aperçu de ce formulaire : mélanger "le document en cours
     * d'upload" et "un autre document déjà archivé" dans la même zone
     * d'aperçu serait trompeur ; le modal reste refermable immédiatement
     * pour revenir à l'import en cours.
     */
    const [docSimilaireUrl, setDocSimilaireUrl] = useState<string | null>(null);
    const [docSimilaireTitre, setDocSimilaireTitre] = useState<string | null>(null);
    const handleVoirDocumentSimilaire = async (doc: DocumentSimilaireDto) => {
        try {
            const url = await streamPdfAAsBlob(doc.documentId);
            setDocSimilaireUrl(url);
            setDocSimilaireTitre(doc.titre);
        } catch {
            notify.error("Impossible d'ouvrir le document similaire (peut-être supprimé depuis)");
        }
    };

    /** Cherche, dans l'arbre COMPLET de l'UO, la racine (nœud de plus haut
     *  niveau) qui contient l'id donné — pour ouvrir la modification sur
     *  TOUTE la branche réelle (pas seulement le point de stockage isolé
     *  choisi dans le <select>), l'utilisateur peut alors restructurer
     *  librement (renommer n'importe quel nœud de la branche, en ajouter de
     *  nouveaux n'importe où) comme dans PhysicalLocationsPanel. */
    const trouverRacineContenant = (arbre: PhysicalLocationNodeDto[], id: string): PhysicalLocationNodeDto | null => {
        const contient = (n: PhysicalLocationNodeDto): boolean => n.id === id || n.children.some(contient);
        return arbre.find(contient) ?? null;
    };

    const ouvrirModificationEmplacement = async () => {
        if (uoId == null || !physicalLocationId) return;
        try {
            const arbre = await getArbreEmplacements(uoId);
            const racine = trouverRacineContenant(arbre, physicalLocationId);
            if (!racine) {
                notify.error("Cet emplacement n'existe plus dans l'arborescence");
                return;
            }
            setEmplacementModal({ open: true, mode: 'update', node: racine });
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors du chargement de l'arborescence");
        }
    };

    /**
     * Appelé par EmplacementTreeModal après création OU modification —
     * rafraîchit la liste (la nature Stockage/Chemin n'est connue qu'après
     * coup : un "Nœud chemin" fraîchement créé ne réapparaît pas dans cette
     * liste, déjà filtrée aux points de stockage côté serveur, voir
     * PhysicalLocationService.getEmplacementsDisponibles) et sélectionne
     * l'emplacement concerné s'il est bien assignable à un document.
     */
    const handleEmplacementSaved = async (node: PhysicalLocationNodeDto) => {
        if (uoId == null) return;
        const actualises = await getEmplacementsDisponibles(uoId, typeDocumentId || null, dossierId);
        setEmplacements(actualises);
        if (node.storagePoint) {
            setPhysicalLocationId(node.id);
        }
        setEmplacementModal({ open: false });
    };

    /** Construit l'état de validation par fichier à partir d'un BulkOcrPreviewResponseDto — commun à toutes les sources. */
    const construireFileStates = (preview: BulkOcrPreviewResponseDto, fallbackNames?: string[]): FileValidationState[] =>
        preview.previews.map((item: OcrPreviewItemDto, idx: number) => {
            const metaValues: Record<string, string> = {};
            const prefilled:  Record<string, boolean> = {};

            selectedType?.metaData.forEach(m => { metaValues[m.nom] = ''; });

            if (item.sessionId && item.metaDataSuggestions) {
                Object.entries(item.metaDataSuggestions).forEach(([k, v]) => {
                    metaValues[k] = v;
                    prefilled[k]  = true;
                });
            }

            return {
                // Un sessionId réel (succès) est déjà unique — pour un
                // échec (sessionId absent pour TOUS), on retombe sur l'index
                // de construction, unique une seule fois ici puis figé pour
                // le reste de la vie de cet objet (voir le champ `id`).
                id:           item.sessionId ?? `err-${idx}`,
                sessionId:    item.sessionId ?? '',
                nomFichier:   item.nomFichier ?? fallbackNames?.[idx] ?? `fichier_${idx + 1}`,
                metaValues,
                prefilled,
                hasError:     !item.sessionId,
                errorMessage: item.sessionId ? undefined : item.message,
                documentSimilaire: item.documentSimilaire,
                policeMinPt: item.policeMinPt,
                avertissements: item.avertissements,
            };
        });

    const appliquerPreview = (preview: BulkOcrPreviewResponseDto, fallbackNames?: string[]) => {
        const states = construireFileStates(preview, fallbackNames);
        setFileStates(states);
        const firstValid = states.findIndex(s => !s.hasError);
        setCurrentIdx(firstValid >= 0 ? firstValid : 0);
        setStep('validate');
    };

    // ── PHASE 1 (local) : lancer l'OCR sur tous les fichiers ─────────────────
    const handleStartOcr = async () => {
        if (files.length === 0)  { notify.error('Ajoutez au moins un fichier'); return; }
        if (!typeDocumentId)      { notify.error('Choisissez un type de document'); return; }

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { notify.error('Session expirée'); return; }

        setStep('ocr');

        try {
            const preview = await bulkSameTypeOcrPreview(files, typeDocumentId as number, userInfo.id);
            appliquerPreview(preview, files.map(f => f.name));
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'analyse");
            setStep('source');
        }
    };

    // ── Lien web — Étape A : découverte (aperçu, sans téléchargement) ────────
    const handleAnalyserLien = async () => {
        if (!lienUrl.trim()) { notify.error('Collez un lien'); return; }
        if (!typeDocumentId)  { notify.error('Choisissez un type de document'); return; }

        setLienLoading(true);
        try {
            const preview = await previewImportWeb(lienUrl.trim());
            setWebPreview(preview);
            setSelectedWebUrls(new Set(preview.fichiers.map(f => f.url)));
            setStep('lien-confirm');
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'analyse du lien");
        } finally {
            setLienLoading(false);
        }
    };

    const toggleWebFile = (url: string) => {
        setSelectedWebUrls(prev => {
            const next = new Set(prev);
            if (next.has(url)) next.delete(url); else next.add(url);
            return next;
        });
    };

    // ── Lien web — Étape B : téléchargement des fichiers confirmés + OCR ─────
    const handleConfirmerLien = async () => {
        if (selectedWebUrls.size === 0) { notify.error('Sélectionnez au moins un fichier'); return; }

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { notify.error('Session expirée'); return; }

        setStep('ocr');

        try {
            const preview = await bulkSameTypeOcrPreviewFromWeb(
                Array.from(selectedWebUrls), typeDocumentId as number, userInfo.id);
            appliquerPreview(preview);
        } catch (err: any) {
            notify.error(err.message ?? "Erreur lors de l'import du lien");
            setStep('lien-confirm');
        }
    };

    // ── Mise à jour d'un champ de métadonnée ─────────────────────────────────
    const handleMetaChange = (fileIdx: number, nom: string, value: string) => {
        setFileStates(prev => prev.map((fs, i) =>
            i === fileIdx
                ? { ...fs, metaValues: { ...fs.metaValues, [nom]: value } }
                : fs,
        ));
    };

    // ── Navigation entre fichiers ─────────────────────────────────────────────
    const goTo = (idx: number) => {
        if (idx >= 0 && idx < fileStates.length) setCurrentIdx(idx);
    };

    // ── Retirer un fichier du lot déjà analysé, avant archivage (pas de
    // suppression serveur — sa session OCR reste en cache, simplement jamais
    // finalisée, voir handleFinalize qui ne lit que fileStates). Distinct de
    // removeFile ci-dessus, qui retire un fichier pas encore analysé à
    // l'étape de sélection. Toujours en garder au moins un : à zéro, tout le
    // bloc de validation (fileStates.length > 0) — "Recommencer" compris —
    // disparaîtrait avec, laissant l'utilisateur bloqué sans issue visible.
    const retirerDuLotValide = (idx: number) => {
        if (fileStates.length <= 1) return;
        const remaining = fileStates.length - 1;
        setFileStates(prev => prev.filter((_, i) => i !== idx));
        setCurrentIdx(prev => {
            if (idx < prev) return prev - 1;
            return Math.min(prev, remaining - 1);
        });
    };

    // ── PHASE 2 : finaliser tous les fichiers ─────────────────────────────────
    const handleFinalize = async () => {
        if (!selectedType) return;

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { notify.error('Session expirée'); return; }

        // Police réduite (voir POLICE_MIN_SEUIL_PT) sur au moins un fichier du
        // lot à archiver : demande une confirmation EXPLICITE ici plutôt qu'un
        // simple bandeau ignorable — l'éditeur doit assumer consciemment le
        // choix d'archiver un texte difficilement lisible, jamais un blocage.
        const fichiersPoliceReduite = fileStates
            .filter(fs => !fs.hasError && fs.policeMinPt != null && fs.policeMinPt < POLICE_MIN_SEUIL_PT);
        if (fichiersPoliceReduite.length > 0) {
            const noms = fichiersPoliceReduite.map(fs => fs.nomFichier).join(', ');
            const accepte = await confirm({
                title: 'Texte réduit après conversion',
                message: (fichiersPoliceReduite.length > 1
                    ? `${fichiersPoliceReduite.length} fichiers (${noms}) ont`
                    : `Le fichier « ${noms} » a`)
                    + ` un texte réduit à environ ${Math.min(...fichiersPoliceReduite.map(fs => fs.policeMinPt!)).toFixed(1)}pt `
                    + 'après conversion — potentiellement difficile à lire. Voulez-vous quand même archiver ?',
                confirmLabel: 'Archiver quand même',
            });
            if (!accepte) return;
        }

        setIsFinalizing(true);

        // Accès, groupe et emplacement physique sont partagés par tout le lot
        // (un même dossier papier va typiquement dans le même carton/rayon).
        const requests: FinalizeUploadRequestDto[] = fileStates
            .filter(fs => !fs.hasError)
            .map(fs => ({
                sessionId: fs.sessionId,
                documentUploadDto: {
                    titre:          fs.nomFichier,
                    access,
                    typeDocumentId: typeDocumentId as number,
                    uploadedById:   userInfo.id,
                    integrityLevel: 'STANDARD' as const,
                    ...(access === 'PRIVE' && {
                        groupeMembresIds: selectedMembres,
                    }),
                    ...(physicalLocationId && { physicalLocationId }),
                    ...(activiteId && { planClassementNoeudId: Number(activiteId) }),
                    ...(dossierId != null && { dossierId }),
                    ...(precedentDocument && { documentPrecedentId: precedentDocument.documentId }),
                },
                metaDataValidated: selectedType.metaData.map(m => ({
                    nom:       m.nom,
                    valeur:    fs.metaValues[m.nom] ?? '',
                    typeValeur: m.metaDataType,
                } satisfies MetaDataValueDto)),
            }));

        try {
            const rep = await bulkSameTypeFinalize({ requests });
            setReport(rep);
            setStep('done');
            onsuccess?.(rep);
        } catch (err: any) {
            notify.error(err.message ?? 'Erreur lors de l\'archivage');
        } finally {
            setIsFinalizing(false);
        }
    };

    // ── Reset complet ─────────────────────────────────────────────────────────
    const handleReset = () => {
        setFiles([]);
        setFileStates([]);
        setReport(null);
        setCurrentIdx(0);
        setStep('source');
        setImportMode('local');
        setLienUrl('');
        setWebPreview(null);
        setSelectedWebUrls(new Set());
        setAccess('PUBLIC');
        setSelectedMembres([]);
        setPhysicalLocationId('');
        // Comme typeDocumentId (non réinitialisé ci-dessus) : "Nouvel import"
        // repart du dossier pré-rempli à l'ouverture, pas d'un champ vidé —
        // utile pour enchaîner plusieurs lots dans le même dossier.
        setDossierId(preselectedDossierId ?? null);
        // Sans ça, resélectionner exactement les mêmes fichiers après un reset
        // ne redéclencherait pas l'analyse automatique (signature identique).
        derniereAnalyseLancee.current = '';
    };

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU
    // ─────────────────────────────────────────────────────────────────────────

    const validCount   = fileStates.filter(fs => !fs.hasError).length;
    const skippedCount = fileStates.filter(fs =>  fs.hasError).length;
    const isSingle      = fileStates.length === 1;

    const peutLancer =
        importMode === 'local'
            ? files.length > 0 && !!typeDocumentId
            : !!lienUrl.trim() && !!typeDocumentId;

    /** Accès/dossier/emplacement — réglages appliqués à TOUT le lot (voir leurs
     *  commentaires respectifs ci-dessous), extraits dans une variable pour être
     *  rendus aussi bien à l'étape "source" (avant analyse) qu'à l'étape
     *  "validate" (après analyse) — retour utilisateur 10/2026 : une fois
     *  l'OCR lancé, ces réglages disparaissaient complètement de l'écran sans
     *  aucun moyen de les revoir/changer autrement qu'en "Recommencer" (donc en
     *  reperdant toute l'analyse déjà faite). Même état (access/dossierId/
     *  physicalLocationId...), donc un changement ici est immédiatement reflété
     *  si on revient à l'étape "source", et inversement. */
    useEffect(() => {
        if (uoId == null) return;
        getPlanClassement(uoId)
            .then(arbre => setActivites(aplatirPlanClassement(arbre)))
            .catch(() => setActivites([])); // non bloquant : sans plan, tout reste "non classé"
    }, [uoId]);

    // Changer de type remet l'activité sur celle par défaut du nouveau type
    useEffect(() => { setActiviteId(''); }, [typeDocumentId]);

    const blocAccesDossierEmplacement = (
        <>
            {/* Accès — appliqué à tout le lot */}
            <div className="up-row" role="radiogroup" aria-label="Accès">
                <label>
                    <input type="radio" checked={access === 'PUBLIC'}
                        onChange={() => setAccess('PUBLIC')} /> Public
                </label>
                <label>
                    <input type="radio" checked={access === 'PRIVE'}
                        onChange={() => setAccess('PRIVE')} /> Privé
                </label>
            </div>

            {access === 'PRIVE' && (
                <div className="groupe-section">
                    {users.length > 0 && (
                        <div className="membres-section">
                            <p className="membres-label">Membres du groupe (optionnel) :</p>
                            <input
                                type="text"
                                className="membres-filtre-input"
                                placeholder="Rechercher (nom, email, téléphone)"
                                aria-label="Rechercher un utilisateur"
                                value={filtreMembre}
                                onChange={e => setFiltreMembre(e.target.value)}
                            />
                            <div className="membres-list">
                                {users
                                    .filter(u => {
                                        const q = filtreMembre.trim().toLowerCase();
                                        if (!q) return true;
                                        return `${u.prenom} ${u.nom}`.toLowerCase().includes(q)
                                            || u.email.toLowerCase().includes(q)
                                            || (u.telephone ?? '').toLowerCase().includes(q);
                                    })
                                    .map(u => (
                                        <label key={u.id} className="membre-item">
                                            <input
                                                type="checkbox"
                                                checked={selectedMembres.includes(u.id)}
                                                onChange={() => toggleMembre(u.id)}
                                            />
                                            <span>{u.prenom} {u.nom}</span>
                                            <span className="membre-email">{u.email}</span>
                                        </label>
                                    ))}
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* Dossier cible — un seul pour tout le lot. Si ce dossier a déjà
                ce type de document parmi ses types attendus, les fichiers
                y sont simplement versés sans rien recréer ; sinon il y est
                automatiquement déclaré (voir DocumentUploadeService côté
                serveur). Pas d'objet pour une nouvelle version tant que
                rien n'est choisi ici : le dossier du prédécesseur est
                hérité par défaut. */}
            {uoId != null && (
                <div className="form-field">
                    <label className="form-field-label">Dossier cible (optionnel)</label>
                    <DossierTreePicker uoId={uoId} value={dossierId} onChange={setDossierId} />
                </div>
            )}

            {/* Activité (plan de classement) — par défaut celle du type, à changer seulement pour un cas
                particulier (ex. facture ponctuelle dans un type habituellement récurrent). Une seule pour
                tout le lot ; un document isolé se corrige ensuite avec « Reclasser ». */}
            {activites.length > 0 && (
                <div className="form-field">
                    <label htmlFor="import-activite" className="form-field-label">Activité (optionnel)</label>
                    <select id="import-activite" className="form-field-input up-select" value={activiteId}
                        onChange={e => setActiviteId(e.target.value)}>
                        <option value="">
                            {selectedType?.activite ? `Par défaut du type : ${selectedType.activite}` : 'Par défaut du type (non classé)'}
                        </option>
                        {activites.map(a => <option key={a.id} value={a.id}>{a.label}</option>)}
                    </select>
                </div>
            )}

            {/* Emplacement physique — un seul pour tout le lot. Toujours affiché
                (même sans aucun emplacement existant) : le modal dédié
                ci-dessous (EmplacementTreeModal, PARTAGÉ avec
                PhysicalLocationsPanel) couvre justement ce cas, et
                permet aussi de modifier l'emplacement sélectionné. */}
            <div className="form-field">
                <label htmlFor="import-emplacement" className="form-field-label">
                    Emplacement physique des originaux (optionnel)
                </label>
                <div className="up-emplacement-choix">
                    <select
                        id="import-emplacement"
                        className="form-field-input up-select"
                        value={physicalLocationId}
                        onChange={e => setPhysicalLocationId(e.target.value)}
                    >
                        <option value="">— Aucun —</option>
                        {emplacements.map(loc => {
                            const plein = loc.capaciteMax != null && loc.nombreDocuments >= loc.capaciteMax;
                            return (
                                <option key={loc.id} value={loc.id} disabled={plein}>
                                    {loc.cheminComplet}
                                    {loc.capaciteMax != null ? ` (${loc.nombreDocuments}/${loc.capaciteMax}${plein ? ' — plein' : ''})` : ''}
                                </option>
                            );
                        })}
                    </select>
                    <button
                        type="button"
                        className="up-btn-secondary"
                        onClick={() => setEmplacementModal({ open: true, mode: 'create' })}
                        disabled={uoId == null}
                        title="Créer un nouvel emplacement"
                    >
                        <i className="fa-solid fa-plus" /> Créer
                    </button>
                    {physicalLocationId && (
                        <button
                            type="button"
                            className="up-btn-secondary"
                            onClick={ouvrirModificationEmplacement}
                            title="Modifier toute l'arborescence de cet emplacement"
                        >
                            <i className="fa-solid fa-pen" /> Modifier
                        </button>
                    )}
                </div>
            </div>
        </>
    );

    return (
        <div className="upload-wrapper">

            {/* ══════════════════════════════════════════════════════════════
                Choix de la source + type + options du lot
            ══════════════════════════════════════════════════════════════ */}
            {step === 'source' && (
                <>
                    {/* Type de document — obligatoire avant tout, verrouillé pour
                        une nouvelle version (celle du document précédent) */}
                    <div className="upload-options">
                        <div className="form-field">
                            {precedentDocument && (
                                <p className="lien-hint" style={{ marginBottom: '0.5rem' }}>
                                    <i className="fa-solid fa-code-branch" /> Nouvelle version de « {precedentDocument.titre} » — le type de document est verrouillé.
                                </p>
                            )}
                            <select
                                className="form-field-input up-select"
                                value={typeDocumentId}
                                onChange={e => handleTypeChange(Number(e.target.value))}
                                aria-label="Type de document"
                                disabled={!!precedentDocument}
                            >
                                <option value="">-- Type de document --</option>
                                {typeDocuments.map(td => (
                                    <option key={td.id} value={td.id}>{td.nom}</option>
                                ))}
                            </select>
                        </div>
                    </div>

                    {/* Choix de la source — "Lien" n'a pas de sens pour une
                        nouvelle version (on remplace un fichier précis qu'on a
                        déjà sous la main, jamais une adresse web à explorer) */}
                    {!precedentDocument && (
                        <div className="bulk-source-tabs" role="tablist" aria-label="Source des documents">
                            <button
                                type="button"
                                role="tab"
                                aria-selected={importMode === 'local'}
                                className={`bulk-source-tab ${importMode === 'local' ? 'active' : ''}`}
                                onClick={() => setImportMode('local')}
                            >
                                <i className="fa-solid fa-folder-open" /> Fichier(s) ou dossier
                            </button>
                            <button
                                type="button"
                                role="tab"
                                aria-selected={importMode === 'lien'}
                                className={`bulk-source-tab ${importMode === 'lien' ? 'active' : ''}`}
                                onClick={() => setImportMode('lien')}
                            >
                                <i className="fa-solid fa-link" /> Lien
                            </button>
                        </div>
                    )}

                    {importMode === 'local' ? (
                        <>
                            {/* Zone de dépôt */}
                            <div
                                className={`drop-zone ${isDragging ? 'dragging' : ''}`}
                                onDragOver={e => { e.preventDefault(); setIsDragging(true); }}
                                onDragLeave={() => setIsDragging(false)}
                                onDrop={e => { e.preventDefault(); setIsDragging(false); addFiles(e.dataTransfer.files); }}
                                onClick={() => document.getElementById('import-files-input')?.click()}
                            >
                                <input
                                    id="import-files-input"
                                    type="file"
                                    multiple={!precedentDocument}
                                    className="sr-only"
                                    aria-label={precedentDocument ? 'Sélectionner le fichier de remplacement' : 'Sélectionner un ou plusieurs fichiers'}
                                    onChange={e => addFiles(e.target.files)}
                                />
                                <div className="drop-zone-placeholder">
                                    <i className="fa-solid fa-cloud-arrow-up drop-icon-lg" />
                                    <p>{precedentDocument ? 'Glissez le nouveau fichier ici' : 'Glissez un ou plusieurs fichiers ici'}</p>
                                    <span>ou cliquez pour parcourir</span>
                                </div>
                            </div>

                            {/* Sélection d'un dossier entier — sans objet pour une nouvelle
                                version (un seul fichier remplace un seul document). Sinon,
                                fonctionne identiquement pour un dossier sur le disque interne ou
                                sur une clé USB branchée. Un dossier ne contenant qu'un seul
                                fichier est traité comme un import simple (voir isSingle à l'étape
                                de validation) — inutile pour l'utilisateur de distinguer les deux
                                cas en amont. */}
                            {!precedentDocument && (
                                <>
                                    <button
                                        type="button"
                                        className="bulk-folder-btn"
                                        onClick={() => document.getElementById('import-folder-input')?.click()}
                                    >
                                        <i className="fa-solid fa-folder-open" /> Ou choisir un dossier entier
                                    </button>
                                    <input
                                        id="import-folder-input"
                                        type="file"
                                        multiple
                                        // @ts-expect-error — attributs non standard mais supportés par les navigateurs
                                        webkitdirectory=""
                                        directory=""
                                        className="sr-only"
                                        aria-label="Sélectionner un dossier à téléverser"
                                        onChange={e => addFiles(e.target.files)}
                                    />
                                </>
                            )}

                            {/* Liste des fichiers */}
                            {files.length > 0 && (
                                <div className="bulk-file-list">
                                    <p className="bulk-file-count">
                                        <strong>{files.length}</strong> fichier{files.length > 1 ? 's' : ''} sélectionné{files.length > 1 ? 's' : ''}
                                    </p>
                                    <div className="bulk-file-items">
                                        {files.map(f => (
                                            <div key={f.name} className="bulk-file-item">
                                                <i className="fa-solid fa-file bulk-file-icon" />
                                                <span className="bulk-file-name">{f.name}</span>
                                                <span className="bulk-file-size">
                                                    {(f.size / 1024).toFixed(1)} Ko
                                                </span>
                                                <button
                                                    type="button"
                                                    className="td-remove-btn"
                                                    onClick={() => removeFile(f.name)}
                                                    aria-label="Retirer"
                                                >✕</button>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </>
                    ) : (
                        /* Lien web */
                        <div className="lien-form">
                            <div className="form-field">
                                <input
                                    id="import-lien"
                                    type="text"
                                    className="form-field-input"
                                    placeholder="Collez un lien ici (ex : https://exemple.fr/cours.pdf ou une page listant des documents)"
                                    aria-label="Lien à importer"
                                    value={lienUrl}
                                    onChange={e => setLienUrl(e.target.value)}
                                />
                            </div>
                            <p className="lien-hint">
                                <i className="fa-solid fa-circle-info" /> Un lien vers un fichier
                                (PDF, Word, Excel, image...) l'importe directement. Un lien vers une
                                page web trouve automatiquement les documents qu'elle contient.
                            </p>
                        </div>
                    )}

                    {blocAccesDossierEmplacement}

                    {/* Local : pas de bouton — l'analyse démarre seule dès que fichier(s) et
                        type sont tous les deux fournis (voir l'effet plus haut). Lien : reste
                        un bouton explicite, un lien se tape au clavier, rien à déclencher tant
                        qu'il n'est pas complet. */}
                    {importMode === 'local' ? (
                        !peutLancer && (
                            <p className="lien-hint">
                                <i className="fa-solid fa-circle-info" />
                                {files.length === 0
                                    ? "Ajoutez un ou plusieurs fichiers pour démarrer l'analyse automatiquement."
                                    : "Choisissez un type de document pour démarrer l'analyse automatiquement."}
                            </p>
                        )
                    ) : (
                        <button
                            type="button"
                            className="form-submit-btn up-submit"
                            onClick={handleAnalyserLien}
                            disabled={!peutLancer || lienLoading}
                        >
                            {lienLoading ? (
                                <><i className="fa-solid fa-spinner fa-spin" /> Analyse du lien…</>
                            ) : (
                                <><i className="fa-solid fa-link" /> Analyser le lien</>
                            )}
                        </button>
                    )}
                </>
            )}

            {/* ══════════════════════════════════════════════════════════════
                ÉTAPE 1bis (lien web simple) : confirmation avant téléchargement
            ══════════════════════════════════════════════════════════════ */}
            {step === 'lien-confirm' && webPreview && (
                <>
                    <p className="bulk-file-count">
                        {webPreview.fichiers.length > 1
                            ? <><strong>{webPreview.fichiers.length}</strong> fichiers trouvés à cette adresse — décochez ceux à ne pas importer :</>
                            : <>1 fichier trouvé à ce lien, prêt à être importé :</>}
                    </p>

                    <div className="bulk-file-list">
                        <div className="bulk-file-items">
                            {webPreview.fichiers.map(f => (
                                <label key={f.url} className="bulk-file-item">
                                    <input
                                        type="checkbox"
                                        checked={selectedWebUrls.has(f.url)}
                                        onChange={() => toggleWebFile(f.url)}
                                    />
                                    <i className="fa-solid fa-file bulk-file-icon" />
                                    <span className="bulk-file-name">{f.nomFichier}</span>
                                </label>
                            ))}
                        </div>
                    </div>

                    <div className="bulk-validate-actions">
                        <button type="button" className="bulk-back-btn" onClick={() => setStep('source')}>
                            <i className="fa-solid fa-arrow-left" /> Modifier le lien
                        </button>
                        <button
                            type="button"
                            className="form-submit-btn up-submit bulk-finalize-btn"
                            onClick={handleConfirmerLien}
                            disabled={selectedWebUrls.size === 0}
                        >
                            <i className="fa-solid fa-download" /> Continuer ({selectedWebUrls.size})
                        </button>
                    </div>
                </>
            )}

            {/* ══════════════════════════════════════════════════════════════
                ÉTAPE 2 : OCR en cours
            ══════════════════════════════════════════════════════════════ */}
            {step === 'ocr' && (
                <div className="upload-progress">
                    <div className="progress-steps">
                        <div className="progress-step">
                            <i className="fa-solid fa-spinner fa-spin" style={{ color: 'var(--accent)' }} />
                            {importMode === 'lien'
                                ? 'Téléchargement depuis le lien puis analyse OCR en cours…'
                                : `Analyse OCR en cours sur ${files.length} fichier${files.length > 1 ? 's' : ''}…`}
                        </div>
                        <div className="progress-step loading">
                            Extraction du texte · Génération des suggestions de métadonnées
                        </div>
                    </div>
                </div>
            )}

            {/* ══════════════════════════════════════════════════════════════
                ÉTAPE 3 : validation métadonnées — présentation adaptée si 1 seul fichier
            ══════════════════════════════════════════════════════════════ */}
            {step === 'validate' && fileStates.length > 0 && selectedType && (
                <>
                    {!isSingle && (
                        <>
                            {/* Bandeau résumé OCR */}
                            <div className="bulk-ocr-summary">
                                <span className="bulk-ocr-stat ok">
                                    <i className="fa-solid fa-check-circle" /> {validCount} fichier{validCount > 1 ? 's' : ''} analysé{validCount > 1 ? 's' : ''}
                                </span>
                                {skippedCount > 0 && (
                                    <span className="bulk-ocr-stat ko">
                                        <i className="fa-solid fa-triangle-exclamation" /> {skippedCount} en erreur (seront ignorés)
                                    </span>
                                )}
                            </div>

                            {/* Navigation entre fichiers */}
                            <div className="bulk-nav">
                                <div className="bulk-nav-tabs">
                                    {fileStates.map((fs, idx) => (
                                        <button
                                            key={fs.id}
                                            type="button"
                                            className={`bulk-nav-tab ${idx === currentIdx ? 'active' : ''} ${fs.hasError ? 'error' : ''}`}
                                            onClick={() => goTo(idx)}
                                            title={fs.nomFichier}
                                        >
                                            {fs.hasError
                                                ? <i className="fa-solid fa-xmark" />
                                                : <i className="fa-solid fa-file" />
                                            }
                                            <span className="bulk-nav-tab-name">
                                                {fs.nomFichier.length > 18
                                                    ? fs.nomFichier.slice(0, 15) + '…'
                                                    : fs.nomFichier}
                                            </span>
                                            {/* Retirer du lot sans l'archiver — n'efface rien côté serveur,
                                                juste exclu de la finalisation (voir handleFinalize). Désactivé
                                                s'il ne reste que ce fichier — "Recommencer" existe pour tout
                                                annuler, mais le lot ne doit jamais tomber à zéro fichier ici. */}
                                            <span
                                                role="button"
                                                tabIndex={fileStates.length > 1 ? 0 : -1}
                                                aria-disabled={fileStates.length <= 1}
                                                className={`bulk-nav-tab-remove ${fileStates.length <= 1 ? 'disabled' : ''}`}
                                                aria-label={`Retirer ${fs.nomFichier} du lot`}
                                                title={fileStates.length <= 1 ? 'Le dernier fichier du lot ne peut pas être retiré — utilisez "Recommencer"' : undefined}
                                                onClick={(e) => { e.stopPropagation(); retirerDuLotValide(idx); }}
                                                onKeyDown={(e) => {
                                                    if (e.key === 'Enter' || e.key === ' ') {
                                                        e.preventDefault();
                                                        e.stopPropagation();
                                                        retirerDuLotValide(idx);
                                                    }
                                                }}
                                            >
                                                <i className="fa-solid fa-circle-xmark" />
                                            </span>
                                        </button>
                                    ))}
                                </div>

                                <div className="bulk-nav-arrows">
                                    <button
                                        type="button"
                                        className="bulk-nav-arrow"
                                        onClick={() => goTo(currentIdx - 1)}
                                        disabled={currentIdx === 0}
                                        aria-label="Fichier précédent"
                                    >‹</button>
                                    <span className="bulk-nav-counter">
                                        {currentIdx + 1} / {fileStates.length}
                                    </span>
                                    <button
                                        type="button"
                                        className="bulk-nav-arrow"
                                        onClick={() => goTo(currentIdx + 1)}
                                        disabled={currentIdx === fileStates.length - 1}
                                        aria-label="Fichier suivant"
                                    >›</button>
                                </div>
                            </div>
                        </>
                    )}

                    {/* Panneau du fichier courant */}
                    {fileStates[currentIdx].hasError ? (
                        <div className="up-alert up-alert-error">
                            <i className="fa-solid fa-triangle-exclamation" style={{ marginRight: '0.5rem' }} />
                            {fileStates[currentIdx].errorMessage ?? 'Erreur OCR — ce fichier sera ignoré.'}
                        </div>
                    ) : (
                        <>
                        {fileStates[currentIdx].avertissements?.map((msg, i) => (
                            <div key={i} className="up-alert up-alert-warning">
                                <span>
                                    <i className="fa-solid fa-triangle-exclamation" style={{ marginRight: '0.5rem' }} />
                                    {msg}
                                </span>
                            </div>
                        ))}
                        {fileStates[currentIdx].documentSimilaire && (
                            <div className="up-alert up-alert-warning">
                                <span>
                                    <i className="fa-solid fa-triangle-exclamation" style={{ marginRight: '0.5rem' }} />
                                    Un document similaire existe déjà dans votre UO : «{' '}
                                    {fileStates[currentIdx].documentSimilaire!.titre} »
                                </span>
                                <button
                                    type="button"
                                    onClick={() => handleVoirDocumentSimilaire(fileStates[currentIdx].documentSimilaire!)}
                                >
                                    <i className="fa-solid fa-eye" /> Voir
                                </button>
                            </div>
                        )}
                        {/* Avertissement (pas un blocage ICI) — tableau mis à l'échelle sur
                            une page (voir singlePageSheets côté serveur) au point que le
                            texte devient minuscule. La confirmation explicite ("j'assume
                            d'archiver quand même") est demandée plus loin, au clic sur
                            "Archiver" (voir handleFinalize), pas ici : cohérent avec le
                            fait que ce panneau ne montre qu'UN fichier du lot à la fois. */}
                        {fileStates[currentIdx].policeMinPt != null && fileStates[currentIdx].policeMinPt! < POLICE_MIN_SEUIL_PT && (
                            <div className="up-alert up-alert-warning">
                                <span>
                                    <i className="fa-solid fa-triangle-exclamation" style={{ marginRight: '0.5rem' }} />
                                    Ce tableau est large — une fois converti, le texte est réduit
                                    à environ {fileStates[currentIdx].policeMinPt!.toFixed(1)}pt, ce
                                    qui peut être difficile à lire. Vérifiez l'aperçu ci-dessous
                                    avant d'archiver.
                                </span>
                            </div>
                        )}
                        <div className="import-validate-split">
                            {/* Visionneuse — le document déjà converti en PDF par le serveur,
                                pour vérifier en le lisant plutôt qu'en faisant confiance à l'OCR. */}
                            <div className="import-preview-pane">
                                {previewLoading ? (
                                    <div className="import-preview-loading">
                                        <i className="fa-solid fa-spinner fa-spin" />
                                        <span>Chargement de l'aperçu…</span>
                                    </div>
                                ) : previewUrl ? (
                                    <PdfViewer url={previewUrl} className="import-preview-iframe" />
                                ) : (
                                    <div className="import-preview-loading">
                                        <i className="fa-solid fa-file-circle-question" />
                                        <span>Aperçu indisponible</span>
                                    </div>
                                )}
                            </div>

                            <div className="bulk-meta-panel">
                                <div className="bulk-meta-header">
                                    <i className="fa-solid fa-file" style={{ color: 'var(--accent)' }} />
                                    <span className="bulk-meta-filename">
                                        {fileStates[currentIdx].nomFichier}
                                    </span>
                                    {Object.values(fileStates[currentIdx].prefilled).some(Boolean) && (
                                        <span className="bulk-meta-ocr-badge">
                                            <i className="fa-solid fa-wand-magic-sparkles" /> OCR
                                        </span>
                                    )}
                                </div>

                                <div className="meta-fields">
                                    <p className="meta-fields-title">
                                        Vérifiez et complétez les métadonnées :
                                    </p>
                                    {selectedType.metaData.map(meta => (
                                        <MetaDataField
                                            key={meta.nom}
                                            nom={meta.nom}
                                            type={meta.metaDataType}
                                            obligatoire={meta.obligatoire}
                                            value={fileStates[currentIdx].metaValues[meta.nom] ?? ''}
                                            onChange={v => handleMetaChange(currentIdx, meta.nom, v)}
                                            prefilled={!!fileStates[currentIdx].prefilled[meta.nom]}
                                        />
                                    ))}
                                </div>

                                {/* Accès/dossier/emplacement — réglages du LOT entier (voir
                                    blocAccesDossierEmplacement), pas de ce seul fichier : revenir
                                    les revoir/changer ici évite d'avoir à "Recommencer" (et donc
                                    reperdre l'analyse OCR déjà faite) juste pour ça. */}
                                <div className="meta-fields meta-fields-lot">
                                    <p className="meta-fields-title">
                                        Accès, dossier et emplacement — pour tout le lot :
                                    </p>
                                    {blocAccesDossierEmplacement}
                                </div>
                            </div>
                        </div>
                        </>
                    )}

                    <div className="bulk-validate-actions">
                        <button type="button" className="bulk-back-btn" onClick={handleReset}>
                            <i className="fa-solid fa-arrow-left" /> Recommencer
                        </button>
                        <button
                            type="button"
                            className="form-submit-btn up-submit bulk-finalize-btn"
                            onClick={handleFinalize}
                            disabled={isFinalizing || validCount === 0}
                        >
                            {isFinalizing ? (
                                <><i className="fa-solid fa-spinner fa-spin" /> Archivage en cours…</>
                            ) : isSingle ? (
                                <><i className="fa-solid fa-box-archive" /> Archiver</>
                            ) : (
                                <><i className="fa-solid fa-box-archive" /> Archiver {validCount} fichier{validCount > 1 ? 's' : ''}</>
                            )}
                        </button>
                    </div>
                </>
            )}

            {/* ══════════════════════════════════════════════════════════════
                ÉTAPE 4 : rapport final
            ══════════════════════════════════════════════════════════════ */}
            {step === 'done' && report && (
                <>
                    <BulkReport report={report} />
                    <button
                        type="button"
                        className="bulk-back-btn"
                        style={{ alignSelf: 'flex-start' }}
                        onClick={handleReset}
                    >
                        <i className="fa-solid fa-plus" /> Nouvel import
                    </button>
                </>
            )}

            {uoId != null && emplacementModal.open && (
                <EmplacementTreeModal
                    isOpen={emplacementModal.open}
                    onClose={() => setEmplacementModal({ open: false })}
                    uoId={uoId}
                    mode={emplacementModal.mode}
                    parentId={emplacementModal.mode === 'create' ? null : undefined}
                    existingNode={emplacementModal.mode === 'update' ? emplacementModal.node : undefined}
                    onSaved={handleEmplacementSaved}
                />
            )}

            {docSimilaireUrl && (
                <Modal
                    isOpen
                    onClose={() => {
                        URL.revokeObjectURL(docSimilaireUrl);
                        setDocSimilaireUrl(null);
                        setDocSimilaireTitre(null);
                    }}
                    title={docSimilaireTitre ?? 'Document similaire'}
                    size="large"
                >
                    <PdfViewer url={docSimilaireUrl} className="import-preview-iframe" />
                </Modal>
            )}
        </div>
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Composant rapport
// ─────────────────────────────────────────────────────────────────────────────

export function BulkReport({ report }: { report: BulkUploadReportDto }) {
    return (
        <div className="bulk-report">
            <div className="bulk-report-summary">
                <div className="bulk-stat total">
                    <span className="bulk-stat-value">{report.total}</span>
                    <span className="bulk-stat-label">Total</span>
                </div>
                <div className="bulk-stat success">
                    <span className="bulk-stat-value">{report.success}</span>
                    <span className="bulk-stat-label">Succès</span>
                </div>
                <div className="bulk-stat failed">
                    <span className="bulk-stat-value">{report.failed}</span>
                    <span className="bulk-stat-label">Échecs</span>
                </div>
            </div>
            {report.details.length > 0 && (
                <div className="bulk-report-details">
                    {report.details.map((item, i) => (
                        <div
                            key={i}
                            className={`bulk-report-item ${item.status === 'SUCCESS' ? 'ok' : 'ko'}`}
                        >
                            <i className={`fa-solid ${item.status === 'SUCCESS' ? 'fa-check' : 'fa-xmark'}`} />
                            <span className="bulk-item-name">{item.nomFichier}</span>
                            {item.erreur && (
                                <span className="bulk-item-error">{item.erreur}</span>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

export default ImportDocuments;
