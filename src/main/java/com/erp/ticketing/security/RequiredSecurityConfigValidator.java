package com.erp.ticketing.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start helpdesk-service when the settings it needs to accept identity-service tokens
 * are missing. Without them the service starts "healthy" but answers every request with
 * 401 "Invalid or expired token" - exactly the production failure this guards against.
 *
 * <ul>
 *   <li>{@code JWT_PUBLIC_KEY} - verifies identity's RS256 access tokens;</li>
 *   <li>{@code INTERNAL_SERVICE_SECRET} - authenticates the token-revocation poll;</li>
 *   <li>{@code IDENTITY_SERVICE_URL} - where the revocation feed is polled from.</li>
 * </ul>
 *
 * <p>On by default. Only the dev and test profiles turn it off
 * ({@code app.security.startup-check.enabled: false}), so any other profile, including one
 * nobody anticipated, gets the check. Only variable names are reported, never values.
 */
@Component
public class RequiredSecurityConfigValidator implements InitializingBean {

    private final boolean enabled;
    private final String publicKey;
    private final String internalServiceSecret;
    private final String identityServiceUrl;

    public RequiredSecurityConfigValidator(
            @Value("${app.security.startup-check.enabled:true}") boolean enabled,
            @Value("${app.security.jwt.public-key:}") String publicKey,
            @Value("${app.security.internal-service-secret:${INTERNAL_SERVICE_SECRET:}}") String internalServiceSecret,
            @Value("${app.security.revocation.identity-url:}") String identityServiceUrl) {
        this.enabled = enabled;
        this.publicKey = publicKey;
        this.internalServiceSecret = internalServiceSecret;
        this.identityServiceUrl = identityServiceUrl;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(publicKey)) {
            missing.add("JWT_PUBLIC_KEY");
        }
        if (isBlank(internalServiceSecret)) {
            missing.add("INTERNAL_SERVICE_SECRET");
        }
        if (isBlank(identityServiceUrl)) {
            missing.add("IDENTITY_SERVICE_URL");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("helpdesk-service cannot start: required environment variable(s) "
                    + String.join(", ", missing) + " not set. Without them every request is refused with "
                    + "401 'Invalid or expired token'. Set them to the same values api-gateway uses "
                    + "(see docs/deployment/required-env.md).");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
