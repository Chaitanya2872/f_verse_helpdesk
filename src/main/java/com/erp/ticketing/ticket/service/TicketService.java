package com.erp.ticketing.ticket.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.csat.service.CsatService;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.model.DepartmentMember;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.routing.dto.RoutingResolution;
import com.erp.ticketing.routing.service.RoutingRuleService;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.sla.dto.SlaDueDates;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.service.SlaCalculationService;
import com.erp.ticketing.ticket.dto.PageResponse;
import com.erp.ticketing.ticket.dto.TicketCreateRequest;
import com.erp.ticketing.ticket.dto.TicketResponse;
import com.erp.ticketing.ticket.dto.TicketSummaryResponse;
import com.erp.ticketing.ticket.dto.TicketUpdateRequest;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketSource;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(TicketStatus.class);

    /**
     * Shared by list()'s openOnly shorthand and slaStatus AT_RISK/ON_TRACK
     * predicates - a ticket in one of these statuses is done and no longer
     * subject to SLA monitoring (mirrors SlaMonitoringJob.TERMINAL_STATUSES).
     */
    private static final Set<TicketStatus> TERMINAL_STATUSES =
            EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

    static {
        ALLOWED_TRANSITIONS.put(TicketStatus.NEW, EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TicketStatus.IN_PROGRESS,
                EnumSet.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.WAITING_VENDOR, TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TicketStatus.WAITING_CUSTOMER,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TicketStatus.WAITING_VENDOR,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TicketStatus.RESOLVED, EnumSet.of(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS));
        ALLOWED_TRANSITIONS.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class));
        ALLOWED_TRANSITIONS.put(TicketStatus.CANCELLED, EnumSet.noneOf(TicketStatus.class));
    }

    private final TicketRepository ticketRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final CategoryRepository categoryRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentMemberRepository departmentMemberRepository;
    private final UserService userService;
    private final ActivityLogService activityLogService;
    private final RoutingRuleService routingRuleService;
    private final SlaCalculationService slaCalculationService;
    private final NotificationService notificationService;
    private final CsatService csatService;

    @PersistenceContext
    private EntityManager entityManager;

    public TicketService(TicketRepository ticketRepository,
                          TicketTypeRepository ticketTypeRepository,
                          CategoryRepository categoryRepository,
                          DepartmentRepository departmentRepository,
                          DepartmentMemberRepository departmentMemberRepository,
                          UserService userService,
                          ActivityLogService activityLogService,
                          RoutingRuleService routingRuleService,
                          SlaCalculationService slaCalculationService,
                          NotificationService notificationService,
                          CsatService csatService) {
        this.ticketRepository = ticketRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.categoryRepository = categoryRepository;
        this.departmentRepository = departmentRepository;
        this.departmentMemberRepository = departmentMemberRepository;
        this.userService = userService;
        this.activityLogService = activityLogService;
        this.routingRuleService = routingRuleService;
        this.slaCalculationService = slaCalculationService;
        this.notificationService = notificationService;
        this.csatService = csatService;
    }

    @Transactional
    public TicketResponse create(TicketCreateRequest request, ErpUserPrincipal principal) {
        User requester = userService.getOrCreateCurrentUser(principal);

        TicketType type = ticketTypeRepository.findById(request.typeId())
                .orElseThrow(() -> new NotFoundException("Ticket type not found: " + request.typeId()));

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
        }

        Ticket ticket = new Ticket();
        ticket.setTicketNumber(generateTicketNumber());
        ticket.setType(type);
        ticket.setCategory(category);
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setPriority(parsePriority(request.priority()));
        ticket.setStatus(TicketStatus.NEW);
        ticket.setRequester(requester);
        ticket.setSource(TicketSource.PORTAL);
        ticket.setTags(request.tags());
        ticket.setCustomFields(request.customFields());

        // Routing: TicketCreateRequest has no department/assignee fields, so both
        // are always unset at this point - only overwrite with a matched rule's
        // (possibly partial) result.
        applyRouting(ticket, type, category);

        // SLA: resolve a policy by type+priority and stamp due dates up front.
        applySla(ticket, type);

        ticket = ticketRepository.save(ticket);
        activityLogService.logCreated(ticket, requester);

        log.info("Ticket created", kv("ticketNumber", ticket.getTicketNumber()), kv("requesterId", requester.getId()),
                kv("typeId", type.getId()), kv("priority", ticket.getPriority()));

        notifyOnCreate(ticket);

        return toResponse(ticket);
    }

    private void applyRouting(Ticket ticket, TicketType type, Category category) {
        Optional<RoutingResolution> routing = routingRuleService.resolveRouting(type, category, ticket.getPriority());
        routing.ifPresent(resolution -> {
            if (ticket.getDepartment() == null && resolution.departmentId() != null) {
                departmentRepository.findById(resolution.departmentId()).ifPresent(ticket::setDepartment);
            }
            if (ticket.getAssignee() == null && resolution.assigneeId() != null) {
                ticket.setAssignee(userService.getRequiredUser(resolution.assigneeId()));
            }
        });
        if (ticket.getDepartment() == null) {
            // Configuration error, not a client error: every ticket must have a
            // department (mandatory), and this means no active routing rule
            // (including the catch-all) resolved one for this type/category/priority.
            throw new IllegalStateException(
                    "No department could be resolved for this ticket - check routing_rules configuration");
        }
    }

    private void applySla(Ticket ticket, TicketType type) {
        Optional<SlaPolicy> policy = slaCalculationService.resolvePolicy(type, ticket.getPriority());
        policy.ifPresent(p -> {
            ticket.setSlaPolicyId(p.getId());
            SlaDueDates dueDates = slaCalculationService.computeDueDates(Instant.now(), p);
            ticket.setFirstResponseDue(dueDates.firstResponseDue());
            ticket.setResolutionDue(dueDates.resolutionDue());
        });
    }

    private void notifyOnCreate(Ticket ticket) {
        User assignee = ticket.getAssignee();
        if (assignee != null) {
            Map<String, Object> placeholders = new HashMap<>();
            placeholders.put("ticketNumber", ticket.getTicketNumber());
            placeholders.put("title", ticket.getTitle());
            placeholders.put("priority", ticket.getPriority().name());
            placeholders.put("assigneeName", assignee.getName());
            notificationService.notify(assignee, ticket, "TICKET_CREATED", placeholders);
            return;
        }
        Department department = ticket.getDepartment();
        if (department != null) {
            List<DepartmentMember> members = departmentMemberRepository.findByDepartment_Id(department.getId());
            for (DepartmentMember member : members) {
                Map<String, Object> placeholders = new HashMap<>();
                placeholders.put("ticketNumber", ticket.getTicketNumber());
                placeholders.put("title", ticket.getTitle());
                placeholders.put("priority", ticket.getPriority().name());
                placeholders.put("departmentName", department.getDepartmentName());
                notificationService.notify(member.getUser(), ticket, "TICKET_CREATED", placeholders);
            }
        }
    }

    @Transactional(readOnly = true)
    public TicketResponse get(Long id, ErpUserPrincipal principal) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + id));
        User currentUser = userService.getOrCreateCurrentUser(principal);
        assertVisible(ticket, principal, currentUser);
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> list(String status, String priority, Long typeId, Long categoryId,
                                              Long assigneeId, Long requesterId, Long departmentId, Boolean openOnly,
                                              Boolean unassigned, String slaStatus, String search, String createdFrom,
                                              String createdTo, Pageable pageable, ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);

        Specification<Ticket> spec = Specification.where(null);

        // status: comma-separated list, e.g. "NEW,IN_PROGRESS" - a bare single
        // value like "NEW" parses to a one-element list, preserving today's
        // exact behavior. When status is given, openOnly is ignored (status wins).
        List<TicketStatus> statuses = parseStatusList(status);
        if (!statuses.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
        } else if (Boolean.TRUE.equals(openOnly)) {
            spec = spec.and((root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES)));
        }

        List<TicketPriority> priorities = parsePriorityList(priority);
        if (!priorities.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("priority").in(priorities));
        }

        if (typeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type").get("id"), typeId));
        }

        if (categoryId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId));
        }

        // assigneeId wins over unassigned when both are given.
        if (assigneeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assignee").get("id"), assigneeId));
        } else if (Boolean.TRUE.equals(unassigned)) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("assignee")));
        }

        if (requesterId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("requester").get("id"), requesterId));
        }
        if (departmentId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId));
        }
        if (slaStatus != null && !slaStatus.isBlank()) {
            spec = spec.and(buildSlaStatusSpec(slaStatus));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(buildSearchSpec(search));
        }

        Instant createdFromInstant = parseInstant(createdFrom, "createdFrom");
        Instant createdToInstant = parseInstant(createdTo, "createdTo");
        if (createdFromInstant != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdFromInstant));
        }
        if (createdToInstant != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdToInstant));
        }

        // Phase 1 visibility: requester-only callers see just their own tickets.
        // Department-based restriction for agents/managers is deferred to a later phase.
        if (isRequesterOnly(principal)) {
            Long currentUserId = currentUser.getId();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("requester").get("id"), currentUserId));
        }

        Page<Ticket> page = ticketRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    /**
     * Free-text substring match (case-insensitive), OR'd across ticketNumber,
     * title, requester.name and assignee.name. assignee is nullable so it is
     * joined explicitly as a LEFT JOIN - using root.get("assignee") directly in
     * a predicate would implicitly INNER JOIN and wrongly exclude unassigned
     * tickets from matches against the other three fields. requester is a
     * mandatory column, so a plain get() (implicit inner join) is safe there.
     * Deliberately out of scope for this pass: ERP links and the tags JSONB
     * column are not searched.
     */
    private Specification<Ticket> buildSearchSpec(String search) {
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            var assigneeJoin = root.join("assignee", jakarta.persistence.criteria.JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(root.get("ticketNumber")), pattern),
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("requester").get("name")), pattern),
                    cb.like(cb.lower(assigneeJoin.get("name")), pattern));
        };
    }

    /** Mirrors ReportingService.parseInstant's validation - duplicated locally to avoid a cross-package dependency for a two-line parse. */
    private Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BadRequestException("Invalid " + paramName + " - expected ISO instant, e.g. 2026-01-01T00:00:00Z");
        }
    }

    private List<TicketStatus> parseStatusList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseStatus)
                .distinct()
                .toList();
    }

    private List<TicketPriority> parsePriorityList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parsePriority)
                .distinct()
                .toList();
    }

    /**
     * slaStatus is a plain predicate over the persisted slaBreached/slaWarningSent
     * flags (kept up to date by SlaMonitoringJob every app.sla.monitoring.fixed-delay-ms)
     * rather than a recomputation of computeSlaStatus - see class-level filtering
     * docs on GET /api/helpdesk/tickets for the exact per-value definitions.
     */
    private Specification<Ticket> buildSlaStatusSpec(String slaStatus) {
        String normalized = slaStatus.trim().toUpperCase();
        return switch (normalized) {
            case "BREACHED" -> (root, query, cb) -> cb.isTrue(root.get("slaBreached"));
            case "AT_RISK" -> (root, query, cb) -> cb.and(
                    cb.isFalse(root.get("slaBreached")),
                    cb.isTrue(root.get("slaWarningSent")),
                    cb.not(root.get("status").in(TERMINAL_STATUSES)));
            case "ON_TRACK" -> (root, query, cb) -> cb.and(
                    cb.isFalse(root.get("slaBreached")),
                    cb.isFalse(root.get("slaWarningSent")),
                    cb.isNotNull(root.get("resolutionDue")),
                    cb.not(root.get("status").in(TERMINAL_STATUSES)));
            case "MET" -> (root, query, cb) -> cb.and(
                    root.get("status").in(Set.of(TicketStatus.RESOLVED, TicketStatus.CLOSED)),
                    cb.isFalse(root.get("slaBreached")));
            case "NOT_APPLICABLE" -> (root, query, cb) -> cb.isNull(root.get("slaPolicyId"));
            default -> throw new BadRequestException("Invalid slaStatus: " + slaStatus);
        };
    }

    /**
     * GET /api/helpdesk/tickets/summary - role-aware header counts for the redesigned
     * Tickets list page. Scoping mirrors ReportingService.dashboardSummary's
     * MANAGER/AGENT variants exactly (department-membership lookup via
     * departmentMemberRepository, org-wide fallback when a manager belongs to
     * no department). Implemented here rather than delegated to ReportingService to
     * avoid a circular dependency, since ReportingService already depends on
     * TicketService (for computeSlaStatus reuse in the CSV export).
     */
    @Transactional(readOnly = true)
    public TicketSummaryResponse summary(ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        String role = resolveRole(principal);

        Specification<Ticket> notTerminal = (root, query, cb) -> cb.not(root.get("status").in(TERMINAL_STATUSES));
        // Same persisted-flag predicates as buildSlaStatusSpec's BREACHED/AT_RISK
        // cases, unioned together (a ticket already breached still counts as
        // "at risk" for this header count) and restricted to non-terminal tickets.
        Specification<Ticket> slaAtRisk = notTerminal.and((root, query, cb) -> cb.or(
                cb.isTrue(root.get("slaBreached")),
                cb.and(cb.isTrue(root.get("slaWarningSent")), cb.isFalse(root.get("slaBreached")))));
        Specification<Ticket> unassignedSpec = (root, query, cb) -> cb.isNull(root.get("assignee"));
        Specification<Ticket> inProgressSpec = (root, query, cb) -> cb.equal(root.get("status"), TicketStatus.IN_PROGRESS);
        Specification<Ticket> onHoldSpec = (root, query, cb) ->
                root.get("status").in(Set.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.WAITING_VENDOR));
        Specification<Ticket> resolvedSpec = (root, query, cb) -> cb.equal(root.get("status"), TicketStatus.RESOLVED);

        return switch (role) {
            case "ADMIN" -> {
                Specification<Ticket> scope = Specification.where(null);
                yield new TicketSummaryResponse(
                        ticketRepository.count(scope),
                        ticketRepository.count(notTerminal),
                        ticketRepository.count(slaAtRisk),
                        ticketRepository.count(unassignedSpec),
                        ticketRepository.count(inProgressSpec),
                        ticketRepository.count(onHoldSpec),
                        ticketRepository.count(resolvedSpec),
                        weekOverWeekTrendPercent(scope, "createdAt"),
                        weekOverWeekTrendPercent(scope, "resolvedAt"));
            }
            case "MANAGER" -> {
                List<Long> departmentIds = resolveDepartmentIds(currentUser.getId());
                Specification<Ticket> scope = departmentIds.isEmpty()
                        ? Specification.where(null)
                        : (root, query, cb) -> root.get("department").get("id").in(departmentIds);
                yield new TicketSummaryResponse(
                        ticketRepository.count(scope),
                        ticketRepository.count(scope.and(notTerminal)),
                        ticketRepository.count(scope.and(slaAtRisk)),
                        ticketRepository.count(scope.and(unassignedSpec)),
                        ticketRepository.count(scope.and(inProgressSpec)),
                        ticketRepository.count(scope.and(onHoldSpec)),
                        ticketRepository.count(scope.and(resolvedSpec)),
                        weekOverWeekTrendPercent(scope, "createdAt"),
                        weekOverWeekTrendPercent(scope, "resolvedAt"));
            }
            case "AGENT" -> {
                Long userId = currentUser.getId();
                Specification<Ticket> byAssignee = (root, query, cb) -> cb.equal(root.get("assignee").get("id"), userId);
                // totalTickets and unassignedTickets are org-wide for agents (per
                // spec: an individual agent's personal queue has no meaningful
                // "unassigned" subset scoped to them) - only openTickets/
                // slaAtRiskTickets are scoped to the agent's own assignments.
                // inProgressTickets/onHoldTickets/resolvedTickets and
                // resolvedTicketsTrendPercent mirror the openTickets/slaAtRisk
                // by-assignee scoping; totalTicketsTrendPercent mirrors totalTickets'
                // org-wide scoping.
                yield new TicketSummaryResponse(
                        ticketRepository.count(Specification.where(null)),
                        ticketRepository.count(byAssignee.and(notTerminal)),
                        ticketRepository.count(byAssignee.and(slaAtRisk)),
                        ticketRepository.count(unassignedSpec),
                        ticketRepository.count(byAssignee.and(inProgressSpec)),
                        ticketRepository.count(byAssignee.and(onHoldSpec)),
                        ticketRepository.count(byAssignee.and(resolvedSpec)),
                        weekOverWeekTrendPercent(Specification.where(null), "createdAt"),
                        weekOverWeekTrendPercent(byAssignee, "resolvedAt"));
            }
            default -> {
                Long userId = currentUser.getId();
                Specification<Ticket> byRequester = (root, query, cb) -> cb.equal(root.get("requester").get("id"), userId);
                yield new TicketSummaryResponse(
                        ticketRepository.count(byRequester),
                        ticketRepository.count(byRequester.and(notTerminal)),
                        ticketRepository.count(byRequester.and(slaAtRisk)),
                        ticketRepository.count(byRequester.and(unassignedSpec)),
                        ticketRepository.count(byRequester.and(inProgressSpec)),
                        ticketRepository.count(byRequester.and(onHoldSpec)),
                        ticketRepository.count(byRequester.and(resolvedSpec)),
                        weekOverWeekTrendPercent(byRequester, "createdAt"),
                        weekOverWeekTrendPercent(byRequester, "resolvedAt"));
            }
        };
    }

    /**
     * Week-over-week trend for a created/resolved event count within the given
     * role scope: compares the count of matching tickets whose {@code dateField}
     * falls in the last 7 days against the 7 days before that, expressed as a
     * percent change rounded to 1 decimal place. Returns null (rather than a
     * divide-by-zero or an infinite/fabricated percentage) when the prior-week
     * count is zero.
     */
    private Double weekOverWeekTrendPercent(Specification<Ticket> scope, String dateField) {
        Instant now = Instant.now();
        Instant weekAgo = now.minus(Duration.ofDays(7));
        Instant twoWeeksAgo = now.minus(Duration.ofDays(14));
        Specification<Ticket> thisWeek = scope.and((root, query, cb) ->
                cb.greaterThanOrEqualTo(root.<Instant>get(dateField), weekAgo));
        Specification<Ticket> priorWeek = scope.and((root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.<Instant>get(dateField), twoWeeksAgo),
                cb.lessThan(root.<Instant>get(dateField), weekAgo)));
        long thisWeekCount = ticketRepository.count(thisWeek);
        long priorWeekCount = ticketRepository.count(priorWeek);
        if (priorWeekCount == 0L) {
            return null;
        }
        double percent = ((double) (thisWeekCount - priorWeekCount) / priorWeekCount) * 100.0;
        return Math.round(percent * 10.0) / 10.0;
    }

    /**
     * ADMIN > MANAGER > AGENT > REQUESTER role precedence, mirroring
     * ReportingService.resolveVariant exactly (duplicated locally for the same
     * reason as parseInstant/resolveDepartmentIds below).
     */
    private String resolveRole(ErpUserPrincipal principal) {
        Set<String> roles = principal.roles();
        if (roles.stream().anyMatch(r -> r.equalsIgnoreCase("ADMIN"))) {
            return "ADMIN";
        }
        if (roles.stream().anyMatch(r -> r.equalsIgnoreCase("MANAGER"))) {
            return "MANAGER";
        }
        if (roles.stream().anyMatch(r -> r.equalsIgnoreCase("AGENT"))) {
            return "AGENT";
        }
        return "REQUESTER";
    }

    /** Mirrors ReportingService.resolveDepartmentIds exactly - duplicated locally to avoid a circular service dependency (see summary() above). */
    private List<Long> resolveDepartmentIds(Long userId) {
        return departmentMemberRepository.findByUser_Id(userId).stream()
                .map(dm -> dm.getDepartment().getId())
                .distinct()
                .toList();
    }

    @Transactional
    public TicketResponse update(Long id, TicketUpdateRequest request, ErpUserPrincipal principal) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + id));
        User currentUser = userService.getOrCreateCurrentUser(principal);
        assertVisible(ticket, principal, currentUser);

        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
            Long oldId = ticket.getCategory() != null ? ticket.getCategory().getId() : null;
            if (!Objects.equals(oldId, category.getId())) {
                logChange(ticket, currentUser, "categoryId", oldId, category.getId());
                ticket.setCategory(category);
            }
        }

        if (request.title() != null && !request.title().equals(ticket.getTitle())) {
            logChange(ticket, currentUser, "title", ticket.getTitle(), request.title());
            ticket.setTitle(request.title());
        }

        if (request.description() != null && !request.description().equals(ticket.getDescription())) {
            logChange(ticket, currentUser, "description", ticket.getDescription(), request.description());
            ticket.setDescription(request.description());
        }

        if (request.priority() != null) {
            TicketPriority newPriority = parsePriority(request.priority());
            if (newPriority != ticket.getPriority()) {
                logChange(ticket, currentUser, "priority", ticket.getPriority(), newPriority);
                ticket.setPriority(newPriority);
            }
        }

        if (request.status() != null) {
            TicketStatus newStatus = parseStatus(request.status());
            TicketStatus oldStatus = ticket.getStatus();
            if (newStatus != oldStatus) {
                validateTransition(oldStatus, newStatus);
                logChange(ticket, currentUser, "status", oldStatus, newStatus);
                ticket.setStatus(newStatus);
                log.info("Ticket status changed", kv("ticketNumber", ticket.getTicketNumber()),
                        kv("actorId", currentUser.getId()), kv("oldStatus", oldStatus), kv("newStatus", newStatus));
                if (newStatus == TicketStatus.RESOLVED) {
                    ticket.setResolvedAt(Instant.now());
                    Map<String, Object> placeholders = new HashMap<>();
                    placeholders.put("ticketNumber", ticket.getTicketNumber());
                    placeholders.put("title", ticket.getTitle());
                    placeholders.put("priority", ticket.getPriority().name());
                    placeholders.put("resolvedBy", currentUser.getName());
                    notificationService.notify(ticket.getRequester(), ticket, "TICKET_RESOLVED", placeholders);
                    csatService.onTicketResolved(ticket);
                } else if (newStatus == TicketStatus.CLOSED) {
                    ticket.setClosedAt(Instant.now());
                }
            }
        }

        if (request.subStatus() != null && !request.subStatus().equals(ticket.getSubStatus())) {
            logChange(ticket, currentUser, "subStatus", ticket.getSubStatus(), request.subStatus());
            ticket.setSubStatus(request.subStatus());
        }

        if (request.assigneeId() != null) {
            User assignee = userService.getRequiredUser(request.assigneeId());
            Long oldId = ticket.getAssignee() != null ? ticket.getAssignee().getId() : null;
            if (!Objects.equals(oldId, assignee.getId())) {
                logChange(ticket, currentUser, "assigneeId", oldId, assignee.getId());
                ticket.setAssignee(assignee);
                Map<String, Object> placeholders = new HashMap<>();
                placeholders.put("ticketNumber", ticket.getTicketNumber());
                placeholders.put("title", ticket.getTitle());
                placeholders.put("assigneeName", assignee.getName());
                placeholders.put("priority", ticket.getPriority().name());
                notificationService.notify(assignee, ticket, "TICKET_ASSIGNED", placeholders);
            }
        }

        // departmentId is mandatory on Ticket (NOT NULL) - a null value here means
        // "leave unchanged" per the same convention as every other field above,
        // and is the only thing preventing a caller from ever clearing it.
        if (request.departmentId() != null) {
            Department department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new NotFoundException("Department not found: " + request.departmentId()));
            Long oldId = ticket.getDepartment() != null ? ticket.getDepartment().getId() : null;
            if (!Objects.equals(oldId, department.getId())) {
                logChange(ticket, currentUser, "departmentId", oldId, department.getId());
                ticket.setDepartment(department);
            }
        }

        if (request.tags() != null && !request.tags().equals(ticket.getTags())) {
            logChange(ticket, currentUser, "tags", ticket.getTags(), request.tags());
            ticket.setTags(request.tags());
        }

        if (request.customFields() != null && !request.customFields().equals(ticket.getCustomFields())) {
            logChange(ticket, currentUser, "customFields", ticket.getCustomFields(), request.customFields());
            ticket.setCustomFields(request.customFields());
        }

        ticket = ticketRepository.save(ticket);
        return toResponse(ticket);
    }

    private void logChange(Ticket ticket, User actor, String fieldName, Object oldValue, Object newValue) {
        activityLogService.logFieldChanged(ticket, actor, fieldName,
                oldValue != null ? oldValue.toString() : null,
                newValue != null ? newValue.toString() : null);
    }

    private void validateTransition(TicketStatus from, TicketStatus to) {
        Set<TicketStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BadRequestException("Invalid status transition: " + from + " -> " + to);
        }
    }

    private void assertVisible(Ticket ticket, ErpUserPrincipal principal, User currentUser) {
        if (isRequesterOnly(principal) && !ticket.getRequester().getId().equals(currentUser.getId())) {
            // 404 rather than 403 to avoid leaking existence of tickets the caller can't see.
            throw new NotFoundException("Ticket not found: " + ticket.getId());
        }
    }

    private boolean isRequesterOnly(ErpUserPrincipal principal) {
        Set<String> roles = principal.roles();
        boolean hasElevatedRole = roles.stream().anyMatch(r ->
                "AGENT".equalsIgnoreCase(r) || "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
        return !hasElevatedRole;
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

    private String generateTicketNumber() {
        Number sequenceValue = (Number) entityManager.createNativeQuery("select nextval('ticket_number_seq')")
                .getSingleResult();
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        return "TKT-" + year + "-" + String.format("%06d", sequenceValue.longValue());
    }

    private TicketResponse toResponse(Ticket ticket) {
        Category category = ticket.getCategory();
        User assignee = ticket.getAssignee();
        Department department = ticket.getDepartment();
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.getType().getId(),
                ticket.getType().getName(),
                category != null ? category.getId() : null,
                category != null ? category.getName() : null,
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getPriority().name(),
                ticket.getStatus().name(),
                ticket.getSubStatus(),
                ticket.getRequester().getId(),
                ticket.getRequester().getName(),
                assignee != null ? assignee.getId() : null,
                assignee != null ? assignee.getName() : null,
                department != null ? department.getId() : null,
                department != null ? department.getDepartmentName() : null,
                ticket.getSource().name(),
                ticket.getTags(),
                ticket.getCustomFields(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getFirstResponseDue(),
                ticket.getResolutionDue(),
                ticket.getFirstResponseAt(),
                ticket.getResolvedAt(),
                ticket.getClosedAt(),
                computeSlaStatus(ticket),
                ticket.getSlaPolicyId());
    }

    /**
     * NOT_APPLICABLE - no SLA policy resolved for this ticket.
     * MET - ticket resolved/closed at or before its resolution due date.
     * BREACHED - past the resolution due date and not (yet) resolved/closed,
     *   or resolved/closed after the due date.
     * AT_RISK - not yet past due, but past 80% of the elapsed-vs-total window.
     * ON_TRACK - everything else.
     *
     * Public (rather than private) so ReportingService's CSV export can reuse
     * the exact same computation as the /api/helpdesk/tickets/** responses instead of
     * duplicating it.
     */
    public String computeSlaStatus(Ticket ticket) {
        if (ticket.getSlaPolicyId() == null || ticket.getResolutionDue() == null) {
            return "NOT_APPLICABLE";
        }

        Instant resolutionDue = ticket.getResolutionDue();
        Instant resolvedOrClosed = ticket.getResolvedAt() != null ? ticket.getResolvedAt() : ticket.getClosedAt();
        if (resolvedOrClosed != null) {
            return !resolvedOrClosed.isAfter(resolutionDue) ? "MET" : "BREACHED";
        }

        Instant now = Instant.now();
        if (now.isAfter(resolutionDue)) {
            return "BREACHED";
        }

        Instant createdAt = ticket.getCreatedAt();
        long totalMinutes = Duration.between(createdAt, resolutionDue).toMinutes();
        if (totalMinutes <= 0) {
            return "AT_RISK";
        }
        long elapsedMinutes = Duration.between(createdAt, now).toMinutes();
        double ratio = (double) elapsedMinutes / totalMinutes;
        return ratio >= 0.8 ? "AT_RISK" : "ON_TRACK";
    }
}
