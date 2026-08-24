import React, { useState } from 'react';
import { uploadBulkMultiType } from '../services/document/DocumentService';
import { getCurrentUserInfo } from '../auth/authService';
import type { BulkUploadReportDto } from '../services/document/DocumentService';
import { BulkReport } from './UploadeBlukSameType';
import '../Style/Editor/Editor.css';

interface UploadBulkMultiTypeProps {
    onsuccess?: (report: BulkUploadReportDto) => void;
}

function UploadBulkMultiType({ onsuccess }: UploadBulkMultiTypeProps) {
    const [metaFile, setMetaFile] = useState<File | null>(null);
    const [zipFile, setZipFile] = useState<File | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState('');
    const [report, setReport] = useState<BulkUploadReportDto | null>(null);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError('');
        if (!metaFile) { setError("Le fichier CSV ou Excel est obligatoire"); return; }
        if (!zipFile) { setError("Le fichier ZIP est obligatoire"); return; }

        const userInfo = getCurrentUserInfo();
        if (!userInfo?.id) { setError("Session expirée"); return; }

        setIsLoading(true);
        try {
            const rep = await uploadBulkMultiType(metaFile, zipFile, userInfo.id);
            setReport(rep);
            setMetaFile(null);
            setZipFile(null);
            onsuccess?.(rep);
        } catch (err: any) {
            setError(err.message || "Erreur lors de l'upload multi-type");
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="upload-wrapper">
            {error && <div className="up-alert up-alert-error">{error}</div>}

            <div className="multitype-info">
                <i className="fa-solid fa-circle-info"></i>
                <p>
                    Uploadez un fichier <strong>CSV ou Excel</strong> contenant les métadonnées
                    et un <strong>ZIP</strong> contenant les documents correspondants.
                    Chaque ligne du CSV doit contenir <code>nom_fichier</code> et <code>type_document</code>.
                </p>
            </div>

            <form onSubmit={handleSubmit}>
                <div className="multitype-files">

                    {/* Fichier CSV/Excel */}
                    <div className="multitype-file-zone">
                        <label className="multitype-label">
                            <i className="fa-solid fa-table"></i> Fichier CSV ou Excel
                        </label>
                        <div
                            className={`drop-zone drop-zone-sm ${metaFile ? 'has-file' : ''}`}
                            onClick={() => document.getElementById('meta-file-input')?.click()}
                            onDragOver={e => e.preventDefault()}
                            onDrop={e => {
                                e.preventDefault();
                                setMetaFile(e.dataTransfer.files[0] || null);
                            }}
                        >
                            <input
                                id="meta-file-input"
                                type="file"
                                accept=".csv,.xlsx"
                                className="sr-only"
                                aria-label='Deposer le fichier Excel/Csv'
                                onChange={e => setMetaFile(e.target.files?.[0] || null)}
                            />
                            {metaFile ? (
                                <div className="drop-zone-file">
                                    <i className="fa-solid fa-file-excel drop-icon"></i>
                                    <span className="drop-filename">{metaFile.name}</span>
                                    <button
                                        type="button"
                                        className="drop-remove"
                                        onClick={e => { e.stopPropagation(); setMetaFile(null); }}
                                    >✕</button>
                                </div>
                            ) : (
                                <div className="drop-zone-placeholder">
                                    <i className="fa-solid fa-file-csv drop-icon"></i>
                                    <span>.csv ou .xlsx</span>
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Fichier ZIP */}
                    <div className="multitype-file-zone">
                        <label className="multitype-label">
                            <i className="fa-solid fa-file-zipper"></i> Fichier ZIP
                        </label>
                        <div
                            className={`drop-zone drop-zone-sm ${zipFile ? 'has-file' : ''}`}
                            onClick={() => document.getElementById('zip-file-input')?.click()}
                            onDragOver={e => e.preventDefault()}
                            onDrop={e => {
                                e.preventDefault();
                                setZipFile(e.dataTransfer.files[0] || null);
                            }}
                        >
                            <input
                                id="zip-file-input"
                                type="file"
                                accept=".zip"
                                className="sr-only"
                                aria-label='Deposer le fichier .zip'
                                onChange={e => setZipFile(e.target.files?.[0] || null)}
                            />
                            {zipFile ? (
                                <div className="drop-zone-file">
                                    <i className="fa-solid fa-file-zipper drop-icon"></i>
                                    <span className="drop-filename">{zipFile.name}</span>
                                    <button
                                        type="button"
                                        className="drop-remove"
                                        onClick={e => { e.stopPropagation(); setZipFile(null); }}
                                    >✕</button>
                                </div>
                            ) : (
                                <div className="drop-zone-placeholder">
                                    <i className="fa-solid fa-file-zipper drop-icon"></i>
                                    <span>.zip</span>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                <button
                    type="submit"
                    className="form-submit-btn up-submit"
                    disabled={isLoading || !metaFile || !zipFile}
                >
                    {isLoading
                        ? <><i className="fa-solid fa-spinner fa-spin"></i> Traitement en cours...</>
                        : <><i className="fa-solid fa-upload"></i> Lancer l'import multi-type</>
                    }
                </button>
            </form>

            {report && <BulkReport report={report} />}
        </div>
    );
}

export default UploadBulkMultiType;