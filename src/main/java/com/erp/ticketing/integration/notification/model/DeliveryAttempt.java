package com.erp.ticketing.integration.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One hand-over attempt for an outbox row, kept so a failure can be explained after the fact. */
@Entity
@Table(name = "notification_delivery_attempts")
@Getter
@Setter
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outbox_id", nullable = false, updatable = false)
    private Long outboxId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "outcome", nullable = false, updatable = false)
    private String outcome;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "detail", updatable = false)
    private String detail;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt = Instant.now();
}
