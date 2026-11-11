package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.whatsapp.WhatsAppTemplateComposer.StoredParams;
import com.erp.ticketing.integration.notification.whatsapp.WhatsAppTemplateComposer.TemplateMessage;
import com.erp.ticketing.integration.notification.whatsapp.WhatsAppTemplateComposer.UrlButton;
import com.erp.ticketing.ticket.model.Confidentiality;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.user.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Template names and the exact order of each template's variables - the contract with the approved templates. */
class WhatsAppTemplateComposerTest {

    private WhatsAppProperties properties;
    private WhatsAppTemplateComposer composer;
    private Ticket ticket;
    private User requester;
    private User assignee;
    private User admin;

    @BeforeEach
    void setUp() {
        properties = new WhatsAppProperties();
        properties.setDisplayTimeZone(ZoneId.of("Asia/Kolkata"));
        composer = new WhatsAppTemplateComposer(properties, new ObjectMapper());

        requester = user(1L, "Rahul Sharma");
        assignee = user(2L, "Priya Nair");
        admin = user(3L, "IT Helpdesk Admin");
        ticket = new Ticket();
        ticket.setId(125L);
        ticket.setTicketNumber("TKT-2026-00125");
        ticket.setTitle("Unable to Access ERP Portal");
        ticket.setDescription("User is unable to log in to the ERP portal.");
        ticket.setPriority(TicketPriority.P2);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setRequester(requester);
        ticket.setAssignee(assignee);
        ticket.setConfidentiality(Confidentiality.STANDARD);
        ticket.setCreatedAt(Instant.parse("2026-11-11T06:00:00Z"));       // 11:30 AM IST
        ticket.setResolutionDue(Instant.parse("2026-11-12T11:30:00Z"));   // 5:00 PM IST
    }

    private static User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        return user;
    }

    @Test
    void ticketCreatedFillsItsFiveVariablesInOrder() {
        TemplateMessage message = composer.ticketCreated(ticket);

        assertThat(message.templateName()).isEqualTo("ticket_created");
        assertThat(message.languageCode()).isEqualTo("en_US");
        assertThat(message.body()).containsExactly(
                "Rahul Sharma", "TKT-2026-00125", "Unable to Access ERP Portal", "High", "11 Nov 2026, 11:30 AM");
        assertThat(message.buttons()).isEmpty();
    }

    @Test
    void ticketAssignedFillsItsSevenVariablesInOrder() {
        TemplateMessage message = composer.ticketAssigned(ticket, assignee, admin);

        assertThat(message.templateName()).isEqualTo("ticket_assigned");
        assertThat(message.body()).containsExactly(
                "Priya Nair", "TKT-2026-00125", "Unable to Access ERP Portal",
                "User is unable to log in to the ERP portal.", "High", "IT Helpdesk Admin", "12 Nov 2026, 5:00 PM");
    }

    @Test
    void anAssignmentByRoutingOrAutomationNamesNoPerson() {
        assertThat(composer.ticketAssigned(ticket, assignee, null).body().get(5)).isEqualTo("Auto-assignment");
    }

    @Test
    void ticketReminderFillsItsSevenVariablesInOrderForEveryReminderType() {
        for (ReminderType type : ReminderType.values()) {
            TemplateMessage message = composer.ticketReminder(ticket, assignee, type);
            assertThat(message.templateName()).isEqualTo("ticket_reminder");
            assertThat(message.body()).containsExactly(
                    "Priya Nair", "TKT-2026-00125", "Unable to Access ERP Portal", "In Progress", "High",
                    "12 Nov 2026, 5:00 PM", type.label());
        }
        assertThat(ReminderType.values()).extracting(ReminderType::label)
                .containsExactly("First Reminder", "Second Reminder", "SLA Due Soon", "SLA Breached");
    }

    @Test
    void configuredTemplateNamesAreUsed() {
        properties.getTicketCreated().setName("ticket_created_v2");
        properties.setLanguageCode("en");

        TemplateMessage message = composer.ticketCreated(ticket);

        assertThat(message.templateName()).isEqualTo("ticket_created_v2");
        assertThat(message.languageCode()).isEqualTo("en");
    }

    @Test
    void valuesAreMadeSafeForMetaAndNeverEmpty() {
        ticket.setTitle("Line one\nLine\ttwo      spaced");
        ticket.setDescription("x".repeat(1000));
        ticket.setResolutionDue(null);
        assignee.setName("  ");

        TemplateMessage message = composer.ticketAssigned(ticket, assignee, admin);

        assertThat(message.body().get(0)).isEqualTo("-");
        assertThat(message.body().get(2)).isEqualTo("Line one Line two spaced");
        assertThat(message.body().get(3)).hasSize(300).endsWith("…");
        assertThat(message.body().get(6)).isEqualTo("Not set");
        assertThat(message.body()).allSatisfy(value -> assertThat(value).isNotBlank().doesNotContain("\n", "\t"));
    }

    @Test
    void restrictedTicketsNeverRevealTitleOrDescription() {
        ticket.setConfidentiality(Confidentiality.CONFIDENTIAL);

        assertThat(composer.ticketAssigned(ticket, assignee, admin).body())
                .doesNotContain("Unable to Access ERP Portal", "User is unable to log in to the ERP portal.")
                .contains("(restricted ticket)", "(not shown for restricted tickets)");
        assertThat(composer.ticketCreated(ticket).body()).doesNotContain("Unable to Access ERP Portal");
        assertThat(composer.ticketReminder(ticket, assignee, ReminderType.SLA_BREACHED).body())
                .doesNotContain("Unable to Access ERP Portal");
    }

    @Test
    void aUrlButtonIsOnlySentWhenConfiguredAndCarriesTheTicketId() {
        assertThat(composer.ticketAssigned(ticket, assignee, admin).buttons()).isEmpty();

        properties.getTicketAssigned().setUrlButtonIndex(0);

        assertThat(composer.ticketAssigned(ticket, assignee, admin).buttons()).containsExactly(new UrlButton(0, "125"));
        assertThat(composer.ticketCreated(ticket).buttons()).as("only the configured template").isEmpty();
    }

    @Test
    void priorityAndStatusUseTheWebAppsLabels() {
        assertThat(WhatsAppTemplateComposer.priority(TicketPriority.P1)).isEqualTo("Critical");
        assertThat(WhatsAppTemplateComposer.priority(TicketPriority.P2)).isEqualTo("High");
        assertThat(WhatsAppTemplateComposer.priority(TicketPriority.P3)).isEqualTo("Medium");
        assertThat(WhatsAppTemplateComposer.priority(TicketPriority.P4)).isEqualTo("Low");
        assertThat(WhatsAppTemplateComposer.status(TicketStatus.WAITING_CUSTOMER)).isEqualTo("Waiting Customer");
        assertThat(WhatsAppTemplateComposer.status(TicketStatus.NEW)).isEqualTo("New");
    }

    @Test
    void utcDatesSayUtc() {
        properties.setDisplayTimeZone(ZoneId.of("UTC"));

        assertThat(composer.date(Instant.parse("2026-11-11T06:00:00Z"))).isEqualTo("11 Nov 2026, 6:00 AM UTC");
    }

    @Test
    void storedParametersRoundTrip() {
        properties.getTicketReminder().setUrlButtonIndex(1);
        TemplateMessage message = composer.ticketReminder(ticket, assignee, ReminderType.SLA_DUE_SOON);

        StoredParams stored = composer.fromJson(composer.toJson(message));

        assertThat(stored.body()).isEqualTo(message.body());
        assertThat(stored.buttons()).containsExactly(new UrlButton(1, "125"));
        assertThatThrownBy(() -> composer.fromJson("not json")).isInstanceOf(IllegalStateException.class);
    }
}
