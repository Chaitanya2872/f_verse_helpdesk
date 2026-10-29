package com.erp.ticketing.itsm.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.ConflictException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.itsm.dto.ItsmDto;
import com.erp.ticketing.itsm.model.Problem;
import com.erp.ticketing.itsm.repository.ProblemRepository;
import com.erp.ticketing.security.ErpUserPrincipal;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Problem management (BRS 16.2): a root-cause record that groups incidents. Its own lifecycle
 * (open, investigating, known error, resolved, closed) is independent of any linked ticket, and a
 * known error must carry a workaround while a resolved problem must carry a root cause and a fix.
 */
@Service
public class ProblemService {

    private static final Map<Problem.Status, Set<Problem.Status>> FLOW = new EnumMap<>(Problem.Status.class);

    static {
        FLOW.put(Problem.Status.OPEN, EnumSet.of(Problem.Status.INVESTIGATING, Problem.Status.CLOSED));
        FLOW.put(Problem.Status.INVESTIGATING,
                EnumSet.of(Problem.Status.KNOWN_ERROR, Problem.Status.RESOLVED, Problem.Status.CLOSED));
        FLOW.put(Problem.Status.KNOWN_ERROR, EnumSet.of(Problem.Status.RESOLVED, Problem.Status.INVESTIGATING));
        FLOW.put(Problem.Status.RESOLVED, EnumSet.of(Problem.Status.CLOSED, Problem.Status.INVESTIGATING));
        FLOW.put(Problem.Status.CLOSED, EnumSet.noneOf(Problem.Status.class));
    }

    private final ProblemRepository repository;
    private final TicketRepository ticketRepository;
    private final TicketAccessService accessService;
    private final UserService userService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public ProblemService(ProblemRepository repository, TicketRepository ticketRepository,
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
    public ItsmDto.Problem create(ItsmDto.ProblemSave request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        if (request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("title is required");
        }
        Problem p = new Problem();
        p.setProblemNumber(nextNumber("PRB", "problem_number_seq"));
        p.setCreatedBy(me);
        apply(p, request);
        if (p.getOwner() == null) {
            p.setOwner(me);
        }
        p = repository.save(p);
        auditService.record(me, "PROBLEM_CREATED", "PROBLEM", p.getId(), null, snapshot(p));
        return toDto(p, principal, me);
    }

    @Transactional
    public ItsmDto.Problem update(Long id, ItsmDto.ProblemSave request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Problem p = require(id);
        checkVersion(p.getVersion(), request.version(), p.getProblemNumber());
        if (p.getStatus() == Problem.Status.CLOSED) {
            throw new BadRequestException("A closed problem cannot be edited");
        }
        Map<String, Object> before = snapshot(p);
        apply(p, request);
        p.setUpdatedAt(Instant.now());
        p = repository.saveAndFlush(p);
        auditService.record(me, "PROBLEM_UPDATED", "PROBLEM", id, before, snapshot(p));
        return toDto(p, principal, me);
    }

    @Transactional
    public ItsmDto.Problem changeStatus(Long id, ItsmDto.StatusRequest request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Problem p = require(id);
        checkVersion(p.getVersion(), request.version(), p.getProblemNumber());
        Problem.Status target;
        try {
            target = Problem.Status.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Unknown problem status: " + request.status());
        }
        if (!FLOW.get(p.getStatus()).contains(target)) {
            throw new BadRequestException("Invalid problem status change: " + p.getStatus() + " -> " + target);
        }
        if (target == Problem.Status.KNOWN_ERROR && blank(p.getWorkaround())) {
            throw new BadRequestException("A known error needs a documented workaround");
        }
        if (target == Problem.Status.RESOLVED && (blank(p.getRootCause()) || blank(p.getPermanentFix()))) {
            throw new BadRequestException("Resolving a problem needs the root cause and the permanent fix");
        }
        Map<String, Object> before = snapshot(p);
        p.setStatus(target);
        p.setUpdatedAt(Instant.now());
        p = repository.saveAndFlush(p);
        auditService.record(me, "PROBLEM_STATUS_CHANGED", "PROBLEM", id, before, snapshot(p));
        return toDto(p, principal, me);
    }

    @Transactional
    public ItsmDto.Problem linkTicket(Long id, Long ticketId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Problem p = require(id);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, me);
        jdbc.update("INSERT INTO problem_tickets (problem_id, ticket_id, linked_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                id, ticketId, me.getId());
        auditService.record(me, "PROBLEM_TICKET_LINKED", "PROBLEM", id, null, Map.of("ticketId", ticketId));
        return toDto(p, principal, me);
    }

    @Transactional
    public ItsmDto.Problem unlinkTicket(Long id, Long ticketId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Problem p = require(id);
        jdbc.update("DELETE FROM problem_tickets WHERE problem_id = ? AND ticket_id = ?", id, ticketId);
        auditService.record(me, "PROBLEM_TICKET_UNLINKED", "PROBLEM", id, Map.of("ticketId", ticketId), null);
        return toDto(p, principal, me);
    }

    @Transactional(readOnly = true)
    public ItsmDto.Problem get(Long id, ErpUserPrincipal principal) {
        return toDto(require(id), principal, userService.getOrCreateCurrentUser(principal));
    }

    @Transactional(readOnly = true)
    public List<ItsmDto.Problem> list(String status, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        return repository.findAll().stream()
                .filter(p -> status == null || status.isBlank() || p.getStatus().name().equalsIgnoreCase(status))
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .map(p -> toDto(p, principal, me)).toList();
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Problem p, ItsmDto.ProblemSave r) {
        if (r.title() != null) {
            if (r.title().isBlank()) {
                throw new BadRequestException("title cannot be blank");
            }
            p.setTitle(r.title().trim());
        }
        if (r.description() != null) {
            p.setDescription(r.description());
        }
        if (r.symptoms() != null) {
            p.setSymptoms(r.symptoms());
        }
        if (r.rootCause() != null) {
            p.setRootCause(r.rootCause());
        }
        if (r.workaround() != null) {
            p.setWorkaround(r.workaround());
        }
        if (r.permanentFix() != null) {
            p.setPermanentFix(r.permanentFix());
        }
        if (r.ownerId() != null) {
            p.setOwner(userService.getRequiredUser(r.ownerId()));
        }
        if (r.reviewDate() != null) {
            p.setReviewDate(r.reviewDate());
        }
    }

    private Problem require(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Problem not found: " + id));
    }

    private String nextNumber(String prefix, String sequence) {
        Long n = jdbc.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        return prefix + "-" + Instant.now().atZone(ZoneOffset.UTC).getYear() + "-" + String.format("%06d", n);
    }

    static void checkVersion(Long current, Long supplied, String number) {
        if (supplied != null && !supplied.equals(current)) {
            throw new ConflictException(number + " was changed by someone else - reload and retry");
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private Map<String, Object> snapshot(Problem p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", p.getProblemNumber());
        m.put("title", p.getTitle());
        m.put("status", p.getStatus().name());
        m.put("rootCause", p.getRootCause());
        m.put("workaround", p.getWorkaround());
        m.put("permanentFix", p.getPermanentFix());
        m.put("ownerId", p.getOwner() != null ? p.getOwner().getId() : null);
        return m;
    }

    private ItsmDto.Problem toDto(Problem p, ErpUserPrincipal principal, User me) {
        // Only tickets the caller may open are named; the count still reflects every linked ticket.
        List<Long> ticketIds = jdbc.queryForList("SELECT ticket_id FROM problem_tickets WHERE problem_id = ?", Long.class, p.getId());
        List<ItsmDto.TicketRef> visible = ticketRepository.findAllById(ticketIds).stream()
                .filter(t -> accessService.canView(t, principal, me))
                .map(t -> new ItsmDto.TicketRef(t.getId(), t.getTicketNumber(), t.getTitle(), t.getStatus().name()))
                .toList();
        List<ItsmDto.ChangeRef> changes = jdbc.query(
                "SELECT c.id, c.change_number, c.title, c.status FROM change_requests c "
                        + "JOIN change_problems cp ON cp.change_id = c.id WHERE cp.problem_id = ? ORDER BY c.id",
                (rs, i) -> new ItsmDto.ChangeRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), p.getId());
        return new ItsmDto.Problem(p.getId(), p.getProblemNumber(), p.getTitle(), p.getDescription(), p.getStatus().name(),
                p.getSymptoms(), p.getRootCause(), p.getWorkaround(), p.getPermanentFix(),
                p.getOwner() != null ? p.getOwner().getId() : null, p.getOwner() != null ? p.getOwner().getName() : null,
                p.getReviewDate(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt(),
                ticketIds.size(), visible, changes);
    }
}
