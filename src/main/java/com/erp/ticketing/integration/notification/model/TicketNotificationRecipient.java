package com.erp.ticketing.integration.notification.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.integration.notification.NotificationEventType;
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

/**
 * A user configured (by an admin) to hear about creation or resolution of tickets, optionally only for one
 * department. This is how HR and Admin recipients are chosen - there is no default fan-out to a role.
 */
@Entity
@Table(name = "ticket_notification_recipients")
@Getter
@Setter
public class TicketNotificationRecipient extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private NotificationEventType eventType;

    /** Null means every department. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
