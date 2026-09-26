package made.archive.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.Attestation;

public interface AttestationRepository extends JpaRepository<Attestation, Long>
{
    // Un document peut avoir plusieurs attestations actives (voir Javadoc de
    // l'entité) — jamais plus une seule au singulier.
    List<Attestation> findAllByDocumentId(UUID documentId);

    Optional<Attestation> findByToken(String token);

    List<Attestation> findByExpireLeBefore(LocalDateTime instant);
}
