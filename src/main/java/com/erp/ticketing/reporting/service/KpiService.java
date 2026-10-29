package com.erp.ticketing.reporting.service;

import com.erp.ticketing.approval.model.ApprovalTask;
import com.erp.ticketing.approval.repository.ApprovalTaskRepository;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.reporting.dto.KpiResponse;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.service.TicketAccessService;
import com.erp.ticketing.ticket.service.TicketService;
import com.erp.ticketing.user.service.UserService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/** Computes the standard KPI set over visible tickets; see {@link KpiResponse} for the definitions. */
@Service
public class KpiService {

    private static final Set<TicketStatus> TERMINAL =
            EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

    private final TicketRepository ticketRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final TicketAccessService accessService;
    private final TicketService ticketService;
    private final UserService userService;

    public KpiService(TicketRepository ticketRepository, ApprovalTaskRepository approvalTaskRepository,
                      TicketAccessService accessService, TicketService ticketService, UserService userService) {
        this.ticketRepository = ticketRepository;
        this.approvalTaskRepository = approvalTaskRepository;
        this.accessService = accessService;
        this.ticketService = ticketService;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public KpiResponse compute(String dateFrom, String dateTo, Long departmentId, ErpUserPrincipal principal) {
        Instant from = parse(dateFrom, "dateFrom", Instant.EPOCH);
        Instant to = parse(dateTo, "dateTo", Instant.now());
        Specification<Ticket> spec = (root, q, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("createdAt"), from), cb.lessThanOrEqualTo(root.get("createdAt"), to));
        if (departmentId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("department").get("id"), departmentId));
        }
        spec = spec.and(accessService.visibleTo(principal, userService.getOrCreateCurrentUser(principal)));
        List<Ticket> tickets = ticketRepository.findAll(spec);
        Instant now = Instant.now();

        Double firstResponse = avg(tickets.stream().filter(t -> t.getFirstResponseAt() != null)
                .mapToDouble(t -> minutes(t.getCreatedAt(), t.getFirstResponseAt())));
        Double resolution = avg(tickets.stream().filter(t -> t.getResolvedAt() != null)
                .mapToDouble(t -> minutes(t.getCreatedAt(), t.getResolvedAt())));

        long met = 0;
        long breached = 0;
        for (Ticket t : tickets) {
            if (t.getStatus() == TicketStatus.CANCELLED) {
                continue;
            }
            String sla = ticketService.computeSlaStatus(t);
            if ("MET".equals(sla)) {
                met++;
            } else if ("BREACHED".equals(sla)) {
                breached++;
            }
        }
        Double slaPercent = met + breached == 0 ? null : round(100.0 * met / (met + breached));

        List<Ticket> open = tickets.stream().filter(t -> !TERMINAL.contains(t.getStatus())).toList();
        Double openAge = avg(open.stream().mapToDouble(t -> minutes(t.getCreatedAt(), now) / 60.0));

        long everResolved = tickets.stream().filter(t -> t.getResolvedAt() != null || t.getReopenCount() > 0).count();
        long reopened = tickets.stream().filter(t -> t.getReopenCount() > 0).count();
        Double reopenRate = everResolved == 0 ? null : round(100.0 * reopened / everResolved);

        Set<Long> visibleIds = tickets.stream().map(Ticket::getId).collect(Collectors.toSet());
        Double approvalTurnaround = avg(approvalTaskRepository.findDecidedCreatedBetween(
                        EnumSet.of(ApprovalTask.Status.APPROVED, ApprovalTask.Status.REJECTED,
                                ApprovalTask.Status.CHANGES_REQUESTED), from, to).stream()
                .filter(t -> visibleIds.contains(t.getTicket().getId()))
                .mapToDouble(t -> minutes(t.getCreatedAt(), t.getDecidedAt()) / 60.0));

        return new KpiResponse(tickets.size(), firstResponse, resolution, slaPercent, met + breached,
                open.size(), openAge, reopenRate, approvalTurnaround, definitions());
    }

    private Map<String, String> definitions() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("firstResponseAvgMinutes", "Average wall-clock minutes from ticket creation to the first human reply, "
                + "over tickets created in the period that have one. Automatic acknowledgements do not count.");
        d.put("resolutionAvgMinutes", "Average wall-clock minutes from creation to the recorded resolution, over resolved "
                + "tickets. Time spent waiting on the requester is included.");
        d.put("slaAchievementPercent", "Resolved tickets that met their resolution target divided by those that met or "
                + "breached it. Cancelled tickets and tickets without an SLA are excluded.");
        d.put("backlog", "Tickets created in the period that are not Resolved, Closed or Cancelled right now; "
                + "paused and waiting tickets are counted.");
        d.put("avgOpenAgeHours", "Average hours since creation of the tickets in the backlog.");
        d.put("reopenRatePercent", "Tickets reopened at least once divided by tickets that were ever resolved or reopened.");
        d.put("approvalTurnaroundAvgHours", "Average hours from an approval task being created to its decision, "
                + "counting each approver separately; expired and withdrawn tasks are excluded.");
        return d;
    }

    private static double minutes(Instant a, Instant b) {
        return Duration.between(a, b).toSeconds() / 60.0;
    }

    private static Double avg(java.util.stream.DoubleStream values) {
        OptionalDouble avg = values.average();
        return avg.isPresent() ? round(avg.getAsDouble()) : null;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Instant parse(String value, String name, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Invalid " + name + " - expected ISO instant, e.g. 2026-01-01T00:00:00Z");
        }
    }
}
