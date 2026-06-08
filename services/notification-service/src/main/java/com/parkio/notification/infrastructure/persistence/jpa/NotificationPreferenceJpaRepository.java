package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.NotificationPreferenceEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceJpaRepository
        extends JpaRepository<NotificationPreferenceEntity, UUID> {
}
