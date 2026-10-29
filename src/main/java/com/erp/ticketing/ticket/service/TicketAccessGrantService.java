package com.erp.ticketing.ticket.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.AccessGrantDto;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketAccessGrant;
import com.erp.ticketing.ticket.repository.TicketAccessGrantRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Grants and revokes per-user access to restricted or confidential tickets; every change is audited (BRS 18.3). */
@Service
public class TicketAccessGrantService {

    private final TicketAccessGrantRepository grantRepository;
    private final TicketRepository ticketRepository;
    private final TicketAccessService accessService;
    private final UserService userService;
    private final AuditService auditService;
    private final ActivityLogService activityLogService;

    public TicketAccessGrantService(TicketAccessGrantRepository grantRepository, TicketRepository ticketRepository,
                                    TicketAccessService accessService, UserService userService,
                                    AuditService auditService, ActivityLogService activityLogService) {
        this.grantRepository = grantRepository;
        this.ticketRepository = ticketRepository;
        this.accessService = accessService;
        this.userService = userService;
        this.auditService = auditService;
        this.activityLogService = activityLogService;
    }

    @Transactional(readOnly = true)
    public List<AccessGrantDto.Grant> list(Long ticketId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = require(ticketId);
        if (!accessService.canGrant(ticket, principal, me)) {
            throw new NotFoundException("Ticket not found: " + ticketId);
        }
        return grantRepository.findByTicket_Id(ticketId).stream().map(this::toDto).toList();
    }

    @Transactional
    public AccessGrantDto.Grant grant(Long ticketId, AccessGrantDto.GrantRequest request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = require(ticketId);
        if (!accessService.canGrant(ticket, principal, me)) {
            throw new NotFoundException("Ticket not found: " + ticketId);
        }
        if (request.userId() == null || request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("userId and a reason are required");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new BadRequestException("expiresAt must be in the future");
        }
        User target = userService.getRequiredUser(request.userId());
        TicketAccessGrant grant = grantRepository.findByTicket_IdAndUser_Id(ticketId, target.getId())
                .orElseGet(TicketAccessGrant::new);
        grant.setTicket(ticket);
        grant.setUser(target);
        grant.setGrantedBy(me);
        grant.setReason(request.reason().trim());
        grant.setExpiresAt(request.expiresAt());
        grant = grantRepository.save(grant);
        auditService.record(me, "TICKET_ACCESS_GRANTED", "TICKET", ticketId, null,
                Map.of("userId", target.getId(), "reason", grant.getReason(),
                        "expiresAt", String.valueOf(grant.getExpiresAt())));
        activityLogService.logFieldChanged(ticket, me, "access", null, "Granted to " + target.getName());
        return toDto(grant);
    }

    @Transactional
    public void revoke(Long ticketId, Long userId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = require(ticketId);
        if (!accessService.canGrant(ticket, principal, me)) {
            throw new NotFoundException("Ticket not found: " + ticketId);
        }
        TicketAccessGrant grant = grantRepository.findByTicket_IdAndUser_Id(ticketId, userId)
                .orElseThrow(() -> new NotFoundException("No access grant for user " + userId));
        grantRepository.delete(grant);
        auditService.record(me, "TICKET_ACCESS_REVOKED", "TICKET", ticketId, Map.of("userId", userId), null);
        activityLogService.logFieldChanged(ticket, me, "access", "Granted to " + grant.getUser().getName(), null);
    }

    private Ticket require(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
    }

    private AccessGrantDto.Grant toDto(TicketAccessGrant g) {
        return new AccessGrantDto.Grant(g.getId(), g.getUser().getId(), g.getUser().getName(),
                g.getGrantedBy() != null ? g.getGrantedBy().getName() : null, g.getReason(), g.getExpiresAt(),
                g.getCreatedAt());
    }
}
