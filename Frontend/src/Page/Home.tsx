import { useState } from 'react';
import Footer from '../Page/Footer';
import '../Style/Page/Home.css';

function Home() {
    const [isOpen, setIsOpen] = useState(false);

    const toggleMenu = () => setIsOpen(prev => !prev);
    const closeMenu = () => setIsOpen(false);

    return (
        <div className="page">
            <header>
                <nav className="navbar">
                    <div className="logo">
                        <a href="#accueil" onClick={closeMenu}>MadeArchive</a>
                    </div>

                    <ul className="links">
                        <li><a href="#accueil">Accueil</a></li>
                        <li><a href="#about">À propos</a></li>
                        <li><a href="#contact">Contact</a></li>
                    </ul>

                    <div className="buttons">
                        <button
                            className="action-button pro"
                            onClick={() => { window.location.href = '/login'; }}
                        >
                            Se connecter
                        </button>
                    </div>

                    <div className="burger-menu-button" onClick={toggleMenu}>
                        <i className={isOpen ? 'fa-solid fa-xmark' : 'fa-solid fa-bars'}></i>
                    </div>
                </nav>

                <div className={`burger-menu ${isOpen ? 'open' : ''}`}>
                    <ul className="links">
                        <li><a href="#accueil" onClick={closeMenu}>Accueil</a></li>
                        <li><a href="#about" onClick={closeMenu}>À propos</a></li>
                        <li><a href="#contact" onClick={closeMenu}>Contact</a></li>
                    </ul>
                    <div className="divider"></div>
                    <div className="buttons-burger-menu">
                        <button
                            className="action-button pro"
                            onClick={() => { window.location.href = '/login'; }}
                        >
                            Se connecter
                        </button>
                    </div>
                </div>
            </header>

            <main>
                <section id="accueil" className="home-section"></section>
                <section id="about" className="home-section"></section>
                <section id="contact" className="home-section"></section>
            </main>
            <Footer />
        </div>
    );
}

export default Home;