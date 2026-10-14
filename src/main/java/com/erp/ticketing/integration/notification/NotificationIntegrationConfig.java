package com.erp.ticketing.integration.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.notification-service.*}; see {@link NotificationServiceProperties}. */
@Configuration
@EnableConfigurationProperties(NotificationServiceProperties.class)
public class NotificationIntegrationConfig {
}
