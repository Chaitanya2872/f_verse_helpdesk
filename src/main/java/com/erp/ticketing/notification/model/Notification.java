package com.erp.ticketing.notification.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "body")
    private String body;

    @Column(name = "is_sent", nullable = false)
    private boolean sent = false;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "sent_at")
    private Instant sentAt;
}
