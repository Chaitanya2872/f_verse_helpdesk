package com.erp.ticketing.integration.asset;

import com.erp.ticketing.common.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * HTTP client for the Asset Manager service. The two services stay separate - each keeps its own database and
 * neither shares code with the other - and this is the only point where the helpdesk talks to it.
 *
 * <p>Contract (read from the Asset Manager source, not assumed):
 * <ul>
 *   <li>{@code GET /api/assets/search?name=} - case-insensitive match on asset name or asset code, a JSON array</li>
 *   <li>{@code GET /api/assets/{id}} - one asset, or 404 when no asset has that id</li>
 *   <li>an asset carries {@code assetId, assetCode, assetName, assetCategory, status, location}</li>
 *   <li>every call needs an identity-service bearer token; the caller's is forwarded, and a 401 is reported
 *       as unavailable rather than as a missing asset</li>
 * </ul>
 * Only those fields are read, so the helpdesk never keeps a copy of values, vendors or assignees.
 */
@Component
public class AssetManagerClient {

    /** Recorded as the source system of every reference that points into the Asset Manager. */
    public static final String SOURCE_SYSTEM = "ASSET_MANAGER";

    private static final Logger log = LoggerFactory.getLogger(AssetManagerClient.class);

    /** The part of an Asset Manager asset the helpdesk shows in its selector and stores as a label. */
    public record AssetSummary(String id, String code, String label, String category, String status, String location) {
    }

    private final RestClient restClient;
    private final AssetManagerProperties properties;

    @Autowired
    public AssetManagerClient(RestClient.Builder builder, AssetManagerProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
        this.properties = properties;
    }

    /** For tests: a client around a pre-built {@link RestClient} (for example one bound to a mock server). */
    AssetManagerClient(RestClient restClient, AssetManagerProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** Assets whose name or code contains the query; empty when the integration is switched off. */
    public List<AssetSummary> search(String query) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(b -> b.path("/api/assets/search").queryParam("name", query.trim()).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(AssetManagerClient::forwardAuthorization)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw unavailable("search", ex);
        }
        List<AssetSummary> results = new ArrayList<>();
        if (body != null && body.isArray()) {
            for (JsonNode node : body) {
                if (results.size() >= properties.getSearchLimit()) {
                    break;
                }
                AssetSummary summary = summarize(node);
                if (summary != null) {
                    results.add(summary);
                }
            }
        }
        return results;
    }

    /** The asset with this id, or empty when the Asset Manager has no such asset. */
    public Optional<AssetSummary> find(String assetId) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri("/api/assets/{id}", assetId)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(AssetManagerClient::forwardAuthorization)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return null;
                        }
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            throw new RestClientException("HTTP " + res.getStatusCode().value());
                        }
                        return res.bodyTo(JsonNode.class);
                    });
        } catch (RestClientException ex) {
            throw unavailable("lookup", ex);
        }
        return Optional.ofNullable(body == null ? null : summarize(body));
    }

    /**
     * The Asset Manager requires an identity-service access token, so the caller's own token is passed on:
     * the helpdesk never asks it for anything on its own behalf, only on behalf of the user it is serving.
     */
    private static void forwardAuthorization(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }

    private static AssetSummary summarize(JsonNode node) {
        String id = text(node, "assetId");
        if (id == null) {
            id = text(node, "id");
        }
        if (id == null) {
            return null;
        }
        String code = text(node, "assetCode");
        String name = text(node, "assetName");
        String label = name != null ? name : code != null ? code : id;
        return new AssetSummary(id, code, label, text(node, "assetCategory"), text(node, "status"), text(node, "location"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private ServiceUnavailableException unavailable(String operation, RestClientException ex) {
        log.warn("Asset Manager unreachable", kv("operation", operation), kv("error", ex.getClass().getSimpleName()));
        return new ServiceUnavailableException("The Asset Manager could not be reached - try again shortly");
    }
}
