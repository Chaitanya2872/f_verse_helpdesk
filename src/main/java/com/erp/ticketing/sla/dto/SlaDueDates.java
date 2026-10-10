package com.erp.ticketing.sla.dto;

import java.time.Instant;

public record SlaDueDates(Instant firstResponseDue, Instant resolutionDue) {
}
