package made.archive.service.importweb;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import made.archive.exception.BusinessException;

/**
 * Plafonne le nombre d'accès sortants d'un utilisateur par minute (fenêtre glissante) : l'import par lien fait faire
 * des requêtes réseau au serveur ; sans plafond par personne, un seul compte pourrait l'utiliser pour balayer des
 * adresses ou saturer la file de téléchargements au détriment des autres. Les plafonds globaux de simultanéité
 * (WebImportHttpProperties) restent en place en plus de celui-ci.
 */
@Component
public class LimiteurImports
{
    private static final long FENETRE_MS = 60_000L;

    private final Map<UUID, Deque<Long>> accesParUtilisateur = new ConcurrentHashMap<>();
    private final LongSupplier horloge;

    public LimiteurImports()
    {
        this(System::currentTimeMillis);
    }

    LimiteurImports(LongSupplier horloge)
    {
        this.horloge = horloge;
    }

    /**
     * Enregistre {@code nombre} accès pour cet utilisateur.
     *
     * @throws BusinessException si cela dépasserait {@code maxParMinute} sur la dernière minute (rien n'est enregistré)
     */
    public void consommer(UUID utilisateur, int nombre, int maxParMinute)
    {
        if (utilisateur == null || maxParMinute <= 0) return;

        Deque<Long> accès = accesParUtilisateur.computeIfAbsent(utilisateur, k -> new ArrayDeque<>());
        synchronized (accès)
        {
            long maintenant = horloge.getAsLong();
            while (!accès.isEmpty() && maintenant - accès.peekFirst() >= FENETRE_MS)
            {
                accès.pollFirst();
            }
            if (accès.size() + nombre > maxParMinute)
            {
                throw new BusinessException("Trop d'imports par lien en peu de temps (limite : " + maxParMinute
                    + " par minute et par utilisateur) — réessayez dans quelques instants.");
            }
            for (int i = 0; i < nombre; i++) accès.addLast(maintenant);
        }
    }
}
