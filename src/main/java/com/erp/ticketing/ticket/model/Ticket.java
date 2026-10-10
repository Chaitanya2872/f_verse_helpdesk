package com.erp.ticketing.ticket.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket extends BaseEntity {

    @Column(name = "ticket_number", nullable = false, unique = true, length = 30)
    private String ticketNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id", nullable = false)
    private TicketType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 2)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TicketStatus status = TicketStatus.NEW;

    @Column(name = "sub_status", length = 50)
    private String subStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(name = "sla_policy_id")
    private Long slaPolicyId;

    @Column(name = "first_response_due")
    private Instant firstResponseDue;

    @Column(name = "resolution_due")
    private Instant resolutionDue;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached = false;

    @Column(name = "sla_warning_sent", nullable = false)
    private boolean slaWarningSent = false;

    @Column(name = "sla_breach_notified", nullable = false)
    private boolean slaBreachNotified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private TicketSource source = TicketSource.PORTAL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Map<String, Object> tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onTicketCreate() {
        Instant now = Instant.now();
        if (getCreatedAt() == null) {
            setCreatedAt(now);
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onTicketUpdate() {
        updatedAt = Instant.now();
    }
}
