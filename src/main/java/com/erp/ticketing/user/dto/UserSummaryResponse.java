package com.erp.ticketing.user.dto;

/** Minimal directory entry for pickers; email is only filled in for staff. */
public record UserSummaryResponse(Long id, String name, String email) {
}
