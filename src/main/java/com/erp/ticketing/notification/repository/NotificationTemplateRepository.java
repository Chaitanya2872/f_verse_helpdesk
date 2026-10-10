package com.erp.ticketing.notification.repository;

import com.erp.ticketing.notification.model.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByEventType(String eventType);
}
