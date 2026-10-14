package com.erp.ticketing.automation.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.automation.dto.AutomationDto;
import com.erp.ticketing.automation.model.AutomationRule;
import com.erp.ticketing.automation.model.AutomationRun;
import com.erp.ticketing.automation.repository.AutomationRuleRepository;
import com.erp.ticketing.automation.repository.AutomationRunRepository;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketAssignment;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.repository.TicketAssignmentRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.service.ActivityLogService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Rule engine (BRS 13). Rules react to ticket events and may only use the whitelisted actions
 * below - no status transitions to Resolved/Closed, no approvals, no access or financial effects.
 *
 * <p>Safeguards: actions never trigger further rules (a per-thread guard), each rule runs at most
 * {@code maxRunsPerTicket} times on a ticket, a failing rule is recorded and never breaks the
 * ticket operation that fired it, and rules can be dry-run against a real ticket without effect.
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);

    /** Set while a rule's actions are being applied, so their side effects cannot fire more rules. */
    private static final ThreadLocal<Boolean> RUNNING = ThreadLocal.withInitial(() -> false);

    private static final Set<String> CONDITION_KEYS =
            Set.of("typeId", "categoryId", "priority", "status", "departmentId", "textContains", "byRequester");
    private static final Set<String> ACTION_TYPES = Set.of("SET_PRIORITY", "ASSIGN_DEPARTMENT", "ASSIGN_USER",
            "ADD_TAG", "NOTIFY_USER", "NOTIFY_DEPARTMENT_MANAGER", "SET_SUB_STATUS");

    private final AutomationRuleRepository ruleRepository;
    private final AutomationRunRepository runRepository;
    private final TicketRepository ticketRepository;
    private final TicketAssignmentRepository assignmentRepository;
    private final DepartmentRepository departmentRepository;
    private final UserService userService;
    private final ActivityLogService activityLogService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public AutomationService(AutomationRuleRepository ruleRepository, AutomationRunRepository runRepository,
                             TicketRepository ticketRepository, TicketAssignmentRepository assignmentRepository,
                             DepartmentRepository departmentRepository, UserService userService,
                             ActivityLogService activityLogService, NotificationService notificationService,
                             AuditService auditService) {
        this.ruleRepository = ruleRepository;
        this.runRepository = runRepository;
        this.ticketRepository = ticketRepository;
        this.assignmentRepository = assignmentRepository;
        this.departmentRepository = departmentRepository;
        this.userService = userService;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ engine

    /** Evaluates every enabled rule for {@code trigger} against the ticket, in id order. */
    @Transactional
    public void fire(AutomationRule.Trigger trigger, Ticket ticket, Map<String, Object> context) {
        if (RUNNING.get()) {
            return;
        }
        List<AutomationRule> rules = ruleRepository.findByEnabledTrueAndTriggerOrderByIdAsc(trigger);
        if (rules.isEmpty()) {
            return;
        }
        RUNNING.set(true);
        try {
            for (AutomationRule rule : rules) {
                runOne(rule, ticket, context == null ? Map.of() : context);
            }
        } finally {
            RUNNING.set(false);
        }
    }

    private void runOne(AutomationRule rule, Ticket ticket, Map<String, Object> context) {
        if (!matches(rule.getConditions(), ticket, context)) {
            record(rule, ticket, false, AutomationRun.Result.NO_MATCH, null, null);
            return;
        }
        long done = runRepository.countByRuleIdAndTicketIdAndResultAndDryRunFalse(
                rule.getId(), ticket.getId(), AutomationRun.Result.APPLIED);
        if (done >= rule.getMaxRunsPerTicket()) {
            record(rule, ticket, false, AutomationRun.Result.SKIPPED_LIMIT, null, null);
            return;
        }
        try {
            List<Map<String, Object>> applied = new ArrayList<>();
            for (Map<String, Object> action : rule.getActions()) {
                apply(rule, action, ticket);
                applied.add(action);
            }
            ticketRepository.save(ticket);
            record(rule, ticket, false, AutomationRun.Result.APPLIED, applied, null);
        } catch (RuntimeException ex) {
            log.warn("Automation rule failed", kv("ruleId", rule.getId()), kv("ticketNumber", ticket.getTicketNumber()), ex);
            record(rule, ticket, false, AutomationRun.Result.FAILED, null, ex.getMessage());
        }
    }

    private boolean matches(Map<String, Object> c, Ticket t, Map<String, Object> context) {
        if (c.get("typeId") != null && !num(c.get("typeId")).equals(t.getType().getId())) {
            return false;
        }
        if (c.get("categoryId") != null
                && (t.getCategory() == null || !num(c.get("categoryId")).equals(t.getCategory().getId()))) {
            return false;
        }
        if (c.get("priority") != null && !c.get("priority").toString().equalsIgnoreCase(t.getPriority().name())) {
            return false;
        }
        if (c.get("status") != null && !c.get("status").toString().equalsIgnoreCase(t.getStatus().name())) {
            return false;
        }
        if (c.get("departmentId") != null && !num(c.get("departmentId")).equals(t.getDepartment().getId())) {
            return false;
        }
        if (c.get("textContains") != null) {
            String needle = c.get("textContains").toString().toLowerCase(Locale.ROOT);
            String hay = (t.getTitle() + " " + (t.getDescription() == null ? "" : t.getDescription())).toLowerCase(Locale.ROOT);
            if (!hay.contains(needle)) {
                return false;
            }
        }
        if (c.get("byRequester") != null && !Boolean.valueOf(String.valueOf(c.get("byRequester")))
                .equals(context.getOrDefault("byRequester", Boolean.FALSE))) {
            return false;
        }
        return true;
    }

    private void apply(AutomationRule rule, Map<String, Object> action, Ticket ticket) {
        String type = String.valueOf(action.get("type"));
        String who = "Automation: " + rule.getName();
        switch (type) {
            case "SET_PRIORITY" -> {
                TicketPriority p = TicketPriority.valueOf(String.valueOf(action.get("value")).toUpperCase(Locale.ROOT));
                if (p != ticket.getPriority()) {
                    activityLogService.logFieldChanged(ticket, null, "priority", ticket.getPriority().name(), p.name() + " (" + who + ")");
                    ticket.setPriority(p);
                }
            }
            case "ASSIGN_DEPARTMENT" -> {
                Department d = departmentRepository.findById(num(action.get("departmentId")))
                        .orElseThrow(() -> new NotFoundException("Department not found"));
                if (!d.getId().equals(ticket.getDepartment().getId())) {
                    Department from = ticket.getDepartment();
                    ticket.setDepartment(d);
                    recordAssignment(ticket, from, ticket.getAssignee(), who);
                }
            }
            case "ASSIGN_USER" -> {
                User u = userService.getRequiredUser(num(action.get("userId")));
                if (ticket.getAssignee() == null || !ticket.getAssignee().getId().equals(u.getId())) {
                    User from = ticket.getAssignee();
                    ticket.setAssignee(u);
                    recordAssignment(ticket, ticket.getDepartment(), from, who);
                }
            }
            case "ADD_TAG" -> {
                Map<String, Object> tags = new LinkedHashMap<>(ticket.getTags() == null ? Map.of() : ticket.getTags());
                tags.put(String.valueOf(action.get("tag")), Boolean.TRUE);
                ticket.setTags(tags);
            }
            case "SET_SUB_STATUS" -> ticket.setSubStatus(String.valueOf(action.get("value")));
            case "NOTIFY_USER" -> notificationService.notify(userService.getRequiredUser(num(action.get("userId"))), ticket,
                    "AUTOMATION", "Ticket " + ticket.getTicketNumber(), who + " - " + ticket.getTitle());
            case "NOTIFY_DEPARTMENT_MANAGER" -> {
                User m = ticket.getDepartment().getManager();
                if (m != null) {
                    notificationService.notify(m, ticket, "AUTOMATION", "Ticket " + ticket.getTicketNumber(),
                            who + " - " + ticket.getTitle());
                }
            }
            default -> throw new BadRequestException("Unsupported action: " + type);
        }
    }

    private void recordAssignment(Ticket ticket, Department fromDept, User fromAssignee, String reason) {
        TicketAssignment row = new TicketAssignment();
        row.setTicket(ticket);
        row.setFromDepartment(fromDept);
        row.setToDepartment(ticket.getDepartment());
        row.setFromAssignee(fromAssignee);
        row.setToAssignee(ticket.getAssignee());
        row.setReason(reason);
        assignmentRepository.save(row);
        activityLogService.logFieldChanged(ticket, null, "assignment", null, reason);
    }

    private void record(AutomationRule rule, Ticket ticket, boolean dry, AutomationRun.Result result,
                        List<Map<String, Object>> actions, String error) {
        AutomationRun run = new AutomationRun();
        run.setRuleId(rule.getId());
        run.setRuleVersion(rule.getVersion());
        run.setTicketId(ticket.getId());
        run.setDryRun(dry);
        run.setResult(result);
        run.setActions(actions);
        run.setError(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
        runRepository.save(run);
    }

    private static Long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Expected a numeric id but got: " + o);
        }
    }

    // -------------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public List<AutomationDto.Rule> list() {
        return ruleRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public AutomationDto.Rule create(AutomationDto.SaveRequest request) {
        AutomationRule rule = new AutomationRule();
        apply(rule, request);
        rule = ruleRepository.save(rule);
        auditService.recordForCurrentUser("AUTOMATION_RULE_CREATED", "AUTOMATION_RULE", rule.getId(), null, snapshot(rule));
        return toDto(rule);
    }

    @Transactional
    public AutomationDto.Rule update(Long id, AutomationDto.SaveRequest request) {
        AutomationRule rule = require(id);
        Map<String, Object> before = snapshot(rule);
        apply(rule, request);
        rule.setVersion(rule.getVersion() + 1);
        rule.setUpdatedAt(Instant.now());
        rule = ruleRepository.save(rule);
        auditService.recordForCurrentUser("AUTOMATION_RULE_UPDATED", "AUTOMATION_RULE", id, before, snapshot(rule));
        return toDto(rule);
    }

    @Transactional
    public AutomationDto.Rule setEnabled(Long id, boolean enabled) {
        AutomationRule rule = require(id);
        Map<String, Object> before = snapshot(rule);
        rule.setEnabled(enabled);
        rule = ruleRepository.save(rule);
        auditService.recordForCurrentUser(enabled ? "AUTOMATION_RULE_ENABLED" : "AUTOMATION_RULE_DISABLED",
                "AUTOMATION_RULE", id, before, snapshot(rule));
        return toDto(rule);
    }

    /** Shows what the rule would do to a real ticket without changing anything (BRS 13.2). */
    @Transactional
    public AutomationDto.DryRunResult dryRun(Long ruleId, Long ticketId) {
        AutomationRule rule = require(ruleId);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        boolean match = matches(rule.getConditions(), ticket, Map.of());
        record(rule, ticket, true, match ? AutomationRun.Result.APPLIED : AutomationRun.Result.NO_MATCH,
                match ? rule.getActions() : null, null);
        return new AutomationDto.DryRunResult(match, match ? rule.getActions() : List.of());
    }

    @Transactional(readOnly = true)
    public List<AutomationDto.Run> runs(Long ruleId) {
        require(ruleId);
        return runRepository.findByRuleIdOrderByIdDesc(ruleId, PageRequest.of(0, 50)).stream()
                .map(r -> new AutomationDto.Run(r.getId(), r.getRuleVersion(), r.getTicketId(), r.isDryRun(),
                        r.getResult().name(), r.getActions(), r.getError(), r.getCreatedAt()))
                .toList();
    }

    private AutomationRule require(Long id) {
        return ruleRepository.findById(id).orElseThrow(() -> new NotFoundException("Automation rule not found: " + id));
    }

    private void apply(AutomationRule rule, AutomationDto.SaveRequest r) {
        if (r.name() == null || r.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        try {
            rule.setTrigger(AutomationRule.Trigger.valueOf(r.trigger().trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("trigger must be TICKET_CREATED, STATUS_CHANGED or COMMENT_ADDED");
        }
        Map<String, Object> conditions = r.conditions() == null ? Map.of() : r.conditions();
        for (String key : conditions.keySet()) {
            if (!CONDITION_KEYS.contains(key)) {
                throw new BadRequestException("Unknown condition: " + key);
            }
        }
        if (r.actions() == null || r.actions().isEmpty()) {
            throw new BadRequestException("At least one action is required");
        }
        for (Map<String, Object> action : r.actions()) {
            validateAction(action);
        }
        rule.setName(r.name().trim());
        rule.setConditions(conditions);
        rule.setActions(r.actions());
        if (r.enabled() != null) {
            rule.setEnabled(r.enabled());
        }
        if (r.maxRunsPerTicket() != null) {
            if (r.maxRunsPerTicket() < 1 || r.maxRunsPerTicket() > 20) {
                throw new BadRequestException("maxRunsPerTicket must be between 1 and 20");
            }
            rule.setMaxRunsPerTicket(r.maxRunsPerTicket());
        }
        if (rule.getOwner() == null) {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof com.erp.ticketing.security.ErpUserPrincipal p) {
                rule.setOwner(userService.getOrCreateCurrentUser(p));
            }
        }
    }

    private void validateAction(Map<String, Object> action) {
        String type = String.valueOf(action.get("type"));
        if (!ACTION_TYPES.contains(type)) {
            throw new BadRequestException("Action not allowed in automation: " + type);
        }
        switch (type) {
            case "SET_PRIORITY" -> {
                try {
                    TicketPriority.valueOf(String.valueOf(action.get("value")).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new BadRequestException("SET_PRIORITY needs value P1-P4");
                }
            }
            case "ASSIGN_DEPARTMENT" -> departmentRepository.findById(num(action.get("departmentId")))
                    .orElseThrow(() -> new BadRequestException("ASSIGN_DEPARTMENT: department not found"));
            case "ASSIGN_USER", "NOTIFY_USER" -> userService.getRequiredUser(num(action.get("userId")));
            case "ADD_TAG" -> {
                if (action.get("tag") == null || action.get("tag").toString().isBlank()) {
                    throw new BadRequestException("ADD_TAG needs a tag");
                }
            }
            case "SET_SUB_STATUS" -> {
                if (action.get("value") == null || action.get("value").toString().isBlank()) {
                    throw new BadRequestException("SET_SUB_STATUS needs a value");
                }
            }
            default -> {
            }
        }
    }

    private Map<String, Object> snapshot(AutomationRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.getName());
        m.put("enabled", r.isEnabled());
        m.put("trigger", r.getTrigger() == null ? null : r.getTrigger().name());
        m.put("conditions", r.getConditions());
        m.put("actions", r.getActions());
        m.put("maxRunsPerTicket", r.getMaxRunsPerTicket());
        m.put("version", r.getVersion());
        return m;
    }

    private AutomationDto.Rule toDto(AutomationRule r) {
        return new AutomationDto.Rule(r.getId(), r.getName(), r.getOwner() != null ? r.getOwner().getName() : null,
                r.isEnabled(), r.getTrigger().name(), r.getConditions(), r.getActions(), r.getMaxRunsPerTicket(),
                r.getVersion(), r.getUpdatedAt());
    }
}
