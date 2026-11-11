package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.TicketEmailComposer;
import com.erp.ticketing.ticket.model.Confidentiality;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.user.model.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Fills the three approved Meta templates. The ORDER of each list is the contract with the template: element
 * {@code i} fills {@code {{i+1}}}. The templates' headers are static text, so no header parameters are sent.
 *
 * <pre>
 * ticket_created   {{1}} requester  {{2}} ticket no.  {{3}} subject  {{4}} priority  {{5}} created
 * ticket_assigned  {{1}} assignee   {{2}} ticket no.  {{3}} subject  {{4}} description  {{5}} priority
 *                  {{6}} assigned by  {{7}} due date
 * ticket_reminder  {{1}} assignee   {{2}} ticket no.  {{3}} subject  {{4}} status  {{5}} priority
 *                  {{6}} due date  {{7}} reminder type
 * </pre>
 *
 * Values are made safe for Meta (no line breaks or tabs, no runs of spaces, never empty, bounded length).
 * Tickets that are not STANDARD confidentiality never have their title or description put in a message,
 * exactly as for email.
 */
@Component
public class WhatsAppTemplateComposer {

    /** A dynamic URL-button suffix: {@code index} is the button's position in the approved template. */
    public record UrlButton(int index, String text) {
    }

    /** A filled template, ready to store on an outbox row. */
    public record TemplateMessage(String templateName, String languageCode, List<String> body, List<UrlButton> buttons) {
    }

    /** What is stored in {@code notification_outbox.template_params}. */
    public record StoredParams(List<String> body, List<UrlButton> buttons) {
    }

    static final String NOT_SET = "Not set";
    static final String AUTO_ASSIGNED_BY = "Auto-assignment";
    private static final int NAME_LIMIT = 100;
    private static final int TITLE_LIMIT = 200;
    private static final int DESCRIPTION_LIMIT = 300;
    private static final String DATE_PATTERN = "d MMM yyyy, h:mm a";

    private final WhatsAppProperties properties;
    private final ObjectMapper mapper;

    public WhatsAppTemplateComposer(WhatsAppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public TemplateMessage ticketCreated(Ticket ticket) {
        boolean restricted = restricted(ticket);
        return message(properties.getTicketCreated(), ticket, List.of(
                name(ticket.getRequester()),
                text(ticket.getTicketNumber(), NAME_LIMIT),
                restricted ? TicketEmailComposer.RESTRICTED_TITLE : text(ticket.getTitle(), TITLE_LIMIT),
                priority(ticket.getPriority()),
                date(ticket.getCreatedAt())));
    }

    /** @param assignedBy who made the assignment; null when routing or automation did it */
    public TemplateMessage ticketAssigned(Ticket ticket, User assignee, User assignedBy) {
        boolean restricted = restricted(ticket);
        return message(properties.getTicketAssigned(), ticket, List.of(
                name(assignee),
                text(ticket.getTicketNumber(), NAME_LIMIT),
                restricted ? TicketEmailComposer.RESTRICTED_TITLE : text(ticket.getTitle(), TITLE_LIMIT),
                restricted ? TicketEmailComposer.RESTRICTED_TEXT : text(ticket.getDescription(), DESCRIPTION_LIMIT),
                priority(ticket.getPriority()),
                assignedBy == null ? AUTO_ASSIGNED_BY : name(assignedBy),
                dueDate(ticket.getResolutionDue())));
    }

    public TemplateMessage ticketReminder(Ticket ticket, User assignee, ReminderType type) {
        boolean restricted = restricted(ticket);
        return message(properties.getTicketReminder(), ticket, List.of(
                name(assignee),
                text(ticket.getTicketNumber(), NAME_LIMIT),
                restricted ? TicketEmailComposer.RESTRICTED_TITLE : text(ticket.getTitle(), TITLE_LIMIT),
                status(ticket.getStatus()),
                priority(ticket.getPriority()),
                dueDate(ticket.getResolutionDue()),
                type.label()));
    }

    public String toJson(TemplateMessage message) {
        try {
            return mapper.writeValueAsString(new StoredParams(message.body(), message.buttons()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialise WhatsApp template parameters", ex);
        }
    }

    public StoredParams fromJson(String json) {
        try {
            StoredParams params = mapper.readValue(json, StoredParams.class);
            return new StoredParams(params.body() == null ? List.of() : params.body(),
                    params.buttons() == null ? List.of() : params.buttons());
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalStateException("Stored WhatsApp template parameters are unreadable", ex);
        }
    }

    // ------------------------------------------------------------------ values

    private TemplateMessage message(WhatsAppProperties.Template template, Ticket ticket, List<String> body) {
        List<UrlButton> buttons = template.getUrlButtonIndex() == null ? List.of()
                : List.of(new UrlButton(template.getUrlButtonIndex(), String.valueOf(ticket.getId())));
        return new TemplateMessage(template.getName(), properties.getLanguageCode(), body, buttons);
    }

    private static boolean restricted(Ticket ticket) {
        return ticket.getConfidentiality() != Confidentiality.STANDARD;
    }

    /** Matches the labels the web app shows next to P1-P4. */
    static String priority(TicketPriority priority) {
        if (priority == null) {
            return "-";
        }
        return switch (priority) {
            case P1 -> "Critical";
            case P2 -> "High";
            case P3 -> "Medium";
            case P4 -> "Low";
        };
    }

    /** IN_PROGRESS -> "In Progress". */
    static String status(TicketStatus status) {
        if (status == null) {
            return "-";
        }
        return Arrays.stream(status.name().split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining(" "));
    }

    private static String name(User user) {
        return user == null ? "-" : text(user.getName(), NAME_LIMIT);
    }

    private String dueDate(Instant due) {
        return due == null ? NOT_SET : date(due);
    }

    String date(Instant instant) {
        if (instant == null) {
            return "-";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_PATTERN, Locale.ENGLISH)
                .withZone(properties.getDisplayTimeZone());
        String formatted = formatter.format(instant);
        return ZoneOffset.UTC.normalized().equals(properties.getDisplayTimeZone().normalized())
                ? formatted + " UTC" : formatted;
    }

    /** One line, no runs of spaces (Meta rejects new lines, tabs and 4+ spaces), never empty, bounded. */
    static String text(String value, int max) {
        if (value == null) {
            return "-";
        }
        String text = value.replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return "-";
        }
        return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…";
    }
}
