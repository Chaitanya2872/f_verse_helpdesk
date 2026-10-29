package com.erp.ticketing.approval.model;

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

/**
 * One approver's decision slot. {@code approver} is who the policy named;
 * {@code actedBy} is who actually decided.
 * QUEUED tasks belong to later steps of a sequential policy and are not yet actionable.
 */
@Entity
@Table(name = "approval_tasks")
@Getter
@Setter
public class ApprovalTask extends BaseEntity {

    public enum Status { QUEUED, PENDING, APPROVED, REJECTED, CHANGES_REQUESTED, WITHDRAWN, EXPIRED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    private ApprovalPolicy policy;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "step_order", nullable = false)
    private int stepOrder = 1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approver_id", nullable = false)
    private User approver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acted_by_id")
    private User actedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "decision_comment")
    private String decisionComment;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    public boolean isOpen() {
        return status == Status.PENDING || status == Status.QUEUED;
    }
}
