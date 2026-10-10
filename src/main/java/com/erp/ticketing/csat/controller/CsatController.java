package com.erp.ticketing.csat.controller;

import com.erp.ticketing.csat.dto.CsatResponse;
import com.erp.ticketing.csat.dto.CsatSubmitRequest;
import com.erp.ticketing.csat.service.CsatService;
import com.erp.ticketing.security.ErpUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Falls under the general /api/helpdesk/** SecurityConfig matcher - reachable by all four roles, same as /api/helpdesk/tickets/{id}/comments. */
@RestController
@RequestMapping("/api/helpdesk/tickets/{ticketId}/csat")
public class CsatController {

    private final CsatService csatService;

    public CsatController(CsatService csatService) {
        this.csatService = csatService;
    }

    @GetMapping
    public CsatResponse get(@PathVariable Long ticketId, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return csatService.get(ticketId, principal);
    }

    @PostMapping
    public CsatResponse submit(@PathVariable Long ticketId,
                                @Valid @RequestBody CsatSubmitRequest request,
                                @AuthenticationPrincipal ErpUserPrincipal principal) {
        return csatService.submit(ticketId, request, principal);
    }
}
