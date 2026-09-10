package com.parkio.auth.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "erasure_service_acks")
@IdClass(ErasureServiceAckEntity.Key.class)
public class ErasureServiceAckEntity {

    @Id
    @Column(name = "erasure_request_id", nullable = false)
    private UUID erasureRequestId;

    @Id
    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "acked_at", nullable = false)
    private Instant ackedAt;

    protected ErasureServiceAckEntity() {
    }

    public ErasureServiceAckEntity(UUID erasureRequestId, String serviceName, String status, Instant ackedAt) {
        this.erasureRequestId = erasureRequestId;
        this.serviceName = serviceName;
        this.status = status;
        this.ackedAt = ackedAt;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getStatus() {
        return status;
    }

    public static final class Key implements Serializable {
        private UUID erasureRequestId;
        private String serviceName;

        public Key() {
        }

        public Key(UUID erasureRequestId, String serviceName) {
            this.erasureRequestId = erasureRequestId;
            this.serviceName = serviceName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(erasureRequestId, key.erasureRequestId)
                    && Objects.equals(serviceName, key.serviceName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(erasureRequestId, serviceName);
        }
    }
}
