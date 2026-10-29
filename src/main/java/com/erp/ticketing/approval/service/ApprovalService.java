package com.erp.ticketing.approval.service;

import com.erp.ticketing.approval.dto.ApprovalDto;
import com.erp.ticketing.approval.model.ApprovalPolicy;
import com.erp.ticketing.approval.model.ApprovalPolicyStep;
import com.erp.ticketing.approval.model.ApprovalTask;
import com.erp.ticketing.approval.repository.ApprovalPolicyRepository;
import com.erp.ticketing.approval.repository.ApprovalTaskRepository;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.service.ActivityLogService;
import com.erp.ticketing.ticket.service.TicketAccessService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Approval gate for tickets (BRS 10.2, 10.3). A ticket entering WAITING_APPROVAL gets
 * tasks from the request type's policy; the ticket leaves that state only through
 * a decision, a withdrawal or cancellation - never through a timeout, which only
 * flags the task and notifies, because silence is not approval.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalPolicyRepository policyRepository;
    private final ApprovalTaskRepository taskRepository;
    private final TicketRepository ticketRepository;
    private final UserService userService;
    private final ActivityLogService activityLogService;
    private final NotificationService notificationService;
    private final TicketAccessService accessService;

    public ApprovalService(ApprovalPolicyRepository policyRepository,
                           ApprovalTaskRepository taskRepository,
                           TicketRepository ticketRepository,
                           UserService userService,
                           ActivityLogService activityLogService,
                           NotificationService notificationService,
                           TicketAccessService accessService) {
        this.policyRepository = policyRepository;
        this.taskRepository = taskRepository;
        this.ticketRepository = ticketRepository;
        this.userService = userService;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
        this.accessService = accessService;
    }

    // ------------------------------------------------------------------ gate

    @Transactional(readOnly = true)
    public boolean hasOpenTasks(Ticket ticket) {
        return !taskRepository.findByTicket_IdAndStatusIn(ticket.getId(),
                EnumSet.of(ApprovalTask.Status.PENDING, ApprovalTask.Status.QUEUED)).isEmpty();
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalPolicy> resolvePolicy(Long ticketTypeId) {
        List<ApprovalPolicy> own = ticketTypeId == null ? List.of()
                : policyRepository.findByTicketTypeIdAndActiveTrueOrderByIdDesc(ticketTypeId);
        if (!own.isEmpty()) {
            return Optional.of(own.get(0));
        }
        List<ApprovalPolicy> fallback = policyRepository.findByTicketTypeIdIsNullAndActiveTrueOrderByIdDesc();
        return fallback.isEmpty() ? Optional.empty() : Optional.of(fallback.get(0));
    }

    /** Called by TicketService when a ticket moves to WAITING_APPROVAL. Fails if no policy applies. */
    @Transactional
    public void start(Ticket ticket, User actor) {
        ApprovalPolicy policy = resolvePolicy(ticket.getType().getId())
                .orElseThrow(() -> new BadRequestException(
                        "No approval policy is configured for ticket type '" + ticket.getType().getName() + "'"));
        if (policy.getSteps().isEmpty()) {
            throw new BadRequestException("Approval policy '" + policy.getName() + "' has no approvers");
        }
        withdrawOpen(ticket, "Superseded by a new approval request");

        Instant due = Instant.now().plus(Duration.ofHours(policy.getTimeoutHours()));
        List<ApprovalPolicyStep> steps = policy.getSteps();
        int firstOrder = steps.get(0).getStepOrder();
        List<ApprovalPolicyStep> used = switch (policy.getMode()) {
            case SINGLE -> List.of(steps.get(0));
            default -> steps;
        };
        List<ApprovalTask> created = new ArrayList<>();
        for (ApprovalPolicyStep step : used) {
            User approver = resolveApprover(step, ticket);
            ApprovalTask task = new ApprovalTask();
            task.setTicket(ticket);
            task.setPolicy(policy);
            task.setPolicyVersion(policy.getVersion());
            task.setApprover(approver);
            task.setStepOrder(policy.getMode() == ApprovalPolicy.Mode.SEQUENTIAL ? step.getStepOrder() : 1);
            boolean active = policy.getMode() != ApprovalPolicy.Mode.SEQUENTIAL || step.getStepOrder() == firstOrder;
            task.setStatus(active ? ApprovalTask.Status.PENDING : ApprovalTask.Status.QUEUED);
            task.setDueAt(active ? due : null);
            created.add(taskRepository.save(task));
        }
        activityLogService.logFieldChanged(ticket, actor, "approval", null,
                "Requested (" + policy.getName() + " v" + policy.getVersion() + ")");
        created.stream().filter(t -> t.getStatus() == ApprovalTask.Status.PENDING)
                .forEach(t -> notifyApprover(t, "Approval needed: " + ticket.getTicketNumber()));
    }

    /** Cancels any still-open tasks, used when a ticket is cancelled or an approval is withdrawn. */
    @Transactional
    public void withdrawOpen(Ticket ticket, String reason) {
        for (ApprovalTask task : taskRepository.findByTicket_IdAndStatusIn(ticket.getId(),
                EnumSet.of(ApprovalTask.Status.PENDING, ApprovalTask.Status.QUEUED))) {
            task.setStatus(ApprovalTask.Status.WITHDRAWN);
            task.setDecisionComment(reason);
            task.setDecidedAt(Instant.now());
            taskRepository.save(task);
        }
    }

    private User resolveApprover(ApprovalPolicyStep step, Ticket ticket) {
        if (step.getApproverType() == ApprovalPolicyStep.ApproverType.USER) {
            return step.getApprover();
        }
        User manager = ticket.getDepartment().getManager();
        if (manager == null) {
            throw new BadRequestException("Department '" + ticket.getDepartment().getDepartmentName()
                    + "' has no manager to approve this request");
        }
        return manager;
    }

    // -------------------------------------------------------------- decisions

    @Transactional
    public ApprovalDto.Task decide(Long taskId, ApprovalDto.DecisionRequest request, ErpUserPrincipal principal) {
        User actor = userService.getOrCreateCurrentUser(principal);
        ApprovalTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Approval task not found: " + taskId));
        if (!canAct(task, actor)) {
            // Same answer as for a missing task so task IDs cannot be probed.
            throw new NotFoundException("Approval task not found: " + taskId);
        }
        if (task.getStatus() != ApprovalTask.Status.PENDING) {
            throw new BadRequestException("This approval is already " + task.getStatus());
        }
        Ticket ticket = task.getTicket();
        boolean blockSelf = task.getPolicy() == null || task.getPolicy().isBlockSelfApproval();
        if (blockSelf && (isSame(actor, ticket.getRequester()) || isSame(task.getApprover(), ticket.getRequester()))) {
            throw new BadRequestException("Requesters cannot approve their own request");
        }
        String decision = request.decision() == null ? "" : request.decision().trim().toUpperCase();
        boolean needsComment = !decision.equals("APPROVE");
        if (needsComment && (request.comment() == null || request.comment().isBlank())) {
            throw new BadRequestException("A comment is required to reject or request changes");
        }
        task.setActedBy(actor);
        task.setDecidedAt(Instant.now());
        task.setDecisionComment(request.comment());

        switch (decision) {
            case "APPROVE" -> {
                task.setStatus(ApprovalTask.Status.APPROVED);
                taskRepository.save(task);
                logDecision(ticket, actor, task, "Approved");
                advance(ticket, actor);
            }
            case "REJECT" -> {
                task.setStatus(ApprovalTask.Status.REJECTED);
                taskRepository.save(task);
                logDecision(ticket, actor, task, "Rejected: " + request.comment());
                withdrawOpen(ticket, "Another approver rejected the request");
                moveTicket(ticket, actor, TicketStatus.CANCELLED);
                notifyRequester(ticket, "Request rejected: " + ticket.getTicketNumber(), request.comment());
            }
            case "REQUEST_CHANGES" -> {
                task.setStatus(ApprovalTask.Status.CHANGES_REQUESTED);
                taskRepository.save(task);
                logDecision(ticket, actor, task, "Changes requested: " + request.comment());
                withdrawOpen(ticket, "Changes were requested");
                moveTicket(ticket, actor, TicketStatus.WAITING_CUSTOMER);
                notifyRequester(ticket, "Changes requested: " + ticket.getTicketNumber(), request.comment());
            }
            default -> throw new BadRequestException("decision must be APPROVE, REJECT or REQUEST_CHANGES");
        }
        return toDto(task);
    }

    /** After an approval: release the next sequential step, or finish the gate and resume work. */
    private void advance(Ticket ticket, User actor) {
        List<ApprovalTask> all = taskRepository.findByTicket_IdOrderByStepOrderAscIdAsc(ticket.getId());
        List<ApprovalTask> pending = all.stream().filter(t -> t.getStatus() == ApprovalTask.Status.PENDING).toList();
        if (!pending.isEmpty()) {
            return;
        }
        List<ApprovalTask> queued = all.stream().filter(t -> t.getStatus() == ApprovalTask.Status.QUEUED).toList();
        if (!queued.isEmpty()) {
            int nextStep = queued.stream().mapToInt(ApprovalTask::getStepOrder).min().orElseThrow();
            for (ApprovalTask t : queued) {
                if (t.getStepOrder() == nextStep) {
                    t.setStatus(ApprovalTask.Status.PENDING);
                    int hours = t.getPolicy() != null ? t.getPolicy().getTimeoutHours() : 72;
                    t.setDueAt(Instant.now().plus(Duration.ofHours(hours)));
                    taskRepository.save(t);
                    notifyApprover(t, "Approval needed: " + ticket.getTicketNumber());
                }
            }
            return;
        }
        moveTicket(ticket, actor, TicketStatus.IN_PROGRESS);
        notifyRequester(ticket, "Request approved: " + ticket.getTicketNumber(), "All required approvals were given.");
    }

    @Transactional
    public void withdraw(Long ticketId, ErpUserPrincipal principal) {
        User actor = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, actor);
        if (ticket.getStatus() != TicketStatus.WAITING_APPROVAL || !hasOpenTasks(ticket)) {
            throw new BadRequestException("This ticket has no approval to withdraw");
        }
        withdrawOpen(ticket, "Withdrawn by " + actor.getName());
        activityLogService.logFieldChanged(ticket, actor, "approval", "Requested", "Withdrawn");
        moveTicket(ticket, actor, TicketStatus.IN_PROGRESS);
    }

    private void moveTicket(Ticket ticket, User actor, TicketStatus target) {
        if (ticket.getStatus() == target) {
            return;
        }
        activityLogService.logFieldChanged(ticket, actor, "status", ticket.getStatus().name(), target.name());
        ticket.setStatus(target);
        ticketRepository.save(ticket);
    }

    private void logDecision(Ticket ticket, User actor, ApprovalTask task, String text) {
        activityLogService.logFieldChanged(ticket, actor, "approval", null, text + " by " + actor.getName());
    }

    // --------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<ApprovalDto.Task> mine(ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Set<Long> approverIds = Set.of(me.getId());
        return taskRepository.findByApprover_IdInAndStatusOrderByCreatedAtAsc(approverIds, ApprovalTask.Status.PENDING)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ApprovalDto.Task> forTicket(Long ticketId, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, me);
        return taskRepository.findByTicket_IdOrderByStepOrderAscIdAsc(ticketId).stream().map(this::toDto).toList();
    }

    private boolean canAct(ApprovalTask task, User actor) {
        return isSame(task.getApprover(), actor);
    }

    private static boolean isSame(User a, User b) {
        return a != null && b != null && a.getId().equals(b.getId());
    }

    // --------------------------------------------------------------- timeout

    /** Flags overdue tasks and tells the requester; never approves (BRS 10.2). */
    @Transactional
    public int expireOverdue() {
        List<ApprovalTask> overdue = taskRepository.findByStatusAndDueAtBefore(ApprovalTask.Status.PENDING, Instant.now());
        for (ApprovalTask task : overdue) {
            task.setStatus(ApprovalTask.Status.EXPIRED);
            task.setDecidedAt(Instant.now());
            task.setDecisionComment("No decision before the deadline");
            taskRepository.save(task);
            Ticket ticket = task.getTicket();
            activityLogService.logFieldChanged(ticket, null, "approval", "Requested",
                    "Expired - no decision from " + task.getApprover().getName());
            notifyRequester(ticket, "Approval overdue: " + ticket.getTicketNumber(),
                    "The approval from " + task.getApprover().getName() + " expired without a decision. "
                            + "An agent needs to re-request it or reassign the approver.");
            log.info("Approval task expired", kv("taskId", task.getId()), kv("ticketNumber", ticket.getTicketNumber()));
        }
        return overdue.size();
    }

    // ------------------------------------------------------------- notifying

    private void notifyApprover(ApprovalTask task, String subject) {
        Ticket t = task.getTicket();
        notificationService.notify(task.getApprover(), t, "APPROVAL_REQUESTED", subject,
                t.getTicketNumber() + ": " + t.getTitle() + " needs your decision.");
    }

    private void notifyRequester(Ticket ticket, String subject, String body) {
        notificationService.notify(ticket.getRequester(), ticket, "APPROVAL_UPDATE", subject, body);
    }

    // --------------------------------------------------------------- mapping

    private ApprovalDto.Task toDto(ApprovalTask t) {
        Ticket ticket = t.getTicket();
        User acted = t.getActedBy();
        return new ApprovalDto.Task(
                t.getId(), ticket.getId(), ticket.getTicketNumber(), ticket.getTitle(), ticket.getDescription(),
                ticket.getPriority().name(), ticket.getRequester().getName(),
                t.getApprover().getId(), t.getApprover().getName(),
                acted != null ? acted.getId() : null, acted != null ? acted.getName() : null,
                t.getStatus().name(), t.getStepOrder(),
                t.getPolicy() != null ? t.getPolicy().getName() : null, t.getPolicyVersion(),
                t.getDecisionComment(), t.getDueAt(), t.getDecidedAt(), t.getCreatedAt());
    }
}
