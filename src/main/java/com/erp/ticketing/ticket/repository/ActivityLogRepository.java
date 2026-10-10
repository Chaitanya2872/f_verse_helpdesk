package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    List<ActivityLog> findByTicket_IdOrderByCreatedAtAsc(Long ticketId);
}
