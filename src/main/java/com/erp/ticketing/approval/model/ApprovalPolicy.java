package com.erp.ticketing.approval.model;

import com.erp.ticketing.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Who must approve tickets of a request type, and how (BRS 10.2). A null
 * {@code ticketTypeId} policy is the fallback for types without their own.
 * {@code version} is bumped on every edit and copied onto each task so history
 * keeps the rules that were in force at the time.
 */
@Entity
@Table(name = "approval_policies")
@Getter
@Setter
public class ApprovalPolicy extends BaseEntity {

    public enum Mode { SINGLE, SEQUENTIAL, PARALLEL }

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "ticket_type_id")
    private Long ticketTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private Mode mode = Mode.SINGLE;

    @Column(name = "timeout_hours", nullable = false)
    private int timeoutHours = 72;

    @Column(name = "block_self_approval", nullable = false)
    private boolean blockSelfApproval = true;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC, id ASC")
    private List<ApprovalPolicyStep> steps = new ArrayList<>();
}
