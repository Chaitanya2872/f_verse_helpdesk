package com.erp.ticketing.integration.notification.whatsapp;

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

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Looks up a person's phone number in identity-service, the platform's only record of it (helpdesk users carry
 * an email and name but no phone). Uses identity's existing internal endpoint
 * {@code GET /internal/users/profile/{id}} with the shared {@code X-Internal-Service-Secret}; the id is the
 * helpdesk user's {@code erpUserId}, which is identity's user id (the token subject).
 *
 * <p>Called by the dispatcher, outside any database transaction. Neither the secret nor the number is logged.
 */
@Component
public class IdentityPhoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(IdentityPhoneDirectory.class);
    private static final String SECRET_HEADER = "X-Internal-Service-Secret";

    public enum Status {
        /** The person exists; {@code phone} is their stored number, possibly null. */
        FOUND,
        /** identity-service does not know the id. */
        NOT_FOUND,
        /** Timeout, connection failure or 5xx - try again later. */
        UNAVAILABLE,
        /** Missing base URL or secret, or the secret was rejected - fix configuration. */
        MISCONFIGURED
    }

    public record Lookup(Status status, String phone, Integer httpStatus) {
    }

    private record RawResponse(int status, String body) {
    }

    private final RestClient restClient;
    private final WhatsAppProperties.Identity settings;
    private final ObjectMapper mapper;

    @Autowired
    public IdentityPhoneDirectory(RestClient.Builder builder, WhatsAppProperties properties, ObjectMapper mapper) {
        WhatsAppProperties.Identity identity = properties.getIdentity();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) identity.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) identity.getReadTimeout().toMillis());
        String baseUrl = identity.getBaseUrl() == null ? "" : identity.getBaseUrl().trim();
        this.restClient = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.settings = identity;
        this.mapper = mapper;
    }

    /** For tests: a client around a pre-built {@link RestClient} bound to a mock server. */
    IdentityPhoneDirectory(RestClient restClient, WhatsAppProperties.Identity settings, ObjectMapper mapper) {
        this.restClient = restClient;
        this.settings = settings;
        this.mapper = mapper;
    }

    public Lookup lookup(String erpUserId) {
        if (isBlank(settings.getBaseUrl()) || isBlank(settings.getInternalSecret())) {
            return new Lookup(Status.MISCONFIGURED, null, null);
        }
        if (isBlank(erpUserId)) {
            return new Lookup(Status.NOT_FOUND, null, null);
        }
        RawResponse response;
        try {
            response = restClient.get()
                    .uri("/internal/users/profile/{id}", erpUserId)
                    .header(SECRET_HEADER, settings.getInternalSecret())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((req, res) -> new RawResponse(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException ex) {
            log.warn("Identity-service unreachable for phone lookup", kv("error", ex.getClass().getSimpleName()));
            return new Lookup(Status.UNAVAILABLE, null, null);
        }

        int status = response.status();
        if (status >= 200 && status < 300) {
            JsonNode body = parse(response.body());
            String phone = body == null ? null : body.path("phoneNumber").asText(null);
            return new Lookup(Status.FOUND, isBlank(phone) ? null : phone, status);
        }
        if (status == 401 || status == 403) {
            log.error("Identity-service rejected the internal-service secret - check IDENTITY_SERVICE_INTERNAL_SECRET",
                    kv("status", status));
            return new Lookup(Status.MISCONFIGURED, null, status);
        }
        if (status == 404) {
            return new Lookup(Status.NOT_FOUND, null, status);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new Lookup(Status.UNAVAILABLE, null, status);
        }
        // identity answers an unknown id with 404, but some of its handlers use 400 for "not found"-like cases.
        return new Lookup(Status.NOT_FOUND, null, status);
    }

    private JsonNode parse(String body) {
        if (isBlank(body)) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
