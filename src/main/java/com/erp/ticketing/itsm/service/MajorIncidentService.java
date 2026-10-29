package com.erp.ticketing.itsm.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.itsm.dto.ItsmDto;
import com.erp.ticketing.itsm.model.MajorIncident;
import com.erp.ticketing.itsm.repository.MajorIncidentRepository;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.service.TicketAccessService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
 * Major incident handling (BRS 8.3). Only a manager or admin can declare one. It groups the
 * individual tickets, keeps a timeline of updates on a set cadence, separates what is shared with
 * requesters from internal notes, and cannot be closed without a post-incident review.
 */
@Service
public class MajorIncidentService {

    private static final Map<MajorIncident.Status, Set<MajorIncident.Status>> FLOW =
            new EnumMap<>(MajorIncident.Status.class);

    static {
        FLOW.put(MajorIncident.Status.DECLARED, EnumSet.of(MajorIncident.Status.MITIGATED, MajorIncident.Status.RESOLVED));
        FLOW.put(MajorIncident.Status.MITIGATED, EnumSet.of(MajorIncident.Status.RESOLVED, MajorIncident.Status.DECLARED));
        FLOW.put(MajorIncident.Status.RESOLVED, EnumSet.of(MajorIncident.Status.CLOSED, MajorIncident.Status.DECLARED));
        FLOW.put(MajorIncident.Status.CLOSED, EnumSet.noneOf(MajorIncident.Status.class));
    }

    private final MajorIncidentRepository repository;
    private final TicketRepository ticketRepository;
    private final TicketAccessService accessService;
    private final UserService userService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public MajorIncidentService(MajorIncidentRepository repository, TicketRepository ticketRepository,
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
    public ItsmDto.Incident declare(ItsmDto.IncidentDeclare request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        if (!isManagerial(principal)) {
            throw new BadRequestException("Only a manager or admin can declare a major incident");
        }
        if (ProblemService.blank(request.title()) || ProblemService.blank(request.reason()) || request.severity() == null) {
            throw new BadRequestException("title, severity and reason are required");
        }
        MajorIncident m = new MajorIncident();
        m.setIncidentNumber(nextNumber());
        m.setTitle(request.title().trim());
        m.setReason(request.reason());
        try {
            m.setSeverity(MajorIncident.Severity.valueOf(request.severity().trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("severity must be SEV1, SEV2 or SEV3");
        }
        m.setScope(request.scope());
        m.setAffectedServices(request.affectedServices());
        m.setCustomerImpact(request.customerImpact());
        m.setDeclaredBy(me);
        m.setIncidentLead(request.incidentLeadId() != null ? userService.getRequiredUser(request.incidentLeadId()) : me);
        if (request.commsLeadId() != null) {
            m.setCommsLead(userService.getRequiredUser(request.commsLeadId()));
        }
        if (request.updateCadenceMinutes() != null) {
            if (request.updateCadenceMinutes() < 5 || request.updateCadenceMinutes() > 1440) {
                throw new BadRequestException("updateCadenceMinutes must be between 5 and 1440");
            }
            m.setUpdateCadenceMinutes(request.updateCadenceMinutes());
        }
        m.setNextUpdateDueAt(Instant.now().plus(Duration.ofMinutes(m.getUpdateCadenceMinutes())));
        m = repository.save(m);
        if (request.ticketIds() != null) {
            for (Long ticketId : request.ticketIds()) {
                link(m, ticketId, principal, me);
            }
        }
        auditService.record(me, "MAJOR_INCIDENT_DECLARED", "MAJOR_INCIDENT", m.getId(), null, snapshot(m));
        return toDto(m, principal, me);
    }

    @Transactional
    public ItsmDto.Incident postUpdate(Long id, ItsmDto.IncidentUpdateRequest request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        MajorIncident m = require(id);
        if (m.getStatus() == MajorIncident.Status.CLOSED) {
            throw new BadRequestException("A closed incident takes no further updates");
        }
        if (ProblemService.blank(request.message())) {
            throw new BadRequestException("message is required");
        }
        jdbc.update("INSERT INTO major_incident_updates (incident_id, author_id, message, is_public) VALUES (?, ?, ?, ?)",
                id, me.getId(), request.message().trim(), Boolean.TRUE.equals(request.isPublic()));
        m.setNextUpdateDueAt(Instant.now().plus(Duration.ofMinutes(m.getUpdateCadenceMinutes())));
        m.setUpdatedAt(Instant.now());
        repository.saveAndFlush(m);
        return toDto(m, principal, me);
    }

    @Transactional
    public ItsmDto.Incident changeStatus(Long id, ItsmDto.IncidentStatusRequest request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        MajorIncident m = require(id);
        ProblemService.checkVersion(m.getVersion(), request.version(), m.getIncidentNumber());
        MajorIncident.Status target;
        try {
            target = MajorIncident.Status.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BadRequestException("Unknown incident status: " + request.status());
        }
        if (!FLOW.get(m.getStatus()).contains(target)) {
            throw new BadRequestException("Invalid incident status change: " + m.getStatus() + " -> " + target);
        }
        if (request.postIncidentReview() != null) {
            m.setPostIncidentReview(request.postIncidentReview());
        }
        if (request.actionItems() != null) {
            m.setActionItems(request.actionItems());
        }
        if (target == MajorIncident.Status.CLOSED && ProblemService.blank(m.getPostIncidentReview())) {
            throw new BadRequestException("A post-incident review is required before an incident can be closed");
        }
        Map<String, Object> before = snapshot(m);
        Instant now = Instant.now();
        m.setStatus(target);
        switch (target) {
            case MITIGATED -> m.setMitigatedAt(now);
            case RESOLVED -> m.setResolvedAt(now);
            case CLOSED -> {
                m.setClosedAt(now);
                m.setNextUpdateDueAt(null);
            }
            case DECLARED -> {
                // Reopened after a premature mitigation or resolution.
                m.setMitigatedAt(null);
                m.setResolvedAt(null);
                m.setNextUpdateDueAt(now.plus(Duration.ofMinutes(m.getUpdateCadenceMinutes())));
            }
            default -> {
            }
        }
        m.setUpdatedAt(now);
        m = repository.saveAndFlush(m);
        auditService.record(me, "MAJOR_INCIDENT_STATUS_CHANGED", "MAJOR_INCIDENT", id, before, snapshot(m));
        return toDto(m, principal, me);
    }

    @Transactional
    public ItsmDto.Incident linkTicket(Long id, Long ticketId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        MajorIncident m = require(id);
        link(m, ticketId, principal, me);
        return toDto(m, principal, me);
    }

    @Transactional(readOnly = true)
    public ItsmDto.Incident get(Long id, ErpUserPrincipal principal) {
        return toDto(require(id), principal, userService.getOrCreateCurrentUser(principal));
    }

    @Transactional(readOnly = true)
    public List<ItsmDto.Incident> list(boolean activeOnly, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        return repository.findAll().stream()
                .filter(m -> !activeOnly || m.getStatus() != MajorIncident.Status.CLOSED)
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .map(m -> toDto(m, principal, me)).toList();
    }

    /** Active incidents as requesters may see them: customer-facing summary and public updates only. */
    @Transactional(readOnly = true)
    public List<ItsmDto.PublicIncident> publicView() {
        return repository.findAll().stream()
                .filter(m -> m.getStatus() != MajorIncident.Status.CLOSED)
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .map(m -> new ItsmDto.PublicIncident(m.getIncidentNumber(), m.getTitle(), m.getStatus().name(),
                        m.getSeverity().name(), m.getCustomerImpact(), m.getStartedAt(),
                        updates(m.getId(), true)))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private void link(MajorIncident m, Long ticketId, ErpUserPrincipal principal, User me) {
        Ticket t = ticketRepository.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(t, principal, me);
        jdbc.update("INSERT INTO major_incident_tickets (incident_id, ticket_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                m.getId(), ticketId);
    }

    private List<ItsmDto.IncidentUpdate> updates(Long incidentId, boolean publicOnly) {
        return jdbc.query("SELECT u.id, a.name, u.message, u.is_public, u.created_at FROM major_incident_updates u "
                        + "LEFT JOIN users a ON a.id = u.author_id WHERE u.incident_id = ? "
                        + (publicOnly ? "AND u.is_public " : "") + "ORDER BY u.created_at, u.id",
                (rs, i) -> new ItsmDto.IncidentUpdate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getTimestamp(5).toInstant()), incidentId);
    }

    private boolean isManagerial(ErpUserPrincipal p) {
        return p.roles().stream().anyMatch(r -> "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
    }

    private MajorIncident require(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Major incident not found: " + id));
    }

    private String nextNumber() {
        Long n = jdbc.queryForObject("SELECT nextval('major_incident_number_seq')", Long.class);
        return "MI-" + Instant.now().atZone(ZoneOffset.UTC).getYear() + "-" + String.format("%06d", n);
    }

    private Map<String, Object> snapshot(MajorIncident m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("number", m.getIncidentNumber());
        s.put("title", m.getTitle());
        s.put("status", m.getStatus().name());
        s.put("severity", m.getSeverity().name());
        s.put("hasReview", !ProblemService.blank(m.getPostIncidentReview()));
        return s;
    }

    private ItsmDto.Incident toDto(MajorIncident m, ErpUserPrincipal principal, User me) {
        List<Long> ids = jdbc.queryForList("SELECT ticket_id FROM major_incident_tickets WHERE incident_id = ?", Long.class, m.getId());
        List<ItsmDto.TicketRef> tickets = ticketRepository.findAllById(ids).stream()
                .filter(t -> accessService.canView(t, principal, me))
                .map(t -> new ItsmDto.TicketRef(t.getId(), t.getTicketNumber(), t.getTitle(), t.getStatus().name()))
                .toList();
        boolean overdue = m.getStatus() != MajorIncident.Status.CLOSED && m.getStatus() != MajorIncident.Status.RESOLVED
                && m.getNextUpdateDueAt() != null && m.getNextUpdateDueAt().isBefore(Instant.now());
        return new ItsmDto.Incident(m.getId(), m.getIncidentNumber(), m.getTitle(), m.getStatus().name(),
                m.getSeverity().name(), m.getReason(), m.getScope(), m.getAffectedServices(), m.getCustomerImpact(),
                m.getIncidentLead() != null ? m.getIncidentLead().getId() : null,
                m.getIncidentLead() != null ? m.getIncidentLead().getName() : null,
                m.getCommsLead() != null ? m.getCommsLead().getId() : null,
                m.getCommsLead() != null ? m.getCommsLead().getName() : null,
                m.getStartedAt(), m.getMitigatedAt(), m.getResolvedAt(), m.getClosedAt(), m.getUpdateCadenceMinutes(),
                m.getNextUpdateDueAt(), overdue, m.getPostIncidentReview(), m.getActionItems(), m.getVersion(),
                tickets, updates(m.getId(), false));
    }
}
