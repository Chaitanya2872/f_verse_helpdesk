package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByTicket_IdOrderByCreatedAtAsc(Long ticketId);
}
