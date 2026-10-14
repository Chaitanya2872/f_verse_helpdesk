package com.erp.ticketing.automation.model;

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
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A trigger, conditions and a whitelisted list of actions (BRS 13.1). Disabled until an admin turns it on. */
@Entity
@Table(name = "automation_rules")
@Getter
@Setter
public class AutomationRule extends BaseEntity {

    public enum Trigger { TICKET_CREATED, STATUS_CHANGED, COMMENT_ADDED }

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_event", nullable = false, length = 30)
    private Trigger trigger;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> conditions = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions_json", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> actions = List.of();

    @Column(name = "max_runs_per_ticket", nullable = false)
    private int maxRunsPerTicket = 3;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
