package com.erp.ticketing.notification.controller;

import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.notification.dto.NotificationTemplateResponse;
import com.erp.ticketing.notification.dto.NotificationTemplateUpdateRequest;
import com.erp.ticketing.notification.model.NotificationTemplate;
import com.erp.ticketing.notification.repository.NotificationTemplateRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Falls under /api/helpdesk/admin/** which SecurityConfig already restricts to
 * ROLE_ADMIN, so no additional per-endpoint role matchers are needed here.
 * event_type itself is immutable - rows are seeded by migration V8, admins
 * only edit subject/body wording.
 */
@RestController
@RequestMapping("/api/helpdesk/admin/notification-templates")
public class NotificationTemplateAdminController {

    private final NotificationTemplateRepository notificationTemplateRepository;

    public NotificationTemplateAdminController(NotificationTemplateRepository notificationTemplateRepository) {
        this.notificationTemplateRepository = notificationTemplateRepository;
    }

    @GetMapping
    public List<NotificationTemplateResponse> list() {
        return notificationTemplateRepository.findAll().stream().map(this::toResponse).toList();
    }

    @PutMapping("/{id}")
    @Transactional
    public NotificationTemplateResponse update(@PathVariable Long id, @RequestBody NotificationTemplateUpdateRequest request) {
        NotificationTemplate template = notificationTemplateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Notification template not found: " + id));
        if (request.subjectTemplate() != null) {
            template.setSubjectTemplate(request.subjectTemplate());
        }
        if (request.bodyTemplate() != null) {
            template.setBodyTemplate(request.bodyTemplate());
        }
        template = notificationTemplateRepository.save(template);
        return toResponse(template);
    }

    private NotificationTemplateResponse toResponse(NotificationTemplate template) {
        return new NotificationTemplateResponse(template.getId(), template.getEventType(),
                template.getSubjectTemplate(), template.getBodyTemplate());
    }
}
