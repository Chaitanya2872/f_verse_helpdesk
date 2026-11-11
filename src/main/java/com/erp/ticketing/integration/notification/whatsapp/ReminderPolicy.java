package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.sla.job.SlaMonitoringJob;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.ticket.model.Ticket;

import java.time.Instant;
import java.util.Optional;

/**
 * Which WhatsApp reminder, if any, a ticket's assignee is due now. Pure: no I/O, so it is tested directly.
 *
 * <p>A ticket qualifies only while its SLA is running: it has an active assignee, a resolution due date, a
 * non-terminal status (not RESOLVED, CLOSED or CANCELLED), and its SLA clock is not paused. Then the highest
 * stage reached wins:
 * <ol>
 *   <li>{@link ReminderType#SLA_BREACHED}: past the resolution due date</li>
 *   <li>{@link ReminderType#SLA_DUE_SOON}: past the ticket's SLA policy warning threshold - the same rule, via
 *       {@link SlaMonitoringJob#isAtRisk}, that raises the in-app SLA warning</li>
 *   <li>{@link ReminderType#SECOND_REMINDER} / {@link ReminderType#FIRST_REMINDER}: past the optional configured
 *       share of the SLA window; not sent at all when unconfigured</li>
 * </ol>
 * A stage that was skipped (a ticket assigned when already at risk, say) is not sent late: once the
 * assignee hears "SLA Due Soon", an earlier "First Reminder" would be noise. Each stage goes out at most once
 * per assignee and resolution round; the outbox's idempotency key enforces that, not this class.
 */
public final class ReminderPolicy {

    private ReminderPolicy() {
    }

    public static Optional<ReminderType> due(Ticket ticket, SlaPolicy policy, Instant now,
                                             WhatsAppProperties.Reminders settings) {
        if (ticket.getStatus() == null || SlaMonitoringJob.TERMINAL_STATUSES.contains(ticket.getStatus())
                || ticket.getAssignee() == null || !ticket.getAssignee().isActive()
                || ticket.getResolutionDue() == null || ticket.getCreatedAt() == null
                || ticket.getSlaPausedAt() != null) {
            return Optional.empty();
        }
        if (now.isAfter(ticket.getResolutionDue())) {
            return Optional.of(ReminderType.SLA_BREACHED);
        }
        if (SlaMonitoringJob.isAtRisk(ticket, now, policy)) {
            return Optional.of(ReminderType.SLA_DUE_SOON);
        }
        double elapsed = SlaMonitoringJob.elapsedFraction(ticket, now);
        if (reached(elapsed, settings.getSecondReminderPercent())) {
            return Optional.of(ReminderType.SECOND_REMINDER);
        }
        if (reached(elapsed, settings.getFirstReminderPercent())) {
            return Optional.of(ReminderType.FIRST_REMINDER);
        }
        return Optional.empty();
    }

    private static boolean reached(double elapsed, Integer percent) {
        return percent != null && percent > 0 && percent < 100 && elapsed >= percent / 100.0;
    }
}
