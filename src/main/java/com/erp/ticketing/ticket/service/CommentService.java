package com.erp.ticketing.ticket.service;

import com.erp.ticketing.automation.model.AutomationRule;
import com.erp.ticketing.automation.service.AutomationService;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.CommentCreateRequest;
import com.erp.ticketing.ticket.dto.CommentResponse;
import com.erp.ticketing.ticket.model.Comment;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.CommentRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final TicketRepository ticketRepository;
    private final UserService userService;
    private final ActivityLogService activityLogService;
    private final TicketAccessService accessService;
    private final AutomationService automationService;

    public CommentService(CommentRepository commentRepository, TicketRepository ticketRepository,
                           UserService userService, ActivityLogService activityLogService,
                           TicketAccessService accessService, AutomationService automationService) {
        this.accessService = accessService;
        this.automationService = automationService;
        this.commentRepository = commentRepository;
        this.ticketRepository = ticketRepository;
        this.userService = userService;
        this.activityLogService = activityLogService;
    }

    @Transactional
    public CommentResponse create(Long ticketId, CommentCreateRequest request, ErpUserPrincipal principal) {
        User author = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = requireVisibleTicket(ticketId, principal, author);

        Comment comment = new Comment();
        comment.setTicket(ticket);
        comment.setAuthor(author);
        comment.setBody(request.body());
        // A requester can never mark their own comment internal, regardless of what the request says.
        comment.setInternal(!isRequesterOnly(principal) && request.isInternal());

        comment = commentRepository.save(comment);

        // First response is defined as the first comment from an agent/manager/admin -
        // a requester replying to their own ticket does not count.
        if (ticket.getFirstResponseAt() == null && !isRequesterOnly(principal)) {
            Instant now = Instant.now();
            ticket.setFirstResponseAt(now);
            ticketRepository.save(ticket);
            activityLogService.logFieldChanged(ticket, author, "firstResponseAt", null, now.toString());
        }

        automationService.fire(AutomationRule.Trigger.COMMENT_ADDED, ticket,
                java.util.Map.of("byRequester", isRequesterOnly(principal)));

        return toResponse(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getForTicket(Long ticketId, ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        requireVisibleTicket(ticketId, principal, currentUser);

        boolean requesterOnly = isRequesterOnly(principal);
        return commentRepository.findByTicket_IdOrderByCreatedAtAsc(ticketId).stream()
                .filter(c -> !requesterOnly || !c.isInternal())
                .map(this::toResponse)
                .toList();
    }

    private Ticket requireVisibleTicket(Long ticketId, ErpUserPrincipal principal, User currentUser) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, currentUser);
        return ticket;
    }

    private boolean isRequesterOnly(ErpUserPrincipal principal) {
        return accessService.isRequesterOnly(principal);
    }

    private CommentResponse toResponse(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getTicket().getId(),
                comment.getAuthor().getId(),
                comment.getAuthor().getName(),
                comment.getBody(),
                comment.isInternal(),
                comment.getCreatedAt());
    }
}
