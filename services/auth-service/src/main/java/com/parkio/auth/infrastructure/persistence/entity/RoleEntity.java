package com.parkio.auth.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import com.parkio.auth.domain.RoleName;

/** JPA mapping for the seeded {@code roles} table. */
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, unique = true)
    private RoleName name;

    protected RoleEntity() {
        // for JPA
    }

    public RoleEntity(UUID id, RoleName name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }
}
