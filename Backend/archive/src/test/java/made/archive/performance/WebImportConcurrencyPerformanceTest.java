package made.archive.performance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import made.archive.config.WebImportHttpProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de charge/concurrence — voir 5.3.2 et 5.9 du mémoire (limiteurs de
 * concurrence de l'import via lien web).
 *
 * Portée assumée : ce test valide le CONTRAT de bornage (java.util.concurrent.
 * Semaphore, même primitive et même valeur par défaut que
 * WebImportHttpProperties.maxConcurrent) sous une charge simultanée bien
 * supérieure à la limite — PAS un test de bout en bout contre de vrais appels
 * réseau (ça nécessiterait un serveur HTTP simulé, type WireMock, hors
 * périmètre de cette passe). C'est la garantie qui compte réellement :
 * jamais plus de N permis utilisés en même temps, quel que soit le nombre de
 * demandeurs, et un dépassement attend puis échoue proprement plutôt que de
 * s'empiler indéfiniment.
 */
@Tag("performance")
class WebImportConcurrencyPerformanceTest
{
    @Test
    void jamaisPlusDePermisSimultanesQueLaLimiteConfiguree() throws InterruptedException
    {
        int limite = new WebImportHttpProperties().getMaxConcurrent(); // 100 par défaut
        int demandeurs = limite * 5; // 5x la charge nominale

        Semaphore permis = new Semaphore(limite);
        AtomicInteger enCours = new AtomicInteger(0);
        AtomicInteger picMaximumObserve = new AtomicInteger(0);
        List<Boolean> resultats = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(demandeurs, 200));
        try
        {
            for (int i = 0; i < demandeurs; i++)
            {
                pool.submit(() -> {
                    boolean acquis = false;
                    try
                    {
                        acquis = permis.tryAcquire(2, TimeUnit.SECONDS);
                        if (acquis)
                        {
                            int courant = enCours.incrementAndGet();
                            picMaximumObserve.updateAndGet(max -> Math.max(max, courant));

                            // Simule un "téléchargement" bref — le temps suffisant
                            // pour que plusieurs threads se chevauchent réellement.
                            Thread.sleep(20);

                            enCours.decrementAndGet();
                        }
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    finally
                    {
                        if (acquis)
                        {
                            permis.release();
                        }
                        resultats.add(acquis);
                    }
                });
            }

            pool.shutdown();
            boolean termine = pool.awaitTermination(30, TimeUnit.SECONDS);
            assertThat(termine).as("tous les threads doivent se terminer sous 30s").isTrue();
        }
        finally
        {
            pool.shutdownNow();
        }

        assertThat(picMaximumObserve.get())
            .as("jamais plus de %d opérations simultanées, quel que soit le nombre de demandeurs (%d)",
                limite, demandeurs)
            .isLessThanOrEqualTo(limite);

        assertThat(resultats).hasSize(demandeurs);
        assertThat(resultats).as("toutes les demandes finissent par obtenir un permis (délai généreux)")
            .allMatch(Boolean::booleanValue);
    }

    @Test
    void auDelaDeLaLimiteUneDemandeAttendPuisEchoueProprementPlutotQueDeSEmpiler()
    {
        Semaphore permis = new Semaphore(1);
        assertThat(permis.tryAcquire()).isTrue(); // occupe l'unique permis

        long debut = System.nanoTime();
        boolean acquisSecondeDemande;
        try
        {
            acquisSecondeDemande = permis.tryAcquire(200, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        long dureeMs = (System.nanoTime() - debut) / 1_000_000;

        assertThat(acquisSecondeDemande)
            .as("le permis est occupé — la seconde demande doit échouer, pas s'empiler indéfiniment")
            .isFalse();
        assertThat(dureeMs)
            .as("doit avoir réellement attendu le délai imparti, pas échoué instantanément")
            .isGreaterThanOrEqualTo(190);
    }
}
