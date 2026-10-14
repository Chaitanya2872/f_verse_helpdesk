package com.erp.ticketing.integration.notification;

import com.erp.ticketing.notification.model.NotificationTemplate;
import com.erp.ticketing.notification.repository.NotificationTemplateRepository;
import com.erp.ticketing.ticket.model.Confidentiality;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Builds the subject and body of a ticket email from the admin-editable templates
 * {@code EMAIL_TICKET_CREATED}, {@code EMAIL_TICKET_ASSIGNED} and {@code EMAIL_TICKET_RESOLVED} (seeded by
 * V23, edited under Admin &gt; Notification Templates). The body is HTML; {@code {{placeholder}}} values are
 * HTML-escaped, so ticket text can never inject markup. If a template is missing or fails to render, the
 * built-in wording below is used so a mail still goes out.
 *
 * <p>Tickets that are not STANDARD confidentiality never have their title, description or resolution notes put in
 * an email, whatever the template says: the values are replaced with a neutral note before rendering.
 */
@Component
public class TicketEmailComposer {

    private static final Logger log = LoggerFactory.getLogger(TicketEmailComposer.class);

    private static final int SUMMARY_LIMIT = 500;
    private static final int SUBJECT_LIMIT = 250;
    private static final String RESTRICTED_TITLE = "(restricted ticket)";
    private static final String RESTRICTED_TEXT = "(not shown for restricted tickets)";
    private static final String TEMPLATE_PREFIX = "EMAIL_";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private final NotificationServiceProperties properties;
    private final NotificationTemplateRepository templates;

    public TicketEmailComposer(NotificationServiceProperties properties, NotificationTemplateRepository templates) {
        this.properties = properties;
        this.templates = templates;
    }

    /** Subject and bodies, ready to store on an outbox row. */
    public record Message(String subject, String html, String text) {
    }

    /** What the event adds to the common ticket details. */
    public record Extras(User assignee, User actor, Instant at) {
        public static Extras none() {
            return new Extras(null, null, null);
        }
    }

    public String ticketLink(Ticket ticket) {
        String base = properties.getAppBaseUrl() == null ? "" : properties.getAppBaseUrl().replaceAll("/+$", "");
        return base + "/helpdesk/tickets/" + ticket.getId();
    }

    public Message compose(NotificationEventType type, Ticket ticket, Extras extras) {
        Map<String, String> variables = variables(type, ticket, extras);
        Optional<NotificationTemplate> template = templates.findByEventType(TEMPLATE_PREFIX + type.name());
        if (template.isPresent()) {
            try {
                String html = render(template.get().getBodyTemplate(), variables, true);
                String subject = truncate(render(template.get().getSubjectTemplate(), variables, false)
                        .replaceAll("[\\r\\n]+", " ").strip(), SUBJECT_LIMIT);
                if (subject.isBlank() || html.isBlank()) {
                    throw new IllegalStateException("Template renders an empty subject or body");
                }
                return new Message(subject, html, toText(html, variables.get("ticketLink")));
            } catch (RuntimeException ex) {
                log.error("Email template could not be rendered; using the built-in wording",
                        kv("template", TEMPLATE_PREFIX + type.name()), ex);
            }
        } else {
            log.warn("No email template found; using the built-in wording", kv("template", TEMPLATE_PREFIX + type.name()));
        }
        return composeBuiltIn(type, ticket, extras, variables);
    }

    // ------------------------------------------------------------------ template rendering

    /**
     * Replaces {@code {{key}}} tokens. HTML bodies get escaped values (newlines become line breaks); subjects get
     * the raw text. A token that is not a known placeholder renders as nothing.
     */
    static String render(String template, Map<String, String> variables, boolean html) {
        Matcher matcher = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), "");
            String replacement = html ? escape(value).replace("\n", "<br>") : value;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Plain-text alternative derived from the rendered HTML, with the ticket link spelled out. */
    static String toText(String html, String link) {
        String text = html
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(tr|p|h[1-6]|div)>", "\n")
                .replaceAll("(?i)</t[dh]>", "  ")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return text + "\n\nOpen ticket: " + link + "\n";
    }

    // ------------------------------------------------------------------ placeholder values

    private Map<String, String> variables(NotificationEventType type, Ticket ticket, Extras extras) {
        boolean restricted = ticket.getConfidentiality() != Confidentiality.STANDARD;
        User handler = type == NotificationEventType.TICKET_ASSIGNED ? extras.assignee() : ticket.getAssignee();
        Instant resolvedAt = ticket.getResolvedAt() != null ? ticket.getResolvedAt() : extras.at();

        Map<String, String> v = new LinkedHashMap<>();
        v.put("ticketNumber", ticket.getTicketNumber());
        v.put("title", restricted ? RESTRICTED_TITLE : orDash(ticket.getTitle()));
        v.put("description", restricted ? RESTRICTED_TEXT : truncate(orDash(ticket.getDescription()), SUMMARY_LIMIT));
        v.put("priority", ticket.getPriority().name());
        v.put("status", ticket.getStatus().name().replace('_', ' '));
        v.put("department", ticket.getDepartment() != null ? orDash(ticket.getDepartment().getDepartmentName()) : "-");
        v.put("creatorName", nameOf(ticket.getCreator()));
        v.put("requesterName", nameOf(ticket.getRequester()));
        v.put("handlerName", nameOf(handler));
        v.put("createdAt", format(ticket.getCreatedAt()));
        v.put("assignedBy", nameOf(extras.actor()));
        v.put("assignedAt", format(type == NotificationEventType.TICKET_ASSIGNED ? extras.at() : null));
        v.put("resolutionDue", format(ticket.getResolutionDue()));
        v.put("firstResponseDue", ticket.getFirstResponseAt() == null ? format(ticket.getFirstResponseDue()) : "-");
        v.put("resolvedAt", format(type == NotificationEventType.TICKET_RESOLVED ? resolvedAt : ticket.getResolvedAt()));
        v.put("resolvedBy", nameOf(ticket.getResolvedBy()));
        v.put("resolutionCode", restricted || ticket.getResolutionCode() == null ? "-"
                : ticket.getResolutionCode().replace('_', ' ').toLowerCase(Locale.ENGLISH));
        v.put("resolutionNotes", restricted ? RESTRICTED_TEXT : truncate(orDash(ticket.getResolutionSummary()), SUMMARY_LIMIT));
        v.put("ticketLink", ticketLink(ticket));
        return v;
    }

    // ------------------------------------------------------------------ built-in fallback

    private Message composeBuiltIn(NotificationEventType type, Ticket ticket, Extras extras, Map<String, String> v) {
        String headline = switch (type) {
            case TICKET_CREATED -> "New ticket " + v.get("ticketNumber");
            case TICKET_ASSIGNED -> "Ticket assigned to you: " + v.get("ticketNumber");
            case TICKET_RESOLVED -> "Ticket resolved: " + v.get("ticketNumber");
        };
        String subject = truncate(headline + " - " + v.get("title"), SUBJECT_LIMIT);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Ticket", v.get("ticketNumber") + " - " + v.get("title")});
        rows.add(new String[] {"Description", v.get("description")});
        switch (type) {
            case TICKET_CREATED -> {
                rows.add(new String[] {"Created by", v.get("creatorName")});
                rows.add(new String[] {"Created", v.get("createdAt")});
            }
            case TICKET_ASSIGNED -> {
                rows.add(new String[] {"Assigned to", v.get("handlerName")});
                rows.add(new String[] {"Assigned by", v.get("assignedBy")});
                rows.add(new String[] {"Assigned", v.get("assignedAt")});
                rows.add(new String[] {"Resolution due", v.get("resolutionDue")});
            }
            case TICKET_RESOLVED -> {
                rows.add(new String[] {"Created by", v.get("creatorName")});
                rows.add(new String[] {"Handled by", v.get("handlerName")});
                rows.add(new String[] {"Resolved", v.get("resolvedAt")});
                rows.add(new String[] {"Resolution notes", v.get("resolutionNotes")});
            }
        }
        rows.add(new String[] {"Department", v.get("department")});
        rows.add(new String[] {"Priority", v.get("priority")});
        rows.add(new String[] {"Status", v.get("status")});

        StringBuilder html = new StringBuilder("<div style=\"font-family:Arial,sans-serif;font-size:14px\"><h2>")
                .append(escape(headline)).append("</h2><table>");
        StringBuilder text = new StringBuilder(headline).append("\n\n");
        for (String[] row : rows) {
            html.append("<tr><td style=\"padding:4px 12px 4px 0;color:#64748b\">").append(escape(row[0]))
                    .append("</td><td>").append(escape(row[1]).replace("\n", "<br>")).append("</td></tr>");
            text.append(row[0]).append(": ").append(row[1]).append('\n');
        }
        html.append("</table><p><a href=\"").append(escape(v.get("ticketLink"))).append("\">Open ticket</a></p></div>");
        text.append("\nOpen ticket: ").append(v.get("ticketLink")).append('\n');
        return new Message(subject, html.toString(), text.toString());
    }

    // ------------------------------------------------------------------ helpers

    private static String nameOf(User user) {
        return user == null || user.getName() == null || user.getName().isBlank() ? "-" : user.getName();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    private static String format(Instant instant) {
        return instant == null ? "-" : WHEN.format(instant);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
