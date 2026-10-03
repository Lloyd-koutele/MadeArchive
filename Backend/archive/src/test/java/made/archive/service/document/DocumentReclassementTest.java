package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.userdetails.UserDetails;

import made.archive.dto.DataTypeDto;
import made.archive.dto.ReclassementRequestDto;
import made.archive.entite.AuditAction;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.LocationModeContrainte;
import made.archive.entite.MetaData;
import made.archive.entite.PlanClassementNoeud;
import made.archive.entite.PhysicalLocation;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DataTypeRepository;
import made.archive.repository.DocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;

/** Reclassement : type + métadonnées, sans toucher au fichier ; échéance recalculée ; historique dans le journal. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentReclassementTest
{
    @Mock DocumentRepository documentRepository;
    @Mock UserRepository userRepository;
    @Mock DataTypeRepository dataTypeRepository;
    @Mock AuditLogService auditLogService;
    @Mock TypeDocumentService typeDocumentService;
    @Mock MeilisearchService meilisearchService;
    @Mock UniteOrganisationnelleService uniteOrganisationnelleService;
    @Mock made.archive.repository.AttestationRepository attestationRepository;
    @Mock made.archive.service.organisation.PhysicalLocationService physicalLocationService;
    @Mock made.archive.service.organisation.DossierService dossierService;
    @Mock made.archive.repository.PlanClassementNoeudRepository planClassementNoeudRepository;
    @InjectMocks DocumentService service;

    private final UserDetails principal = org.springframework.security.core.userdetails.User
        .withUsername("ed@x.sn").password("x").authorities("ROLE_EDITOR").build();

    private User editeur;
    private UniteOrganisationnelle uo;
    private TypeDocument ancienType;
    private TypeDocument nouveauType;
    private Document doc;
    private DocumentService spy;

    private MetaData meta(String nom, boolean obligatoire)
    {
        MetaData m = new MetaData();
        m.setNom(nom);
        m.setObligatoire(obligatoire);
        return m;
    }

    private TypeDocument type(long id, String nom, Long annees, MetaData... metas)
    {
        Retention r = new Retention();
        r.setRetentionYears(annees);
        TypeDocument t = new TypeDocument();
        t.setId(id);
        t.setNom(nom);
        t.setRetention(r);
        t.setUniteOrganisationnelle(uo);
        t.setMetaData(List.of(metas));
        return t;
    }

    private DataTypeDto valeur(String nom, String v)
    {
        DataTypeDto d = new DataTypeDto();
        d.setNom(nom);
        d.setValeur(v);
        return d;
    }

    @BeforeEach
    void init()
    {
        uo = new UniteOrganisationnelle();
        uo.setId(1L);

        editeur = new User();
        editeur.setId(UUID.randomUUID());
        editeur.setEmail("ed@x.sn");
        Set<Role> roles = new HashSet<>();
        roles.add(new Role(1L, Role_Name.EDITOR));
        editeur.setRoles(roles);
        when(userRepository.findByEmail("ed@x.sn")).thenReturn(java.util.Optional.of(editeur));
        when(userRepository.findByUniteOrganisationnelleId(1L)).thenReturn(List.of(editeur));

        ancienType = type(10L, "Note", 5L, meta("Numéro", false), meta("Objet", false));
        nouveauType = type(20L, "Facture", 10L, meta("Numero", true), meta("Montant", true));

        doc = new Document();
        doc.setId(UUID.randomUUID());
        doc.setTitre("scan.pdf");
        doc.setUploadedBy(editeur);
        doc.setUniteOrganisationnelle(uo);
        doc.setTypeDocument(ancienType);
        doc.setAccess(TypeAccess.PUBLIC);
        doc.setStatus(DocumentStatus.ACTIVE);
        doc.setCreateAt(LocalDateTime.of(2024, 3, 10, 9, 0));
        doc.setRetentionUntil(LocalDate.of(2029, 3, 10));
        doc.setPdfaSha256("a".repeat(64));
        doc.setPkiSignature("sig");
        when(documentRepository.findById(doc.getId())).thenReturn(java.util.Optional.of(doc));
        when(typeDocumentService.getTypeDocumentById(20L)).thenReturn(nouveauType);

        DataType ancienne = new DataType();
        ancienne.setMetaData(ancienType.getMetaData().get(0));
        ancienne.setValeur("F-001");
        DataType objet = new DataType();
        objet.setMetaData(ancienType.getMetaData().get(1));
        objet.setValeur("Fournitures");
        when(dataTypeRepository.findByDocument_Id(doc.getId())).thenReturn(List.of(ancienne, objet));

        spy = spy(service);
        doReturn(null).when(spy).getDetail(any(), any());
    }

    private ReclassementRequestDto requete(DataTypeDto... valeurs)
    {
        ReclassementRequestDto r = new ReclassementRequestDto();
        r.setTypeDocumentId(20L);
        r.setMetaData(List.of(valeurs));
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void changerDeType_reprendLesValeursParNom_recalculeLEcheance_etGardeLHistoriqueDansLeJournal()
    {
        spy.reclasser(doc.getId(), requete(valeur("Numéro", "F-001"), valeur("Montant", "1500")), principal);

        // Type, échéance : 10 ans depuis la date D'ARCHIVAGE (pas depuis aujourd'hui)
        assertThat(doc.getTypeDocument()).isSameAs(nouveauType);
        assertThat(doc.getRetentionUntil()).isEqualTo(LocalDate.of(2034, 3, 10));
        // Le fichier et sa preuve ne bougent pas
        assertThat(doc.getPdfaSha256()).isEqualTo("a".repeat(64));
        assertThat(doc.getPkiSignature()).isEqualTo("sig");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.ACTIVE);

        verify(dataTypeRepository).deleteByDocumentId(doc.getId());
        ArgumentCaptor<List<DataType>> nouvelles = ArgumentCaptor.forClass(List.class);
        verify(dataTypeRepository).saveAll(nouvelles.capture());
        assertThat(nouvelles.getValue()).extracting(d -> d.getMetaData().getNom(), DataType::getValeur)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("Numero", "F-001"),   // "Numéro" → "Numero" (même champ, accent près)
                org.assertj.core.groups.Tuple.tuple("Montant", "1500"));

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq(editeur), eq(AuditAction.DOCUMENT_RECLASSE), any(), eq(doc.getId().toString()),
            eq(1L), anyString(), eq(true), details.capture());
        Map<String, Object> d = details.getValue();
        assertThat(d).containsEntry("typeAvant", "Note").containsEntry("typeApres", "Facture")
            .containsEntry("conservationApres", "2034-03-10");
        // « Objet » n'existe pas dans « Facture » : retiré de la fiche mais conservé dans le journal
        assertThat((Map<String, String>) d.get("valeursNonReprises")).containsEntry("Objet", "Fournitures");
        assertThat((Map<String, String>) d.get("valeursAvant")).containsEntry("Objet", "Fournitures");
        verify(meilisearchService).updateDocumentClassement(eq(doc), any());
    }

    @Test
    void unChampObligatoireDuNouveauTypeManquant_refuseTout_sansRienModifier()
    {
        assertThatThrownBy(() -> spy.reclasser(doc.getId(), requete(valeur("Numéro", "F-001")), principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Montant");

        assertThat(doc.getTypeDocument()).isSameAs(ancienType);
        assertThat(doc.getRetentionUntil()).isEqualTo(LocalDate.of(2029, 3, 10));
        verify(dataTypeRepository, never()).deleteByDocumentId(any());
        verify(meilisearchService, never()).updateDocumentClassement(any(), any());
    }

    @Test
    void echeanceDejaDepassee_leDocumentResteActif_aucuneSuppressionImmediate()
    {
        nouveauType.getRetention().setRetentionYears(1L); // 2025-03-10 : déjà passée

        spy.reclasser(doc.getId(), requete(valeur("Numéro", "F-001"), valeur("Montant", "1")), principal);

        assertThat(doc.getRetentionUntil()).isBefore(LocalDate.now());
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.ACTIVE); // la tâche planifiée l'enverra en corbeille avec son délai de grâce
    }

    @Test
    void unTypeIllimite_donneUneEcheanceVide()
    {
        nouveauType.getRetention().setRetentionYears(null);

        spy.reclasser(doc.getId(), requete(valeur("Numéro", "F-001"), valeur("Montant", "1")), principal);

        assertThat(doc.getRetentionUntil()).isNull();
    }

    @Test
    void leNouveauTypeDoitEtreDeLaMemeUo()
    {
        UniteOrganisationnelle autre = new UniteOrganisationnelle();
        autre.setId(2L);
        nouveauType.setUniteOrganisationnelle(autre);

        assertThatThrownBy(() -> spy.reclasser(doc.getId(), requete(valeur("Numero", "1"), valeur("Montant", "1")), principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("même unité");
        assertThat(doc.getTypeDocument()).isSameAs(ancienType);
    }

    @Test
    void unDocumentEnCorbeilleNePeutPasEtreReclasse()
    {
        doc.setStatus(DocumentStatus.CORBEILLE);

        assertThatThrownBy(() -> spy.reclasser(doc.getId(), requete(valeur("Numero", "1"), valeur("Montant", "1")), principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("corbeille");
    }

    @Test
    void unEmplacementQuiNAccepteQueLAncienType_bloqueLeReclassement_sauChangementDEmplacement()
    {
        PhysicalLocation boite = new PhysicalLocation();
        boite.setName("Boîte notes");
        boite.setModeContrainte(LocationModeContrainte.TYPE_UNIQUE);
        boite.setTypeDocumentAccepte(ancienType);
        doc.setPhysicalLocation(boite);

        assertThatThrownBy(() -> spy.reclasser(doc.getId(), requete(valeur("Numero", "1"), valeur("Montant", "1")), principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Boîte notes");
        assertThat(doc.getTypeDocument()).isSameAs(ancienType);
    }

    @Test
    void aucunChangementDemande_estRefuse()
    {
        ReclassementRequestDto vide = new ReclassementRequestDto();
        vide.setTypeDocumentId(ancienType.getId()); // même type, rien d'autre

        assertThatThrownBy(() -> spy.reclasser(doc.getId(), vide, principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Aucun changement");
    }

    @Test
    void seulUnEditeurAyantAccesPeutReclasser()
    {
        editeur.getRoles().clear();
        editeur.getRoles().add(new Role(2L, Role_Name.USER));

        assertThatThrownBy(() -> spy.reclasser(doc.getId(), requete(valeur("Numero", "1"), valeur("Montant", "1")), principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("éditeur");
        assertThat(doc.getTypeDocument()).isSameAs(ancienType);
    }

    private PlanClassementNoeud noeud(long id, String code, String libelle, UniteOrganisationnelle de)
    {
        PlanClassementNoeud n = new PlanClassementNoeud();
        n.setId(id);
        n.setCode(code);
        n.setLibelle(libelle);
        n.setUniteOrganisationnelle(de);
        return n;
    }

    @Test
    @SuppressWarnings("unchecked")
    void activiteSeuleChangee_enregistreUneExceptionPourCeDocument_etLaJournalise()
    {
        PlanClassementNoeud parDefaut = noeud(1L, "03.2", "Factures", uo);
        PlanClassementNoeud ponctuelle = noeud(2L, "03.4", "Achats ponctuels", uo);
        ancienType.setPlanClassementNoeud(parDefaut);
        when(planClassementNoeudRepository.findById(2L)).thenReturn(java.util.Optional.of(ponctuelle));

        ReclassementRequestDto r = new ReclassementRequestDto();
        r.setModifierActivite(true);
        r.setPlanClassementNoeudId(2L);
        spy.reclasser(doc.getId(), r, principal);

        assertThat(doc.getPlanClassementNoeud()).isSameAs(ponctuelle);          // exception propre au document
        assertThat(doc.getTypeDocument()).isSameAs(ancienType);                 // le type ne bouge pas
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq(editeur), eq(AuditAction.DOCUMENT_RECLASSE), any(), eq(doc.getId().toString()),
            eq(1L), anyString(), eq(true), details.capture());
        assertThat(details.getValue()).containsEntry("activiteAvant", "03.2 Factures")
            .containsEntry("activiteApres", "03.4 Achats ponctuels");
    }

    @Test
    void activiteIdentiqueAcelleDuType_neCreePasDException_leDocumentSuitSonType()
    {
        PlanClassementNoeud parDefaut = noeud(1L, "03.2", "Factures", uo);
        ancienType.setPlanClassementNoeud(parDefaut);
        doc.setPlanClassementNoeud(noeud(2L, "03.4", "Achats ponctuels", uo)); // exception existante
        when(planClassementNoeudRepository.findById(1L)).thenReturn(java.util.Optional.of(parDefaut));

        ReclassementRequestDto r = new ReclassementRequestDto();
        r.setModifierActivite(true);
        r.setPlanClassementNoeudId(1L);
        spy.reclasser(doc.getId(), r, principal);

        assertThat(doc.getPlanClassementNoeud()).isNull();
    }

    @Test
    void retirerLActivite_remetLeDocumentSurCelleDeSonType()
    {
        ancienType.setPlanClassementNoeud(noeud(1L, "03.2", "Factures", uo));
        doc.setPlanClassementNoeud(noeud(2L, "03.4", "Achats ponctuels", uo));

        ReclassementRequestDto r = new ReclassementRequestDto();
        r.setModifierActivite(true);
        r.setPlanClassementNoeudId(null);
        spy.reclasser(doc.getId(), r, principal);

        assertThat(doc.getPlanClassementNoeud()).isNull();
    }

    @Test
    void uneActiviteDUneAutreUo_estRefusee()
    {
        UniteOrganisationnelle autre = new UniteOrganisationnelle();
        autre.setId(2L);
        when(planClassementNoeudRepository.findById(9L)).thenReturn(java.util.Optional.of(noeud(9L, "01", "Ailleurs", autre)));

        ReclassementRequestDto r = new ReclassementRequestDto();
        r.setModifierActivite(true);
        r.setPlanClassementNoeudId(9L);

        assertThatThrownBy(() -> spy.reclasser(doc.getId(), r, principal))
            .isInstanceOf(BusinessException.class).hasMessageContaining("autre UO");
        assertThat(doc.getPlanClassementNoeud()).isNull();
    }

    @Test
    void changerDeType_remetLActiviteSurCelleDuNouveauType_sansException_heritee()
    {
        doc.setPlanClassementNoeud(noeud(2L, "03.4", "Achats ponctuels", uo)); // exception liée à l'ancien contexte
        nouveauType.setPlanClassementNoeud(noeud(5L, "03.2", "Factures", uo));

        spy.reclasser(doc.getId(), requete(valeur("Numéro", "1"), valeur("Montant", "1")), principal);

        assertThat(doc.getPlanClassementNoeud()).isNull(); // suit désormais le nouveau type
        assertThat(made.archive.service.organisation.PlanClassementService.activiteEffective(doc).getId()).isEqualTo(5L);
    }
}
