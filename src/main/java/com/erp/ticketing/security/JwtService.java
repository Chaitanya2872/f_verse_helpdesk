package com.erp.ticketing.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Issues and validates the HS256 JWTs that back this application's stateless
 * login sessions. The signing key comes from {@code app.jwt.secret} - it
 * must be overridden with a long random value outside dev (see
 * application-prod.yml, which requires the JWT_SECRET env var).
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String generateToken(String subject, String email, String name, Set<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(subject)
                .claim("email", email)
                .claim("name", name)
                .claim("roles", List.copyOf(roles))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and signature/expiry-validates {@code token}, throwing
     * {@link JwtException} if it is malformed, expired, or not signed by
     * this key.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(Claims claims) {
        List<String> roles = claims.get("roles", List.class);
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }
}
