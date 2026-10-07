package made.archive.service.importweb;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.exception.BusinessException;

@Tag("unit")
class LimiteurImportsTest
{
    private final AtomicLong horloge = new AtomicLong(1_000_000);
    private final LimiteurImports limiteur = new LimiteurImports(horloge::get);

    @Test
    void autoriseJusquAuPlafondPuisRefuse()
    {
        UUID u = UUID.randomUUID();
        limiteur.consommer(u, 3, 5);
        limiteur.consommer(u, 2, 5);

        assertThatThrownBy(() -> limiteur.consommer(u, 1, 5))
            .isInstanceOf(BusinessException.class).hasMessageContaining("limite : 5 par minute");
    }

    @Test
    void laFenetreGlisse()
    {
        UUID u = UUID.randomUUID();
        limiteur.consommer(u, 5, 5);
        horloge.addAndGet(59_000);
        assertThatThrownBy(() -> limiteur.consommer(u, 1, 5)).isInstanceOf(BusinessException.class);

        horloge.addAndGet(2_000);   // plus d'une minute après les premiers accès
        assertThatCode(() -> limiteur.consommer(u, 5, 5)).doesNotThrowAnyException();
    }

    @Test
    void chaqueUtilisateurAsonPropreCompteur()
    {
        limiteur.consommer(UUID.randomUUID(), 5, 5);
        assertThatCode(() -> limiteur.consommer(UUID.randomUUID(), 5, 5)).doesNotThrowAnyException();
    }

    @Test
    void uneDemandeTropGrosseNEnregistreRien()
    {
        UUID u = UUID.randomUUID();
        assertThatThrownBy(() -> limiteur.consommer(u, 6, 5)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiteur.consommer(u, 5, 5)).doesNotThrowAnyException();
    }
}
