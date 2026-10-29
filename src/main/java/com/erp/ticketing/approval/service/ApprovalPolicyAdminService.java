package com.erp.ticketing.approval.service;

import com.erp.ticketing.approval.dto.ApprovalDto;
import com.erp.ticketing.approval.model.ApprovalPolicy;
import com.erp.ticketing.approval.model.ApprovalPolicyStep;
import com.erp.ticketing.approval.repository.ApprovalPolicyRepository;
import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin CRUD for approval policies; every change is audited and bumps the policy version. */
@Service
public class ApprovalPolicyAdminService {

    private final ApprovalPolicyRepository repository;
    private final TicketTypeRepository ticketTypeRepository;
    private final UserService userService;
    private final AuditService auditService;

    public ApprovalPolicyAdminService(ApprovalPolicyRepository repository, TicketTypeRepository ticketTypeRepository,
                                      UserService userService, AuditService auditService) {
        this.repository = repository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.userService = userService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ApprovalDto.Policy> list() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public ApprovalDto.Policy create(ApprovalDto.PolicySaveRequest request, ErpUserPrincipal principal) {
        User actor = userService.getOrCreateCurrentUser(principal);
        ApprovalPolicy policy = new ApprovalPolicy();
        apply(policy, request);
        policy = repository.save(policy);
        auditService.record(actor, "APPROVAL_POLICY_CREATED", "APPROVAL_POLICY", policy.getId(), null, snapshot(policy));
        return toDto(policy);
    }

    @Transactional
    public ApprovalDto.Policy update(Long id, ApprovalDto.PolicySaveRequest request, ErpUserPrincipal principal) {
        User actor = userService.getOrCreateCurrentUser(principal);
        ApprovalPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Approval policy not found: " + id));
        Map<String, Object> before = snapshot(policy);
        apply(policy, request);
        policy.setVersion(policy.getVersion() + 1);
        policy = repository.save(policy);
        auditService.record(actor, "APPROVAL_POLICY_UPDATED", "APPROVAL_POLICY", id, before, snapshot(policy));
        return toDto(policy);
    }

    /** Policies are deactivated rather than deleted so historical tasks keep their reference. */
    @Transactional
    public void deactivate(Long id, ErpUserPrincipal principal) {
        User actor = userService.getOrCreateCurrentUser(principal);
        ApprovalPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Approval policy not found: " + id));
        Map<String, Object> before = snapshot(policy);
        policy.setActive(false);
        repository.save(policy);
        auditService.record(actor, "APPROVAL_POLICY_DEACTIVATED", "APPROVAL_POLICY", id, before, snapshot(policy));
    }

    private void apply(ApprovalPolicy policy, ApprovalDto.PolicySaveRequest r) {
        if (r.name() == null || r.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (r.steps() == null || r.steps().isEmpty()) {
            throw new BadRequestException("At least one approval step is required");
        }
        if (r.ticketTypeId() != null) {
            ticketTypeRepository.findById(r.ticketTypeId())
                    .orElseThrow(() -> new NotFoundException("Ticket type not found: " + r.ticketTypeId()));
        }
        policy.setName(r.name().trim());
        policy.setTicketTypeId(r.ticketTypeId());
        try {
            policy.setMode(r.mode() == null ? ApprovalPolicy.Mode.SINGLE
                    : ApprovalPolicy.Mode.valueOf(r.mode().trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("mode must be SINGLE, SEQUENTIAL or PARALLEL");
        }
        if (r.timeoutHours() != null) {
            if (r.timeoutHours() < 1) {
                throw new BadRequestException("timeoutHours must be at least 1");
            }
            policy.setTimeoutHours(r.timeoutHours());
        }
        if (r.blockSelfApproval() != null) {
            policy.setBlockSelfApproval(r.blockSelfApproval());
        }
        if (r.active() != null) {
            policy.setActive(r.active());
        }
        policy.getSteps().clear();
        int order = 1;
        for (ApprovalDto.StepRequest s : r.steps()) {
            ApprovalPolicyStep step = new ApprovalPolicyStep();
            step.setPolicy(policy);
            step.setStepOrder(s.stepOrder() != null ? s.stepOrder() : order);
            order++;
            try {
                step.setApproverType(ApprovalPolicyStep.ApproverType.valueOf(s.approverType().trim().toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new BadRequestException("approverType must be USER or DEPARTMENT_MANAGER");
            }
            if (step.getApproverType() == ApprovalPolicyStep.ApproverType.USER) {
                if (s.approverId() == null) {
                    throw new BadRequestException("approverId is required for USER steps");
                }
                step.setApprover(userService.getRequiredUser(s.approverId()));
            }
            policy.getSteps().add(step);
        }
    }

    private Map<String, Object> snapshot(ApprovalPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("ticketTypeId", p.getTicketTypeId());
        m.put("mode", p.getMode().name());
        m.put("timeoutHours", p.getTimeoutHours());
        m.put("blockSelfApproval", p.isBlockSelfApproval());
        m.put("version", p.getVersion());
        m.put("active", p.isActive());
        m.put("steps", p.getSteps().stream().map(s -> s.getStepOrder() + ":" + s.getApproverType()
                + (s.getApprover() != null ? "#" + s.getApprover().getId() : "")).toList());
        return m;
    }

    private ApprovalDto.Policy toDto(ApprovalPolicy p) {
        return new ApprovalDto.Policy(p.getId(), p.getName(), p.getTicketTypeId(), p.getMode().name(),
                p.getTimeoutHours(), p.isBlockSelfApproval(), p.getVersion(), p.isActive(),
                p.getSteps().stream().map(s -> new ApprovalDto.Step(s.getStepOrder(), s.getApproverType().name(),
                        s.getApprover() != null ? s.getApprover().getId() : null,
                        s.getApprover() != null ? s.getApprover().getName() : null)).toList());
    }
}
