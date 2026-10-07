package made.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import made.archive.entite.CleChiffrementReference;

public interface CleChiffrementReferenceRepository extends JpaRepository<CleChiffrementReference, Long>
{
}
