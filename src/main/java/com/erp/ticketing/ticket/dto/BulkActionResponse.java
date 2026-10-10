package com.erp.ticketing.ticket.dto;

import java.util.List;

public record BulkActionResponse(
        List<BulkActionResult> results
) {
}
