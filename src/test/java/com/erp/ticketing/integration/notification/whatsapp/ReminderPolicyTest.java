package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Which reminder a ticket is due, from its status, assignee and SLA policy - no database. */
class ReminderPolicyTest {

    private static final Instant CREATED = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant DUE = CREATED.plus(Duration.ofHours(10));

    private Ticket ticket;
    private SlaPolicy policy;
    private WhatsAppProperties.Reminders settings;

    @BeforeEach
    void setUp() {
        User assignee = new User();
        assignee.setId(7L);
        assignee.setActive(true);
        ticket = new Ticket();
        ticket.setId(1L);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setAssignee(assignee);
        ticket.setCreatedAt(CREATED);
        ticket.setResolutionDue(DUE);
        policy = new SlaPolicy();
        policy.setWarningPercent(80);
        settings = new WhatsAppProperties.Reminders();
    }

    private Instant at(int percentOfWindow) {
        return CREATED.plus(Duration.ofMinutes(600L * percentOfWindow / 100));
    }

    @Test
    void nothingIsDueEarlyInTheWindowWithoutConfiguredEarlyReminders() {
        assertThat(ReminderPolicy.due(ticket, policy, at(10), settings)).isEmpty();
        assertThat(ReminderPolicy.due(ticket, policy, at(79), settings)).isEmpty();
    }

    @Test
    void slaDueSoonFollowsThePolicysWarningThreshold() {
        assertThat(ReminderPolicy.due(ticket, policy, at(80), settings)).contains(ReminderType.SLA_DUE_SOON);

        policy.setWarningPercent(50);
        assertThat(ReminderPolicy.due(ticket, policy, at(55), settings)).contains(ReminderType.SLA_DUE_SOON);
    }

    @Test
    void withoutAPolicyTheSlaJobsDefaultWarningOf80PercentApplies() {
        assertThat(ReminderPolicy.due(ticket, null, at(79), settings)).isEmpty();
        assertThat(ReminderPolicy.due(ticket, null, at(81), settings)).contains(ReminderType.SLA_DUE_SOON);
    }

    @Test
    void pastTheResolutionDueDateTheSlaIsBreached() {
        assertThat(ReminderPolicy.due(ticket, policy, DUE.plusSeconds(1), settings)).contains(ReminderType.SLA_BREACHED);
        assertThat(ReminderPolicy.due(ticket, policy, DUE.plus(Duration.ofDays(3)), settings)).contains(ReminderType.SLA_BREACHED);
    }

    @Test
    void configuredEarlyRemindersFireAtTheirShareOfTheWindowAndTheHighestStageWins() {
        settings.setFirstReminderPercent(25);
        settings.setSecondReminderPercent(50);

        assertThat(ReminderPolicy.due(ticket, policy, at(20), settings)).isEmpty();
        assertThat(ReminderPolicy.due(ticket, policy, at(30), settings)).contains(ReminderType.FIRST_REMINDER);
        assertThat(ReminderPolicy.due(ticket, policy, at(60), settings)).contains(ReminderType.SECOND_REMINDER);
        assertThat(ReminderPolicy.due(ticket, policy, at(85), settings)).contains(ReminderType.SLA_DUE_SOON);
    }

    @Test
    void nonsensicalPercentagesAreIgnoredRatherThanFiringImmediately() {
        settings.setFirstReminderPercent(0);
        settings.setSecondReminderPercent(150);

        assertThat(ReminderPolicy.due(ticket, policy, at(10), settings)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"RESOLVED", "CLOSED", "CANCELLED"})
    void resolvedClosedAndCancelledTicketsAreNeverReminded(TicketStatus status) {
        ticket.setStatus(status);

        assertThat(ReminderPolicy.due(ticket, policy, DUE.plusSeconds(60), settings)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"NEW", "TRIAGE", "ASSIGNED", "IN_PROGRESS", "WAITING_APPROVAL",
            "WAITING_VENDOR", "REOPENED"})
    void everyOpenStatusIsEligible(TicketStatus status) {
        ticket.setStatus(status);

        assertThat(ReminderPolicy.due(ticket, policy, DUE.plusSeconds(60), settings)).contains(ReminderType.SLA_BREACHED);
    }

    @Test
    void unassignedTicketsInactiveAssigneesAndPausedSlasAreNotReminded() {
        Instant late = DUE.plusSeconds(60);

        ticket.getAssignee().setActive(false);
        assertThat(ReminderPolicy.due(ticket, policy, late, settings)).as("inactive").isEmpty();

        ticket.setAssignee(null);
        assertThat(ReminderPolicy.due(ticket, policy, late, settings)).as("unassigned").isEmpty();

        User someone = new User();
        someone.setActive(true);
        ticket.setAssignee(someone);
        ticket.setSlaPausedAt(CREATED.plusSeconds(60));
        assertThat(ReminderPolicy.due(ticket, policy, late, settings)).as("paused").isEmpty();

        ticket.setSlaPausedAt(null);
        ticket.setResolutionDue(null);
        assertThat(ReminderPolicy.due(ticket, policy, late, settings)).as("no SLA").isEmpty();
    }
}
