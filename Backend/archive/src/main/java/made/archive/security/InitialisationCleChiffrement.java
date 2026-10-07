package made.archive.security;

import java.io.InputStream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.Document;
import made.archive.repository.DocumentRepository;
import made.archive.security.ControleCleChiffrementService.Etat;
import made.archive.service.integrite.AlerteIntegriteService;
import made.archive.service.storage.StorageService;

/**
 * Au démarrage : enregistre l'empreinte de la clé de chiffrement si elle ne l'est pas encore, ou alerte
 * l'administration si la clé configurée n'est pas (ou plus) la bonne.
 *
 * Quand des archives existent déjà sans référence (base antérieure à ce contrôle), l'empreinte n'est enregistrée
 * qu'APRÈS avoir prouvé que la clé configurée ouvre la plus ancienne archive : on ne fige jamais une mauvaise clé
 * comme référence. Un stockage indisponible reporte simplement l'enregistrement (aucun blocage, voir
 * ControleCleChiffrementService.exigerCleValide).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialisationCleChiffrement implements ApplicationRunner
{
    private final ControleCleChiffrementService controle;
    private final DocumentEncryptionService chiffrement;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AlerteIntegriteService alerteIntegriteService;

    @Override
    public void run(ApplicationArguments args)
    {
        try
        {
            Etat etat = controle.etat();
            switch (etat)
            {
                case VALIDE -> log.info("[CleChiffrement] Clé de chiffrement conforme à la référence.");
                case REFERENCE_NON_INITIALISEE -> initialiser();
                default ->
                {
                    log.error("[CleChiffrement] {}", ControleCleChiffrementService.message(etat));
                    alerteIntegriteService.cleChiffrementAnormale(ControleCleChiffrementService.message(etat));
                }
            }
        }
        catch (Exception e)
        {
            log.warn("[CleChiffrement] Initialisation reportée : {}", e.getMessage());
        }
    }

    private void initialiser() throws Exception
    {
        Document plusAncien = documentRepository
            .findAll(PageRequest.of(0, 1, Sort.by("createAt").ascending()))
            .stream().findFirst().orElse(null);

        if (plusAncien == null)
        {
            controle.enregistrerReference();
            return;
        }

        byte[] chiffre;
        try (InputStream flux = storageService.download(plusAncien.getStorageKey()))
        {
            if (flux == null)
            {
                log.warn("[CleChiffrement] Archive de référence introuvable : enregistrement reporté.");
                return;
            }
            chiffre = flux.readAllBytes();
        }

        if (chiffrement.peutDechiffrer(chiffre))
        {
            controle.enregistrerReference();
        }
        else
        {
            String raison = "La clé de chiffrement configurée n'ouvre pas les archives existantes : aucune "
                + "référence enregistrée. Rétablir la clé d'origine (STORAGE_ENCRYPTION_KEY).";
            log.error("[CleChiffrement] {}", raison);
            alerteIntegriteService.cleChiffrementAnormale(raison);
        }
    }
}
