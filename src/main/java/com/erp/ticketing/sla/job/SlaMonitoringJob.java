package com.erp.ticketing.sla.job;

import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.model.DepartmentMember;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.service.ActivityLogService;
import com.erp.ticketing.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Periodically scans open tickets for SLA breaches/at-risk warnings and
 * notifies the assignee (or department leads/manager, if unassigned).
 */
@Component
public class SlaMonitoringJob {

    private static final Logger log = LoggerFactory.getLogger(SlaMonitoringJob.class);

    private static final Set<TicketStatus> TERMINAL_STATUSES =
            EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

    private static final double WARNING_THRESHOLD_RATIO = 0.8;

    private final TicketRepository ticketRepository;
    private final ActivityLogService activityLogService;
    private final NotificationService notificationService;
    private final DepartmentMemberRepository departmentMemberRepository;

    @Value("${app.sla.monitoring.enabled:true}")
    private boolean enabled;

    public SlaMonitoringJob(TicketRepository ticketRepository,
                             ActivityLogService activityLogService,
                             NotificationService notificationService,
                             DepartmentMemberRepository departmentMemberRepository) {
        this.ticketRepository = ticketRepository;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
        this.departmentMemberRepository = departmentMemberRepository;
    }

    @Scheduled(fixedDelayString = "${app.sla.monitoring.fixed-delay-ms:60000}")
    @Transactional
    public void checkSlaStatus() {
        if (!enabled) {
            return;
        }

        Instant now = Instant.now();
        List<Ticket> candidates = ticketRepository.findByStatusNotInAndResolutionDueIsNotNull(TERMINAL_STATUSES);

        for (Ticket ticket : candidates) {
            boolean changed = false;

            if (now.isAfter(ticket.getResolutionDue())) {
                if (!ticket.isSlaBreached()) {
                    ticket.setSlaBreached(true);
                    activityLogService.logSlaBreached(ticket);
                    User breachAssignee = ticket.getAssignee();
                    log.info("SLA breached", kv("ticketNumber", ticket.getTicketNumber()),
                            kv("assigneeId", breachAssignee != null ? breachAssignee.getId() : null),
                            kv("resolutionDue", ticket.getResolutionDue()));
                    changed = true;
                }
                if (!ticket.isSlaBreachNotified()) {
                    notifyAssigneeOrDepartmentLeads(ticket, "SLA_BREACHED");
                    ticket.setSlaBreachNotified(true);
                    changed = true;
                }
            } else if (!ticket.isSlaWarningSent() && isAtRisk(ticket, now)) {
                ticket.setSlaWarningSent(true);
                notifyAssigneeOrDepartmentLeads(ticket, "SLA_WARNING");
                changed = true;
            }

            if (changed) {
                ticketRepository.save(ticket);
            }
        }
    }

    private boolean isAtRisk(Ticket ticket, Instant now) {
        Instant createdAt = ticket.getCreatedAt();
        Instant resolutionDue = ticket.getResolutionDue();
        long totalMinutes = Duration.between(createdAt, resolutionDue).toMinutes();
        if (totalMinutes <= 0) {
            return true;
        }
        long elapsedMinutes = Duration.between(createdAt, now).toMinutes();
        double ratio = (double) elapsedMinutes / totalMinutes;
        return ratio >= WARNING_THRESHOLD_RATIO;
    }

    private void notifyAssigneeOrDepartmentLeads(Ticket ticket, String eventType) {
        Map<String, Object> placeholders = buildPlaceholders(ticket);
        User assignee = ticket.getAssignee();
        if (assignee != null) {
            notificationService.notify(assignee, ticket, eventType, placeholders);
            return;
        }

        Department department = ticket.getDepartment();
        if (department == null) {
            log.warn("Ticket {} has no assignee and no department - cannot deliver {} notification",
                    ticket.getTicketNumber(), eventType);
            return;
        }

        // Notify every designated department lead, and additionally the
        // department's manager (if set) when not already among the leads -
        // additive to the existing per-membership leads path, not a replacement.
        List<DepartmentMember> leads = departmentMemberRepository.findByDepartment_IdAndLeadTrue(department.getId());
        Set<Long> notified = new HashSet<>();
        for (DepartmentMember lead : leads) {
            notificationService.notify(lead.getUser(), ticket, eventType, placeholders);
            notified.add(lead.getUser().getId());
        }
        User manager = department.getManager();
        if (manager != null && !notified.contains(manager.getId())) {
            notificationService.notify(manager, ticket, eventType, placeholders);
        }
        if (leads.isEmpty() && manager == null) {
            log.warn("Ticket {} department '{}' has no designated lead or manager - cannot deliver {} notification",
                    ticket.getTicketNumber(), department.getDepartmentName(), eventType);
        }
    }

    private Map<String, Object> buildPlaceholders(Ticket ticket) {
        Map<String, Object> placeholders = new HashMap<>();
        placeholders.put("ticketNumber", ticket.getTicketNumber());
        placeholders.put("title", ticket.getTitle());
        if (ticket.getPriority() != null) {
            placeholders.put("priority", ticket.getPriority().name());
        }
        if (ticket.getResolutionDue() != null) {
            long minutesRemaining = Duration.between(Instant.now(), ticket.getResolutionDue()).toMinutes();
            placeholders.put("resolutionDueMinutesRemaining", minutesRemaining);
        }
        User assignee = ticket.getAssignee();
        if (assignee != null) {
            placeholders.put("assigneeName", assignee.getName());
        }
        Department department = ticket.getDepartment();
        if (department != null) {
            placeholders.put("departmentName", department.getDepartmentName());
        }
        return placeholders;
    }
}
