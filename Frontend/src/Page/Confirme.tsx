// Confirme.jsx
import "../Style/Page/Confirme.css";

const Confirme = ({ isOpen, message, onConfirm, onCancel }) => {
    if (!isOpen) return null;

    return (
        <div className="modal-overlay" onClick={onCancel}>
            <div className="modal-box" onClick={(e) => e.stopPropagation()}>
                <p>{message}</p>
                <div className="modal-actions">
                    <button onClick={onCancel} className="btn-cancel">Annuler</button>
                    <button onClick={onConfirm} className="btn-confirm">Confirmer</button>
                </div>
            </div>
        </div>
    );
};

export default Confirme;
