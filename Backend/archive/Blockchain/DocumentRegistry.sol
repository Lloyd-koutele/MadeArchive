// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract DocumentRegistry 
{

    // Événement émis à chaque enregistrement
    event DocumentRegistered(
        string indexed sha256Hash,
        address indexed registeredBy,
        uint256 timestamp
    );

    // Mapping : hash → timestamp
    mapping(string => uint256) private registry;

    /**
     * Enregistre le hash d'un document sur la blockchain.
     * Retourne une erreur si le hash existe déjà.
     */
    function registerDocument(string memory sha256Hash) public {
        require(registry[sha256Hash] == 0, "Document deja enregistre");
        registry[sha256Hash] = block.timestamp;
        emit DocumentRegistered(sha256Hash, msg.sender, block.timestamp);
    }

    /**
     * Vérifie si un document a été enregistré.
     * Retourne le timestamp d'enregistrement (0 si non enregistré).
     */
    function getTimestamp(string memory sha256Hash) public view returns (uint256) {
        return registry[sha256Hash];
    }
}