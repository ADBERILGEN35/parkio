package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.ErasureServiceAckEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasureServiceAckJpaRepository
        extends JpaRepository<ErasureServiceAckEntity, ErasureServiceAckEntity.Key> {

    List<ErasureServiceAckEntity> findByErasureRequestId(UUID erasureRequestId);

    long countByErasureRequestIdAndStatus(UUID erasureRequestId, String status);
}
