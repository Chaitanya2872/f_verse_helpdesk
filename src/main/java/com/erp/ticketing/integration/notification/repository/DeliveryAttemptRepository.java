package com.erp.ticketing.integration.notification.repository;

import com.erp.ticketing.integration.notification.model.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

    List<DeliveryAttempt> findByOutboxIdOrderByAttemptNoAsc(Long outboxId);
}
