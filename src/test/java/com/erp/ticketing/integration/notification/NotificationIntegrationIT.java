package com.erp.ticketing.integration.notification;

import com.erp.ticketing.integration.notification.event.TicketCreatedEvent;
import com.erp.ticketing.integration.notification.model.DeliveryAttempt;
import com.erp.ticketing.integration.notification.model.NotificationOutbox;
import com.erp.ticketing.integration.notification.repository.DeliveryAttemptRepository;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.security.JwtService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;

import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ticket lifecycle events through to the notification-service hand-over, against the real Flyway-migrated
 * database with the notification-service itself mocked - no mail is ever sent. Each test rolls back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "app.notification-service.enabled=true",
        "app.notification-service.app-base-url=https://helpdesk.example.test",
        "app.notification-service.retry-base-delay=1s",
        "app.notification-service.retry-max-delay=8s",
        "app.notification-service.max-attempts=3",
        "app.notification-service.poll-interval-ms=3600000"
})
class NotificationIntegrationIT {

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
    private UserRepository users;
    @Autowired
    private ApplicationEventPublisher publisher;
    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private NotificationServiceClient client;

    @BeforeEach
    void accept() {
        when(client.sendEmail(any(), any())).thenReturn(DeliveryOutcome.accepted(200, "SENT"));
    }

    // ------------------------------------------------------------------ A: ticket created

    @Test
    void creationQueuesAnEmailForEachConfiguredHrAndAdminRecipient() throws Exception {
        String hr = user("hr");
        String admin = user("admin");
        String bystander = user("bystander");
        subscribe(hr, "TICKET_CREATED");
        subscribe(admin, "TICKET_CREATED");
        me(bystander, "REQUESTER");

        long ticketId = createTicket(user("req"), "REQUESTER");

        List<NotificationOutbox> rows = rows(ticketId, NotificationEventType.TICKET_CREATED);
        assertThat(rows).extracting(NotificationOutbox::getRecipientUserId)
                .containsExactlyInAnyOrder(me(hr, "REQUESTER"), me(admin, "REQUESTER"));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getChannel()).isEqualTo(DeliveryChannel.EMAIL);
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(row.getSubject()).contains("New ticket");
            assertThat(row.getBodyHtml()).contains("https://helpdesk.example.test/helpdesk/tickets/" + ticketId);
            assertThat(row.getRecipientEmail()).endsWith("@example.com");
        });
        // Nothing is sent from inside the ticket transaction: delivery is the dispatcher's job, after commit.
        verify(client, never()).sendEmail(any(), any());
    }

    @Test
    void aSubscriptionScopedToAnotherDepartmentDoesNotFire() throws Exception {
        String hr = user("hr");
        long userId = me(hr, "REQUESTER");
        mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(user("a"), "ADMIN"))
                        .contentType("application/json")
                        .content("{\"userId\": " + userId + ", \"eventType\": \"TICKET_CREATED\", \"departmentId\": 999999}"))
                .andExpect(status().isNotFound());

        long ticketId = createTicket(user("req"), "REQUESTER");

        assertThat(rows(ticketId, NotificationEventType.TICKET_CREATED)).isEmpty();
    }

    @Test
    void aRecipientWithoutAnEmailAddressIsSkippedWithoutBreakingTheTicket() throws Exception {
        String noEmail = user("noemail");
        String hr = user("hr");
        subscribe(noEmail, "TICKET_CREATED");
        subscribe(hr, "TICKET_CREATED");
        User account = users.findById(me(noEmail, "REQUESTER")).orElseThrow();
        account.setEmail(null);
        users.saveAndFlush(account);

        long ticketId = createTicket(user("req"), "REQUESTER");

        assertThat(rows(ticketId, NotificationEventType.TICKET_CREATED)).extracting(NotificationOutbox::getRecipientUserId)
                .containsExactly(me(hr, "REQUESTER"));
    }

    // ------------------------------------------------------------------ B: ticket assigned

    @Test
    void assignmentNotifiesTheNewHandlerAndReassignmentNotifiesTheNextOne() throws Exception {
        String admin = user("admin");
        String first = staff("first");
        String second = staff("second");
        long ticketId = createTicket(user("req"), "REQUESTER");

        assign(admin, ticketId, first);
        List<NotificationOutbox> afterFirst = rows(ticketId, NotificationEventType.TICKET_ASSIGNED);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getRecipientUserId()).isEqualTo(me(first, "AGENT"));
        assertThat(afterFirst.get(0).getBodyText()).contains("Assigned by " + admin).contains("Assigned to " + first);

        assign(admin, ticketId, second);
        assertThat(rows(ticketId, NotificationEventType.TICKET_ASSIGNED)).extracting(NotificationOutbox::getRecipientUserId)
                .containsExactlyInAnyOrder(me(first, "AGENT"), me(second, "AGENT"));
    }

    @Test
    void unrelatedUpdatesAndRepeatingTheSameHandlerRaiseNoAssignmentNotification() throws Exception {
        String admin = user("admin");
        String handler = staff("handler");
        long ticketId = createTicket(user("req"), "REQUESTER");
        assign(admin, ticketId, handler);
        int before = rows(ticketId, NotificationEventType.TICKET_ASSIGNED).size();

        update(admin, "ADMIN", ticketId, "{\"title\": \"A clearer title\"}").andExpect(status().isOk());
        update(admin, "ADMIN", ticketId, "{\"priority\": \"P1\"}").andExpect(status().isOk());
        assign(admin, ticketId, handler);

        assertThat(rows(ticketId, NotificationEventType.TICKET_ASSIGNED)).hasSize(before);
    }

    // ------------------------------------------------------------------ C: ticket resolved

    @Test
    void resolutionNotifiesSubscribersCreatorAndHandlerOnceEach() throws Exception {
        String hr = user("hr");
        String admin = user("admin");
        String requester = user("req");
        String agent = staff("agent");
        subscribe(hr, "TICKET_RESOLVED");
        subscribe(admin, "TICKET_RESOLVED");
        long ticketId = createTicket(requester, "REQUESTER");
        // The admin is a subscriber AND the handler: one person, two roles.
        assign(admin, ticketId, admin);
        update(agent, "AGENT", ticketId, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());

        update(agent, "AGENT", ticketId, "{\"status\": \"RESOLVED\", \"resolutionCode\": \"FIXED\", "
                + "\"resolutionSummary\": \"Fixed the payroll batch\"}").andExpect(status().isOk());

        List<NotificationOutbox> rows = rows(ticketId, NotificationEventType.TICKET_RESOLVED);
        assertThat(rows).extracting(NotificationOutbox::getRecipientUserId)
                .containsExactlyInAnyOrder(me(hr, "REQUESTER"), me(admin, "REQUESTER"), me(requester, "REQUESTER"));
        assertThat(rows.get(0).getBodyText()).contains("Fixed the payroll batch").contains("RESOLVED");
    }

    @Test
    void anUnrelatedUpdateToAnAlreadyResolvedTicketDoesNotNotifyAgain() throws Exception {
        String agent = staff("agent");
        String admin = user("admin");
        subscribe(admin, "TICKET_RESOLVED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        update(agent, "AGENT", ticketId, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(agent, "AGENT", ticketId, "{\"status\": \"RESOLVED\", \"resolutionCode\": \"FIXED\", "
                + "\"resolutionSummary\": \"done\"}").andExpect(status().isOk());
        int before = rows(ticketId, NotificationEventType.TICKET_RESOLVED).size();

        update(agent, "AGENT", ticketId, "{\"tags\": {\"note\": \"x\"}}").andExpect(status().isOk());
        update(agent, "AGENT", ticketId, "{\"status\": \"RESOLVED\"}").andExpect(status().isOk());

        assertThat(rows(ticketId, NotificationEventType.TICKET_RESOLVED)).hasSize(before);
    }

    @Test
    void aReopenedTicketThatIsResolvedAgainNotifiesAgain() throws Exception {
        String agent = staff("agent");
        String admin = user("admin");
        subscribe(admin, "TICKET_RESOLVED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        update(agent, "AGENT", ticketId, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        String resolve = "{\"status\": \"RESOLVED\", \"resolutionCode\": \"FIXED\", \"resolutionSummary\": \"done\"}";
        update(agent, "AGENT", ticketId, resolve).andExpect(status().isOk());
        int afterFirst = rows(ticketId, NotificationEventType.TICKET_RESOLVED).size();

        update(agent, "AGENT", ticketId, "{\"status\": \"REOPENED\"}").andExpect(status().isOk());
        update(agent, "AGENT", ticketId, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(agent, "AGENT", ticketId, resolve).andExpect(status().isOk());

        assertThat(rows(ticketId, NotificationEventType.TICKET_RESOLVED)).hasSize(afterFirst * 2);
    }

    // ------------------------------------------------------------------ failures raise nothing

    @Test
    void failedOperationsProduceNoNotifications() throws Exception {
        String admin = user("admin");
        String requester = user("req");
        String agent = staff("agent");
        subscribe(admin, "TICKET_CREATED");
        subscribe(admin, "TICKET_RESOLVED");
        long before = outbox.count();

        // Creation rejected (bad priority); resolution rejected (no resolution details);
        // assignment rejected (a requester may not assign).
        mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(requester, "REQUESTER"))
                        .contentType("application/json")
                        .content("{\"typeId\": " + INCIDENT + ", \"title\": \"t\", \"description\": \"d\", \"priority\": \"NOPE\"}"))
                .andExpect(status().isBadRequest());
        long ticketId = createTicket(requester, "REQUESTER");
        long afterCreate = outbox.count();
        update(agent, "AGENT", ticketId, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(agent, "AGENT", ticketId, "{\"status\": \"RESOLVED\"}").andExpect(status().isBadRequest());
        update(requester, "REQUESTER", ticketId, "{\"assigneeId\": " + me(agent, "AGENT") + "}").andExpect(status().isBadRequest());

        assertThat(afterCreate - before).isEqualTo(1); // only the successful creation, for the one subscriber
        assertThat(outbox.count()).isEqualTo(afterCreate);
        assertThat(rows(ticketId, NotificationEventType.TICKET_RESOLVED)).isEmpty();
        assertThat(rows(ticketId, NotificationEventType.TICKET_ASSIGNED)).isEmpty();
    }

    @Test
    void aRolledBackChangeLeavesNoNotificationBehind() throws Exception {
        String admin = user("admin");
        subscribe(admin, "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        outbox.deleteAll();
        outbox.flush();

        // Same connection, same transaction: a savepoint stands in for "the transaction that rolled back".
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            Savepoint savepoint = connection.setSavepoint();
            publisher.publishEvent(new TicketCreatedEvent(ticketId));
            assertThat(outbox.findByTicketIdOrderByIdAsc(ticketId)).hasSize(1);
            connection.rollback(savepoint);
        });
        entityManager.clear();

        assertThat(outbox.findByTicketIdOrderByIdAsc(ticketId)).isEmpty();
    }

    @Test
    void processingTheSameEventTwiceDoesNotDuplicateMessages() throws Exception {
        String admin = user("admin");
        subscribe(admin, "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        assertThat(rows(ticketId, NotificationEventType.TICKET_CREATED)).hasSize(1);

        publisher.publishEvent(new TicketCreatedEvent(ticketId));
        publisher.publishEvent(new TicketCreatedEvent(ticketId));

        assertThat(rows(ticketId, NotificationEventType.TICKET_CREATED)).hasSize(1);
    }

    // ------------------------------------------------------------------ dispatch, retry, failure

    @Test
    void anAcceptedHandOverIsRecordedAndNotRepeated() throws Exception {
        subscribe(user("admin"), "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero();

        NotificationOutbox row = rows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.ACCEPTED);
        assertThat(row.getAcceptedAt()).isNotNull();
        assertThat(attempts.findByOutboxIdOrderByAttemptNoAsc(row.getId())).extracting(DeliveryAttempt::getOutcome)
                .containsExactly("ACCEPTED");
        ArgumentCaptor<NotificationServiceClient.EmailRequest> sent = ArgumentCaptor.forClass(NotificationServiceClient.EmailRequest.class);
        verify(client).sendEmail(sent.capture(), any());
        assertThat(sent.getValue().to()).containsExactly(row.getRecipientEmail());
        assertThat(sent.getValue().idempotencyKey()).isEqualTo(row.getIdempotencyKey());
        assertThat(sent.getValue().module()).isEqualTo("helpdesk");
    }

    @Test
    void aTimeoutLeavesTheTicketCommittedAndSchedulesARetry() throws Exception {
        subscribe(user("admin"), "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        when(client.sendEmail(any(), any())).thenReturn(DeliveryOutcome.retryable(null, "No response: SocketTimeoutException", null));

        dispatcher.dispatchDue();

        NotificationOutbox row = rows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(row.getLastError()).contains("SocketTimeoutException");
        // The ticket itself is untouched by the failure.
        mvc.perform(get("/api/helpdesk/tickets/{id}", ticketId).header("Authorization", bearer(user("admin2"), "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ticketId));
    }

    @Test
    void serverErrorsAreRetriedWithGrowingBackoffUntilTheyAreAccepted() throws Exception {
        subscribe(user("admin"), "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        when(client.sendEmail(any(), any()))
                .thenReturn(DeliveryOutcome.retryable(503, "HTTP 503", null))
                .thenReturn(DeliveryOutcome.retryable(502, "SMTP down", null))
                .thenReturn(DeliveryOutcome.accepted(200, "SENT"));

        dispatcher.dispatchDue();
        NotificationOutbox row = rows(ticketId, NotificationEventType.TICKET_CREATED).get(0);
        Instant firstRetryAt = row.getNextAttemptAt();
        assertThat(dispatcher.dispatchDue()).as("not due yet").isZero();

        makeDue(row);
        dispatcher.dispatchDue();
        Duration secondDelay = Duration.between(Instant.now(), row.getNextAttemptAt());
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(secondDelay).as("backoff grows").isGreaterThan(Duration.between(Instant.now(), firstRetryAt));

        makeDue(row);
        dispatcher.dispatchDue();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.ACCEPTED);
        assertThat(attempts.findByOutboxIdOrderByAttemptNoAsc(row.getId())).extracting(DeliveryAttempt::getOutcome)
                .containsExactly("RETRY_SCHEDULED", "RETRY_SCHEDULED", "ACCEPTED");
    }

    @Test
    void retriesAreBoundedAndAFailedMessageCanBeRetriedSafely() throws Exception {
        subscribe(user("admin"), "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        when(client.sendEmail(any(), any())).thenReturn(DeliveryOutcome.retryable(503, "HTTP 503", null));
        NotificationOutbox row = rows(ticketId, NotificationEventType.TICKET_CREATED).get(0);

        for (int i = 0; i < 3; i++) {
            makeDue(row);
            dispatcher.dispatchDue();
        }
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(3);
        makeDue(row);
        assertThat(dispatcher.dispatchDue()).as("a FAILED row is not picked up again").isZero();

        // An admin retries it once the notification-service is healthy again.
        when(client.sendEmail(any(), any())).thenReturn(DeliveryOutcome.accepted(200, "SENT"));
        mvc.perform(post("/api/helpdesk/admin/notification-outbox/{id}/retry", row.getId())
                        .header("Authorization", bearer(user("a"), "ADMIN")))
                .andExpect(status().isAccepted());
        outbox.flush();
        dispatcher.dispatchDue();

        assertThat(rows(ticketId, NotificationEventType.TICKET_CREATED)).as("same row, no duplicate").hasSize(1);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.ACCEPTED);
        ArgumentCaptor<NotificationServiceClient.EmailRequest> sent = ArgumentCaptor.forClass(NotificationServiceClient.EmailRequest.class);
        verify(client, org.mockito.Mockito.times(4)).sendEmail(sent.capture(), any());
        assertThat(sent.getAllValues()).extracting(NotificationServiceClient.EmailRequest::idempotencyKey)
                .containsOnly(row.getIdempotencyKey());
    }

    @Test
    void authenticationAndValidationFailuresAreNotRetried() throws Exception {
        subscribe(user("admin"), "TICKET_CREATED");
        subscribe(user("hr"), "TICKET_CREATED");
        long ticketId = createTicket(user("req"), "REQUESTER");
        when(client.sendEmail(any(), any()))
                .thenReturn(DeliveryOutcome.permanent(403, "Authentication rejected (HTTP 403)"))
                .thenReturn(DeliveryOutcome.permanent(400, "A subject is required."));

        dispatcher.dispatchDue();

        List<NotificationOutbox> rows = rows(ticketId, NotificationEventType.TICKET_CREATED);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(row.getAttempts()).isEqualTo(1);
        });
        assertThat(rows).extracting(NotificationOutbox::getLastError)
                .containsExactlyInAnyOrder("Authentication rejected (HTTP 403)", "A subject is required.");
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void failedMessagesAreListedForAdminsAndHiddenFromEveryoneElse() throws Exception {
        subscribe(user("admin"), "TICKET_CREATED");
        createTicket(user("req"), "REQUESTER");
        when(client.sendEmail(any(), any())).thenReturn(DeliveryOutcome.permanent(403, "Authentication rejected (HTTP 403)"));
        dispatcher.dispatchDue();

        mvc.perform(get("/api/helpdesk/admin/notification-outbox").header("Authorization", bearer(user("a"), "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].lastError").value("Authentication rejected (HTTP 403)"));
        mvc.perform(get("/api/helpdesk/admin/notification-outbox").header("Authorization", bearer(user("g"), "AGENT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(user("g"), "AGENT"))
                        .contentType("application/json").content("{\"userId\": 1, \"eventType\": \"TICKET_CREATED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void recipientsCanBeAddedAndRemovedAndAssignmentCannotBeConfigured() throws Exception {
        String admin = user("admin");
        long userId = me(user("hr"), "REQUESTER");

        String created = mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + userId + ", \"eventType\": \"TICKET_RESOLVED\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.departmentId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + userId + ", \"eventType\": \"TICKET_RESOLVED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + userId + ", \"eventType\": \"TICKET_ASSIGNED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/helpdesk/admin/notification-recipients/{id}", json.readTree(created).get("id").asLong())
                        .header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void emailsAreRenderedFromTheSeededTemplatesAndAnAdminEditChangesThem() throws Exception {
        String admin = user("admin");
        subscribe(user("hr"), "TICKET_CREATED");

        long first = createTicket(user("req"), "REQUESTER");
        NotificationOutbox seeded = rows(first, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(seeded.getSubject()).startsWith("New ticket ").endsWith(" - Test ticket");
        assertThat(seeded.getBodyHtml()).contains("<h2 style=").contains("Open ticket</a>");

        String listed = mvc.perform(get("/api/helpdesk/admin/notification-templates").header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long templateId = 0;
        for (com.fasterxml.jackson.databind.JsonNode node : json.readTree(listed)) {
            if ("EMAIL_TICKET_CREATED".equals(node.get("eventType").asText())) {
                templateId = node.get("id").asLong();
            }
        }
        assertThat(templateId).as("EMAIL_TICKET_CREATED is seeded").isPositive();
        mvc.perform(put("/api/helpdesk/admin/notification-templates/{id}", templateId).header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"subjectTemplate\": \"[{{department}}] {{priority}}: {{title}}\", "
                                + "\"bodyTemplate\": \"<p>{{creatorName}} raised {{ticketNumber}}</p>\"}"))
                .andExpect(status().isOk());

        long second = createTicket(user("req2"), "REQUESTER");
        NotificationOutbox edited = rows(second, NotificationEventType.TICKET_CREATED).get(0);
        assertThat(edited.getSubject()).isEqualTo("[General Support] P3: Test ticket");
        assertThat(edited.getBodyHtml()).startsWith("<p>req2-").contains(" raised ");
        assertThat(edited.getBodyText()).contains("Open ticket: https://helpdesk.example.test/helpdesk/tickets/" + second);
    }

    @Test
    void anAssignmentEmailUsesItsSeededTemplate() throws Exception {
        String admin = user("admin");
        String handler = staff("handler");
        long ticketId = createTicket(user("req"), "REQUESTER");

        assign(admin, ticketId, handler);

        NotificationOutbox row = rows(ticketId, NotificationEventType.TICKET_ASSIGNED).get(0);
        assertThat(row.getSubject()).startsWith("Ticket assigned to you: ");
        assertThat(row.getBodyHtml()).contains("Assigned by").contains(admin).contains(handler);
    }

    // ------------------------------------------------------------------ helpers

    private List<NotificationOutbox> rows(long ticketId, NotificationEventType type) {
        outbox.flush();
        return outbox.findByTicketIdOrderByIdAsc(ticketId).stream().filter(r -> r.getEventType() == type).toList();
    }

    /** Pulls the row's retry time into the past so the next dispatch picks it up. */
    private void makeDue(NotificationOutbox row) {
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        row.setLeaseUntil(null);
        outbox.saveAndFlush(row);
    }

    private void subscribe(String login, String eventType) throws Exception {
        long id = me(login, "REQUESTER");
        mvc.perform(post("/api/helpdesk/admin/notification-recipients").header("Authorization", bearer(user("cfg-admin"), "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + id + ", \"eventType\": \"" + eventType + "\"}"))
                .andExpect(status().isCreated());
    }

    private void assign(String adminLogin, long ticketId, String handlerLogin) throws Exception {
        update(adminLogin, "ADMIN", ticketId, "{\"assigneeId\": " + me(handlerLogin, "AGENT") + "}").andExpect(status().isOk());
    }

    private long createTicket(String login, String role) throws Exception {
        String res = mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(login, role))
                        .contentType("application/json")
                        .content("{\"typeId\": " + INCIDENT + ", \"title\": \"Test ticket\", \"description\": \"desc\", \"priority\": \"P3\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private ResultActions update(String login, String role, long id, String body) throws Exception {
        return mvc.perform(put("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(login, role))
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

    @SuppressWarnings("unused")
    private static Set<Long> ids(List<NotificationOutbox> rows) {
        return rows.stream().map(NotificationOutbox::getRecipientUserId).collect(Collectors.toSet());
    }
}
