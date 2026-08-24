import "../Style/Page/Footer.css"
function Footer() {
    return (
        <footer>
            <p className="footer">© {new Date().getFullYear()} - Système de Gestion d'archive</p>
        </footer>


    );
}

export default Footer;
