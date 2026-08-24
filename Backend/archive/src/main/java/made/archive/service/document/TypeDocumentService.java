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
import made.archive.entite.TypeDocument;
import made.archive.entite.UniteOrganisationnelle;
import made.archive.entite.User;
import made.archive.exception.AccessDeniedException;
import made.archive.exception.BusinessException;
import made.archive.repository.DocumentRepository;
import made.archive.repository.TypeDocumentRepository;
import made.archive.repository.UserRepository;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import made.archive.service.storage.MinioStorageService;



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

    public TypeDocumentService(
        TypeDocumentRepository typeDocumentRepository,
        DocumentRepository documentRepository,
        UserRepository userRepository,
        MeilisearchService meilisearchService,
        MinioStorageService minioStorageService,
        UniteOrganisationnelleService uniteOrganisationnelleService,
        AuditLogService auditLogService,
        DocumentRetentionService documentRetentionService)
    {
        this.typeDocumentRepository = typeDocumentRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.meilisearchService = meilisearchService;
        this.minioStorageService = minioStorageService;
        this.documentRetentionService = documentRetentionService;
        this.uniteOrganisationnelleService = uniteOrganisationnelleService;
        this.auditLogService = auditLogService;
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
    
            if (!uniteOrganisationnelleService.aAutoriteSur(
                    typeDocument.getUniteOrganisationnelle().getId(), currentUser))
            {
                throw new AccessDeniedException("Vous n'avez pas l'autorisation de supprimer ce type de document");
            }
    
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

        if (!uniteOrganisationnelleService.aAutoriteSur(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException(
                "Vous n'avez pas l'autorisation de réinitialiser les regex de ce type");
        }

        typeDocument.setExtractionRegexMap(Map.of());
        typeDocumentRepository.save(typeDocument);

        log.info("[TypeDocument] Regex réinitialisées pour le type {} par {}",
            id, currentUser.getEmail());

        auditLogService.log(currentUser, AuditAction.TYPE_DOCUMENT_REGEX_REINITIALISEE, AuditCible.TYPE_DOCUMENT,
            id.toString(), typeDocument.getUniteOrganisationnelle().getId(),
            "Réinitialisation des regex du type " + typeDocument.getNom(), true);
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
                .getUOEntiteAvecAutorite(dto.getUoId(), currentUser);
    
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
    
            if (dto.getRetentionYears() != null)
            {
                retention.setRetentionYears(dto.getRetentionYears());
                retention.setPeriodGrace(dto.getPeriodGrace() != null ? dto.getPeriodGrace() : 30L);
            }
            else
            {
                retention.setRetentionYears(null);
                retention.setPeriodGrace(null);
            }
    
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
    
            if (!uniteOrganisationnelleService.aAutoriteSur(typeDocument.getUniteOrganisationnelle().getId(), currentUser))
            {
                throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier ce type de document");
            }
    
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
    
            currentRetention.setRetentionYears(dto.getRetentionYears());
            currentRetention.setPeriodGrace(dto.getRetentionYears() != null
                ? (dto.getPeriodGrace() != null ? dto.getPeriodGrace() : 30L)
                : null);
    
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

    @Transactional
    public TypeDocumentDto renommerTypeDocument(Long id, String nouveauNom, User currentUser)
    {
        if (nouveauNom == null || nouveauNom.isBlank())
        {
            throw new BusinessException("Le nom est obligatoire");
        }
    
        TypeDocument typeDocument = typeDocumentRepository.findById(id)
            .orElseThrow(() -> new BusinessException("Type de document non trouvé avec l'ID: " + id));
    
        if (!uniteOrganisationnelleService.aAutoriteSur(
                typeDocument.getUniteOrganisationnelle().getId(), currentUser))
        {
            throw new AccessDeniedException("Vous n'avez pas l'autorisation de modifier ce type de document");
        }
    
        if (!typeDocument.getNom().equalsIgnoreCase(nouveauNom))
        {
            typeDocumentRepository
                .findByNomIgnoreCaseAndUniteOrganisationnelleId(nouveauNom, typeDocument.getUniteOrganisationnelle().getId())
                .ifPresent(t -> {
                    throw new BusinessException("Un type de document avec ce nom existe déjà dans cette UO");
                });

            String ancienNom = typeDocument.getNom();
            typeDocument.setNom(nouveauNom);
            typeDocumentRepository.save(typeDocument);

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

    private void verifierNomTypeDocumentUnique(String nom, Long uoId, Long exclutId)
    {
        typeDocumentRepository.findByNomIgnoreCaseAndUniteOrganisationnelleId(nom, uoId)
            .filter(t -> exclutId == null || !t.getId().equals(exclutId))
            .ifPresent(t -> {
                throw new BusinessException("Un type de document avec ce nom existe déjà dans cette UO");
            });
    }
}