package com.erp.ticketing.integration.notification;

import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.integration.notification.TicketEmailComposer.Extras;
import com.erp.ticketing.integration.notification.TicketEmailComposer.Message;
import com.erp.ticketing.notification.model.NotificationTemplate;
import com.erp.ticketing.notification.repository.NotificationTemplateRepository;
import com.erp.ticketing.ticket.model.Confidentiality;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketEmailComposerTest {

    private NotificationTemplateRepository templates;
    private TicketEmailComposer composer;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        NotificationServiceProperties properties = new NotificationServiceProperties();
        properties.setAppBaseUrl("https://helpdesk.example.test/");
        templates = mock(NotificationTemplateRepository.class);
        when(templates.findByEventType(anyString())).thenReturn(Optional.empty());
        composer = new TicketEmailComposer(properties, templates);

        Department department = new Department();
        department.setId(1L);
        department.setDepartmentName("Human Resources");
        User creator = new User();
        creator.setName("Asha Rao");
        ticket = new Ticket();
        ticket.setId(42L);
        ticket.setTicketNumber("HR-2026-000042");
        ticket.setTitle("Payslip <missing>");
        ticket.setDescription("Overtime & bonus not shown");
        ticket.setPriority(TicketPriority.P2);
        ticket.setStatus(TicketStatus.NEW);
        ticket.setDepartment(department);
        ticket.setCreator(creator);
        ticket.setCreatedAt(Instant.parse("2026-10-13T10:15:00Z"));
    }

    private void template(String eventType, String subject, String body) {
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(eventType);
        template.setSubjectTemplate(subject);
        template.setBodyTemplate(body);
        when(templates.findByEventType(eventType)).thenReturn(Optional.of(template));
    }

    // ------------------------------------------------------------------ from the stored templates

    @Test
    void rendersSubjectAndBodyFromTheStoredTemplate() {
        template("EMAIL_TICKET_CREATED", "New {{priority}} ticket {{ticketNumber}} in {{department}}",
                "<p>{{creatorName}} opened <b>{{ticketNumber}}</b> on {{createdAt}}.</p><a href=\"{{ticketLink}}\">Open</a>");

        Message message = composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none());

        assertThat(message.subject()).isEqualTo("New P2 ticket HR-2026-000042 in Human Resources");
        assertThat(message.html()).isEqualTo("<p>Asha Rao opened <b>HR-2026-000042</b> on 13 Oct 2026, 10:15 UTC.</p>"
                + "<a href=\"https://helpdesk.example.test/helpdesk/tickets/42\">Open</a>");
    }

    @Test
    void anEditedTemplateTakesEffectWithoutTouchingCode() {
        template("EMAIL_TICKET_RESOLVED", "Done: {{ticketNumber}}", "<p>Resolved by {{resolvedBy}}: {{resolutionNotes}}</p>");
        User resolver = new User();
        resolver.setName("Ravi Kumar");
        ticket.setResolvedBy(resolver);
        ticket.setResolutionSummary("Re-ran the payroll batch");

        Message message = composer.compose(NotificationEventType.TICKET_RESOLVED, ticket, Extras.none());

        assertThat(message.subject()).isEqualTo("Done: HR-2026-000042");
        assertThat(message.html()).isEqualTo("<p>Resolved by Ravi Kumar: Re-ran the payroll batch</p>");
    }

    @Test
    void placeholderValuesAreEscapedButTheTemplateMarkupIsKept() {
        template("EMAIL_TICKET_CREATED", "{{title}}", "<h2>{{title}}</h2><p>{{description}}</p>");

        Message message = composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none());

        assertThat(message.html()).isEqualTo("<h2>Payslip &lt;missing&gt;</h2><p>Overtime &amp; bonus not shown</p>");
        assertThat(message.subject()).as("subjects are plain text, not HTML").isEqualTo("Payslip <missing>");
    }

    @Test
    void missingValuesRenderAsADashAndUnknownPlaceholdersAsNothing() {
        template("EMAIL_TICKET_ASSIGNED", "S", "<p>due: {{resolutionDue}} / by: {{assignedBy}} / x: [{{notAThing}}]</p>");

        Message message = composer.compose(NotificationEventType.TICKET_ASSIGNED, ticket, Extras.none());

        assertThat(message.html()).isEqualTo("<p>due: - / by: - / x: []</p>");
    }

    @Test
    void multiLineValuesKeepTheirLineBreaks() {
        template("EMAIL_TICKET_CREATED", "S", "<p>{{description}}</p>");
        ticket.setDescription("line one\nline two");

        assertThat(composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none()).html())
                .isEqualTo("<p>line one<br>line two</p>");
    }

    @Test
    void aSubjectCannotCarryLineBreaksOrGrowWithoutBound() {
        template("EMAIL_TICKET_CREATED", "{{title}}\r\nBcc: attacker@example.com", "<p>x</p>");
        ticket.setTitle("T".repeat(400));

        String subject = composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none()).subject();

        assertThat(subject).doesNotContain("\n").doesNotContain("\r");
        assertThat(subject.length()).isLessThanOrEqualTo(250);
    }

    @Test
    void theTextAlternativeIsDerivedFromTheHtmlAndSpellsOutTheLink() {
        template("EMAIL_TICKET_CREATED", "S",
                "<h2>New ticket {{ticketNumber}}</h2><table><tr><td>By</td><td>{{creatorName}} &amp; team</td></tr></table>");

        Message message = composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none());

        assertThat(message.text()).contains("New ticket HR-2026-000042").contains("By Asha Rao & team")
                .contains("Open ticket: https://helpdesk.example.test/helpdesk/tickets/42").doesNotContain("<");
    }

    @Test
    void aRestrictedTicketNeverShowsItsTextEvenIfTheTemplateAsksForIt() {
        template("EMAIL_TICKET_RESOLVED", "Resolved {{ticketNumber}} {{title}}",
                "<p>{{title}} | {{description}} | {{resolutionNotes}}</p>");
        ticket.setConfidentiality(Confidentiality.CONFIDENTIAL);
        ticket.setResolutionSummary("secret details");

        Message message = composer.compose(NotificationEventType.TICKET_RESOLVED, ticket, Extras.none());

        assertThat(message.subject()).doesNotContain("Payslip");
        assertThat(message.html()).doesNotContain("Payslip").doesNotContain("Overtime").doesNotContain("secret details");
        assertThat(message.text()).doesNotContain("Payslip").doesNotContain("secret details");
        assertThat(message.subject()).contains("HR-2026-000042");
    }

    @Test
    void aTemplateThatBlowsUpFallsBackToTheBuiltInWording() {
        NotificationTemplate broken = new NotificationTemplate();
        broken.setEventType("EMAIL_TICKET_CREATED");
        broken.setSubjectTemplate(null);
        broken.setBodyTemplate("<p>x</p>");
        when(templates.findByEventType("EMAIL_TICKET_CREATED")).thenReturn(Optional.of(broken));

        Message message = composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none());

        assertThat(message.subject()).startsWith("New ticket HR-2026-000042");
    }

    // ------------------------------------------------------------------ built-in fallback (no template row)

    @Test
    void withoutATemplateTheBuiltInCreationMailCarriesTheDetailsAndALink() {
        Message message = composer.compose(NotificationEventType.TICKET_CREATED, ticket, Extras.none());

        assertThat(message.subject()).startsWith("New ticket HR-2026-000042");
        assertThat(message.text()).contains("Asha Rao", "Human Resources", "P2", "NEW", "13 Oct 2026, 10:15 UTC")
                .contains("https://helpdesk.example.test/helpdesk/tickets/42");
        assertThat(message.html()).contains("Payslip &lt;missing&gt;").contains("Overtime &amp; bonus")
                .doesNotContain("<missing>");
    }

    @Test
    void withoutATemplateAssignmentNamesTheHandlerTheAssignerAndTheDueDate() {
        User handler = new User();
        handler.setName("Ravi Kumar");
        User admin = new User();
        admin.setName("Priya Admin");
        ticket.setResolutionDue(Instant.parse("2026-10-15T17:00:00Z"));

        Message message = composer.compose(NotificationEventType.TICKET_ASSIGNED, ticket,
                new Extras(handler, admin, Instant.parse("2026-10-13T11:00:00Z")));

        assertThat(message.subject()).startsWith("Ticket assigned to you: HR-2026-000042");
        assertThat(message.text()).contains("Ravi Kumar", "Priya Admin", "13 Oct 2026, 11:00 UTC", "15 Oct 2026, 17:00 UTC");
    }
}
