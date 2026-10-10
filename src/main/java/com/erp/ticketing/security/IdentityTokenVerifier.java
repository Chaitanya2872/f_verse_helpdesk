package com.erp.ticketing.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies identity-service's RS256 access tokens - the tokens the platform's web
 * app sends through the API gateway - with {@code app.security.jwt.public-key}, the
 * same key the gateway and every other service verify with.
 *
 * <p>Kept apart from {@link JwtService}, which signs and verifies this service's own
 * HS256 tokens (used by the integration tests). {@link JwtAuthenticationFilter} tries
 * this verifier first and falls back to {@link JwtService}.
 */
@Component
public class IdentityTokenVerifier {

    private final PublicKey publicKey;

    public IdentityTokenVerifier(@Value("${app.security.jwt.public-key:}") String configuredKey) {
        this.publicKey = parsePublicKey(configuredKey);
    }

    /** The token's claims if identity-service signed it and it has not expired; empty otherwise. */
    public Optional<Claims> verify(String token) {
        if (publicKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** identity-service's expanded permission keys (implications already applied). */
    public Set<String> extractPermissions(Claims claims) {
        Object raw = claims.get("permissions");
        Set<String> permissions = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    permissions.add(String.valueOf(value));
                }
            }
        }
        return permissions;
    }

    private static PublicKey parsePublicKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(
                    configured.replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replaceAll("[^A-Za-z0-9+/=]", ""));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "app.security.jwt.public-key is not a valid X.509 RSA public key.", ex);
        }
    }
}
