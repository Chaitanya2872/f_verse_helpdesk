package com.erp.ticketing.automation.model;

import com.erp.ticketing.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/** One evaluation of a rule against a ticket, kept for audit and to enforce the per-ticket run limit. */
@Entity
@Table(name = "automation_runs")
@Getter
@Setter
public class AutomationRun extends BaseEntity {

    public enum Result { APPLIED, NO_MATCH, FAILED, SKIPPED_LIMIT }

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private Result result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions_json", columnDefinition = "jsonb")
    private List<Map<String, Object>> actions;

    @Column(name = "error")
    private String error;
}
