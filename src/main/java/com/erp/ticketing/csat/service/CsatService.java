package com.erp.ticketing.csat.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.csat.dto.CsatResponse;
import com.erp.ticketing.csat.dto.CsatSubmitRequest;
import com.erp.ticketing.csat.model.CsatStatus;
import com.erp.ticketing.csat.model.CsatSurvey;
import com.erp.ticketing.csat.repository.CsatSurveyRepository;
import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * No dependency back on TicketService - onTicketResolved() is called FROM
 * TicketService.update(), so a reverse dependency would be circular. Reads
 * TicketRepository/Ticket directly instead.
 */
@Service
public class CsatService {

    private final CsatSurveyRepository csatSurveyRepository;
    private final TicketRepository ticketRepository;
    private final UserService userService;
    private final NotificationService notificationService;
    private final com.erp.ticketing.ticket.service.TicketAccessService accessService;

    public CsatService(CsatSurveyRepository csatSurveyRepository, TicketRepository ticketRepository,
                        UserService userService, NotificationService notificationService,
                        com.erp.ticketing.ticket.service.TicketAccessService accessService) {
        this.accessService = accessService;
        this.csatSurveyRepository = csatSurveyRepository;
        this.ticketRepository = ticketRepository;
        this.userService = userService;
        this.notificationService = notificationService;
    }

    /**
     * Called from TicketService.update() at the exact moment a ticket
     * transitions into RESOLVED. First resolution creates a new PENDING
     * survey; a re-resolution (after being reopened) resets the existing row
     * back to PENDING and clears any prior rating/comment/submittedAt - this
     * row always reflects the most recent resolution rather than tracking
     * history. Either way, fires CSAT_REQUEST through the same
     * NotificationService pathway (persisted in-app always, emailed too when
     * app.notifications.email-enabled=true) as every other ticket event.
     */
    @Transactional
    public void onTicketResolved(Ticket ticket) {
        CsatSurvey survey = csatSurveyRepository.findByTicket_Id(ticket.getId()).orElseGet(CsatSurvey::new);
        survey.setTicket(ticket);
        survey.setRequester(ticket.getRequester());
        survey.setStatus(CsatStatus.PENDING);
        survey.setRating(null);
        survey.setComment(null);
        survey.setSubmittedAt(null);
        csatSurveyRepository.save(survey);

        Map<String, Object> placeholders = new HashMap<>();
        placeholders.put("ticketNumber", ticket.getTicketNumber());
        placeholders.put("title", ticket.getTitle());
        notificationService.notify(ticket.getRequester(), ticket, "CSAT_REQUEST", placeholders);
    }

    @Transactional
    public CsatResponse submit(Long ticketId, CsatSubmitRequest request, ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));

        accessService.assertVisible(ticket, principal, currentUser);
        if (!ticket.getRequester().getId().equals(currentUser.getId())) {
            // Reached only by an elevated role (agent/manager/admin) who CAN see the
            // ticket but must not submit a rating on the requester's behalf - a 400,
            // not a 404, since existence isn't in question here.
            throw new BadRequestException("Only the ticket's requester can submit a CSAT rating for ticket " + ticketId);
        }

        CsatSurvey survey = csatSurveyRepository.findByTicket_Id(ticketId)
                .orElseThrow(() -> new NotFoundException("CSAT survey not found for ticket: " + ticketId));
        if (survey.getStatus() == CsatStatus.SUBMITTED) {
            throw new BadRequestException("CSAT survey for ticket " + ticketId + " has already been submitted");
        }

        survey.setRating(request.rating());
        survey.setComment(request.comment());
        survey.setStatus(CsatStatus.SUBMITTED);
        survey.setSubmittedAt(Instant.now());
        survey = csatSurveyRepository.save(survey);
        return toResponse(survey);
    }

    @Transactional(readOnly = true)
    public CsatResponse get(Long ticketId, ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, currentUser);
        CsatSurvey survey = csatSurveyRepository.findByTicket_Id(ticketId)
                .orElseThrow(() -> new NotFoundException("CSAT survey not found for ticket: " + ticketId));
        return toResponse(survey);
    }

    private boolean isRequesterOnly(ErpUserPrincipal principal) {
        Set<String> roles = principal.roles();
        boolean hasElevatedRole = roles.stream().anyMatch(r ->
                "AGENT".equalsIgnoreCase(r) || "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
        return !hasElevatedRole;
    }

    private CsatResponse toResponse(CsatSurvey s) {
        Ticket ticket = s.getTicket();
        return new CsatResponse(
                s.getId(), ticket.getId(), ticket.getTicketNumber(),
                s.getRequester().getId(), s.getRequester().getName(),
                s.getRating(), s.getComment(), s.getStatus().name(),
                s.getCreatedAt(), s.getSubmittedAt());
    }
}
