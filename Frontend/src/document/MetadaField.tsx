import type { MetaDataType } from '../services/document/DocumentService';

interface MetaDataFieldProps {
    nom: string;
    type: MetaDataType;
    obligatoire: boolean;
    value: string;
    onChange: (value: string) => void;
    prefilled?: boolean;
    disabled?: boolean;
}

function MetaDataField({
    nom,
    type,
    obligatoire,
    value,
    onChange,
    prefilled = false,
    disabled = false,
}: MetaDataFieldProps) {
    const id = `meta-${nom.replace(/\s+/g, '-').toLowerCase()}`;

    // Label flottant, commun à tous les types texte ci-dessous : placeholder=" "
    // (un espace, jamais vide) est nécessaire pour que :placeholder-shown
    // distingue correctement un champ vide d'un champ rempli — un placeholder
    // réellement vide ne déclenche pas :placeholder-shown de façon fiable.
    const floatingLabel = (
        <label htmlFor={id} className="meta-floating-label">
            {nom}
            {obligatoire && <span className="required-star"> *</span>}
        </label>
    );

    // ── BOOLEAN ───────────────────────────────────────────────────────────
    if (type === 'BOOLEAN') {
        return (
            <div className="form-field meta-field-static-label">
                <label htmlFor={id} className="meta-static-label">
                    {nom}
                    {obligatoire && <span className="required-star"> *</span>}
                </label>
                <select
                    id={id}
                    className="form-field-input up-select"
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                >
                    <option value="" disabled hidden />
                    <option value="true">Oui</option>
                    <option value="false">Non</option>
                </select>
            </div>
        );
    }

    // ── TEXT (textarea) ────────────────────────────────────────────────────
    if (type === 'TEXT') {
        return (
            <div className="form-field meta-field-textarea meta-field-floating">
                <textarea
                    id={id}
                    className="form-field-input meta-textarea"
                    placeholder=" "
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    rows={3}
                    aria-label={nom}
                />
                {floatingLabel}
            </div>
        );
    }

    // ── DATE ───────────────────────────────────────────────────────────────
    if (type === 'DATE') {
        return (
            <div className="form-field meta-field-date">
                <label htmlFor={id} className="meta-static-label">
                    {nom}
                    {obligatoire && <span className="required-star"> *</span>}
                    {prefilled && value && (
                        <span className="meta-prefilled"> (pré-rempli par OCR)</span>
                    )}
                </label>
                <input
                    id={id}
                    type="date"
                    className="form-field-input meta-date-input"
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
            </div>
        );
    }

    // ── INTEGER ────────────────────────────────────────────────────────────
    if (type === 'INTEGER') {
        return (
            <div className="form-field meta-field-floating">
                <input
                    id={id}
                    type="number"
                    step="1"
                    className="form-field-input"
                    placeholder=" "
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
                {floatingLabel}
            </div>
        );
    }

    // ── FLOAT / DOUBLE ─────────────────────────────────────────────────────
    if (type === 'FLOAT' || type === 'DOUBLE') {
        return (
            <div className="form-field meta-field-floating">
                <input
                    id={id}
                    type="number"
                    step="any"
                    className="form-field-input"
                    placeholder=" "
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
                {floatingLabel}
            </div>
        );
    }

    // ── CHAR ────────────────────────────────────────────────────────────────
    if (type === 'CHAR') {
        return (
            <div className="form-field meta-field-floating">
                <input
                    id={id}
                    type="text"
                    maxLength={1}
                    className="form-field-input meta-char-input"
                    placeholder=" "
                    value={value}
                    onChange={e => onChange(e.target.value.slice(0, 1))}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
                {floatingLabel}
            </div>
        );
    }

    // ── STRING (défaut) ─────────────────────────────────────────────────────
    return (
        <div className="form-field meta-field-floating">
            <input
                id={id}
                type="text"
                className="form-field-input"
                placeholder=" "
                value={value}
                onChange={e => onChange(e.target.value)}
                required={obligatoire}
                disabled={disabled}
                aria-label={nom}
            />
            {floatingLabel}
        </div>
    );
}

export default MetaDataField;
