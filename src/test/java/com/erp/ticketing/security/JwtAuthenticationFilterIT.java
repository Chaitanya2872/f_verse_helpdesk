package com.erp.ticketing.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link JwtAuthenticationFilter} and the role-gated
 * matchers declared in {@code SecurityConfig}, run against the real (empty,
 * Flyway-migrated) {@code ticketing_test} database. {@code @Transactional}
 * rolls back anything a test commits directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JwtAuthenticationFilterIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String bearer(String userId, String role) {
        return "Bearer " + jwtService.generateToken(userId, userId + "@example.com", userId, Set.of(role));
    }

    @Test
    void missingAuthorizationHeaderReturns401WithExpectedBodyShape() throws Exception {
        mockMvc.perform(get("/api/helpdesk/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Missing or invalid Authorization header"));
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/helpdesk/tickets").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void requesterOnlyRoleIsForbiddenFromAdminEndpoint() throws Exception {
        mockMvc.perform(post("/api/helpdesk/admin/ticket-types")
                        .header("Authorization", bearer("requester-1", "REQUESTER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Should Not Be Created\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleIsAllowedOnAdminEndpoint() throws Exception {
        mockMvc.perform(post("/api/helpdesk/admin/ticket-types")
                        .header("Authorization", bearer("admin-1", "ADMIN"))
                        .contentType("application/json")
                        .content("{\"name\":\"IT-Test-Created-Type\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("IT-Test-Created-Type"));
    }

    @Test
    void unknownRoleStringGrantsNoAuthorityAndIsForbidden() throws Exception {
        // UserRoleResolver silently drops role strings outside REQUESTER/AGENT/MANAGER/ADMIN,
        // so this caller ends up with zero authorities and is forbidden even from the
        // general /api/helpdesk/** matcher (which requires at least one of the four known roles).
        mockMvc.perform(get("/api/helpdesk/tickets")
                        .header("Authorization", bearer("mystery-1", "SUPERUSER")))
                .andExpect(status().isForbidden());
    }
}
