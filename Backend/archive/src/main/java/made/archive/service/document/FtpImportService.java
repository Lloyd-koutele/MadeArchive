package made.archive.service.document;

import lombok.extern.slf4j.Slf4j;
import made.archive.dto.FtpImportRequestDto;
import made.archive.exception.BusinessException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Récupère les fichiers d'un dossier distant via FTP ou FTPS pour alimenter le
 * bulk upload "même type" (voir BulkUploadSameTypeService.startOcrPreviewFromFtp).
 *
 * Les identifiants de connexion ne sont jamais persistés : ils ne vivent que le
 * temps de l'appel à {@link #telechargerDossier(FtpImportRequestDto)}.
 */
@Slf4j
@Service
public class FtpImportService
{
    private static final Set<String> EXTENSIONS_SUPPORTEES = Set.of(
        "pdf", "doc", "docx", "odt", "rtf", "txt",
        "xls", "xlsx", "ods", "csv",
        "jpg", "jpeg", "png", "tif", "tiff", "bmp"
    );

    private static final int  TIMEOUT_MS          = 15_000;
    private static final long TAILLE_MAX_OCTETS    = 50L * 1024 * 1024; // 50 Mo par fichier
    private static final int  DEFAULT_PORT         = 21;

    public record FichierDistant(String nomFichier, byte[] bytes) {}

    public List<FichierDistant> telechargerDossier(FtpImportRequestDto requete)
    {
        if (!StringUtils.hasText(requete.getHost()))
        {
            throw new BusinessException("L'adresse du serveur FTP est obligatoire");
        }

        FTPClient client = requete.isSecure() ? new FTPSClient() : new FTPClient();

        try
        {
            connecter(client, requete);
            authentifier(client, requete);

            if (client instanceof FTPSClient ftps)
            {
                // Chiffre aussi le canal de données (et pas seulement le contrôle) — sinon
                // le contenu des fichiers transite en clair malgré une session FTPS.
                ftps.execPBSZ(0);
                ftps.execPROT("P");
            }

            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);

            String chemin = StringUtils.hasText(requete.getRemotePath()) ? requete.getRemotePath() : "/";
            if (!client.changeWorkingDirectory(chemin))
            {
                throw new BusinessException("Dossier distant introuvable ou inaccessible : " + chemin);
            }

            return telechargerFichiersDuDossier(client);
        }
        catch (BusinessException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            log.error("[FTP-Import] Erreur de connexion à {} : {}", requete.getHost(), e.getMessage(), e);
            throw new BusinessException("Impossible de se connecter au serveur FTP : " + e.getMessage(), e);
        }
        finally
        {
            deconnecter(client);
        }
    }

    private void connecter(FTPClient client, FtpImportRequestDto requete) throws IOException
    {
        client.setConnectTimeout(TIMEOUT_MS);
        client.setDataTimeout(Duration.ofMillis(TIMEOUT_MS));

        int port = requete.getPort() != null ? requete.getPort() : DEFAULT_PORT;
        client.connect(requete.getHost(), port);

        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply))
        {
            client.disconnect();
            throw new BusinessException("Connexion refusée par le serveur FTP (code " + reply + ")"
                + (requete.isSecure() ? " — le serveur supporte-t-il bien le FTPS ?" : ""));
        }
    }

    private void authentifier(FTPClient client, FtpImportRequestDto requete) throws IOException
    {
        String utilisateur = StringUtils.hasText(requete.getUsername()) ? requete.getUsername() : "anonymous";
        String motDePasse  = requete.getPassword() != null ? requete.getPassword() : "";

        if (!client.login(utilisateur, motDePasse))
        {
            throw new BusinessException("Authentification FTP refusée — vérifiez l'identifiant et le mot de passe");
        }
    }

    private List<FichierDistant> telechargerFichiersDuDossier(FTPClient client) throws IOException
    {
        FTPFile[] fichiers = client.listFiles();
        if (fichiers == null || fichiers.length == 0)
        {
            throw new BusinessException("Aucun fichier trouvé dans le dossier distant");
        }

        List<FichierDistant> resultats = new ArrayList<>();

        for (FTPFile f : fichiers)
        {
            if (!f.isFile()) continue;

            String nom = f.getName();

            if (!extensionSupportee(nom))
            {
                log.info("[FTP-Import] Ignoré (extension non supportée) : {}", nom);
                continue;
            }

            if (f.getSize() > TAILLE_MAX_OCTETS)
            {
                log.warn("[FTP-Import] Ignoré (trop volumineux : {} octets) : {}", f.getSize(), nom);
                continue;
            }

            try (ByteArrayOutputStream buffer = new ByteArrayOutputStream())
            {
                if (!client.retrieveFile(nom, buffer))
                {
                    log.warn("[FTP-Import] Échec du téléchargement : {}", nom);
                    continue;
                }
                resultats.add(new FichierDistant(nom, buffer.toByteArray()));
                log.info("[FTP-Import] Téléchargé : {} ({} octets)", nom, buffer.size());
            }
        }

        if (resultats.isEmpty())
        {
            throw new BusinessException(
                "Aucun fichier importable trouvé dans le dossier distant (extensions supportées : "
                + String.join(", ", EXTENSIONS_SUPPORTEES) + ")");
        }

        return resultats;
    }

    private boolean extensionSupportee(String nomFichier)
    {
        int idx = nomFichier.lastIndexOf('.');
        if (idx < 0 || idx == nomFichier.length() - 1) return false;
        return EXTENSIONS_SUPPORTEES.contains(nomFichier.substring(idx + 1).toLowerCase(Locale.ROOT));
    }

    private void deconnecter(FTPClient client)
    {
        try
        {
            if (client.isConnected())
            {
                client.logout();
                client.disconnect();
            }
        }
        catch (IOException e)
        {
            log.warn("[FTP-Import] Erreur à la déconnexion (ignorée) : {}", e.getMessage());
        }
    }
}
