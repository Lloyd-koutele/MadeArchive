package made.archive.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UniteOrganisationnelleDto 
{
    private Long id;
    private String nom;
    private Long parentId;
    private String cheminComplet;
    private List<UserResponseDto> users;
    private List<TypeDocumentDto> typeDocuments;
}
