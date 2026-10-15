package made.archive.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import made.archive.service.audit.AuditChainService;
import made.archive.service.integrite.AlerteIntegriteService;

/** Après un échec d'horodatage du scellement, le planificateur attend le délai de reprise avant de rappeler la TSA. */
class AuditChainSchedulerTest
{
    private AuditChainService service;
    private AuditChainScheduler scheduler;

    @BeforeEach
    void init()
    {
        service = mock(AuditChainService.class);
        scheduler = new AuditChainScheduler(service, mock(AlerteIntegriteService.class));
        ReflectionTestUtils.setField(scheduler, "delaiRepriseScellementMs", 300_000L);
    }

    @Test
    void scellementReussi_leProchainPassageAppelleLaTsa()
    {
        when(service.scellementEnEchec()).thenReturn(false);

        scheduler.sceller();
        scheduler.sceller();

        verify(service, times(2)).scellerSiNecessaire();
    }

    @Test
    void echecDHorodatage_lesPassagesSuivantsAttendentLeDelaiDeReprise()
    {
        when(service.scellementEnEchec()).thenReturn(true);

        scheduler.sceller();   // appelle la TSA, échoue
        scheduler.sceller();   // 2 minutes plus tard : trop tôt, sauté
        scheduler.sceller();

        verify(service, times(1)).scellerSiNecessaire();
    }

    @Test
    void echecDHorodatage_apresLeDelaiLaTsaEstRappelee()
    {
        when(service.scellementEnEchec()).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "delaiRepriseScellementMs", 0L);

        scheduler.sceller();
        scheduler.sceller();

        verify(service, times(2)).scellerSiNecessaire();
    }
}
