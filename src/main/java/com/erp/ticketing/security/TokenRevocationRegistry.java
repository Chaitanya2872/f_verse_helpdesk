package com.erp.ticketing.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Users and sessions whose access tokens identity-service has revoked, polled from identity's
 * {@code /internal/auth/token-revocations} feed - the same feed the gateway reads.
 *
 * <p>This service verifies tokens by signature, so without this a deactivated or offboarded user,
 * a deleted user or a signed-out session kept working access here for the rest of the token's
 * lifetime whenever the gateway was bypassed: a direct call, or another service forwarding the
 * user's token. With it the gap is one poll interval.
 *
 * <p>Failing safe: until the first successful poll nothing is known, so user tokens are refused
 * ({@code app.security.revocation.fail-closed}, default true) - a service restarted while identity
 * is down must not forget revocations it had. After that, a failed poll keeps the last set; since
 * revoking needs identity, an identity outage adds no revocations the set is missing. Each
 * successful poll replaces the set with identity's one-token-lifetime window, so entries age out.
 */
@Component
public class TokenRevocationRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(TokenRevocationRegistry.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final long RETRY_UNTIL_LOADED_MS = 2000;

  private final URI feedUri;
  private final String internalServiceSecret;
  private final long pollIntervalMs;
  private final boolean failClosed;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
  private final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private ScheduledExecutorService scheduler;

  private volatile boolean loaded;
  private volatile Map<String, Instant> revokedAtByUser = Map.of();
  private volatile Set<String> revokedSessions = Set.of();

  public TokenRevocationRegistry(
      @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String identityServiceUrl,
      @Value("${app.security.internal-service-secret:}") String internalServiceSecret,
      @Value("${app.security.revocation.poll-interval-ms:15000}") long pollIntervalMs,
      @Value("${app.security.revocation.fail-closed:true}") boolean failClosed
  ) {
    String base = identityServiceUrl.endsWith("/")
        ? identityServiceUrl.substring(0, identityServiceUrl.length() - 1) : identityServiceUrl;
    this.feedUri = URI.create(base + "/internal/auth/token-revocations");
    this.internalServiceSecret = internalServiceSecret == null ? "" : internalServiceSecret.trim();
    this.pollIntervalMs = pollIntervalMs;
    this.failClosed = failClosed;
  }

  @PostConstruct
  void start() {
    if (internalServiceSecret.isEmpty()) {
      LOGGER.warn("INTERNAL_SERVICE_SECRET is not set: token revocations cannot be read{}",
          failClosed ? "; user tokens are refused" : "; revoked tokens stay usable until they expire");
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "token-revocation-poll");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.execute(this::pollAndReschedule);
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /**
   * True when this token must be refused: its user's tokens were revoked at or after it was issued,
   * its session was signed out, or - failing closed - revocations have not been loaded yet. Service
   * tokens (no user, no session) are never matched.
   */
  public boolean isRevoked(String userId, Instant issuedAt, String sessionId) {
    if (!loaded) {
      return failClosed;
    }
    if (sessionId != null && revokedSessions.contains(sessionId)) {
      return true;
    }
    Instant revokedAt = userId == null ? null : revokedAtByUser.get(userId);
    if (revokedAt == null) {
      return false;
    }
    // iat has second precision; a token from the same second as the revocation is refused too.
    return issuedAt == null || !issuedAt.isAfter(revokedAt.truncatedTo(ChronoUnit.SECONDS));
  }

  private void pollAndReschedule() {
    refresh();
    try {
      scheduler.schedule(this::pollAndReschedule, loaded ? pollIntervalMs : RETRY_UNTIL_LOADED_MS,
          TimeUnit.MILLISECONDS);
    } catch (RuntimeException ignored) {
      // Shutting down.
    }
  }

  void refresh() {
    try {
      HttpRequest request = HttpRequest.newBuilder(feedUri)
          .timeout(REQUEST_TIMEOUT)
          .header("X-Internal-Service-Secret", internalServiceSecret)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOGGER.warn("Token revocation poll answered {}; keeping the last known set", response.statusCode());
        return;
      }
      RevocationFeed feed = objectMapper.readValue(response.body(), RevocationFeed.class);
      if (feed == null || feed.revocations() == null) {
        LOGGER.warn("Token revocation poll returned nothing; keeping the last known set");
        return;
      }
      replace(feed.revocations(), feed.revokedSessions());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (Exception exception) {
      LOGGER.warn("Token revocation poll failed ({}); keeping the last known set",
          exception.getClass().getSimpleName());
    }
  }

  void replace(List<Revocation> revocations, List<String> sessions) {
    Map<String, Instant> next = new HashMap<>();
    for (Revocation revocation : revocations) {
      if (revocation.userId() != null && revocation.revokedAt() != null) {
        next.merge(revocation.userId(), revocation.revokedAt(), (left, right) -> left.isAfter(right) ? left : right);
      }
    }
    revokedAtByUser = Map.copyOf(next);
    revokedSessions = sessions == null ? Set.of() : Set.copyOf(sessions.stream().filter(id -> id != null).toList());
    if (!loaded) {
      LOGGER.info("Token revocations loaded");
    }
    loaded = true;
  }

  record RevocationFeed(Instant serverTime, List<Revocation> revocations, List<String> revokedSessions) {
  }

  record Revocation(String userId, Instant revokedAt) {
  }
}
