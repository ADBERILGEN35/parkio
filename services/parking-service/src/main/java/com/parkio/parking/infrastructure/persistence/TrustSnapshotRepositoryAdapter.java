package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.TrustShadowProjectionConflictException;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.port.TrustSnapshotWritePort;
import com.parkio.parking.infrastructure.persistence.entity.TrustSnapshotEntity;
import com.parkio.parking.infrastructure.persistence.jpa.TrustSnapshotJpaRepository;
import com.parkio.parking.infrastructure.persistence.trust.TrustPersistenceMapper;
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSubject;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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
        return jpa.findBySubjectTypeAndSubjectIdAndTrustDomain(subject.type().name(), subject.subjectId(), domain.name())
                .map(mapper::toDomain);
    }

    @Override
    public void upsert(TrustSnapshot snapshot) {
        persist(snapshot, jpa.findBySubjectTypeAndSubjectIdAndTrustDomain(
                snapshot.subject().type().name(),
                snapshot.subject().subjectId(),
                snapshot.domain().name()));
    }

    @Override
    public void replaceLocked(TrustSubject subject, TrustDomain domain, Supplier<TrustSnapshot> nextSnapshot) {
        Optional<TrustSnapshotEntity> existing = jpa.lockBySubjectTypeAndSubjectIdAndTrustDomain(
                subject.type().name(),
                subject.subjectId(),
                domain.name());
        persist(nextSnapshot.get(), existing);
    }

    private void persist(TrustSnapshot snapshot, Optional<TrustSnapshotEntity> existing) {
        try {
            if (existing.isPresent()) {
                TrustSnapshotEntity current = existing.get();
                TrustSnapshotEntity updated =
                        mapper.toEntity(snapshot, current.getCreatedAt(), clock.instant(), current.getVersion());
                jpa.save(updated);
                jpa.flush();
                return;
            }
            jpa.save(mapper.toEntity(snapshot, clock.instant(), clock.instant(), null));
            jpa.flush();
        } catch (OptimisticLockingFailureException
                | PessimisticLockingFailureException
                | DataIntegrityViolationException ex) {
            throw new TrustShadowProjectionConflictException("Concurrent trust snapshot update", ex);
        }
    }
}
