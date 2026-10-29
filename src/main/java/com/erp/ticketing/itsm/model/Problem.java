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
import java.time.LocalDate;

/** Root-cause record grouping related incidents; its lifecycle is separate from any one ticket (BRS 16.2). */
@Entity
@Table(name = "problems")
@Getter
@Setter
public class Problem extends BaseEntity {

    public enum Status { OPEN, INVESTIGATING, KNOWN_ERROR, RESOLVED, CLOSED }

    @Column(name = "problem_number", nullable = false, unique = true, length = 30)
    private String problemNumber;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(name = "symptoms")
    private String symptoms;

    @Column(name = "root_cause")
    private String rootCause;

    @Column(name = "workaround")
    private String workaround;

    @Column(name = "permanent_fix")
    private String permanentFix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "review_date")
    private LocalDate reviewDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
