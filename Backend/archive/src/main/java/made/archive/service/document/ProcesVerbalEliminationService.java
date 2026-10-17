package made.archive.service.document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.GroupeAccess;
import made.archive.entite.DocumentStatus;
import made.archive.entite.IntegrityLevel;
import made.archive.entite.MetaData;
import made.archive.entite.MotifSuppression;
import made.archive.entite.Role_Name;
import made.archive.entite.Retention;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeAccess;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.exception.PdfAConversionException;
import made.archive.entite.User;
import made.archive.repository.DataTypeRepository;
import made.archive.repository.DocumentRepository;
import made.archive.repository.GroupeAccessRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UniteOrganisationnelleRepository;
import made.archive.repository.UserRepository;
import made.archive.security.DocumentEncryptionService;
import made.archive.service.audit.AuditLogService;
import made.archive.service.integrite.ManifestePreuveService;
import made.archive.service.integrite.PreuveIntegriteService;
import made.archive.service.organisation.PlanClassementService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import made.archive.util.DocumentVersionLabels;
import made.archive.service.storage.StorageService;
import made.archive.service.user.UtilisateurSystemeService;

/**
 * Procès-verbal d'élimination automatique : pour toute suppression définitive (automatique OU manuelle,
 * quel que soit le motif), un PV (PDF/A, horodaté RFC 3161) est archivé DANS MadeArchive, comme document
 * d'un type système « Procès-verbal d'élimination » en sort CONSERVER — donc jamais éliminable lui-même.
 *
 * Un PV PAR DOCUMENT éliminé (jamais un PV commun à plusieurs documents), portant son TITRE et les informations qui
 * permettent de l'identifier (identifiant, type, activité, version, dossier, emplacement, métadonnées, déposant,
 * dates, règle de conservation, empreinte SHA-256, motif et auteur) : sans cela, le PV dirait pourquoi et comment
 * on a supprimé, mais pas QUOI. Les titres et métadonnées pouvant être sensibles (ou contenir des données
 * personnelles), le PV est à ACCÈS RESTREINT : voir groupeDesResponsables (éditeurs et administrateurs d'UO de l'UO,
 * plus les administrateurs généraux).
 *
 * La purge d'abord, le PV ensuite : l'élimination n'est jamais retardée par une panne de génération (PDF/A
 * via Ghostscript, stockage...). Tant qu'un PV échoue, Document.procesVerbalId reste null et chaque nuit
 * le réessaie (voir DocumentRetentionCleanupScheduler) ; les données du PV viennent de la pierre tombale,
 * qui conserve tout ce qu'il faut. Chaque groupe est traité dans SA transaction : un échec n'empêche pas
 * les autres.
 *
 * Document déposé par le compte technique « Système MadeArchive » (voir UtilisateurSystemeService), donc
 * horodaté mais non signé (pas de clé PKI applicative : un PV signé par l'application elle-même ne
 * prouverait rien de plus que l'horodatage indépendant et le sceau du journal chaîné). Accès PRIVÉ : seuls les
 * éditeurs et les administrateurs d'UO de l'UO concernée (voir groupeDesResponsables) le voient et le trouvent
 * en recherche — y compris par le titre d'un document éliminé.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcesVerbalEliminationService
{
    public static final String NOM_TYPE = "Procès-verbal d'élimination";
    private static final String CHAMP_DATE = "Date d'élimination";
    private static final String CHAMP_NOMBRE = "Nombre de documents";
    private static final ZoneId FUSEAU = ZoneId.systemDefault();
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter HEURE_SEULE = DateTimeFormatter.ofPattern("HH:mm");

    private final DocumentRepository             documentRepository;
    private final TypeDocumentRepository         typeDocumentRepository;
    private final DataTypeRepository             dataTypeRepository;
    private final UniteOrganisationnelleRepository uoRepository;
    private final UserRepository                 userRepository;
    private final GroupeAccessRepository         groupeAccessRepository;
    private final UniteOrganisationnelleService  uniteOrganisationnelleService;
    private final UtilisateurSystemeService      utilisateurSystemeService;
    private final ProcesVerbalPdfService         pdfService;
    private final PdfAConversionService          pdfAConversionService;
    private final HashService                    hashService;
    private final DocumentEncryptionService      documentEncryptionService;
    private final StorageService                 storageService;
    private final MeilisearchService             meilisearchService;
    private final HorodatageService              horodatageService;
    private final AuditLogService                auditLogService;
    private final PlatformTransactionManager     transactionManager;
    private final PreuveIntegriteService         preuveIntegriteService;
    private final ManifestePreuveService         manifestePreuveService;

    /** Une élimination à documenter : la pierre tombale d'un seul document (un procès-verbal par document). */
    private record Groupe(Long uoId, LocalDate jour, List<UUID> documentIds) {}

    private record Genere(UUID procesVerbalId, Long uoId, String titre, List<UUID> documentIds, LocalDate jour) {}

    /** Un seul passage à la fois : la tâche de nuit et une suppression manuelle ne doivent jamais documenter deux fois les mêmes éliminations. */
    private final Object verrou = new Object();

    /**
     * Déclenchée juste après une suppression manuelle (voir DocumentRetentionService.supprimerDefinitivementManuellement) :
     * le PV est généré en arrière-plan, sans retarder la réponse à l'éditeur ni attendre la tâche de nuit, qui reste
     * le filet de sécurité si cette génération échoue.
     */
    @Async
    public void genererEnArrierePlan()
    {
        genererProcesVerbauxEnAttente();
    }

    /** Génère les PV de toutes les éliminations qui n'en ont pas encore. Ne lève jamais : tout échec est logué. */
    public void genererProcesVerbauxEnAttente()
    {
        synchronized (verrou)
        {
            genererSousVerrou();
        }
    }

    private void genererSousVerrou()
    {
        reconcilierAdministrateurs();

        List<Groupe> groupes;
        try
        {
            groupes = new TransactionTemplate(transactionManager).execute(status -> regrouper());
        }
        catch (Exception e)
        {
            log.error("[PV-Elimination] Impossible de lister les éliminations sans procès-verbal : {}", e.getMessage(), e);
            return;
        }
        if (groupes == null || groupes.isEmpty())
        {
            return;
        }

        log.info("[PV-Elimination] {} procès-verbal(aux) à générer", groupes.size());
        for (Groupe groupe : groupes)
        {
            try
            {
                Genere genere = new TransactionTemplate(transactionManager).execute(status -> genererPourGroupe(groupe));
                if (genere == null) continue;

                // Après le commit : horodatage (asynchrone, via le proxy) et journal (transactions indépendantes).
                horodatageService.horodaterApresUpload(genere.procesVerbalId());
                journaliser(genere);
            }
            catch (Exception e)
            {
                log.error("[PV-Elimination] Échec du procès-verbal de l'UO {} du {} ({} document(s)) — "
                    + "sera réessayé à la prochaine exécution : {}",
                    groupe.uoId(), groupe.jour(), groupe.documentIds().get(0), e.getMessage(), e);
            }
        }
    }

    /**
     * Les administrateurs généraux voient tous les procès-verbaux : le groupe d'accès d'un PV est un instantané, on y
     * ajoute donc les administrateurs actifs qui n'en font pas encore partie (procès-verbaux déjà établis, administrateurs
     * nommés depuis). Ne lève jamais : un échec est logué et sera repris au prochain passage.
     */
    private void reconcilierAdministrateurs()
    {
        try
        {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            {
                List<User> admins = administrateursActifs();
                if (admins.isEmpty()) return;
                for (Document pv : documentRepository.findByTypeDocumentSystemeTrueAndStatusNot(DocumentStatus.DELETED))
                {
                    GroupeAccess g = pv.getGroupe();
                    if (g == null || pv.getAccess() != TypeAccess.PRIVE) continue;
                    List<User> membres = g.getMembres() != null ? new ArrayList<>(g.getMembres()) : new ArrayList<>();
                    boolean modifie = false;
                    for (User a : admins)
                    {
                        if (membres.stream().noneMatch(m -> m.getId().equals(a.getId())))
                        {
                            membres.add(a);
                            modifie = true;
                        }
                    }
                    if (modifie)
                    {
                        g.setMembres(membres);
                        groupeAccessRepository.save(g);
                    }
                }
            });
        }
        catch (Exception e)
        {
            log.error("[PV-Elimination] Ajout des administrateurs aux procès-verbaux existants impossible : {}", e.getMessage(), e);
        }
    }

    private List<User> administrateursActifs()
    {
        return userRepository.findByRoleName(Role_Name.ADMIN).stream().filter(User::isActif).toList();
    }

    private List<Groupe> regrouper()
    {
        List<Groupe> res = new ArrayList<>();
        for (Document d : documentRepository.findByStatusAndElimineLeIsNotNullAndProcesVerbalIdIsNull(DocumentStatus.DELETED))
        {
            if (d.getUniteOrganisationnelle() == null) continue;
            res.add(new Groupe(d.getUniteOrganisationnelle().getId(), d.getElimineLe().atZone(FUSEAU).toLocalDate(),
                List.of(d.getId())));
        }
        return res;
    }

    private Genere genererPourGroupe(Groupe groupe)
    {
        UniteOrganisationnelle uo = uoRepository.findById(groupe.uoId()).orElseThrow();
        List<Document> eliminees = documentRepository.findAllById(groupe.documentIds()).stream()
            .filter(d -> d.getStatus() == DocumentStatus.DELETED && d.getProcesVerbalId() == null)
            .sorted(Comparator.comparing(Document::getElimineLe).thenComparing(Document::getId))
            .toList();
        if (eliminees.isEmpty())
        {
            return null;
        }

        LocalDateTime maintenant = LocalDateTime.now();
        List<ProcesVerbalPdfService.Ligne> lignes = new ArrayList<>();
        for (Document d : eliminees)
        {
            lignes.add(ligne(d));
        }

        byte[] pdfSource = pdfService.generer(new ProcesVerbalPdfService.Donnees(
            uo.getNom(), groupe.jour(), maintenant, lignes));
        PdfAConversionService.ResultatPdfA resultat;
        try
        {
            resultat = pdfAConversionService.convertirEtVerifier(pdfSource,
                "proces-verbal-elimination-" + groupe.jour() + ".pdf");
        }
        catch (PdfAConversionException e)
        {
            throw new IllegalStateException("Conversion PDF/A du procès-verbal impossible : " + e.getMessage(), e);
        }
        byte[] pdfA = resultat.pdfBytes();

        User systeme = utilisateurSystemeService.obtenir();
        TypeDocument type = obtenirTypeProcesVerbal(uo, systeme);

        String cle = "pdfa/Proces-verbal-elimination/" + groupe.jour() + "/" + UUID.randomUUID()
            + "/proces-verbal-elimination.pdf";
        byte[] pdfAChiffre = documentEncryptionService.encrypt(pdfA);
        String cleStockee = storageService.uploadBytes(pdfAChiffre, cle, "application/octet-stream");
        // Même règle que pour tout archivage : le fichier doit être arrivé intact AVANT que la moindre ligne soit
        // écrite en base. La clé vient d'être créée (UUID) : aucun document ne peut la référencer, on peut la retirer.
        try
        {
            storageService.verifierDepot(cleStockee, pdfAChiffre);
        }
        catch (RuntimeException e)
        {
            try
            {
                storageService.delete(cleStockee);
            }
            catch (RuntimeException suppression)
            {
                log.warn("[PV] Nettoyage de l'objet non vérifié {} impossible : {}", cleStockee, suppression.getMessage());
            }
            throw new IllegalStateException("Procès-verbal non vérifié dans le stockage, enregistrement annulé : " + e.getMessage(), e);
        }

        String titreDocument = eliminees.get(0).getTitre();
        if (titreDocument != null && titreDocument.length() > 150)
        {
            titreDocument = titreDocument.substring(0, 147) + "...";
        }
        String titre = NOM_TYPE + " — " + titreDocument + " (" + groupe.jour().format(JOUR) + " à "
            + eliminees.get(0).getElimineLe().atZone(FUSEAU).format(HEURE_SEULE) + ")";

        Document pv = new Document();
        pv.setTitre(titre);
        pv.setAccess(TypeAccess.PRIVE);
        pv.setGroupe(groupeDesResponsables(uo));
        pv.setOriginalSha256(hashService.calculateFromBytes(pdfSource));
        pv.setPdfaSha256(hashService.calculateFromBytes(pdfA));
        pv.setStorageKey(cleStockee);
        pv.setStatus(DocumentStatus.ACTIVE);
        pv.setIntegrityLevel(IntegrityLevel.STANDARD);
        pv.setUploadedBy(systeme);
        pv.setTypeDocument(type);
        pv.setUniteOrganisationnelle(uo);
        pv.setCreateAt(maintenant);
        pv.setVersion(1L);
        pv.setDerniereVersion(true);
        pv.setRetentionUntil(null);
        Document pvEnregistre = documentRepository.saveAndFlush(pv);

        // Un PV n'a pas d'éditeur : signé par la clé du système, comme le sont les enregistrements de rattrapage.
        preuveIntegriteService.scellerParLeSysteme(pvEnregistre);
        documentRepository.save(pvEnregistre);
        manifestePreuveService.ecrire(pvEnregistre);

        Map<String, MetaData> defs = new LinkedHashMap<>();
        type.getMetaData().forEach(m -> defs.put(m.getNom(), m));
        List<DataType> valeurs = new ArrayList<>();
        valeurs.add(dataType(pvEnregistre, defs.get(CHAMP_DATE), groupe.jour().format(JOUR)));
        valeurs.add(dataType(pvEnregistre, defs.get(CHAMP_NOMBRE), String.valueOf(eliminees.size())));
        dataTypeRepository.saveAll(valeurs);

        for (Document d : eliminees)
        {
            d.setProcesVerbalId(pvEnregistre.getId());
        }
        documentRepository.saveAll(eliminees);

        // Texte indexé : les titres et métadonnées des documents éliminés, pour retrouver un PV à partir du document
        // qu'il concerne (la recherche respecte l'accès restreint du PV).
        StringBuilder texte = new StringBuilder(titre).append('\n').append(uo.getNom()).append('\n');
        lignes.forEach(l -> texte.append(l.titre()).append(' ').append(l.identifiant()).append(' ').append(l.type())
            .append(' ').append(String.join(" ", l.metadonnees())).append(' ').append(l.motif()).append('\n'));
        meilisearchService.indexDocument(pvEnregistre, texte.toString(), valeurs);

        return new Genere(pvEnregistre.getId(), uo.getId(), titre,
            eliminees.stream().map(Document::getId).toList(), groupe.jour());
    }

    private ProcesVerbalPdfService.Ligne ligne(Document d)
    {
        TypeDocument type = d.getTypeDocument();
        Retention retention = type.getRetention();
        String conservation = retention != null && retention.getRetentionYears() != null
            ? "conservation " + retention.getRetentionYears() + " an(s)"
                + (d.getRetentionUntil() != null ? " (échéance " + d.getRetentionUntil().format(JOUR) + ")" : "")
            : "conservation illimitée";
        if (retention != null && retention.getSortFinal() != null)
        {
            conservation += ", sort final " + retention.getSortFinal();
        }

        String eliminePar = d.getEliminePar() == null
            ? "le système (suppression automatique)"
            : userRepository.findById(d.getEliminePar()).map(User::getEmail).orElse("l'utilisateur " + d.getEliminePar());

        User depose = d.getUploadedBy();
        String deposePar = depose == null ? null
            : ((depose.getPrenom() != null ? depose.getPrenom() + " " : "") + (depose.getNom() != null ? depose.getNom() : "")).trim()
                + " (" + depose.getEmail() + ")";

        List<String> metadonnees = new ArrayList<>();
        if (d.getData() != null)
        {
            for (DataType dt : d.getData())
            {
                if (dt.getValeur() == null || dt.getValeur().isBlank()) continue;
                String nom = dt.getMetaData() != null && dt.getMetaData().getNom() != null ? dt.getMetaData().getNom() : "(champ)";
                metadonnees.add(nom + " : " + dt.getValeur().trim());
            }
        }

        return new ProcesVerbalPdfService.Ligne(
            d.getId().toString(),
            d.getTitre(),
            type.getNom(),
            PlanClassementService.chemin(PlanClassementService.activiteEffective(d)),
            libelleVersion(DocumentVersionLabels.compute(d)),
            d.getDossier() != null ? d.getDossier().getNom() : null,
            d.getPhysicalLocation() != null ? d.getPhysicalLocation().getName() : null,
            deposePar,
            d.getCreateAt().toLocalDate(),
            conservation,
            metadonnees,
            d.getPdfaSha256(),
            libelleMotif(d),
            eliminePar,
            d.getElimineLe().atZone(FUSEAU).format(HEURE));
    }

    /**
     * Les personnes qui peuvent lire le PV d'une UO : ses éditeurs (ceux qui gèrent et suppriment les documents),
     * les administrateurs d'UO ayant autorité sur elle et les administrateurs généraux. Instantané au moment de la génération du PV : un éditeur
     * nommé plus tard ne verra pas les PV déjà établis. Le PV n'est pas public dans l'UO : il reproduit les titres
     * de documents qui pouvaient être privés.
     */
    private GroupeAccess groupeDesResponsables(UniteOrganisationnelle uo)
    {
        Map<UUID, User> membres = new LinkedHashMap<>();
        for (User u : userRepository.findByUniteOrganisationnelleId(uo.getId()))
        {
            boolean editeur = u.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR);
            if (editeur && u.isActif())
            {
                membres.put(u.getId(), u);
            }
        }
        for (User u : uniteOrganisationnelleService.getAdminUOAvecAutoriteSur(uo.getId()))
        {
            if (u.isActif())
            {
                membres.put(u.getId(), u);
            }
        }
        for (User u : administrateursActifs())
        {
            membres.put(u.getId(), u);
        }

        GroupeAccess groupe = new GroupeAccess();
        groupe.setCreateAt(LocalDate.now());
        groupe.setMembres(new ArrayList<>(membres.values()));
        return groupeAccessRepository.save(groupe);
    }

    /** « Version 2 » -> « 2 », « Final » -> « finale » (le PV affiche « Version : … »), null si document sans versions. */
    private static String libelleVersion(String libelle)
    {
        if (libelle == null) return null;
        return libelle.startsWith("Version ") ? libelle.substring("Version ".length()) : "finale";
    }

    private static String libelleMotif(Document d)
    {
        MotifSuppression m = d.getMotifSuppression();
        String base = m == null ? "non renseigné"
            : switch (m)
            {
                case ERREUR_ARCHIVAGE -> "erreur d'archivage";
                case SUPPRESSION_LEGALE -> "suppression légale";
                case AUTRE -> "autre";
                case FIN_DE_VIE -> "fin de vie du document";
            };
        String commentaire = d.getCommentaireSuppression();
        return commentaire != null && !commentaire.isBlank() ? base + " (" + commentaire.trim() + ")" : base;
    }

    private DataType dataType(Document doc, MetaData meta, String valeur)
    {
        DataType dt = new DataType();
        dt.setDocument(doc);
        dt.setMetaData(meta);
        dt.setValeur(valeur);
        return dt;
    }

    /** Le type système de CETTE UO — créé à la première élimination. */
    private TypeDocument obtenirTypeProcesVerbal(UniteOrganisationnelle uo, User systeme)
    {
        return typeDocumentRepository.findByUniteOrganisationnelleId(uo.getId()).stream()
            .filter(TypeDocument::isSysteme)
            .findFirst()
            .orElseGet(() ->
            {
                Retention retention = new Retention();
                retention.setRetentionYears(null);          // durée illimitée : jamais d'échéance
                retention.setPeriodGrace(null);
                retention.setSortFinal(SortFinal.CONSERVER); // jamais éliminable

                TypeDocument t = new TypeDocument();
                t.setNom(NOM_TYPE);
                t.setSysteme(true);
                t.setUser(systeme);
                t.setUniteOrganisationnelle(uo);
                t.setRetention(retention);

                List<MetaData> metas = new ArrayList<>();
                int ordreChamp = 0;
                for (String champ : List.of(CHAMP_DATE, CHAMP_NOMBRE))
                {
                    MetaData m = new MetaData();
                    m.setOrdre(ordreChamp++);
                    m.setNom(champ);
                    m.setObligatoire(false);
                    m.setTypeDocument(t);
                    metas.add(m);
                }
                t.setMetaData(metas);
                return typeDocumentRepository.save(t);
            });
    }

    private void journaliser(Genere g)
    {
        auditLogService.log(null, AuditAction.PV_ELIMINATION_GENERE, AuditCible.DOCUMENT,
            g.procesVerbalId().toString(), g.uoId(),
            titreSansDonnees(g) + " — généré et archivé dans MadeArchive", true,
            Map.of("documents", g.documentIds().size(), "jourElimination", g.jour().toString()));

        for (UUID id : g.documentIds())
        {
            auditLogService.log(null, AuditAction.DOCUMENT_INCLUS_PV_ELIMINATION, AuditCible.DOCUMENT,
                id.toString(), g.uoId(),
                "Élimination consignée dans le procès-verbal d'élimination " + g.procesVerbalId(), true,
                Map.of("procesVerbalId", g.procesVerbalId().toString()));
        }
    }

    private static String titreSansDonnees(Genere g)
    {
        return g.titre();
    }
}
