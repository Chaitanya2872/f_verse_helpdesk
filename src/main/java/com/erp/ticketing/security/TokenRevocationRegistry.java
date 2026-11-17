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

  /** Outcome of checking one token against the revocation list. */
  public enum Status {
    NOT_REVOKED,
    REVOKED,
    /** The list has not loaded yet and the registry fails closed. */
    NOT_LOADED
  }

  public TokenRevocationRegistry(
      // No localhost default outside the dev profile: production must set IDENTITY_SERVICE_URL
      // (RequiredSecurityConfigValidator refuses to start without it).
      @Value("${app.security.revocation.identity-url:}") String identityServiceUrl,
      @Value("${app.security.internal-service-secret:${INTERNAL_SERVICE_SECRET:}}") String internalServiceSecret,
      @Value("${app.security.revocation.poll-interval-ms:15000}") long pollIntervalMs,
      @Value("${app.security.revocation.fail-closed:true}") boolean failClosed
  ) {
    String base = identityServiceUrl == null ? "" : identityServiceUrl.trim();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    this.feedUri = base.isEmpty() ? null : URI.create(base + "/internal/auth/token-revocations");
    this.internalServiceSecret = internalServiceSecret == null ? "" : internalServiceSecret.trim();
    this.pollIntervalMs = pollIntervalMs;
    this.failClosed = failClosed;
  }

  @PostConstruct
  void start() {
    String consequence = failClosed
        ? "; user tokens are refused" : "; revoked tokens stay usable until they expire";
    if (feedUri == null) {
      LOGGER.warn("IDENTITY_SERVICE_URL is not set: token revocations cannot be read{}", consequence);
      return;
    }
    if (internalServiceSecret.isEmpty()) {
      LOGGER.warn("INTERNAL_SERVICE_SECRET is not set: token revocations cannot be read{}", consequence);
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
    return check(userId, issuedAt, sessionId) != Status.NOT_REVOKED;
  }

  /** Same decision as {@link #isRevoked}, but tells a revocation apart from a list not yet loaded. */
  public Status check(String userId, Instant issuedAt, String sessionId) {
    if (!loaded) {
      return failClosed ? Status.NOT_LOADED : Status.NOT_REVOKED;
    }
    if (sessionId != null && revokedSessions.contains(sessionId)) {
      return Status.REVOKED;
    }
    Instant revokedAt = userId == null ? null : revokedAtByUser.get(userId);
    if (revokedAt == null) {
      return Status.NOT_REVOKED;
    }
    // iat has second precision; a token from the same second as the revocation is refused too.
    return issuedAt == null || !issuedAt.isAfter(revokedAt.truncatedTo(ChronoUnit.SECONDS))
        ? Status.REVOKED : Status.NOT_REVOKED;
  }

  /** True once a poll of identity's revocation feed has succeeded. */
  public boolean isLoaded() {
    return loaded;
  }

  /** True when user tokens are refused until the list loads (the production setting). */
  public boolean isFailClosed() {
    return failClosed;
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
      int status = response.statusCode();
      if (status != 200) {
        String hint = status == 401 || status == 403
            ? " (INTERNAL_SERVICE_SECRET does not match identity-service's)" : "";
        LOGGER.warn("Token revocation poll of {} answered HTTP {}{}; {}", feedUri, status, hint, keptState());
        return;
      }
      RevocationFeed feed = objectMapper.readValue(response.body(), RevocationFeed.class);
      if (feed == null || feed.revocations() == null) {
        LOGGER.warn("Token revocation poll of {} returned no revocation list; {}", feedUri, keptState());
        return;
      }
      replace(feed.revocations(), feed.revokedSessions());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (Exception exception) {
      // The exception message describes the connection/parse failure; the request's secret header
      // is never part of it, and the secret itself is never logged.
      LOGGER.warn("Token revocation poll of {} failed: {}: {}; {}", feedUri,
          exception.getClass().getSimpleName(), exception.getMessage(), keptState());
    }
  }

  private String keptState() {
    if (loaded) {
      return "keeping the last known set";
    }
    return failClosed ? "revocations not loaded, user tokens are refused" : "revocations not loaded";
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
