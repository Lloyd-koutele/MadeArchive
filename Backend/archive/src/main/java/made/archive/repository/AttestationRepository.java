package made.archive.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.Attestation;

public interface AttestationRepository extends JpaRepository<Attestation, Long>
{
    Optional<Attestation> findByDocumentId(UUID documentId);

    Optional<Attestation> findByToken(String token);
}
