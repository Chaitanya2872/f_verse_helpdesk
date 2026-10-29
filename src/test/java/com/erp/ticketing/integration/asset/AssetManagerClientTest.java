package com.erp.ticketing.integration.asset;

import com.erp.ticketing.common.ServiceUnavailableException;
import com.erp.ticketing.integration.asset.AssetManagerClient.AssetSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The HTTP contract with the Asset Manager's /api/assets endpoints, against a mock server. */
class AssetManagerClientTest {

    private static final String BASE = "http://assets.test";

    private MockRestServiceServer server;
    private AssetManagerProperties properties;
    private AssetManagerClient client;

    @BeforeEach
    void setUp() {
        properties = new AssetManagerProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(BASE);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AssetManagerClient(builder.build(), properties);
    }

    @Test
    void searchReadsOnlyTheSummaryFieldsAndHonoursTheLimit() {
        properties.setSearchLimit(1);
        server.expect(requestTo(BASE + "/api/assets/search?name=lap"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"assetId": "a-1", "assetCode": "AST-0001", "assetName": "Dell Latitude", "assetCategory": "IT",
                          "status": "IN_USE", "location": "HQ", "value": 1200, "vendorEmail": "v@example.com"},
                         {"assetId": "a-2", "assetName": "Lenovo"}]""", MediaType.APPLICATION_JSON));

        List<AssetSummary> results = client.search(" lap ");

        assertThat(results).containsExactly(new AssetSummary("a-1", "AST-0001", "Dell Latitude", "IT", "IN_USE", "HQ"));
        server.verify();
    }

    @Test
    void forwardsTheCallersAccessToken() {
        MockHttpServletRequest incoming = new MockHttpServletRequest();
        incoming.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));
        try {
            server.expect(requestTo(BASE + "/api/assets/a-1"))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                    .andRespond(withSuccess("{\"assetId\": \"a-1\", \"assetName\": \"Printer\"}", MediaType.APPLICATION_JSON));

            assertThat(client.find("a-1")).map(AssetSummary::label).contains("Printer");
            server.verify();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void aRejectedTokenIsReportedAsUnavailableRatherThanAsAMissingAsset() {
        server.expect(requestTo(BASE + "/api/assets/a-1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.find("a-1")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void searchIsEmptyAndSendsNothingWhenSwitchedOffOrBlank() {
        assertThat(client.search("  ")).isEmpty();
        properties.setEnabled(false);
        assertThat(client.search("lap")).isEmpty();
        server.verify();
    }

    @Test
    void findFallsBackToTheCodeWhenAnAssetHasNoName() {
        server.expect(requestTo(BASE + "/api/assets/a-9"))
                .andRespond(withSuccess("{\"assetId\": \"a-9\", \"assetCode\": \"AST-0009\"}", MediaType.APPLICATION_JSON));

        assertThat(client.find("a-9")).map(AssetSummary::label).contains("AST-0009");
    }

    @Test
    void findIsEmptyForAnAssetTheAssetManagerDoesNotHave() {
        server.expect(requestTo(BASE + "/api/assets/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"status\": 404}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.find("missing")).isEqualTo(Optional.empty());
    }

    @Test
    void serverErrorsAreReportedAsUnavailableRatherThanAsAMissingAsset() {
        server.expect(requestTo(BASE + "/api/assets/a-1")).andRespond(withServerError());
        server.expect(requestTo(BASE + "/api/assets/search?name=x")).andRespond(withServerError());

        assertThatThrownBy(() -> client.find("a-1")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> client.search("x")).isInstanceOf(ServiceUnavailableException.class);
        server.verify();
    }
}
