package made.archive.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import made.archive.entite.User;

public interface UserRepository extends JpaRepository<User, UUID>
{
    Optional<User> findByEmail(String email);

    Optional<User> findByTelephone(String telephone);

    Optional<User> findByEmailAndRoles (String email, Role roles);
    
    boolean existsByEmail(String email);

    List<User> findByRoles(Role roles);

    List<User> findByActif(boolean actif);

    Optional<User> findById(Long userId);


    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name = :roleName")
    List<User> findByRoleName(@Param("roleName") Role_Name roleName);

    // UserRepository
    @Query("SELECT u FROM User u JOIN u.membresUniteOrganisationnelles m WHERE m.uniteOrganisationnelle.id = :uoId")
    List<User> findByUniteOrganisationnelleId(@Param("uoId") Long uoId);
}