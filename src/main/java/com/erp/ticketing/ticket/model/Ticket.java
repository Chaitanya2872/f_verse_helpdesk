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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    private User creator;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidentiality", nullable = false, length = 20)
    private Confidentiality confidentiality = Confidentiality.STANDARD;

    @Column(name = "resolution_code", length = 50)
    private String resolutionCode;

    @Column(name = "resolution_summary")
    private String resolutionSummary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact", length = 10)
    private Level impact;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", length = 10)
    private Level urgency;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

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

    @Column(name = "sla_policy_version")
    private Integer slaPolicyVersion;

    /** Non-null while the SLA clock is paused; the due dates are recomputed from the remaining minutes on resume. */
    @Column(name = "sla_paused_at")
    private Instant slaPausedAt;

    @Column(name = "sla_resolution_remaining_minutes")
    private Long slaResolutionRemainingMinutes;

    @Column(name = "sla_first_response_remaining_minutes")
    private Long slaFirstResponseRemainingMinutes;

    @Column(name = "sla_escalated", nullable = false)
    private boolean slaEscalated = false;

    @Column(name = "form_version_id")
    private Long formVersionId;

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
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
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
