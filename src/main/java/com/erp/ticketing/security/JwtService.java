package com.erp.ticketing.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mints RS256 tokens with {@code app.security.jwt.private-key} ({@code JWT_PRIVATE_KEY}) - the same
 * key pair identity-service signs with and every service verifies with {@code JWT_PUBLIC_KEY}.
 * There is no symmetric ({@code JWT_SECRET}) key any more: every token this service accepts is
 * verified by {@link IdentityTokenVerifier}.
 *
 * <p>Only the integration tests mint tokens. The private key is optional; without it
 * {@link #generateToken} throws, and verification is unaffected.
 */
@Component
public class JwtService {

    private final PrivateKey signingKey;
    private final long expirationMs;

    public JwtService(@Value("${app.security.jwt.private-key:}") String privateKey,
                      @Value("${app.jwt.expiration-ms:28800000}") long expirationMs) {
        this.signingKey = parsePrivateKey(privateKey);
        this.expirationMs = expirationMs;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String generateToken(String subject, String email, String name, Set<String> roles) {
        if (signingKey == null) {
            throw new IllegalStateException(
                    "app.security.jwt.private-key (JWT_PRIVATE_KEY) is not set; this service cannot mint tokens.");
        }
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(subject)
                .claim("email", email)
                .claim("name", name)
                .claim("roles", List.copyOf(roles))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(Claims claims) {
        List<String> roles = claims.get("roles", List.class);
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }

    private static PrivateKey parsePrivateKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(
                    configured.replace("-----BEGIN PRIVATE KEY-----", "")
                            .replace("-----END PRIVATE KEY-----", "")
                            .replaceAll("[^A-Za-z0-9+/=]", ""));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "app.security.jwt.private-key is not a valid PKCS#8 RSA private key.", ex);
        }
    }
}
