package made.archive.dto;

import lombok.Data;
import java.util.List;

@Data
public class SaveMetaDataDto
{
    private List<DataTypeDto> metaData;
}