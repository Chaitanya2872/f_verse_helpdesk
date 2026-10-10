package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    List<Ticket> findByStatusNotInAndResolutionDueIsNotNull(Collection<TicketStatus> statuses);
}
