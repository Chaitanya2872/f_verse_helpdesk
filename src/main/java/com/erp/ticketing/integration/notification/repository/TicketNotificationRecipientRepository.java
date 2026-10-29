package com.erp.ticketing.integration.notification.repository;

import com.erp.ticketing.integration.notification.NotificationEventType;
import com.erp.ticketing.integration.notification.model.TicketNotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TicketNotificationRecipientRepository extends JpaRepository<TicketNotificationRecipient, Long> {

    /** Active subscriptions for an event that cover the department (or are not limited to one). */
    @Query("select r from TicketNotificationRecipient r join fetch r.user "
            + "where r.active = true and r.eventType = :eventType "
            + "and (r.department is null or r.department.id = :departmentId)")
    List<TicketNotificationRecipient> findActiveFor(@Param("eventType") NotificationEventType eventType,
                                                    @Param("departmentId") Long departmentId);

    boolean existsByUser_IdAndEventTypeAndDepartmentIsNull(Long userId, NotificationEventType eventType);

    boolean existsByUser_IdAndEventTypeAndDepartment_Id(Long userId, NotificationEventType eventType, Long departmentId);

    @Query("select r from TicketNotificationRecipient r join fetch r.user left join fetch r.department order by r.id")
    List<TicketNotificationRecipient> findAllWithDetails();
}
