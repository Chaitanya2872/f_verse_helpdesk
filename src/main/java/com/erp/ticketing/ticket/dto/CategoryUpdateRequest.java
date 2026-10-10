package com.erp.ticketing.ticket.dto;

/**
 * Both fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest); there is no way to explicitly clear parentCategoryId
 * back to null via this simple representation once set.
 */
public record CategoryUpdateRequest(String name, Long parentCategoryId) {
}
