package com.erp.ticketing.enterprise;

import com.erp.ticketing.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks of the enterprise-spec behaviours (acceptance scenarios AT-002, AT-004,
 * AT-005, AT-009, AT-010, AT-011, AT-015, AT-018, AT-019) against the real Flyway-migrated database.
 * Each test runs in a rolled-back transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EnterpriseFlowsIT {

    private static final long INCIDENT = 1L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JwtService jwt;

    // ------------------------------------------------------------ confidentiality

    @Test
    void confidentialTicketIsInvisibleToStaffAndAdminsWithoutAGrant() throws Exception {
        String admin = user("admin");
        long typeId = createType(admin, "Confidential grievance " + UUID.randomUUID(), "CONFIDENTIAL");
        String requester = user("req");
        String agent = staff("agent");

        long ticketId = createTicket(requester, "REQUESTER", typeId, "P3");

        mvc.perform(get("/api/helpdesk/tickets/{id}", ticketId).header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confidentiality").value("CONFIDENTIAL"));
        // AT-004: concealed by id, and absent from list/search.
        mvc.perform(get("/api/helpdesk/tickets/{id}", ticketId).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/helpdesk/tickets/{id}", ticketId).header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/helpdesk/tickets").param("search", "Test ticket")
                        .header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + ticketId + ")]").doesNotExist());
        mvc.perform(get("/api/helpdesk/tickets/{id}/comments", ticketId).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void requesterCannotHideTheirOwnTicketByClassifyingItConfidential() throws Exception {
        String requester = user("req");
        long id = createTicketWith(requester, "REQUESTER", """
                {"typeId": %d, "title": "t", "priority": "P3", "confidentiality": "CONFIDENTIAL"}""".formatted(INCIDENT));
        mvc.perform(get("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(staff("agent"), "AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confidentiality").value("STANDARD"));
    }

    @Test
    void agentsOnlySeeStandardTicketsOfTheirDepartmentsAndAdminsSeeAll() throws Exception {
        String requester = user("req");
        long id = createTicket(requester, "REQUESTER", INCIDENT, "P3");
        String member = staff("member");
        String outsider = user("outsider");
        String admin = user("admin");

        mvc.perform(get("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(member, "AGENT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(outsider, "AGENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/helpdesk/tickets").header("Authorization", bearer(outsider, "AGENT")))
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").doesNotExist());
        mvc.perform(get("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ attachments

    @Test
    void attachmentCannotBeDownloadedByAnotherRequester() throws Exception {
        String owner = user("owner");
        String other = user("other");
        long ticketId = createTicket(owner, "REQUESTER", INCIDENT, "P3");

        String body = mvc.perform(multipart("/api/helpdesk/tickets/{id}/attachments", ticketId)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", bearer(owner, "REQUESTER")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long attachmentId = json.readTree(body).get("id").asLong();

        mvc.perform(get("/api/helpdesk/attachments/{id}/download", attachmentId)
                        .header("Authorization", bearer(other, "REQUESTER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/helpdesk/attachments/{id}/download", attachmentId)
                        .header("Authorization", bearer(owner, "REQUESTER")))
                .andExpect(status().isOk());
    }

    @Test
    void unsafeFileTypesAreRejected() throws Exception {
        String owner = user("owner");
        long ticketId = createTicket(owner, "REQUESTER", INCIDENT, "P3");
        mvc.perform(multipart("/api/helpdesk/tickets/{id}/attachments", ticketId)
                        .file(new MockMultipartFile("file", "run.exe", "application/octet-stream", "MZ".getBytes()))
                        .header("Authorization", bearer(owner, "REQUESTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not allowed")));
    }

    // ------------------------------------------------------------ lifecycle rules

    @Test
    void staleVersionIsRejectedWithConflict() throws Exception {
        String agent = staff("agent");
        long id = createTicket(agent, "AGENT", INCIDENT, "P3");
        long version = ticket(agent, "AGENT", id).get("version").asLong();

        update(agent, "AGENT", id, "{\"title\": \"first edit\", \"version\": " + version + "}").andExpect(status().isOk());
        // Same stale version again: AT-018, no silent overwrite.
        update(agent, "AGENT", id, "{\"title\": \"second edit\", \"version\": " + version + "}")
                .andExpect(status().isConflict());
    }

    @Test
    void resolvingNeedsCodeAndSummaryAndReopeningIsTracked() throws Exception {
        String agent = staff("agent");
        long id = createTicket(agent, "AGENT", INCIDENT, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());

        update(agent, "AGENT", id, "{\"status\": \"RESOLVED\"}").andExpect(status().isBadRequest());
        update(agent, "AGENT", id, "{\"status\": \"RESOLVED\", \"resolutionCode\": \"FIXED\", \"resolutionSummary\": \"Rebooted\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionCode").value("FIXED"))
                .andExpect(jsonPath("$.resolvedByName").value(agent));
        update(agent, "AGENT", id, "{\"status\": \"REOPENED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reopenCount").value(1))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    @Test
    void requesterCannotChangePriorityOrAssignee() throws Exception {
        String req = user("req");
        long id = createTicket(req, "REQUESTER", INCIDENT, "P3");
        update(req, "REQUESTER", id, "{\"priority\": \"P1\"}").andExpect(status().isBadRequest());
        update(req, "REQUESTER", id, "{\"status\": \"CANCELLED\", \"reason\": \"no longer needed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void assignmentChangesAreRecordedWithActorAndReason() throws Exception {
        String agent = staff("agent");
        String other = staff("other-agent");
        long id = createTicket(agent, "AGENT", INCIDENT, "P3");
        long otherId = me(other, "AGENT");

        update(agent, "AGENT", id, "{\"assigneeId\": " + otherId + ", \"reason\": \"specialist needed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));

        mvc.perform(get("/api/helpdesk/tickets/{id}/assignments", id).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[-1:].reason").value("specialist needed"))
                .andExpect(jsonPath("$[-1:].actor").value(agent))
                .andExpect(jsonPath("$[-1:].toAssignee").value(other));
    }

    // ------------------------------------------------------------ approvals

    @Test
    void approvalGateBlocksProgressUntilApproved() throws Exception {
        String admin = user("admin");
        String agent = staff("agent");
        String approver = user("approver");
        String requester = user("req");
        long typeId = createType(admin, "Purchase " + UUID.randomUUID(), "STANDARD");
        createPolicy(admin, typeId, "SINGLE", me(approver, "REQUESTER"));

        long id = createTicket(requester, "REQUESTER", typeId, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(agent, "AGENT", id, "{\"status\": \"WAITING_APPROVAL\"}").andExpect(status().isOk());

        // AT-009: fulfilment is blocked while the decision is pending.
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Pending approvals")));

        JsonNode pending = pendingFor(approver, "REQUESTER");
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).get("requesterName").asText()).isEqualTo(requester);

        decide(approver, "REQUESTER", pending.get(0).get("id").asLong(), "APPROVE", "ok").andExpect(status().isOk());
        assertThat(ticket(agent, "AGENT", id).get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void requesterCannotApproveTheirOwnRequest() throws Exception {
        String admin = user("admin");
        String agent = staff("agent");
        String requester = user("req");
        long typeId = createType(admin, "SelfApprove " + UUID.randomUUID(), "STANDARD");
        // The requester is (mis)configured as their own approver: AT-010 must still hold.
        createPolicy(admin, typeId, "SINGLE", me(requester, "REQUESTER"));

        long id = createTicket(requester, "REQUESTER", typeId, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(agent, "AGENT", id, "{\"status\": \"WAITING_APPROVAL\"}").andExpect(status().isOk());

        long taskId = pendingFor(requester, "REQUESTER").get(0).get("id").asLong();
        decide(requester, "REQUESTER", taskId, "APPROVE", "me").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("own request")));
    }

    @Test
    void rejectionCancelsTheTicketAndNeedsAComment() throws Exception {
        String admin = user("admin");
        String agent = staff("agent");
        String approver = user("approver");
        String requester = user("req");
        long typeId = createType(admin, "Reject " + UUID.randomUUID(), "STANDARD");
        createPolicy(admin, typeId, "SINGLE", me(approver, "REQUESTER"));
        long id = createTicket(requester, "REQUESTER", typeId, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}");
        update(agent, "AGENT", id, "{\"status\": \"WAITING_APPROVAL\"}");
        long taskId = pendingFor(approver, "REQUESTER").get(0).get("id").asLong();

        decide(approver, "REQUESTER", taskId, "REJECT", "").andExpect(status().isBadRequest());
        decide(approver, "REQUESTER", taskId, "REJECT", "over budget").andExpect(status().isOk());
        assertThat(ticket(agent, "AGENT", id).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void sequentialApprovalReleasesStepsInOrder() throws Exception {
        String admin = user("admin");
        String agent = staff("agent");
        String first = user("approver1");
        String second = user("approver2");
        String requester = user("req");
        long typeId = createType(admin, "Sequential " + UUID.randomUUID(), "STANDARD");
        mvc.perform(post("/api/helpdesk/admin/approval-policies").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("""
                                {"name": "two-step", "ticketTypeId": %d, "mode": "SEQUENTIAL",
                                 "steps": [{"stepOrder": 1, "approverType": "USER", "approverId": %d},
                                           {"stepOrder": 2, "approverType": "USER", "approverId": %d}]}"""
                                .formatted(typeId, me(first, "REQUESTER"), me(second, "REQUESTER"))))
                .andExpect(status().isCreated());

        long id = createTicket(requester, "REQUESTER", typeId, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}");
        update(agent, "AGENT", id, "{\"status\": \"WAITING_APPROVAL\"}");

        assertThat(pendingFor(second, "REQUESTER").size()).isZero();
        decide(first, "REQUESTER", pendingFor(first, "REQUESTER").get(0).get("id").asLong(), "APPROVE", "ok")
                .andExpect(status().isOk());
        assertThat(ticket(agent, "AGENT", id).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        JsonNode secondPending = pendingFor(second, "REQUESTER");
        assertThat(secondPending.size()).isEqualTo(1);
        decide(second, "REQUESTER", secondPending.get(0).get("id").asLong(), "APPROVE", "ok").andExpect(status().isOk());
        assertThat(ticket(agent, "AGENT", id).get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void strangersCannotDecideSomeoneElsesApproval() throws Exception {
        String admin = user("admin");
        String agent = staff("agent");
        String approver = user("approver");
        String stranger = user("stranger");
        String requester = user("req");
        long typeId = createType(admin, "Stranger " + UUID.randomUUID(), "STANDARD");
        createPolicy(admin, typeId, "SINGLE", me(approver, "REQUESTER"));
        long id = createTicket(requester, "REQUESTER", typeId, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}");
        update(agent, "AGENT", id, "{\"status\": \"WAITING_APPROVAL\"}");
        long taskId = pendingFor(approver, "REQUESTER").get(0).get("id").asLong();

        decide(stranger, "REQUESTER", taskId, "APPROVE", "sneaky").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ SLA

    @Test
    void waitingOnRequesterPausesTheSlaClockAndResumeShiftsTheDeadline() throws Exception {
        String agent = staff("agent");
        long id = createTicket(agent, "AGENT", INCIDENT, "P3");
        Instant originalDue = Instant.parse(ticket(agent, "AGENT", id).get("resolutionDue").asText());
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}");

        update(agent, "AGENT", id, "{\"status\": \"WAITING_CUSTOMER\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.slaStatus").value("PAUSED"));
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.slaStatus").value(org.hamcrest.Matchers.not("PAUSED")));

        Instant newDue = Instant.parse(ticket(agent, "AGENT", id).get("resolutionDue").asText());
        // The deadline is rebuilt from the frozen remainder, so it can only move later or stay put.
        assertThat(newDue).isAfterOrEqualTo(originalDue.minus(2, ChronoUnit.MINUTES));
    }

    // ------------------------------------------------------------ catalog & forms

    @Test
    void publishedFormIsEnforcedServerSideAndRetiredServicesRefuseNewRequests() throws Exception {
        String admin = user("admin");
        String requester = user("req");
        long typeId = createType(admin, "Form svc " + UUID.randomUUID(), "STANDARD");

        String draft = mvc.perform(post("/api/helpdesk/admin/ticket-types/{id}/forms", typeId)
                        .header("Authorization", bearer(admin, "ADMIN")).contentType("application/json").content("""
                                {"schema": {"fields": [
                                  {"id": "app", "label": "Application", "type": "text", "required": true},
                                  {"id": "env", "label": "Environment", "type": "select", "required": true,
                                   "options": [{"value": "prod", "label": "Prod"}, {"value": "uat", "label": "UAT"}]}]}}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long formId = json.readTree(draft).get("id").asLong();
        mvc.perform(post("/api/helpdesk/admin/forms/{id}/publish", formId).header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));

        // AT-002: nothing is created and every bad field is reported.
        mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(requester, "REQUESTER"))
                        .contentType("application/json").content("""
                                {"typeId": %d, "title": "x", "priority": "P3", "customFields": {"env": "dev", "extra": 1}}"""
                                .formatted(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.app").exists())
                .andExpect(jsonPath("$.fieldErrors.env").exists())
                .andExpect(jsonPath("$.fieldErrors.extra").value("Unknown field"));

        mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(requester, "REQUESTER"))
                        .contentType("application/json").content("""
                                {"typeId": %d, "title": "x", "priority": "P3", "customFields": {"app": "Billing", "env": "uat"}}"""
                                .formatted(typeId)))
                .andExpect(status().isCreated());

        // AT-019: retire the service, new requests are blocked.
        mvc.perform(put("/api/helpdesk/admin/ticket-types/{id}", typeId).header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("{\"lifecycleStatus\": \"RETIRED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(requester, "REQUESTER"))
                        .contentType("application/json").content("""
                                {"typeId": %d, "title": "x", "priority": "P3", "customFields": {"app": "Billing", "env": "uat"}}"""
                                .formatted(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not accepting")));
        // ...and requesters no longer see it in the catalog, while admins still do.
        mvc.perform(get("/api/helpdesk/ticket-types").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(jsonPath("$[?(@.id == " + typeId + ")]").doesNotExist());
        mvc.perform(get("/api/helpdesk/ticket-types").header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(jsonPath("$[?(@.id == " + typeId + ")]").exists());
    }

    // ------------------------------------------------------------ workflow & audit

    @Test
    void workflowAdminRejectsUnreachableStatesAndChangesAreAudited() throws Exception {
        String admin = user("admin");
        mvc.perform(put("/api/helpdesk/admin/workflow").param("ticketTypeId", String.valueOf(INCIDENT))
                        .header("Authorization", bearer(admin, "ADMIN")).contentType("application/json")
                        .content("""
                                {"transitions": [{"from": "NEW", "to": "IN_PROGRESS"}, {"from": "IN_PROGRESS", "to": "CLOSED"},
                                                 {"from": "TRIAGE", "to": "ASSIGNED"}]}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("unreachable")));

        mvc.perform(put("/api/helpdesk/admin/workflow").param("ticketTypeId", String.valueOf(INCIDENT))
                        .header("Authorization", bearer(admin, "ADMIN")).contentType("application/json")
                        .content("""
                                {"transitions": [{"from": "NEW", "to": "IN_PROGRESS"}, {"from": "IN_PROGRESS", "to": "CLOSED"}]}"""))
                .andExpect(status().isOk());

        // The new rules apply immediately: NEW -> TRIAGE is no longer allowed for this type.
        String agent = staff("agent");
        long id = createTicket(agent, "AGENT", INCIDENT, "P3");
        update(agent, "AGENT", id, "{\"status\": \"TRIAGE\"}").andExpect(status().isBadRequest());
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());

        mvc.perform(get("/api/helpdesk/admin/audit").param("action", "WORKFLOW_SAVED")
                        .header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("WORKFLOW_SAVED"))
                .andExpect(jsonPath("$.content[0].actorName").value(admin));
        // Audit is admin-only.
        mvc.perform(get("/api/helpdesk/admin/audit").header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ user directory

    @Test
    void userSearchNeedsTwoCharactersAndHidesEmailsFromNonStaff() throws Exception {
        String target = user("zelda");
        long targetId = me(target, "REQUESTER");
        String requester = user("req");

        mvc.perform(get("/api/helpdesk/users").param("q", "z").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/helpdesk/users").param("q", "zelda").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + targetId + ")]").exists())
                .andExpect(jsonPath("$[0].email").doesNotExist());
        mvc.perform(get("/api/helpdesk/users").param("q", "zelda").header("Authorization", bearer(user("agent"), "AGENT")))
                .andExpect(jsonPath("$[0].email").exists());
    }

    // ------------------------------------------------------------ automation

    @Test
    void automationRuleAppliesToMatchingTicketsOnlyAndRejectsForbiddenActions() throws Exception {
        String admin = user("admin");
        String requester = user("req");
        String marker = "vpn" + UUID.randomUUID().toString().substring(0, 8);

        // Resolving or closing is not an automation action.
        mvc.perform(post("/api/helpdesk/admin/automation-rules").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("""
                                {"name": "bad", "trigger": "TICKET_CREATED", "actions": [{"type": "CHANGE_STATUS", "value": "RESOLVED"}]}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("not allowed")));

        String created = mvc.perform(post("/api/helpdesk/admin/automation-rules").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("""
                                {"name": "vpn to critical", "trigger": "TICKET_CREATED",
                                 "conditions": {"textContains": "%s"},
                                 "actions": [{"type": "SET_PRIORITY", "value": "P1"}, {"type": "ADD_TAG", "tag": "network"}]}"""
                                .formatted(marker)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(false))
                .andReturn().getResponse().getContentAsString();
        long ruleId = json.readTree(created).get("id").asLong();

        // Disabled rules do nothing.
        long before = createTicketWith(requester, "REQUESTER", """
                {"typeId": %d, "title": "%s outage", "priority": "P4"}""".formatted(INCIDENT, marker));
        assertThat(ticket(requester, "REQUESTER", before).get("priority").asText()).isEqualTo("P4");

        // Dry run reports the effect without applying it.
        mvc.perform(post("/api/helpdesk/admin/automation-rules/{id}/dry-run", ruleId).param("ticketId", String.valueOf(before))
                        .header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches").value(true))
                .andExpect(jsonPath("$.wouldApply.length()").value(2));
        assertThat(ticket(requester, "REQUESTER", before).get("priority").asText()).isEqualTo("P4");

        mvc.perform(post("/api/helpdesk/admin/automation-rules/{id}/enable", ruleId).header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));

        long hit = createTicketWith(requester, "REQUESTER", """
                {"typeId": %d, "title": "%s outage", "priority": "P4"}""".formatted(INCIDENT, marker));
        long miss = createTicketWith(requester, "REQUESTER", """
                {"typeId": %d, "title": "printer jam", "priority": "P4"}""".formatted(INCIDENT));
        assertThat(ticket(requester, "REQUESTER", hit).get("priority").asText()).isEqualTo("P1");
        assertThat(ticket(requester, "REQUESTER", hit).get("tags").has("network")).isTrue();
        assertThat(ticket(requester, "REQUESTER", miss).get("priority").asText()).isEqualTo("P4");

        mvc.perform(get("/api/helpdesk/admin/automation-rules/{id}/runs", ruleId).header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.result == 'APPLIED' && @.dryRun == false)]").exists());
        mvc.perform(get("/api/helpdesk/admin/automation-rules").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void automationRespectsThePerTicketRunLimit() throws Exception {
        String admin = user("admin");
        String requester = user("req");
        mvc.perform(post("/api/helpdesk/admin/automation-rules").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("""
                                {"name": "tag on reply", "trigger": "COMMENT_ADDED", "enabled": true, "maxRunsPerTicket": 1,
                                 "conditions": {"byRequester": true}, "actions": [{"type": "ADD_TAG", "tag": "replied"}]}"""))
                .andExpect(status().isCreated());
        long ruleId = lastRuleId(admin);

        long id = createTicket(requester, "REQUESTER", INCIDENT, "P3");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/helpdesk/tickets/{id}/comments", id).header("Authorization", bearer(requester, "REQUESTER"))
                            .contentType("application/json").content("{\"body\": \"any update?\"}"))
                    .andExpect(status().isCreated());
        }
        JsonNode runs = json.readTree(mvc.perform(get("/api/helpdesk/admin/automation-rules/{id}/runs", ruleId)
                .header("Authorization", bearer(admin, "ADMIN"))).andReturn().getResponse().getContentAsString());
        long applied = 0;
        long skipped = 0;
        for (JsonNode run : runs) {
            if (run.get("ticketId").asLong() != id) {
                continue;
            }
            applied += "APPLIED".equals(run.get("result").asText()) ? 1 : 0;
            skipped += "SKIPPED_LIMIT".equals(run.get("result").asText()) ? 1 : 0;
        }
        assertThat(applied).isEqualTo(1);
        assertThat(skipped).isEqualTo(1);
    }

    private long lastRuleId(String admin) throws Exception {
        JsonNode rules = json.readTree(mvc.perform(get("/api/helpdesk/admin/automation-rules")
                .header("Authorization", bearer(admin, "ADMIN"))).andReturn().getResponse().getContentAsString());
        return rules.get(rules.size() - 1).get("id").asLong();
    }

    // ------------------------------------------------------------ exports

    @Test
    void exportOmitsTicketsTheCallerCannotSeeAndIsAudited() throws Exception {
        String admin = user("admin");
        long confidentialType = createType(admin, "Secret " + UUID.randomUUID(), "CONFIDENTIAL");
        String requester = user("req");
        long secret = createTicket(requester, "REQUESTER", confidentialType, "P3");
        long open = createTicket(requester, "REQUESTER", INCIDENT, "P3");
        String secretNumber = ticket(requester, "REQUESTER", secret).get("ticketNumber").asText();
        String openNumber = ticket(requester, "REQUESTER", open).get("ticketNumber").asText();
        String agent = staff("agent");

        String csv = mvc.perform(get("/api/helpdesk/reports/tickets/export.csv")
                        .param("ticketIds", secret + "," + open).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(openNumber).doesNotContain(secretNumber);

        mvc.perform(get("/api/helpdesk/admin/audit").param("action", "TICKETS_EXPORTED").header("Authorization", bearer(admin, "ADMIN")))
                .andExpect(jsonPath("$.content[0].actorName").value(agent))
                .andExpect(jsonPath("$.content[0].after.rows").value(1));
    }

    // ------------------------------------------------------------ KPIs

    @Test
    void kpisCoverVisibleTicketsAndCarryTheirDefinitions() throws Exception {
        String agent = staff("agent");
        String requester = user("req");
        long id = createTicket(requester, "REQUESTER", INCIDENT, "P3");
        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        mvc.perform(post("/api/helpdesk/tickets/{id}/comments", id).header("Authorization", bearer(agent, "AGENT"))
                        .contentType("application/json").content("{\"body\": \"looking\"}")).andExpect(status().isCreated());
        update(agent, "AGENT", id, "{\"status\": \"RESOLVED\", \"resolutionCode\": \"FIXED\", \"resolutionSummary\": \"ok\"}")
                .andExpect(status().isOk());

        mvc.perform(get("/api/helpdesk/reports/kpis").header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketsInPeriod").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.firstResponseAvgMinutes").isNumber())
                .andExpect(jsonPath("$.resolutionAvgMinutes").isNumber())
                .andExpect(jsonPath("$.definitions.slaAchievementPercent").isString())
                .andExpect(jsonPath("$.definitions.backlog").isString());
        mvc.perform(get("/api/helpdesk/reports/kpis").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ problems, changes, incidents

    @Test
    void problemLifecycleEnforcesWorkaroundAndRootCauseAndHidesTicketsFromWrongPeople() throws Exception {
        String admin = user("admin");
        String agent = staff("agent");
        String requester = user("req");
        long openTicket = createTicket(requester, "REQUESTER", INCIDENT, "P3");
        long secretType = createType(admin, "Hidden " + UUID.randomUUID(), "CONFIDENTIAL");
        long secretTicket = createTicket(requester, "REQUESTER", secretType, "P3");

        String body = mvc.perform(post("/api/helpdesk/problems").header("Authorization", bearer(agent, "AGENT"))
                        .contentType("application/json").content("{\"title\": \"Recurring VPN drops\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.problemNumber").value(containsString("PRB-")))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();

        problemStatus(agent, id, "INVESTIGATING").andExpect(status().isOk());
        problemStatus(agent, id, "KNOWN_ERROR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("workaround")));
        mvc.perform(put("/api/helpdesk/problems/{id}", id).header("Authorization", bearer(agent, "AGENT"))
                        .contentType("application/json").content("{\"workaround\": \"Reconnect via the backup gateway\"}"))
                .andExpect(status().isOk());
        problemStatus(agent, id, "KNOWN_ERROR").andExpect(status().isOk());
        problemStatus(agent, id, "RESOLVED").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("root cause")));

        mvc.perform(post("/api/helpdesk/problems/{id}/tickets/{t}", id, openTicket).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linkedTicketCount").value(1));
        // A confidential ticket cannot be pulled into a problem by someone who cannot see it.
        mvc.perform(post("/api/helpdesk/problems/{id}/tickets/{t}", id, secretTicket).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/helpdesk/problems").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions problemStatus(String agent, long id, String status) throws Exception {
        return mvc.perform(post("/api/helpdesk/problems/{id}/status", id).header("Authorization", bearer(agent, "AGENT"))
                .contentType("application/json").content("{\"status\": \"" + status + "\"}"));
    }

    @Test
    void changeNeedsCompletePlansIndependentApprovalAndRefusesOverlappingSchedules() throws Exception {
        String owner = user("manager-owner");
        String approver = user("manager-approver");
        String service = "svc-" + UUID.randomUUID().toString().substring(0, 6);

        long a = createChange(owner, "Upgrade " + service, "NORMAL", service);
        mvc.perform(post("/api/helpdesk/changes/{id}/submit", a).header("Authorization", bearer(owner, "MANAGER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("test plan")));
        mvc.perform(put("/api/helpdesk/changes/{id}", a).header("Authorization", bearer(owner, "MANAGER"))
                        .contentType("application/json").content("{\"testPlan\": \"Smoke tests in UAT\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/helpdesk/changes/{id}/submit", a).header("Authorization", bearer(owner, "MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Segregation of duties: the owner cannot approve their own change; scheduling needs approval first.
        decideChange(owner, a, true, "ok").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("own change")));
        schedule(owner, a, 2, 4).andExpect(status().isBadRequest());
        decideChange(approver, a, false, "").andExpect(status().isBadRequest());
        decideChange(approver, a, true, "Looks safe").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approvedByName").value(approver));
        schedule(owner, a, 2, 4).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));

        // A second change on the same service in an overlapping window is refused; a later window is fine.
        long b = createChange(owner, "Patch " + service, "STANDARD", service);
        mvc.perform(put("/api/helpdesk/changes/{id}", b).header("Authorization", bearer(owner, "MANAGER"))
                        .contentType("application/json").content("{\"testPlan\": \"t\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/helpdesk/changes/{id}/submit", b).header("Authorization", bearer(owner, "MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        schedule(owner, b, 3, 5).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("CHG-")));
        schedule(owner, b, 10, 12).andExpect(status().isOk());

        // Execution: the outcome is mandatory.
        mvc.perform(post("/api/helpdesk/changes/{id}/start", a).header("Authorization", bearer(owner, "MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IMPLEMENTING"));
        mvc.perform(post("/api/helpdesk/changes/{id}/finish", a).header("Authorization", bearer(owner, "MANAGER"))
                        .contentType("application/json").content("{\"success\": true, \"outcome\": \"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/helpdesk/changes/{id}/finish", a).header("Authorization", bearer(owner, "MANAGER"))
                        .contentType("application/json").content("{\"success\": true, \"outcome\": \"Upgraded, no issues\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/api/helpdesk/changes/calendar").header("Authorization", bearer(owner, "MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + b + ")]").exists());
    }

    private long createChange(String owner, String title, String type, String service) throws Exception {
        String body = mvc.perform(post("/api/helpdesk/changes").header("Authorization", bearer(owner, "MANAGER"))
                        .contentType("application/json").content("""
                                {"title": "%s", "changeType": "%s", "reason": "Security fix", "scope": "Production",
                                 "risk": "LOW", "impact": "MEDIUM", "affectedServices": "%s",
                                 "implementationPlan": "Deploy in window", "rollbackPlan": "Redeploy previous version"}"""
                                .formatted(title, type, service)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.changeNumber").value(containsString("CHG-")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions decideChange(String who, long id, boolean approve, String comment) throws Exception {
        return mvc.perform(post("/api/helpdesk/changes/{id}/decision", id).header("Authorization", bearer(who, "MANAGER"))
                .contentType("application/json").content("{\"approve\": " + approve + ", \"comment\": \"" + comment + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions schedule(String who, long id, int startHours, int endHours) throws Exception {
        Instant start = Instant.now().plus(startHours, ChronoUnit.HOURS);
        return mvc.perform(post("/api/helpdesk/changes/{id}/schedule", id).header("Authorization", bearer(who, "MANAGER"))
                .contentType("application/json").content("{\"start\": \"" + start + "\", \"end\": \""
                        + Instant.now().plus(endHours, ChronoUnit.HOURS) + "\"}"));
    }

    @Test
    void majorIncidentNeedsAManagerSharesOnlyPublicUpdatesAndCannotCloseWithoutReview() throws Exception {
        String manager = memberOfGeneralSupport("manager", "MANAGER");
        String agent = staff("agent");
        String requester = user("req");
        long ticketId = createTicket(requester, "REQUESTER", INCIDENT, "P1");

        mvc.perform(post("/api/helpdesk/major-incidents").header("Authorization", bearer(agent, "AGENT"))
                        .contentType("application/json").content("{\"title\": \"x\", \"severity\": \"SEV1\", \"reason\": \"y\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("manager")));

        String title = "Email outage " + UUID.randomUUID().toString().substring(0, 6);
        String body = mvc.perform(post("/api/helpdesk/major-incidents").header("Authorization", bearer(manager, "MANAGER"))
                        .contentType("application/json").content("""
                                {"title": "%s", "severity": "SEV1", "reason": "Mail gateway down", "customerImpact": "No email",
                                 "updateCadenceMinutes": 15, "ticketIds": [%d]}""".formatted(title, ticketId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.incidentNumber").value(containsString("MI-")))
                .andExpect(jsonPath("$.linkedTickets.length()").value(1)).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();

        postIncidentUpdate(agent, "AGENT", id, "Investigating the gateway cluster", false);
        postIncidentUpdate(agent, "AGENT", id, "We are working on restoring email", true);

        // Requesters see the incident and only its public update.
        mvc.perform(get("/api/helpdesk/major-incidents/public").header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == '" + title + "')].updates.length()").value(1))
                .andExpect(jsonPath("$[?(@.title == '" + title + "')].updates[0].message").value("We are working on restoring email"));
        mvc.perform(get("/api/helpdesk/major-incidents/{id}", id).header("Authorization", bearer(requester, "REQUESTER")))
                .andExpect(status().isForbidden());

        incidentStatus(manager, id, "RESOLVED", null).andExpect(status().isOk());
        incidentStatus(manager, id, "CLOSED", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("post-incident review")));
        incidentStatus(manager, id, "CLOSED", "Root cause: failed certificate renewal. Add expiry monitoring.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        postIncidentUpdateRaw(manager, "MANAGER", id, "late note").andExpect(status().isBadRequest());
    }

    private void postIncidentUpdate(String who, String role, long id, String message, boolean isPublic) throws Exception {
        mvc.perform(post("/api/helpdesk/major-incidents/{id}/updates", id).header("Authorization", bearer(who, role))
                        .contentType("application/json").content("{\"message\": \"" + message + "\", \"isPublic\": " + isPublic + "}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postIncidentUpdateRaw(String who, String role, long id, String message) throws Exception {
        return mvc.perform(post("/api/helpdesk/major-incidents/{id}/updates", id).header("Authorization", bearer(who, role))
                .contentType("application/json").content("{\"message\": \"" + message + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions incidentStatus(String who, long id, String status, String review) throws Exception {
        String reviewJson = review == null ? "" : ", \"postIncidentReview\": \"" + review + "\"";
        return mvc.perform(post("/api/helpdesk/major-incidents/{id}/status", id).header("Authorization", bearer(who, "MANAGER"))
                .contentType("application/json").content("{\"status\": \"" + status + "\"" + reviewJson + "}"));
    }

    // ------------------------------------------------------------ references, views, search, service owner

    @Test
    void ticketReferencesStoreOnlyAnIdAndLabelAndFollowTicketVisibility() throws Exception {
        String owner = user("owner");
        String other = user("other");
        long id = createTicket(owner, "REQUESTER", INCIDENT, "P3");

        mvc.perform(post("/api/helpdesk/tickets/{id}/erp-links", id).header("Authorization", bearer(owner, "REQUESTER"))
                        .contentType("application/json").content("""
                                {"sourceSystem": "CMDB", "entityType": "ASSET", "entityId": "LAP-00421", "label": "Dell Latitude",
                                 "entitySnapshot": {"name": "ignored", "address": "1 Main St", "salary": 100}}"""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.label").value("Dell Latitude"))
                .andExpect(jsonPath("$.entitySnapshot.address").doesNotExist());
        // Adding the same reference again updates it rather than duplicating it.
        mvc.perform(post("/api/helpdesk/tickets/{id}/erp-links", id).header("Authorization", bearer(owner, "REQUESTER"))
                        .contentType("application/json").content("{\"sourceSystem\": \"CMDB\", \"entityType\": \"ASSET\", \"entityId\": \"LAP-00421\", \"label\": \"Dell Latitude 7440\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/helpdesk/tickets/{id}/erp-links", id).header("Authorization", bearer(owner, "REQUESTER")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].label").value("Dell Latitude 7440"));

        mvc.perform(post("/api/helpdesk/tickets/{id}/erp-links", id).header("Authorization", bearer(owner, "REQUESTER"))
                        .contentType("application/json").content("{\"entityType\": \"ASSET\", \"entityId\": \"bad id; drop\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/helpdesk/tickets/{id}/erp-links", id).header("Authorization", bearer(owner, "REQUESTER"))
                        .contentType("application/json").content("{\"entityType\": \"SECRETS\", \"entityId\": \"x\"}"))
                .andExpect(status().isBadRequest());

        // A reference follows the ticket: other people cannot read or add to it.
        mvc.perform(get("/api/helpdesk/tickets/{id}/erp-links", id).header("Authorization", bearer(other, "REQUESTER")))
                .andExpect(status().isNotFound());
        // The selector's lookups answer with an empty list rather than an error.
        mvc.perform(get("/api/helpdesk/erp/assets").param("query", "lap").header("Authorization", bearer(owner, "REQUESTER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void changesAndProblemsCanReferenceAssetsAndOnlyStaffManageThem() throws Exception {
        String manager = user("manager");
        String agent = staff("agent");
        long changeId = createChange(manager, "Replace switch", "STANDARD", "net-" + UUID.randomUUID().toString().substring(0, 5));
        mvc.perform(post("/api/helpdesk/changes/{id}/references", changeId).header("Authorization", bearer(manager, "MANAGER"))
                        .contentType("application/json").content("{\"entityType\": \"ASSET\", \"entityId\": \"SW-77\", \"label\": \"Core switch\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/helpdesk/changes/{id}/references", changeId).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(jsonPath("$[0].entityId").value("SW-77"));
        mvc.perform(get("/api/helpdesk/changes/{id}/references", changeId).header("Authorization", bearer(user("req"), "REQUESTER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/helpdesk/problems/{id}/references", 999999).header("Authorization", bearer(agent, "AGENT"))
                        .contentType("application/json").content("{\"entityType\": \"ASSET\", \"entityId\": \"X\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void savedViewsKeepOnlyListFiltersAndSharingNeedsAManager() throws Exception {
        String agent = staff("agent");
        String manager = memberOfGeneralSupport("manager", "MANAGER");
        String name = "My urgent " + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(post("/api/helpdesk/views").header("Authorization", bearer(agent, "AGENT")).contentType("application/json")
                        .content("{\"name\": \"" + name + "\", \"filters\": {\"priority\": \"P1\", \"openOnly\": true, \"search\": \"\"}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.filters.priority").value("P1"))
                .andExpect(jsonPath("$.filters.search").doesNotExist());
        mvc.perform(post("/api/helpdesk/views").header("Authorization", bearer(agent, "AGENT")).contentType("application/json")
                        .content("{\"name\": \"bad\", \"filters\": {\"password\": \"x\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/helpdesk/views").header("Authorization", bearer(agent, "AGENT")).contentType("application/json")
                        .content("{\"name\": \"shared try\", \"shared\": true, \"filters\": {\"priority\": \"P2\"}}"))
                .andExpect(status().isBadRequest());

        String shared = "Team queue " + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(post("/api/helpdesk/views").header("Authorization", bearer(manager, "MANAGER")).contentType("application/json")
                        .content("{\"name\": \"" + shared + "\", \"shared\": true, \"filters\": {\"unassigned\": true}}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/helpdesk/views").header("Authorization", bearer(agent, "AGENT")))
                .andExpect(jsonPath("$[?(@.name == '" + name + "')].mine").value(true))
                .andExpect(jsonPath("$[?(@.name == '" + shared + "')].mine").value(false));
        // Nobody else sees a private view.
        mvc.perform(get("/api/helpdesk/views").header("Authorization", bearer(manager, "MANAGER")))
                .andExpect(jsonPath("$[?(@.name == '" + name + "')]").doesNotExist());
    }

    @Test
    void advancedSearchFindsByDescriptionExternalReferenceAndResolutionCode() throws Exception {
        String agent = staff("agent");
        String requester = user("req");
        String word = "zebra" + UUID.randomUUID().toString().substring(0, 6);
        String ref = "CASE-" + UUID.randomUUID().toString().substring(0, 6);
        long id = createTicketWith(requester, "REQUESTER", """
                {"typeId": %d, "title": "Plain title", "description": "the %s keeps crashing", "priority": "P3",
                 "externalRef": "%s"}""".formatted(INCIDENT, word, ref));
        long other = createTicket(requester, "REQUESTER", INCIDENT, "P3");

        mvc.perform(get("/api/helpdesk/tickets").param("search", word).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
        mvc.perform(get("/api/helpdesk/tickets").param("externalRef", ref).header("Authorization", bearer(agent, "AGENT")))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(id));

        update(agent, "AGENT", id, "{\"status\": \"IN_PROGRESS\"}").andExpect(status().isOk());
        update(agent, "AGENT", id, "{\"status\": \"RESOLVED\", \"resolutionCode\": \"WORKAROUND\", \"resolutionSummary\": \"restart\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/helpdesk/tickets").param("resolutionCode", "WORKAROUND").header("Authorization", bearer(agent, "AGENT")))
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").exists());
        update(agent, "AGENT", id, "{\"status\": \"REOPENED\"}").andExpect(status().isOk());
        mvc.perform(get("/api/helpdesk/tickets").param("reopened", "true").header("Authorization", bearer(agent, "AGENT")))
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
        mvc.perform(get("/api/helpdesk/tickets").param("source", "NOPE").header("Authorization", bearer(agent, "AGENT")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ report and dashboard scoping

    @Test
    void reportsAndDashboardCountsFollowTheSameVisibilityAsTheTicketList() throws Exception {
        String admin = user("admin");
        String member = staff("member");
        String outsider = user("outsider");
        String requester = user("req");

        int memberBefore = totalTickets(member, "AGENT");
        int outsiderBefore = totalTickets(outsider, "AGENT");
        int adminBefore = totalTickets(admin, "ADMIN");

        createTicket(requester, "REQUESTER", INCIDENT, "P3");
        long secretType = createType(admin, "Counted " + UUID.randomUUID(), "CONFIDENTIAL");
        createTicket(requester, "REQUESTER", secretType, "P3");

        // A department member sees the standard ticket, never the confidential one; an agent outside any
        // department sees neither; the admin sees the standard ticket but gets no implicit access to the secret.
        assertThat(totalTickets(member, "AGENT")).isEqualTo(memberBefore + 1);
        assertThat(totalTickets(outsider, "AGENT")).isEqualTo(outsiderBefore);
        assertThat(totalTickets(admin, "ADMIN")).isEqualTo(adminBefore + 1);

        // The ticket-list header counts and the dashboard summary agree with that for the outsider.
        mvc.perform(get("/api/helpdesk/tickets/summary").header("Authorization", bearer(outsider, "AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openTickets").value(0));
        mvc.perform(get("/api/helpdesk/reports/dashboard/summary").header("Authorization", bearer(outsider, "AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kpis.myOpenTickets").value(0));
        mvc.perform(get("/api/helpdesk/reports/escalations").header("Authorization", bearer(outsider, "AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/helpdesk/reports/kpis").header("Authorization", bearer(outsider, "AGENT")))
                .andExpect(jsonPath("$.ticketsInPeriod").value(0));
    }

    private int totalTickets(String who, String role) throws Exception {
        return json.readTree(mvc.perform(get("/api/helpdesk/reports/summary").header("Authorization", bearer(who, role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("totalTickets").asInt();
    }

    // ------------------------------------------------------------ helpers






    private long createType(String admin, String name, String defaultConfidentiality) throws Exception {
        String body = mvc.perform(post("/api/helpdesk/admin/ticket-types").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("""
                                {"name": "%s", "description": "d", "defaultConfidentiality": "%s"}"""
                                .formatted(name, defaultConfidentiality)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private void createPolicy(String admin, long typeId, String mode, long approverId) throws Exception {
        mvc.perform(post("/api/helpdesk/admin/approval-policies").header("Authorization", bearer(admin, "ADMIN"))
                        .contentType("application/json").content("""
                                {"name": "policy", "ticketTypeId": %d, "mode": "%s",
                                 "steps": [{"approverType": "USER", "approverId": %d}]}""".formatted(typeId, mode, approverId)))
                .andExpect(status().isCreated());
    }

    private long createTicket(String userId, String role, long typeId, String priority) throws Exception {
        return createTicketWith(userId, role, """
                {"typeId": %d, "title": "Test ticket", "description": "desc", "priority": "%s"}""".formatted(typeId, priority));
    }

    private long createTicketWith(String userId, String role, String body) throws Exception {
        String res = mvc.perform(post("/api/helpdesk/tickets").header("Authorization", bearer(userId, role))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private JsonNode ticket(String userId, String role, long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(userId, role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions update(String userId, String role, long id, String body) throws Exception {
        return mvc.perform(put("/api/helpdesk/tickets/{id}", id).header("Authorization", bearer(userId, role))
                .contentType("application/json").content(body));
    }

    private JsonNode pendingFor(String userId, String role) throws Exception {
        return json.readTree(mvc.perform(get("/api/helpdesk/approvals/mine").header("Authorization", bearer(userId, role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions decide(String userId, String role, long taskId, String decision, String comment) throws Exception {
        return mvc.perform(post("/api/helpdesk/approvals/{id}/decision", taskId)
                .header("Authorization", bearer(userId, role)).contentType("application/json")
                .content("{\"decision\": \"" + decision + "\", \"comment\": \"" + comment + "\"}"));
    }

    /** Internal numeric id for a user, creating the row on first contact. */
    private long me(String userId, String role) throws Exception {
        return json.readTree(mvc.perform(get("/api/helpdesk/me").header("Authorization", bearer(userId, role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** An agent who belongs to the seeded General Support department (id 1), where routed tickets land. */
    private String staff(String prefix) throws Exception {
        return memberOfGeneralSupport(prefix, "AGENT");
    }

    private String memberOfGeneralSupport(String prefix, String role) throws Exception {
        String login = user(prefix);
        long id = me(login, role);
        mvc.perform(post("/api/helpdesk/departments/1/members").header("Authorization", bearer(user("setup-admin"), "ADMIN"))
                        .contentType("application/json").content("{\"userId\": " + id + ", \"isLead\": false}"))
                .andExpect(status().isCreated());
        return login;
    }

    /** Unique login whose display name equals the login, so assertions can compare names directly. */
    private String user(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwt.generateToken(userId, userId + "@example.com", userId, Set.of(role));
    }
}
