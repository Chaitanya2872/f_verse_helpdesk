package com.erp.ticketing.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IntegrationEmailClientTest {

    private MockRestServiceServer server;
    private IntegrationEmailClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IntegrationEmailClient(builder, "http://integration-service", "test-secret");
    }

    @Test
    void relaysEmailWithProvenanceAndInternalSecret() {
        server.expect(requestTo("http://integration-service/internal/integration/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Secret", "test-secret"))
                .andExpect(header("X-Calling-Module", "helpdesk"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.to[0]").value("agent@example.com"))
                .andExpect(jsonPath("$.subject").value("Ticket assigned to you: HD-1"))
                .andExpect(jsonPath("$.bodyText").value("Body"))
                .andExpect(jsonPath("$.eventType").value("TICKET_ASSIGNED"))
                .andExpect(jsonPath("$.idempotencyKey").value("helpdesk-notification-7"))
                .andRespond(withSuccess("{\"sent\":true}", MediaType.APPLICATION_JSON));

        boolean sent = client.send("agent@example.com", "Ticket assigned to you: HD-1", "Body",
                "TICKET_ASSIGNED", "helpdesk-notification-7");

        assertThat(sent).isTrue();
        server.verify();
    }

    @Test
    void reportsRelayFailureWithoutThrowing() {
        server.expect(requestTo("http://integration-service/internal/integration/email"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sent\":false,\"error\":\"SMTP down\"}"));

        boolean sent = client.send("agent@example.com", "Subject", "Body", "TICKET_CREATED", "key");

        assertThat(sent).isFalse();
    }
}
