package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.ticket.dto.TicketTypeCreateRequest;
import com.erp.ticketing.ticket.dto.TicketTypeResponse;
import com.erp.ticketing.ticket.dto.TicketTypeUpdateRequest;
import com.erp.ticketing.ticket.service.AdminReferenceDataService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Falls under /api/helpdesk/admin/** which SecurityConfig already restricts to
 * ROLE_ADMIN, so no additional per-endpoint role matchers are needed here.
 * GET /api/helpdesk/ticket-types stays on ReferenceDataController unchanged - this
 * controller only adds the admin mutation endpoints.
 */
@RestController
@RequestMapping("/api/helpdesk/admin/ticket-types")
public class AdminTicketTypeController {

    private final AdminReferenceDataService adminReferenceDataService;

    public AdminTicketTypeController(AdminReferenceDataService adminReferenceDataService) {
        this.adminReferenceDataService = adminReferenceDataService;
    }

    @PostMapping
    public ResponseEntity<TicketTypeResponse> create(@Valid @RequestBody TicketTypeCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminReferenceDataService.createTicketType(request));
    }

    @PutMapping("/{id}")
    public TicketTypeResponse update(@PathVariable Long id, @RequestBody TicketTypeUpdateRequest request) {
        return adminReferenceDataService.updateTicketType(id, request);
    }
}
