package com.erp.ticketing.itsm.model;

import com.erp.ticketing.common.BaseEntity;
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

/** Coordinated response to a serious outage, grouping the tickets raised about it (BRS 8.3). */
@Entity
@Table(name = "major_incidents")
@Getter
@Setter
public class MajorIncident extends BaseEntity {

    public enum Status { DECLARED, MITIGATED, RESOLVED, CLOSED }

    public enum Severity { SEV1, SEV2, SEV3 }

    @Column(name = "incident_number", nullable = false, unique = true, length = 30)
    private String incidentNumber;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DECLARED;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private Severity severity;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "scope")
    private String scope;

    @Column(name = "affected_services")
    private String affectedServices;

    @Column(name = "customer_impact")
    private String customerImpact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_lead_id")
    private User incidentLead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comms_lead_id")
    private User commsLead;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "mitigated_at")
    private Instant mitigatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "update_cadence_minutes", nullable = false)
    private int updateCadenceMinutes = 30;

    @Column(name = "next_update_due_at")
    private Instant nextUpdateDueAt;

    @Column(name = "post_incident_review")
    private String postIncidentReview;

    @Column(name = "action_items")
    private String actionItems;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_by")
    private User declaredBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
