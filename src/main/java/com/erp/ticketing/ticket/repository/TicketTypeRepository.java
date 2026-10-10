package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {
}
