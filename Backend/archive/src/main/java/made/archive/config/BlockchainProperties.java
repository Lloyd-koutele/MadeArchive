package made.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "blockchain")
public class BlockchainProperties 
{
    private String rpcUrl;
    private String privateKey;
    private String contractAddress;
}