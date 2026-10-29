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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end ticket lifecycle tests against the real, Flyway-migrated
 * {@code ticketing_test} database via MockMvc. Relies only on Flyway-seeded
 * reference data (ticket type id 1 = Incident, the "General Support" catch-all
 * routing rule, the "Default SLA" wildcard policy) - every Ticket/User row the
 * tests themselves need is created through the API inside the per-test
 * rolled-back transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TicketControllerIT {

    private static final Long INCIDENT_TYPE_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void createReturnsCreatedWithGeneratedTicketNumberAndDefaultRouting() throws Exception {
        String requesterId = uniqueUserId("requester");

        MvcResult result = mockMvc.perform(post("/api/helpdesk/tickets")
                        .header("Authorization", bearer(requesterId, "REQUESTER"))
                        .contentType("application/json")
                        .content("""
                                {"typeId": %d, "title": "Printer is on fire", "description": "Send help",
                                 "priority": "P3"}
                                """.formatted(INCIDENT_TYPE_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.priority").value("P3"))
                .andExpect(jsonPath("$.typeId").value(INCIDENT_TYPE_ID))
                // The catch-all routing rule (V4__routing_rules.sql) routes every
                // ticket with no more specific match to the seeded "General Support" department.
                .andExpect(jsonPath("$.departmentId").value(1))
                .andExpect(jsonPath("$.departmentName").value("General Support"))
                .andReturn();

        JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString());
        String ticketNumber = created.get("ticketNumber").asText();
        int currentYear = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
        assertThat(ticketNumber).matches("[A-Z0-9]{2,5}-" + currentYear + "-\\d{6}");
    }

    @Test
    void getReturnsTheCreatedTicket() throws Exception {
        String requesterId = uniqueUserId("requester");
        long ticketId = createTicket(requesterId, "REQUESTER", "P2");

        mockMvc.perform(get("/api/helpdesk/tickets/{id}", ticketId)
                        .header("Authorization", bearer(requesterId, "REQUESTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.priority").value("P2"));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        String agentId = uniqueUserId("agent");
        long newTicketId = createTicket(agentId, "AGENT", "P4");

        mockMvc.perform(get("/api/helpdesk/tickets")
                        .param("status", "NEW")
                        .header("Authorization", bearer(agentId, "AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + newTicketId + ")]").exists());

        // Sanity check: filtering by a status the ticket is NOT in excludes it.
        mockMvc.perform(get("/api/helpdesk/tickets")
                        .param("status", "CLOSED")
                        .header("Authorization", bearer(agentId, "AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + newTicketId + ")]").doesNotExist());
    }

    @Test
    void validStatusTransitionSucceeds() throws Exception {
        String agentId = uniqueUserId("agent");
        long ticketId = createTicket(agentId, "AGENT", "P3");

        mockMvc.perform(put("/api/helpdesk/tickets/{id}", ticketId)
                        .header("Authorization", bearer(agentId, "AGENT"))
                        .contentType("application/json")
                        .content("{\"status\": \"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void invalidStatusTransitionReturns400WithTransitionInMessage() throws Exception {
        String agentId = uniqueUserId("agent");
        long ticketId = createTicket(agentId, "AGENT", "P3");

        // NEW -> CLOSED is not in TicketService.ALLOWED_TRANSITIONS.
        mockMvc.perform(put("/api/helpdesk/tickets/{id}", ticketId)
                        .header("Authorization", bearer(agentId, "AGENT"))
                        .contentType("application/json")
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NEW")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CLOSED")));
    }

    @Test
    void requesterOnlyCallerGets404OnAnotherRequesterTicket() throws Exception {
        String requesterA = uniqueUserId("requester-a");
        String requesterB = uniqueUserId("requester-b");
        long ticketId = createTicket(requesterA, "REQUESTER", "P3");

        mockMvc.perform(get("/api/helpdesk/tickets/{id}", ticketId)
                        .header("Authorization", bearer(requesterB, "REQUESTER")))
                .andExpect(status().isNotFound());

        // The owning requester can still see it.
        mockMvc.perform(get("/api/helpdesk/tickets/{id}", ticketId)
                        .header("Authorization", bearer(requesterA, "REQUESTER")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createTicket(String userId, String role, String priority) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/helpdesk/tickets")
                        .header("Authorization", bearer(userId, role))
                        .contentType("application/json")
                        .content("""
                                {"typeId": %d, "title": "Test ticket", "description": "desc", "priority": "%s"}
                                """.formatted(INCIDENT_TYPE_ID, priority)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private String uniqueUserId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtService.generateToken(userId, userId + "@example.com", userId, Set.of(role));
    }
}
