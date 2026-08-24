package made.archive.dto;

import lombok.Data;

import java.util.UUID;

/**
 * Requête Phase 1 du bulk upload "même type" depuis un serveur distant (FTP/FTPS).
 *
 * Les identifiants (username/password) ne sont utilisés que pour la durée de cette
 * requête — ils ne sont jamais persistés en base (voir FtpImportService).
 */
@Data
public class FtpImportRequestDto
{
    private String host;

    /** Optionnel — 21 par défaut. */
    private Integer port;

    /** Dossier distant à importer — "/" par défaut. */
    private String remotePath;

    /** Optionnel — connexion anonyme si absent. */
    private String username;

    /** Optionnel — connexion anonyme si absent. */
    private String password;

    /** true = FTPS (chiffré), false = FTP simple. */
    private boolean secure;

    private Long typeDocumentId;
    private UUID uploadedById;
}
