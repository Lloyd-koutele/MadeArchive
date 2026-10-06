package made.archive.service.integrite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Racine de Merkle des preuves d'un lot de documents. Les préfixes distinguent une feuille d'un nœud interne (un
 * nœud ne peut pas se faire passer pour une feuille), et un nombre impair de nœuds promeut le dernier tel quel
 * plutôt que de le dupliquer (duplication = deux jeux de feuilles différents pour une même racine).
 */
public final class ArbreMerkle
{
    private ArbreMerkle() {}

    public static String feuille(FeuilleDocument f)
    {
        return sha256("F|" + f.id() + "|" + f.pdfaSha256() + "|" + f.originalSha256() + "|"
            + (f.signatureEnregistrement() != null ? f.signatureEnregistrement() : ""));
    }

    /** Racine d'un lot ; l'ordre d'entrée n'a pas d'importance (tri par identifiant). Vide si le lot est vide. */
    public static String racine(List<FeuilleDocument> documents)
    {
        if (documents.isEmpty())
        {
            return "";
        }
        List<String> niveau = new ArrayList<>();
        documents.stream()
            .sorted(Comparator.comparing(f -> f.id().toString()))
            .forEach(f -> niveau.add(feuille(f)));

        while (niveau.size() > 1)
        {
            List<String> suivant = new ArrayList<>();
            for (int i = 0; i < niveau.size(); i += 2)
            {
                suivant.add(i + 1 < niveau.size()
                    ? sha256("N|" + niveau.get(i) + "|" + niveau.get(i + 1))
                    : niveau.get(i));
            }
            niveau.clear();
            niveau.addAll(suivant);
        }
        return niveau.get(0);
    }

    private static String sha256(String texte)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(texte.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
