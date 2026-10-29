package com.erp.ticketing.integration.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the hand-over to the external notification-service ({@code app.notification-service.*}).
 * The notification-service owns SMTP credentials and delivery; this service only needs where it lives and
 * the shared internal-service secret.
 */
@ConfigurationProperties(prefix = "app.notification-service")
public class NotificationServiceProperties {

    /** Off by default: nothing is recorded or sent until the notification-service is configured. */
    private boolean enabled = false;
    private String baseUrl = "http://localhost:8092";
    /** Sent as X-Internal-Service-Secret. Never logged. */
    private String internalSecret = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(15);
    /** Origin of the web app, used to build the link to a ticket. */
    private String appBaseUrl = "http://localhost:5173";
    /** Reported to the notification-service in the "module" field of every request. */
    private String module = "helpdesk";
    private int maxAttempts = 6;
    private Duration retryBaseDelay = Duration.ofSeconds(30);
    private Duration retryMaxDelay = Duration.ofMinutes(15);
    private int batchSize = 25;
    /** How long a claimed row is hidden from other dispatchers while it is being sent. */
    private Duration lease = Duration.ofMinutes(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getInternalSecret() { return internalSecret; }
    public void setInternalSecret(String internalSecret) { this.internalSecret = internalSecret; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public String getAppBaseUrl() { return appBaseUrl; }
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
    public Duration getRetryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration retryMaxDelay) { this.retryMaxDelay = retryMaxDelay; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
}
