package com.erp.ticketing.integration.notification.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.integration.notification.DeliveryChannel;
import com.erp.ticketing.integration.notification.NotificationEventType;
import com.erp.ticketing.integration.notification.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One message to hand to the notification-service: a recipient, a channel and the composed content. */
@Entity
@Table(name = "notification_outbox")
@Getter
@Setter
public class NotificationOutbox extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private NotificationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private DeliveryChannel channel;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private Long ticketId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "recipient_email")
    private String recipientEmail;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body_html")
    private String bodyHtml;

    @Column(name = "body_text")
    private String bodyText;

    /** WHATSAPP only: the approved Meta template, e.g. {@code ticket_created}. */
    @Column(name = "template_name", updatable = false)
    private String templateName;

    /** WHATSAPP only: the template's language, e.g. {@code en_US}. */
    @Column(name = "template_language", updatable = false)
    private String templateLanguage;

    /** WHATSAPP only: JSON of the body variables (in order) and URL-button suffixes, fixed when recorded. */
    @Column(name = "template_params", updatable = false)
    private String templateParams;

    /** WHATSAPP only: the number the message went to, in international digits, resolved at dispatch time. */
    @Column(name = "recipient_phone")
    private String recipientPhone;

    /** WHATSAPP only: Meta's message id once accepted. Acceptance is not a delivery receipt. */
    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
