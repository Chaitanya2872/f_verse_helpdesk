package com.erp.ticketing.reporting.dto;

import java.time.Instant;

/**
 * Response shape for GET /api/helpdesk/reports/dashboard/summary. {@code kpis} is one
 * of {@link RequesterDashboardKpis}, {@link AgentDashboardKpis},
 * {@link ManagerDashboardKpis}, or {@link AdminDashboardKpis} depending on
 * {@code variant} - kept as Object here (rather than a sealed interface) so
 * each variant serializes with exactly its own field names and nothing else.
 */
public record DashboardSummaryResponse(String variant, Instant generatedAt, Object kpis) {
}
