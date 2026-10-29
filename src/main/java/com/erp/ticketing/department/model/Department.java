package com.erp.ticketing.department.model;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "departments")
@Getter
@Setter
public class Department extends BaseEntity {

    @Column(name = "department_name", nullable = false)
    private String departmentName;

    /** Short prefix for ticket numbers, e.g. "IT" gives IT-2026-000036. */
    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "description")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private User manager;

    /**
     * Admin metadata only - not currently consulted by SlaCalculationService,
     * which resolves a ticket's SLA policy purely by ticket type/priority.
     */
    @Column(name = "sla_policy_id")
    private Long slaPolicyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_hours_json", columnDefinition = "jsonb")
    private Map<String, Object> workingHoursJson;
}
