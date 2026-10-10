package com.erp.ticketing.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} methods application-wide (currently only
 * {@code SlaMonitoringJob}).
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
