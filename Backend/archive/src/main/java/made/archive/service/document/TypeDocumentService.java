package made.archive.service.document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.MetaDataDto;
import made.archive.dto.TypeDocumentDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.Document;
import made.archive.entite.MetaData;
import made.archive.entite.Retention;
import made.archive.entite.SortFinal;
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.entite.DocumentStatus;
import made.archive.repository.DocumentRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import made.archive.service.storage.MinioStorageService;
import made.archive.util.NormalisationNoms;



@Slf4j
@Service
public class TypeDocumentService
{
    private final TypeDocumentRepository typeDocumentRepository;

    private final DocumentRepository documentRepository;

    private final UserRepository userRepository;

    private final MeilisearchService meilisearchService;

    private final MinioStorageService minioStorageService;

    private final UniteOrganisationnelleService uniteOrganisationnelleService;

    private final AuditLogService auditLogService;

    private final DocumentRetentionService documentRetentionService;

    private final made.archive.util.TypeDocumentMapper typeDocumentMapper;

    public TypeDocumentService(
        TypeDocumentRepository typeDocumentRepository,
        DocumentRepository documentRepository,
        UserRepository userRepository,
        MeilisearchService meilisearchService,
        MinioStorageService minioStorageService,
        UniteOrganisationnelleService uniteOrganisationnelleService,
        AuditLogService auditLogService,
        DocumentRetentionService documentRetentionService,
        made.archive.util.TypeDocumentMapper typeDocumentMapper)
    {
        this.typeDocumentRepository = typeDocumentRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.meilisearchService = meilisearchService;
        this.minioStorageService = minioStorageService;
        this.documentRetentionService = documentRetentionService;
        this.uniteOrganisationnelleService = uniteOrganisationnelleService;
        this.auditLogService = auditLogService;
        this.typeDocumentMapper = typeDocumentMapper;
    }

    @Transactional
    public List<TypeDocument> getAllTypeDocuments()
    {
        try
        {
            return typeDocumentRepository.findAllWithRetentionAndMetaData();
        }
        catch(Exception e)
        {
            throw new RuntimeException("Erreur lors de la récupération de tous les types de documents");
        }
    }

    @Transactional
    public TypeDocument getTypeDocumentById(Long id)
    {
        try 
        {
            return typeDocumentRepository.findById(id).orElse(null);
        } 
        catch (Exception e) 
        {
            throw new RuntimeException("Erreur lors de la récupération du type le type de documents");
        }
    }

    @Transactional
    public TypeDocument getTypeDocumentByName(String name)
    {
        try 
        {
            return typeDocumentRepository.findByNom(name).orElse(null);
        } 
        catch (Exception e) 
        {
            throw new RuntimeException("Erreur lors de la récupération du type le type de documents");
        }
    }

    
    public boolean hasLinkedDocuments(Long id) 
    {
        try
        {
            return typeDocumentRepository.existsByDocumentsNotEmptyAndId(id);
        }
        catch(Exception e)
        {
            throw new RuntimeException("Erreur lors de la récupération du type le type de documents");
        }
    }

   @Transactional
    public void deleteTypeDocumentById(Long id, User currentUser) 
    {
        try 
        {
            TypeDocument typeDocument = typeDocumentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                    "Impossible de supprimer : le type de document avec l'ID " + id + " n'existe pas."));
    
            if (!uniteOrganisationnelleService.estEditeurDeUO(
                    typeDocument.getUniteOrganisationnelle().getId(), currentUser))
            {
                throw new AccessDeniedException("Vous n'avez pas l'autorisation de supprimer ce type de document");
            }
    
            refuserSiSysteme(typeDocument);
            if (hasLinkedDocuments(id)) 
            {
                throw new BusinessException("Impossible de supprimer ce type de document car des documents y sont actuellement rattachés.");
            }
    
            Long uoId = typeDocument.getUniteOrganisationnelle().getId();
            String nom = typeDocument.getNom();

            cleanupExternalStoresBeforeDeletion(id);
            typeDocumentRepository.deleteById(id);

            auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_SUPPRIME, AuditCible.TYPE_DOCUMENT,
                id.toString(), uoId, "Suppression du type de document " + nom, true);
        }
        catch (BusinessException e) 
        {
            throw e;
        }
        catch (AccessDeniedException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new BusinessException(
                "Une erreur technique est survenue lors de la tentative de suppression.", e);
        }
    }
    
    /**
     * Réinitialise les regex d'extraction d'un type : efface
     * extractionRegexJson et remet regexGenerated à false. Prochain document
     * de ce type → regénération automatique (voir
     * DocumentUploadeService.generateRegexIfFirstDocument()).
     *
     * Nécessaire car aujourd'hui la génération ne se fait qu'une seule fois
     * par type (au premier document) : si le premier exemplaire donne de
     * mauvaises regex, il n'existait auparavant aucun moyen de corriger le
     * tir sans modifier la base à la main.
     */
    @Transactional
    public void resetRegex(Long id, User currentUser)
    {
        TypeDocument typeDocument = typeDocumentRepository.findById(id)
            .orElseThrow(() -> new BusinessException(
                "Type de document introuvable : " + id));

        if (!uniteOrganisationnelleService.estEditeurDeUO(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException(
                "Vous n'avez pas l'autorisation de réinitialiser les regex de ce type");
        }

        viderRegex(typeDocument);

        auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_REGEX_REINITIALISEE, AuditCible.TYPE_DOCUMENT,
            id.toString(), typeDocument.getUniteOrganisationnelle().getId(),
            "Réinitialisation manuelle des regex du type " + typeDocument.getNom()
                + " par " + currentUser.getEmail(), true);
    }

    /**
     * Version SANS vérification d'autorité — appelée automatiquement quand la
     * correction des métadonnées de l'unique document existant d'un type
     * invalide les regex générées à partir de lui seul (voir
     * DocumentService.modifierMetaData). L'autorisation a déjà été vérifiée
     * en amont, sur l'action réellement effectuée par l'utilisateur (modifier
     * SON document) — pas la gestion du type lui-même, donc aAutoriteSur ne
     * s'applique pas ici.
     */
    @Transactional
    public void viderRegexAutomatiquement(TypeDocument typeDocument, User acteur, String raison)
    {
        viderRegex(typeDocument);

        log.info("[TypeDocument] Regex invalidées automatiquement pour le type {} : {}",
            typeDocument.getId(), raison);

        auditLogService.log(acteur, AuditAction.TYPE_DOCUMENT_REGEX_REINITIALISEE, AuditCible.TYPE_DOCUMENT,
            typeDocument.getId().toString(), typeDocument.getUniteOrganisationnelle().getId(),
            "Regex du type " + typeDocument.getNom() + " invalidées automatiquement — " + raison, true);
    }

    private void viderRegex(TypeDocument typeDocument)
    {
        typeDocument.setExtractionRegexMap(Map.of());
        typeDocumentRepository.save(typeDocument);

        log.info("[TypeDocument] Regex réinitialisées pour le type {}", typeDocument.getId());
    }

    /**
     * Correction manuelle des regex d'extraction par un administrateur — pour
     * quand une regex générée automatiquement (voir RegexGenerationService)
     * se trompe systématiquement mais que réinitialiser purement et
     * simplement (resetRegex) forcerait à attendre un nouveau document avant
     * d'avoir à nouveau des suggestions.
     *
     * Remplace intégralement la table champ→regex par celle soumise (pas de
     * fusion) : le formulaire d'édition envoie systématiquement un champ par
     * métadonnée existante, donc l'ensemble reçu représente déjà l'état
     * complet voulu.
     */
    @Transactional
    public TypeDocumentDto modifierRegex(Long id, Map<String, String> regexMap, User currentUser)
    {
        TypeDocument typeDocument = typeDocumentRepository.findByIdWithMetaData(id)
            .orElseThrow(() -> new BusinessException(
                "Type de document introuvable : " + id));

        if (!uniteOrganisationnelleService.estEditeurDeUO(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException(
                "Vous n'avez pas l'autorisation de modifier les regex de ce type");
        }

        if (regexMap == null || regexMap.isEmpty())
        {
            throw new BusinessException("Aucune regex fournie");
        }

        Set<String> champsConnus = typeDocument.getMetaData().stream()
            .map(MetaData::getNom)
            .collect(Collectors.toSet());

        for (Map.Entry<String, String> entry : regexMap.entrySet())
        {
            if (!champsConnus.contains(entry.getKey()))
            {
                throw new BusinessException(
                    "« " + entry.getKey() + "» ne correspond à aucune métadonnée de ce type");
            }
            try
            {
                java.util.regex.Pattern.compile(entry.getValue());
            }
            catch (java.util.regex.PatternSyntaxException e)
            {
                throw new BusinessException(
                    "Regex invalide pour « " + entry.getKey() + " » : " + e.getMessage());
            }
        }

        typeDocument.setExtractionRegexMap(regexMap);
        typeDocumentRepository.save(typeDocument);

        auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_REGEX_MODIFIEE, AuditCible.TYPE_DOCUMENT,
            id.toString(), typeDocument.getUniteOrganisationnelle().getId(),
            "Correction manuelle des regex du type " + typeDocument.getNom()
                + " par " + currentUser.getEmail(), true);

        return typeDocumentMapper.toDto(typeDocument);
    }

    public void cleanupExternalStoresBeforeDeletion(Long typeDocumentId)
    {
        List<Document> documents = documentRepository.findByTypeDocumentId(typeDocumentId);
        if (documents.isEmpty()) return;
    
        List<String> keysToDelete = new ArrayList<>();
        List<String> meiliIdsToDelete = new ArrayList<>();
    
        for (Document doc : documents) 
        {
            if (doc.getStorageKey() != null) keysToDelete.add(doc.getStorageKey());
            keysToDelete.add("ocr/" + doc.getId() + ".txt");
    
            meiliIdsToDelete.add(doc.getId().toString());
        }
    
        // Un par un via le garde-fou partagé (DocumentRetentionService) plutôt qu'un
        // deleteMultiple() en bloc : si cette méthode finit un jour par s'exécuter avec
        // des documents encore ACTIFS (aujourd'hui bloqué en amont par hasLinkedDocuments,
        // voir deleteTypeDocumentById), on veut refuser fichier par fichier plutôt que
        // supprimer aveuglément tout le lot.
        for (String key : keysToDelete)
        {
            documentRetentionService.supprimerFichierMinioSiOrphelin(
                key, "[TypeDocument-Delete] suppression du type " + typeDocumentId);
        }

        try
        {
            meilisearchService.deleteDocuments(meiliIdsToDelete);
        }
        catch (Exception e)
        {
            log.warn("[TypeDocument-Delete] Best-effort : échec de suppression Meilisearch "
                + "pour {} document(s) du type {} : {}",
                meiliIdsToDelete.size(), typeDocumentId, e.getMessage(), e);
        }
    }
    
    @Transactional
    public void deleteListTypeDocumentBestEffort(List<Long> ids, User currentUser) 
    {
        List<String> errors = new ArrayList<>();
    
        for (Long id : ids)
        {
            try 
            {
                deleteTypeDocumentById(id, currentUser);
            } 
            catch (BusinessException e) 
            {
                errors.add("ID " + id + " : " + e.getMessage());
            }
            catch (AccessDeniedException e)
            {
                errors.add("ID " + id + " : " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) 
        {
            throw new BusinessException("Certains types de documents n'ont pas pu être supprimés : " + String.join(" | ", errors));
        }
    }


    @Transactional
    public TypeDocument findById(Long id) 
    {
        try 
        {
            if (id == null) 
            {
                throw new BusinessException("L'ID est requis");
            }
            return typeDocumentRepository.findById(id)
                .orElseThrow(() -> {
                    return new BusinessException("Le type de document avec l'ID " + id + " n'existe pas.");
                });
        }
        catch (Exception e)
        {
            throw new BusinessException("Une erreur technique est survenue lors de la tentative de récupération du type de document.", e);
        }
    }

    @Transactional
    public TypeDocumentDto createTypeDocument(TypeDocumentDto dto, User currentUser) 
    {
        try 
        {
            if (dto.getUoId() == null)
            {
                throw new BusinessException("L'unité organisationnelle est obligatoire");
            }
    
            UniteOrganisationnelle uo = uniteOrganisationnelleService
                .getUOEntiteSiEditeur(dto.getUoId(), currentUser);
    
            verifierNomTypeDocumentUnique(dto.getNom(), dto.getUoId(), null);
    
            if (dto.getMetaData() == null || dto.getMetaData().isEmpty()) 
            {
                throw new BusinessException("Le type de document doit contenir au moins une métadonnée");
            }
    
            if (dto.getRetentionYears() != null && dto.getRetentionYears() <= 0)
            {
                throw new BusinessException("La durée de rétention, si elle est renseignée, doit être supérieure à 0");
            }
    
            Retention retention = new Retention();
            retention.setCreateAt(LocalDateTime.now());
    
            retention.setRetentionYears(dto.getRetentionYears());
            // Délai de grâce avant suppression définitive (corbeille) — indépendant de la durée de
            // rétention : il s'applique aussi à une suppression volontaire. Null = défaut (6 jours).
            retention.setPeriodGrace(validerDelaiGrace(dto.getPeriodGrace()));

            SortFinal sortFinalDemande = parseSortFinal(dto.getSortFinal());
            if (sortFinalDemande == null)
            {
                throw new BusinessException("Sort final invalide — valeurs acceptées : "
                    + java.util.Arrays.toString(SortFinal.values()));
            }
            retention.setSortFinal(sortFinalDemande);

            TypeDocument typeDocument = new TypeDocument();
            typeDocument.setNom(dto.getNom());
            typeDocument.setUser(currentUser);
            typeDocument.setRetention(retention);
            typeDocument.setUniteOrganisationnelle(uo);
    
            List<MetaData> metaDataList = new ArrayList<>();
            for (MetaDataDto metaDto : dto.getMetaData()) 
            {
                if (metaDto.getNom() == null || metaDto.getNom().isBlank()) 
                {
                    throw new BusinessException("Le nom d'un attribut de métadonnée ne peut pas être vide");
                }
    
                MetaData metaData = new MetaData();
                metaData.setNom(metaDto.getNom());
                metaData.setObligatoire(metaDto.getObligatoire());
                metaData.setTypeDocument(typeDocument);
                metaDataList.add(metaData);
            }
            typeDocument.setMetaData(metaDataList);
            typeDocumentRepository.save(typeDocument);
            
            auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_CREE, AuditCible.TYPE_DOCUMENT,
                typeDocument.getId().toString(), uo.getId(),
                "Création du type de document " + typeDocument.getNom(), true);

            dto.setId(typeDocument.getId());
            return dto;

        }
        catch (BusinessException e) 
        {
            throw e;
        } 
        catch (AccessDeniedException e)
        {
            throw e;
        }
        catch (Exception e) 
        {
            throw new BusinessException("Erreur lors de la création du type de document: " + e.getMessage());
        }
    }

    @Transactional
    public Optional<TypeDocumentDto> updateTypeDocument(Long id, TypeDocumentDto dto, User currentUser) 
    {
        try 
        {
            if (dto == null || id == null) 
            {
                throw new BusinessException("Données invalides");
            }
    
            TypeDocument typeDocument = typeDocumentRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Type de document non trouvé avec l'ID: " + id));
    
            if (!uniteOrganisationnelleService.estEditeurDeUO(typeDocument.getUniteOrganisationnelle().getId(), currentUser))
            {
                throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier ce type de document");
            }
    
            refuserSiSysteme(typeDocument);
            if (hasLinkedDocuments(id)) 
            {
                throw new BusinessException("Impossible de modifier ce type de document car des documents y sont actuellement rattachés.");
            }

            Long uoActuelleId = typeDocument.getUniteOrganisationnelle().getId();
    
            if (dto.getNom() != null && !typeDocument.getNom().equalsIgnoreCase(dto.getNom())) 
            {
                verifierNomTypeDocumentUnique(dto.getNom(), uoActuelleId, id);
                typeDocument.setNom(dto.getNom());
            }
    
            Retention currentRetention = typeDocument.getRetention();
            if (currentRetention == null) 
            {
                currentRetention = new Retention();
                currentRetention.setCreateAt(LocalDateTime.now());
                typeDocument.setRetention(currentRetention);
            }
    
            if (dto.getRetentionYears() != null && dto.getRetentionYears() <= 0)
            {
                throw new BusinessException("La durée de rétention, si elle est renseignée, doit être supérieure à 0");
            }
    
            // periodGrace n'est volontairement PAS touché ici : il se modifie via modifierDelaiGrace
            // (appel dédié, non verrouillé par les documents déjà rattachés).
            currentRetention.setRetentionYears(dto.getRetentionYears());
    
            if (dto.getMetaData() != null)
            {
                List<MetaData> currentMetaDatas = typeDocument.getMetaData();

                Set<Long> idsConserves = dto.getMetaData().stream()
                    .map(MetaDataDto::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

                currentMetaDatas.removeIf(m -> !idsConserves.contains(m.getId()));

                for (MetaDataDto metaDto : dto.getMetaData())
                {
                    if (metaDto.getId() != null)
                    {
                        currentMetaDatas.stream()
                            .filter(m -> m.getId().equals(metaDto.getId()))
                            .findFirst()
                            .ifPresent(existingMeta -> {
                                existingMeta.setNom(metaDto.getNom());
                                existingMeta.setObligatoire(metaDto.getObligatoire());
                            });
                    }
                    else
                    {
                        MetaData newMeta = new MetaData();
                        newMeta.setNom(metaDto.getNom());
                        newMeta.setObligatoire(metaDto.getObligatoire());
                        newMeta.setTypeDocument(typeDocument);
                        currentMetaDatas.add(newMeta);
                    }
                }
            }

            auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_MODIFIE, AuditCible.TYPE_DOCUMENT,
                id.toString(), uoActuelleId, "Modification du type de document " + typeDocument.getNom(), true);

            dto.setId(typeDocument.getId());
            return Optional.of(dto);

        }
        catch (BusinessException e) 
        {
            throw e;
        } 
        catch (AccessDeniedException e)
        {
            throw e;
        }
        catch (Exception e) 
        {
            throw new BusinessException("Erreur lors de la modification: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<TypeDocument> getTypeDocumentsByUO(Long uoId, User currentUser)
    {
        if (uoId == null)
        {
            throw new BusinessException("L'UO est obligatoire");
        }

        try
        {
            return typeDocumentRepository.findByUniteOrganisationnelleIdWithRetentionAndMetaData(uoId);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur lors de la récupération des types de documents de l'UO");
        }
    }

    /**
     * Types de documents visibles par l'utilisateur connecté — ouvert à
     * TOUT rôle (EDITOR, ADMIN_UO, ADMIN, simple USER), contrairement à
     * getAllTypeDocuments()/getTypeDocumentsByUO() ci-dessus qui exigent
     * tous les deux un rôle ADMIN/ADMIN_UO côté contrôleur. Sert le filtre
     * "Type de document" de "Documents accessibles" (voir
     * DocumentAccessService), qui doit rester utilisable par un simple
     * éditeur ou utilisateur — pas seulement un administrateur.
     *
     * @param uoIdExplicite Restreint à une UO précise (navigation Admin/
     *                      Admin_UO dans l'arbre) — reste borné au périmètre
     *                      déjà autorisé pour l'appelant, jamais un moyen d'en
     *                      sortir (même garde que DocumentAccessFilterDto.uoId).
     *                      null = tout le périmètre visible de l'appelant.
     */
    @Transactional
    public List<TypeDocument> getTypeDocumentsVisibles(User currentUser, Long uoIdExplicite)
    {
        // null = ADMIN global, aucun filtrage nécessaire — voir
        // UniteOrganisationnelleService.getUoIdsVisiblesPourLecture().
        Set<Long> uoVisibles = uniteOrganisationnelleService.getUoIdsVisiblesPourLecture(currentUser);

        if (uoIdExplicite != null)
        {
            if (uoVisibles != null && !uoVisibles.contains(uoIdExplicite))
            {
                return List.of(); // hors périmètre autorisé — jamais une fuite
            }
            return typeDocumentRepository.findByUniteOrganisationnelleIdWithRetentionAndMetaData(uoIdExplicite);
        }

        if (uoVisibles == null)
        {
            return typeDocumentRepository.findAllWithRetentionAndMetaData();
        }
        if (uoVisibles.isEmpty())
        {
            return List.of();
        }
        return typeDocumentRepository.findByUniteOrganisationnelleIdInWithRetentionAndMetaData(uoVisibles);
    }

    @Transactional
    public TypeDocumentDto renommerTypeDocument(Long id, String nouveauNom, User currentUser)
    {
        if (nouveauNom == null || nouveauNom.isBlank())
        {
            throw new BusinessException("Le nom est obligatoire");
        }
    
        TypeDocument typeDocument = typeDocumentRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Type de document non trouvé avec l'ID: " + id));
    
        if (!uniteOrganisationnelleService.estEditeurDeUO(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier ce type de document");
        }
        refuserSiSysteme(typeDocument);
    
        if (!typeDocument.getNom().equalsIgnoreCase(nouveauNom))
        {
            verifierNomTypeDocumentUnique(nouveauNom, typeDocument.getUniteOrganisationnelle().getId(), id);

            String ancienNom = typeDocument.getNom();
            typeDocument.setNom(nouveauNom);
            typeDocumentRepository.save(typeDocument);

            // Le nom du type est indexé (recherche en texte libre) pour CHAQUE document du type : sans cette mise à
            // jour, tous resteraient trouvables sous l'ancien nom. Les documents CORRUPTED / DELETED ne sont plus
            // dans l'index et ne doivent pas y être recréés.
            meilisearchService.mettreAJourTypeDocument(id, nouveauNom,
                documentRepository.findIdsByTypeDocumentIdAndStatusNotIn(id,
                    java.util.List.of(DocumentStatus.DELETED, DocumentStatus.CORRUPTED)));

            auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_MODIFIE, AuditCible.TYPE_DOCUMENT,
                id.toString(), typeDocument.getUniteOrganisationnelle().getId(),
                "Renommage du type de document « " + ancienNom + " » en « " + nouveauNom + " »", true,
                Map.of("nom", Map.of("avant", ancienNom, "apres", nouveauNom)));
        }
    
        TypeDocumentDto dto = new TypeDocumentDto();
        dto.setId(typeDocument.getId());
        dto.setNom(typeDocument.getNom());
        dto.setUoId(typeDocument.getUniteOrganisationnelle().getId());
        return dto;
    }

    /**
     * Modifie le sort final (CONSERVER/DETRUIRE/TRIER — voir entite.SortFinal)
     * d'un type de document — DÉLIBÉRÉMENT un endpoint à part, PAS soumis au
     * verrou hasLinkedDocuments de updateTypeDocument (voir Javadoc plus haut) :
     * contrairement au nom ou aux métadonnées, le sort final est une décision de
     * gouvernance purement tournée vers l'avenir — elle ne touche jamais une date
     * déjà calculée sur un document existant (Document.retentionUntil), donc rien
     * n'empêche de la revoir à tout moment, y compris pour un type déjà utilisé
     * (le cas le plus courant en pratique : c'est précisément pour les types déjà
     * en service que cette décision a besoin d'être prise ou changée).
     */
    @Transactional
    public TypeDocumentDto modifierSortFinal(Long id, String sortFinalDemande, User currentUser)
    {
        TypeDocument typeDocument = typeDocumentRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Type de document non trouvé avec l'ID: " + id));

        if (!uniteOrganisationnelleService.estEditeurDeUO(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier ce type de document");
        }

        refuserSiSysteme(typeDocument);
        SortFinal nouveauSortFinal = parseSortFinal(sortFinalDemande);
        if (nouveauSortFinal == null)
        {
            throw new BusinessException("Sort final invalide — valeurs acceptées : "
                + java.util.Arrays.toString(SortFinal.values()));
        }

        Retention retention = typeDocument.getRetention();
        SortFinal ancienSortFinal = retention.getSortFinal();
        retention.setSortFinal(nouveauSortFinal);
        typeDocumentRepository.save(typeDocument);

        auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_SORT_FINAL_MODIFIE, AuditCible.TYPE_DOCUMENT,
            id.toString(), typeDocument.getUniteOrganisationnelle().getId(),
            "Sort final du type \"" + typeDocument.getNom() + "\" changé de " + ancienSortFinal
                + " à " + nouveauSortFinal, true,
            Map.of("sortFinal", Map.of("avant", String.valueOf(ancienSortFinal), "apres", nouveauSortFinal.name())));

        TypeDocumentDto dto = typeDocumentMapper.toDto(typeDocument);
        return dto;
    }

    /** Un type créé par l'application (ex. « Procès-verbal d'élimination ») n'est jamais modifiable ni supprimable. */
    public static void refuserSiSysteme(TypeDocument type)
    {
        if (type != null && type.isSysteme())
        {
            throw new BusinessException("Le type « " + type.getNom()
                + " » est géré par l'application : il ne peut être ni modifié ni supprimé");
        }
    }

    /** Délai de grâce de la corbeille pour ce type, en jours (1 à 365) — null = défaut (voir
     *  DocumentService.delaiGraceJours). Comme modifierSortFinal, DÉLIBÉRÉMENT hors du verrou
     *  hasLinkedDocuments : il ne touche aucun document existant (l'échéance d'un document déjà en
     *  corbeille reste celle calculée à son entrée) et sert surtout pour les types déjà en service. */
    @Transactional
    public TypeDocumentDto modifierDelaiGrace(Long id, Long jours, User currentUser)
    {
        TypeDocument typeDocument = typeDocumentRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Type de document non trouvé avec l'ID: " + id));

        if (!uniteOrganisationnelleService.estEditeurDeUO(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier ce type de document");
        }

        refuserSiSysteme(typeDocument);
        Long nouveau = validerDelaiGrace(jours);
        Long ancien = typeDocument.getRetention().getPeriodGrace();
        typeDocument.getRetention().setPeriodGrace(nouveau);
        typeDocumentRepository.save(typeDocument);

        auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_DELAI_GRACE_MODIFIE, AuditCible.TYPE_DOCUMENT,
            id.toString(), typeDocument.getUniteOrganisationnelle().getId(),
            "Délai de grâce du type \"" + typeDocument.getNom() + "\" : "
                + (ancien != null ? ancien + " jours" : "défaut") + " → "
                + (nouveau != null ? nouveau + " jours" : "défaut (" + DocumentService.DELAI_GRACE_CORBEILLE_JOURS + " jours)"),
            true);

        return typeDocumentMapper.toDto(typeDocument);
    }

    private Long validerDelaiGrace(Long jours)
    {
        if (jours != null && (jours < 1 || jours > 365))
        {
            throw new BusinessException("Le délai de grâce doit être compris entre 1 et 365 jours");
        }
        return jours;
    }

    /** null (pas d'erreur) si non fourni — createTypeDocument applique alors le
     *  défaut CONSERVER déjà porté par Retention.sortFinal elle-même. */
    private SortFinal parseSortFinal(String valeur)
    {
        if (valeur == null || valeur.isBlank())
        {
            return SortFinal.CONSERVER;
        }
        try
        {
            return SortFinal.valueOf(valeur.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Doublon détecté après NORMALISATION (casse, accents, espacement — voir
     * NormalisationNoms), même principe que UniteOrganisationnelleService.
     * verifierNomUnique/verifierNomUniqueExclut : "Facture", "facture" et
     * "Fàcture" sont considérés comme LE MÊME nom dans une même UO, pas
     * seulement un doublon casse-insensible comme avant (findByNomIgnoreCase...
     * laissait passer les accents).
     */
    private void verifierNomTypeDocumentUnique(String nom, Long uoId, Long exclutId)
    {
        String nomNormalise = NormalisationNoms.normaliser(nom);
        boolean existe = typeDocumentRepository.findByUniteOrganisationnelleId(uoId).stream()
            .anyMatch(t -> (exclutId == null || !t.getId().equals(exclutId))
                && NormalisationNoms.normaliser(t.getNom()).equals(nomNormalise));

        if (existe)
        {
            throw new BusinessException("Un type de document avec ce nom existe déjà dans cette UO");
        }
    }
}