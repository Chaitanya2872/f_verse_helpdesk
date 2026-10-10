package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.ActivityLogResponse;
import com.erp.ticketing.ticket.dto.BulkActionRequest;
import com.erp.ticketing.ticket.dto.BulkActionResponse;
import com.erp.ticketing.ticket.dto.PageResponse;
import com.erp.ticketing.ticket.dto.TicketCreateRequest;
import com.erp.ticketing.ticket.dto.TicketResponse;
import com.erp.ticketing.ticket.dto.TicketSummaryResponse;
import com.erp.ticketing.ticket.dto.TicketUpdateRequest;
import com.erp.ticketing.ticket.service.ActivityLogService;
import com.erp.ticketing.ticket.service.BulkTicketActionService;
import com.erp.ticketing.ticket.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final ActivityLogService activityLogService;
    private final BulkTicketActionService bulkTicketActionService;

    public TicketController(TicketService ticketService, ActivityLogService activityLogService,
                             BulkTicketActionService bulkTicketActionService) {
        this.ticketService = ticketService;
        this.activityLogService = activityLogService;
        this.bulkTicketActionService = bulkTicketActionService;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody TicketCreateRequest request,
                                                  @AuthenticationPrincipal ErpUserPrincipal principal) {
        TicketResponse response = ticketService.create(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return ticketService.get(id, principal);
    }

    @PutMapping("/{id}")
    public TicketResponse update(@PathVariable Long id,
                                  @RequestBody TicketUpdateRequest request,
                                  @AuthenticationPrincipal ErpUserPrincipal principal) {
        return ticketService.update(id, request, principal);
    }

    @GetMapping
    public PageResponse<TicketResponse> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String priority,
                                              @RequestParam(required = false) Long typeId,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) Long assigneeId,
                                              @RequestParam(required = false) Long requesterId,
                                              @RequestParam(required = false) Long departmentId,
                                              @RequestParam(required = false) Boolean openOnly,
                                              @RequestParam(required = false) Boolean unassigned,
                                              @RequestParam(required = false) String slaStatus,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) String createdFrom,
                                              @RequestParam(required = false) String createdTo,
                                              @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
                                              @AuthenticationPrincipal ErpUserPrincipal principal) {
        return ticketService.list(status, priority, typeId, categoryId, assigneeId, requesterId, departmentId, openOnly,
                unassigned, slaStatus, search, createdFrom, createdTo, pageable, principal);
    }

    /**
     * Role-aware header counts for the Tickets list page. A literal segment
     * ("/summary") always wins over the "/{id}" pattern regardless of
     * declaration order (Spring MVC matches the more specific path first), so
     * this needs no special ordering relative to get(Long id, ...) below - but
     * it is placed first here for readability.
     */
    @GetMapping("/summary")
    public TicketSummaryResponse summary(@AuthenticationPrincipal ErpUserPrincipal principal) {
        return ticketService.summary(principal);
    }

    @GetMapping("/{id}/activity")
    public List<ActivityLogResponse> activity(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        // Enforce the same visibility rule as GET /api/helpdesk/tickets/{id} (404s if not visible) before exposing history.
        ticketService.get(id, principal);
        return activityLogService.getForTicket(id);
    }

    /**
     * AGENT/MANAGER/ADMIN only - see the dedicated SecurityConfig matcher for
     * POST /api/helpdesk/tickets/bulk-action (requesters must never reach this).
     */
    @PostMapping("/bulk-action")
    public BulkActionResponse bulkAction(@RequestBody BulkActionRequest request,
                                          @AuthenticationPrincipal ErpUserPrincipal principal) {
        return bulkTicketActionService.bulkAction(request, principal);
    }
}
