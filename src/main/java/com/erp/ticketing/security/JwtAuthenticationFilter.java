package com.erp.ticketing.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Authenticates each request from a {@code Bearer} RS256 JWT - an
 * identity-service access token forwarded by the API gateway, verified with
 * {@code JWT_PUBLIC_KEY} by {@link IdentityTokenVerifier} - instead of trusting
 * identity headers. Helpdesk roles are resolved by {@link UserRoleResolver}.
 * On success, the resolved identity is published as an
 * {@link ErpUserPrincipal} - kept as the principal type so the rest of the
 * app (controllers/services taking {@code @AuthenticationPrincipal
 * ErpUserPrincipal}) needs no change; only how that principal is populated
 * changed, from trusted headers to a signed token.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String ACTUATOR_PATH = "/actuator/";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final IdentityTokenVerifier identityTokenVerifier;
    private final JwtService jwtService;
    private final UserRoleResolver userRoleResolver;
    private final TokenRevocationRegistry revocationRegistry;
    private final boolean localTokensEnabled;

    public JwtAuthenticationFilter(IdentityTokenVerifier identityTokenVerifier,
                                   JwtService jwtService,
                                   UserRoleResolver userRoleResolver,
                                   TokenRevocationRegistry revocationRegistry,
                                   @Value("${app.jwt.local-tokens-enabled:false}") boolean localTokensEnabled) {
        this.identityTokenVerifier = identityTokenVerifier;
        this.jwtService = jwtService;
        this.userRoleResolver = userRoleResolver;
        this.revocationRegistry = revocationRegistry;
        this.localTokensEnabled = localTokensEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith(ACTUATOR_PATH) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        // Every token is RS256, verified with JWT_PUBLIC_KEY - there is no symmetric fallback.
        IdentityTokenVerifier.Verification verification = identityTokenVerifier.verifyToken(token);
        Claims claims = verification.claims();
        if (claims == null) {
            reject(request, response, verification.reason(), null);
            return;
        }
        // Deactivated, offboarded and deleted users and signed-out sessions, from identity's
        // feed: direct calls and forwarded tokens never pass the gateway's own check.
        Object sessionId = claims.get("sessionId");
        TokenRevocationRegistry.Status revocation = revocationRegistry.check(claims.getSubject(),
                claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant(),
                sessionId == null ? null : String.valueOf(sessionId));
        if (revocation != TokenRevocationRegistry.Status.NOT_REVOKED) {
            reject(request, response, revocation == TokenRevocationRegistry.Status.REVOKED
                    ? TokenRejectionReason.REVOKED : TokenRejectionReason.REVOCATIONS_NOT_LOADED,
                    claims.getSubject());
            return;
        }
        // Tokens minted by JwtService (integration tests) carry helpdesk role names in "roles" and no
        // "permissions" claim; null permissions makes UserRoleResolver read those names. That is only
        // honoured where a profile enables it - elsewhere a token without permissions gets no role.
        Set<String> permissions = claims.containsKey("permissions") || !localTokensEnabled
                ? identityTokenVerifier.extractPermissions(claims)
                : null;

        String subject = claims.getSubject();
        String email = claims.get("email", String.class);
        String name = claims.get("name", String.class);
        Set<String> roles = userRoleResolver.resolveRoles(jwtService.extractRoles(claims), permissions);

        ErpUserPrincipal principal = new ErpUserPrincipal(subject, email, name, roles);
        List<SimpleGrantedAuthority> authorities = userRoleResolver.resolveAuthorities(roles);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    /**
     * Logs why a token was refused and answers 401. The token is never logged; the subject (a user
     * id, not a credential) is included only once the signature has been verified.
     */
    private void reject(HttpServletRequest request, HttpServletResponse response,
                        TokenRejectionReason reason, String verifiedSubject) throws IOException {
        if (verifiedSubject == null) {
            LOGGER.warn("Rejected bearer token: reason={} method={} path={}",
                    reason.code(), request.getMethod(), request.getRequestURI());
        } else {
            LOGGER.warn("Rejected bearer token: reason={} method={} path={} subject={}",
                    reason.code(), request.getMethod(), request.getRequestURI(), verifiedSubject);
        }
        writeUnauthorized(response, "Invalid or expired token");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}");
    }
}
