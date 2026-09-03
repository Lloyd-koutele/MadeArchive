package made.archive.entite;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Suivi d'une demande d'export administratif de documents (voir
 * DocumentExportService) — remplace le script export_uo_documents.py utilisé
 * jusqu'ici pour les migrations/changements de système d'archivage, en
 * l'intégrant à l'application avec ses propres contrôles d'accès plutôt que
 * des identifiants d'infrastructure (DB, MinIO, clé de chiffrement) partagés
 * à la main.
 *
 * Persisté (pas un simple cache mémoire comme OcrSessionCache) : contrairement
 * à une session OCR, un export peut prendre du temps et l'admin doit pouvoir
 * retrouver son statut plus tard, y compris après un redémarrage du serveur.
 *
 * uoIdsJson / documentIdsJson : listes sérialisées en JSON dans une colonne
 * texte, même convention que TypeDocument.extractionRegexJson — pas de table
 * de jointure pour une liste d'ids qui n'a de sens qu'à la lecture de ce job.
 * documentIdsJson est la liste RÉSOLUE (déjà filtrée par visibilité) au
 * moment de lancerExport() — le traitement asynchrone n'a plus besoin de
 * ré-appliquer les règles d'accès, qui pourraient avoir changé entre-temps.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "export_jobs")
public class ExportJob
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demande_par", nullable = false)
    private User demandePar;

    @Lob
    @Column(name = "uo_ids_json", nullable = false)
    private String uoIdsJson;

    @Lob
    @Column(name = "document_ids_json")
    private String documentIdsJson;

    /**
     * Export élargi aux documents PRIVÉS dont le demandeur n'est pas membre
     * du groupe d'accès — réservé à ROLE_ADMIN (voir DocumentExportService),
     * jamais ROLE_ADMIN_UO. Faux par défaut : un export ne contourne alors
     * aucune règle de visibilité déjà en place ailleurs dans l'application.
     */
    @Column(name = "include_prive_non_membre", nullable = false)
    private boolean includePriveNonMembre;

    @Column(name = "separate_projects", nullable = false)
    private boolean separateProjects;

    @Column(name = "exclude_corbeille", nullable = false)
    private boolean excludeCorbeille;

    /**
     * Motif de l'export — obligatoire quand includePriveNonMembre est vrai
     * (voir DocumentExportService), affiché dans la notification obligatoire
     * envoyée aux membres des documents privés concernés et à l'admin_uo.
     */
    @Column(length = 500)
    private String motif;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ExportJobStatus statut = ExportJobStatus.EN_ATTENTE;

    @Column(nullable = false)
    private int documentsTotal;

    @Column(nullable = false)
    private int documentsTraites;

    @Column(nullable = false)
    private int documentsEnEchec;

    /** Chemin du fichier ZIP généré, sur le disque local du serveur — jamais dans MinIO
     *  (voir DocumentExportService : le contenu est déchiffré, y compris des documents
     *  privés, et ne doit donc jamais transiter par le stockage objet chiffré au repos). */
    @Column(name = "chemin_zip", length = 500)
    private String cheminZip;

    @Column(nullable = false)
    private LocalDateTime createAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    /** Le ZIP (et cette ligne) sont purgés après ce délai — voir ExportCleanupScheduler.
     *  Contient du contenu déchiffré, y compris potentiellement des documents privés :
     *  il ne doit jamais traîner indéfiniment sur le disque du serveur. */
    @Column(nullable = false)
    private LocalDateTime expireAt;

    public List<Long> getUoIds()
    {
        return parseListSilencieux(uoIdsJson, new TypeReference<List<Long>>() {});
    }

    public void setUoIds(List<Long> uoIds)
    {
        this.uoIdsJson = ecrireJson(uoIds);
    }

    public List<UUID> getDocumentIds()
    {
        return parseListSilencieux(documentIdsJson, new TypeReference<List<UUID>>() {});
    }

    public void setDocumentIds(List<UUID> documentIds)
    {
        this.documentIdsJson = ecrireJson(documentIds);
    }

    private static <T> List<T> parseListSilencieux(String json, TypeReference<List<T>> type)
    {
        if (json == null || json.isBlank())
        {
            return List.of();
        }
        try
        {
            return MAPPER.readValue(json, type);
        }
        catch (Exception e)
        {
            return List.of();
        }
    }

    private static String ecrireJson(Object value)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("Sérialisation JSON impossible pour ExportJob", e);
        }
    }
}
