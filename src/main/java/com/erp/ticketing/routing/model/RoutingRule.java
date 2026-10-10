package com.erp.ticketing.routing.model;

import com.erp.ticketing.common.BaseEntity;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketType;
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

/**
 * A single row in the ordered routing table consulted by
 * {@code RoutingRuleService.resolveRouting}. {@code ticketType}/{@code category}/
 * {@code priority} are each either a specific value to match, or null to act as
 * a wildcard that matches any ticket.
 */
@Entity
@Table(name = "routing_rules")
@Getter
@Setter
public class RoutingRule extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "order_index", nullable = false)
    private int orderIndex = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_type_id")
    private TicketType ticketType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 2)
    private TicketPriority priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
