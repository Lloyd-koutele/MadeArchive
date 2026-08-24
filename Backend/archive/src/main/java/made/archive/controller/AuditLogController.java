package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.dto.AuditLogDto;
import made.archive.dto.AuditLogPageDto;
import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.security.UserDetailsImpl;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consultation du journal d'audit. ADMIN voit tout ; ADMIN_UO est automatiquement
 * restreint à son UO + sous-arbre (voir AuditLogService.rechercher /
 * UniteOrganisationnelleService.getUoIdsSousAutorite) — il n'y a pas de paramètre
 * "uoId" qui permettrait de contourner cette restriction, le scoping est appliqué
 * côté serveur avant tout filtre demandé par le client.
 */
@RestController
@RequestMapping("/api/admin_uo/audit-logs")
@RequiredArgsConstructor
public class AuditLogController
{
    private final AuditLogService auditLogService;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;

    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping
    public ResponseEntity<AuditLogPageDto> rechercher(
        @AuthenticationPrincipal UserDetailsImpl currentUser,
        @RequestParam(required = false) UUID acteurId,
        @RequestParam(required = false) AuditAction action,
        @RequestParam(required = false) AuditCible cibleType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateDebut,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFin,
        @RequestParam(required = false) String texte,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size)
    {
        Set<Long> uoAutorisees = uniteOrganisationnelleService.getUoIdsSousAutorite(currentUser.getUser());

        AuditLogPageDto resultat = auditLogService.rechercher(
            uoAutorisees, acteurId, action, cibleType,
            /* uoId */ null, dateDebut, dateFin, texte, page, Math.min(size, 100));

        return ResponseEntity.ok(resultat);
    }

    /**
     * Raccourci pour "tirer les logs d'un seul utilisateur" — strictement équivalent
     * à /audit-logs?acteurId=..., proposé séparément car c'est le cas d'usage nommé
     * explicitement par le besoin métier.
     */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/utilisateur/{acteurId}")
    public ResponseEntity<AuditLogPageDto> rechercherParUtilisateur(
        @AuthenticationPrincipal UserDetailsImpl currentUser,
        @org.springframework.web.bind.annotation.PathVariable UUID acteurId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size)
    {
        Set<Long> uoAutorisees = uniteOrganisationnelleService.getUoIdsSousAutorite(currentUser.getUser());

        AuditLogPageDto resultat = auditLogService.rechercher(
            uoAutorisees, acteurId, null, null,
            null, null, null, null, page, Math.min(size, 100));

        return ResponseEntity.ok(resultat);
    }

    /**
     * GET /api/admin_uo/audit-logs/export?format=csv|log&...
     *
     * Télécharge, dans le format demandé, exactement les entrées que les filtres
     * fournis sélectionnent (mêmes paramètres que la recherche paginée, sans page/size) —
     * pas d'export "tout, sans limite de période" : voir AuditLogService.EXPORT_MAX_LIGNES
     * pour le filet de sécurité si un filtre trop large est quand même fourni.
     */
    @Secured({"ROLE_ADMIN", "ROLE_ADMIN_UO"})
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporter(
        @AuthenticationPrincipal UserDetailsImpl currentUser,
        @RequestParam(required = false) UUID acteurId,
        @RequestParam(required = false) AuditAction action,
        @RequestParam(required = false) AuditCible cibleType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateDebut,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFin,
        @RequestParam(required = false) String texte,
        @RequestParam(defaultValue = "csv") String format)
    {
        Set<Long> uoAutorisees = uniteOrganisationnelleService.getUoIdsSousAutorite(currentUser.getUser());

        List<AuditLogDto> logs = auditLogService.rechercherTout(
            uoAutorisees, acteurId, action, cibleType, null, dateDebut, dateFin, texte);

        boolean formatLog = "log".equalsIgnoreCase(format);
        String contenu = formatLog ? versLogTexte(logs) : versCsv(logs);
        byte[] octets = contenu.getBytes(StandardCharsets.UTF_8);

        String extension = formatLog ? "log" : "csv";
        String nomFichier = "journal-audit_" + LocalDate.now() + "." + extension;
        MediaType type = formatLog
            ? MediaType.parseMediaType("text/plain; charset=UTF-8")
            : MediaType.parseMediaType("text/csv; charset=UTF-8");

        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(nomFichier).build().toString())
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(octets.length))
            .body(octets);
    }

    private static final DateTimeFormatter FORMAT_DATE_EXPORT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private String versCsv(List<AuditLogDto> logs)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM UTF-8 — Excel affiche correctement les accents avec ça
        sb.append("Date,Acteur,Role,IP,Action,TypeCible,IdCible,UO,Description,Succes,Details\n");

        for (AuditLogDto l : logs)
        {
            sb.append(csv(FORMAT_DATE_EXPORT.format(l.getHorodatage()))).append(',')
              .append(csv(l.getActeurEmail())).append(',')
              .append(csv(l.getActeurRole())).append(',')
              .append(csv(l.getAdresseIp())).append(',')
              .append(csv(l.getAction() != null ? l.getAction().name() : null)).append(',')
              .append(csv(l.getCibleType() != null ? l.getCibleType().name() : null)).append(',')
              .append(csv(l.getCibleId())).append(',')
              .append(csv(l.getUoId() != null ? l.getUoId().toString() : null)).append(',')
              .append(csv(l.getDescription())).append(',')
              .append(l.isSucces() ? "OUI" : "NON").append(',')
              .append(csv(l.getDetails()))
              .append('\n');
        }

        return sb.toString();
    }

    private String csv(String valeur)
    {
        if (valeur == null) return "";
        boolean aEchapper = valeur.contains(",") || valeur.contains("\"") || valeur.contains("\n");
        String echappe = valeur.replace("\"", "\"\"");
        return aEchapper ? "\"" + echappe + "\"" : echappe;
    }

    private String versLogTexte(List<AuditLogDto> logs)
    {
        StringBuilder sb = new StringBuilder();
        for (AuditLogDto l : logs)
        {
            sb.append('[').append(FORMAT_DATE_EXPORT.format(l.getHorodatage())).append("] ");
            sb.append(l.getActeurEmail() != null ? l.getActeurEmail() : "anonyme");
            if (l.getActeurRole() != null) sb.append(" (").append(l.getActeurRole()).append(')');
            sb.append(" — ").append(l.getAction());
            if (l.getCibleType() != null)
            {
                sb.append(" — ").append(l.getCibleType());
                if (l.getCibleId() != null) sb.append('#').append(l.getCibleId());
            }
            sb.append(" — ").append(l.getDescription());
            if (!l.isSucces()) sb.append(" [ÉCHEC]");
            if (l.getAdresseIp() != null) sb.append(" — IP ").append(l.getAdresseIp());
            sb.append('\n');
        }
        return sb.toString();
    }
}
