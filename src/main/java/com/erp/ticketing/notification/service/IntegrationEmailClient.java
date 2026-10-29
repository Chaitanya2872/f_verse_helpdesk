package com.erp.ticketing.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends helpdesk emails along the platform's single outbound path:
 *
 * <pre>helpdesk-service -&gt; integration-service -&gt; notifications-service -&gt; SMTP</pre>
 *
 * <p>Helpdesk holds no SMTP configuration of its own; credentials and the from address live
 * only in notifications-service. Failures are returned as {@code false}, never thrown, so a
 * mail problem can't fail the ticket operation that triggered it.
 */
@Component
public class IntegrationEmailClient {

    private static final Logger log = LoggerFactory.getLogger(IntegrationEmailClient.class);

    private static final String CALLING_MODULE = "helpdesk";

    private final RestClient restClient;

    public IntegrationEmailClient(
            @LoadBalanced RestClient.Builder integrationRestClientBuilder,
            @Value("${app.notifications.integration-url:http://integration-service}") String integrationUrl,
            @Value("${app.security.internal-service-secret:}") String internalServiceSecret) {
        this.restClient = integrationRestClientBuilder
                .baseUrl(integrationUrl)
                .defaultHeader("X-Internal-Service-Secret", internalServiceSecret)
                .defaultHeader("X-Calling-Module", CALLING_MODULE)
                .build();
    }

    /** True when the relay reports the email as sent. */
    public boolean send(String to, String subject, String bodyText, String eventType, String idempotencyKey) {
        Map<String, Object> request = Map.of(
                "module", CALLING_MODULE,
                "eventType", eventType,
                "to", List.of(to),
                "subject", subject,
                "bodyText", bodyText,
                "idempotencyKey", idempotencyKey);
        try {
            restClient.post()
                    .uri("/internal/integration/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            log.warn("Email relay rejected eventType={} to={} status={} body={}",
                    eventType, to, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return false;
        } catch (Exception ex) {
            log.warn("Email relay unreachable for eventType={} to={}: {}", eventType, to, ex.getMessage());
            return false;
        }
    }

    @Configuration
    static class LoadBalancedClientConfig {

        /**
         * Resolves {@code http://integration-service} through Eureka. Bounded timeouts: the send
         * happens inside the ticket operation, which must not hang on a slow relay.
         */
        @Bean
        @LoadBalanced
        RestClient.Builder integrationRestClientBuilder() {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            requestFactory.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
            return RestClient.builder().requestFactory(requestFactory);
        }
    }
}
