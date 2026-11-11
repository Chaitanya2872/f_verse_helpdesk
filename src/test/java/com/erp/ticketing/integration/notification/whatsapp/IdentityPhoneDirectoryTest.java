package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.whatsapp.IdentityPhoneDirectory.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** identity-service's GET /internal/users/profile/{id}, against a mock server. */
class IdentityPhoneDirectoryTest {

    private static final String URL = "http://identity.test/internal/users/profile/u-1";

    private WhatsAppProperties.Identity settings;
    private MockRestServiceServer server;
    private IdentityPhoneDirectory directory;

    @BeforeEach
    void setUp() {
        settings = new WhatsAppProperties.Identity();
        settings.setBaseUrl("http://identity.test");
        settings.setInternalSecret("internal-secret");
        RestClient.Builder builder = RestClient.builder().baseUrl(settings.getBaseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new IdentityPhoneDirectory(builder.build(), settings, new ObjectMapper());
    }

    @Test
    void returnsTheStoredNumberUsingTheInternalSecret() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Secret", "internal-secret"))
                .andRespond(withSuccess("{\"id\":\"u-1\",\"name\":\"Rahul\",\"phoneNumber\":\"+91 98765 43210\"}",
                        MediaType.APPLICATION_JSON));

        IdentityPhoneDirectory.Lookup lookup = directory.lookup("u-1");

        assertThat(lookup.status()).isEqualTo(Status.FOUND);
        assertThat(lookup.phone()).isEqualTo("+91 98765 43210");
        server.verify();
    }

    @Test
    void aPersonWithoutANumberIsFoundWithNoPhone() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"id\":\"u-1\",\"phoneNumber\":null}", MediaType.APPLICATION_JSON));

        IdentityPhoneDirectory.Lookup lookup = directory.lookup("u-1");

        assertThat(lookup.status()).isEqualTo(Status.FOUND);
        assertThat(lookup.phone()).isNull();
    }

    @Test
    void anUnknownIdIsNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(directory.lookup("u-1").status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void outagesAreUnavailableSoTheSendIsRetried() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(directory.lookup("u-1").status()).isEqualTo(Status.UNAVAILABLE);

        server.reset();
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });
        assertThat(directory.lookup("u-1").status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void aRejectedOrMissingSecretIsAConfigurationProblem() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(directory.lookup("u-1").status()).isEqualTo(Status.MISCONFIGURED);

        settings.setInternalSecret("");
        assertThat(directory.lookup("u-1").status()).as("no call made").isEqualTo(Status.MISCONFIGURED);
    }
}
