package com.erp.ticketing.itsm.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.ticket.model.Level;
import com.erp.ticketing.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A planned change with its plans, schedule and approval (BRS 16.3). */
@Entity
@Table(name = "change_requests")
@Getter
@Setter
public class ChangeRequest extends BaseEntity {

    public enum Type { STANDARD, NORMAL, EMERGENCY }

    public enum Status { DRAFT, SUBMITTED, APPROVED, REJECTED, SCHEDULED, IMPLEMENTING, COMPLETED, FAILED, CANCELLED }

    @Column(name = "change_number", nullable = false, unique = true, length = 30)
    private String changeNumber;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private Type changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "reason")
    private String reason;

    @Column(name = "scope")
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk", length = 10)
    private Level risk;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact", length = 10)
    private Level impact;

    /** Comma-separated service names; two scheduled changes touching the same one may not overlap. */
    @Column(name = "affected_services")
    private String affectedServices;

    @Column(name = "implementation_plan")
    private String implementationPlan;

    @Column(name = "test_plan")
    private String testPlan;

    @Column(name = "rollback_plan")
    private String rollbackPlan;

    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "scheduled_end")
    private Instant scheduledEnd;

    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    @Column(name = "outcome")
    private String outcome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_comment")
    private String approvalComment;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
