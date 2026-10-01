package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.TrustShadowProjectionConflictException;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.port.TrustSnapshotRevision;
import com.parkio.parking.application.port.TrustSnapshotWritePort;
import com.parkio.parking.infrastructure.persistence.entity.TrustSnapshotEntity;
import com.parkio.parking.infrastructure.persistence.jpa.TrustSnapshotJpaRepository;
import com.parkio.parking.infrastructure.persistence.trust.TrustPersistenceMapper;
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSubject;
import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class TrustSnapshotRepositoryAdapter implements TrustSnapshotReadPort, TrustSnapshotWritePort {

    private final TrustSnapshotJpaRepository jpa;
    private final TrustPersistenceMapper mapper;
    private final Clock clock;

    public TrustSnapshotRepositoryAdapter(TrustSnapshotJpaRepository jpa, ObjectMapper objectMapper, Clock clock) {
        this.jpa = jpa;
        this.mapper = new TrustPersistenceMapper(objectMapper);
        this.clock = clock;
    }

    @Override
    public Optional<TrustSnapshot> findBySubjectAndDomain(TrustSubject subject, TrustDomain domain) {
        return findEntity(subject, domain).map(mapper::toDomain);
    }

    @Override
    public Optional<TrustSnapshotRevision> findRevision(TrustSubject subject, TrustDomain domain) {
        return findEntity(subject, domain)
                .map(entity -> new TrustSnapshotRevision(mapper.toDomain(entity), entity.getVersion()));
    }

    @Override
    public void upsert(TrustSnapshot snapshot, Long expectedVersion) {
        try {
            if (expectedVersion == null) {
                jpa.save(mapper.toEntity(snapshot, clock.instant(), clock.instant(), null));
                jpa.flush();
                return;
            }
            Optional<TrustSnapshotEntity> existing = findEntity(snapshot.subject(), snapshot.domain());
            if (existing.isEmpty() || !expectedVersion.equals(existing.get().getVersion())) {
                throw new TrustShadowProjectionConflictException(
                        "Concurrent trust snapshot update",
                        new OptimisticLockingFailureException("trust snapshot version changed"));
            }
            TrustSnapshotEntity current = existing.get();
            jpa.save(mapper.toEntity(snapshot, current.getCreatedAt(), clock.instant(), expectedVersion));
            jpa.flush();
        } catch (OptimisticLockingFailureException ex) {
            throw new TrustShadowProjectionConflictException("Concurrent trust snapshot update", ex);
        } catch (DataIntegrityViolationException ex) {
            throw new TrustShadowProjectionConflictException("Concurrent trust snapshot insert", ex);
        }
    }

    private Optional<TrustSnapshotEntity> findEntity(TrustSubject subject, TrustDomain domain) {
        return jpa.findBySubjectTypeAndSubjectIdAndTrustDomain(
                subject.type().name(), subject.subjectId(), domain.name());
    }
}

