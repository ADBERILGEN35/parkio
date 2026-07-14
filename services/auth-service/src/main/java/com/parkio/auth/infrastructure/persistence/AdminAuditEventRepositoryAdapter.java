package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.admin.AdminAuditSearchQuery;
import com.parkio.auth.application.admin.AdminAuditTargets;
import com.parkio.auth.application.port.AdminAuditEventRepository;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.domain.admin.AdminAuditEvent;
import com.parkio.auth.infrastructure.persistence.entity.AdminAuditEventEntity;
import com.parkio.auth.infrastructure.persistence.jpa.AdminAuditEventJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AdminAuditPersistenceMapper;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditEventRepositoryAdapter implements AdminAuditEventRepository {

    private static final String TARGET_TYPE_AUTH_USER = AdminAuditTargets.AUTH_USER;

    private final AdminAuditEventJpaRepository jpa;

    public AdminAuditEventRepositoryAdapter(AdminAuditEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(AdminAuditEvent event) {
        jpa.save(AdminAuditPersistenceMapper.toEntity(event));
    }

    @Override
    public PageResult<AdminAuditEvent> search(AdminAuditSearchQuery query) {
        Specification<AdminAuditEventEntity> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.actorUserId() != null) {
                predicates.add(cb.equal(root.get("actorUserId"), query.actorUserId()));
            }
            if (query.targetResourceId() != null) {
                predicates.add(cb.equal(root.get("targetResourceId"), query.targetResourceId()));
            }
            if (query.actionType() != null) {
                predicates.add(cb.equal(root.get("actionType"), query.actionType()));
            }
            if (query.result() != null) {
                predicates.add(cb.equal(root.get("result"), query.result()));
            }
            if (query.occurredFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), query.occurredFrom()));
            }
            if (query.occurredTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), query.occurredTo()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Pageable pageable = PageRequest.of(query.page(), query.size(), parseSort(query.sort(), "occurredAt"));
        Page<AdminAuditEventEntity> page = jpa.findAll(spec, pageable);
        List<AdminAuditEvent> content = page.getContent().stream()
                .map(AdminAuditPersistenceMapper::toDomain)
                .toList();
        return new PageResult<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    public List<AdminAuditEvent> findRecentForTarget(String targetResourceType, UUID targetResourceId, int limit) {
        return jpa.findByTargetResourceTypeAndTargetResourceIdOrderByOccurredAtDesc(
                        targetResourceType, targetResourceId, PageRequest.of(0, limit))
                .stream()
                .map(AdminAuditPersistenceMapper::toDomain)
                .toList();
    }

    public static String authUserTargetType() {
        return AdminAuditTargets.AUTH_USER;
    }

    /** Sortable entity properties; anything else falls back to the default (no probing arbitrary columns). */
    private static final java.util.Set<String> SORTABLE_FIELDS =
            java.util.Set.of("occurredAt", "actionType", "result");

    private static Sort parseSort(String sort, String defaultField) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, defaultField);
        }
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            return Sort.by(Sort.Direction.DESC, defaultField);
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
