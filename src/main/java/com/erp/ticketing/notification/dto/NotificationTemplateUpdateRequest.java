package com.erp.ticketing.notification.dto;

/**
 * Both fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest). event_type is intentionally not settable here - these
 * are seeded rows (migration V8), admins only edit wording.
 */
public record NotificationTemplateUpdateRequest(String subjectTemplate, String bodyTemplate) {
}
