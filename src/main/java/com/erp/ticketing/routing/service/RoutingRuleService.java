package com.erp.ticketing.routing.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.routing.dto.RoutingResolution;
import com.erp.ticketing.routing.dto.RoutingRuleRequest;
import com.erp.ticketing.routing.dto.RoutingRuleResponse;
import com.erp.ticketing.routing.model.RoutingRule;
import com.erp.ticketing.routing.repository.RoutingRuleRepository;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RoutingRuleService {

    private final RoutingRuleRepository routingRuleRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final CategoryRepository categoryRepository;
    private final DepartmentRepository departmentRepository;
    private final UserService userService;
    private final AuditService auditService;

    public RoutingRuleService(RoutingRuleRepository routingRuleRepository,
                               TicketTypeRepository ticketTypeRepository,
                               CategoryRepository categoryRepository,
                               DepartmentRepository departmentRepository,
                               UserService userService,
                               AuditService auditService) {
        this.auditService = auditService;
        this.routingRuleRepository = routingRuleRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.categoryRepository = categoryRepository;
        this.departmentRepository = departmentRepository;
        this.userService = userService;
    }

    /**
     * Fetches active rules ordered by {@code orderIndex} ascending and returns
     * the first whose ticketType/category/priority are each either null
     * (wildcard) or equal to the ticket's actual values. Either departmentId or
     * assigneeId on the match may still be null.
     */
    @Transactional(readOnly = true)
    public Optional<RoutingResolution> resolveRouting(TicketType type, Category category, TicketPriority priority) {
        List<RoutingRule> rules = routingRuleRepository.findByActiveTrueOrderByOrderIndexAsc();
        for (RoutingRule rule : rules) {
            if (matches(rule, type, category, priority)) {
                Long departmentId = rule.getDepartment() != null ? rule.getDepartment().getId() : null;
                Long assigneeId = rule.getAssignee() != null ? rule.getAssignee().getId() : null;
                return Optional.of(new RoutingResolution(departmentId, assigneeId));
            }
        }
        return Optional.empty();
    }

    private boolean matches(RoutingRule rule, TicketType type, Category category, TicketPriority priority) {
        if (rule.getTicketType() != null && (type == null || !rule.getTicketType().getId().equals(type.getId()))) {
            return false;
        }
        if (rule.getCategory() != null && (category == null || !rule.getCategory().getId().equals(category.getId()))) {
            return false;
        }
        if (rule.getPriority() != null && rule.getPriority() != priority) {
            return false;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<RoutingRuleResponse> list() {
        return routingRuleRepository.findAllByOrderByOrderIndexAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public RoutingRuleResponse create(RoutingRuleRequest request) {
        RoutingRule rule = new RoutingRule();
        applyRequest(rule, request);
        rule = routingRuleRepository.save(rule);
        auditService.recordForCurrentUser("ROUTING_RULE_CREATED", "ROUTING_RULE", rule.getId(), null, snapshot(rule));
        return toResponse(rule);
    }

    @Transactional
    public RoutingRuleResponse update(Long id, RoutingRuleRequest request) {
        RoutingRule rule = routingRuleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Routing rule not found: " + id));
        java.util.Map<String, Object> before = snapshot(rule);
        applyRequest(rule, request);
        rule = routingRuleRepository.save(rule);
        auditService.recordForCurrentUser("ROUTING_RULE_UPDATED", "ROUTING_RULE", id, before, snapshot(rule));
        return toResponse(rule);
    }

    private java.util.Map<String, Object> snapshot(RoutingRule r) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", r.getName());
        m.put("orderIndex", r.getOrderIndex());
        m.put("ticketTypeId", r.getTicketType() != null ? r.getTicketType().getId() : null);
        m.put("categoryId", r.getCategory() != null ? r.getCategory().getId() : null);
        m.put("priority", r.getPriority() != null ? r.getPriority().name() : null);
        m.put("departmentId", r.getDepartment() != null ? r.getDepartment().getId() : null);
        m.put("assigneeId", r.getAssignee() != null ? r.getAssignee().getId() : null);
        m.put("active", r.isActive());
        return m;
    }

    private void applyRequest(RoutingRule rule, RoutingRuleRequest request) {
        if (request.name() != null) {
            rule.setName(request.name());
        }
        if (request.orderIndex() != null) {
            rule.setOrderIndex(request.orderIndex());
        }
        if (request.ticketTypeId() != null) {
            TicketType type = ticketTypeRepository.findById(request.ticketTypeId())
                    .orElseThrow(() -> new NotFoundException("Ticket type not found: " + request.ticketTypeId()));
            rule.setTicketType(type);
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
            rule.setCategory(category);
        }
        if (request.priority() != null) {
            rule.setPriority(parsePriority(request.priority()));
        }
        if (request.departmentId() != null) {
            Department department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new NotFoundException("Department not found: " + request.departmentId()));
            rule.setDepartment(department);
        }
        if (request.assigneeId() != null) {
            User assignee = userService.getRequiredUser(request.assigneeId());
            rule.setAssignee(assignee);
        }
        if (request.isActive() != null) {
            rule.setActive(request.isActive());
        }
    }

    private TicketPriority parsePriority(String value) {
        try {
            return TicketPriority.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new com.erp.ticketing.common.BadRequestException("Invalid priority: " + value);
        }
    }

    private RoutingRuleResponse toResponse(RoutingRule rule) {
        return new RoutingRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getOrderIndex(),
                rule.getTicketType() != null ? rule.getTicketType().getId() : null,
                rule.getCategory() != null ? rule.getCategory().getId() : null,
                rule.getPriority() != null ? rule.getPriority().name() : null,
                rule.getDepartment() != null ? rule.getDepartment().getId() : null,
                rule.getAssignee() != null ? rule.getAssignee().getId() : null,
                rule.isActive());
    }
}
