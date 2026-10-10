package com.erp.ticketing.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Authenticates each request from a {@code Bearer} JWT - normally an
 * identity-service access token forwarded by the API gateway (see
 * {@link IdentityTokenVerifier}), with this service's own HS256 tokens
 * ({@link JwtService}) accepted as a fallback - instead of trusting identity
 * headers. Helpdesk roles are resolved by {@link UserRoleResolver}.
 * On success, the resolved identity is published as an
 * {@link ErpUserPrincipal} - kept as the principal type so the rest of the
 * app (controllers/services taking {@code @AuthenticationPrincipal
 * ErpUserPrincipal}) needs no change; only how that principal is populated
 * changed, from trusted headers to a signed token.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PATH = "/actuator/";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final IdentityTokenVerifier identityTokenVerifier;
    private final JwtService jwtService;
    private final UserRoleResolver userRoleResolver;

    public JwtAuthenticationFilter(IdentityTokenVerifier identityTokenVerifier,
                                   JwtService jwtService,
                                   UserRoleResolver userRoleResolver) {
        this.identityTokenVerifier = identityTokenVerifier;
        this.jwtService = jwtService;
        this.userRoleResolver = userRoleResolver;
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
        Claims claims = identityTokenVerifier.verify(token).orElse(null);
        // null permissions marks a token that did not come from identity-service.
        Set<String> permissions = null;
        if (claims != null) {
            permissions = identityTokenVerifier.extractPermissions(claims);
        } else {
            try {
                claims = jwtService.parseToken(token);
            } catch (JwtException | IllegalArgumentException ex) {
                writeUnauthorized(response, "Invalid or expired token");
                return;
            }
        }

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

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}");
    }
}
