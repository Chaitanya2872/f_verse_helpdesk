package com.erp.ticketing.ticket.service;

import com.erp.ticketing.approval.service.ApprovalService;
import com.erp.ticketing.automation.model.AutomationRule;
import com.erp.ticketing.automation.service.AutomationService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.form.model.FormVersion;
import com.erp.ticketing.form.service.FormService;
import com.erp.ticketing.common.ConflictException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.csat.service.CsatService;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.integration.notification.event.TicketAssignedEvent;
import com.erp.ticketing.integration.notification.event.TicketCreatedEvent;
import com.erp.ticketing.integration.notification.event.TicketNotificationEvent;
import com.erp.ticketing.integration.notification.event.TicketResolvedEvent;
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
import com.erp.ticketing.sla.service.SlaPauseService;
import com.erp.ticketing.ticket.dto.PageResponse;
import com.erp.ticketing.ticket.dto.TicketFilterExtras;
import com.erp.ticketing.ticket.dto.TicketCreateRequest;
import com.erp.ticketing.ticket.dto.TicketResponse;
import com.erp.ticketing.ticket.dto.TicketSummaryResponse;
import com.erp.ticketing.ticket.dto.TicketUpdateRequest;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.Confidentiality;
import com.erp.ticketing.ticket.model.Level;
import com.erp.ticketing.ticket.model.PriorityMatrix;
import com.erp.ticketing.ticket.model.TicketAssignment;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketSource;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketAssignmentRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import com.erp.ticketing.workflow.service.WorkflowService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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

    /**
     * Shared by list()'s openOnly shorthand and slaStatus AT_RISK/ON_TRACK
     * predicates - a ticket in one of these statuses is done and no longer
     * subject to SLA monitoring (mirrors SlaMonitoringJob.TERMINAL_STATUSES).
     */
    private static final Set<TicketStatus> TERMINAL_STATUSES =
            EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

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
    private final TicketAccessService accessService;
    private final TicketAssignmentRepository assignmentRepository;
    private final WorkflowService workflowService;
    private final ApprovalService approvalService;
    private final SlaPauseService slaPauseService;
    private final FormService formService;
    private final AutomationService automationService;

    @PersistenceContext
    private EntityManager entityManager;

    /** Optional so the service can still be built by hand in unit tests; events are simply dropped without it. */
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    private void publish(TicketNotificationEvent event) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(event);
        }
    }

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
                          CsatService csatService,
                          TicketAccessService accessService,
                          TicketAssignmentRepository assignmentRepository,
                          WorkflowService workflowService,
                          ApprovalService approvalService,
                          SlaPauseService slaPauseService,
                          FormService formService,
                          AutomationService automationService) {
        this.automationService = automationService;
        this.formService = formService;
        this.slaPauseService = slaPauseService;
        this.workflowService = workflowService;
        this.approvalService = approvalService;
        this.accessService = accessService;
        this.assignmentRepository = assignmentRepository;
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

        if (type.getLifecycleStatus() != TicketType.Lifecycle.PUBLISHED) {
            throw new BadRequestException("'" + type.getName() + "' is not accepting new requests");
        }
        FormVersion form = formService.published(type.getId()).orElse(null);
        Map<String, Object> customFields = request.customFields();
        if (form != null) {
            customFields = formService.validate(form, customFields);
        }

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
        }

        User requesterUser = requester;
        if (request.requesterId() != null && !request.requesterId().equals(requester.getId())) {
            // On-behalf creation (BRS 4.4): staff only; the creator stays on record separately.
            if (accessService.isRequesterOnly(principal)) {
                throw new BadRequestException("Only staff can raise a ticket on behalf of another user");
            }
            requesterUser = userService.getRequiredUser(request.requesterId());
        }

        Ticket ticket = new Ticket();
        ticket.setType(type);
        ticket.setCategory(category);
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        Level impact = parseLevel(request.impact(), "impact");
        Level urgency = parseLevel(request.urgency(), "urgency");
        ticket.setImpact(impact);
        ticket.setUrgency(urgency);
        if (request.priority() != null && !request.priority().isBlank()) {
            ticket.setPriority(parsePriority(request.priority()));
        } else if (impact != null && urgency != null) {
            ticket.setPriority(PriorityMatrix.resolve(impact, urgency));
        } else {
            throw new BadRequestException("priority is required unless both impact and urgency are provided");
        }
        ticket.setStatus(TicketStatus.NEW);
        ticket.setRequester(requesterUser);
        ticket.setCreator(requester);
        // The request type sets the floor. Only staff may classify higher; nobody can go below it,
        // and a requester cannot hide their own ticket from the people who must work it.
        Confidentiality requested = accessService.isRequesterOnly(principal)
                ? Confidentiality.STANDARD : parseConfidentiality(request.confidentiality());
        ticket.setConfidentiality(requested.compareTo(type.getDefaultConfidentiality()) >= 0
                ? requested : type.getDefaultConfidentiality());
        ticket.setExternalRef(request.externalRef());
        ticket.setSource(requesterUser == requester ? TicketSource.PORTAL : TicketSource.AGENT);
        ticket.setTags(request.tags());
        ticket.setCustomFields(customFields);
        ticket.setFormVersionId(form != null ? form.getId() : null);

        // Routing: TicketCreateRequest has no department/assignee fields, so both
        // are always unset at this point - only overwrite with a matched rule's
        // (possibly partial) result.
        applyRouting(ticket, type, category);

        // The number carries the department's code, so it can only be issued once routing has picked one.
        ticket.setTicketNumber(generateTicketNumber(ticket.getDepartment()));

        // SLA: resolve a policy by type+priority and stamp due dates up front.
        applySla(ticket, type);

        ticket = ticketRepository.save(ticket);
        activityLogService.logCreated(ticket, requester);
        recordAssignment(ticket, requester, null, null, "Initial routing");

        log.info("Ticket created", kv("ticketNumber", ticket.getTicketNumber()), kv("requesterId", requester.getId()),
                kv("typeId", type.getId()), kv("priority", ticket.getPriority()));

        notifyOnCreate(ticket);
        automationService.fire(AutomationRule.Trigger.TICKET_CREATED, ticket, Map.of());

        publish(new TicketCreatedEvent(ticket.getId()));
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
            ticket.setSlaPolicyVersion(p.getVersion());
            SlaDueDates dueDates = slaCalculationService.computeDueDates(Instant.now(), p);
            ticket.setFirstResponseDue(dueDates.firstResponseDue());
            ticket.setResolutionDue(dueDates.resolutionDue());
        });
    }

    private void notifyOnCreate(Ticket ticket) {
        if (ticket.getConfidentiality() != Confidentiality.STANDARD) {
            notifyRestrictedCreate(ticket);
            return;
        }
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

    /** Restricted tickets never put their title in front of people who cannot open them (BRS 12.1). */
    private void notifyRestrictedCreate(Ticket ticket) {
        User recipient = ticket.getAssignee() != null ? ticket.getAssignee() : ticket.getDepartment().getManager();
        if (recipient == null) {
            log.warn("Restricted ticket {} has no assignee or department manager to notify", ticket.getTicketNumber());
            return;
        }
        notificationService.notify(recipient, ticket, "TICKET_CREATED",
                "New restricted ticket: " + ticket.getTicketNumber(),
                "A restricted ticket needs attention. Open it in the helpdesk to see the details.");
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
    public List<com.erp.ticketing.ticket.dto.AssignmentHistoryResponse> assignmentHistory(
            Long id, ErpUserPrincipal principal) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + id));
        assertVisible(ticket, principal, userService.getOrCreateCurrentUser(principal));
        return assignmentRepository.findByTicket_IdOrderByCreatedAtAsc(id).stream()
                .map(a -> new com.erp.ticketing.ticket.dto.AssignmentHistoryResponse(
                        a.getId(),
                        a.getFromDepartment() != null ? a.getFromDepartment().getDepartmentName() : null,
                        a.getToDepartment() != null ? a.getToDepartment().getDepartmentName() : null,
                        a.getFromAssignee() != null ? a.getFromAssignee().getName() : null,
                        a.getToAssignee() != null ? a.getToAssignee().getName() : null,
                        a.getActor() != null ? a.getActor().getName() : null,
                        a.getReason(),
                        a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> list(String status, String priority, Long typeId, Long categoryId,
                                              Long assigneeId, Long requesterId, Long departmentId, Boolean openOnly,
                                              Boolean unassigned, String slaStatus, String search, String createdFrom,
                                              String createdTo, TicketFilterExtras extras, Pageable pageable,
                                              ErpUserPrincipal principal) {
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
        spec = spec.and(buildExtrasSpec(extras == null ? TicketFilterExtras.NONE : extras));
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

        // Requesters see their own tickets; staff never see restricted or confidential
        // tickets they have not been granted (TicketAccessService).
        spec = spec.and(accessService.visibleTo(principal, currentUser));

        Page<Ticket> page = ticketRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    private Specification<Ticket> buildExtrasSpec(TicketFilterExtras x) {
        Specification<Ticket> spec = Specification.where(null);
        if (x.externalRef() != null && !x.externalRef().isBlank()) {
            String ref = x.externalRef().trim().toLowerCase();
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(cb.coalesce(root.<String>get("externalRef"), "")), ref));
        }
        if (x.confidentiality() != null && !x.confidentiality().isBlank()) {
            Confidentiality c = parseConfidentiality(x.confidentiality());
            spec = spec.and((root, query, cb) -> cb.equal(root.get("confidentiality"), c));
        }
        if (x.resolutionCode() != null && !x.resolutionCode().isBlank()) {
            String code = x.resolutionCode().trim().toUpperCase();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("resolutionCode"), code));
        }
        if (Boolean.TRUE.equals(x.reopened())) {
            spec = spec.and((root, query, cb) -> cb.greaterThan(root.<Integer>get("reopenCount"), 0));
        }
        if (x.source() != null && !x.source().isBlank()) {
            TicketSource source;
            try {
                source = TicketSource.valueOf(x.source().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Invalid source: " + x.source());
            }
            spec = spec.and((root, query, cb) -> cb.equal(root.get("source"), source));
        }
        return spec;
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
                    cb.like(cb.lower(cb.coalesce(root.<String>get("description"), "")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.<String>get("externalRef"), "")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.<String>get("resolutionSummary"), "")), pattern),
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
                        countVisible(scope),
                        countVisible(notTerminal),
                        countVisible(slaAtRisk),
                        countVisible(unassignedSpec),
                        countVisible(inProgressSpec),
                        countVisible(onHoldSpec),
                        countVisible(resolvedSpec),
                        weekOverWeekTrendPercent(scope, "createdAt"),
                        weekOverWeekTrendPercent(scope, "resolvedAt"));
            }
            case "MANAGER" -> {
                List<Long> departmentIds = resolveDepartmentIds(currentUser.getId());
                Specification<Ticket> scope = departmentIds.isEmpty()
                        ? Specification.where(null)
                        : (root, query, cb) -> root.get("department").get("id").in(departmentIds);
                yield new TicketSummaryResponse(
                        countVisible(scope),
                        countVisible(scope.and(notTerminal)),
                        countVisible(scope.and(slaAtRisk)),
                        countVisible(scope.and(unassignedSpec)),
                        countVisible(scope.and(inProgressSpec)),
                        countVisible(scope.and(onHoldSpec)),
                        countVisible(scope.and(resolvedSpec)),
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
                        countVisible(Specification.where(null)),
                        countVisible(byAssignee.and(notTerminal)),
                        countVisible(byAssignee.and(slaAtRisk)),
                        countVisible(unassignedSpec),
                        countVisible(byAssignee.and(inProgressSpec)),
                        countVisible(byAssignee.and(onHoldSpec)),
                        countVisible(byAssignee.and(resolvedSpec)),
                        weekOverWeekTrendPercent(Specification.where(null), "createdAt"),
                        weekOverWeekTrendPercent(byAssignee, "resolvedAt"));
            }
            default -> {
                Long userId = currentUser.getId();
                Specification<Ticket> byRequester = (root, query, cb) -> cb.equal(root.get("requester").get("id"), userId);
                yield new TicketSummaryResponse(
                        countVisible(byRequester),
                        countVisible(byRequester.and(notTerminal)),
                        countVisible(byRequester.and(slaAtRisk)),
                        countVisible(byRequester.and(unassignedSpec)),
                        countVisible(byRequester.and(inProgressSpec)),
                        countVisible(byRequester.and(onHoldSpec)),
                        countVisible(byRequester.and(resolvedSpec)),
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
        long thisWeekCount = countVisible(thisWeek);
        long priorWeekCount = countVisible(priorWeek);
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
    private long countVisible(Specification<Ticket> spec) {
        return ticketRepository.count(Specification.where(spec).and(accessService.currentVisibility()));
    }

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

        if (request.version() != null && !request.version().equals(ticket.getVersion())) {
            throw new ConflictException("Ticket " + ticket.getTicketNumber()
                    + " was changed by someone else - reload and retry");
        }
        if (isRequesterOnly(principal)) {
            guardRequesterUpdate(ticket, request);
        }
        Department previousDepartment = ticket.getDepartment();
        User previousAssignee = ticket.getAssignee();

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

        TicketStatus statusChangedTo = null;
        if (request.status() != null) {
            TicketStatus newStatus = parseStatus(request.status());
            TicketStatus oldStatus = ticket.getStatus();
            if (newStatus != oldStatus) {
                validateTransition(ticket.getType().getId(), oldStatus, newStatus);
                if (oldStatus == TicketStatus.WAITING_APPROVAL && approvalService.hasOpenTasks(ticket)) {
                    if (newStatus != TicketStatus.CANCELLED) {
                        throw new BadRequestException(
                                "Pending approvals must be decided or withdrawn before this ticket can move on");
                    }
                    approvalService.withdrawOpen(ticket, "Ticket cancelled");
                }
                if (newStatus == TicketStatus.CANCELLED && isBlank(request.reason())) {
                    throw new BadRequestException("A reason is required to cancel a ticket");
                }
                if (newStatus == TicketStatus.RESOLVED) {
                    String code = firstNonBlank(request.resolutionCode(), ticket.getResolutionCode());
                    String summary = firstNonBlank(request.resolutionSummary(), ticket.getResolutionSummary());
                    if (code == null || summary == null) {
                        throw new BadRequestException("resolutionCode and resolutionSummary are required to resolve a ticket");
                    }
                    ticket.setResolutionCode(code);
                    ticket.setResolutionSummary(summary);
                    ticket.setResolvedBy(currentUser);
                    logChange(ticket, currentUser, "resolution", null, code + ": " + summary);
                } else if (newStatus == TicketStatus.REOPENED) {
                    // Prior resolution stays in the activity log; the live fields restart.
                    ticket.setReopenCount(ticket.getReopenCount() + 1);
                    ticket.setResolvedAt(null);
                    ticket.setClosedAt(null);
                }
                logChange(ticket, currentUser, "status", oldStatus, newStatus);
                ticket.setStatus(newStatus);
                if (newStatus == TicketStatus.WAITING_APPROVAL) {
                    approvalService.start(ticket, currentUser);
                }
                slaPauseService.onStatusChange(ticket, oldStatus, newStatus, currentUser);
                statusChangedTo = newStatus;
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

        User newlyAssigned = null;
        if (request.assigneeId() != null) {
            User assignee = userService.getRequiredUser(request.assigneeId());
            Long oldId = ticket.getAssignee() != null ? ticket.getAssignee().getId() : null;
            if (!Objects.equals(oldId, assignee.getId())) {
                newlyAssigned = assignee;
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
            Map<String, Object> newFields = request.customFields();
            if (ticket.getFormVersionId() != null) {
                // Edits are held to the form version the ticket was submitted with, not the latest one.
                newFields = formService.validate(formService.getVersion(ticket.getFormVersionId()), newFields);
            }
            logChange(ticket, currentUser, "customFields", ticket.getCustomFields(), newFields);
            ticket.setCustomFields(newFields);
        }

        boolean ownershipChanged = !Objects.equals(previousAssignee, ticket.getAssignee())
                || !Objects.equals(previousDepartment, ticket.getDepartment());
        if (ownershipChanged) {
            if (request.status() == null
                    && (ticket.getStatus() == TicketStatus.NEW || ticket.getStatus() == TicketStatus.TRIAGE)
                    && ticket.getAssignee() != null) {
                logChange(ticket, currentUser, "status", ticket.getStatus(), TicketStatus.ASSIGNED);
                ticket.setStatus(TicketStatus.ASSIGNED);
            }
            recordAssignment(ticket, currentUser, previousDepartment, previousAssignee, request.reason());
        }

        ticket = ticketRepository.saveAndFlush(ticket);
        if (statusChangedTo != null) {
            automationService.fire(AutomationRule.Trigger.STATUS_CHANGED, ticket, Map.of());
            ticket = ticketRepository.saveAndFlush(ticket);
        }
        // Only genuine changes raise events: an unrelated edit, or re-saving the same handler, raises none.
        if (newlyAssigned != null) {
            publish(new TicketAssignedEvent(ticket.getId(), newlyAssigned.getId(), currentUser.getId(), Instant.now()));
        }
        if (statusChangedTo == TicketStatus.RESOLVED) {
            publish(new TicketResolvedEvent(ticket.getId(), ticket.getReopenCount()));
        }
        return toResponse(ticket);
    }

    /** Requesters may edit wording, supply information and cancel/confirm - never triage or ownership fields. */
    private void guardRequesterUpdate(Ticket ticket, TicketUpdateRequest request) {
        if (request.priority() != null || request.assigneeId() != null || request.departmentId() != null
                || request.subStatus() != null || request.confidentiality() != null
                || request.resolutionCode() != null || request.resolutionSummary() != null
                || request.impact() != null || request.urgency() != null || request.categoryId() != null) {
            throw new BadRequestException("Requesters cannot change classification, priority or ownership");
        }
        if (request.status() != null) {
            TicketStatus target = parseStatus(request.status());
            boolean cancelOwn = target == TicketStatus.CANCELLED;
            boolean afterResolution = ticket.getStatus() == TicketStatus.RESOLVED
                    && (target == TicketStatus.CLOSED || target == TicketStatus.REOPENED);
            boolean supplyInfo = ticket.getStatus() == TicketStatus.WAITING_CUSTOMER && target == TicketStatus.IN_PROGRESS;
            if (!(cancelOwn || afterResolution || supplyInfo)) {
                throw new BadRequestException("Requesters cannot move a ticket to " + target);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) {
            return a.trim();
        }
        return isBlank(b) ? null : b;
    }

    private void logChange(Ticket ticket, User actor, String fieldName, Object oldValue, Object newValue) {
        activityLogService.logFieldChanged(ticket, actor, fieldName,
                oldValue != null ? oldValue.toString() : null,
                newValue != null ? newValue.toString() : null);
    }

    private void validateTransition(Long ticketTypeId, TicketStatus from, TicketStatus to) {
        if (!workflowService.isAllowed(ticketTypeId, from, to)) {
            throw new BadRequestException("Invalid status transition: " + from + " -> " + to);
        }
    }

    private void assertVisible(Ticket ticket, ErpUserPrincipal principal, User currentUser) {
        // 404 rather than 403 so a guessed ID reveals nothing about tickets the caller cannot see.
        accessService.assertVisible(ticket, principal, currentUser);
    }

    private boolean isRequesterOnly(ErpUserPrincipal principal) {
        return accessService.isRequesterOnly(principal);
    }

    private Level parseLevel(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Level.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
    }

    private Confidentiality parseConfidentiality(String value) {
        if (value == null || value.isBlank()) {
            return Confidentiality.STANDARD;
        }
        try {
            return Confidentiality.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid confidentiality: " + value);
        }
    }

    private void recordAssignment(Ticket ticket, User actor, Department fromDepartment, User fromAssignee, String reason) {
        TicketAssignment row = new TicketAssignment();
        row.setTicket(ticket);
        row.setActor(actor);
        row.setFromDepartment(fromDepartment);
        row.setToDepartment(ticket.getDepartment());
        row.setFromAssignee(fromAssignee);
        row.setToAssignee(ticket.getAssignee());
        row.setReason(reason);
        assignmentRepository.save(row);
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

    /** CODE-YEAR-NUMBER, e.g. IT-2026-000036. The number is one global sequence, so it never repeats across departments. */
    private String generateTicketNumber(Department department) {
        Number sequenceValue = (Number) entityManager.createNativeQuery("select nextval('ticket_number_seq')")
                .getSingleResult();
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        String code = department != null && department.getCode() != null ? department.getCode() : "HD";
        return code + "-" + year + "-" + String.format("%06d", sequenceValue.longValue());
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
                ticket.getSlaPolicyId(),
                ticket.getPublicId() != null ? ticket.getPublicId().toString() : null,
                ticket.getVersion(),
                ticket.getCreator() != null ? ticket.getCreator().getId() : null,
                ticket.getCreator() != null ? ticket.getCreator().getName() : null,
                ticket.getConfidentiality().name(),
                ticket.getResolutionCode(),
                ticket.getResolutionSummary(),
                ticket.getResolvedBy() != null ? ticket.getResolvedBy().getId() : null,
                ticket.getResolvedBy() != null ? ticket.getResolvedBy().getName() : null,
                ticket.getReopenCount(),
                ticket.getImpact() != null ? ticket.getImpact().name() : null,
                ticket.getUrgency() != null ? ticket.getUrgency().name() : null,
                ticket.getExternalRef());
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
        if (ticket.getSlaPausedAt() != null) {
            return "PAUSED";
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
