package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.DataTypeDto;
import made.archive.dto.SaveMetaDataDto;
import made.archive.entite.DataType;
import made.archive.entite.Document;
import made.archive.entite.MetaData;
import made.archive.exception.BusinessException;
import made.archive.repository.DataTypeRepository;
import made.archive.repository.DocumentRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentMetaDataService
{
    private final DocumentRepository documentRepository;
    private final DataTypeRepository dataTypeRepository;

    @Transactional
    public void saveMetaData(UUID documentId,
                              SaveMetaDataDto dto,
                              UserDetails userDetails)
    {
        // 1. Récupérer le document
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(
                "Document introuvable : " + documentId));

        // 2. Vérifier que l'utilisateur connecté est bien l'uploadeur
        String email = userDetails.getUsername();
        if (!document.getUploadedBy().getEmail().equals(email))
        {
            throw new BusinessException(
                "Vous n'êtes pas autorisé à modifier les métadonnées de ce document");
        }

        // 3. Supprimer les anciennes valeurs si elles existent
        dataTypeRepository.deleteByDocumentId(documentId);

        // 4. Sauvegarder les nouvelles valeurs
        Map<String, MetaData> metaDataParNom = document.getTypeDocument().getMetaData().stream()
            .collect(Collectors.toMap(MetaData::getNom, m -> m, (a, b) -> a));

        List<DataType> dataTypes = new ArrayList<>();
        for (DataTypeDto metaDto : dto.getMetaData())
        {
            if (metaDto.getValeur() == null || metaDto.getValeur().isBlank()) continue;

            DataType dataType = new DataType();
            dataType.setValeur(metaDto.getValeur());
            dataType.setDocument(document);
            dataType.setMetaData(metaDataParNom.get(metaDto.getNom()));
            dataTypes.add(dataType);
        }

        dataTypeRepository.saveAll(dataTypes);
        log.info("[MetaData] {} valeurs sauvegardées pour document {}",
                 dataTypes.size(), documentId);
    }
}