package com.erp.ticketing.ticket.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Explicit per-user access to a restricted/confidential ticket (BRS 7.1). */
@Entity
@Table(name = "ticket_access_grants")
@Getter
@Setter
public class TicketAccessGrant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private User grantedBy;

    @Column(name = "reason", length = 500)
    private String reason;

    /** Null means no expiry; emergency access should always carry one (BRS 3.1). */
    @Column(name = "expires_at")
    private java.time.Instant expiresAt;
}
