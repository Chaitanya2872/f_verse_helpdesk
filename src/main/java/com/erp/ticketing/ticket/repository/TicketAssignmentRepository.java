package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.TicketAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketAssignmentRepository extends JpaRepository<TicketAssignment, Long> {

    List<TicketAssignment> findByTicket_IdOrderByCreatedAtAsc(Long ticketId);
}
