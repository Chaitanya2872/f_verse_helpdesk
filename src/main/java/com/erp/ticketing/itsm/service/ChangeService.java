package com.erp.ticketing.itsm.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.itsm.dto.ItsmDto;
import com.erp.ticketing.itsm.model.ChangeRequest;
import com.erp.ticketing.itsm.repository.ChangeRequestRepository;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.model.Level;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.service.TicketAccessService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Change management (BRS 16.3). Standard changes are pre-approved templates; normal and emergency
 * changes need a manager or admin who is not the change owner (segregation of duties). A change
 * can only be scheduled once approved, and two changes touching the same service may not overlap.
 */
@Service
public class ChangeService {

    private final ChangeRequestRepository repository;
    private final TicketRepository ticketRepository;
    private final TicketAccessService accessService;
    private final UserService userService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public ChangeService(ChangeRequestRepository repository, TicketRepository ticketRepository,
                         TicketAccessService accessService, UserService userService, AuditService auditService,
                         JdbcTemplate jdbc) {
        this.repository = repository;
        this.ticketRepository = ticketRepository;
        this.accessService = accessService;
        this.userService = userService;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    @Transactional
    public ItsmDto.Change create(ItsmDto.ChangeSave request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        if (ProblemService.blank(request.title()) || ProblemService.blank(request.changeType())) {
            throw new BadRequestException("title and changeType are required");
        }
        ChangeRequest c = new ChangeRequest();
        c.setChangeNumber(nextNumber());
        c.setOwner(me);
        apply(c, request);
        c = repository.save(c);
        auditService.record(me, "CHANGE_CREATED", "CHANGE", c.getId(), null, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change update(Long id, ItsmDto.ChangeSave request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        ProblemService.checkVersion(c.getVersion(), request.version(), c.getChangeNumber());
        if (c.getStatus() != ChangeRequest.Status.DRAFT && c.getStatus() != ChangeRequest.Status.REJECTED) {
            throw new BadRequestException("Only a draft or rejected change can be edited");
        }
        Map<String, Object> before = snapshot(c);
        apply(c, request);
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, "CHANGE_UPDATED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    /** DRAFT/REJECTED -> SUBMITTED (or straight to APPROVED for pre-approved standard changes). */
    @Transactional
    public ItsmDto.Change submit(Long id, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        requireOwnerOrManager(c, me, principal);
        if (c.getStatus() != ChangeRequest.Status.DRAFT && c.getStatus() != ChangeRequest.Status.REJECTED) {
            throw new BadRequestException("Only a draft or rejected change can be submitted");
        }
        List<String> missing = new java.util.ArrayList<>();
        if (ProblemService.blank(c.getReason())) missing.add("reason");
        if (ProblemService.blank(c.getScope())) missing.add("scope");
        if (c.getRisk() == null) missing.add("risk");
        if (c.getImpact() == null) missing.add("impact");
        if (ProblemService.blank(c.getAffectedServices())) missing.add("affected services");
        if (ProblemService.blank(c.getImplementationPlan())) missing.add("implementation plan");
        if (ProblemService.blank(c.getRollbackPlan())) missing.add("rollback plan");
        if (c.getChangeType() != ChangeRequest.Type.EMERGENCY && ProblemService.blank(c.getTestPlan())) {
            missing.add("test plan");
        }
        if (!missing.isEmpty()) {
            throw new BadRequestException("Before submitting, complete: " + String.join(", ", missing));
        }
        Map<String, Object> before = snapshot(c);
        if (c.getChangeType() == ChangeRequest.Type.STANDARD) {
            c.setStatus(ChangeRequest.Status.APPROVED);
            c.setApprovedAt(Instant.now());
            c.setApprovalComment("Pre-approved standard change");
        } else {
            c.setStatus(ChangeRequest.Status.SUBMITTED);
        }
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, "CHANGE_SUBMITTED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change decide(Long id, ItsmDto.ChangeDecision decision, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        if (!isManagerial(principal)) {
            throw new BadRequestException("Only a manager or admin can approve or reject changes");
        }
        if (c.getOwner() != null && c.getOwner().getId().equals(me.getId())) {
            throw new BadRequestException("You cannot approve or reject your own change");
        }
        if (c.getStatus() != ChangeRequest.Status.SUBMITTED) {
            throw new BadRequestException("Only a submitted change can be decided");
        }
        if (!decision.approve() && ProblemService.blank(decision.comment())) {
            throw new BadRequestException("A comment is required to reject a change");
        }
        Map<String, Object> before = snapshot(c);
        c.setStatus(decision.approve() ? ChangeRequest.Status.APPROVED : ChangeRequest.Status.REJECTED);
        c.setApprovedBy(me);
        c.setApprovedAt(Instant.now());
        c.setApprovalComment(decision.comment());
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, decision.approve() ? "CHANGE_APPROVED" : "CHANGE_REJECTED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change schedule(Long id, ItsmDto.ChangeSchedule request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        requireOwnerOrManager(c, me, principal);
        if (c.getStatus() != ChangeRequest.Status.APPROVED && c.getStatus() != ChangeRequest.Status.SCHEDULED) {
            throw new BadRequestException("A change must be approved before it is scheduled");
        }
        if (request.start() == null || request.end() == null || !request.end().isAfter(request.start())) {
            throw new BadRequestException("A schedule needs a start and a later end");
        }
        if (request.start().isBefore(Instant.now()) && c.getChangeType() != ChangeRequest.Type.EMERGENCY) {
            throw new BadRequestException("A change cannot be scheduled in the past");
        }
        List<String> conflicts = conflicts(c, request.start(), request.end());
        if (!conflicts.isEmpty()) {
            throw new BadRequestException("Schedule conflicts with " + String.join(", ", conflicts));
        }
        Map<String, Object> before = snapshot(c);
        c.setScheduledStart(request.start());
        c.setScheduledEnd(request.end());
        c.setStatus(ChangeRequest.Status.SCHEDULED);
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, "CHANGE_SCHEDULED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change start(Long id, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        requireOwnerOrManager(c, me, principal);
        if (c.getStatus() != ChangeRequest.Status.SCHEDULED) {
            throw new BadRequestException("Only a scheduled change can be started");
        }
        Map<String, Object> before = snapshot(c);
        c.setStatus(ChangeRequest.Status.IMPLEMENTING);
        c.setActualStart(Instant.now());
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, "CHANGE_STARTED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change finish(Long id, ItsmDto.ChangeFinish request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        requireOwnerOrManager(c, me, principal);
        if (c.getStatus() != ChangeRequest.Status.IMPLEMENTING) {
            throw new BadRequestException("Only a change in progress can be completed or failed");
        }
        if (ProblemService.blank(request.outcome())) {
            throw new BadRequestException("Record the outcome of the change");
        }
        Map<String, Object> before = snapshot(c);
        c.setStatus(request.success() ? ChangeRequest.Status.COMPLETED : ChangeRequest.Status.FAILED);
        c.setActualEnd(Instant.now());
        c.setOutcome(request.outcome());
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, request.success() ? "CHANGE_COMPLETED" : "CHANGE_FAILED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change cancel(Long id, String reason, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        requireOwnerOrManager(c, me, principal);
        Set<ChangeRequest.Status> cancellable = EnumSet.of(ChangeRequest.Status.DRAFT, ChangeRequest.Status.SUBMITTED,
                ChangeRequest.Status.APPROVED, ChangeRequest.Status.SCHEDULED, ChangeRequest.Status.REJECTED);
        if (!cancellable.contains(c.getStatus())) {
            throw new BadRequestException("A change that is " + c.getStatus() + " cannot be cancelled");
        }
        if (ProblemService.blank(reason)) {
            throw new BadRequestException("A reason is required to cancel a change");
        }
        Map<String, Object> before = snapshot(c);
        c.setStatus(ChangeRequest.Status.CANCELLED);
        c.setOutcome(reason);
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        auditService.record(me, "CHANGE_CANCELLED", "CHANGE", id, before, snapshot(c));
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change linkTicket(Long id, Long ticketId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        Ticket t = ticketRepository.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(t, principal, me);
        jdbc.update("INSERT INTO change_tickets (change_id, ticket_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, ticketId);
        return toDto(c, principal, me);
    }

    @Transactional
    public ItsmDto.Change linkProblem(Long id, Long problemId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        ChangeRequest c = require(id);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM problems WHERE id = ?", Integer.class, problemId);
        if (exists == null || exists == 0) {
            throw new NotFoundException("Problem not found: " + problemId);
        }
        jdbc.update("INSERT INTO change_problems (change_id, problem_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, problemId);
        return toDto(c, principal, me);
    }

    @Transactional(readOnly = true)
    public ItsmDto.Change get(Long id, ErpUserPrincipal principal) {
        return toDto(require(id), principal, userService.getOrCreateCurrentUser(principal));
    }

    @Transactional(readOnly = true)
    public List<ItsmDto.Change> list(String status, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        return repository.findAll().stream()
                .filter(c -> status == null || status.isBlank() || c.getStatus().name().equalsIgnoreCase(status))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .map(c -> toDto(c, principal, me)).toList();
    }

    /** Scheduled and running changes whose window touches [from, to]: the change calendar. */
    @Transactional(readOnly = true)
    public List<ItsmDto.Change> calendar(Instant from, Instant to, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        return repository.findAll().stream()
                .filter(c -> (c.getStatus() == ChangeRequest.Status.SCHEDULED || c.getStatus() == ChangeRequest.Status.IMPLEMENTING)
                        && c.getScheduledStart() != null && c.getScheduledEnd() != null
                        && !c.getScheduledEnd().isBefore(from) && !c.getScheduledStart().isAfter(to))
                .sorted((a, b) -> a.getScheduledStart().compareTo(b.getScheduledStart()))
                .map(c -> toDto(c, principal, me)).toList();
    }

    // ------------------------------------------------------------------ helpers

    private List<String> conflicts(ChangeRequest c, Instant start, Instant end) {
        Set<String> mine = services(c.getAffectedServices());
        return repository.findAll().stream()
                .filter(o -> !o.getId().equals(c.getId()))
                .filter(o -> o.getStatus() == ChangeRequest.Status.SCHEDULED || o.getStatus() == ChangeRequest.Status.IMPLEMENTING)
                .filter(o -> o.getScheduledStart() != null && o.getScheduledEnd() != null
                        && o.getScheduledStart().isBefore(end) && o.getScheduledEnd().isAfter(start))
                .filter(o -> services(o.getAffectedServices()).stream().anyMatch(mine::contains))
                .map(ChangeRequest::getChangeNumber)
                .toList();
    }

    private static Set<String> services(String csv) {
        return csv == null ? Set.of() : Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private void apply(ChangeRequest c, ItsmDto.ChangeSave r) {
        if (r.title() != null) {
            c.setTitle(r.title().trim());
        }
        if (r.changeType() != null) {
            try {
                c.setChangeType(ChangeRequest.Type.valueOf(r.changeType().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("changeType must be STANDARD, NORMAL or EMERGENCY");
            }
        }
        if (r.reason() != null) c.setReason(r.reason());
        if (r.scope() != null) c.setScope(r.scope());
        if (r.risk() != null) c.setRisk(level(r.risk(), "risk"));
        if (r.impact() != null) c.setImpact(level(r.impact(), "impact"));
        if (r.affectedServices() != null) c.setAffectedServices(r.affectedServices());
        if (r.implementationPlan() != null) c.setImplementationPlan(r.implementationPlan());
        if (r.testPlan() != null) c.setTestPlan(r.testPlan());
        if (r.rollbackPlan() != null) c.setRollbackPlan(r.rollbackPlan());
    }

    private Level level(String v, String field) {
        try {
            return Level.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(field + " must be LOW, MEDIUM or HIGH");
        }
    }

    private boolean isManagerial(ErpUserPrincipal p) {
        return p.roles().stream().anyMatch(r -> "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
    }

    private void requireOwnerOrManager(ChangeRequest c, User me, ErpUserPrincipal principal) {
        boolean owner = c.getOwner() != null && c.getOwner().getId().equals(me.getId());
        if (!owner && !isManagerial(principal)) {
            throw new NotFoundException("Change not found: " + c.getId());
        }
    }

    private ChangeRequest require(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Change not found: " + id));
    }

    private String nextNumber() {
        Long n = jdbc.queryForObject("SELECT nextval('change_number_seq')", Long.class);
        return "CHG-" + Instant.now().atZone(ZoneOffset.UTC).getYear() + "-" + String.format("%06d", n);
    }

    private Map<String, Object> snapshot(ChangeRequest c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", c.getChangeNumber());
        m.put("title", c.getTitle());
        m.put("type", c.getChangeType().name());
        m.put("status", c.getStatus().name());
        m.put("scheduledStart", c.getScheduledStart() == null ? null : c.getScheduledStart().toString());
        m.put("scheduledEnd", c.getScheduledEnd() == null ? null : c.getScheduledEnd().toString());
        m.put("outcome", c.getOutcome());
        return m;
    }

    private ItsmDto.Change toDto(ChangeRequest c, ErpUserPrincipal principal, User me) {
        List<Long> ticketIds = jdbc.queryForList("SELECT ticket_id FROM change_tickets WHERE change_id = ?", Long.class, c.getId());
        List<ItsmDto.TicketRef> tickets = ticketRepository.findAllById(ticketIds).stream()
                .filter(t -> accessService.canView(t, principal, me))
                .map(t -> new ItsmDto.TicketRef(t.getId(), t.getTicketNumber(), t.getTitle(), t.getStatus().name()))
                .toList();
        List<Long> problemIds = jdbc.queryForList("SELECT problem_id FROM change_problems WHERE change_id = ?", Long.class, c.getId());
        return new ItsmDto.Change(c.getId(), c.getChangeNumber(), c.getTitle(), c.getChangeType().name(), c.getStatus().name(),
                c.getReason(), c.getScope(), c.getRisk() == null ? null : c.getRisk().name(),
                c.getImpact() == null ? null : c.getImpact().name(), c.getAffectedServices(), c.getImplementationPlan(),
                c.getTestPlan(), c.getRollbackPlan(), c.getScheduledStart(), c.getScheduledEnd(), c.getActualStart(),
                c.getActualEnd(), c.getOutcome(), c.getOwner() != null ? c.getOwner().getId() : null,
                c.getOwner() != null ? c.getOwner().getName() : null,
                c.getApprovedBy() != null ? c.getApprovedBy().getName() : null, c.getApprovedAt(),
                c.getApprovalComment(), c.getVersion(), c.getCreatedAt(), tickets, problemIds);
    }
}
