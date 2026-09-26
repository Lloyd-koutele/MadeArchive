package made.archive.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import made.archive.entite.TypeDocument;
import made.archive.security.UserDetailsImpl;
import made.archive.service.document.TypeDocumentService;
import made.archive.util.TypeDocumentMapper;

/**
 * Lecture SEULE des types de documents d'une UO, pour ADMIN/ADMIN_UO —
 * séparé de la gestion (créer/modifier/supprimer, désormais exclusivement
 * réservée aux EDITOR de leur propre UO, voir controller.DocumentController
 * sous /api/editor). Ce endpoint de LECTURE reste nécessaire à ADMIN_UO pour
 * des fonctionnalités qui n'ont rien à voir avec la gestion des types eux-
 * mêmes : regrouper les cibles d'un contrôle d'intégrité par type d'origine
 * (Admin.FixityCheckPanel), ou lister les types attendus dans un dossier
 * (organisation.DossiersPanel). Sans lui, ces deux écrans se retrouveraient
 * cassés pour ADMIN_UO alors qu'ils n'ont jamais géré (créer/modifier/
 * supprimer) de type de document.
 */
@RestController
@RequestMapping("/api/admin_uo/types-documents")
public class TypeDocumentLectureController
{
    private final TypeDocumentService typeDocumentService;
    private final TypeDocumentMapper typeDocumentMapper;

    public TypeDocumentLectureController(TypeDocumentService typeDocumentService, TypeDocumentMapper typeDocumentMapper)
    {
        this.typeDocumentService = typeDocumentService;
        this.typeDocumentMapper = typeDocumentMapper;
    }

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/uo/{uoId}")
    public ResponseEntity<?> getTypeDocumentByUo(@PathVariable Long uoId, @AuthenticationPrincipal UserDetailsImpl currentUser)
    {
        try
        {
            List<TypeDocument> typeDocument = typeDocumentService.getTypeDocumentsByUO(uoId, currentUser.getUser());
            return ResponseEntity.ok(typeDocumentMapper.toDtoList(typeDocument));
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(
                java.util.Map.of("message", "Erreur lors de la récupération des types de documents de cet UO: " + e.getMessage()));
        }
    }
}
