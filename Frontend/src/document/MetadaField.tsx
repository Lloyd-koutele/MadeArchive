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

    // Le libellé vit dans le placeholder ; seule la note OCR reste affichée,
    // car elle n'apparaît que lorsque le champ est rempli.
    const placeholderText = obligatoire ? `${nom} *` : nom;

    const labelContent = prefilled && value
        ? <span className="meta-prefilled">pré-rempli par OCR</span>
        : null;

    // ── BOOLEAN ───────────────────────────────────────────────────────────
    if (type === 'BOOLEAN') {
        return (
            <div className="form-field">
                <select
                    id={id}
                    className="form-field-input up-select"
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                >
                    <option value="">{placeholderText}</option>
                    <option value="true">Oui</option>
                    <option value="false">Non</option>
                </select>
                {labelContent}
            </div>
        );
    }

    // ── TEXT (textarea) ────────────────────────────────────────────────────
    if (type === 'TEXT') {
        return (
            <div className="form-field meta-field-textarea">
                <textarea
                    id={id}
                    className="form-field-input meta-textarea"
                    placeholder={placeholderText}
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    rows={3}
                    aria-label={nom}
                />
                {labelContent}
            </div>
        );
    }

    // ── DATE ───────────────────────────────────────────────────────────────
    if (type === 'DATE') {
        return (
            <div className="form-field meta-field-date">
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
                <label htmlFor={id} className="meta-date-label">
                    {nom}
                    {obligatoire && <span className="required-star"> *</span>}
                    {prefilled && value && (
                        <span className="meta-prefilled"> (pré-rempli par OCR)</span>
                    )}
                </label>
            </div>
        );
    }

    // ── INTEGER ────────────────────────────────────────────────────────────
    if (type === 'INTEGER') {
        return (
            <div className="form-field">
                <input
                    id={id}
                    type="number"
                    step="1"
                    className="form-field-input"
                    placeholder={placeholderText}
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
                {labelContent}
            </div>
        );
    }

    // ── FLOAT / DOUBLE ─────────────────────────────────────────────────────
    if (type === 'FLOAT' || type === 'DOUBLE') {
        return (
            <div className="form-field">
                <input
                    id={id}
                    type="number"
                    step="any"
                    className="form-field-input"
                    placeholder={placeholderText}
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
                {labelContent}
            </div>
        );
    }

    // ── CHAR ────────────────────────────────────────────────────────────────
    if (type === 'CHAR') {
        return (
            <div className="form-field">
                <input
                    id={id}
                    type="text"
                    maxLength={1}
                    className="form-field-input meta-char-input"
                    placeholder={placeholderText}
                    value={value}
                    onChange={e => onChange(e.target.value.slice(0, 1))}
                    required={obligatoire}
                    disabled={disabled}
                    aria-label={nom}
                />
                {labelContent}
            </div>
        );
    }

    // ── STRING (défaut) ─────────────────────────────────────────────────────
    return (
        <div className="form-field">
            <input
                id={id}
                type="text"
                className="form-field-input"
                placeholder={placeholderText}
                value={value}
                onChange={e => onChange(e.target.value)}
                required={obligatoire}
                disabled={disabled}
                aria-label={nom}
            />
            {labelContent}
        </div>
    );
}

export default MetaDataField;