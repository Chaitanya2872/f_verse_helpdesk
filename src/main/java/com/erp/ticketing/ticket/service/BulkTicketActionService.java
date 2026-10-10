package com.erp.ticketing.ticket.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.BulkActionRequest;
import com.erp.ticketing.ticket.dto.BulkActionResponse;
import com.erp.ticketing.ticket.dto.BulkActionResult;
import com.erp.ticketing.ticket.dto.TicketUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Backs POST /api/helpdesk/tickets/bulk-action. Deliberately a separate bean from
 * TicketService (rather than a method on it) so that each per-ticket call
 * below goes through the real Spring proxy for {@link TicketService#update}:
 * calling it via {@code this.update(...)} from within TicketService itself
 * would be a same-class self-invocation that bypasses the proxy, silently
 * dropping update()'s @Transactional boundary. Going through a separate bean
 * keeps each ticket's update in its own transaction, which is also exactly
 * the isolation we want - one bad ticket must not roll back the others.
 *
 * No new validation/transition logic lives here: every action is translated
 * into the equivalent {@link TicketUpdateRequest} and handed to the existing,
 * fully-validated {@link TicketService#update} method.
 */
@Service
public class BulkTicketActionService {

    private static final Logger log = LoggerFactory.getLogger(BulkTicketActionService.class);

    private static final Set<String> VALID_ACTIONS =
            Set.of("ASSIGN_AGENT", "ASSIGN_DEPARTMENT", "CHANGE_STATUS", "CHANGE_PRIORITY", "CANCEL");

    private final TicketService ticketService;

    public BulkTicketActionService(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    public BulkActionResponse bulkAction(BulkActionRequest request, ErpUserPrincipal principal) {
        if (request.ticketIds() == null || request.ticketIds().isEmpty()) {
            throw new BadRequestException("ticketIds must not be empty");
        }
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        if (!VALID_ACTIONS.contains(action)) {
            throw new BadRequestException("Invalid action: " + request.action());
        }
        TicketUpdateRequest template = buildUpdateRequest(action, request.value());

        List<BulkActionResult> results = new ArrayList<>();
        for (Long ticketId : request.ticketIds()) {
            try {
                ticketService.update(ticketId, template, principal);
                results.add(new BulkActionResult(ticketId, true, null));
            } catch (Exception ex) {
                log.warn("Bulk action {} failed for ticket {}: {}", action, ticketId, ex.getMessage());
                results.add(new BulkActionResult(ticketId, false, ex.getMessage()));
            }
        }
        return new BulkActionResponse(results);
    }

    private TicketUpdateRequest buildUpdateRequest(String action, Object value) {
        return switch (action) {
            case "ASSIGN_AGENT" -> new TicketUpdateRequest(
                    null, null, null, null, null, null, toLong(value, action), null, null, null);
            case "ASSIGN_DEPARTMENT" -> new TicketUpdateRequest(
                    null, null, null, null, null, null, null, toLong(value, action), null, null);
            case "CHANGE_STATUS" -> new TicketUpdateRequest(
                    null, null, null, null, toRequiredString(value, action), null, null, null, null, null);
            case "CHANGE_PRIORITY" -> new TicketUpdateRequest(
                    null, null, null, toRequiredString(value, action), null, null, null, null, null, null);
            case "CANCEL" -> new TicketUpdateRequest(
                    null, null, null, null, "CANCELLED", null, null, null, null, null);
            default -> throw new BadRequestException("Invalid action: " + action);
        };
    }

    private Long toLong(Object value, String action) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ex) {
                throw new BadRequestException("value must be a numeric id for action " + action + ": " + value);
            }
        }
        throw new BadRequestException("value must be a numeric id for action " + action);
    }

    private String toRequiredString(Object value, String action) {
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        throw new BadRequestException("value must be a non-blank string for action " + action);
    }
}
