package com.erp.ticketing.notification.repository;

import com.erp.ticketing.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId);

    List<Notification> findByUser_IdAndReadFalseOrderByCreatedAtDesc(Long userId);

    Optional<Notification> findByIdAndUser_Id(Long id, Long userId);
}
