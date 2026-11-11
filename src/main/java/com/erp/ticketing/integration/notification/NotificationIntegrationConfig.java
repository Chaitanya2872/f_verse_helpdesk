package com.erp.ticketing.integration.notification;

import com.erp.ticketing.integration.notification.whatsapp.WhatsAppProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.notification-service.*} (see {@link NotificationServiceProperties}) and
 * {@code app.whatsapp.*} (see {@link WhatsAppProperties}).
 */
@Configuration
@EnableConfigurationProperties({NotificationServiceProperties.class, WhatsAppProperties.class})
public class NotificationIntegrationConfig {
}
