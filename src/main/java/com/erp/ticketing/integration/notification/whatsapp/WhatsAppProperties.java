package com.erp.ticketing.integration.notification.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Helpdesk WhatsApp template notifications ({@code app.whatsapp.*}). Meta credentials are NOT here: they live
 * only in the notification-service, which makes the Cloud API call. This service decides who is told what,
 * and in which approved template.
 *
 * <p>Messages are only recorded and sent when both this switch and {@code app.notification-service.enabled}
 * are on, because WhatsApp rows travel through the same outbox and dispatcher as email.
 */
@ConfigurationProperties(prefix = "app.whatsapp")
public class WhatsAppProperties {

    private boolean enabled = false;
    /** Language the three templates were approved in. */
    private String languageCode = "en_US";
    /** Zone used to print dates in messages ("11 Nov 2026, 11:30 AM"). UTC dates are suffixed " UTC". */
    private ZoneId displayTimeZone = ZoneId.of("UTC");
    /**
     * Country calling code (digits, e.g. {@code 91}) applied to stored numbers that have no country code.
     * Blank: such numbers are treated as invalid rather than guessed.
     */
    private String defaultCountryCode = "";

    private final Template ticketCreated = new Template("ticket_created");
    private final Template ticketAssigned = new Template("ticket_assigned");
    private final Template ticketReminder = new Template("ticket_reminder");
    private final Reminders reminders = new Reminders();
    private final Identity identity = new Identity();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }
    public ZoneId getDisplayTimeZone() { return displayTimeZone; }
    public void setDisplayTimeZone(ZoneId displayTimeZone) { this.displayTimeZone = displayTimeZone; }
    public String getDefaultCountryCode() { return defaultCountryCode; }
    public void setDefaultCountryCode(String defaultCountryCode) {
        this.defaultCountryCode = defaultCountryCode == null ? "" : defaultCountryCode.trim().replace("+", "");
    }
    public Template getTicketCreated() { return ticketCreated; }
    public Template getTicketAssigned() { return ticketAssigned; }
    public Template getTicketReminder() { return ticketReminder; }
    public Reminders getReminders() { return reminders; }
    public Identity getIdentity() { return identity; }

    /** One approved Meta template. */
    public static class Template {
        private String name;
        /**
         * Position (0-based) of a URL button with a dynamic suffix, if the APPROVED template has one; its
         * parameter is the ticket id, so the button's URL in Business Manager should end in
         * {@code /helpdesk/tickets/{{1}}}. Leave unset when the template has no such button - nothing is invented.
         */
        private Integer urlButtonIndex;

        public Template(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getUrlButtonIndex() { return urlButtonIndex; }
        public void setUrlButtonIndex(Integer urlButtonIndex) { this.urlButtonIndex = urlButtonIndex; }
    }

    /**
     * Pending-ticket reminders. "SLA Due Soon" uses each ticket's SLA policy warning threshold and "SLA Breached"
     * its resolution due date, exactly as the SLA monitoring job does. The two earlier reminders are optional
     * thresholds, as a percentage of the SLA window elapsed; unset means that reminder is not sent.
     */
    public static class Reminders {
        private boolean enabled = true;
        private Integer firstReminderPercent;
        private Integer secondReminderPercent;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Integer getFirstReminderPercent() { return firstReminderPercent; }
        public void setFirstReminderPercent(Integer firstReminderPercent) { this.firstReminderPercent = firstReminderPercent; }
        public Integer getSecondReminderPercent() { return secondReminderPercent; }
        public void setSecondReminderPercent(Integer secondReminderPercent) { this.secondReminderPercent = secondReminderPercent; }
    }

    /** Where recipients' phone numbers come from: identity-service's internal profile lookup. */
    public static class Identity {
        private String baseUrl = "http://localhost:8081";
        /** Sent as X-Internal-Service-Secret. Never logged. */
        private String internalSecret = "";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getInternalSecret() { return internalSecret; }
        public void setInternalSecret(String internalSecret) { this.internalSecret = internalSecret; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

        @Override
        public String toString() {
            return "Identity{baseUrl=" + baseUrl + ", secretSet=" + !internalSecret.isBlank() + "}";
        }
    }
}
