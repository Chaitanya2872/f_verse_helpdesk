package com.erp.ticketing.form.dto;

import java.time.Instant;
import java.util.Map;

public final class FormDto {

    private FormDto() {
    }

    public record Version(Long id, Long ticketTypeId, int version, String status, Map<String, Object> schema,
                          Instant createdAt, Instant publishedAt) {
    }

    public record SaveRequest(Map<String, Object> schema) {
    }
}
