package com.erp.ticketing.ticket.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One row per ownership change: previous and new department/assignee, actor, reason (BRS 6.4). */
@Entity
@Table(name = "ticket_assignments")
@Getter
@Setter
public class TicketAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_department_id")
    private Department fromDepartment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_department_id")
    private Department toDepartment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_assignee_id")
    private User fromAssignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_assignee_id")
    private User toAssignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "reason", length = 500)
    private String reason;
}
