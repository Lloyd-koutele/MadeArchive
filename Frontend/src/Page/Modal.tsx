import  { useEffect } from "react";
import type { ReactNode } from "react"; 
import '../Style/Page/Modal.css';

interface ModalProps {
    isOpen: boolean;
    onClose: () => void;
    title: string;
    children: ReactNode;
    /** 'large' pour un contenu qui a besoin de place (ex : visionneuse + formulaire côte à côte). */
    size?: 'default' | 'large';
}

function Modal({ isOpen, onClose, title, children, size = 'default' }: ModalProps) {

    useEffect(() => {
        const handleEsc = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                onClose();
            }
        };

        document.addEventListener("keydown", handleEsc);

        return () => {
            document.removeEventListener("keydown", handleEsc);
        };
    }, [onClose]);

    if (!isOpen) return null;

    return (
        <div className="modal-overlay">
            <div className={`modal-content ${size === 'large' ? 'modal-content-large' : ''}`}>
                <div className="modal-header">
                    <h2>{title}</h2>
                    <button onClick={onClose} aria-label="Fermer la fenêtre modale">
                        <i className="fa-solid fa-xmark"></i>
                    </button>
                </div>

                <div className="modal-body">
                    {children}
                </div>
            </div>
        </div>
    );
}

export default Modal;