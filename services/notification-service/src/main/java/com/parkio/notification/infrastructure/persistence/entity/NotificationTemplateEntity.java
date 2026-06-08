package com.parkio.notification.infrastructure.persistence.entity;

import com.parkio.notification.domain.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA mapping for {@code notification_templates} (seeded reference data). */
@Entity
@Table(name = "notification_templates")
public class NotificationTemplateEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "title_template", nullable = false)
    private String titleTemplate;

    @Column(name = "body_template", nullable = false)
    private String bodyTemplate;

    protected NotificationTemplateEntity() {
        // for JPA
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitleTemplate() {
        return titleTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }
}
