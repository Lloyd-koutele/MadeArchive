package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import made.archive.dto.MetaDataDto;
import made.archive.entite.MetaData;
import made.archive.entite.TypeDocument;

/** L'éditeur choisit l'ordre des champs, à la création comme à la mise à jour : il est conservé champ par champ. */
class TypeDocumentOrdreChampsTest
{
    private MetaData champ(long id, String nom, int ordre)
    {
        MetaData m = new MetaData();
        m.setId(id);
        m.setNom(nom);
        m.setObligatoire(true);
        m.setOrdre(ordre);
        m.setExtractionRegex("regex-" + nom);
        return m;
    }

    private MetaDataDto demande(Long id, String nom)
    {
        return new MetaDataDto(id, nom, true);
    }

    private List<String> nomsDansLOrdre(List<MetaData> champs)
    {
        return champs.stream().sorted(Comparator.comparing(MetaData::getOrdre)).map(MetaData::getNom).toList();
    }

    @Test
    void reordonner_changeLesPositionsSansTouchAuxIdentifiantsNiAuxRegex()
    {
        List<MetaData> courants = new ArrayList<>(List.of(champ(1, "Ecole", 0), champ(2, "Département", 1), champ(3, "Classe", 2)));

        TypeDocumentService.synchroniserMetaData(new TypeDocument(), courants,
            List.of(demande(3L, "Classe"), demande(1L, "Ecole"), demande(2L, "Département")));

        assertThat(nomsDansLOrdre(courants)).containsExactly("Classe", "Ecole", "Département");
        // même champs, même identifiants : la règle d'extraction déjà générée est conservée
        assertThat(courants).extracting(MetaData::getId).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(courants).extracting(MetaData::getExtractionRegex)
            .containsExactlyInAnyOrder("regex-Ecole", "regex-Département", "regex-Classe");
    }

    @Test
    void unNouveauChampPrendSaPlaceDansLaListeDemandee()
    {
        List<MetaData> courants = new ArrayList<>(List.of(champ(1, "Ecole", 0), champ(2, "Classe", 1)));

        TypeDocumentService.synchroniserMetaData(new TypeDocument(), courants,
            List.of(demande(1L, "Ecole"), demande(null, "Option"), demande(2L, "Classe")));

        assertThat(courants).hasSize(3);
        assertThat(nomsDansLOrdre(courants)).containsExactly("Ecole", "Option", "Classe");
    }

    @Test
    void unChampRetireDeLaDemandeEstSupprime_etLesAutresSontRenumerotes()
    {
        List<MetaData> courants = new ArrayList<>(List.of(champ(1, "Ecole", 0), champ(2, "Département", 1), champ(3, "Classe", 2)));

        TypeDocumentService.synchroniserMetaData(new TypeDocument(), courants,
            List.of(demande(3L, "Classe"), demande(1L, "Ecole")));

        assertThat(nomsDansLOrdre(courants)).containsExactly("Classe", "Ecole");
        assertThat(courants).extracting(MetaData::getOrdre).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void leRenommageEtLeCaractereObligatoireSuiventLaDemande()
    {
        List<MetaData> courants = new ArrayList<>(List.of(champ(1, "Ecole", 0)));

        TypeDocumentService.synchroniserMetaData(new TypeDocument(), courants, List.of(new MetaDataDto(1L, "Établissement", false)));

        assertThat(courants.get(0).getNom()).isEqualTo("Établissement");
        assertThat(courants.get(0).getObligatoire()).isFalse();
    }
}
