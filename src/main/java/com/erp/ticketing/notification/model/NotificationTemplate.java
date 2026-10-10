package com.erp.ticketing.notification.model;

import com.erp.ticketing.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Admin-editable wording for a notification event type. One row per
 * event_type (seeded by migration V8); event_type itself is immutable once
 * seeded - see {@code NotificationTemplateAdminController}. Rendering
 * ({{placeholder}} substitution) lives in {@code NotificationService}.
 */
@Entity
@Table(name = "notification_templates")
@Getter
@Setter
public class NotificationTemplate extends BaseEntity {

    @Column(name = "event_type", nullable = false, unique = true, length = 50)
    private String eventType;

    @Column(name = "subject_template", nullable = false, length = 500)
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false)
    private String bodyTemplate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onTemplateCreate() {
        Instant now = Instant.now();
        if (getCreatedAt() == null) {
            setCreatedAt(now);
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onTemplateUpdate() {
        updatedAt = Instant.now();
    }
}
