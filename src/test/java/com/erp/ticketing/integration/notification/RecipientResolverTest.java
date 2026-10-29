package com.erp.ticketing.integration.notification;

import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.integration.notification.RecipientResolver.Recipient;
import com.erp.ticketing.integration.notification.model.TicketNotificationRecipient;
import com.erp.ticketing.integration.notification.repository.TicketNotificationRecipientRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecipientResolverTest {

    private TicketNotificationRecipientRepository subscriptions;
    private RecipientResolver resolver;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        subscriptions = mock(TicketNotificationRecipientRepository.class);
        resolver = new RecipientResolver(subscriptions);
        ticket = new Ticket();
        Department department = new Department();
        department.setId(7L);
        ticket.setDepartment(department);
    }

    private static User user(long id, String email) {
        User user = new User();
        user.setId(id);
        user.setName("User " + id);
        user.setEmail(email);
        user.setActive(true);
        return user;
    }

    private static TicketNotificationRecipient subscription(User user) {
        TicketNotificationRecipient row = new TicketNotificationRecipient();
        row.setUser(user);
        return row;
    }

    private void subscribe(NotificationEventType type, User... users) {
        when(subscriptions.findActiveFor(eq(type), any())).thenReturn(
                java.util.Arrays.stream(users).map(RecipientResolverTest::subscription).toList());
    }

    @Test
    void creationGoesToTheConfiguredSubscribersOnly() {
        subscribe(NotificationEventType.TICKET_CREATED, user(1, "hr@example.com"), user(2, "admin@example.com"));
        ticket.setCreator(user(3, "creator@example.com"));
        ticket.setAssignee(user(4, "handler@example.com"));

        List<Recipient> result = resolver.resolve(NotificationEventType.TICKET_CREATED, ticket, null);

        assertThat(result).extracting(Recipient::userId).containsExactly(1L, 2L);
    }

    @Test
    void resolutionReachesSubscribersCreatorRequesterAndHandler() {
        subscribe(NotificationEventType.TICKET_RESOLVED, user(1, "hr@example.com"), user(2, "admin@example.com"));
        ticket.setCreator(user(3, "creator@example.com"));
        ticket.setRequester(user(5, "requester@example.com"));
        ticket.setAssignee(user(4, "handler@example.com"));

        List<Recipient> result = resolver.resolve(NotificationEventType.TICKET_RESOLVED, ticket, null);

        assertThat(result).extracting(Recipient::userId).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void aPersonInSeveralRolesIsNotifiedOnce() {
        User admin = user(2, "admin@example.com");
        subscribe(NotificationEventType.TICKET_RESOLVED, admin, user(1, "hr@example.com"));
        ticket.setCreator(admin);
        ticket.setRequester(admin);
        ticket.setAssignee(admin);

        List<Recipient> result = resolver.resolve(NotificationEventType.TICKET_RESOLVED, ticket, null);

        assertThat(result).extracting(Recipient::userId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void twoAccountsSharingAnAddressAreNotifiedOnce() {
        subscribe(NotificationEventType.TICKET_RESOLVED, user(1, "Shared@Example.com"));
        ticket.setCreator(user(2, " shared@example.com "));

        List<Recipient> result = resolver.resolve(NotificationEventType.TICKET_RESOLVED, ticket, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("shared@example.com");
    }

    @Test
    void peopleWithoutAUsableAddressOrAnInactiveAccountAreSkipped() {
        User inactive = user(3, "gone@example.com");
        inactive.setActive(false);
        subscribe(NotificationEventType.TICKET_RESOLVED, user(1, null), user(2, "   "), inactive, user(4, "not-an-address"));
        ticket.setCreator(user(5, "ok@example.com"));

        List<Recipient> result = resolver.resolve(NotificationEventType.TICKET_RESOLVED, ticket, null);

        assertThat(result).extracting(Recipient::userId).containsExactly(5L);
    }

    @Test
    void assignmentGoesToTheNewHandlerOnly() {
        subscribe(NotificationEventType.TICKET_CREATED, user(1, "hr@example.com"));
        ticket.setCreator(user(3, "creator@example.com"));

        List<Recipient> result = resolver.resolve(NotificationEventType.TICKET_ASSIGNED, ticket, user(9, "handler@example.com"));

        assertThat(result).extracting(Recipient::userId).containsExactly(9L);
    }

    @Test
    void normalizeEmailAcceptsOrdinaryAddressesAndRejectsJunk() {
        assertThat(RecipientResolver.normalizeEmail("  A.B@Example.COM ")).isEqualTo("a.b@example.com");
        assertThat(RecipientResolver.normalizeEmail("a@b")).isNull();
        assertThat(RecipientResolver.normalizeEmail("@example.com")).isNull();
        assertThat(RecipientResolver.normalizeEmail("a b@example.com")).isNull();
        assertThat(RecipientResolver.normalizeEmail("a@@example.com")).isNull();
        assertThat(RecipientResolver.normalizeEmail(null)).isNull();
    }
}
