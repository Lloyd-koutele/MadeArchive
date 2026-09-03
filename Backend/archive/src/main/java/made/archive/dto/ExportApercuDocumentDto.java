package made.archive.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import made.archive.entite.TypeAccess;

/** Une ligne d'aperçu — voir ExportApercuRequestDto. */
@Data
@AllArgsConstructor
public class ExportApercuDocumentDto
{
    private UUID id;
    private String titre;
    private String uoNom;
    private String projetNom;
    private TypeAccess access;

    /** Faux si ce document n'apparaît dans cet aperçu QUE grâce à
     *  includePriveNonMembre=true (document privé dont l'appelant n'est
     *  pas membre) — permet au client de le signaler visuellement avant
     *  de lancer l'export. */
    private boolean accesNormal;
}
