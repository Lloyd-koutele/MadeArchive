import '../Style/Page/HomeSections.css';

const PRINCIPES = [
    { titre: 'Intégrité', texte: "Toute modification d'un document archivé ou de ses preuves peut être détectée." },
    { titre: 'Traçabilité', texte: "Chaque action est inscrite dans un journal d'audit chaîné, scellé et horodaté." },
    { titre: 'Confidentialité', texte: "Les documents sont chiffrés et les droits d'accès suivent la hiérarchie des unités organisationnelles." },
    { titre: 'Pérennité', texte: "Les documents sont conservés au format PDF/A, conçu pour l'archivage à long terme." },
];

const REFERENTIELS = ['ISO 15489', 'ISAD(G)', 'OAIS (ISO 14721)', 'ISO 14641', 'RFC 3161', 'PDF/A (ISO 19005)'];

const TECHNOLOGIES = ['Spring Boot', 'React', 'PostgreSQL', 'MinIO', 'SoftHSM (PKCS#11)', 'Docker'];

function About() {
    return (
        <section id="about" className="home-section section-compacte">
            <h2>À propos</h2>

            <p className="intro">
                MadeArchive est une plateforme d'archivage électronique conçue à l'École Supérieure
                Polytechnique de Dakar (département Génie Informatique). Elle répond à un besoin simple :
                conserver durablement les documents d'un établissement d'enseignement supérieur
                et pouvoir prouver, des années plus tard, qu'ils sont authentiques.
            </p>

            <h3 className="sous-titre">Notre approche</h3>
            <p className="intro">
                La solution est libre, accessible et souveraine : aucune licence commerciale n'est nécessaire,
                et chaque institution héberge sa propre instance. Chaque document est signé par son éditeur,
                horodaté par une autorité tierce et protégé par une empreinte numérique, ce qui permet de le
                vérifier publiquement sans compte.
            </p>

            <div className="grille-cartes">
                {PRINCIPES.map(p => (
                    <article className="carte" key={p.titre}>
                        <h3>{p.titre}</h3>
                        <p>{p.texte}</p>
                    </article>
                ))}
            </div>

            <h3 className="sous-titre">Références</h3>
            <p className="intro">MadeArchive s'appuie sur les normes et standards de l'archivage électronique :</p>
            <ul className="pastilles">
                {REFERENTIELS.map(r => <li key={r}>{r}</li>)}
            </ul>

            <h3 className="sous-titre">Technologies</h3>
            <ul className="pastilles">
                {TECHNOLOGIES.map(t => <li key={t}>{t}</li>)}
            </ul>
        </section>
    );
}

export default About;
