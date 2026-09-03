package made.archive.util;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import made.archive.dto.MetaDataDto;
import made.archive.dto.TypeDocumentDto;
import made.archive.entite.MetaData;
import made.archive.entite.TypeDocument;

@Component
public class TypeDocumentMapper 
{

    public TypeDocumentDto toDto(TypeDocument entity) 
    {
        if (entity == null) 
        {
            return null;
        }

        TypeDocumentDto dto = new TypeDocumentDto();
        dto.setId(entity.getId());
        dto.setNom(entity.getNom());
        dto.setUserId(entity.getUser().getId());

        // ✅ Aplatir les données de Retention
        if (entity.getRetention() != null) {
            dto.setRetentionYears(entity.getRetention().getRetentionYears());
            dto.setPeriodGrace(entity.getRetention().getPeriodGrace());
        }

        // Mapper les MetaDatas
        if (entity.getMetaData() != null && !entity.getMetaData().isEmpty()) {
            dto.setMetaData(
                entity.getMetaData().stream()
                    .map(this::metaDataToDto)
                    .collect(Collectors.toList())
            );
        } else {
            dto.setMetaData(List.of());
        }

        return dto;
    }

    public List<TypeDocumentDto> toDtoList(List<TypeDocument> entities) {
        return entities.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    private MetaDataDto metaDataToDto(MetaData entity) 
    {
        return new MetaDataDto(
            entity.getId(),
            entity.getNom(),
            entity.getObligatoire()
        );
    }
}