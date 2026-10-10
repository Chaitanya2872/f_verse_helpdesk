package com.erp.ticketing.reporting.dto;

import java.util.Map;

public record CsatSummaryResponse(
        long totalSurveys,
        long submittedCount,
        Double responseRatePercent,
        Double averageRating,
        Map<Integer, Long> ratingDistribution
) {
}
