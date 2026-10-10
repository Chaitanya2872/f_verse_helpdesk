package com.erp.ticketing.sla.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * An SLA policy: {@code conditionsJson} is either null/empty (wildcard, matches
 * any ticket) or a map with optional {@code priority} / {@code ticketTypeId}
 * keys that must match the ticket's actual values for the policy to apply.
 * See {@code SlaCalculationService.resolvePolicy}.
 */
@Entity
@Table(name = "sla_policies")
@Getter
@Setter
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions_json", columnDefinition = "jsonb")
    private Map<String, Object> conditionsJson;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id")
    private SlaCalendar calendar;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
