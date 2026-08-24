package made.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import made.archive.entite.Role;
import made.archive.entite.Role_Name;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long>
{
    Optional<Role> findByName(Role_Name name);
}
