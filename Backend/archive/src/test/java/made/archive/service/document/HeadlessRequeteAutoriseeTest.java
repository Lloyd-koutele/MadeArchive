package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Les requêtes que la page rendue par Chromium voudrait faire vers l'intérieur du réseau sont bloquées. */
@Tag("unit")
class HeadlessRequeteAutoriseeTest
{
    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:9000/documents/x", "http://localhost:7700/indexes", "http://169.254.169.254/latest/meta-data/",
        "http://10.1.2.3/", "http://172.20.0.5:9000/", "http://192.168.0.1/", "http://[::1]:6379/", "http://100.64.1.1/",
        "file:///etc/passwd", "ftp://example.com/a", "chrome://settings", "javascript:alert(1)", "ws://127.0.0.1:3000/",
        "not a url", "http:///sans-hote"
    })
    void bloqueLesAdressesInternesEtLesSchemasInterdits(String url)
    {
        assertThat(HeadlessBrowserImportService.requeteAutorisee(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "data:text/plain;base64,aGk=", "blob:https://example.com/abc", "about:blank", "http://8.8.8.8/x", "https://1.1.1.1/" })
    void autoriseCeQuiNeToucheAucunReseauInterne(String url)
    {
        assertThat(HeadlessBrowserImportService.requeteAutorisee(url)).as(url).isTrue();
    }
}
