package com.erp.ticketing.integration.asset;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the connection to the Asset Manager service ({@code app.asset-manager.*}). The Asset Manager stays
 * the only owner of asset records; the helpdesk only searches it and checks that a referenced asset exists.
 */
@ConfigurationProperties(prefix = "app.asset-manager")
public class AssetManagerProperties {

    /** Off by default: references to assets stay unverified and the asset lookup answers with an empty list. */
    private boolean enabled = false;
    private String baseUrl = "http://localhost:8088";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    /** The most results a single lookup hands back to the selector. */
    private int searchLimit = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getSearchLimit() { return searchLimit; }
    public void setSearchLimit(int searchLimit) { this.searchLimit = searchLimit; }
}
