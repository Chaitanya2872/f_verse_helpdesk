package com.erp.ticketing.workflow.controller;

import com.erp.ticketing.approval.repository.ApprovalPolicyRepository;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.service.UserService;
import com.erp.ticketing.workflow.dto.WorkflowDto;
import com.erp.ticketing.workflow.service.WorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only via the /admin/** matcher. Omit {@code ticketTypeId} to address the default workflow. */
@RestController
@RequestMapping("/api/helpdesk/admin/workflow")
public class WorkflowAdminController {

    private final WorkflowService workflowService;
    private final ApprovalPolicyRepository approvalPolicyRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final UserService userService;

    public WorkflowAdminController(WorkflowService workflowService, ApprovalPolicyRepository approvalPolicyRepository,
                                   TicketTypeRepository ticketTypeRepository, UserService userService) {
        this.workflowService = workflowService;
        this.approvalPolicyRepository = approvalPolicyRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.userService = userService;
    }

    @GetMapping
    public WorkflowDto.Workflow get(@RequestParam(required = false) Long ticketTypeId) {
        requireType(ticketTypeId);
        return workflowService.get(ticketTypeId);
    }

    @PutMapping
    public WorkflowDto.Workflow save(@RequestParam(required = false) Long ticketTypeId,
                                     @RequestBody WorkflowDto.SaveRequest request,
                                     @AuthenticationPrincipal ErpUserPrincipal principal) {
        requireType(ticketTypeId);
        boolean hasPolicy = approvalPolicyRepository.existsByTicketTypeIdIsNullAndActiveTrue()
                || (ticketTypeId != null && approvalPolicyRepository.existsByTicketTypeIdAndActiveTrue(ticketTypeId));
        return workflowService.save(ticketTypeId, request.transitions(), hasPolicy,
                userService.getOrCreateCurrentUser(principal));
    }

    @DeleteMapping
    public ResponseEntity<Void> revert(@RequestParam Long ticketTypeId,
                                       @AuthenticationPrincipal ErpUserPrincipal principal) {
        requireType(ticketTypeId);
        workflowService.revertToDefault(ticketTypeId, userService.getOrCreateCurrentUser(principal));
        return ResponseEntity.noContent().build();
    }

    private void requireType(Long ticketTypeId) {
        if (ticketTypeId != null && !ticketTypeRepository.existsById(ticketTypeId)) {
            throw new NotFoundException("Ticket type not found: " + ticketTypeId);
        }
    }
}
