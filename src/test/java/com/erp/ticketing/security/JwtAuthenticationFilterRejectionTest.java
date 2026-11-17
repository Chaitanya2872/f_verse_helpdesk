package com.erp.ticketing.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rejection reason codes logged by {@link JwtAuthenticationFilter}. Keys are generated per run,
 * so no key material is stored in the test.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtAuthenticationFilterRejectionTest {

    private static KeyPair trusted;
    private static KeyPair untrusted;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted = generator.generateKeyPair();
        untrusted = generator.generateKeyPair();
    }

    private static String token(KeyPair signer, Instant expiresAt) {
        return Jwts.builder()
                .subject("user-42")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(expiresAt))
                .claim("sessionId", "session-1")
                .signWith(signer.getPrivate())
                .compact();
    }

    private static String validToken() {
        return token(trusted, Instant.now().plusSeconds(600));
    }

    private static JwtAuthenticationFilter filter(TokenRevocationRegistry registry) {
        IdentityTokenVerifier verifier = new IdentityTokenVerifier(
                Base64.getEncoder().encodeToString(trusted.getPublic().getEncoded()));
        // Local HS256 tokens off, as in production; JwtService only extracts roles here, so it gets a
        // throwaway random secret generated per run.
        byte[] randomSecret = new byte[48];
        new java.security.SecureRandom().nextBytes(randomSecret);
        JwtService jwtService = new JwtService(Base64.getEncoder().encodeToString(randomSecret), 60000);
        return new JwtAuthenticationFilter(verifier, jwtService, new UserRoleResolver(), registry, false);
    }

    private static TokenRevocationRegistry loadedRegistry() {
        TokenRevocationRegistry registry = new TokenRevocationRegistry("http://identity-service:8081", "", 15000, true);
        registry.replace(List.of(), List.of());
        return registry;
    }

    private static MockHttpServletResponse call(JwtAuthenticationFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/helpdesk/tickets");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void revocationsNotLoadedIsRejectedAndLoggedWithoutTheToken(CapturedOutput output) throws Exception {
        TokenRevocationRegistry notLoaded = new TokenRevocationRegistry("http://identity-service:8081", "", 15000, true);
        String token = validToken();

        MockHttpServletResponse response = call(filter(notLoaded), token);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired token");
        assertThat(output).contains("reason=revocations_not_loaded").doesNotContain(token);
    }

    @Test
    void revokedSessionIsRejectedAsRevoked(CapturedOutput output) throws Exception {
        TokenRevocationRegistry registry = new TokenRevocationRegistry("http://identity-service:8081", "", 15000, true);
        registry.replace(List.of(), List.of("session-1"));

        assertThat(call(filter(registry), validToken()).getStatus()).isEqualTo(401);
        assertThat(output).contains("reason=revoked");
    }

    @Test
    void expiredTokenIsRejectedAsExpired(CapturedOutput output) throws Exception {
        String token = token(trusted, Instant.now().minusSeconds(60));

        assertThat(call(filter(loadedRegistry()), token).getStatus()).isEqualTo(401);
        assertThat(output).contains("reason=expired").doesNotContain(token);
    }

    @Test
    void tokenFromAnotherKeyIsRejectedAsBadSignature(CapturedOutput output) throws Exception {
        String token = token(untrusted, Instant.now().plusSeconds(600));

        assertThat(call(filter(loadedRegistry()), token).getStatus()).isEqualTo(401);
        assertThat(output).contains("reason=bad_signature").doesNotContain(token);
    }

    @Test
    void garbageIsRejectedAsBadClaims(CapturedOutput output) throws Exception {
        assertThat(call(filter(loadedRegistry()), "not-a-jwt").getStatus()).isEqualTo(401);
        assertThat(output).contains("reason=bad_claims");
    }

    @Test
    void validTokenWithLoadedRevocationsPassesThrough() throws Exception {
        MockHttpServletResponse response = call(filter(loadedRegistry()), validToken());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
