package made.archive.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.unit.DataSize;

/** Les limites du dépôt local et de l'import par lien viennent d'une seule source (app.limites.*). */
class LimitesFichiersPropertiesTest
{
    private StandardEnvironment environnement() throws Exception
    {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties"))
        {
            p.load(in);
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new PropertiesPropertySource("application", p));
        return env;
    }

    @Test
    void valeurs_par_defaut_de_l_application()
    {
        LimitesFichiersProperties l = new LimitesFichiersProperties();

        assertThat(l.getMaxFichier()).isEqualTo(DataSize.ofMegabytes(101));
        assertThat(l.getMaxTotal()).isEqualTo(DataSize.ofMegabytes(2000));
        assertThat(l.getMaxFichiers()).isEqualTo(10_000);
    }

    @Test
    void les_reglages_multipart_et_tomcat_derivent_de_la_meme_source() throws Exception
    {
        StandardEnvironment env = environnement();

        assertThat(env.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("101MB");
        assertThat(env.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("2000MB");
        assertThat(env.getProperty("server.tomcat.max-part-count")).isEqualTo("10000");
        assertThat(env.getProperty("server.tomcat.max-parameter-count")).isEqualTo("10000");
    }

    @Test
    void l_import_d_un_dossier_drive_et_la_limite_par_minute_suivent_le_nombre_maximal_de_fichiers() throws Exception
    {
        StandardEnvironment env = environnement();

        assertThat(env.getProperty("google-drive.max-fichiers")).isEqualTo("10000");
        assertThat(env.getProperty("web-import.http.limite-par-minute")).isEqualTo("10000");
    }
}
