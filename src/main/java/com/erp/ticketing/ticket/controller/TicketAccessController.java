package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.AccessGrantDto;
import com.erp.ticketing.ticket.service.TicketAccessGrantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk/tickets/{ticketId}/access")
public class TicketAccessController {

    private final TicketAccessGrantService service;

    public TicketAccessController(TicketAccessGrantService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccessGrantDto.Grant> list(@PathVariable Long ticketId, @AuthenticationPrincipal ErpUserPrincipal p) {
        return service.list(ticketId, p);
    }

    @PostMapping
    public ResponseEntity<AccessGrantDto.Grant> grant(@PathVariable Long ticketId,
                                                      @RequestBody AccessGrantDto.GrantRequest request,
                                                      @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.grant(ticketId, request, p));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> revoke(@PathVariable Long ticketId, @PathVariable Long userId,
                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        service.revoke(ticketId, userId, p);
        return ResponseEntity.noContent().build();
    }
}
