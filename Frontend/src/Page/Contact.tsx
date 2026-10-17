import { useState } from 'react';
import type { FormEvent } from 'react';
import '../Style/Page/HomeSections.css';

const EMAIL = 'marvinlloydkoutele@esp.sn';
const TELEPHONE = '+221 77 837 01 57';
const TELEPHONE_LIEN = '+221778370157';

function Contact() {
    const [nom, setNom] = useState('');
    const [email, setEmail] = useState('');
    const [objet, setObjet] = useState('');
    const [message, setMessage] = useState('');

    // Pas de serveur de messagerie à configurer : le formulaire prépare le message
    // dans le logiciel de messagerie de l'utilisateur, qui l'envoie lui-même.
    const envoyer = (e: FormEvent) => {
        e.preventDefault();
        const corps = `${message}\n\n— ${nom} (${email})`;
        window.location.href =
            `mailto:${EMAIL}?subject=${encodeURIComponent(objet || 'Contact MadeArchive')}&body=${encodeURIComponent(corps)}`;
    };

    return (
        <section id="contact" className="home-section section-compacte section-contact">
            <h2>Contact</h2>
            <p className="intro">
                Une question sur MadeArchive, une demande de démonstration ou un problème à signaler ?
                Écrivez-nous.
            </p>

            <div className="contact-grille">
                <div className="contact-infos">
                    <div className="contact-ligne">
                        <i className="fa-solid fa-envelope" aria-hidden="true"></i>
                        <a href={`mailto:${EMAIL}`}>{EMAIL}</a>
                    </div>
                    <div className="contact-ligne">
                        <i className="fa-solid fa-phone" aria-hidden="true"></i>
                        <a href={`tel:${TELEPHONE_LIEN}`}>{TELEPHONE}</a>
                    </div>
                    <div className="contact-ligne">
                        <i className="fa-solid fa-location-dot" aria-hidden="true"></i>
                        <span>
                            École Supérieure Polytechnique<br />
                            Université Cheikh Anta Diop, Dakar, Sénégal
                        </span>
                    </div>
                </div>

                <form className="contact-formulaire" onSubmit={envoyer}>
                    <label htmlFor="contact-nom">Nom</label>
                    <input id="contact-nom" type="text" value={nom} onChange={e => setNom(e.target.value)} required />

                    <label htmlFor="contact-email">Adresse e-mail</label>
                    <input id="contact-email" type="email" value={email} onChange={e => setEmail(e.target.value)} required />

                    <label htmlFor="contact-objet">Objet</label>
                    <input id="contact-objet" type="text" value={objet} onChange={e => setObjet(e.target.value)} required />

                    <label htmlFor="contact-message">Message</label>
                    <textarea id="contact-message" rows={3} value={message} onChange={e => setMessage(e.target.value)} required />

                    <button type="submit" className="action-button">Envoyer</button>
                    <p className="contact-note">
                        Le bouton ouvre votre logiciel de messagerie avec le message prêt à envoyer.
                    </p>
                </form>
            </div>
        </section>
    );
}

export default Contact;
