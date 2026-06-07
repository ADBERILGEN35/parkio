package com.parkio.auth.application.port;

import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import java.util.Optional;

/** Persistence port for reading the seeded roles. */
public interface RoleRepository {

    Optional<Role> findByName(RoleName name);
}
