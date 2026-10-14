package com.erp.ticketing.integration.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * HTTP client for the notification-service's {@code POST /internal/notifications/email}.
 *
 * <p>Contract (read from the notification-service source, not assumed):
 * <ul>
 *   <li>auth: header {@code X-Internal-Service-Secret}; a wrong or missing secret gives an empty 403</li>
 *   <li>request: {@code module, eventType, to[], subject, bodyText, bodyHtml, idempotencyKey}</li>
 *   <li>200 with {@code sent:true} when SMTP took the message ({@code status:"DEDUPLICATED"} for a repeat key)</li>
 *   <li>502 with {@code sent:false, error} for any failure it reports - provider errors but also its own
 *       validation errors, so a 502 cannot be told apart from a transient fault and is retried a bounded
 *       number of times</li>
 * </ul>
 * An accepted response means the message was handed to the mail provider. It does not prove delivery.
 * Neither the secret nor message content is ever logged.
 */
@Component
public class NotificationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceClient.class);
    private static final String EMAIL_PATH = "/internal/notifications/email";
    private static final String SECRET_HEADER = "X-Internal-Service-Secret";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final int DETAIL_LIMIT = 300;

    /** The fields of the notification-service's SendEmailRequest that this integration uses. */
    public record EmailRequest(String module, String eventType, List<String> to, String subject,
                               String bodyText, String bodyHtml, String idempotencyKey) {
    }

    private record RawResponse(int status, String retryAfter, String body) {
    }

    private final RestClient restClient;
    private final NotificationServiceProperties properties;
    private final ObjectMapper mapper;

    @Autowired
    public NotificationServiceClient(RestClient.Builder builder, NotificationServiceProperties properties,
                                     ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
        this.properties = properties;
        this.mapper = mapper;
    }

    /** For tests: a client around a pre-built {@link RestClient} (for example one bound to a mock server). */
    NotificationServiceClient(RestClient restClient, NotificationServiceProperties properties, ObjectMapper mapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    public DeliveryOutcome sendEmail(EmailRequest request, String correlationId) {
        RawResponse response;
        try {
            response = restClient.post()
                    .uri(EMAIL_PATH)
                    .header(SECRET_HEADER, properties.getInternalSecret())
                    .headers(h -> {
                        if (correlationId != null && !correlationId.isBlank()) {
                            h.set(CORRELATION_HEADER, correlationId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, res) -> new RawResponse(
                            res.getStatusCode().value(),
                            res.getHeaders().getFirst("Retry-After"),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException ex) {
            // Timeouts, refused connections and unreadable responses: the request may or may not have arrived.
            log.warn("Notification-service unreachable", kv("eventType", request.eventType()),
                    kv("error", ex.getClass().getSimpleName()));
            return DeliveryOutcome.retryable(null, "No response: " + ex.getClass().getSimpleName(), null);
        }
        return classify(response, request.eventType());
    }

    private DeliveryOutcome classify(RawResponse response, String eventType) {
        int status = response.status();
        JsonNode body = parse(response.body());
        String reported = body != null && body.hasNonNull("error") ? truncate(body.get("error").asText()) : null;

        if (status >= 200 && status < 300) {
            boolean sent = body == null || !body.has("sent") || body.get("sent").asBoolean();
            if (sent) {
                return DeliveryOutcome.accepted(status, body != null && body.hasNonNull("status") ? body.get("status").asText() : "OK");
            }
            return DeliveryOutcome.retryable(status, reported != null ? reported : "Reported not sent", null);
        }
        if (status == 401 || status == 403) {
            // Configuration, not a transient fault: retrying with the same secret will keep failing.
            log.error("Notification-service rejected the internal-service secret - check "
                    + "NOTIFICATION_SERVICE_INTERNAL_SECRET", kv("status", status), kv("eventType", eventType));
            return DeliveryOutcome.permanent(status, "Authentication rejected (HTTP " + status + ")");
        }
        if (status == 429) {
            return DeliveryOutcome.retryable(status, "Rate limited", parseRetryAfter(response.retryAfter()));
        }
        if (status == 408 || status >= 500) {
            log.warn("Notification-service error", kv("status", status), kv("eventType", eventType));
            return DeliveryOutcome.retryable(status, reported != null ? reported : "HTTP " + status,
                    parseRetryAfter(response.retryAfter()));
        }
        log.error("Notification-service rejected the request", kv("status", status), kv("eventType", eventType));
        return DeliveryOutcome.permanent(status, reported != null ? reported : "Rejected (HTTP " + status + ")");
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (IOException ex) {
            return null;
        }
    }

    private static Duration parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= DETAIL_LIMIT ? value : value.substring(0, DETAIL_LIMIT);
    }
}
