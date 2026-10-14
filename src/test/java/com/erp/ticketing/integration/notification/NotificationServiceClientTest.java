package com.erp.ticketing.integration.notification;

import com.erp.ticketing.integration.notification.NotificationServiceClient.EmailRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The HTTP contract with the notification-service's /internal/notifications/email, against a mock server -
 * no real mail is ever sent.
 */
class NotificationServiceClientTest {

    private static final String URL = "http://notifications.test/internal/notifications/email";

    private MockRestServiceServer server;
    private NotificationServiceClient client;

    @BeforeEach
    void setUp() {
        NotificationServiceProperties properties = new NotificationServiceProperties();
        properties.setBaseUrl("http://notifications.test");
        properties.setInternalSecret("s3cret-value");
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NotificationServiceClient(builder.build(), properties, new ObjectMapper());
    }

    private static EmailRequest request() {
        return new EmailRequest("helpdesk", "TICKET_CREATED", List.of("a@example.com"), "Subject", "text", "<p>html</p>", "key-1");
    }

    @Test
    void sendsTheDocumentedContractWithSecretAndCorrelationHeaders() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Secret", "s3cret-value"))
                .andExpect(header("X-Correlation-Id", "corr-1"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.module").value("helpdesk"))
                .andExpect(jsonPath("$.eventType").value("TICKET_CREATED"))
                .andExpect(jsonPath("$.to[0]").value("a@example.com"))
                .andExpect(jsonPath("$.subject").value("Subject"))
                .andExpect(jsonPath("$.bodyHtml").value("<p>html</p>"))
                .andExpect(jsonPath("$.idempotencyKey").value("key-1"))
                .andRespond(withSuccess("{\"sent\":true,\"status\":\"SENT\",\"recipientCount\":1}", MediaType.APPLICATION_JSON));

        DeliveryOutcome outcome = client.sendEmail(request(), "corr-1");

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.ACCEPTED);
        assertThat(outcome.httpStatus()).isEqualTo(200);
        server.verify();
    }

    @Test
    void aDeduplicatedResponseCountsAsAccepted() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"sent\":true,\"status\":\"DEDUPLICATED\"}", MediaType.APPLICATION_JSON));

        assertThat(client.sendEmail(request(), null).kind()).isEqualTo(DeliveryOutcome.Kind.ACCEPTED);
    }

    @Test
    void serverErrorsAreRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        DeliveryOutcome outcome = client.sendEmail(request(), null);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.RETRYABLE);
        assertThat(outcome.httpStatus()).isEqualTo(503);
    }

    @Test
    void theNotificationServicesOwn502WithAnErrorIsRetryableAndCarriesItsMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"sent\":false,\"status\":\"FAILED\",\"error\":\"SMTP connection refused\"}"));

        DeliveryOutcome outcome = client.sendEmail(request(), null);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.RETRYABLE);
        assertThat(outcome.detail()).isEqualTo("SMTP connection refused");
    }

    @Test
    void aRejectedSecretIsPermanentAndNeverLeaksTheSecret() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        DeliveryOutcome outcome = client.sendEmail(request(), null);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.PERMANENT);
        assertThat(outcome.httpStatus()).isEqualTo(403);
        assertThat(outcome.detail()).doesNotContain("s3cret-value");
    }

    @Test
    void validationErrorsArePermanent() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"A subject is required.\"}"));

        DeliveryOutcome outcome = client.sendEmail(request(), null);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.PERMANENT);
        assertThat(outcome.detail()).isEqualTo("A subject is required.");
    }

    @Test
    void rateLimitingIsRetryableAndHonoursRetryAfter() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));

        DeliveryOutcome outcome = client.sendEmail(request(), null);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.RETRYABLE);
        assertThat(outcome.retryAfter()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void aTimeoutIsRetryableAndDoesNotThrow() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        DeliveryOutcome outcome = client.sendEmail(request(), null);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.RETRYABLE);
        assertThat(outcome.httpStatus()).isNull();
    }

    @Test
    void aConnectionFailureIsRetryable() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        assertThat(client.sendEmail(request(), null).kind()).isEqualTo(DeliveryOutcome.Kind.RETRYABLE);
    }

    @Test
    void anUnparseableSuccessBodyIsStillAccepted() {
        server.expect(requestTo(URL)).andExpect(content().string(org.hamcrest.Matchers.containsString("TICKET_CREATED")))
                .andRespond(withSuccess("OK", MediaType.TEXT_PLAIN));

        assertThat(client.sendEmail(request(), null).kind()).isEqualTo(DeliveryOutcome.Kind.ACCEPTED);
    }
}
