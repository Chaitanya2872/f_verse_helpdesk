package com.erp.ticketing.csat.model;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "csat_surveys")
@Getter
@Setter
public class CsatSurvey extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, unique = true)
    private Ticket ticket;

    /** Denormalized snapshot of ticket.requester at (re)generation time. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "rating")
    private Integer rating;

    @Column(name = "comment")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CsatStatus status = CsatStatus.PENDING;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCsatCreate() {
        Instant now = Instant.now();
        if (getCreatedAt() == null) {
            setCreatedAt(now);
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onCsatUpdate() {
        updatedAt = Instant.now();
    }
}
