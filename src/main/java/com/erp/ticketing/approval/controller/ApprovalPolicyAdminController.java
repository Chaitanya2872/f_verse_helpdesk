package com.erp.ticketing.approval.controller;

import com.erp.ticketing.approval.dto.ApprovalDto;
import com.erp.ticketing.approval.service.ApprovalPolicyAdminService;
import com.erp.ticketing.security.ErpUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only via the /admin/** matcher in SecurityConfig. */
@RestController
@RequestMapping("/api/helpdesk/admin/approval-policies")
public class ApprovalPolicyAdminController {

    private final ApprovalPolicyAdminService service;

    public ApprovalPolicyAdminController(ApprovalPolicyAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApprovalDto.Policy> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<ApprovalDto.Policy> create(@RequestBody ApprovalDto.PolicySaveRequest request,
                                                     @AuthenticationPrincipal ErpUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, principal));
    }

    @PutMapping("/{id}")
    public ApprovalDto.Policy update(@PathVariable Long id, @RequestBody ApprovalDto.PolicySaveRequest request,
                                     @AuthenticationPrincipal ErpUserPrincipal principal) {
        return service.update(id, request, principal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        service.deactivate(id, principal);
        return ResponseEntity.noContent().build();
    }
}
