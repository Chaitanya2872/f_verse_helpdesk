package com.erp.ticketing.reference;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * References from tickets, problems, changes and incidents to records that live in other systems
 * (assets, applications, customers, orders...). Only the source system, type, id and a short label are
 * stored, so the helpdesk never becomes a second authoritative inventory, and holding a reference
 * never grants access to the record itself (BRS 16.1, 16.4). Whether the record still exists can only
 * be checked where an integration is connected; none is configured here, so references are unverified.
 */
@Service
public class ReferenceService {

    public static final Set<String> ENTITY_TYPES = Set.of("CUSTOMER", "SALES_ORDER", "ASSET", "INVOICE",
            "PURCHASE_REQUISITION", "PURCHASE_ORDER", "PRODUCT", "EMPLOYEE", "APPLICATION", "EXTERNAL_ISSUE", "OTHER");

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:/#-]{1,100}$");
    private static final Pattern SOURCE = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
    private static final Set<String> OWNERS = Set.of("PROBLEM", "CHANGE", "MAJOR_INCIDENT");
    /** Snapshot keys worth keeping; everything else (addresses, amounts, contacts) is dropped on purpose. */
    private static final List<String> LABEL_KEYS = List.of("label", "name", "title");

    private final JdbcTemplate jdbc;
    private final TicketRepository ticketRepository;
    private final TicketAccessService accessService;
    private final UserService userService;

    public ReferenceService(JdbcTemplate jdbc, TicketRepository ticketRepository, TicketAccessService accessService,
                            UserService userService) {
        this.jdbc = jdbc;
        this.ticketRepository = ticketRepository;
        this.accessService = accessService;
        this.userService = userService;
    }

    public record Reference(Long id, String sourceSystem, String entityType, String entityId, String label,
                            Map<String, Object> entitySnapshot, Instant createdAt) {
    }

    public record Request(String sourceSystem, String entityType, String entityId, String label,
                          Map<String, Object> entitySnapshot) {
    }

    // ----------------------------------------------------------------- tickets

    @Transactional(readOnly = true)
    public List<Reference> forTicket(Long ticketId, ErpUserPrincipal principal) {
        visibleTicket(ticketId, principal);
        return jdbc.query("SELECT id, source_system, entity_type, entity_id, label, created_at FROM ticket_erp_links "
                        + "WHERE ticket_id = ? ORDER BY id", (rs, i) -> map(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), ticketId);
    }

    @Transactional
    public Reference addToTicket(Long ticketId, Request request, ErpUserPrincipal principal) {
        Ticket ticket = visibleTicket(ticketId, principal);
        User me = userService.getOrCreateCurrentUser(principal);
        Clean c = clean(request);
        Long id = jdbc.queryForObject("INSERT INTO ticket_erp_links (ticket_id, entity_type, entity_id, source_system, label, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (ticket_id, source_system, entity_type, entity_id) "
                        + "DO UPDATE SET label = EXCLUDED.label RETURNING id", Long.class,
                ticket.getId(), c.type, c.id, c.source, c.label, me.getId());
        return map(id, c.source, c.type, c.id, c.label, Instant.now());
    }

    @Transactional
    public void removeFromTicket(Long ticketId, Long linkId, ErpUserPrincipal principal) {
        if (accessService.isRequesterOnly(principal)) {
            throw new BadRequestException("Only staff can remove a reference");
        }
        visibleTicket(ticketId, principal);
        jdbc.update("DELETE FROM ticket_erp_links WHERE id = ? AND ticket_id = ?", linkId, ticketId);
    }

    // ------------------------------------------------------- problems/changes

    @Transactional(readOnly = true)
    public List<Reference> forOwner(String ownerType, Long ownerId) {
        owner(ownerType);
        return jdbc.query("SELECT id, source_system, entity_type, entity_id, label, created_at FROM entity_links "
                        + "WHERE owner_type = ? AND owner_id = ? ORDER BY id", (rs, i) -> map(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()),
                ownerType, ownerId);
    }

    @Transactional
    public Reference addToOwner(String ownerType, Long ownerId, Request request, ErpUserPrincipal principal) {
        owner(ownerType);
        requireOwnerExists(ownerType, ownerId);
        User me = userService.getOrCreateCurrentUser(principal);
        Clean c = clean(request);
        Long id = jdbc.queryForObject("INSERT INTO entity_links (owner_type, owner_id, source_system, entity_type, entity_id, label, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (owner_type, owner_id, source_system, entity_type, entity_id) "
                        + "DO UPDATE SET label = EXCLUDED.label RETURNING id", Long.class,
                ownerType, ownerId, c.source, c.type, c.id, c.label, me.getId());
        return map(id, c.source, c.type, c.id, c.label, Instant.now());
    }

    @Transactional
    public void removeFromOwner(String ownerType, Long ownerId, Long linkId) {
        owner(ownerType);
        jdbc.update("DELETE FROM entity_links WHERE id = ? AND owner_type = ? AND owner_id = ?", linkId, ownerType, ownerId);
    }

    // ----------------------------------------------------------------- helpers

    private record Clean(String source, String type, String id, String label) {
    }

    private Clean clean(Request r) {
        String type = r.entityType() == null ? "" : r.entityType().trim().toUpperCase(Locale.ROOT);
        if (!ENTITY_TYPES.contains(type)) {
            throw new BadRequestException("entityType must be one of " + ENTITY_TYPES.stream().sorted().toList());
        }
        String id = r.entityId() == null ? "" : r.entityId().trim();
        if (!ID.matcher(id).matches()) {
            throw new BadRequestException("entityId must be 1-100 characters: letters, digits and . _ : / # -");
        }
        String source = r.sourceSystem() == null || r.sourceSystem().isBlank() ? "ERP" : r.sourceSystem().trim();
        if (!SOURCE.matcher(source).matches()) {
            throw new BadRequestException("sourceSystem may only contain letters, digits, _ and -");
        }
        String label = r.label();
        if ((label == null || label.isBlank()) && r.entitySnapshot() != null) {
            for (String key : LABEL_KEYS) {
                Object v = r.entitySnapshot().get(key);
                if (v instanceof String s && !s.isBlank()) {
                    label = s;
                    break;
                }
            }
        }
        if (label != null) {
            label = label.trim();
            label = label.substring(0, Math.min(255, label.length()));
        }
        return new Clean(source, type, id, label);
    }

    private Reference map(Long id, String source, String type, String entityId, String label, Instant createdAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (label != null) {
            snapshot.put("label", label);
        }
        return new Reference(id, source, type, entityId, label, snapshot, createdAt);
    }

    private Ticket visibleTicket(Long ticketId, ErpUserPrincipal principal) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, userService.getOrCreateCurrentUser(principal));
        return ticket;
    }

    private void owner(String ownerType) {
        if (!OWNERS.contains(ownerType)) {
            throw new BadRequestException("Unknown owner type: " + ownerType);
        }
    }

    private void requireOwnerExists(String ownerType, Long ownerId) {
        String table = switch (ownerType) {
            case "PROBLEM" -> "problems";
            case "CHANGE" -> "change_requests";
            default -> "major_incidents";
        };
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, ownerId);
        if (n == null || n == 0) {
            throw new NotFoundException(ownerType.toLowerCase(Locale.ROOT).replace('_', ' ') + " not found: " + ownerId);
        }
    }
}
