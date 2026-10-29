package com.erp.ticketing.reporting.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.csat.model.CsatStatus;
import com.erp.ticketing.csat.model.CsatSurvey;
import com.erp.ticketing.csat.repository.CsatSurveyRepository;
import com.erp.ticketing.reporting.dto.AdminDashboardKpis;
import com.erp.ticketing.reporting.dto.AgentDashboardKpis;
import com.erp.ticketing.reporting.dto.CsatSummaryResponse;
import com.erp.ticketing.reporting.dto.DashboardSummaryResponse;
import com.erp.ticketing.reporting.dto.DepartmentWorkloadResponse;
import com.erp.ticketing.reporting.dto.EscalationResponse;
import com.erp.ticketing.reporting.dto.ManagerDashboardKpis;
import com.erp.ticketing.reporting.dto.ReportSummaryResponse;
import com.erp.ticketing.reporting.dto.RequesterDashboardKpis;
import com.erp.ticketing.reporting.dto.SlaComplianceResponse;
import com.erp.ticketing.reporting.dto.SlaComplianceTrendResponse;
import com.erp.ticketing.reporting.dto.TicketVolumeTrendResponse;
import com.erp.ticketing.reporting.dto.TicketsByPriorityResponse;
import com.erp.ticketing.reporting.dto.TicketsByStatusResponse;
import com.erp.ticketing.reporting.dto.TicketsByTypeResponse;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.model.DepartmentMember;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.ticket.service.TicketService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.ticket.service.TicketAccessService;
import com.erp.ticketing.user.service.UserService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only aggregation queries over existing ticket data backing the
 * /api/helpdesk/reports/** endpoints. Kept out of TicketService to avoid bloating it
 * further; reuses the same Specification-composition approach TicketService.list()
 * already uses for filtering, rather than hand-rolled native SQL.
 *
 * All methods pull the filtered ticket set into memory and aggregate in Java
 * rather than pushing aggregation into the database - simpler to get exactly
 * right than JPQL/native aggregate queries, at the cost of scaling less well
 * to very large ticket volumes. Acceptable for this phase; worth revisiting
 * with DB-side aggregation if report datasets grow large.
 */
@Service
public class ReportingService {

    /** Mirrors SlaMonitoringJob/TicketService's terminal-status set. */
    private static final Set<TicketStatus> TERMINAL_STATUSES =
            EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

    private static final int DEFAULT_TREND_DAYS = 30;
    private static final int MAX_TREND_DAYS = 366;

    private final TicketRepository ticketRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final TicketService ticketService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentMemberRepository departmentMemberRepository;
    private final CsatSurveyRepository csatSurveyRepository;
    private final TicketAccessService accessService;
    private final AuditService auditService;

    public ReportingService(TicketRepository ticketRepository,
                             TicketTypeRepository ticketTypeRepository,
                             TicketService ticketService,
                             UserService userService,
                             UserRepository userRepository,
                             DepartmentRepository departmentRepository,
                             DepartmentMemberRepository departmentMemberRepository,
                             CsatSurveyRepository csatSurveyRepository,
                             TicketAccessService accessService,
                             AuditService auditService) {
        this.accessService = accessService;
        this.auditService = auditService;
        this.ticketRepository = ticketRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.ticketService = ticketService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.departmentMemberRepository = departmentMemberRepository;
        this.csatSurveyRepository = csatSurveyRepository;
    }

    /** Reports only ever count tickets the caller may see; restricted tickets and other departments drop out. */
    private List<Ticket> scopedAll(Specification<Ticket> spec) {
        return ticketRepository.findAll(Specification.where(spec).and(accessService.currentVisibility()));
    }

    private long scopedCount(Specification<Ticket> spec) {
        return ticketRepository.count(Specification.where(spec).and(accessService.currentVisibility()));
    }

    @Transactional(readOnly = true)
    public ReportSummaryResponse summary(String dateFrom, String dateTo, Long typeId, Long departmentId) {
        List<Ticket> tickets = scopedAll(buildCommonSpec(dateFrom, dateTo, typeId, departmentId));

        Map<String, Long> byStatus = computeByStatus(tickets);
        Map<String, Long> byPriority = computeByPriority(tickets);

        Double avgFirstResponseMinutes = average(tickets.stream()
                .filter(t -> t.getFirstResponseAt() != null)
                .map(t -> (double) Duration.between(t.getCreatedAt(), t.getFirstResponseAt()).toMinutes()));
        Double avgResolutionMinutes = average(tickets.stream()
                .filter(t -> t.getResolvedAt() != null)
                .map(t -> (double) Duration.between(t.getCreatedAt(), t.getResolvedAt()).toMinutes()));

        return new ReportSummaryResponse(tickets.size(), byStatus, byPriority, avgFirstResponseMinutes, avgResolutionMinutes);
    }

    @Transactional(readOnly = true)
    public SlaComplianceResponse slaCompliance(String dateFrom, String dateTo, Long typeId, Long departmentId) {
        List<Ticket> tickets = scopedAll(buildCommonSpec(dateFrom, dateTo, typeId, departmentId));

        List<Ticket> evaluated = tickets.stream()
                .filter(t -> t.getSlaPolicyId() != null)
                .filter(t -> isResolvedOrClosed(t) || t.isSlaBreached())
                .toList();

        long totalEvaluated = evaluated.size();
        long metCount = evaluated.stream().filter(t -> isResolvedOrClosed(t) && !t.isSlaBreached()).count();
        long breachedCount = evaluated.stream().filter(Ticket::isSlaBreached).count();
        Double compliancePercentage = totalEvaluated == 0 ? null
                : Math.round(metCount * 1000.0 / totalEvaluated) / 10.0;

        return new SlaComplianceResponse(totalEvaluated, metCount, breachedCount, compliancePercentage);
    }

    /**
     * Returns one row per ticket_types row (including types with zero matching
     * tickets, so a caller doesn't need a separate call to /api/helpdesk/ticket-types to
     * know which types exist), counting tickets matching the given filters.
     */
    @Transactional(readOnly = true)
    public List<TicketsByTypeResponse> ticketsByType(String dateFrom, String dateTo, Long typeId, Long departmentId) {
        List<Ticket> tickets = scopedAll(buildCommonSpec(dateFrom, dateTo, typeId, departmentId));
        Map<Long, Long> counts = tickets.stream()
                .collect(Collectors.groupingBy(t -> t.getType().getId(), Collectors.counting()));

        return ticketTypeRepository.findAll().stream()
                .sorted(Comparator.comparing(TicketType::getId))
                .map(type -> new TicketsByTypeResponse(type.getId(), type.getName(), counts.getOrDefault(type.getId(), 0L)))
                .toList();
    }

    /**
     * GET /api/helpdesk/reports/dashboard/summary - the one /api/helpdesk/reports/** endpoint
     * open to plain REQUESTER callers too (see the dedicated SecurityConfig
     * matcher). Picks exactly one KPI variant per caller by role precedence
     * ADMIN > MANAGER > AGENT > REQUESTER.
     */
    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboardSummary(ErpUserPrincipal principal) {
        String variant = resolveVariant(principal);
        User currentUser = userService.getOrCreateCurrentUser(principal);
        Object kpis = switch (variant) {
            case "ADMIN" -> adminKpis();
            case "MANAGER" -> managerKpis(currentUser.getId());
            case "AGENT" -> agentKpis(currentUser.getId());
            default -> requesterKpis(currentUser.getId());
        };
        return new DashboardSummaryResponse(variant, Instant.now(), kpis);
    }

    private String resolveVariant(ErpUserPrincipal principal) {
        Set<String> roles = principal.roles();
        if (hasRole(roles, "ADMIN")) {
            return "ADMIN";
        }
        if (hasRole(roles, "MANAGER")) {
            return "MANAGER";
        }
        if (hasRole(roles, "AGENT")) {
            return "AGENT";
        }
        return "REQUESTER";
    }

    private boolean hasRole(Set<String> roles, String role) {
        return roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    private RequesterDashboardKpis requesterKpis(Long userId) {
        Instant now = Instant.now();
        Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);
        Specification<Ticket> byRequester = (root, query, cb) -> cb.equal(root.get("requester").get("id"), userId);

        long myOpenTickets = scopedCount(byRequester.and(
                (root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES))));
        long myAwaitingResponse = scopedCount(byRequester.and(
                (root, query, cb) -> cb.equal(root.get("status"), TicketStatus.WAITING_CUSTOMER)));
        long myResolvedLast30Days = scopedCount(byRequester.and(
                (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("resolvedAt"), thirtyDaysAgo)));

        List<Ticket> resolvedMine = scopedAll(byRequester.and(
                (root, query, cb) -> cb.isNotNull(root.get("resolvedAt"))));
        Double avgResolutionMinutesMine = average(resolvedMine.stream()
                .map(t -> (double) Duration.between(t.getCreatedAt(), t.getResolvedAt()).toMinutes()));

        return new RequesterDashboardKpis(myOpenTickets, myAwaitingResponse, myResolvedLast30Days, avgResolutionMinutesMine);
    }

    private AgentDashboardKpis agentKpis(Long userId) {
        Instant now = Instant.now();
        Instant fourHoursFromNow = now.plus(4, ChronoUnit.HOURS);
        Specification<Ticket> byAssignee = (root, query, cb) -> cb.equal(root.get("assignee").get("id"), userId);

        long myOpenTickets = scopedCount(byAssignee.and(
                (root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES))));
        long myOverdueTickets = scopedCount(byAssignee.and(
                (root, query, cb) -> cb.isTrue(root.get("slaBreached"))));
        long slaBreachRiskNext4h = scopedCount(byAssignee.and((root, query, cb) -> cb.and(
                cb.isFalse(root.get("slaBreached")),
                cb.between(root.get("resolutionDue"), now, fourHoursFromNow),
                cb.not(root.get("status").in(TERMINAL_STATUSES)))));

        List<Ticket> withFirstResponse = scopedAll(byAssignee.and(
                (root, query, cb) -> cb.isNotNull(root.get("firstResponseAt"))));
        Double avgFirstResponseMinutesMine = average(withFirstResponse.stream()
                .map(t -> (double) Duration.between(t.getCreatedAt(), t.getFirstResponseAt()).toMinutes()));

        return new AgentDashboardKpis(myOpenTickets, myOverdueTickets, slaBreachRiskNext4h, avgFirstResponseMinutesMine);
    }

    /**
     * Scoped to the manager's own department(s), resolved via
     * department_members. If the manager belongs to zero departments, falls
     * back to org-wide scope (all departments) rather than returning all-zero
     * KPIs, since a manager not yet assigned to a department should still see
     * the overall picture rather than a dashboard that looks broken.
     */
    private ManagerDashboardKpis managerKpis(Long userId) {
        List<Long> departmentIds = resolveDepartmentIds(userId);
        boolean scopeToDepartments = !departmentIds.isEmpty();
        Specification<Ticket> scope = scopeToDepartments
                ? (root, query, cb) -> root.get("department").get("id").in(departmentIds)
                : Specification.where(null);

        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        long departmentOpenTickets = scopedCount(scope.and(
                (root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES))));
        long departmentOverdueTickets = scopedCount(scope.and(
                (root, query, cb) -> cb.isTrue(root.get("slaBreached"))));
        // Simplification (per spec): rather than joining activity_logs on
        // action_type='SLA_BREACHED' and correlating back to department through
        // the ticket, count tickets currently flagged slaBreached=true whose
        // updatedAt falls in the last 7 days. SlaMonitoringJob saves the ticket
        // at the moment it flips slaBreached=true, so updatedAt is a close
        // proxy for "breached in the last 7 days" without a second query.
        long slaBreachCountLast7Days = scopedCount(scope.and((root, query, cb) -> cb.and(
                cb.isTrue(root.get("slaBreached")),
                cb.greaterThanOrEqualTo(root.get("updatedAt"), sevenDaysAgo))));

        List<Ticket> resolvedInScope = scopedAll(scope.and(
                (root, query, cb) -> cb.isNotNull(root.get("resolvedAt"))));
        Double avgResolutionMinutesDepartment = average(resolvedInScope.stream()
                .map(t -> (double) Duration.between(t.getCreatedAt(), t.getResolvedAt()).toMinutes()));

        return new ManagerDashboardKpis(departmentOpenTickets, departmentOverdueTickets, slaBreachCountLast7Days, avgResolutionMinutesDepartment);
    }

    private AdminDashboardKpis adminKpis() {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long totalOpenTickets = scopedCount(
                (root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES)));
        // Same simplification as the manager variant above, org-wide (no department scoping).
        long totalSlaBreachesLast7Days = scopedCount((root, query, cb) -> cb.and(
                cb.isTrue(root.get("slaBreached")),
                cb.greaterThanOrEqualTo(root.get("updatedAt"), sevenDaysAgo)));
        long totalUsers = userRepository.count();
        long totalDepartments = departmentRepository.count();
        return new AdminDashboardKpis(totalOpenTickets, totalSlaBreachesLast7Days, totalUsers, totalDepartments);
    }

    private List<Long> resolveDepartmentIds(Long userId) {
        return departmentMemberRepository.findByUser_Id(userId).stream()
                .map(dm -> dm.getDepartment().getId())
                .distinct()
                .toList();
    }

    /**
     * GET /api/helpdesk/reports/ticket-volume-trend - one entry per UTC calendar day
     * (ascending) for the last {@code days} days including today.
     */
    @Transactional(readOnly = true)
    public List<TicketVolumeTrendResponse> ticketVolumeTrend(Integer days) {
        int clamped = clampDays(days);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(clamped - 1L);
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Ticket> createdInRange = scopedAll((root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("createdAt"), from),
                cb.lessThan(root.get("createdAt"), to)));
        List<Ticket> resolvedInRange = scopedAll((root, query, cb) -> cb.and(
                cb.isNotNull(root.get("resolvedAt")),
                cb.greaterThanOrEqualTo(root.get("resolvedAt"), from),
                cb.lessThan(root.get("resolvedAt"), to)));

        Map<LocalDate, Long> createdByDay = createdInRange.stream().collect(Collectors.groupingBy(
                t -> t.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), Collectors.counting()));
        Map<LocalDate, Long> resolvedByDay = resolvedInRange.stream().collect(Collectors.groupingBy(
                t -> t.getResolvedAt().atZone(ZoneOffset.UTC).toLocalDate(), Collectors.counting()));

        List<TicketVolumeTrendResponse> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            result.add(new TicketVolumeTrendResponse(d.toString(), createdByDay.getOrDefault(d, 0L), resolvedByDay.getOrDefault(d, 0L)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<TicketsByStatusResponse> ticketsByStatus(String dateFrom, String dateTo, Long typeId, Long departmentId) {
        List<Ticket> tickets = scopedAll(buildCommonSpec(dateFrom, dateTo, typeId, departmentId));
        return computeByStatus(tickets).entrySet().stream()
                .map(e -> new TicketsByStatusResponse(e.getKey(), e.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TicketsByPriorityResponse> ticketsByPriority(String dateFrom, String dateTo, Long typeId, Long departmentId) {
        List<Ticket> tickets = scopedAll(buildCommonSpec(dateFrom, dateTo, typeId, departmentId));
        return computeByPriority(tickets).entrySet().stream()
                .map(e -> new TicketsByPriorityResponse(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * GET /api/helpdesk/reports/department-workload - if departmentId is omitted,
     * scopes to the caller's own department(s) (same department_members lookup
     * as the manager dashboard variant); if the caller belongs to no
     * department (e.g. an ADMIN with no membership row), scopes to all
     * tickets org-wide.
     */
    @Transactional(readOnly = true)
    public List<DepartmentWorkloadResponse> departmentWorkload(Long departmentId, ErpUserPrincipal principal) {
        Specification<Ticket> scope;
        if (departmentId != null) {
            scope = (root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId);
        } else {
            User currentUser = userService.getOrCreateCurrentUser(principal);
            List<Long> departmentIds = resolveDepartmentIds(currentUser.getId());
            scope = departmentIds.isEmpty()
                    ? Specification.where(null)
                    : (root, query, cb) -> root.get("department").get("id").in(departmentIds);
        }

        Specification<Ticket> openAssignedInScope = scope.and((root, query, cb) -> cb.and(
                cb.isNotNull(root.get("assignee")),
                cb.not(root.get("status").in(TERMINAL_STATUSES))));

        List<Ticket> tickets = scopedAll(openAssignedInScope);
        Map<Long, Long> countsByAssignee = tickets.stream()
                .collect(Collectors.groupingBy(t -> t.getAssignee().getId(), Collectors.counting()));
        Map<Long, String> namesByAssignee = tickets.stream()
                .collect(Collectors.toMap(t -> t.getAssignee().getId(), t -> t.getAssignee().getName(), (a, b) -> a));

        return countsByAssignee.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .map(e -> new DepartmentWorkloadResponse(e.getKey(), namesByAssignee.get(e.getKey()), e.getValue()))
                .toList();
    }

    /**
     * GET /api/helpdesk/reports/sla-compliance-trend - one entry per UTC calendar day.
     * For each day D, considers tickets whose resolvedAt OR closedAt falls on
     * day D (a ticket can therefore contribute to at most two different days'
     * buckets if those two timestamps land on different days). Null
     * compliancePercentage means zero tickets were resolved/closed that day -
     * a gap in the trend, not a 0% day.
     */
    @Transactional(readOnly = true)
    public List<SlaComplianceTrendResponse> slaComplianceTrend(Integer days) {
        int clamped = clampDays(days);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(clamped - 1L);
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Ticket> candidates = scopedAll((root, query, cb) -> cb.or(
                cb.and(cb.isNotNull(root.get("resolvedAt")),
                        cb.greaterThanOrEqualTo(root.get("resolvedAt"), from), cb.lessThan(root.get("resolvedAt"), to)),
                cb.and(cb.isNotNull(root.get("closedAt")),
                        cb.greaterThanOrEqualTo(root.get("closedAt"), from), cb.lessThan(root.get("closedAt"), to))));

        List<SlaComplianceTrendResponse> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            LocalDate day = d;
            List<Ticket> dayTickets = candidates.stream()
                    .filter(t -> fallsOnDay(t.getResolvedAt(), day) || fallsOnDay(t.getClosedAt(), day))
                    .toList();
            Double compliance = dayTickets.isEmpty() ? null
                    : Math.round(dayTickets.stream().filter(t -> !t.isSlaBreached()).count() * 1000.0 / dayTickets.size()) / 10.0;
            result.add(new SlaComplianceTrendResponse(day.toString(), compliance));
        }
        return result;
    }

    private boolean fallsOnDay(Instant instant, LocalDate day) {
        return instant != null && instant.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
    }

    private int clampDays(Integer days) {
        int value = days != null ? days : DEFAULT_TREND_DAYS;
        if (value < 1) {
            value = 1;
        }
        if (value > MAX_TREND_DAYS) {
            value = MAX_TREND_DAYS;
        }
        return value;
    }

    /**
     * GET /api/helpdesk/reports/escalations - tickets currently BREACHED or AT_RISK per
     * the same persisted-flag definitions used by GET /api/helpdesk/tickets?slaStatus=,
     * sorted breached-first then at-risk, and within each group by earliest
     * resolutionDue first (most overdue / soonest-due first respectively).
     * breachType is FIRST_RESPONSE when the ticket hasn't had a first response
     * yet and its firstResponseDue has already passed, otherwise RESOLUTION -
     * SlaMonitoringJob itself only ever drives slaBreached/slaWarningSent off
     * resolutionDue (never firstResponseDue), so minutesOverdue/minutesUntilDue
     * are always computed against resolutionDue to stay consistent with that
     * job's actual math; breachType is just a descriptive label layered on top.
     */
    @Transactional(readOnly = true)
    public List<EscalationResponse> escalations(Long departmentId, Long assigneeId, ErpUserPrincipal principal) {
        Specification<Ticket> notTerminal = (root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES));
        Specification<Ticket> breachedOrAtRisk = (root, query, cb) -> cb.or(
                cb.isTrue(root.get("slaBreached")),
                cb.and(cb.isFalse(root.get("slaBreached")), cb.isTrue(root.get("slaWarningSent"))));

        Specification<Ticket> spec = Specification.where(notTerminal).and(breachedOrAtRisk);
        if (departmentId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId));
        }
        if (assigneeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assignee").get("id"), assigneeId));
        }

        // Rows carry titles, so they must respect ticket visibility (restricted tickets, department scope).
        spec = spec.and(accessService.visibleTo(principal, userService.getOrCreateCurrentUser(principal)));

        List<Ticket> tickets = scopedAll(spec);
        Instant now = Instant.now();

        Comparator<Ticket> byBreachFirst = Comparator.comparing((Ticket t) -> t.isSlaBreached() ? 0 : 1);
        Comparator<Ticket> byResolutionDueAsc = Comparator.comparing(Ticket::getResolutionDue);

        return tickets.stream()
                .sorted(byBreachFirst.thenComparing(byResolutionDueAsc))
                .map(t -> toEscalationResponse(t, now))
                .toList();
    }

    private EscalationResponse toEscalationResponse(Ticket ticket, Instant now) {
        String breachStatus = ticket.isSlaBreached() ? "BREACHED" : "AT_RISK";
        String breachType = ticket.getFirstResponseAt() == null
                && ticket.getFirstResponseDue() != null
                && now.isAfter(ticket.getFirstResponseDue())
                ? "FIRST_RESPONSE" : "RESOLUTION";

        Long minutesOverdue = null;
        Long minutesUntilDue = null;
        if ("BREACHED".equals(breachStatus)) {
            minutesOverdue = Duration.between(ticket.getResolutionDue(), now).toMinutes();
        } else {
            minutesUntilDue = Duration.between(now, ticket.getResolutionDue()).toMinutes();
        }

        Department department = ticket.getDepartment();
        User assignee = ticket.getAssignee();
        return new EscalationResponse(
                ticket.getId(), ticket.getTicketNumber(), ticket.getTitle(),
                department != null ? department.getId() : null, department != null ? department.getDepartmentName() : null,
                assignee != null ? assignee.getId() : null, assignee != null ? assignee.getName() : null,
                breachStatus, breachType, minutesOverdue, minutesUntilDue);
    }

    /**
     * GET /api/helpdesk/reports/csat - aggregate CSAT response rate/average rating/
     * distribution over surveys generated in the given date range (by
     * generation date, i.e. csat_surveys.created_at, not submission date).
     * Pulls into memory and aggregates in Java, consistent with every other
     * method in this class.
     */
    @Transactional(readOnly = true)
    public CsatSummaryResponse csatSummary(String dateFrom, String dateTo, Long departmentId) {
        Instant from = parseInstant(dateFrom, "dateFrom");
        Instant to = parseInstant(dateTo, "dateTo");
        Specification<CsatSurvey> spec = Specification.where(null);
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        if (departmentId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("ticket").get("department").get("id"), departmentId));
        }

        List<CsatSurvey> surveys = csatSurveyRepository.findAll(spec);
        java.util.Set<Long> ticketIds = surveys.stream().map(x -> x.getTicket().getId()).collect(Collectors.toSet());
        java.util.Set<Long> visibleIds = ticketIds.isEmpty() ? java.util.Set.of()
                : scopedAll((root, query, cb) -> root.get("id").in(ticketIds)).stream().map(Ticket::getId).collect(Collectors.toSet());
        surveys = surveys.stream().filter(x -> visibleIds.contains(x.getTicket().getId())).toList();
        long total = surveys.size();
        List<CsatSurvey> submitted = surveys.stream().filter(s -> s.getStatus() == CsatStatus.SUBMITTED).toList();
        long submittedCount = submitted.size();
        Double responseRate = total == 0 ? null : Math.round(submittedCount * 1000.0 / total) / 10.0;
        Double avgRating = submitted.isEmpty() ? null
                : Math.round(submitted.stream().mapToInt(CsatSurvey::getRating).average().orElse(0) * 100.0) / 100.0;

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            distribution.put(i, 0L);
        }
        submitted.forEach(s -> distribution.merge(s.getRating(), 1L, Long::sum));

        return new CsatSummaryResponse(total, submittedCount, responseRate, avgRating, distribution);
    }

    private Map<String, Long> computeByStatus(List<Ticket> tickets) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TicketStatus status : TicketStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        for (Ticket ticket : tickets) {
            byStatus.merge(ticket.getStatus().name(), 1L, Long::sum);
        }
        return byStatus;
    }

    private Map<String, Long> computeByPriority(List<Ticket> tickets) {
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (TicketPriority priority : TicketPriority.values()) {
            byPriority.put(priority.name(), 0L);
        }
        for (Ticket ticket : tickets) {
            byPriority.merge(ticket.getPriority().name(), 1L, Long::sum);
        }
        return byPriority;
    }

    /**
     * Builds the CSV for the caller. Only tickets the caller may see are included, even when specific
     * ticket ids are requested (BRS 17.3, AT-017), and every export is recorded in the audit trail.
     * Built eagerly on the request thread so the audit row and the caller identity are never lost.
     */
    @Transactional
    public byte[] buildTicketsCsv(String dateFrom, String dateTo, Long typeId, Long departmentId,
                                  String status, String priority, Long assigneeId, Long requesterId,
                                  String ticketIds, ErpUserPrincipal principal) throws IOException {
        Specification<Ticket> spec;
        if (ticketIds != null && !ticketIds.isBlank()) {
            // "Export selected" from a bulk-action bar: export exactly these
            // tickets and ignore every other filter param entirely - simplest,
            // least surprising behavior. Absent, behavior is unchanged from before.
            List<Long> ids = parseTicketIds(ticketIds);
            spec = (root, query, cb) -> root.get("id").in(ids);
        } else {
            spec = buildCommonSpec(dateFrom, dateTo, typeId, departmentId);
            if (status != null && !status.isBlank()) {
                TicketStatus statusEnum = parseStatus(status);
                spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), statusEnum));
            }
            if (priority != null && !priority.isBlank()) {
                TicketPriority priorityEnum = parsePriority(priority);
                spec = spec.and((root, query, cb) -> cb.equal(root.get("priority"), priorityEnum));
            }
            if (assigneeId != null) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("assignee").get("id"), assigneeId));
            }
            if (requesterId != null) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("requester").get("id"), requesterId));
            }
        }

        var user = userService.getOrCreateCurrentUser(principal);
        spec = spec.and(accessService.visibleTo(principal, user));
        List<Ticket> tickets = scopedAll(spec);
        auditService.record(user, "TICKETS_EXPORTED", "TICKET_EXPORT", null, null, exportDescription(
                dateFrom, dateTo, typeId, departmentId, status, priority, assigneeId, requesterId, ticketIds, tickets.size()));

        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write("Ticket Number,Title,Type,Category,Priority,Status,Requester,Assignee,Department,Created At,Resolved At,SLA Status\r\n");
        for (Ticket ticket : tickets) {
            writer.write(csvRow(ticket));
        }
        writer.flush();
        return outputStream.toByteArray();
    }

    private Map<String, Object> exportDescription(String dateFrom, String dateTo, Long typeId, Long departmentId,
                                                  String status, String priority, Long assigneeId, Long requesterId,
                                                  String ticketIds, int rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("dateFrom", dateFrom);
        m.put("dateTo", dateTo);
        m.put("typeId", typeId);
        m.put("departmentId", departmentId);
        m.put("status", status);
        m.put("priority", priority);
        m.put("assigneeId", assigneeId);
        m.put("requesterId", requesterId);
        m.put("ticketIds", ticketIds);
        return m;
    }

    private String csvRow(Ticket ticket) {
        String[] fields = {
                ticket.getTicketNumber(),
                ticket.getTitle(),
                ticket.getType() != null ? ticket.getType().getName() : "",
                ticket.getCategory() != null ? ticket.getCategory().getName() : "",
                ticket.getPriority() != null ? ticket.getPriority().name() : "",
                ticket.getStatus() != null ? ticket.getStatus().name() : "",
                ticket.getRequester() != null ? ticket.getRequester().getName() : "",
                ticket.getAssignee() != null ? ticket.getAssignee().getName() : "",
                ticket.getDepartment() != null ? ticket.getDepartment().getDepartmentName() : "",
                ticket.getCreatedAt() != null ? ticket.getCreatedAt().toString() : "",
                ticket.getResolvedAt() != null ? ticket.getResolvedAt().toString() : "",
                ticketService.computeSlaStatus(ticket)
        };
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(csvEscape(fields[i]));
        }
        row.append("\r\n");
        return row.toString();
    }

    /** Quotes a field per RFC 4180 if it contains a comma, quote, or newline. */
    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private boolean isResolvedOrClosed(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.CLOSED;
    }

    private Double average(Stream<Double> values) {
        List<Double> list = values.toList();
        if (list.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double v : list) {
            sum += v;
        }
        return Math.round((sum / list.size()) * 100.0) / 100.0;
    }

    private Specification<Ticket> buildCommonSpec(String dateFrom, String dateTo, Long typeId, Long departmentId) {
        Specification<Ticket> spec = Specification.where(null);
        Instant from = parseInstant(dateFrom, "dateFrom");
        Instant to = parseInstant(dateTo, "dateTo");
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        if (typeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type").get("id"), typeId));
        }
        if (departmentId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId));
        }
        return spec;
    }

    private List<Long> parseTicketIds(String value) {
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Invalid ticketIds - expected a comma-separated list of numeric ids: " + value);
        }
    }

    private Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Invalid " + paramName + " - expected ISO instant, e.g. 2026-01-01T00:00:00Z");
        }
    }

    private TicketPriority parsePriority(String value) {
        try {
            return TicketPriority.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Invalid priority: " + value);
        }
    }

    private TicketStatus parseStatus(String value) {
        try {
            return TicketStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Invalid status: " + value);
        }
    }
}
