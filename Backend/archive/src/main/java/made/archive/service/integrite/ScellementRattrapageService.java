package made.archive.service.integrite;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.entite.Document;
import made.archive.repository.DocumentRepository;

/**
 * Scelle les documents archivés AVANT l'introduction de la signature d'enregistrement.
 *
 * Aucun document n'est scellé sur la foi de ce qu'il contient : la signature existante de son hash PDF/A (celle
 * produite à l'upload par la clé de l'éditeur) doit se vérifier avec la clé du HSM. Un ancien document dont
 * l'empreinte ne correspond déjà plus à sa signature est laissé non scellé et signalé — le sceller aurait
 * légitimé la falsification. Un procès-verbal (produit par le système, sans signature d'éditeur) est signé par la
 * clé système. Un document dont la clé de vérification est introuvable est laissé de côté, sans jugement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScellementRattrapageService
{
    private static final int TAILLE_LOT = 100;

    public record Resultat(int scelles, int ignores, int suspects) {}

    private final DocumentRepository documents;
    private final PreuveIntegriteService preuves;
    private final ManifestePreuveService manifestes;
    private final AlerteIntegriteService alertes;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @PostConstruct
    void init()
    {
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Resultat rattraper()
    {
        List<UUID> ids = documents.findIdsASceller();
        int scelles = 0;
        int ignores = 0;
        int suspects = 0;

        for (int i = 0; i < ids.size(); i += TAILLE_LOT)
        {
            List<UUID> lot = ids.subList(i, Math.min(i + TAILLE_LOT, ids.size()));
            int[] bilan = tx.execute(statut ->
            {
                int s = 0, ig = 0, su = 0;
                for (Document d : documents.findAllById(lot))
                {
                    switch (scellerUn(d))
                    {
                        case SCELLE -> s++;
                        case SUSPECT -> su++;
                        case IGNORE -> ig++;
                    }
                }
                return new int[] { s, ig, su };
            });
            scelles += bilan[0];
            ignores += bilan[1];
            suspects += bilan[2];
        }

        if (!ids.isEmpty())
        {
            log.info("[Preuves] Rattrapage du scellement : {} scellé(s), {} ignoré(s), {} suspect(s) sur {}",
                scelles, ignores, suspects, ids.size());
        }
        return new Resultat(scelles, ignores, suspects);
    }

    private enum Issue { SCELLE, IGNORE, SUSPECT }

    private Issue scellerUn(Document d)
    {
        boolean sansSignature = d.getPkiSignature() == null || d.getPkiSignature().isBlank();
        if (sansSignature)
        {
            if (d.getUploadedBy() == null || d.getUploadedBy().getPkiKeyAlias() != null)
            {
                log.warn("[Preuves] Document {} sans signature d'éditeur — non scellé", d.getId());
                return Issue.IGNORE;
            }
        }
        else
        {
            var valide = preuves.signatureHashValide(d, d.getPdfaSha256());
            if (valide.isEmpty())
            {
                return Issue.IGNORE;
            }
            if (!valide.get())
            {
                alertes.documentPreuveAlteree(d, "la signature du hash PDF/A ne correspond plus à l'empreinte en base "
                    + "(constaté lors du scellement de rattrapage — document non scellé)");
                return Issue.SUSPECT;
            }
        }

        preuves.scellerParLeSysteme(d);
        documents.save(d);
        manifestes.ecrire(d);
        return Issue.SCELLE;
    }
}
