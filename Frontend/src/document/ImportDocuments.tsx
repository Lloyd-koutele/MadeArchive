import { useState, useEffect, useRef } from 'react';
import {
    bulkSameTypeOcrPreview,
    bulkSameTypeOcrPreviewFromWeb,
    bulkSameTypeFinalize,
    previewImportWeb,
    getOcrPreviewPdfUrl,
    getAllTypeDocuments,
    getAllUsers,
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
} from '../services/document/DocumentService';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import MetaDataField from './MetadaField';
import { useNotify } from '../notifications/NotificationProvider';
import '../Style/Editor/Editor.css';

interface PrecedentDocumentInfo {
    documentId:     string;
    typeDocumentId: number;
    titre:          string;
}

interface ImportDocumentsProps {
    onsuccess?: (report: BulkUploadReportDto) => void;
    /** Type pré-rempli à l'ouverture (ex : bouton "+" depuis un dossier déjà ouvert) — reste modifiable, pas verrouillé. */
    preselectedTypeId?: number | null;
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
    sessionId: string;
    nomFichier: string;
    metaValues: Record<string, string>;
    prefilled: Record<string, boolean>;
    hasError: boolean;
    errorMessage?: string;
}

// ─────────────────────────────────────────────────────────────────────────────

function ImportDocuments({ onsuccess, preselectedTypeId, precedentDocument }: ImportDocumentsProps) {
    const notify = useNotify();
    // ── Données stables ──────────────────────────────────────────────────────
    const [typeDocuments, setTypeDocuments]   = useState<TypeDocumentDto[]>([]);
    const [typeDocumentId, setTypeDocumentId] = useState<number | ''>('');
    const [selectedType, setSelectedType]     = useState<TypeDocumentDto | null>(null);

    // ── Accès / groupe / emplacement — appliqués à TOUT le lot ──────────────
    const [access, setAccess]                   = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [users, setUsers]                      = useState<UserDto[]>([]);
    const [selectedMembres, setSelectedMembres]  = useState<string[]>([]);
    const [emplacements, setEmplacements]        = useState<PhysicalLocationDto[]>([]);
    const [physicalLocationId, setPhysicalLocationId] = useState('');

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
                getAllUsers(uo.id).then(setUsers).catch(() => {});
                getEmplacementsDisponibles(uo.id).then(setEmplacements).catch(() => {});
            })
            .catch(() => {});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

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
                sessionId:    item.sessionId ?? '',
                nomFichier:   item.nomFichier ?? fallbackNames?.[idx] ?? `fichier_${idx + 1}`,
                metaValues,
                prefilled,
                hasError:     !item.sessionId,
                errorMessage: item.sessionId ? undefined : item.message,
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

    // ── PHASE 2 : finaliser tous les fichiers ─────────────────────────────────
    const handleFinalize = async () => {
        if (!selectedType) return;

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { notify.error('Session expirée'); return; }

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
                                    <div className="membres-list">
                                        {users.map(u => (
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

                    {/* Emplacement physique — un seul pour tout le lot */}
                    {emplacements.length > 0 && (
                        <div className="form-field">
                            <label htmlFor="import-emplacement" className="form-field-label">
                                Emplacement physique des originaux (optionnel)
                            </label>
                            <select
                                id="import-emplacement"
                                className="form-field-input up-select"
                                value={physicalLocationId}
                                onChange={e => setPhysicalLocationId(e.target.value)}
                            >
                                <option value="">— Aucun —</option>
                                {emplacements.map(loc => (
                                    <option key={loc.id} value={loc.id}>{loc.cheminComplet}</option>
                                ))}
                            </select>
                        </div>
                    )}

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
                                            key={idx}
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
                                    <iframe
                                        src={previewUrl}
                                        className="import-preview-iframe"
                                        title={`Aperçu de ${fileStates[currentIdx].nomFichier}`}
                                    />
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
                            </div>
                        </div>
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
