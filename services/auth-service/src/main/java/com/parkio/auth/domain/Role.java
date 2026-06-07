package com.parkio.auth.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A role an {@link AuthUser} can hold. Identity is the {@link RoleName}.
 */
public final class Role {

    private final UUID id;
    private final RoleName name;

    public Role(UUID id, RoleName name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
    }

    public UUID id() {
        return id;
    }

    public RoleName name() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Role role)) {
            return false;
        }
        return name == role.name;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
