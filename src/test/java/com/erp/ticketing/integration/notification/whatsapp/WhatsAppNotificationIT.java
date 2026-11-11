package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.DeliveryChannel;
import com.erp.ticketing.integration.notification.DeliveryOutcome;
import com.erp.ticketing.integration.notification.NotificationDispatcher;
import com.erp.ticketing.integration.notification.NotificationEventType;
import com.erp.ticketing.integration.notification.NotificationServiceClient;
import com.erp.ticketing.integration.notification.NotificationServiceClient.WhatsAppTemplateRequest;
import com.erp.ticketing.integration.notification.OutboxStatus;
import com.erp.ticketing.integration.notification.model.DeliveryAttempt;
import com.erp.ticketing.integration.notification.model.NotificationOutbox;
import com.erp.ticketing.integration.notification.repository.DeliveryAttemptRepository;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.integration.notification.whatsapp.IdentityPhoneDirectory.Lookup;
import com.erp.ticketing.integration.notification.whatsapp.IdentityPhoneDirectory.Status;
import com.erp.ticketing.integration.notification.whatsapp.WhatsAppTemplateComposer.UrlButton;
import com.erp.ticketing.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WhatsApp template notifications end to end, against the real Flyway-migrated database, with the
 * notification-service (which would call Meta) and identity-service (phone numbers) mocked. No WhatsApp message
 * is ever sent. Each test rolls back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "app.notification-service.enabled=true",
        "app.notification-service.retry-base-delay=1s",
        "app.notification-service.retry-max-delay=8s",
        "app.notification-service.max-attempts=3",
        "app.notification-service.poll-interval-ms=3600000",
        "app.whatsapp.enabled=true",
        "app.whatsapp.display-time-zone=Asia/Kolkata",
        // Switched on per test, so the scheduler's start-up scan never writes outside a test transaction.
        "app.whatsapp.reminders.enabled=false",
        "app.whatsapp.reminders.fixed-delay-ms=3600000"
})
class WhatsAppNotificationIT {

    private static final long INCIDENT = 1L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JwtService jwt;
    @Autowired
    private NotificationOutboxRepository outbox;
    @Autowired
    private DeliveryAttemptRepository attempts;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private WhatsAppReminderJob reminderJob;
    @Autowired
    private WhatsAppProperties whatsApp;
    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private NotificationServiceClient client;
    @MockBean
    private IdentityPhoneDirectory directory;
    @SpyBean
    private WhatsAppTemplateComposer composer;

    @BeforeEach
    void setUp() {
        when(client.sendEmail(any(), any())).thenReturn(DeliveryOutcome.accepted(200, "SENT"));
        when(client.sendWhatsAppTemplate(any(), any())).thenReturn(DeliveryOutcome.accepted(200, "SENT", "wamid.TEST"));
        when(directory.lookup(any())).thenReturn(new Lookup(Status.FOUND, "+91 98765 43210", 200));
        whatsApp.setEnabled(true);
        whatsApp.getReminders().setEnabled(true);
    }

    @AfterEach
    void restore() {
        whatsApp.setEnabled(true);
        whatsApp.getReminders().setEnabled(false);
        whatsApp.getTicketAssigned().setUrlButtonIndex(null);
    }

    // ------------------------------------------------------------------ ticket_created

    @Test
    void creationQueuesTicketCreatedForTheRequesterInsideTheTicketTransaction() throws Exception {
        String requester = user("req");
        long ticketId = createTicket(requester);

        List<NotificationOutbox> rows = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED);
        assertThat(rows).hasSize(1);
        NotificationOutbox row = rows.get(0);
        assertThat(row.getRecipientUserId()).isEqualTo(me(requester, "REQUESTER"));
        assertThat(row.getTemplateName()).isEqualTo("ticket_created");
        assertThat(row.getTemplateLanguage()).isEqualTo("en_US");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getIdempotencyKey()).isEqualTo("TICKET_CREATED:" + ticketId + ":" + row.getRecipientUserId() + ":WHATSAPP");
        assertThat(composer.fromJson(row.getTemplateParams()).body())
                .hasSize(5)
                .startsWith(requester)
                .contains("Test ticket", "Medium");
        // Nothing leaves the process from inside the ticket transaction.
        verify(client, never()).sendWhatsAppTemplate(any(), any());
    }

    @Test
    void dispatchSendsTheTemplateToTheNormalisedNumberAndKeepsMetasMessageId() throws Exception {
        long ticketId = createTicket(user("req"));

        dispatcher.dispatchDue();

        NotificationOutbox row = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.ACCEPTED);
        assertThat(row.getAcceptedAt()).isNotNull();
        assertThat(row.getProviderMessageId()).isEqualTo("wamid.TEST");
        assertThat(row.getRecipientPhone()).isEqualTo("919876543210");
        assertThat(attempts.findByOutboxIdOrderByAttemptNoAsc(row.getId())).extracting(DeliveryAttempt::getOutcome)
                .containsExactly("ACCEPTED");

        ArgumentCaptor<WhatsAppTemplateRequest> sent = ArgumentCaptor.forClass(WhatsAppTemplateRequest.class);
        verify(client).sendWhatsAppTemplate(sent.capture(), any());
        WhatsAppTemplateRequest request = sent.getValue();
        assertThat(request.to()).isEqualTo("919876543210");
        assertThat(request.templateName()).isEqualTo("ticket_created");
        assertThat(request.languageCode()).isEqualTo("en_US");
        assertThat(request.module()).isEqualTo("helpdesk");
        assertThat(request.bodyParameters()).hasSize(5);
        assertThat(request.bodyParameters().get(4)).matches("\\d{1,2} \\w{3} \\d{4}, \\d{1,2}:\\d{2} (AM|PM)");
        assertThat(request.buttons()).isEmpty();
        assertThat(request.idempotencyKey()).isEqualTo(row.getIdempotencyKey());
        assertThat(dispatcher.dispatchDue()).as("not sent twice").isZero();
    }

    @Test
    void theSameEventProcessedTwiceQueuesOneMessage() throws Exception {
        long ticketId = createTicket(user("req"));
        entityManager.flush();

        publish(new com.erp.ticketing.integration.notification.event.TicketCreatedEvent(ticketId));
        publish(new com.erp.ticketing.integration.notification.event.TicketCreatedEvent(ticketId));

        assertThat(whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED)).hasSize(1);
    }

    // ------------------------------------------------------------------ ticket_assigned

    @Test
    void assignmentAndReassignmentEachNotifyTheNewHandlerButTheSameHandlerAgainDoesNot() throws Exception {
        String admin = user("admin");
        String first = staff("first");
        String second = staff("second");
        long ticketId = createTicket(user("req"));

        assign(admin, ticketId, first);
        List<NotificationOutbox> afterFirst = whatsAppRows(ticketId, NotificationEventType.TICKET_ASSIGNED);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getRecipientUserId()).isEqualTo(me(first, "AGENT"));
        assertThat(afterFirst.get(0).getTemplateName()).isEqualTo("ticket_assigned");
        List<String> body = composer.fromJson(afterFirst.get(0).getTemplateParams()).body();
        assertThat(body).hasSize(7);
        assertThat(body.get(0)).isEqualTo(first);
        assertThat(body.get(2)).isEqualTo("Test ticket");
        assertThat(body.get(3)).isEqualTo("desc");
        assertThat(body.get(4)).isEqualTo("Medium");
        assertThat(body.get(5)).isEqualTo(admin);

        assign(admin, ticketId, second);
        assertThat(whatsAppRows(ticketId, NotificationEventType.TICKET_ASSIGNED)).extracting(NotificationOutbox::getRecipientUserId)
                .containsExactly(me(first, "AGENT"), me(second, "AGENT"));

        update(admin, ticketId, "{\"title\": \"Clearer title\"}").andExpect(status().isOk());
        assign(admin, ticketId, second);
        assertThat(whatsAppRows(ticketId, NotificationEventType.TICKET_ASSIGNED)).as("assignee unchanged").hasSize(2);
    }

    @Test
    void anAssignmentThatWasOverriddenBeforeItsTurnIsCancelledNotSent() throws Exception {
        String admin = user("admin");
        String first = staff("first");
        String second = staff("second");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, first);
        assign(admin, ticketId, second);

        dispatcher.dispatchDue();

        List<NotificationOutbox> rows = whatsAppRows(ticketId, NotificationEventType.TICKET_ASSIGNED);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.CANCELLED);
        assertThat(rows.get(0).getLastError()).isEqualTo("Ticket was reassigned");
        assertThat(rows.get(1).getStatus()).isEqualTo(OutboxStatus.ACCEPTED);
        // ticket_created + the current handler's ticket_assigned only.
        verify(client, times(2)).sendWhatsAppTemplate(any(), any());
    }

    @Test
    void aConfiguredUrlButtonCarriesTheTicketId() throws Exception {
        whatsApp.getTicketAssigned().setUrlButtonIndex(0);
        String admin = user("admin");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, staff("h"));

        dispatcher.dispatchDue();

        ArgumentCaptor<WhatsAppTemplateRequest> sent = ArgumentCaptor.forClass(WhatsAppTemplateRequest.class);
        verify(client, times(2)).sendWhatsAppTemplate(sent.capture(), any());
        WhatsAppTemplateRequest assigned = sent.getAllValues().stream()
                .filter(r -> r.templateName().equals("ticket_assigned")).findFirst().orElseThrow();
        assertThat(assigned.buttons()).containsExactly(new UrlButton(0, String.valueOf(ticketId)));
        WhatsAppTemplateRequest created = sent.getAllValues().stream()
                .filter(r -> r.templateName().equals("ticket_created")).findFirst().orElseThrow();
        assertThat(created.buttons()).as("not configured for ticket_created").isEmpty();
    }

    // ------------------------------------------------------------------ recipients and failures

    @Test
    void aRecipientWithoutAPhoneNumberFailsPermanentlyAndTheTicketIsUnaffected() throws Exception {
        when(directory.lookup(any())).thenReturn(new Lookup(Status.FOUND, null, 200));
        long ticketId = createTicket(user("req"));

        dispatcher.dispatchDue();

        NotificationOutbox row = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("Recipient has no phone number on file");
        assertThat(dispatcher.dispatchDue()).as("not retried").isZero();
        verify(client, never()).sendWhatsAppTemplate(any(), any());
        mvc.perform(get("/api/helpdesk/tickets/{id}", ticketId).header("Authorization", bearer(user("a"), "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ticketId));
    }

    @Test
    void anInvalidPhoneNumberFailsPermanentlyWithoutCallingTheProvider() throws Exception {
        when(directory.lookup(any())).thenReturn(new Lookup(Status.FOUND, "98765", 200));
        long ticketId = createTicket(user("req"));

        dispatcher.dispatchDue();

        NotificationOutbox row = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getLastError()).contains("not a valid international number").doesNotContain("98765");
        verify(client, never()).sendWhatsAppTemplate(any(), any());
    }

    @Test
    void identityOutagesAndTransientMetaErrorsAreRetriedWithBackoffThenSucceed() throws Exception {
        when(directory.lookup(any()))
                .thenReturn(new Lookup(Status.UNAVAILABLE, null, 503))
                .thenReturn(new Lookup(Status.FOUND, "+919876543210", 200));
        when(client.sendWhatsAppTemplate(any(), any()))
                .thenReturn(DeliveryOutcome.retryable(502, "WhatsApp Cloud API HTTP 400, error 130429: Rate limit hit", null))
                .thenReturn(DeliveryOutcome.accepted(200, "SENT", "wamid.LATE"));
        long ticketId = createTicket(user("req"));
        NotificationOutbox row = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED).get(0);

        dispatcher.dispatchDue();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(dispatcher.dispatchDue()).as("backing off").isZero();

        makeDue(row);
        dispatcher.dispatchDue();
        makeDue(row);
        dispatcher.dispatchDue();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.ACCEPTED);
        assertThat(row.getProviderMessageId()).isEqualTo("wamid.LATE");
        assertThat(attempts.findByOutboxIdOrderByAttemptNoAsc(row.getId())).extracting(DeliveryAttempt::getOutcome)
                .containsExactly("RETRY_SCHEDULED", "RETRY_SCHEDULED", "ACCEPTED");
        verify(directory, times(2)).lookup(any());
    }

    @Test
    void permanentMetaErrorsAreNotRetried() throws Exception {
        when(client.sendWhatsAppTemplate(any(), any()))
                .thenReturn(DeliveryOutcome.permanent(422, "WhatsApp Cloud API HTTP 404, error 132001: Template name does not exist"));
        long ticketId = createTicket(user("req"));

        dispatcher.dispatchDue();

        NotificationOutbox row = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("132001");
        makeDue(row);
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void aFailingWhatsAppRecorderNeverFailsTheTicketOperation() throws Exception {
        doThrow(new IllegalStateException("template blew up")).when(composer).ticketCreated(any());

        long ticketId = createTicket(user("req"));

        assertThat(ticketId).isPositive();
        assertThat(whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED)).isEmpty();
        mvc.perform(get("/api/helpdesk/tickets/{id}", ticketId).header("Authorization", bearer(user("a"), "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void switchedOffNothingIsQueuedAndAnythingStillQueuedIsCancelled() throws Exception {
        long queued = createTicket(user("req"));
        whatsApp.setEnabled(false);

        long notQueued = createTicket(user("req2"));
        dispatcher.dispatchDue();

        assertThat(whatsAppRows(notQueued, NotificationEventType.TICKET_CREATED)).isEmpty();
        assertThat(whatsAppRows(queued, NotificationEventType.TICKET_CREATED).get(0).getStatus()).isEqualTo(OutboxStatus.CANCELLED);
        verify(client, never()).sendWhatsAppTemplate(any(), any());
    }

    @Test
    void emailNotificationsAreUnchangedAlongsideWhatsApp() throws Exception {
        String hr = user("hr");
        long hrId = me(hr, "REQUESTER");
        mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(user("cfg"), "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + hrId + ", \"eventType\": \"TICKET_CREATED\"}"))
                .andExpect(status().isCreated());

        long ticketId = createTicket(user("req"));

        List<NotificationOutbox> rows = rows(ticketId);
        assertThat(rows).extracting(NotificationOutbox::getChannel)
                .containsExactlyInAnyOrder(DeliveryChannel.EMAIL, DeliveryChannel.WHATSAPP);
        NotificationOutbox email = rows.stream().filter(r -> r.getChannel() == DeliveryChannel.EMAIL).findFirst().orElseThrow();
        assertThat(email.getRecipientUserId()).isEqualTo(hrId);
        assertThat(email.getTemplateName()).isNull();
    }

    // ------------------------------------------------------------------ ticket_reminder

    @Test
    void aBreachedTicketRemindsItsAssigneeOnceHoweverOftenTheJobRuns() throws Exception {
        String admin = user("admin");
        String handler = staff("h");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, handler);
        setSlaWindow(ticketId, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofMinutes(5)));

        reminderJob.scan(Instant.now());
        reminderJob.scan(Instant.now());
        reminderJob.scan(Instant.now());

        List<NotificationOutbox> reminders = whatsAppRows(ticketId, NotificationEventType.TICKET_REMINDER);
        assertThat(reminders).hasSize(1);
        NotificationOutbox reminder = reminders.get(0);
        assertThat(reminder.getRecipientUserId()).isEqualTo(me(handler, "AGENT"));
        assertThat(reminder.getTemplateName()).isEqualTo("ticket_reminder");
        List<String> body = composer.fromJson(reminder.getTemplateParams()).body();
        assertThat(body).hasSize(7);
        assertThat(body.get(0)).isEqualTo(handler);
        assertThat(body.get(3)).isEqualTo("Assigned");
        assertThat(body.get(4)).isEqualTo("Medium");
        assertThat(body.get(6)).isEqualTo("SLA Breached");
    }

    @Test
    void approachingTheSlaPolicysWarningThresholdSendsSlaDueSoon() throws Exception {
        String admin = user("admin");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, staff("h"));
        // 90% of a 10-hour window has elapsed; the policies' warning threshold (default 80%) is passed.
        setSlaWindow(ticketId, Instant.now().minus(Duration.ofHours(9)), Instant.now().plus(Duration.ofHours(1)));

        reminderJob.scan(Instant.now());

        List<NotificationOutbox> reminders = whatsAppRows(ticketId, NotificationEventType.TICKET_REMINDER);
        assertThat(reminders).hasSize(1);
        assertThat(composer.fromJson(reminders.get(0).getTemplateParams()).body().get(6)).isEqualTo("SLA Due Soon");
    }

    @Test
    void aTicketEarlyInItsSlaWindowOrWithoutAnAssigneeIsNotReminded() throws Exception {
        String admin = user("admin");
        long early = createTicket(user("req"));
        assign(admin, early, staff("h"));
        setSlaWindow(early, Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(9)));
        long unassigned = createTicket(user("req2"));
        setSlaWindow(unassigned, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofMinutes(5)));

        reminderJob.scan(Instant.now());

        assertThat(whatsAppRows(early, NotificationEventType.TICKET_REMINDER)).isEmpty();
        assertThat(whatsAppRows(unassigned, NotificationEventType.TICKET_REMINDER)).isEmpty();
    }

    @Test
    void reassignmentGivesTheNewAssigneeTheirOwnReminderAndCancelsTheOldOnesPendingReminder() throws Exception {
        String admin = user("admin");
        String first = staff("first");
        String second = staff("second");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, first);
        setSlaWindow(ticketId, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofMinutes(5)));
        reminderJob.scan(Instant.now());

        assign(admin, ticketId, second);
        reminderJob.scan(Instant.now());
        dispatcher.dispatchDue();

        List<NotificationOutbox> reminders = whatsAppRows(ticketId, NotificationEventType.TICKET_REMINDER);
        assertThat(reminders).extracting(NotificationOutbox::getRecipientUserId)
                .containsExactly(me(first, "AGENT"), me(second, "AGENT"));
        assertThat(reminders).extracting(NotificationOutbox::getStatus)
                .containsExactly(OutboxStatus.CANCELLED, OutboxStatus.ACCEPTED);
    }

    @Test
    void resolvedAndClosedTicketsAreNotRemindedAndAPendingReminderIsCancelledOnResolution() throws Exception {
        String admin = user("admin");
        String handler = staff("h");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, handler);
        setSlaWindow(ticketId, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofMinutes(5)));
        reminderJob.scan(Instant.now());
        assertThat(whatsAppRows(ticketId, NotificationEventType.TICKET_REMINDER)).hasSize(1);

        update(handler, ticketId, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(handler, ticketId, "{\"status\": \"RESOLVED\", \"resolutionCode\": \"FIXED\", "
                + "\"resolutionSummary\": \"done\"}").andExpect(status().isOk());
        reminderJob.scan(Instant.now());
        dispatcher.dispatchDue();

        List<NotificationOutbox> reminders = whatsAppRows(ticketId, NotificationEventType.TICKET_REMINDER);
        assertThat(reminders).as("no new reminder after resolution").hasSize(1);
        assertThat(reminders.get(0).getStatus()).isEqualTo(OutboxStatus.CANCELLED);
        assertThat(reminders.get(0).getLastError()).isEqualTo("Ticket is RESOLVED");
        verify(client, never()).sendWhatsAppTemplate(
                org.mockito.ArgumentMatchers.argThat(r -> r != null && "ticket_reminder".equals(r.templateName())), any());
    }

    @Test
    void remindersRespectTheSwitch() throws Exception {
        whatsApp.getReminders().setEnabled(false);
        String admin = user("admin");
        long ticketId = createTicket(user("req"));
        assign(admin, ticketId, staff("h"));
        setSlaWindow(ticketId, Instant.now().minus(Duration.ofHours(10)), Instant.now().minus(Duration.ofMinutes(5)));

        assertThat(reminderJob.scan(Instant.now())).isZero();
        assertThat(whatsAppRows(ticketId, NotificationEventType.TICKET_REMINDER)).isEmpty();
    }

    @Test
    void theOutboxKeyAloneMakesAConcurrentDuplicateANoOp() throws Exception {
        long ticketId = createTicket(user("req"));
        long userId = whatsAppRows(ticketId, NotificationEventType.TICKET_CREATED).get(0).getRecipientUserId();
        String key = "TICKET_REMINDER:" + ticketId + ":0:SLA_BREACHED:" + userId + ":WHATSAPP";

        int first = outbox.insertWhatsAppIfAbsent(key, "TICKET_REMINDER", ticketId, userId, "x", "ticket_reminder",
                "en_US", "{\"body\":[\"a\"],\"buttons\":[]}", null);
        int second = outbox.insertWhatsAppIfAbsent(key, "TICKET_REMINDER", ticketId, userId, "x", "ticket_reminder",
                "en_US", "{\"body\":[\"a\"],\"buttons\":[]}", null);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    void adminsSeeWhatsAppDeliveryStateWithAMaskedNumber() throws Exception {
        createTicket(user("req"));
        when(client.sendWhatsAppTemplate(any(), any())).thenReturn(DeliveryOutcome.permanent(422, "Template name does not exist"));
        dispatcher.dispatchDue();

        mvc.perform(get("/api/helpdesk/admin/notification-outbox").header("Authorization", bearer(user("a"), "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].templateName").value("ticket_created"))
                .andExpect(jsonPath("$[0].recipientPhone").value("********3210"));
    }

    // ------------------------------------------------------------------ helpers

    @Autowired
    private org.springframework.context.ApplicationEventPublisher publisher;

    private void publish(Object event) {
        publisher.publishEvent(event);
    }

    private List<NotificationOutbox> rows(long ticketId) {
        outbox.flush();
        return outbox.findByTicketIdOrderByIdAsc(ticketId);
    }

    private List<NotificationOutbox> whatsAppRows(long ticketId, NotificationEventType type) {
        return rows(ticketId).stream()
                .filter(r -> r.getChannel() == DeliveryChannel.WHATSAPP && r.getEventType() == type).toList();
    }

    /** Moves the ticket's SLA window; created_at is not updatable through JPA, so this is plain SQL. */
    private void setSlaWindow(long ticketId, Instant createdAt, Instant resolutionDue) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE tickets SET created_at = :c, resolution_due = :d, sla_paused_at = NULL "
                        + "WHERE id = :id")
                .setParameter("c", Timestamp.from(createdAt))
                .setParameter("d", Timestamp.from(resolutionDue))
                .setParameter("id", ticketId)
                .executeUpdate();
        entityManager.clear();
    }

    private void makeDue(NotificationOutbox row) {
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        row.setLeaseUntil(null);
        outbox.saveAndFlush(row);
    }

    private void assign(String adminLogin, long ticketId, String handlerLogin) throws Exception {
        update(adminLogin, ticketId, "{\"assigneeId\": " + me(handlerLogin, "AGENT") + "}").andExpect(status().isOk());
    }

    private long createTicket(String login) throws Exception {
        String res = mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(login, "REQUESTER"))
                        .contentType("application/json")
                        .content("{\"typeId\": " + INCIDENT + ", \"title\": \"Test ticket\", \"description\": \"desc\", \"priority\": \"P3\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private ResultActions update(String login, long id, String body) throws Exception {
        return mvc.perform(put("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(login, "ADMIN"))
                .contentType("application/json").content(body));
    }

    private long me(String login, String role) throws Exception {
        return json.readTree(mvc.perform(get("/api/helpdesk/me").header("Authorization", bearer(login, role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** An agent in the seeded General Support department (id 1), where routed tickets land. */
    private String staff(String prefix) throws Exception {
        String login = user(prefix);
        long id = me(login, "AGENT");
        mvc.perform(post("/api/helpdesk/departments/1/members").header("Authorization", bearer(user("setup-admin"), "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + id + ", \"isLead\": false}"))
                .andExpect(status().isCreated());
        return login;
    }

    private String user(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String bearer(String login, String role) {
        return "Bearer " + jwt.generateToken(login, login + "@example.com", login, Set.of(role));
    }
}
