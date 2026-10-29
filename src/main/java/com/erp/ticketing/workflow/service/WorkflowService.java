package com.erp.ticketing.workflow.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.workflow.dto.WorkflowDto;
import com.erp.ticketing.workflow.model.WorkflowTransition;
import com.erp.ticketing.workflow.repository.WorkflowTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "may a ticket of this request type move from A to B" (BRS 10.1).
 * A request type with its own transition rows uses only those; otherwise the
 * default workflow (rows with no type) applies, and if even that is empty the
 * baseline lifecycle below does. Saved workflows are validated before they take
 * effect so an admin cannot create unreachable states or bypass an approval gate.
 */
@Service
public class WorkflowService {

    private static final Set<TicketStatus> TERMINAL = EnumSet.of(TicketStatus.CLOSED, TicketStatus.CANCELLED);

    /** BRS 6.3 baseline; mirrors the rows seeded in migration V16. */
    private static final Map<TicketStatus, Set<TicketStatus>> BASELINE = new EnumMap<>(TicketStatus.class);

    static {
        BASELINE.put(TicketStatus.NEW, EnumSet.of(TicketStatus.TRIAGE, TicketStatus.ASSIGNED,
                TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.TRIAGE,
                EnumSet.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.ASSIGNED, EnumSet.of(TicketStatus.TRIAGE, TicketStatus.IN_PROGRESS,
                TicketStatus.WAITING_CUSTOMER, TicketStatus.WAITING_APPROVAL, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.IN_PROGRESS, EnumSet.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.WAITING_VENDOR,
                TicketStatus.WAITING_APPROVAL, TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.WAITING_CUSTOMER,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.WAITING_VENDOR,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.WAITING_APPROVAL, EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.RESOLVED,
                EnumSet.of(TicketStatus.CLOSED, TicketStatus.REOPENED, TicketStatus.IN_PROGRESS));
        BASELINE.put(TicketStatus.REOPENED, EnumSet.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS,
                TicketStatus.WAITING_CUSTOMER, TicketStatus.WAITING_VENDOR, TicketStatus.RESOLVED,
                TicketStatus.CANCELLED));
        BASELINE.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class));
        BASELINE.put(TicketStatus.CANCELLED, EnumSet.noneOf(TicketStatus.class));
    }

    private final WorkflowTransitionRepository repository;
    private final AuditService auditService;

    public WorkflowService(WorkflowTransitionRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(Long ticketTypeId, TicketStatus from, TicketStatus to) {
        return resolve(ticketTypeId).getOrDefault(from, Set.of()).contains(to);
    }

    @Transactional(readOnly = true)
    public WorkflowDto.Workflow get(Long ticketTypeId) {
        boolean overridden = ticketTypeId != null && !repository.findByTicketTypeId(ticketTypeId).isEmpty();
        return toDto(ticketTypeId, overridden, resolve(ticketTypeId));
    }

    /** Replaces the transitions for a type (or the default workflow when the id is null). */
    @Transactional
    public WorkflowDto.Workflow save(Long ticketTypeId, List<WorkflowDto.Transition> requested,
                                     boolean hasApprovalPolicy, User actor) {
        Map<TicketStatus, Set<TicketStatus>> graph = parse(requested);
        validate(graph, hasApprovalPolicy);

        Map<String, Object> before = describe(resolve(ticketTypeId));
        if (ticketTypeId == null) {
            repository.deleteByTicketTypeIdIsNull();
        } else {
            repository.deleteByTicketTypeId(ticketTypeId);
        }
        repository.flush();
        List<WorkflowTransition> rows = new ArrayList<>();
        graph.forEach((from, targets) -> targets.forEach(to -> {
            WorkflowTransition row = new WorkflowTransition();
            row.setTicketTypeId(ticketTypeId);
            row.setFromStatus(from);
            row.setToStatus(to);
            rows.add(row);
        }));
        repository.saveAll(rows);
        auditService.record(actor, "WORKFLOW_SAVED", "WORKFLOW", ticketTypeId == null ? "default" : ticketTypeId,
                before, describe(graph));
        return toDto(ticketTypeId, ticketTypeId != null, graph);
    }

    /** Drops a type's override so it inherits the default workflow again. */
    @Transactional
    public void revertToDefault(Long ticketTypeId, User actor) {
        Map<String, Object> before = describe(resolve(ticketTypeId));
        repository.deleteByTicketTypeId(ticketTypeId);
        auditService.record(actor, "WORKFLOW_REVERTED", "WORKFLOW", ticketTypeId, before, null);
    }

    private Map<TicketStatus, Set<TicketStatus>> resolve(Long ticketTypeId) {
        if (ticketTypeId != null) {
            List<WorkflowTransition> own = repository.findByTicketTypeId(ticketTypeId);
            if (!own.isEmpty()) {
                return group(own);
            }
        }
        List<WorkflowTransition> defaults = repository.findByTicketTypeIdIsNull();
        return defaults.isEmpty() ? BASELINE : group(defaults);
    }

    private Map<TicketStatus, Set<TicketStatus>> group(List<WorkflowTransition> rows) {
        Map<TicketStatus, Set<TicketStatus>> graph = new EnumMap<>(TicketStatus.class);
        for (WorkflowTransition row : rows) {
            graph.computeIfAbsent(row.getFromStatus(), k -> EnumSet.noneOf(TicketStatus.class)).add(row.getToStatus());
        }
        return graph;
    }

    private Map<TicketStatus, Set<TicketStatus>> parse(List<WorkflowDto.Transition> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new BadRequestException("A workflow needs at least one transition");
        }
        Map<TicketStatus, Set<TicketStatus>> graph = new EnumMap<>(TicketStatus.class);
        for (WorkflowDto.Transition t : requested) {
            TicketStatus from = status(t.from());
            TicketStatus to = status(t.to());
            if (from == to) {
                throw new BadRequestException("A transition cannot leave and enter " + from);
            }
            graph.computeIfAbsent(from, k -> EnumSet.noneOf(TicketStatus.class)).add(to);
        }
        return graph;
    }

    private TicketStatus status(String raw) {
        try {
            return TicketStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Unknown status: " + raw);
        }
    }

    /** BRS 10.1: no unreachable states, no way out of terminal states, no way around the approval gate. */
    private void validate(Map<TicketStatus, Set<TicketStatus>> graph, boolean hasApprovalPolicy) {
        for (TicketStatus terminal : TERMINAL) {
            if (!graph.getOrDefault(terminal, Set.of()).isEmpty()) {
                throw new BadRequestException(terminal + " is final and cannot have outgoing transitions");
            }
        }
        Set<TicketStatus> mentioned = new LinkedHashSet<>(graph.keySet());
        graph.values().forEach(mentioned::addAll);

        Set<TicketStatus> reachable = EnumSet.of(TicketStatus.NEW);
        Deque<TicketStatus> queue = new ArrayDeque<>(reachable);
        while (!queue.isEmpty()) {
            for (TicketStatus next : graph.getOrDefault(queue.poll(), Set.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        for (TicketStatus status : mentioned) {
            if (!reachable.contains(status)) {
                throw new BadRequestException(status + " is unreachable from NEW");
            }
        }
        if (!reachable.contains(TicketStatus.CLOSED) && !reachable.contains(TicketStatus.CANCELLED)) {
            throw new BadRequestException("Tickets must be able to reach CLOSED or CANCELLED");
        }
        if (hasApprovalPolicy) {
            Set<TicketStatus> fromApproval = graph.getOrDefault(TicketStatus.WAITING_APPROVAL, Set.of());
            if (!EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED).containsAll(fromApproval)) {
                throw new BadRequestException(
                        "WAITING_APPROVAL may only lead to IN_PROGRESS or CANCELLED when an approval policy applies");
            }
        }
    }

    private WorkflowDto.Workflow toDto(Long ticketTypeId, boolean overridden, Map<TicketStatus, Set<TicketStatus>> graph) {
        List<WorkflowDto.Transition> list = new ArrayList<>();
        graph.forEach((from, targets) -> targets.forEach(to -> list.add(new WorkflowDto.Transition(from.name(), to.name()))));
        return new WorkflowDto.Workflow(ticketTypeId, overridden, list);
    }

    private Map<String, Object> describe(Map<TicketStatus, Set<TicketStatus>> graph) {
        Map<String, Object> out = new java.util.TreeMap<>();
        graph.forEach((from, targets) -> out.put(from.name(), targets.stream().map(Enum::name).sorted().toList()));
        return out;
    }
}
