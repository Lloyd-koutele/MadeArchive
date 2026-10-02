package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import made.archive.config.SedaProperties;
import made.archive.dto.DocumentExportRow;
import made.archive.entite.DocumentStatus;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeAccess;
import made.archive.service.document.SedaManifestBuilder.Contexte;
import made.archive.service.document.SedaManifestBuilder.DossierInfo;
import made.archive.service.document.SedaManifestBuilder.Entree;
import made.archive.service.document.SedaManifestBuilder.Noeud;

/** Le manifest généré doit être valide au regard du XSD SEDA 2.1 officiel (chargé comme en production). */
class SedaManifestBuilderTest
{
    private static final String SHA = "a".repeat(64);

    private final SedaExportGenerationService validateur =
        new SedaExportGenerationService(null, null, null, null, null, null, new SedaProperties());

    private Contexte contexte()
    {
        return new Contexte("MSG-1", Instant.parse("2026-10-02T08:00:00Z"), "Test", "ACCORD", "SERVICE", "UO-1", "DUA-");
    }

    private Entree entree(String titre, String typeNom, Long dossierId, Long noeudId, Long annees, SortFinal sort,
                          String cheminUO, Map<String, String> metas)
    {
        UUID id = UUID.randomUUID();
        DocumentExportRow row = new DocumentExportRow(id, titre, "k", TypeAccess.PUBLIC, DocumentStatus.ACTIVE,
            LocalDateTime.of(2026, 1, 15, 10, 0), 1L, "UO", typeNom, null, dossierId, noeudId,
            SHA, SHA, null, null, null, null, annees, sort);
        return new Entree(row, SHA, 1234, "content/" + id + ".pdf", cheminUO, metas);
    }

    @Test
    void manifestAvecActivitesDossiersEtRegles_estValideAuXsd()
    {
        Map<Long, Noeud> noeuds = Map.of(
            1L, new Noeud(1L, "03", "Finances", null),
            2L, new Noeud(2L, "03.2", "Factures & paiements", 1L));
        Map<Long, DossierInfo> dossiers = Map.of(
            10L, new DossierInfo(10L, "Marché 2026", null),
            11L, new DossierInfo(11L, "Lot <1>", 10L));

        List<Entree> entrees = List.of(
            entree("facture-1.pdf", "Facture", 11L, 2L, 5L, SortFinal.DETRUIRE, "Ucad/Faculté Sciences", Map.of("Numéro", "F-001", "Montant", "1500")),
            entree("facture-2.pdf", "Facture", 11L, 2L, 5L, SortFinal.CONSERVER, "Ucad/Faculté Sciences", Map.of()),
            entree("note", "Note", null, null, null, SortFinal.TRIER, "Ucad/Faculté Sciences", Map.of("Objet", "contrôle\u0001 «é»")),
            entree("hors-uo.pdf", "Note", null, null, 10L, SortFinal.CONSERVER, null, null));

        byte[] xml = new SedaManifestBuilder().construire(contexte(), entrees, noeuds, dossiers, true);
        validateur.valider(xml);

        String s = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(s).contains("<Title>03 Finances</Title>", "<Title>03.2 Factures &amp; paiements</Title>",
            "<Title>Marché 2026</Title>", "<Title>Lot &lt;1&gt;</Title>");
        assertThat(s).contains("<Rule>DUA-5ANS</Rule>", "<FinalAction>Destroy</FinalAction>", "<FinalAction>Keep</FinalAction>");
        assertThat(s).doesNotContain("\u0001");
        // Les deux factures partagent les MÊMES unités UO/activité/dossier (pas de doublons)
        assertThat(count(s, "<Title>03 Finances</Title>")).isEqualTo(1);
        assertThat(count(s, "<Title>Marché 2026</Title>")).isEqualTo(1);
        // Un document sans durée de rétention : FinalAction seul, sans Rule (note : retention null)
        assertThat(count(s, "<Rule>")).isEqualTo(3);
        // Identifiants uniques (xsd:ID)
        Matcher m = Pattern.compile("<ArchiveUnit id=\"([^\"]+)\"").matcher(s);
        java.util.Set<String> ids = new java.util.HashSet<>();
        while (m.find()) assertThat(ids.add(m.group(1))).isTrue();
    }

    @Test
    void sansOptionDossier_lesDocumentsNeSontPasImbriquesDansLesDossiers()
    {
        Map<Long, DossierInfo> dossiers = Map.of(10L, new DossierInfo(10L, "Marché 2026", null));
        byte[] xml = new SedaManifestBuilder().construire(contexte(),
            List.of(entree("a.pdf", "Facture", 10L, null, 1L, SortFinal.CONSERVER, "UO", Map.of())),
            Map.of(), dossiers, false);
        validateur.valider(xml);
        assertThat(new String(xml, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Marché 2026");
    }

    @Test
    void unManifestInvalideEstRefuse()
    {
        assertThatThrownBy(() -> validateur.valider("<ArchiveTransfer xmlns=\"fr:gouv:culture:archivesdefrance:seda:v2.1\"/>".getBytes()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalide");
    }

    private static int count(String s, String sub)
    {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
