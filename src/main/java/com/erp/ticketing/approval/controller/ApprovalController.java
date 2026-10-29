package com.erp.ticketing.approval.controller;

import com.erp.ticketing.approval.dto.ApprovalDto;
import com.erp.ticketing.approval.service.ApprovalService;
import com.erp.ticketing.security.ErpUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** Pending approvals for me. */
    @GetMapping("/approvals/mine")
    public List<ApprovalDto.Task> mine(@AuthenticationPrincipal ErpUserPrincipal principal) {
        return approvalService.mine(principal);
    }

    @PostMapping("/approvals/{id}/decision")
    public ApprovalDto.Task decide(@PathVariable Long id, @RequestBody ApprovalDto.DecisionRequest request,
                                   @AuthenticationPrincipal ErpUserPrincipal principal) {
        return approvalService.decide(id, request, principal);
    }

    @GetMapping("/tickets/{ticketId}/approvals")
    public List<ApprovalDto.Task> forTicket(@PathVariable Long ticketId,
                                            @AuthenticationPrincipal ErpUserPrincipal principal) {
        return approvalService.forTicket(ticketId, principal);
    }

    @PostMapping("/tickets/{ticketId}/approvals/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable Long ticketId,
                                         @AuthenticationPrincipal ErpUserPrincipal principal) {
        approvalService.withdraw(ticketId, principal);
        return ResponseEntity.noContent().build();
    }
}
