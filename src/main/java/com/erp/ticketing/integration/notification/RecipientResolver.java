package com.erp.ticketing.integration.notification;

import com.erp.ticketing.integration.notification.model.TicketNotificationRecipient;
import com.erp.ticketing.integration.notification.repository.TicketNotificationRecipientRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Works out who should hear about a ticket event, from the ticket's own relationships and the admin-configured
 * subscriptions - never from hard-coded people.
 *
 * <ul>
 *   <li>created: the configured creation subscribers for the ticket's department</li>
 *   <li>assigned: the new handler only</li>
 *   <li>resolved: the configured resolution subscribers, the ticket's creator and requester, and the handler</li>
 * </ul>
 * Inactive accounts and people without an email address are skipped. Someone who appears more than once
 * (an admin who is also the handler, say) is returned once, keyed by user id and by normalised address.
 */
@Component
public class RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolver.class);

    /** A person to notify, with the address normalised for the channel. */
    public record Recipient(Long userId, String name, String email) {
    }

    private final TicketNotificationRecipientRepository subscriptions;

    public RecipientResolver(TicketNotificationRecipientRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    public List<Recipient> resolve(NotificationEventType type, Ticket ticket, User assignee) {
        List<User> candidates = new ArrayList<>();
        Long departmentId = ticket.getDepartment() != null ? ticket.getDepartment().getId() : null;
        switch (type) {
            case TICKET_CREATED -> candidates.addAll(subscribed(type, departmentId));
            case TICKET_ASSIGNED -> {
                if (assignee != null) {
                    candidates.add(assignee);
                }
            }
            case TICKET_RESOLVED -> {
                candidates.addAll(subscribed(type, departmentId));
                candidates.add(ticket.getCreator());
                candidates.add(ticket.getRequester());
                candidates.add(ticket.getAssignee());
            }
        }
        return dedupe(candidates, ticket, type);
    }

    private List<User> subscribed(NotificationEventType type, Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        return subscriptions.findActiveFor(type, departmentId).stream()
                .map(TicketNotificationRecipient::getUser)
                .toList();
    }

    private List<Recipient> dedupe(List<User> candidates, Ticket ticket, NotificationEventType type) {
        Set<Long> seenUsers = new HashSet<>();
        Set<String> seenAddresses = new HashSet<>();
        List<Recipient> result = new ArrayList<>();
        for (User user : candidates) {
            if (user == null || !user.isActive()) {
                continue;
            }
            String email = normalizeEmail(user.getEmail());
            if (email == null) {
                // Not an error: plenty of accounts have no address on file. Logged without the person's details.
                log.info("Skipping recipient without a usable email address",
                        kv("ticketId", ticket.getId()), kv("event", type), kv("userId", user.getId()));
                continue;
            }
            if (seenUsers.add(user.getId()) && seenAddresses.add(email)) {
                result.add(new Recipient(user.getId(), user.getName(), email));
            }
        }
        return result;
    }

    /** Trimmed, lower-cased, and rejected unless it looks like an address. */
    public static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        boolean plausible = at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1
                && email.indexOf(' ') < 0 && email.indexOf('.', at) > at + 1 && !email.endsWith(".");
        return plausible ? email : null;
    }
}
