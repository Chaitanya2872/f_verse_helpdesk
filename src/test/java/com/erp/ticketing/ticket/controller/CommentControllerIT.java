package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for internal-comment visibility filtering
 * ({@link com.erp.ticketing.ticket.service.CommentService#getForTicket}),
 * run against the real, Flyway-migrated {@code ticketing_test} database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CommentControllerIT {

    private static final Long INCIDENT_TYPE_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void internalCommentIsHiddenFromRequesterButVisibleToAgent() throws Exception {
        String requesterId = uniqueUserId("requester");
        String agentId = staffInGeneralSupport("agent");
        long ticketId = createTicket(requesterId);

        // Public comment, visible to everyone.
        mockMvc.perform(post("/api/helpdesk/tickets/{id}/comments", ticketId)
                        .header("Authorization", bearer(agentId, "AGENT"))
                        .contentType("application/json")
                        .content("{\"body\": \"Public update\", \"isInternal\": false}"))
                .andExpect(status().isCreated());

        // Internal comment, added by an agent.
        mockMvc.perform(post("/api/helpdesk/tickets/{id}/comments", ticketId)
                        .header("Authorization", bearer(agentId, "AGENT"))
                        .contentType("application/json")
                        .content("{\"body\": \"Internal-only note\", \"isInternal\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isInternal").value(true));

        // The requester sees only the public comment.
        MvcResult requesterView = mockMvc.perform(get("/api/helpdesk/tickets/{id}/comments", ticketId)
                        .header("Authorization", bearer(requesterId, "REQUESTER")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode requesterComments = objectMapper.readTree(requesterView.getResponse().getContentAsString());
        assertThat(requesterComments).hasSize(1);
        assertThat(requesterComments.get(0).get("body").asText()).isEqualTo("Public update");

        // The agent sees both.
        MvcResult agentView = mockMvc.perform(get("/api/helpdesk/tickets/{id}/comments", ticketId)
                        .header("Authorization", bearer(agentId, "AGENT")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode agentComments = objectMapper.readTree(agentView.getResponse().getContentAsString());
        assertThat(agentComments).hasSize(2);
    }

    @Test
    void requesterCannotMarkTheirOwnCommentInternal() throws Exception {
        String requesterId = uniqueUserId("requester");
        long ticketId = createTicket(requesterId);

        mockMvc.perform(post("/api/helpdesk/tickets/{id}/comments", ticketId)
                        .header("Authorization", bearer(requesterId, "REQUESTER"))
                        .contentType("application/json")
                        .content("{\"body\": \"I am trying to hide this\", \"isInternal\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isInternal").value(false));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createTicket(String requesterId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/helpdesk/tickets")
                        .header("Authorization", bearer(requesterId, "REQUESTER"))
                        .contentType("application/json")
                        .content("""
                                {"typeId": %d, "title": "Comment visibility test", "description": "desc", "priority": "P3"}
                                """.formatted(INCIDENT_TYPE_ID)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private String staffInGeneralSupport(String prefix) throws Exception {
        String login = uniqueUserId(prefix);
        long id = objectMapper.readTree(mockMvc.perform(get("/api/helpdesk/me").header("Authorization", bearer(login, "AGENT")))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(post("/api/helpdesk/departments/1/members")
                        .header("Authorization", bearer(uniqueUserId("setup-admin"), "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + id + ", \"isLead\": false}"))
                .andExpect(status().isCreated());
        return login;
    }

    private String uniqueUserId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtService.generateToken(userId, userId + "@example.com", userId, Set.of(role));
    }
}
