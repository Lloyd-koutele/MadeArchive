package made.archive.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;

import made.archive.service.document.TypeDocumentService;
import made.archive.util.TypeDocumentMapper;
import made.archive.dto.TypeDocumentDto;
import made.archive.entite.TypeDocument;
import made.archive.exception.BusinessException;
import made.archive.security.UserDetailsImpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin_uo")
public class TypeDocumentController 
{
    private final TypeDocumentService typeDocumentService;

    private final TypeDocumentMapper typeDocumentMapper;

    public TypeDocumentController(TypeDocumentService typeDocumentService, TypeDocumentMapper typeDocumentMapper)
    {
        this.typeDocumentService = typeDocumentService;
        this.typeDocumentMapper = typeDocumentMapper;
    }
    

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PostMapping("/types-documents/create")
    public ResponseEntity<?> createTypeDocument(@RequestBody TypeDocumentDto dto, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            TypeDocumentDto result = typeDocumentService.createTypeDocument(dto, currentUser.getUser());
            return ResponseEntity.ok(result);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(buildError("Erreur serveur lors de la création du type de document: " + e.getMessage()));
        }
    }


    @Secured("ROLE_ADMIN")
    @GetMapping("/types-documents")
    public ResponseEntity<?> getAllTypeDocuments()
    {
        try
        {
            List<TypeDocument> typeDocuments = typeDocumentService.getAllTypeDocuments();
            return ResponseEntity.ok(typeDocumentMapper.toDtoList(typeDocuments));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(buildError("Erreur lors de la récupération des types de documents: " + e.getMessage()));
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/types-documents/{id}")
    public ResponseEntity<?> getTypeDocumentById(@PathVariable Long id)
    {
        try
        {
            TypeDocument typeDocument = typeDocumentService.getTypeDocumentById(id);
            if (typeDocument == null) 
            {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(typeDocumentMapper.toDto(typeDocument)); 
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(buildError("Erreur lors de la récupération du type de document: " + e.getMessage()));
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/types-documents/uo/{uoId}")
    public ResponseEntity<?> getTypeDocumentByUo(@PathVariable Long uoId, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            List<TypeDocument> typeDocument = typeDocumentService.getTypeDocumentsByUO(uoId, currentUser.getUser());
            return ResponseEntity.ok(typeDocumentMapper.toDtoList(typeDocument)); 
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(buildError("Erreur lors de la récupération des types de documents de cet UO: " + e.getMessage()));
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/types-documents/{id}")
    public ResponseEntity<?> updateTypeDocument(@PathVariable Long id, @RequestBody TypeDocumentDto dto, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(typeDocumentService.updateTypeDocument(id, dto, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("Erreur lors de la mise à jour du type de document: " + e.getMessage()));
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/types-documents/renommer/{id}")
    public ResponseEntity<?> renommerTypeDocument(@PathVariable Long id, @RequestBody String nom, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            return ResponseEntity.ok(typeDocumentService.renommerTypeDocument(id, nom, currentUser.getUser()));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("Erreur lors du renommage du type de document: " + e.getMessage()));
        }
    }


    /**
     * Réinitialise les regex d'extraction du type : le prochain document
     * déposé pour ce type régénérera automatiquement des regex fraîches.
     * À utiliser si le premier document ayant servi de base était un mauvais
     * candidat (scan flou, valeurs atypiques...).
     */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @PutMapping("/types-documents/{id}/reset-regex")
    public ResponseEntity<?> resetRegex(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            typeDocumentService.resetRegex(id, currentUser.getUser());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Regex réinitialisées — elles seront régénérées au prochain document de ce type");
            return ResponseEntity.ok(response);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("Erreur lors de la réinitialisation des regex : " + e.getMessage()));
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @DeleteMapping("/types-documents/{id}")
    public ResponseEntity<?> deleteTypeDocumentById(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {   typeDocumentService.deleteTypeDocumentById(id, currentUser.getUser());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Type de document supprimers avec succès");
            return ResponseEntity.ok(response);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("Erreur lors de la suppression du type de document: " + e.getMessage()));
        }
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @DeleteMapping("/types-documents/delete-list")
    public ResponseEntity<?> deleteListTypeDocuments(@RequestBody List<Long> ids, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            typeDocumentService.deleteListTypeDocumentBestEffort(ids, currentUser.getUser());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Types de documents supprimés avec succès");
            return ResponseEntity.ok(response);
        }
        catch (BusinessException e)
        {
            return ResponseEntity.badRequest()
                .body(buildError("Erreur lors de la suppression des types de documents : " + e.getMessage()));
        }
    }

    /** Corps JSON {"message": ...} attendu par le client (error.response.data.message) —
     *  un corps texte brut ne serait pas exploitable côté frontend. */
    private Map<String, Object> buildError(String message)
    {
        return Map.of("message", message);
    }
}