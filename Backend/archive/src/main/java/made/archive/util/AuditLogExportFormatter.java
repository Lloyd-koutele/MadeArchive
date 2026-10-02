package made.archive.util;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import made.archive.dto.AuditLogDto;

/**
 * Mise en forme des exports (CSV avec BOM UTF-8 / fichier .log) d'entrées de journal —
 * partagée entre le journal d'audit global (AuditLogController) et le journal de cycle de
 * vie d'un document (UserDocumentController), pour que les deux exports soient identiques.
 */
public final class AuditLogExportFormatter
{
    private AuditLogExportFormatter() {}

    private static final DateTimeFormatter FORMAT_DATE_EXPORT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static String versCsv(List<AuditLogDto> logs)
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

    private static String csv(String valeur)
    {
        if (valeur == null) return "";
        boolean aEchapper = valeur.contains(",") || valeur.contains("\"") || valeur.contains("\n");
        String echappe = valeur.replace("\"", "\"\"");
        return aEchapper ? "\"" + echappe + "\"" : echappe;
    }

    public static String versLogTexte(List<AuditLogDto> logs)
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
