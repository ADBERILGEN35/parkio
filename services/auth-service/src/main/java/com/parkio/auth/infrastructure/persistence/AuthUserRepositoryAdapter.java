package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.admin.AdminUserSearchQuery;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.AuthUserEntity;
import com.parkio.auth.infrastructure.persistence.jpa.AuthUserJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Adapts the {@link AuthUserRepository} port to Spring Data JPA. */
@Component
public class AuthUserRepositoryAdapter implements AuthUserRepository {

    private final AuthUserJpaRepository jpa;

    public AuthUserRepositoryAdapter(AuthUserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuthUser save(AuthUser user) {
        AuthUserEntity saved = jpa.save(AuthPersistenceMapper.toEntity(user));
        return AuthPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<AuthUser> findById(UUID id) {
        return jpa.findById(id).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public Optional<AuthUser> findByEmail(String email) {
        return jpa.findByEmail(email).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public Optional<AuthUser> findByEmailVerificationTokenHash(String tokenHash) {
        return jpa.findByEmailVerificationTokenHash(tokenHash).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }

    @Override
    public PageResult<AuthUser> search(AdminUserSearchQuery query) {
        Specification<AuthUserEntity> spec = buildSearchSpec(query);
        Pageable pageable = PageRequest.of(query.page(), query.size(), parseSort(query.sort()));
        Page<AuthUserEntity> page = jpa.findAll(spec, pageable);
        List<AuthUser> content = page.getContent().stream().map(AuthPersistenceMapper::toDomain).toList();
        return new PageResult<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public long countByStatus(AuthUserStatus status) {
        return jpa.countByStatus(status);
    }

    @Override
    public long countVerified() {
        return jpa.countByEmailVerified(true);
    }

    @Override
    public long countUnverified() {
        return jpa.countByEmailVerified(false);
    }

    @Override
    public long countCreatedSince(Instant since) {
        return jpa.countByCreatedAtGreaterThanEqual(since);
    }

    @Override
    public long countByRole(RoleName roleName) {
        return jpa.countByRoleName(roleName);
    }

    private static Specification<AuthUserEntity> buildSearchSpec(AdminUserSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            String emailFilter = firstNonBlank(query.emailContains(), query.q());
            if (emailFilter != null) {
                predicates.add(cb.like(cb.lower(root.get("email")),
                        "%" + emailFilter.toLowerCase(Locale.ROOT) + "%"));
            }
            if (query.userId() != null) {
                predicates.add(cb.equal(root.get("id"), query.userId()));
            }
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.emailVerified() != null) {
                predicates.add(cb.equal(root.get("emailVerified"), query.emailVerified()));
            }
            if (query.roleName() != null) {
                Join<Object, Object> roles = root.join("roles");
                predicates.add(cb.equal(roles.get("name"), query.roleName()));
                cq.distinct(true);
            }
            if (query.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.createdFrom()));
            }
            if (query.createdTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.createdTo()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    /** Sortable entity properties; anything else falls back to the default (no probing arbitrary columns). */
    private static final java.util.Set<String> SORTABLE_FIELDS = java.util.Set.of("createdAt", "email", "status");

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
