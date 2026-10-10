package com.erp.ticketing.notification.controller;

import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.model.DepartmentMember;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.security.JwtService;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that creating a ticket which routes to the seeded "General
 * Support" department (id 1, via the catch-all routing rule in
 * V4__routing_rules.sql) produces a TICKET_CREATED in-app notification for
 * each of that department's members. Run against the real, Flyway-migrated
 * {@code ticketing_test} database.
 *
 * <p>The department member is added via direct repository access rather than
 * {@code POST /api/helpdesk/departments/1/members} - both are equally valid inside
 * this {@code @Transactional} test scope, and going direct avoids a second,
 * unrelated user-provisioning round-trip through the API.
 *
 * <p>The member's {@code User} row itself, however, is provisioned via a
 * real authenticated request rather than a direct {@code userRepository.save},
 * and deliberately before anything else touches that erp user id. Reason:
 * {@code UserService.getOrCreateCurrentUser} runs {@code REQUIRES_NEW} - a
 * separate DB transaction/connection - and under READ COMMITTED it cannot
 * see a same-erp-user-id row this test's own (still open) outer transaction
 * inserted directly. If it were pre-seeded that way, the later
 * {@code GET /api/helpdesk/notifications} call as that same user would have its
 * REQUIRES_NEW lookup miss, attempt to re-insert the same erp_user_id, and
 * block forever on the uncommitted row from the outer transaction - a
 * genuine self-deadlock (reproduced while writing this test). Warming the
 * user up through a real request first lets that REQUIRES_NEW transaction
 * commit independently, so the row is fully visible before it matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationControllerIT {

    private static final Long INCIDENT_TYPE_ID = 1L;
    private static final Long GENERAL_SUPPORT_DEPARTMENT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DepartmentMemberRepository departmentMemberRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    void ticketCreatedRoutedToDepartmentNotifiesDepartmentMember() throws Exception {
        String memberErpId = uniqueUserId("department-member");

        // Warm-up request: provisions (and commits, via getOrCreateCurrentUser's
        // own REQUIRES_NEW transaction) the member's User row for real, before
        // this test's own transaction touches that erp user id at all. See the
        // class Javadoc for why this ordering matters.
        mockMvc.perform(get("/api/helpdesk/tickets")
                        .header("Authorization", bearer(memberErpId, "AGENT")))
                .andExpect(status().isOk());

        User member = userRepository.findByErpUserId(memberErpId).orElseThrow();

        Department generalSupport = departmentRepository.findById(GENERAL_SUPPORT_DEPARTMENT_ID).orElseThrow();
        DepartmentMember departmentMember = new DepartmentMember();
        departmentMember.getId().setDepartmentId(generalSupport.getId());
        departmentMember.getId().setUserId(member.getId());
        departmentMember.setDepartment(generalSupport);
        departmentMember.setUser(member);
        departmentMember.setLead(false);
        departmentMemberRepository.save(departmentMember);

        // Create a ticket with no more specific routing match, so the
        // catch-all rule routes it (department-only, no pre-assigned agent) to
        // "General Support" - see TicketService.notifyOnCreate, which then
        // notifies every member of that department.
        String requesterId = uniqueUserId("requester");
        mockMvc.perform(post("/api/helpdesk/tickets")
                        .header("Authorization", bearer(requesterId, "REQUESTER"))
                        .contentType("application/json")
                        .content("""
                                {"typeId": %d, "title": "Needs a home team", "description": "desc", "priority": "P3"}
                                """.formatted(INCIDENT_TYPE_ID)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/helpdesk/notifications")
                        .header("Authorization", bearer(memberErpId, "AGENT")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode notifications = objectMapper.readTree(result.getResponse().getContentAsString());
        boolean hasTicketCreated = false;
        for (JsonNode notification : notifications) {
            if ("TICKET_CREATED".equals(notification.get("eventType").asText())) {
                hasTicketCreated = true;
                assertThat(notification.get("type").asText()).isEqualTo("IN_APP");
                assertThat(notification.get("isRead").asBoolean()).isFalse();
            }
        }
        assertThat(hasTicketCreated).as("expected a TICKET_CREATED notification for the routed department member").isTrue();
    }

    private String uniqueUserId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtService.generateToken(userId, userId + "@example.com", userId, Set.of(role));
    }
}
