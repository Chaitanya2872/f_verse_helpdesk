package com.erp.ticketing.reporting.dto;

/**
 * One entry per UTC calendar day for GET /api/helpdesk/reports/sla-compliance-trend.
 * {@code compliancePercentage} is null when zero tickets were resolved/closed
 * that day - a day with no data point to plot, not a 0% day. Frontends should
 * render null as a gap in the trend line rather than a dip to zero.
 */
public record SlaComplianceTrendResponse(String date, Double compliancePercentage) {
}
