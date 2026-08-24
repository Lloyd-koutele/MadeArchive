package made.archive.service.document;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;

@Service
public class HashService 
{
    public String calculate(MultipartFile file) 
    {
        try (InputStream is = file.getInputStream()) 
        {
            return calculateFromStream(is);
        } 
        catch (Exception e) 
        {
            throw new RuntimeException("Erreur calcul SHA-256", e);
        }
    }

    /**
     * Calcule le SHA-256 depuis un InputStream.
     */
    public String calculateFromStream(InputStream is) 
    {
        try 
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) 
            {
                digest.update(buffer, 0, read);
            }
            return bytesToHex(digest.digest());
        } 
        catch (Exception e) 
        {
            throw new RuntimeException("Erreur calcul SHA-256", e);
        }
    }


    public boolean verify(MultipartFile file, String expectedHash) 
    {
        try
        {
            return calculate(file).equals(expectedHash);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur verification SHA-256", e);
        }
    }


    public boolean verifyFromStream(InputStream is, String expectedHash) 
    {
        try
        {
            return calculateFromStream(is).equals(expectedHash);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur verification SHA-256", e);
        }
    }

    private String bytesToHex(byte[] bytes) 
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) 
        {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String calculateFromBytes(byte[] bytes)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return bytesToHex(digest.digest());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Erreur calcul SHA-256", e);
        }
    }
}