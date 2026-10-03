package made.archive.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.service.user.UtilisateurSystemeService;

/** Crée le compte technique « Système MadeArchive » au démarrage s'il n'existe pas encore. */
@Slf4j
@Component
@RequiredArgsConstructor
public class UtilisateurSystemeInitializer implements CommandLineRunner
{
    private final UtilisateurSystemeService utilisateurSystemeService;

    @Override
    public void run(String... args)
    {
        try
        {
            utilisateurSystemeService.obtenir();
        }
        catch (Exception e)
        {
            log.error("[Systeme] Impossible de préparer le compte technique : {}", e.getMessage(), e);
        }
    }
}
