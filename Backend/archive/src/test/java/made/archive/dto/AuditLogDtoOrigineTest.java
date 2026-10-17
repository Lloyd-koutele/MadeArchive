package made.archive.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.entite.AuditAction;
import made.archive.util.AuditLogExportFormatter;

/** Qui a fait l'action : un utilisateur, un visiteur non connecté (son IP est conservée) ou le système. */
@Tag("unit")
class AuditLogDtoOrigineTest
{
    @Test
    void unUtilisateurIdentifieNAPasDOrigine()
    {
        assertThat(AuditLogDto.origineDe(UUID.randomUUID(), "a@esp.sn", "10.0.0.1")).isNull();
        assertThat(AuditLogDto.origineDe(null, "a@esp.sn", null)).isNull();
    }

    @Test
    void sansUtilisateurMaisAvecUneAdresseIp_cEstUnVisiteur()
    {
        assertThat(AuditLogDto.origineDe(null, null, "203.0.113.7")).isEqualTo(AuditLogDto.ORIGINE_VISITEUR);
    }

    @Test
    void sansUtilisateurNiAdresseIp_cEstLeSysteme()
    {
        assertThat(AuditLogDto.origineDe(null, null, null)).isEqualTo(AuditLogDto.ORIGINE_SYSTEME);
        assertThat(AuditLogDto.origineDe(null, " ", "")).isEqualTo(AuditLogDto.ORIGINE_SYSTEME);
    }

    private static AuditLogDto entree(String email, String ip)
    {
        return AuditLogDto.builder().horodatage(Instant.parse("2026-10-15T23:12:16Z")).acteurEmail(email).adresseIp(ip)
            .origine(AuditLogDto.origineDe(null, email, ip)).action(AuditAction.DOCUMENT_VERIFICATION_PUBLIQUE)
            .description("Vérification").succes(true).build();
    }

    @Test
    void lesExportsNommentLeVisiteurEtLeSysteme_etGardentLIp()
    {
        List<AuditLogDto> logs = List.of(entree(null, "203.0.113.7"), entree(null, null), entree("a@esp.sn", "10.0.0.1"));

        String csv = AuditLogExportFormatter.versCsv(logs);
        String texte = AuditLogExportFormatter.versLogTexte(logs);

        assertThat(csv).contains(",Visiteur anonyme,,203.0.113.7,").contains(",Système,,,").contains(",a@esp.sn,,10.0.0.1,");
        assertThat(texte).contains("Visiteur anonyme").contains("IP 203.0.113.7").contains("Système");
    }
}
