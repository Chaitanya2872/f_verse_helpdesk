package com.erp.ticketing.reporting.dto;

public record SlaComplianceResponse(
        long totalEvaluated,
        long metCount,
        long breachedCount,
        Double compliancePercentage
) {
}
