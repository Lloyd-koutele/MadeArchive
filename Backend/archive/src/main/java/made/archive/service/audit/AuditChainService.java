package made.archive.service.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.ChaineAuditRuptureDto;
import made.archive.dto.ChaineAuditVerificationDto;
import made.archive.entite.AuditChainConfig;
import made.archive.entite.AuditChainSeal;
import made.archive.entite.JournalAudit;
import made.archive.exception.CleChaineAuditException;
import made.archive.repository.AuditChainConfigRepository;
import made.archive.repository.AuditChainSealRepository;
import made.archive.repository.JournalAuditRepository;
import made.archive.security.HsmKeyStoreService;
import made.archive.security.HsmKeyStoreService.CalculHmac;
import made.archive.service.document.HashService;
import made.archive.service.document.HorodatageService;
import made.archive.service.integrite.HorodatageVerificationService;
import made.archive.service.integrite.PreuveIntegriteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chaînage et scellement du journal d'audit — chaque entrée reçoit l'empreinte
 * d'elle-même concaténée à l'empreinte de l'entrée précédente (par position
 * croissante), rendant toute modification ou suppression détectable.
 *
 * L'empreinte d'un maillon est un HMAC-SHA256 dont la CLÉ reste dans le HSM (alias {@link #ALIAS_CLE_CHAINE}) :
 * l'algorithme est public mais, sans la clé, personne — pas même qui peut écrire dans PostgreSQL — ne peut recalculer
 * la chaîne après avoir modifié une entrée. Chaque maillon dépendant de TOUS les précédents, le premier maillon
 * HMAC verrouille aussi l'historique antérieur, chaîné en SHA-256 simple avant cette évolution (la position de
 * bascule est figée dans audit_chain_config, voir la migration V13).
 *
 * Chaque scellement (empreinte du bout de chaîne + jeton RFC 3161) est de plus SIGNÉ par la clé système du HSM et
 * porte l'empreinte du scellement précédent : en fabriquer ou en retirer un au milieu se voit. Les scellements sont
 * enfin inscrits dans un registre hors base (RegistreScellementsService), pour que la DISPARITION des derniers soit
 * elle aussi détectée.
 *
 * Deux opérations distinctes, deux cadences (voir config.AuditChainScheduler) :
 *   - CHAÎNAGE (calculerChainage) : quelques microsecondes par entrée, purement local, lancé toutes
 *     les quelques secondes — le journal est protégé quasiment en temps réel.
 *   - SCELLEMENT (scellerSiNecessaire) : un appel réseau à la TSA (jusqu'à plusieurs secondes),
 *     toutes les 15 minutes, et seulement s'il y a eu de l'activité depuis le dernier scellement
 *     réussi. Un seul jeton couvre toute la chaîne jusqu'au point scellé : le nombre d'appels TSA
 *     ne dépend pas du volume d'activité. Jamais dans une transaction, jamais sur une requête
 *     utilisateur.
 *
 * Calcul VOLONTAIREMENT DIFFÉRÉ (job planifié — pas AuditLogService.log() au moment de
 * l'écriture) : log() est appelé en
 * parallèle depuis des dizaines de services métier, chacun dans sa PROPRE
 * transaction REQUIRES_NEW (voir sa Javadoc). Si le chaînage était calculé à
 * l'écriture, deux écritures concurrentes liraient la même "dernière empreinte",
 * produiraient deux entrées prétendant toutes deux descendre du même ancêtre, et
 * une vérification ultérieure détecterait des "ruptures" qui n'en sont pas —
 * exactement l'inverse de l'effet recherché. Un job planifié, par construction
 * séquentiel, élimine ce risque sans ralentir aucune écriture.
 *
 * L'ORDRE de la chaîne est JournalAudit.positionChaine, attribuée ici au moment du chaînage — pas
 * l'id : un id est attribué à l'insertion, pas au commit, donc une transaction lente peut rendre
 * visible l'entrée 100 après que la 101 a déjà été chaînée. Elle est alors simplement chaînée au
 * passage suivant, à la position suivante, et la vérification (qui suit positionChaine) reste juste.
 * Un verrou PostgreSQL (verrouillerChainage) empêche deux instances de chaîner en même temps.
 *
 * Pas de chaînage rétroactif de l'historique antérieur à la mise en place de ce
 * mécanisme : les entrées déjà en base avant son premier passage ne seront
 * JAMAIS chaînées (chainHash restera null pour elles indéfiniment). Un chaînage
 * rétroactif donnerait une fausse impression d'avoir protégé un historique qui,
 * en réalité, ne l'était pas au moment où il a été écrit — un attaquant ayant
 * déjà altéré une vieille ligne avant ce déploiement verrait sa falsification
 * légitimée par un backfill. La chaîne démarre honnêtement au premier passage
 * de calculerChainage() après ce déploiement.
 *
 * Limite assumée de verifierChaine : détecte toute incohérence dans la chaîne
 * VIVANTE (altération isolée, ou même une altération suivie d'un ré-enchaînement
 * complet de la suite, puisque celui-ci diverge alors du dernier scellement
 * horodaté externe déjà enregistré — voir la comparaison avec AuditChainSeal).
 * Ne va PAS jusqu'à revalider cryptographiquement chaque jeton RFC 3161 stocké
 * contre le certificat public du TSA (ce que AuditChainSeal.horodatageToken
 * permettrait en théorie) — un attaquant contrôlant entièrement la base pourrait
 * en théorie supprimer/falsifier aussi les lignes audit_chain_seals elles-mêmes.
 * Amélioration possible plus tard si un niveau de preuve supérieur est requis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainService
{
    private final JournalAuditRepository    journalAuditRepository;
    private final AuditChainSealRepository  auditChainSealRepository;
    private final HashService               hashService;
    private final HorodatageService         horodatageService;
    private final HorodatageVerificationService jetons;
    private final HsmKeyStoreService            hsm;
    private final AuditChainConfigRepository    configRepository;
    private final RegistreScellementsService    registre;
    private final PlatformTransactionManager    transactionManager;

    private TransactionTemplate tx;

    @PostConstruct
    void init()
    {
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Alias, dans le HSM fichier, de la clé secrète HMAC qui authentifie les maillons de la chaîne. */
    public static final String ALIAS_CLE_CHAINE = "chaine-audit-hmac";

    private static final String PREFIXE_HMAC       = "MADEARCHIVE-CHAINE-AUDIT-V2";
    private static final String LIBELLE_CONTROLE   = "MADEARCHIVE-CHAINE-AUDIT-CONTROLE-CLE-V1";
    private static final String PREFIXE_SCELLEMENT = "MADEARCHIVE-SCELLEMENT-AUDIT-V1";

    /** Valeur de départ de la chaîne — jamais une vraie empreinte d'entrée,
     *  juste une constante fixe connue de tous pour amorcer le calcul. */
    private static final String GENESE = "GENESE-CHAINE-AUDIT-MADEARCHIVE";

    private static final char SEP = '\u001F'; // séparateur ASCII "unit separator", jamais présent dans un texte normal

    /** Clé du verrou PostgreSQL du chaînage (pg_try_advisory_xact_lock) — valeur arbitraire mais fixe,
     *  propre à ce mécanisme ("CHAIN" en ASCII). */
    private static final long CLE_VERROU_CHAINAGE = 0x434841494EL;

    /** Verrou de la création d'un scellement ("SEAL" en ASCII) — garde le lien entre scellements cohérent même si deux
     *  instances scellent en même temps. */
    private static final long CLE_VERROU_SCELLEMENT = 0x5345414CL;

    /**
     * Chaîne toutes les entrées pas encore chaînées, en leur attribuant les positions suivantes de la
     * chaîne. Purement local (aucun appel réseau) et court : appelé toutes les quelques secondes
     * (voir AuditChainScheduler). Idempotent, sans effet si rien de nouveau ; sauté si une autre
     * instance de l'application est déjà en train de chaîner.
     *
     * La clé HMAC doit être disponible ET être celle de la chaîne : sinon rien n'est chaîné (jamais de repli sur un
     * SHA-256 simple, qui serait un retour en arrière exploitable) et une CleChaineAuditException est levée — les
     * entrées restent en attente et seront chaînées dès que la clé est rétablie.
     *
     * @return le nombre d'entrées chaînées par ce passage
     */
    @Transactional
    public int calculerChainage()
    {
        if (!journalAuditRepository.verrouillerChainage(CLE_VERROU_CHAINAGE))
        {
            log.debug("[Chaine-Audit] Chaînage déjà en cours sur une autre instance — passage sauté");
            return 0;
        }

        JournalAudit bout = journalAuditRepository.findTopByPositionChaineIsNotNullOrderByPositionChaineDesc();
        AuditChainConfig config = configRepository.findById(AuditChainConfig.ID_UNIQUE)
            .orElseGet(() -> initialiserConfiguration(bout));

        List<JournalAudit> aChainer = journalAuditRepository.findByChainHashIsNullOrderByIdAsc();
        if (aChainer.isEmpty())
        {
            return 0;
        }

        String hashPrecedent = bout != null ? bout.getChainHash() : sha256(GENESE);
        long position = bout != null ? bout.getPositionChaine() : 0L;

        long positionDepart = position;
        String premierHash = hashPrecedent;
        String dernierHash = hsm.avecHmac(config.getCleAlias(), calcul ->
        {
            exigerBonneCle(config, calcul);
            String precedent = premierHash;
            long courante = positionDepart;
            for (JournalAudit entree : aChainer)
            {
                String hash = maillon(++courante, config.getHmacDepuisPosition(), calcul, precedent,
                    serialiserCanonique(entree));
                entree.setChainHash(hash);
                entree.setPositionChaine(courante);
                precedent = hash;
            }
            return precedent;
        });
        journalAuditRepository.saveAll(aChainer);

        log.debug("[Chaine-Audit] {} entrée(s) chaînée(s) (positions jusqu'à {}), dernière empreinte {}",
            aChainer.size(), positionDepart + aChainer.size(), dernierHash);
        return aChainer.size();
    }

    /**
     * Première utilisation du chaînage HMAC : dépose la clé dans le HSM (si absente), fige la position de bascule
     * (la prochaine à chaîner) et le premier scellement qui devra être signé. Écrit une seule fois — un déclencheur en
     * base interdit toute modification. Appelée sous le verrou du chaînage.
     */
    private AuditChainConfig initialiserConfiguration(JournalAudit bout)
    {
        try
        {
            hsm.garantirCleSecrete(ALIAS_CLE_CHAINE);
            String empreinte = hsm.avecHmac(ALIAS_CLE_CHAINE, calcul -> calcul.hex(LIBELLE_CONTROLE));

            AuditChainSeal dernierScellement = auditChainSealRepository.findTopByOrderByIdDesc();
            AuditChainConfig config = new AuditChainConfig(AuditChainConfig.ID_UNIQUE,
                (bout != null ? bout.getPositionChaine() : 0L) + 1, ALIAS_CLE_CHAINE, empreinte,
                (dernierScellement != null ? dernierScellement.getId() : 0L) + 1, Instant.now());
            log.info("[Chaine-Audit] Chaînage HMAC activé à partir de la position {} (scellements signés à partir du n°{})",
                config.getHmacDepuisPosition(), config.getScellementsSignesDepuisId());
            return configRepository.save(config);
        }
        catch (CleChaineAuditException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new CleChaineAuditException("Clé de chaînage du journal d'audit indisponible dans le HSM : "
                + e.getMessage());
        }
    }

    /** La clé disponible est-elle bien celle qui a servi à chaîner ? Sinon on s'arrête avant d'écrire quoi que ce soit. */
    private void exigerBonneCle(AuditChainConfig config, CalculHmac calcul)
    {
        if (!calcul.hex(LIBELLE_CONTROLE).equals(config.getCleEmpreinte()))
        {
            throw new CleChaineAuditException("La clé de chaînage du journal d'audit n'est plus celle utilisée jusqu'ici "
                + "(alias '" + config.getCleAlias() + "') : chaînage suspendu, rétablir la clé d'origine du HSM.");
        }
    }

    /** Maillon n : HMAC (clé HSM) à partir de la position de bascule, SHA-256 simple avant (historique antérieur). */
    private String maillon(long position, long debutHmac, CalculHmac calcul, String hashPrecedent, String canonique)
    {
        return position >= debutHmac
            ? calcul.hex(PREFIXE_HMAC + SEP + hashPrecedent + SEP + canonique)
            : sha256(hashPrecedent + SEP + canonique);
    }

    /**
     * Scelle le bout actuel de la chaîne par un horodatage RFC 3161 — SEULEMENT s'il a avancé depuis
     * le dernier scellement RÉUSSI (aucune activité = aucun appel à la TSA). Volontairement HORS
     * transaction : l'appel TSA peut durer plusieurs secondes et ne doit jamais garder une connexion
     * à la base occupée.
     *
     * Le scellement est signé par la clé système du HSM, rattaché au scellement précédent, puis inscrit au registre
     * hors base. La configuration de la chaîne doit exister (créée par le premier chaînage) : sinon rien n'est scellé.
     *
     * En cas d'échec de la TSA, rien n'est enregistré : le passage suivant (15 minutes plus tard)
     * réessaie automatiquement, et HorodatageService prévient déjà les administrateurs d'une TSA
     * indisponible (avec un délai entre deux alertes).
     *
     * @return true si un nouveau scellement a été enregistré
     */
    public boolean scellerSiNecessaire()
    {
        if (configRepository.findById(AuditChainConfig.ID_UNIQUE).isEmpty())
        {
            log.debug("[Chaine-Audit] Configuration de la chaîne pas encore créée — scellement reporté");
            return false;
        }

        JournalAudit bout = journalAuditRepository.findTopByPositionChaineIsNotNullOrderByPositionChaineDesc();
        if (bout == null)
        {
            return false;
        }

        AuditChainSeal dernierReussi = auditChainSealRepository.findTopByHorodatageTokenIsNotNullOrderByIdDesc();
        if (dernierReussi != null && bout.getId().equals(dernierReussi.getDernierEntryId()))
        {
            log.debug("[Chaine-Audit] Aucune activité depuis le dernier scellement — pas d'appel à la TSA");
            return false;
        }

        HorodatageService.HorodatageResult resultat = horodatageService.horodater(bout.getChainHash());
        if (resultat == null)
        {
            log.warn("[Chaine-Audit] Scellement horodaté échoué — le chaînage continue normalement, "
                + "nouvelle tentative au prochain passage (jusqu'à la position {} à sceller)", bout.getPositionChaine());
            return false;
        }

        AuditChainSeal seal = tx.execute(statut ->
        {
            if (!journalAuditRepository.verrouillerChainage(CLE_VERROU_SCELLEMENT))
            {
                return null;
            }
            AuditChainSeal precedent = auditChainSealRepository.findTopByOrderByIdDesc();

            AuditChainSeal nouveau = new AuditChainSeal();
            nouveau.setDernierEntryId(bout.getId());
            nouveau.setDernierPositionChaine(bout.getPositionChaine());
            nouveau.setDernierChainHash(bout.getChainHash());
            nouveau.setHorodatageToken(resultat.token());
            nouveau.setHorodatageDate(resultat.date());
            nouveau.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            nouveau.setEmpreintePrecedente(precedent != null ? empreinteScellement(precedent) : null);
            nouveau.setSignatureAlias(PreuveIntegriteService.ALIAS_SYSTEME);
            nouveau.setSignature(hsm.sign(PreuveIntegriteService.ALIAS_SYSTEME, empreinteScellement(nouveau)));
            return auditChainSealRepository.saveAndFlush(nouveau);
        });
        if (seal == null)
        {
            log.debug("[Chaine-Audit] Un scellement est déjà en cours sur une autre instance — passage sauté");
            return false;
        }

        registre.ajouter(seal);

        log.info("[Chaine-Audit] Chaîne scellée jusqu'à la position {} (entrée {}), horodatage TSA {}",
            bout.getPositionChaine(), bout.getId(), resultat.date());
        return true;
    }

    /**
     * Empreinte d'un scellement : ses valeurs, dans un ordre FIXE. C'est ce que signe la clé système, et ce que le
     * scellement suivant recopie. Ni l'id (inconnu avant l'insertion) ni le jeton (vérifié à part) n'en font partie.
     */
    String empreinteScellement(AuditChainSeal s)
    {
        String payload = String.join("|", PREFIXE_SCELLEMENT,
            String.valueOf(s.getCreatedAt().getEpochSecond()),
            String.valueOf(s.getDernierEntryId()),
            s.getDernierPositionChaine() != null ? s.getDernierPositionChaine().toString() : "-",
            String.valueOf(s.getDernierChainHash()),
            s.getHorodatageDate() != null ? String.valueOf(s.getHorodatageDate().getEpochSecond()) : "-",
            s.getEmpreintePrecedente() != null ? s.getEmpreintePrecedente() : "-");
        return sha256(payload);
    }

    /**
     * Recalcule la chaîne entière et compare à ce qui est stocké. uoIdsAutorisees
     * (même convention que AuditLogService.rechercher) restreint les RUPTURES
     * renvoyées en détail à l'appelant (ADMIN_UO), jamais le calcul lui-même : la
     * chaîne traverse toutes les UO, une vérification partielle n'aurait pas de
     * sens cryptographique — voir rupturesHorsPerimetre pour signaler sans
     * détail qu'autre chose est cassé ailleurs.
     *
     * Contrôle aussi les scellements (signature du système, lien avec le précédent, entrée scellée toujours en place,
     * jeton RFC 3161) et les confronte au registre hors base : un scellement disparu est signalé. Si la clé HMAC est
     * absente ou différente, les maillons HMAC ne sont PAS jugés (état indéterminé, pas falsification) et une
     * anomalie globale le dit.
     */
    @Transactional(readOnly = true)
    public ChaineAuditVerificationDto verifierChaine(Set<Long> uoIdsAutorisees)
    {
        List<JournalAudit> chaine = journalAuditRepository.findByPositionChaineIsNotNullOrderByPositionChaineAsc();
        AuditChainConfig config = configRepository.findById(AuditChainConfig.ID_UNIQUE).orElse(null);

        List<ChaineAuditRuptureDto> ruptures = new ArrayList<>();
        List<String> anomaliesGlobales = new ArrayList<>();
        Map<Long, String> hashLiveParEntryId = new HashMap<>();
        Map<Long, Long> positionLiveParEntryId = new HashMap<>();
        long debutHmac = config != null ? config.getHmacDepuisPosition() : Long.MAX_VALUE;

        if (config == null)
        {
            verifierMaillons(chaine, debutHmac, null, ruptures, hashLiveParEntryId, positionLiveParEntryId);
        }
        else
        {
            boolean[] fait = { false };
            try
            {
                hsm.avecHmac(config.getCleAlias(), calcul ->
                {
                    fait[0] = true;
                    if (!calcul.hex(LIBELLE_CONTROLE).equals(config.getCleEmpreinte()))
                    {
                        anomaliesGlobales.add("La clé de chaînage du journal d'audit n'est plus celle utilisée jusqu'ici : "
                            + "les maillons HMAC n'ont pas pu être vérifiés (rétablir la clé d'origine du HSM).");
                        verifierMaillons(chaine, debutHmac, null, ruptures, hashLiveParEntryId, positionLiveParEntryId);
                    }
                    else
                    {
                        verifierMaillons(chaine, debutHmac, calcul, ruptures, hashLiveParEntryId, positionLiveParEntryId);
                    }
                    return null;
                });
            }
            catch (made.archive.exception.BusinessException e)
            {
                if (fait[0])
                {
                    throw e;
                }
                anomaliesGlobales.add("La clé de chaînage du journal d'audit est introuvable dans le HSM : les maillons "
                    + "HMAC n'ont pas pu être vérifiés (" + e.getMessage() + ").");
                verifierMaillons(chaine, debutHmac, null, ruptures, hashLiveParEntryId, positionLiveParEntryId);
            }
        }

        List<AuditChainSeal> scellements = auditChainSealRepository.findAllByOrderByIdAsc();
        verifierScellements(scellements, config, chaine, hashLiveParEntryId, positionLiveParEntryId, ruptures,
            anomaliesGlobales);
        verifierRegistre(scellements, anomaliesGlobales);

        List<ChaineAuditRuptureDto> ruptureVisibles = uoIdsAutorisees == null
            ? ruptures
            : ruptures.stream()
                .filter(r -> r.getUoId() == null || uoIdsAutorisees.contains(r.getUoId()))
                .collect(Collectors.toList());

        AuditChainSeal dernierScellement = auditChainSealRepository.findTopByHorodatageTokenIsNotNullOrderByIdDesc();

        return ChaineAuditVerificationDto.builder()
            .chaineIntacte(ruptures.isEmpty() && anomaliesGlobales.isEmpty())
            .nombreEntreesChainees(chaine.size())
            .ruptures(ruptureVisibles)
            // Les anomalies globales (clé, registre) ne se rattachent à aucune UO : détaillées aux seuls ADMIN globaux.
            .anomaliesGlobales(uoIdsAutorisees == null ? anomaliesGlobales : List.of())
            .rupturesHorsPerimetre(uoIdsAutorisees != null
                && (ruptureVisibles.size() < ruptures.size() || !anomaliesGlobales.isEmpty()))
            .dernierScellementDate(dernierScellement != null ? dernierScellement.getHorodatageDate() : null)
            .dernierScellementEntryId(dernierScellement != null ? dernierScellement.getDernierEntryId() : null)
            .build();
    }

    /** Recalcule chaque maillon. {@code calcul} null = clé HMAC indisponible : les maillons HMAC ne sont pas jugés. */
    private void verifierMaillons(List<JournalAudit> chaine, long debutHmac, CalculHmac calcul,
                                  List<ChaineAuditRuptureDto> ruptures, Map<Long, String> hashLiveParEntryId,
                                  Map<Long, Long> positionLiveParEntryId)
    {
        String hashAttendu = sha256(GENESE);
        for (JournalAudit entree : chaine)
        {
            boolean jugeable = entree.getPositionChaine() < debutHmac || calcul != null;
            if (jugeable)
            {
                String hashCalcule = maillon(entree.getPositionChaine(), debutHmac, calcul, hashAttendu,
                    serialiserCanonique(entree));
                if (!hashCalcule.equals(entree.getChainHash()))
                {
                    ruptures.add(rupture(entree, null));
                }
            }
            // Continue avec l'empreinte STOCKÉE (pas la recalculée) comme base du
            // tour suivant : une entrée altérée est ainsi signalée SEULE, sans
            // propager une rupture en cascade à toutes les entrées suivantes qui,
            // elles, sont parfaitement cohérentes avec ce qui est réellement stocké.
            hashAttendu = entree.getChainHash();
            hashLiveParEntryId.put(entree.getId(), entree.getChainHash());
            positionLiveParEntryId.put(entree.getId(), entree.getPositionChaine());
        }
    }

    private void verifierScellements(List<AuditChainSeal> scellements, AuditChainConfig config,
                                     List<JournalAudit> chaine, Map<Long, String> hashLiveParEntryId,
                                     Map<Long, Long> positionLiveParEntryId, List<ChaineAuditRuptureDto> ruptures,
                                     List<String> anomaliesGlobales)
    {
        String empreintePrecedente = null;
        for (AuditChainSeal seal : scellements)
        {
            JournalAudit entree = chaine.stream()
                .filter(e -> Objects.equals(e.getId(), seal.getDernierEntryId())).findFirst().orElse(null);
            String nom = "Scellement n°" + seal.getId() + " du " + seal.getCreatedAt();

            // Comparaison avec les scellements déjà horodatés : si l'empreinte VIVANTE à l'id scellé ne correspond
            // plus à ce qui a été scellé à l'époque, c'est le signal le plus fort possible (même un ré-enchaînement
            // complet de la suite ne peut pas faire correspondre à nouveau une valeur déjà figée par un tiers).
            String hashVivant = hashLiveParEntryId.get(seal.getDernierEntryId());
            if (hashVivant == null)
            {
                if (seal.getDernierEntryId() != null)
                {
                    ruptures.add(rupture(entree, nom + " : l'entrée scellée n°" + seal.getDernierEntryId()
                        + " n'existe plus dans la chaîne (supprimée ou retirée de la chaîne)"));
                }
            }
            else if (!hashVivant.equals(seal.getDernierChainHash())
                && ruptures.stream().noneMatch(r -> Objects.equals(r.getId(), seal.getDernierEntryId())))
            {
                ruptures.add(rupture(entree, "Divergence avec le scellement horodaté du "
                    + seal.getCreatedAt() + " — l'empreinte actuelle ne correspond plus à celle scellée"));
            }

            // Le jeton RFC 3161 de chaque scellement est lui aussi vérifié (signature, certificat TSA reconnu, empreinte
            // attestée) : sans cela, un attaquant qui réécrit la chaîne ET les empreintes scellées en base resterait
            // indétectable, la comparaison ci-dessus ne portant que sur des valeurs de la même base. Le jeton, lui, ne
            // se réécrit pas sans l'autorité d'horodatage.
            if (seal.getHorodatageToken() != null && seal.getDernierChainHash() != null)
            {
                HorodatageVerificationService.Etat etat = jetons.verifier(seal.getHorodatageToken(), seal.getDernierChainHash());
                if (etat == HorodatageVerificationService.Etat.EMPREINTE_DIFFERENTE
                    || etat == HorodatageVerificationService.Etat.INVALIDE)
                {
                    ruptures.add(rupture(entree, "Le jeton d'horodatage du scellement du " + seal.getCreatedAt()
                        + (etat == HorodatageVerificationService.Etat.EMPREINTE_DIFFERENTE
                            ? " atteste une autre empreinte que celle enregistrée"
                            : " est invalide (illisible, signature incorrecte ou autorité inconnue)")));
                }
            }

            // Signature de la clé système, position et lien avec le précédent : exigés dès le premier scellement
            // créé après l'introduction de la signature (les précédents n'en ont pas, et c'est normal).
            boolean signatureExigee = config != null && seal.getId() >= config.getScellementsSignesDepuisId();
            if (signatureExigee)
            {
                if (seal.getSignature() == null || seal.getSignature().isBlank())
                {
                    ruptures.add(rupture(entree, nom + " : la signature du système a été retirée"));
                }
                else
                {
                    // Clé de confiance introuvable : indéterminé, pas falsifié (même convention que partout ailleurs).
                    Optional<Boolean> valide = hsm.verifier(seal.getSignatureAlias(), empreinteScellement(seal),
                        seal.getSignature());
                    if (valide.isPresent() && !valide.get())
                    {
                        ruptures.add(rupture(entree, nom + " : signature du système invalide (scellement modifié "
                            + "ou fabriqué sans la clé du HSM)"));
                    }
                }

                Long positionVivante = positionLiveParEntryId.get(seal.getDernierEntryId());
                if (seal.getDernierPositionChaine() != null && positionVivante != null
                    && !seal.getDernierPositionChaine().equals(positionVivante))
                {
                    ruptures.add(rupture(entree, nom + " : la position de l'entrée scellée a changé (chaîne réordonnée)"));
                }

                if (!Objects.equals(seal.getEmpreintePrecedente(), empreintePrecedente))
                {
                    ruptures.add(rupture(entree, nom + " : le lien avec le scellement précédent est rompu "
                        + "(un scellement a été retiré, inséré ou modifié)"));
                }
            }
            empreintePrecedente = empreinteScellement(seal);
        }
    }

    /** Confronte les scellements de la base au registre tenu hors base : c'est ce qui révèle un scellement SUPPRIMÉ. */
    private void verifierRegistre(List<AuditChainSeal> scellements, List<String> anomaliesGlobales)
    {
        RegistreScellementsService.Lecture lecture = registre.lire();
        if (!lecture.disponible())
        {
            return;
        }

        Map<Long, AuditChainSeal> parId = new HashMap<>();
        scellements.forEach(s -> parId.put(s.getId(), s));
        List<AuditChainSeal> signes = scellements.stream().filter(s -> s.getSignature() != null).toList();

        if (!lecture.fichierPresent())
        {
            if (!signes.isEmpty())
            {
                anomaliesGlobales.add("Le registre des scellements (fichier hors base) est introuvable alors que "
                    + signes.size() + " scellement(s) signé(s) existent : la disparition de scellements ne peut plus "
                    + "être détectée.");
            }
            return;
        }
        if (lecture.lignesInvalides() > 0)
        {
            anomaliesGlobales.add("Le registre des scellements contient " + lecture.lignesInvalides()
                + " ligne(s) illisible(s) ou à signature invalide (registre altéré).");
        }

        List<Long> disparus = new ArrayList<>();
        List<Long> modifies = new ArrayList<>();
        for (RegistreScellementsService.Ligne ligne : lecture.lignes())
        {
            AuditChainSeal seal = parId.get(ligne.id());
            if (seal == null)
            {
                disparus.add(ligne.id());
            }
            else if (seal.getCreatedAt().getEpochSecond() != ligne.creeLeEpoch()
                || !Objects.equals(seal.getDernierChainHash(), ligne.chainHash())
                || !registre.empreinteSignature(seal).equals(ligne.empreinteSignature()))
            {
                modifies.add(ligne.id());
            }
        }
        if (!disparus.isEmpty())
        {
            anomaliesGlobales.add("Scellement(s) n°" + disparus + " inscrit(s) au registre mais DISPARU(S) de la base "
                + "(suppression de scellements : la chaîne ne peut plus être contrôlée contre ce qu'ils figeaient).");
        }
        if (!modifies.isEmpty())
        {
            anomaliesGlobales.add("Scellement(s) n°" + modifies + " modifié(s) depuis leur inscription au registre.");
        }

        List<Long> inscrits = lecture.lignes().stream().map(RegistreScellementsService.Ligne::id).toList();
        List<Long> nonInscrits = signes.stream().map(AuditChainSeal::getId).filter(id -> !inscrits.contains(id)).toList();
        if (!nonInscrits.isEmpty())
        {
            anomaliesGlobales.add("Scellement(s) n°" + nonInscrits + " absent(s) du registre : le registre a perdu des "
                + "lignes (ou une inscription a échoué).");
        }
    }

    private ChaineAuditRuptureDto rupture(JournalAudit entree, String description)
    {
        return ChaineAuditRuptureDto.builder()
            .id(entree != null ? entree.getId() : null)
            .horodatage(entree != null ? entree.getHorodatage() : null)
            .uoId(entree != null ? entree.getUoId() : null)
            .action(entree != null ? entree.getAction() : null)
            .description(description != null ? description : entree.getDescription())
            .build();
    }

    /** Concaténation déterministe des champs logiques d'une entrée, dans un ordre
     *  FIXE — jamais ObjectMapper.writeValueAsString(entité) : l'ordre des champs
     *  JSON de Jackson est un détail d'implémentation, pas un contrat ; une montée
     *  de version pourrait silencieusement invalider toutes les vérifications passées. */
    private String serialiserCanonique(JournalAudit e)
    {
        return new StringBuilder()
            .append(e.getId()).append(SEP)
            .append(e.getHorodatage()).append(SEP)
            .append(e.getActeurId()).append(SEP)
            .append(e.getActeurEmail()).append(SEP)
            .append(e.getActeurRole()).append(SEP)
            .append(e.getAdresseIp()).append(SEP)
            .append(e.getAction()).append(SEP)
            .append(e.getCibleType()).append(SEP)
            .append(e.getCibleId()).append(SEP)
            .append(e.getUoId()).append(SEP)
            .append(e.getDescription()).append(SEP)
            .append(e.isSucces()).append(SEP)
            .append(e.getDetails())
            .toString();
    }

    private String sha256(String texte)
    {
        return hashService.calculateFromBytes(texte.getBytes(StandardCharsets.UTF_8));
    }
}
