package made.archive.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import made.archive.entite.Document;
import made.archive.entite.DocumentStatus;
import made.archive.entite.MotifSuppression;
import made.archive.entite.NotificationType;
import made.archive.entite.Retention;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.notification.NotificationService;
import made.archive.service.storage.StorageService;

/**
 * Règles de fin de vie : un document de conservation permanente n'est jamais purgé par le système,
 * un document dont l'élimination est bloquée non plus, une suppression volontaire l'est à l'échéance,
 * et aucune purge n'a lieu hors corbeille.
 */
class DocumentRetentionServiceTest
{
    private DocumentRepository documentRepository;
    private StorageService storageService;
    private MeilisearchService meilisearchService;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private UserRepository userRepository;
    private DocumentRetentionService service;

    @BeforeEach
    void init()
    {
        documentRepository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        meilisearchService = mock(MeilisearchService.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        service = new DocumentRetentionService(documentRepository, storageService, meilisearchService,
            auditLogService, notificationService, userRepository);
    }

    private Document documentEnCorbeille(SortFinal sort, MotifSuppression motif, LocalDate echeance)
    {
        UniteOrganisationnelle uo = new UniteOrganisationnelle();
        uo.setId(1L);
        Retention retention = new Retention();
        retention.setSortFinal(sort);
        TypeDocument type = new TypeDocument();
        type.setId(5L);
        type.setNom("Facture");
        type.setRetention(retention);

        Document d = new Document();
        d.setId(UUID.randomUUID());
        d.setTitre("doc");
        d.setStorageKey("pdfa/" + d.getId() + ".pdf");
        d.setTypeDocument(type);
        d.setUniteOrganisationnelle(uo);
        d.setStatus(DocumentStatus.CORBEILLE);
        d.setStatutAvantCorbeille(DocumentStatus.ACTIVE);
        d.setMotifSuppression(motif);
        d.setSuppressionPrevueLe(echeance);
        return d;
    }

    private void echeancesAtteintes(Document... docs)
    {
        when(documentRepository.findByStatusAndSuppressionPrevueLeLessThanEqual(eq(DocumentStatus.CORBEILLE), any()))
            .thenReturn(List.of(docs));
    }

    private User utilisateur(String email)
    {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        return u;
    }

    // ── Qui est supprimé automatiquement ? ─────────────────────────────────

    @Test
    void estSupprimableAutomatiquement_suppressionVolontaireTouteSortFinal_finDeVieSeulementDetruire()
    {
        LocalDate hier = LocalDate.now().minusDays(1);
        for (SortFinal sort : SortFinal.values())
        {
            assertThat(DocumentRetentionService.estSupprimableAutomatiquement(
                documentEnCorbeille(sort, MotifSuppression.ERREUR_ARCHIVAGE, hier)))
                .as("suppression volontaire + %s", sort).isTrue();
        }
        assertThat(DocumentRetentionService.estSupprimableAutomatiquement(
            documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, hier))).isTrue();
        assertThat(DocumentRetentionService.estSupprimableAutomatiquement(
            documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, hier))).isFalse();
        assertThat(DocumentRetentionService.estSupprimableAutomatiquement(
            documentEnCorbeille(SortFinal.TRIER, MotifSuppression.FIN_DE_VIE, hier))).isFalse();

        Document actif = documentEnCorbeille(SortFinal.DETRUIRE, null, hier);
        actif.setStatus(DocumentStatus.ACTIVE);
        assertThat(DocumentRetentionService.estSupprimableAutomatiquement(actif)).isFalse();
    }

    // ── La tâche planifiée ─────────────────────────────────────────────────

    @Test
    void unDocumentCONSERVERArriveEnFinDeVie_neEstJamaisPurgeParLeSysteme()
    {
        Document d = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, LocalDate.now().minusDays(400));
        echeancesAtteintes(d);

        service.purgeDocumentsCorbeille();

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.CORBEILLE);
        verify(storageService, never()).delete(anyString());
        verify(meilisearchService, never()).deleteDocument(anyString());
    }

    @Test
    void unDocumentDetruireEnFinDeVie_estPurgeApresLeDelai_avecMotifFinDeVie()
    {
        Document d = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, LocalDate.now());
        echeancesAtteintes(d);

        service.purgeDocumentsCorbeille();

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.DELETED);
        assertThat(d.getMotifSuppression()).isEqualTo(MotifSuppression.FIN_DE_VIE); // conservé dans la pierre tombale
        verify(storageService).delete(d.getStorageKey());
        verify(meilisearchService).deleteDocument(d.getId().toString());
    }

    @Test
    void uneSuppressionVolontaireDUnTypeCONSERVER_estPurgeeALEcheance()
    {
        Document d = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.ERREUR_ARCHIVAGE, LocalDate.now());
        echeancesAtteintes(d);

        service.purgeDocumentsCorbeille();

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.DELETED);
    }

    @Test
    void unDocumentDontLEliminationEstBloquee_neEstJamaisPurge_memeApresLEcheance()
    {
        Document d = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, LocalDate.now().minusDays(30));
        d.setEliminationBloquee(true);
        echeancesAtteintes(d);

        service.purgeDocumentsCorbeille();

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.CORBEILLE);
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void laTachePlanifieeNePurgeRienQuandAucuneEcheanceNEstAtteinte()
    {
        echeancesAtteintes(); // la requête ne renvoie que les échéances <= aujourd'hui : rien avant le délai complet

        service.purgeDocumentsCorbeille();

        verify(storageService, never()).delete(anyString());
    }

    // ── Suppression définitive manuelle ────────────────────────────────────

    @Test
    void suppressionManuelle_possibleImmediatementPourUneFinDeVieConservationPermanente_avecMotif()
    {
        Document d = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, LocalDate.now().plusDays(5));

        service.supprimerDefinitivementManuellement(d, utilisateur("ed@x.sn"), MotifSuppression.SUPPRESSION_LEGALE, null);

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.DELETED);
        assertThat(d.getMotifSuppression()).isEqualTo(MotifSuppression.SUPPRESSION_LEGALE);
    }

    @Test
    void suppressionManuelle_motifAutreExigeUnCommentaire_etFinDeVieEstInterditAUnUtilisateur()
    {
        Document d = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, LocalDate.now());
        User ed = utilisateur("ed@x.sn");

        assertThatThrownBy(() -> service.supprimerDefinitivementManuellement(d, ed, MotifSuppression.AUTRE, "  "))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.supprimerDefinitivementManuellement(d, ed, MotifSuppression.FIN_DE_VIE, "x"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.supprimerDefinitivementManuellement(d, ed, null, "x"))
            .isInstanceOf(BusinessException.class);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.CORBEILLE);
    }

    @Test
    void suppressionManuelle_refuseePourUnDocumentQueLeSystemeSupprimeraSeul_ouBloque()
    {
        User ed = utilisateur("ed@x.sn");
        Document auto = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, LocalDate.now().plusDays(4));
        assertThatThrownBy(() -> service.supprimerDefinitivementManuellement(auto, ed, MotifSuppression.AUTRE, "raison"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("automatiquement");

        Document bloque = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, LocalDate.now());
        bloque.setEliminationBloquee(true);
        assertThatThrownBy(() -> service.supprimerDefinitivementManuellement(bloque, ed, MotifSuppression.AUTRE, "raison"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("bloquée");

        assertThat(auto.getStatus()).isEqualTo(DocumentStatus.CORBEILLE);
        assertThat(bloque.getStatus()).isEqualTo(DocumentStatus.CORBEILLE);
    }

    @Test
    void laPurgeRefuseUnDocumentHorsCorbeille_gardeUnique()
    {
        Document actif = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.ERREUR_ARCHIVAGE, LocalDate.now().minusDays(1));
        actif.setStatus(DocumentStatus.ACTIVE);
        echeancesAtteintes(actif); // même si un appelant se trompait et le présentait comme candidat

        service.purgeDocumentsCorbeille();

        assertThat(actif.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        verify(storageService, never()).delete(anyString());
    }

    // ── Blocage / déblocage ────────────────────────────────────────────────

    @Test
    void blocage_exigeUnMotif_etDeblocageRedonneUnDelaiDeGraceComplet()
    {
        Document d = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, LocalDate.now().plusDays(1));
        User ed = utilisateur("ed@x.sn");

        assertThatThrownBy(() -> service.bloquerElimination(d, ed, " ")).isInstanceOf(BusinessException.class);

        service.bloquerElimination(d, ed, "Litige en cours");
        assertThat(d.isEliminationBloquee()).isTrue();
        assertThat(d.getBlocageMotif()).isEqualTo("Litige en cours");
        assertThat(d.getBlocagePar()).isEqualTo(ed.getId());
        assertThatThrownBy(() -> service.bloquerElimination(d, ed, "encore")).isInstanceOf(BusinessException.class);

        service.debloquerElimination(d, ed, "Litige clos");
        assertThat(d.isEliminationBloquee()).isFalse();
        assertThat(d.getSuppressionPrevueLe()).isEqualTo(LocalDate.now().plusDays(DocumentService.DELAI_GRACE_CORBEILLE_JOURS));
    }

    @Test
    void onNeBloquePasUnDocumentDeConservationPermanente_ilNyAAriendABloquer()
    {
        Document d = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, LocalDate.now());
        assertThatThrownBy(() -> service.bloquerElimination(d, utilisateur("ed@x.sn"), "motif"))
            .isInstanceOf(BusinessException.class);
    }

    // ── Alertes quotidiennes ───────────────────────────────────────────────

    @Test
    void alertes_regroupeesParUoTypeEtDate_envoyeesUneSeuleFoisParJour_ignorantBloquesEtConservationPermanente()
    {
        LocalDate dans2 = LocalDate.now().plusDays(2);
        Document a = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, dans2);
        Document b = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.ERREUR_ARCHIVAGE, dans2);
        Document bloque = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, dans2);
        bloque.setEliminationBloquee(true);
        Document permanent = documentEnCorbeille(SortFinal.CONSERVER, MotifSuppression.FIN_DE_VIE, dans2);
        Document dejaAlerte = documentEnCorbeille(SortFinal.DETRUIRE, MotifSuppression.FIN_DE_VIE, dans2);
        dejaAlerte.setAlerteSuppressionLe(LocalDate.now());
        for (Document d : List.of(a, b, bloque, permanent, dejaAlerte))
        {
            d.getTypeDocument().setId(5L); // même type, même UO, même date
        }
        when(documentRepository.findByStatusAndSuppressionPrevueLeBetween(eq(DocumentStatus.CORBEILLE), any(), any()))
            .thenReturn(List.of(a, b, bloque, permanent, dejaAlerte));

        User editeur = utilisateur("ed@x.sn");
        Role roleEditeur = new Role(1L, Role_Name.EDITOR);
        Set<Role> roles = new HashSet<>();
        roles.add(roleEditeur);
        editeur.setRoles(roles);
        User simpleUser = utilisateur("u@x.sn");
        Set<Role> rolesUser = new HashSet<>();
        rolesUser.add(new Role(2L, Role_Name.USER));
        simpleUser.setRoles(rolesUser);
        when(userRepository.findByUniteOrganisationnelleId(1L)).thenReturn(List.of(editeur, simpleUser));

        service.alerterSuppressionsImminentes();

        // UN seul message, pour les 2 documents concernés, aux éditeurs seulement (pas au simple user)
        verify(notificationService).notifier(eq(List.of(editeur)), eq(NotificationType.DOCUMENT_SUPPRESSION_IMMINENTE),
            org.mockito.ArgumentMatchers.contains("2 documents de type « Facture »"));
        assertThat(a.getAlerteSuppressionLe()).isEqualTo(LocalDate.now());
        assertThat(b.getAlerteSuppressionLe()).isEqualTo(LocalDate.now());
        assertThat(bloque.getAlerteSuppressionLe()).isNull();
        assertThat(permanent.getAlerteSuppressionLe()).isNull();
    }
}
