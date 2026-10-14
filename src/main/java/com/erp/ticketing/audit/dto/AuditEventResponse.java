package com.erp.ticketing.audit.dto;

import java.time.Instant;
import java.util.Map;

public record AuditEventResponse(
        Long id,
        Long actorId,
        String actorName,
        String action,
        String targetType,
        String targetId,
        String correlationId,
        Map<String, Object> before,
        Map<String, Object> after,
        Instant createdAt
) {
}
