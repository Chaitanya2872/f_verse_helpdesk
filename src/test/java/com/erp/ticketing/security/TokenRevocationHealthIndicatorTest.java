package com.erp.ticketing.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRevocationHealthIndicatorTest {

    private static TokenRevocationRegistry registry(boolean failClosed) {
        // Never started: no poll runs, so the list stays unloaded until replace() is called.
        return new TokenRevocationRegistry("http://identity-service:8081", "", 15000, failClosed);
    }

    @Test
    void downUntilRevocationsLoadThenUp() {
        TokenRevocationRegistry registry = registry(true);
        TokenRevocationHealthIndicator indicator = new TokenRevocationHealthIndicator(registry);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("loaded", false);

        registry.replace(List.of(), List.of());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("loaded", true);
    }

    @Test
    void upWhenFailClosedIsOffBecauseTokensAreStillServed() {
        TokenRevocationHealthIndicator indicator = new TokenRevocationHealthIndicator(registry(false));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("loaded", false);
    }

    @Test
    void registryTellsNotLoadedApartFromRevoked() {
        TokenRevocationRegistry registry = registry(true);
        Instant issued = Instant.parse("2026-11-16T10:00:00Z");

        assertThat(registry.check("user-1", issued, "s1")).isEqualTo(TokenRevocationRegistry.Status.NOT_LOADED);
        assertThat(registry.isRevoked("user-1", issued, "s1")).isTrue();

        registry.replace(List.of(new TokenRevocationRegistry.Revocation("user-1", issued.plusSeconds(60))),
                List.of("s2"));

        assertThat(registry.check("user-1", issued, "s1")).isEqualTo(TokenRevocationRegistry.Status.REVOKED);
        assertThat(registry.check("user-2", issued, "s2")).isEqualTo(TokenRevocationRegistry.Status.REVOKED);
        assertThat(registry.check("user-2", issued, "s1")).isEqualTo(TokenRevocationRegistry.Status.NOT_REVOKED);
    }
}
