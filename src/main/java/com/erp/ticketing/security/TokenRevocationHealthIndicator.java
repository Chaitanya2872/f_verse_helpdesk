package com.erp.ticketing.security;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports {@code /actuator/health} DOWN until the token-revocation list has loaded. While it is not
 * loaded the service refuses every user token (fail-closed), so it is not able to serve traffic and
 * must not look healthy. Contributes as {@code tokenRevocation}.
 *
 * <p>Where fail-closed is switched off (dev/test only) tokens are still accepted without the list,
 * so the indicator stays UP and just reports {@code loaded=false}.
 */
@Component("tokenRevocation")
public class TokenRevocationHealthIndicator implements HealthIndicator {

    private final TokenRevocationRegistry registry;

    public TokenRevocationHealthIndicator(TokenRevocationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        boolean loaded = registry.isLoaded();
        Health.Builder builder = loaded || !registry.isFailClosed() ? Health.up() : Health.down();
        return builder
                .withDetail("loaded", loaded)
                .withDetail("failClosed", registry.isFailClosed())
                .build();
    }
}
