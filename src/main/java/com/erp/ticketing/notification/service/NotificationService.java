package com.erp.ticketing.notification.service;

import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.notification.dto.NotificationResponse;
import com.erp.ticketing.notification.model.Notification;
import com.erp.ticketing.notification.model.NotificationTemplate;
import com.erp.ticketing.notification.model.NotificationType;
import com.erp.ticketing.notification.repository.NotificationRepository;
import com.erp.ticketing.notification.repository.NotificationTemplateRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists notifications and (optionally) attempts email delivery through
 * notifications-service, via {@link IntegrationEmailClient}. Delivery failures
 * must never break the ticket operation that triggered them, so they are
 * logged and recorded as unsent rather than propagated.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository notificationTemplateRepository;
    private final IntegrationEmailClient emailClient;

    @Value("${app.notifications.email-enabled:false}")
    private boolean emailEnabled;

    public NotificationService(NotificationRepository notificationRepository,
                                NotificationTemplateRepository notificationTemplateRepository,
                                IntegrationEmailClient emailClient) {
        this.notificationRepository = notificationRepository;
        this.notificationTemplateRepository = notificationTemplateRepository;
        this.emailClient = emailClient;
    }

    /**
     * Renders subject/body from the admin-configurable {@link NotificationTemplate}
     * for {@code eventType} (falling back to a hardcoded default - with a warning
     * logged - if no template row exists for it, so a missing/misconfigured
     * template never breaks notification delivery), then delegates to the
     * plain-string overload above.
     */
    @Transactional
    public void notify(User recipient, Ticket ticket, String eventType, Map<String, Object> placeholders) {
        Optional<NotificationTemplate> template = notificationTemplateRepository.findByEventType(eventType);
        String subject;
        String body;
        if (template.isPresent()) {
            subject = render(template.get().getSubjectTemplate(), placeholders);
            body = render(template.get().getBodyTemplate(), placeholders);
        } else {
            log.warn("No notification template configured for eventType={} - using hardcoded fallback wording", eventType);
            subject = fallbackSubject(eventType, placeholders);
            body = fallbackBody(eventType, placeholders);
        }
        notify(recipient, ticket, eventType, subject, body);
    }

    /**
     * Replaces every {@code {{key}}} token in {@code template} with
     * {@code String.valueOf(placeholders.get(key))}; a key missing from
     * {@code placeholders} (or a null map) leaves the token as-is rather than
     * throwing.
     */
    private String render(String template, Map<String, Object> placeholders) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = placeholders != null ? placeholders.get(key) : null;
            String replacement = value != null ? String.valueOf(value) : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String fallbackSubject(String eventType, Map<String, Object> placeholders) {
        String ticketNumber = placeholderOrBlank(placeholders, "ticketNumber");
        return switch (eventType) {
            case "TICKET_CREATED" -> "New ticket for your department: " + ticketNumber;
            case "TICKET_ASSIGNED" -> "Ticket assigned to you: " + ticketNumber;
            case "TICKET_RESOLVED" -> "Your ticket has been resolved: " + ticketNumber;
            case "SLA_WARNING" -> "SLA at risk: " + ticketNumber;
            case "SLA_BREACHED" -> "SLA breached: " + ticketNumber;
            case "CSAT_REQUEST" -> "How did we do? Rate ticket " + ticketNumber;
            default -> "Notification: " + ticketNumber;
        };
    }

    private String fallbackBody(String eventType, Map<String, Object> placeholders) {
        String ticketNumber = placeholderOrBlank(placeholders, "ticketNumber");
        String title = placeholderOrBlank(placeholders, "title");
        return switch (eventType) {
            case "TICKET_CREATED" -> "Ticket " + ticketNumber + " (" + title + ") has been routed to your department.";
            case "TICKET_ASSIGNED" -> "Ticket " + ticketNumber + " (" + title + ") has been assigned to you.";
            case "TICKET_RESOLVED" -> "Ticket " + ticketNumber + " (" + title + ") has been marked resolved.";
            case "SLA_WARNING" -> "Ticket " + ticketNumber + " (" + title + ") is approaching its resolution SLA due date.";
            case "SLA_BREACHED" -> "Ticket " + ticketNumber + " (" + title + ") has breached its resolution SLA.";
            case "CSAT_REQUEST" -> "Ticket " + ticketNumber + " (" + title + ") has been resolved. Please rate your experience.";
            default -> "Ticket " + ticketNumber + " (" + title + ")";
        };
    }

    private String placeholderOrBlank(Map<String, Object> placeholders, String key) {
        Object value = placeholders != null ? placeholders.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    @Transactional
    public void notify(User recipient, Ticket ticket, String eventType, String subject, String body) {
        if (recipient == null) {
            log.warn("Skipping notification eventType={} ticket={} - no recipient resolved",
                    eventType, ticket != null ? ticket.getTicketNumber() : null);
            return;
        }

        Notification inApp = new Notification();
        inApp.setUser(recipient);
        inApp.setTicket(ticket);
        inApp.setType(NotificationType.IN_APP);
        inApp.setEventType(eventType);
        inApp.setSubject(subject);
        inApp.setBody(body);
        notificationRepository.save(inApp);

        if (!emailEnabled) {
            return;
        }

        // Tracked as an independent row from the IN_APP one above, per the data
        // model - in-app and email delivery are recorded separately.
        Notification emailNotification = new Notification();
        emailNotification.setUser(recipient);
        emailNotification.setTicket(ticket);
        emailNotification.setType(NotificationType.EMAIL);
        emailNotification.setEventType(eventType);
        emailNotification.setSubject(subject);
        emailNotification.setBody(body);

        // Saved first so its id can serve as the idempotency key: a retried relay
        // hop for the same row is then delivered once.
        emailNotification = notificationRepository.save(emailNotification);
        if (attemptEmailSend(recipient, eventType, subject, body, emailNotification.getId())) {
            emailNotification.setSent(true);
            emailNotification.setSentAt(Instant.now());
            notificationRepository.save(emailNotification);
        }
    }

    private boolean attemptEmailSend(User recipient, String eventType, String subject, String body, Long notificationId) {
        if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            log.warn("Cannot email user {} - no email address on file", recipient.getId());
            return false;
        }
        return emailClient.send(
                recipient.getEmail(),
                subject,
                body != null && !body.isBlank() ? body : subject,
                eventType,
                "helpdesk-notification-" + notificationId);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(Long userId, boolean unreadOnly) {
        List<Notification> notifications = unreadOnly
                ? notificationRepository.findByUser_IdAndReadFalseOrderByCreatedAtDesc(userId)
                : notificationRepository.findByUser_IdOrderByCreatedAtDesc(userId);
        return notifications.stream().map(this::toResponse).toList();
    }

    @Transactional
    public NotificationResponse markRead(Long id, Long userId) {
        Notification notification = notificationRepository.findByIdAndUser_Id(id, userId)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        notification.setRead(true);
        notification = notificationRepository.save(notification);
        return toResponse(notification);
    }

    private NotificationResponse toResponse(Notification notification) {
        Ticket ticket = notification.getTicket();
        return new NotificationResponse(
                notification.getId(),
                ticket != null ? ticket.getId() : null,
                notification.getType().name(),
                notification.getEventType(),
                notification.getSubject(),
                notification.getBody(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
