package com.erp.ticketing.notification.dto;

public record NotificationTemplateResponse(Long id, String eventType, String subjectTemplate, String bodyTemplate) {
}
