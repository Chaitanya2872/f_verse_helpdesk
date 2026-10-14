package com.erp.ticketing.approval.dto;

import java.time.Instant;
import java.util.List;

public final class ApprovalDto {

    private ApprovalDto() {
    }

    /** Only decision-relevant ticket data is exposed, so an approver needs no general ticket access. */
    public record Task(
            Long id,
            Long ticketId,
            String ticketNumber,
            String ticketTitle,
            String ticketDescription,
            String ticketPriority,
            String requesterName,
            Long approverId,
            String approverName,
            Long actedById,
            String actedByName,
            String status,
            int stepOrder,
            String policyName,
            Integer policyVersion,
            String decisionComment,
            Instant dueAt,
            Instant decidedAt,
            Instant createdAt
    ) {
    }

    public record DecisionRequest(String decision, String comment) {
    }

    public record Step(int stepOrder, String approverType, Long approverId, String approverName) {
    }

    public record Policy(Long id, String name, Long ticketTypeId, String mode, int timeoutHours,
                         boolean blockSelfApproval, int version, boolean active, List<Step> steps) {
    }

    public record PolicySaveRequest(String name, Long ticketTypeId, String mode, Integer timeoutHours,
                                    Boolean blockSelfApproval, Boolean active, List<StepRequest> steps) {
    }

    public record StepRequest(Integer stepOrder, String approverType, Long approverId) {
    }

}
