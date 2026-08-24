import { useState, useEffect } from 'react';
import {
    bulkSameTypeOcrPreview,
    bulkSameTypeOcrPreviewFromFtp,
    bulkSameTypeFinalize,
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
} from '../services/document/DocumentService';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import MetaDataField from './MetadaField';
import '../Style/Editor/Editor.css';

interface UploadBulkSameTypeProps {
    onsuccess?: (report: BulkUploadReportDto) => void;
}

/** Étape globale du wizard */
type WizardStep = 'select' | 'ocr' | 'validate' | 'done';

/** Source des fichiers à l'étape 1 */
type UploadSource = 'local' | 'ftp';

/**
 * État de validation pour un fichier : valeurs saisies + sessionId
 */
interface FileValidationState {
    sessionId: string;
    nomFichier: string;
    metaValues: Record<string, string>;
    prefilled: Record<string, boolean>;
    hasError: boolean;
    errorMessage?: string;
}

// ─────────────────────────────────────────────────────────────────────────────

function UploadBulkSameType({ onsuccess }: UploadBulkSameTypeProps) {
    // ── Données stables ──────────────────────────────────────────────────────
    const [typeDocuments, setTypeDocuments]   = useState<TypeDocumentDto[]>([]);
    const [typeDocumentId, setTypeDocumentId] = useState<number | ''>('');
    const [selectedType, setSelectedType]     = useState<TypeDocumentDto | null>(null);

    // ── Accès / groupe / emplacement — appliqués à TOUT le lot ──────────────
    const [access, setAccess]                   = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [groupeNom, setGroupeNom]              = useState('');
    const [users, setUsers]                      = useState<UserDto[]>([]);
    const [selectedMembres, setSelectedMembres]  = useState<string[]>([]);
    const [emplacements, setEmplacements]        = useState<PhysicalLocationDto[]>([]);
    const [physicalLocationId, setPhysicalLocationId] = useState('');

    // ── Fichiers sélectionnés ────────────────────────────────────────────────
    const [files, setFiles]         = useState<File[]>([]);
    const [isDragging, setIsDragging] = useState(false);

    // ── Source des fichiers : locale (PC/clé USB) ou lien FTP distant ────────
    const [source, setSource] = useState<UploadSource>('local');
    const [ftpHost, setFtpHost]         = useState('');
    const [ftpPort, setFtpPort]         = useState('');
    const [ftpPath, setFtpPath]         = useState('');
    const [ftpUsername, setFtpUsername] = useState('');
    const [ftpPassword, setFtpPassword] = useState('');
    const [ftpSecure, setFtpSecure]     = useState(true);

    // ── Wizard ───────────────────────────────────────────────────────────────
    const [step, setStep]                   = useState<WizardStep>('select');
    const [isOcrLoading, setIsOcrLoading]   = useState(false);

    // ── Phase 2 : validations ─────────────────────────────────────────────
    const [fileStates, setFileStates]     = useState<FileValidationState[]>([]);
    const [currentIdx, setCurrentIdx]     = useState(0);
    const [isFinalizing, setIsFinalizing] = useState(false);

    // ── Résultat final ───────────────────────────────────────────────────────
    const [report, setReport] = useState<BulkUploadReportDto | null>(null);

    // ── Messages ─────────────────────────────────────────────────────────────
    const [error, setError]     = useState('');
    const [ocrError, setOcrError] = useState('');

    // ── Chargement des types ─────────────────────────────────────────────────
    useEffect(() => {
        getAllTypeDocuments()
            .then(setTypeDocuments)
            .catch(() => setError('Impossible de charger les types de documents'));
        getMyUO()
            .then(uo => {
                getAllUsers(uo.id).then(setUsers).catch(() => {});
                getEmplacementsDisponibles(uo.id).then(setEmplacements).catch(() => {});
            })
            .catch(() => {});
    }, []);

    const toggleMembre = (userId: string) => {
        setSelectedMembres(prev =>
            prev.includes(userId) ? prev.filter(id => id !== userId) : [...prev, userId]);
    };

    // ── Gestion fichiers ─────────────────────────────────────────────────────
    const addFiles = (newFiles: FileList | null) => {
        if (!newFiles) return;
        const arr = Array.from(newFiles);
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

    // ── PHASE 1 : lancer l'OCR sur tous les fichiers ─────────────────────────
    const handleStartOcr = async () => {
        setError('');
        setOcrError('');
        if (files.length === 0)  { setError('Ajoutez au moins un fichier'); return; }
        if (!typeDocumentId)      { setError('Choisissez un type de document'); return; }

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { setError('Session expirée'); return; }

        setIsOcrLoading(true);
        setStep('ocr');

        try {
            const preview: BulkOcrPreviewResponseDto = await bulkSameTypeOcrPreview(
                files,
                typeDocumentId as number,
                userInfo.id,
            );

            // Construire l'état de validation pour chaque fichier
            const states: FileValidationState[] = preview.previews.map(
                (item: OcrPreviewItemDto, idx: number) => {
                    const metaValues: Record<string, string> = {};
                    const prefilled:  Record<string, boolean> = {};

                    // Initialiser tous les champs à vide
                    selectedType?.metaData.forEach(m => { metaValues[m.nom] = ''; });

                    if (item.sessionId && item.metaDataSuggestions) {
                        // Pré-remplir avec les suggestions OCR
                        Object.entries(item.metaDataSuggestions).forEach(([k, v]) => {
                            metaValues[k] = v;
                            prefilled[k]  = true;
                        });
                    }

                    return {
                        sessionId:    item.sessionId ?? '',
                        nomFichier:   item.nomFichier ?? files[idx]?.name ?? `fichier_${idx + 1}`,
                        metaValues,
                        prefilled,
                        hasError:     !item.sessionId,
                        errorMessage: item.sessionId ? undefined : item.message,
                    };
                },
            );

            setFileStates(states);
            // Aller directement au premier fichier valide
            const firstValid = states.findIndex(s => !s.hasError);
            setCurrentIdx(firstValid >= 0 ? firstValid : 0);
            setStep('validate');
        } catch (err: any) {
            setOcrError(err.message ?? "Erreur lors de l'OCR en masse");
            setStep('select');
        } finally {
            setIsOcrLoading(false);
        }
    };

    // ── PHASE 1 (source distante) : télécharger puis lancer l'OCR via FTP ────
    const handleStartOcrFtp = async () => {
        setError('');
        setOcrError('');
        if (!ftpHost.trim())     { setError('Indiquez le lien / hôte du serveur FTP'); return; }
        if (!typeDocumentId)      { setError('Choisissez un type de document'); return; }

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { setError('Session expirée'); return; }

        setIsOcrLoading(true);
        setStep('ocr');

        try {
            const preview: BulkOcrPreviewResponseDto = await bulkSameTypeOcrPreviewFromFtp({
                host:           ftpHost.trim(),
                port:           ftpPort.trim() ? Number(ftpPort) : undefined,
                remotePath:     ftpPath.trim() || undefined,
                username:       ftpUsername.trim() || undefined,
                password:       ftpPassword || undefined,
                secure:         ftpSecure,
                typeDocumentId: typeDocumentId as number,
                uploadedById:   userInfo.id,
            });

            const states: FileValidationState[] = preview.previews.map(
                (item: OcrPreviewItemDto, idx: number) => {
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
                        nomFichier:   item.nomFichier ?? `fichier_${idx + 1}`,
                        metaValues,
                        prefilled,
                        hasError:     !item.sessionId,
                        errorMessage: item.sessionId ? undefined : item.message,
                    };
                },
            );

            setFileStates(states);
            const firstValid = states.findIndex(s => !s.hasError);
            setCurrentIdx(firstValid >= 0 ? firstValid : 0);
            setStep('validate');
        } catch (err: any) {
            setOcrError(err.message ?? "Erreur lors de l'import FTP");
            setStep('select');
        } finally {
            setIsOcrLoading(false);
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
        setError('');
        if (!selectedType) return;
        if (access === 'PRIVE' && !groupeNom.trim()) {
            setError('Le nom du groupe est obligatoire pour un lot privé');
            return;
        }

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { setError('Session expirée'); return; }

        setIsFinalizing(true);

        // Construire les requêtes pour les fichiers sans erreur OCR — accès,
        // groupe et emplacement physique sont partagés par tout le lot (un
        // même dossier papier va typiquement dans le même carton/rayon).
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
                        groupeNom,
                        groupeMembresIds: selectedMembres,
                    }),
                    ...(physicalLocationId && { physicalLocationId }),
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
            setError(err.message ?? 'Erreur lors de la finalisation');
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
        setStep('select');
        setError('');
        setOcrError('');
        setFtpHost('');
        setFtpPort('');
        setFtpPath('');
        setFtpUsername('');
        setFtpPassword('');
        setFtpSecure(true);
        setAccess('PUBLIC');
        setGroupeNom('');
        setSelectedMembres([]);
        setPhysicalLocationId('');
    };

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU
    // ─────────────────────────────────────────────────────────────────────────

    const validCount   = fileStates.filter(fs => !fs.hasError).length;
    const skippedCount = fileStates.filter(fs =>  fs.hasError).length;

    return (
        <div className="upload-wrapper">

            {/* ── Alertes globales ── */}
            {error    && <div className="up-alert up-alert-error">{error}</div>}
            {ocrError && <div className="up-alert up-alert-error">{ocrError}</div>}

            {/* ── Indicateur d'étapes ── */}
            <div className="upload-steps">
                <div className={`upload-step ${step === 'ocr' ? 'active' : 'done'}`}>
                    <span className="step-number">
                        {step !== 'ocr' ? <i className="fa-solid fa-check" /> : '1'}
                    </span>
                    <span className="step-label">Sélection</span>
                </div>
                <div className={`upload-step ${step === 'ocr' ? 'active' : (step === 'validate' || step === 'done' ? 'done' : '')}`}>
                    <span className="step-number">
                        {(step === 'validate' || step === 'done')
                            ? <i className="fa-solid fa-check" />
                            : '2'}
                    </span>
                    <span className="step-label">Analyse OCR</span>
                </div>
                <div className={`upload-step ${step === 'validate' ? 'active' : (step === 'done' ? 'done' : '')}`}>
                    <span className="step-number">
                        {step === 'done' ? <i className="fa-solid fa-check" /> : '3'}
                    </span>
                    <span className="step-label">Validation</span>
                </div>
                <div className={`upload-step ${step === 'done' ? 'done' : ''}`}>
                    <span className="step-number">
                        {step === 'done' ? <i className="fa-solid fa-check" /> : '4'}
                    </span>
                    <span className="step-label">Archivage</span>
                </div>
            </div>

            {/* ══════════════════════════════════════════════════════════════
                ÉTAPE 1 : sélection des fichiers + type
            ══════════════════════════════════════════════════════════════ */}
            {step === 'select' && (
                <>
                    {/* Choix de la source */}
                    <div className="bulk-source-tabs" role="tablist" aria-label="Source des documents">
                        <button
                            type="button"
                            role="tab"
                            aria-selected={source === 'local'}
                            className={`bulk-source-tab ${source === 'local' ? 'active' : ''}`}
                            onClick={() => { setSource('local'); setOcrError(''); }}
                        >
                            <i className="fa-solid fa-desktop" /> Fichiers locaux / dossier
                        </button>
                        <button
                            type="button"
                            role="tab"
                            aria-selected={source === 'ftp'}
                            className={`bulk-source-tab ${source === 'ftp' ? 'active' : ''}`}
                            onClick={() => { setSource('ftp'); setOcrError(''); }}
                        >
                            <i className="fa-solid fa-server" /> Lien distant (FTP)
                        </button>
                    </div>

                    {source === 'local' ? (
                        <>
                            {/* Zone de dépôt */}
                            <div
                                className={`drop-zone ${isDragging ? 'dragging' : ''}`}
                                onDragOver={e => { e.preventDefault(); setIsDragging(true); }}
                                onDragLeave={() => setIsDragging(false)}
                                onDrop={e => { e.preventDefault(); setIsDragging(false); addFiles(e.dataTransfer.files); }}
                                onClick={() => document.getElementById('bulk-files-input')?.click()}
                            >
                                <input
                                    id="bulk-files-input"
                                    type="file"
                                    multiple
                                    className="sr-only"
                                    aria-label="Sélectionner les fichiers à téléverser"
                                    onChange={e => addFiles(e.target.files)}
                                />
                                <div className="drop-zone-placeholder">
                                    <i className="fa-solid fa-files drop-icon-lg" />
                                    <p>Glissez vos fichiers ici</p>
                                    <span>ou cliquez pour parcourir (sélection multiple)</span>
                                </div>
                            </div>

                            {/* Sélection d'un dossier entier — fonctionne identiquement pour un
                                dossier sur le disque interne ou sur une clé USB branchée : le
                                sélecteur du navigateur ne fait aucune différence entre les deux. */}
                            <button
                                type="button"
                                className="bulk-folder-btn"
                                onClick={() => document.getElementById('bulk-folder-input')?.click()}
                            >
                                <i className="fa-solid fa-folder-open" /> Choisir un dossier entier (PC ou clé USB)
                            </button>
                            <input
                                id="bulk-folder-input"
                                type="file"
                                multiple
                                // @ts-expect-error — attributs non standard mais supportés par les navigateurs
                                webkitdirectory=""
                                directory=""
                                className="sr-only"
                                aria-label="Sélectionner un dossier à téléverser"
                                onChange={e => addFiles(e.target.files)}
                            />

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
                        /* Formulaire de connexion FTP */
                        <div className="ftp-form">
                            <div className="up-row">
                                <div className="form-field" style={{ flex: 2 }}>
                                    <input
                                        id="ftp-host"
                                        type="text"
                                        className="form-field-input"
                                        placeholder="Lien / hôte du serveur FTP *" aria-label="Lien / hôte du serveur FTP *"
                                        value={ftpHost}
                                        onChange={e => setFtpHost(e.target.value)}
                                    />
                                </div>
                                <div className="form-field">
                                    <input
                                        id="ftp-port"
                                        type="number"
                                        className="form-field-input"
                                        placeholder="Port (défaut 21)" aria-label="Port (défaut 21)"
                                        value={ftpPort}
                                        onChange={e => setFtpPort(e.target.value)}
                                    />
                                </div>
                            </div>

                            <div className="form-field">
                                <input
                                    id="ftp-path"
                                    type="text"
                                    className="form-field-input"
                                    placeholder="Dossier distant (ex : /archives/2025)" aria-label="Dossier distant (ex : /archives/2025)"
                                    value={ftpPath}
                                    onChange={e => setFtpPath(e.target.value)}
                                />
                            </div>

                            <div className="up-row">
                                <div className="form-field">
                                    <input
                                        id="ftp-username"
                                        type="text"
                                        className="form-field-input"
                                        placeholder="Identifiant (vide = anonyme)" aria-label="Identifiant (vide = anonyme)"
                                        value={ftpUsername}
                                        onChange={e => setFtpUsername(e.target.value)}
                                        autoComplete="off"
                                    />
                                </div>
                                <div className="form-field">
                                    <input
                                        id="ftp-password"
                                        type="password"
                                        className="form-field-input"
                                        placeholder="Mot de passe" aria-label="Mot de passe"
                                        value={ftpPassword}
                                        onChange={e => setFtpPassword(e.target.value)}
                                        autoComplete="off"
                                    />
                                </div>
                            </div>

                            <label className="ftp-secure-toggle">
                                <input
                                    type="checkbox"
                                    checked={ftpSecure}
                                    onChange={e => setFtpSecure(e.target.checked)}
                                />
                                Connexion sécurisée (FTPS)
                            </label>
                            <p className="ftp-hint">
                                <i className="fa-solid fa-circle-info" /> Les identifiants ne sont jamais
                                enregistrés — ils ne servent que le temps de cet import. Décochez FTPS
                                uniquement si le serveur distant ne le supporte pas.
                            </p>
                        </div>
                    )}

                    {/* Sélection du type */}
                    <div className="upload-options">
                        <div className="form-field">
                            <select
                                className="form-field-input up-select"
                                value={typeDocumentId}
                                onChange={e => handleTypeChange(Number(e.target.value))}
                                aria-label="Type de document"
                            >
                                <option value="">-- Type de document commun --</option>
                                {typeDocuments.map(td => (
                                    <option key={td.id} value={td.id}>{td.nom}</option>
                                ))}
                            </select>
                        </div>
                    </div>

                    {/* Accès — appliqué à tout le lot */}
                    <div className="up-row" role="radiogroup" aria-label="Accès du lot">
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
                            <div className="form-field">
                                <input
                                    id="bulk-groupe-nom"
                                    type="text"
                                    className="form-field-input"
                                    placeholder="Nom du groupe d'accès *" aria-label="Nom du groupe d'accès *"
                                    value={groupeNom}
                                    onChange={e => setGroupeNom(e.target.value)}
                                />
                            </div>
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
                            <label htmlFor="bulk-emplacement" className="form-field-label">
                                Emplacement physique des originaux (optionnel, appliqué à tout le lot)
                            </label>
                            <select
                                id="bulk-emplacement"
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

                    {source === 'local' ? (
                        <button
                            type="button"
                            className="form-submit-btn up-submit"
                            onClick={handleStartOcr}
                            disabled={files.length === 0 || !typeDocumentId}
                        >
                            <i className="fa-solid fa-magnifying-glass" />
                            Analyser {files.length > 0 ? `${files.length} fichier${files.length > 1 ? 's' : ''}` : ''}
                        </button>
                    ) : (
                        <button
                            type="button"
                            className="form-submit-btn up-submit"
                            onClick={handleStartOcrFtp}
                            disabled={!ftpHost.trim() || !typeDocumentId}
                        >
                            <i className="fa-solid fa-cloud-arrow-down" />
                            Importer depuis le serveur FTP
                        </button>
                    )}
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
                            {source === 'ftp'
                                ? 'Téléchargement depuis le serveur FTP puis analyse OCR en cours…'
                                : `Analyse OCR en cours sur ${files.length} fichier${files.length > 1 ? 's' : ''}…`}
                        </div>
                        <div className="progress-step loading">
                            Extraction du texte · Génération des suggestions de métadonnées
                        </div>
                    </div>
                </div>
            )}

            {/* ══════════════════════════════════════════════════════════════
                ÉTAPE 3 : validation métadonnées fichier par fichier
            ══════════════════════════════════════════════════════════════ */}
            {step === 'validate' && fileStates.length > 0 && selectedType && (
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

                        {/* Flèches précédent / suivant */}
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

                    {/* Panneau du fichier courant */}
                    {fileStates[currentIdx].hasError ? (
                        <div className="up-alert up-alert-error">
                            <i className="fa-solid fa-triangle-exclamation" style={{ marginRight: '0.5rem' }} />
                            {fileStates[currentIdx].errorMessage ?? 'Erreur OCR — ce fichier sera ignoré.'}
                        </div>
                    ) : (
                        <div className="bulk-meta-panel">
                            {/* En-tête fichier courant */}
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

                            {/* Champs métadonnées typés */}
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
                    )}

                    {/* Boutons bas */}
                    <div className="bulk-validate-actions">
                        <button
                            type="button"
                            className="bulk-back-btn"
                            onClick={handleReset}
                        >
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
// Composant rapport (partagé avec BulkMultiType)
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

export default UploadBulkSameType;