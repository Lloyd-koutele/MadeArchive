import '../Style/Page/HomeSections.css';

const POINTS_FORTS = [
    {
        icone: 'fa-solid fa-shield-halved',
        titre: 'Authenticité garantie',
        texte: "Chaque document reçoit une empreinte numérique, la signature de son éditeur et un horodatage délivré par un tiers.",
    },
    {
        icone: 'fa-solid fa-lock',
        titre: 'Documents protégés',
        texte: "Les fichiers sont chiffrés et l'accès dépend du rôle et de l'unité organisationnelle de chaque utilisateur.",
    },
    {
        icone: 'fa-solid fa-qrcode',
        titre: 'Vérification publique',
        texte: "Un QR code permet à toute personne de contrôler un document archivé, sans avoir de compte.",
    },
    {
        icone: 'fa-solid fa-server',
        titre: 'Vos données chez vous',
        texte: "Chaque institution héberge sa propre instance, sur son infrastructure, sans licence commerciale.",
    },
];

const ETAPES = [
    {
        numero: '1',
        titre: 'Déposer',
        texte: "L'éditeur importe un document (fichier, lot ou lien web). Le texte est extrait et les informations sont pré-remplies.",
    },
    {
        numero: '2',
        titre: 'Valider',
        texte: "L'éditeur contrôle et corrige les informations. Rien n'est archivé avant sa validation.",
    },
    {
        numero: '3',
        titre: 'Archiver',
        texte: "Le document est converti en PDF/A, signé, chiffré, horodaté et indexé, puis conservé selon sa règle de rétention.",
    },
];

function Accueil() {
    return (
        <section id="accueil" className="home-section section-accueil">
            <div className="hero">
                <h1>Archivez vos documents.<br />Prouvez leur authenticité.</h1>
                <p className="hero-texte">
                    MadeArchive conserve les documents officiels de votre établissement et permet
                    à quiconque de vérifier qu'ils n'ont pas été modifiés.
                </p>
                <div className="hero-boutons">
                    <button
                        className="action-button"
                        onClick={() => { window.location.href = '/login'; }}
                    >
                        Se connecter
                    </button>
                    <a className="lien-bouton" href="#about">En savoir plus</a>
                </div>
            </div>

            <div className="grille-cartes">
                {POINTS_FORTS.map(p => (
                    <article className="carte" key={p.titre}>
                        <i className={p.icone} aria-hidden="true"></i>
                        <h3>{p.titre}</h3>
                        <p>{p.texte}</p>
                    </article>
                ))}
            </div>

            <h2 className="titre-bloc">Comment ça marche</h2>
            <ol className="etapes">
                {ETAPES.map(e => (
                    <li className="etape" key={e.numero}>
                        <span className="etape-numero">{e.numero}</span>
                        <h3>{e.titre}</h3>
                        <p>{e.texte}</p>
                    </li>
                ))}
            </ol>

            <div className="bloc-verification">
                <i className="fa-solid fa-circle-check" aria-hidden="true"></i>
                <div>
                    <h3>Vérifier un document</h3>
                    <p>
                        Chaque attestation d'archivage porte un QR code. En le scannant, vous accédez à une page
                        publique qui recalcule l'empreinte du document et contrôle sa signature et son horodatage,
                        sans compte et sans rien installer.
                    </p>
                </div>
            </div>
        </section>
    );
}

export default Accueil;
