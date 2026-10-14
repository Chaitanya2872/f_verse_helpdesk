package com.erp.ticketing.ticket.dto;

import java.time.Instant;

public final class AccessGrantDto {

    private AccessGrantDto() {
    }

    public record Grant(Long id, Long userId, String userName, String grantedBy, String reason, Instant expiresAt,
                        Instant createdAt) {
    }

    public record GrantRequest(Long userId, String reason, Instant expiresAt) {
    }
}
