package com.erp.ticketing.sla.service;

import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.service.ActivityLogService;
import com.erp.ticketing.user.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pauses and resumes a ticket's SLA clock as it moves in and out of the statuses
 * its policy lists (BRS 9.3). While paused the remaining working time is frozen;
 * on resume the due dates are rebuilt from that remainder, so waiting on the
 * requester never counts against the agent. Every pause is recorded in
 * {@code sla_pauses} and the activity log - nothing is silently dropped.
 */
@Service
public class SlaPauseService {

    private final SlaPolicyRepository policyRepository;
    private final SlaCalculationService calculationService;
    private final ActivityLogService activityLogService;
    private final JdbcTemplate jdbc;

    public SlaPauseService(SlaPolicyRepository policyRepository, SlaCalculationService calculationService,
                           ActivityLogService activityLogService, JdbcTemplate jdbc) {
        this.policyRepository = policyRepository;
        this.calculationService = calculationService;
        this.activityLogService = activityLogService;
        this.jdbc = jdbc;
    }

    /** Call after a ticket status changed from {@code from} to {@code to}, inside the same transaction. */
    public void onStatusChange(Ticket ticket, TicketStatus from, TicketStatus to, User actor) {
        if (ticket.getSlaPolicyId() == null || ticket.getResolutionDue() == null) {
            return;
        }
        Optional<SlaPolicy> found = policyRepository.findById(ticket.getSlaPolicyId());
        if (found.isEmpty()) {
            return;
        }
        SlaPolicy policy = found.get();
        Set<TicketStatus> pausing = pausingStatuses(policy);
        boolean paused = ticket.getSlaPausedAt() != null;

        if (paused && !pausing.contains(to)) {
            resume(ticket, policy, actor);
        } else if (!paused && pausing.contains(to)) {
            pause(ticket, policy, to, actor);
        }
        if (to == TicketStatus.REOPENED) {
            restart(ticket, policy, actor);
        }
    }

    private void pause(Ticket ticket, SlaPolicy policy, TicketStatus status, User actor) {
        Instant now = Instant.now();
        ticket.setSlaPausedAt(now);
        ticket.setSlaResolutionRemainingMinutes(
                calculationService.remainingMinutes(now, ticket.getResolutionDue(), policy));
        if (ticket.getFirstResponseAt() == null && ticket.getFirstResponseDue() != null) {
            ticket.setSlaFirstResponseRemainingMinutes(
                    calculationService.remainingMinutes(now, ticket.getFirstResponseDue(), policy));
        }
        jdbc.update("INSERT INTO sla_pauses (ticket_id, status, started_at) VALUES (?, ?, ?)",
                ticket.getId(), status.name(), java.sql.Timestamp.from(now));
        activityLogService.logFieldChanged(ticket, actor, "slaClock", "Running", "Paused (" + status + ")");
    }

    private void resume(Ticket ticket, SlaPolicy policy, User actor) {
        Instant now = Instant.now();
        if (ticket.getSlaResolutionRemainingMinutes() != null) {
            ticket.setResolutionDue(
                    calculationService.addMinutes(now, ticket.getSlaResolutionRemainingMinutes(), policy));
        }
        if (ticket.getSlaFirstResponseRemainingMinutes() != null && ticket.getFirstResponseAt() == null) {
            ticket.setFirstResponseDue(
                    calculationService.addMinutes(now, ticket.getSlaFirstResponseRemainingMinutes(), policy));
        }
        ticket.setSlaPausedAt(null);
        ticket.setSlaResolutionRemainingMinutes(null);
        ticket.setSlaFirstResponseRemainingMinutes(null);
        // Warnings are relative to the new deadline, so they may fire again; breaches stay on record.
        ticket.setSlaWarningSent(false);
        jdbc.update("UPDATE sla_pauses SET ended_at = ? WHERE ticket_id = ? AND ended_at IS NULL",
                java.sql.Timestamp.from(now), ticket.getId());
        activityLogService.logFieldChanged(ticket, actor, "slaClock", "Paused", "Running");
    }

    /** Reopen policy: the resolution clock restarts from now; an earlier breach stays recorded. */
    private void restart(Ticket ticket, SlaPolicy policy, User actor) {
        var due = calculationService.computeDueDates(Instant.now(), policy);
        ticket.setResolutionDue(due.resolutionDue());
        ticket.setSlaWarningSent(false);
        ticket.setSlaBreachNotified(false);
        ticket.setSlaEscalated(false);
        activityLogService.logFieldChanged(ticket, actor, "slaClock", "Stopped", "Restarted on reopen");
    }

    private Set<TicketStatus> pausingStatuses(SlaPolicy policy) {
        Set<TicketStatus> set = EnumSet.noneOf(TicketStatus.class);
        Arrays.stream(policy.getPauseStatuses().split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .forEach(s -> set.add(TicketStatus.valueOf(s)));
        return set;
    }

    public static String describe(Set<TicketStatus> statuses) {
        return statuses.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
