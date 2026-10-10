package com.erp.ticketing.kb.dto;

/**
 * All fields optional - null means "leave unchanged" (same convention as
 * TicketUpdateRequest/DepartmentUpdateRequest). Status is intentionally
 * absent - publish state changes only via POST .../publish and .../unpublish.
 */
public record KbArticleUpdateRequest(String title, String content, String category, String tags) {
}
