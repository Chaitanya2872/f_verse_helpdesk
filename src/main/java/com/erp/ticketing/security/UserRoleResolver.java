package com.erp.ticketing.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the helpdesk's own roles (REQUESTER, AGENT, MANAGER, ADMIN) for a
 * validated token, and maps them to Spring Security {@code ROLE_*} authorities.
 *
 * <p>identity-service tokens carry platform roles and permission keys, not helpdesk
 * roles, so for those the helpdesk role comes from the permission keys seeded by
 * identity-service migration V35:
 * <ul>
 *   <li>{@code feature.helpdesk.admin} - ADMIN</li>
 *   <li>{@code feature.helpdesk.manage} - MANAGER</li>
 *   <li>{@code feature.helpdesk.agent} - AGENT</li>
 *   <li>{@code module.helpdesk} / {@code page.helpdesk} - REQUESTER</li>
 * </ul>
 * plus ADMIN for the platform's administrative roles. Role names are otherwise
 * ignored for identity tokens, so a custom role someone happens to call "agent"
 * in the roles UI gains nothing by its name.
 *
 * <p>Tokens without a {@code permissions} claim (the HMAC tokens the integration
 * tests mint) keep the original behaviour: their {@code roles} claim already
 * holds helpdesk role names. Unknown strings are ignored rather than fatal.
 */
@Component
public class UserRoleResolver {

    public static final String PERMISSION_ADMIN = "feature.helpdesk.admin";
    public static final String PERMISSION_MANAGE = "feature.helpdesk.manage";
    public static final String PERMISSION_AGENT = "feature.helpdesk.agent";
    public static final String PERMISSION_MODULE = "module.helpdesk";
    public static final String PERMISSION_PAGE = "page.helpdesk";

    private static final Set<String> KNOWN_ROLES = Set.of("REQUESTER", "AGENT", "MANAGER", "ADMIN");

    /** Platform roles that administer the helpdesk without needing a helpdesk grant. */
    private static final Set<String> PLATFORM_ADMIN_ROLES =
            Set.of("ROLE_ADMIN", "ROLE_PLATFORM_SUPER_ADMIN", "ROLE_MASTER");

    /**
     * @param rawRoles    the token's {@code roles} claim
     * @param permissions the token's {@code permissions} claim, or {@code null} when the
     *                    token has none (i.e. it is not an identity-service token)
     */
    public Set<String> resolveRoles(Set<String> rawRoles, Set<String> permissions) {
        Set<String> resolved = new LinkedHashSet<>();
        if (permissions == null) {
            if (rawRoles != null) {
                rawRoles.stream()
                        .map(UserRoleResolver::normalize)
                        .filter(KNOWN_ROLES::contains)
                        .forEach(resolved::add);
            }
            return resolved;
        }

        boolean platformAdmin = rawRoles != null && rawRoles.stream()
                .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT))
                .anyMatch(PLATFORM_ADMIN_ROLES::contains);
        if (platformAdmin || permissions.contains(PERMISSION_ADMIN)) {
            resolved.add("ADMIN");
        }
        if (permissions.contains(PERMISSION_MANAGE)) {
            resolved.add("MANAGER");
        }
        if (permissions.contains(PERMISSION_AGENT)) {
            resolved.add("AGENT");
        }
        if (!resolved.isEmpty()
                || permissions.contains(PERMISSION_MODULE)
                || permissions.contains(PERMISSION_PAGE)) {
            // Everyone who can open the helpdesk can raise a ticket.
            resolved.add("REQUESTER");
        }
        return resolved;
    }

    public List<SimpleGrantedAuthority> resolveAuthorities(Collection<String> helpdeskRoles) {
        if (helpdeskRoles == null) {
            return List.of();
        }
        return helpdeskRoles.stream()
                .map(UserRoleResolver::normalize)
                .filter(KNOWN_ROLES::contains)
                .distinct()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }

    private static String normalize(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }
}
