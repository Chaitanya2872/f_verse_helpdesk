package com.erp.ticketing.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleResolverTest {

    private final UserRoleResolver resolver = new UserRoleResolver();

    @Test
    void legacyTokenKeepsItsHelpdeskRoleNames() {
        assertThat(resolver.resolveRoles(Set.of("agent", "SUPERUSER"), null))
                .containsExactly("AGENT");
    }

    @Test
    void identityTokenWithOnlyThePageKeyIsARequester() {
        assertThat(resolver.resolveRoles(Set.of("ROLE_SALES_EXECUTIVE"), Set.of("page.helpdesk")))
                .containsExactly("REQUESTER");
    }

    @Test
    void identityTokenMapsFeatureKeysToHelpdeskRoles() {
        assertThat(resolver.resolveRoles(
                Set.of("ROLE_SUPPORT"),
                Set.of("module.helpdesk", "feature.helpdesk.agent", "feature.helpdesk.manage")))
                .containsExactlyInAnyOrder("AGENT", "MANAGER", "REQUESTER");
    }

    @Test
    void platformAdminRolesAreHelpdeskAdmins() {
        assertThat(resolver.resolveRoles(Set.of("ROLE_PLATFORM_SUPER_ADMIN"), Set.of()))
                .containsExactlyInAnyOrder("ADMIN", "REQUESTER");
    }

    @Test
    void identityRoleNamesGrantNothingByThemselves() {
        // A custom role someone named "agent" in the roles UI is not a helpdesk agent.
        assertThat(resolver.resolveRoles(Set.of("agent", "ROLE_MANAGER"), Set.of("page.crm.leads")))
                .isEmpty();
    }

    @Test
    void authoritiesArePrefixed() {
        assertThat(resolver.resolveAuthorities(Set.of("ADMIN", "REQUESTER")))
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_REQUESTER");
    }
}
