import React, { useState, useEffect, useRef } from 'react';
import {
    uploadDocumentOcrPreview,
    finalizeUploadDocument,
    getAllTypeDocuments,
    getAllUsers,
} from '../services/document/DocumentService';
import type {
    DocumentUploadDto,
    DocumentUploadResultDto,
    FinalizeUploadRequestDto,
    MetaDataValueDto,
    OcrPreviewResponseDto,
    TypeDocumentDto,
    UserDto,
} from '../services/document/DocumentService';
import { getCurrentUserInfo } from '../auth/authService';
import { getMyUO } from '../services/organisation/UOService';
import { getEmplacementsDisponibles } from '../services/organisation/PhysicalLocationService';
import type { PhysicalLocationDto } from '../services/organisation/PhysicalLocationService';
import MetaDataField from './MetadaField';
import '../Style/Editor/Editor.css';

interface PrecedentDocumentInfo {
    documentId:     string;
    typeDocumentId: number;
    titre:          string;
}

interface UploadSimpleProps {
    onsuccess?: (result: DocumentUploadResultDto) => void;
    preselectedTypeId?: number | null; // <-- 1. Ajout de la prop dans l'interface
    /** Si fourni, cet upload devient la version suivante de ce document (type verrouillé). */
    precedentDocument?: PrecedentDocumentInfo | null;
}

/**
 * Upload simple — flow 2 phases :
 *
 * Phase 1 : dès que fichier + type sont définis
 * → uploadDocumentOcrPreview()  POST /editor/ocr-preview
 * → reçoit sessionId + suggestions OCR
 * → affiche les champs MetaData pré-remplis
 *
 * Phase 2 : bouton "Archiver"
 * → finalizeUploadDocument()    POST /editor/finalize-upload
 * → valide les types strictement côté serveur
 * → crée le Document + PKI + Meilisearch
 */
function UploadSimple({ onsuccess, preselectedTypeId, precedentDocument }: UploadSimpleProps) { // <-- 2. Récupération de la prop

    // ── Données de référence ─────────────────────────────────────────────────
    const [typeDocuments, setTypeDocuments] = useState<TypeDocumentDto[]>([]);
    const [users, setUsers]                 = useState<UserDto[]>([]);

    // ── Sélections utilisateur ────────────────────────────────────────────────
    const [file, setFile]                     = useState<File | null>(null);
    const [typeDocumentId, setTypeDocumentId] = useState<number | ''>('');
    const [selectedType, setSelectedType]     = useState<TypeDocumentDto | null>(null);
    const [access, setAccess]                 = useState<'PUBLIC' | 'PRIVE'>('PUBLIC');
    const [integrityLevel, setIntegrityLevel] = useState<'STANDARD' | 'BLOCKCHAIN'>('STANDARD');
    const [groupeNom, setGroupeNom]           = useState('');
    const [selectedMembres, setSelectedMembres] = useState<string[]>([]);
    const [emplacements, setEmplacements]     = useState<PhysicalLocationDto[]>([]);
    const [physicalLocationId, setPhysicalLocationId] = useState('');
    const [isDragging, setIsDragging]         = useState(false);

    // ── Phase 1 : résultat OCR ────────────────────────────────────────────────
    const [sessionId, setSessionId]       = useState<string | null>(null);
    const [metaValues, setMetaValues]     = useState<Record<string, string>>({});
    const [prefilledKeys, setPrefilledKeys] = useState<Set<string>>(new Set());

    // ── États UI ──────────────────────────────────────────────────────────────
    const [isProcessing, setIsProcessing] = useState(false); // Phase 1 en cours
    const [isSaving, setIsSaving]         = useState(false); // Phase 2 en cours
    const [error, setError]               = useState('');
    const [success, setSuccess]           = useState('');

    // Ref pour déclencher Phase 1 sans boucle infinie dans useEffect
    const lastOcrKey = useRef<string>('');

    // ── Chargement initial ────────────────────────────────────────────────────
    useEffect(() => {
        getAllTypeDocuments()
            .then(setTypeDocuments)
            .catch(() => setError('Impossible de charger les types de documents'));
        // getAllUsers a besoin de l'UO de l'éditeur (l'endpoint liste les utilisateurs
        // d'une UO donnée, pas "tout le monde") — on la récupère d'abord.
        getMyUO()
            .then(uo => {
                getAllUsers(uo.id).then(setUsers).catch(() => {});
                getEmplacementsDisponibles(uo.id).then(setEmplacements).catch(() => {});
            })
            .catch(() => {});
    }, []);

    // ── Gestion de la pré-sélection automatique ──────────────────────────────
    // S'exécute dès que les types sont chargés OU que l'id pré-sélectionné change.
    // Le type du document précédent (nouvelle version) est prioritaire et verrouillé.
    useEffect(() => {
        const forcedId = precedentDocument?.typeDocumentId ?? preselectedTypeId;
        if (forcedId && typeDocuments.length > 0) {
            const id = Number(forcedId);
            setTypeDocumentId(id);
            const matchedType = typeDocuments.find(td => td.id === id);
            setSelectedType(matchedType ?? null);
        }
    }, [preselectedTypeId, precedentDocument, typeDocuments]);

    // ── Déclenchement automatique Phase 1 ────────────────────────────────────
    // Se lance dès que fichier + type sont définis, et à chaque changement de l'un d'eux.
    useEffect(() => {
        if (!file || !typeDocumentId) return;

        // Clé unique pour éviter les doubles déclenchements
        const ocrKey = `${file.name}-${file.size}-${typeDocumentId}`;
        if (lastOcrKey.current === ocrKey) return;
        lastOcrKey.current = ocrKey;

        lancerOcrPreview();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [file, typeDocumentId]);

    // ── Phase 1 : OCR Preview ─────────────────────────────────────────────────
    const lancerOcrPreview = async () => {
        if (!file || !typeDocumentId) return;
        // Pour accès privé, attendre que le nom de groupe soit rempli
        if (access === 'PRIVE' && !groupeNom.trim()) return;

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { setError('Session expirée'); return; }

        setError('');
        setSuccess('');
        setSessionId(null);
        setMetaValues({});
        setPrefilledKeys(new Set());
        setIsProcessing(true);

        try {
            const preview: OcrPreviewResponseDto = await uploadDocumentOcrPreview(
                file,
                typeDocumentId as number,
            );

            console.log('[DIAG-OCR] preview.sessionId:', preview.sessionId);
            console.log('[DIAG-OCR] preview.metaDataSuggestions:', preview.metaDataSuggestions);
            console.log('[DIAG-OCR] clés suggestions:', Object.keys(preview.metaDataSuggestions ?? {}));
            
            // Initialiser les champs MetaData
            const initial: Record<string, string> = {};
            selectedType?.metaData.forEach(m => { 
                initial[m.nom] = ''; 
                console.log('[DIAG-OCR] MetaData attendu:', m.nom);
            });

            const prefilled = new Set<string>();
            if (preview.metaDataSuggestions) {
                Object.entries(preview.metaDataSuggestions).forEach(([k, v]) => {
                    console.log('[DIAG-OCR] Suggestion reçue: clé=', k, '| valeur=', v);
                    initial[k] = v;
                    prefilled.add(k);
                });
            }
            console.log('[DIAG-OCR] metaValues final:', initial);
            console.log('[DIAG-OCR] prefilledKeys:', [...prefilled]);

            setSessionId(preview.sessionId);
            setMetaValues(initial);
            setPrefilledKeys(prefilled);

        } catch (err: any) {
            setError(err.message ?? "Erreur lors de l'analyse OCR");
            setSessionId(null);
            lastOcrKey.current = ''; // Permettre une relance
        } finally {
            setIsProcessing(false);
        }
    };

    // ── Phase 2 : Finalisation ────────────────────────────────────────────────
    const handleFormSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!sessionId || !selectedType) return;

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { setError('Session expirée'); return; }

        // Validation accès privé
        if (access === 'PRIVE' && !groupeNom.trim()) {
            setError('Le nom du groupe est obligatoire pour un document privé');
            return;
        }

        setError('');
        setIsSaving(true);

        try {
            const uploadDto: DocumentUploadDto = {
                titre:          file!.name,
                access,
                typeDocumentId: typeDocumentId as number,
                uploadedById:   userInfo.id,
                integrityLevel,
                ...(access === 'PRIVE' && {
                    groupeNom,
                    groupeMembresIds: selectedMembres,
                }),
                ...(precedentDocument && {
                    documentPrecedentId: precedentDocument.documentId,
                }),
                ...(physicalLocationId && { physicalLocationId }),
            };

            const metaDataValidated: MetaDataValueDto[] = selectedType.metaData.map(m => ({
                nom:       m.nom,
                valeur:    metaValues[m.nom] ?? '',
                typeValeur: m.metaDataType,
            }));

            const request: FinalizeUploadRequestDto = {
                sessionId,
                documentUploadDto:  uploadDto,
                metaDataValidated,
            };

            const result: DocumentUploadResultDto = await finalizeUploadDocument(request);

            setSuccess('Document archivé avec succès');
            onsuccess?.(result);

            // Reset pour un nouvel upload
            resetForm();

        } catch (err: any) {
            if (err.isSessionExpired) {
                setError('Session expirée — veuillez re-déposer le fichier.');
                resetOcr();
            } else if (err.isValidationError) {
                setError('Erreur de validation : ' + err.message);
            } else {
                setError(err.message ?? "Erreur lors de l'archivage");
            }
        } finally {
            setIsSaving(false);
        }
    };

    // ── Gestion des changements ───────────────────────────────────────────────
    const handleFileChange = (f: File) => {
        resetOcr();
        setFile(f);
    };

    const handleTypeChange = (id: number) => {
        resetOcr();
        setTypeDocumentId(id);
        setSelectedType(typeDocuments.find(td => td.id === id) ?? null);
    };

    const handleDrop = (e: React.DragEvent) => {
        e.preventDefault();
        setIsDragging(false);
        const dropped = e.dataTransfer.files[0];
        if (dropped) handleFileChange(dropped);
    };

    const toggleMembre = (userId: string) =>
        setSelectedMembres(prev =>
            prev.includes(userId)
                ? prev.filter(id => id !== userId)
                : [...prev, userId],
        );

    // ── Helpers reset ─────────────────────────────────────────────────────────
    const resetOcr = () => {
        setSessionId(null);
        setMetaValues({});
        setPrefilledKeys(new Set());
        lastOcrKey.current = '';
    };

    const resetForm = () => {
        setFile(null);
        setTypeDocumentId('');
        setSelectedType(null);
        setGroupeNom('');
        setSelectedMembres([]);
        resetOcr();
    };

    // ─────────────────────────────────────────────────────────────────────────
    // RENDU
    // ─────────────────────────────────────────────────────────────────────────
    const hasSession = !!sessionId && !isProcessing;

    return (
        <div className="upload-wrapper">
            {precedentDocument && (
                <div className="up-alert up-alert-info">
                    <i className="fa-solid fa-code-branch" />
                    {' '}Nouvelle version de « {precedentDocument.titre} » — le type de document est verrouillé.
                </div>
            )}
            {error   && <div className="up-alert up-alert-error">{error}</div>}
            {success && <div className="up-alert up-alert-success">{success}</div>}

            {/* ── Zone de dépôt ── */}
            <div
                className={`drop-zone ${isDragging ? 'dragging' : ''} ${file ? 'has-file' : ''}`}
                onDragOver={e => { e.preventDefault(); setIsDragging(true); }}
                onDragLeave={() => setIsDragging(false)}
                onDrop={handleDrop}
                onClick={() => !isProcessing && document.getElementById('file-input')?.click()}
            >
                <input
                    id="file-input"
                    type="file"
                    className="sr-only"
                    aria-label="Fichier à archiver"
                    onChange={e => {
                        const f = e.target.files?.[0];
                        if (f) handleFileChange(f);
                    }}
                />

                {isProcessing ? (
                    <div className="drop-zone-placeholder">
                        <i className="fa-solid fa-spinner fa-spin drop-icon-lg" />
                        <p>Analyse OCR en cours…</p>
                        <span>Extraction du texte et des métadonnées</span>
                    </div>
                ) : file ? (
                    <div className="drop-zone-file">
                        <i className="fa-solid fa-file drop-icon" />
                        <span className="drop-filename">{file.name}</span>
                        <span className="drop-filesize">
                            {(file.size / 1024 / 1024).toFixed(2)} Mo
                        </span>
                        <button
                            type="button"
                            className="drop-remove"
                            onClick={e => { e.stopPropagation(); resetForm(); }}
                        >✕ Retirer</button>
                    </div>
                ) : (
                    <div className="drop-zone-placeholder">
                        <i className="fa-solid fa-cloud-arrow-up drop-icon-lg" />
                        <p>Glissez votre fichier ici</p>
                        <span>ou cliquez pour parcourir</span>
                    </div>
                )}
            </div>

            {/* ── Options ── */}
            <div className="upload-options">

                {/* Type de document */}
                <div className="form-field">
                    <select
                        className="form-field-input up-select"
                        value={typeDocumentId}
                        onChange={e => handleTypeChange(Number(e.target.value))}
                        aria-label="Type de document"
                        disabled={isProcessing || !!precedentDocument}
                    >
                        <option value="">-- Type de document --</option>
                        {typeDocuments.map(td => (
                            <option key={td.id} value={td.id}>{td.nom}</option>
                        ))}
                    </select>
                </div>

                {/* Accès + intégrité */}
                <div className="up-row">
                    <div className="form-field">
                        <select
                            className="form-field-input up-select"
                            value={access}
                            onChange={e => setAccess(e.target.value as 'PUBLIC' | 'PRIVE')}
                            aria-label="Niveau d'accès"
                            disabled={isProcessing}
                        >
                            <option value="PUBLIC">Public</option>
                            <option value="PRIVE">Privé</option>
                        </select>
                    </div>
                    <div className="form-field">
                        <select
                            className="form-field-input up-select"
                            value={integrityLevel}
                            onChange={e => setIntegrityLevel(e.target.value as 'STANDARD' | 'BLOCKCHAIN')}
                            aria-label="Niveau d'intégrité"
                            disabled={isProcessing}
                        >
                            <option value="STANDARD">Standard</option>
                            <option value="BLOCKCHAIN">Blockchain</option>
                        </select>
                    </div>
                </div>

                {emplacements.length > 0 && (
                    <div className="form-field">
                        <label htmlFor="up-emplacement" className="form-field-label">
                            Emplacement physique de l'original (optionnel)
                        </label>
                        <select
                            id="up-emplacement"
                            className="form-field-input up-select"
                            value={physicalLocationId}
                            onChange={e => setPhysicalLocationId(e.target.value)}
                            disabled={isProcessing}
                        >
                            <option value="">— Aucun —</option>
                            {emplacements.map(loc => (
                                <option key={loc.id} value={loc.id}>{loc.cheminComplet}</option>
                            ))}
                        </select>
                    </div>
                )}

                {/* Groupe si PRIVE */}
                {access === 'PRIVE' && (
                    <div className="groupe-section">
                        <div className="form-field">
                            <input
                                id="groupe-nom"
                                type="text"
                                className="form-field-input"
                                placeholder="Nom du groupe d'accès *" aria-label="Nom du groupe d'accès *"
                                value={groupeNom}
                                onChange={e => setGroupeNom(e.target.value)}
                                disabled={isProcessing}
                            />
                        </div>

                        {users.length > 0 && (
                            <div className="membres-section">
                                <p className="membres-label">
                                    Membres du groupe (optionnel) :
                                </p>
                                <div className="membres-list">
                                    {users.map(u => (
                                        <label key={u.id} className="membre-item">
                                            <input
                                                type="checkbox"
                                                checked={selectedMembres.includes(u.id)}
                                                onChange={() => toggleMembre(u.id)}
                                                disabled={isProcessing}
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
            </div>

            {/* ── Phase 2 : validation des métadonnées + archivage ── */}
            {hasSession && selectedType && (
                <form onSubmit={handleFormSubmit}>

                    {/* Badge OCR */}
                    <div className="up-ocr-badge">
                        <i className="fa-solid fa-wand-magic-sparkles" />
                        {prefilledKeys.size > 0
                            ? `${prefilledKeys.size} champ${prefilledKeys.size > 1 ? 's' : ''} pré-rempli${prefilledKeys.size > 1 ? 's' : ''} par OCR — vérifiez avant d'archiver`
                            : 'Analyse OCR terminée — remplissez les métadonnées'}
                    </div>

                    {/* Champs MetaData typés */}
                    {selectedType.metaData.length > 0 && (
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
                                    value={metaValues[meta.nom] ?? ''}
                                    onChange={v =>
                                        setMetaValues(prev => ({ ...prev, [meta.nom]: v }))
                                    }
                                    prefilled={prefilledKeys.has(meta.nom)}
                                    disabled={isSaving}
                                />
                            ))}
                        </div>
                    )}

                    {/* Bouton archiver */}
                    <button
                        type="submit"
                        className="form-submit-btn up-submit"
                        disabled={isSaving}
                    >
                        {isSaving ? (
                            <><i className="fa-solid fa-spinner fa-spin" /> Archivage…</>
                        ) : (
                            <><i className="fa-solid fa-box-archive" /> Archiver</>
                        )}
                    </button>
                </form>
            )}

            {/* ── Relancer l'OCR manuellement si besoin ── */}
            {!hasSession && !isProcessing && file && typeDocumentId && !sessionId && (
                <button
                    type="button"
                    className="bulk-back-btn"
                    style={{ marginTop: '0.5rem' }}
                    onClick={() => { lastOcrKey.current = ''; lancerOcrPreview(); }}
                >
                    <i className="fa-solid fa-rotate-right" /> Relancer l'analyse OCR
                </button>
            )}
        </div>
    );
}

export default UploadSimple;