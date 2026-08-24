package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.config.BlockchainProperties;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.tx.RawTransactionManager;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockchainService 
{

    private final BlockchainProperties props;

    /**
     * Enregistre le SHA-256 d'un document sur Polygon.
     * Retourne le transaction hash → stocké dans Document.blockChainTxld
     */
    public String registerDocument(String sha256Hash) {
        try {
            Web3j web3j = Web3j.build(new HttpService(props.getRpcUrl()));
            Credentials credentials = Credentials.create(props.getPrivateKey());

            RawTransactionManager txManager = new RawTransactionManager(
                web3j, credentials, 80002L // Chain ID Polygon Amoy Testnet
            );

            // Encodage de l'appel à registerDocument(string)
            Function function = new Function(
                "registerDocument",
                List.of(new Utf8String(sha256Hash)),
                Collections.emptyList()
            );
            String encodedFunction = FunctionEncoder.encode(function);

            // Envoi de la transaction
            EthSendTransaction response = txManager.sendTransaction(
                DefaultGasProvider.GAS_PRICE,
                DefaultGasProvider.GAS_LIMIT,
                props.getContractAddress(),
                encodedFunction,
                BigInteger.ZERO  // pas d'ETH/MATIC envoyé
            );

            if (response.hasError()) {
                throw new RuntimeException(
                    "Erreur transaction blockchain : " + response.getError().getMessage()
                );
            }

            String txHash = response.getTransactionHash();
            log.info("Document enregistré sur Polygon. TxHash : {}", txHash);
            return txHash;

        } catch (Exception e) {
            throw new RuntimeException("Erreur blockchain", e);
        }
    }

    /**
     * Vérifie si un hash est bien enregistré sur la blockchain.
     * Retourne le timestamp (0 si non trouvé).
     */
    public BigInteger verifyDocument(String sha256Hash) {
        try {
            Web3j web3j = Web3j.build(new HttpService(props.getRpcUrl()));

            Function function = new Function(
                "getTimestamp",
                List.of(new Utf8String(sha256Hash)),
                List.of(new org.web3j.abi.TypeReference<Uint256>() {})
            );

            String encodedFunction = FunctionEncoder.encode(function);

            org.web3j.protocol.core.methods.response.EthCall result = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    null,
                    props.getContractAddress(),
                    encodedFunction
                ),
                org.web3j.protocol.core.DefaultBlockParameterName.LATEST
            ).send();

            List<Type> decoded = org.web3j.abi.FunctionReturnDecoder.decode(
                result.getValue(),
                function.getOutputParameters()
            );

            return (BigInteger) decoded.get(0).getValue();

        } catch (Exception e) {
            throw new RuntimeException("Erreur vérification blockchain", e);
        }
    }
}