package com.erp.ticketing.reporting.dto;

import java.util.Map;

public record ReportSummaryResponse(
        long totalTickets,
        Map<String, Long> byStatus,
        Map<String, Long> byPriority,
        Double avgFirstResponseMinutes,
        Double avgResolutionMinutes
) {
}
