package com.erp.ticketing.integration.asset;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.asset-manager.*}; see {@link AssetManagerProperties}. */
@Configuration
@EnableConfigurationProperties(AssetManagerProperties.class)
public class AssetManagerIntegrationConfig {
}
