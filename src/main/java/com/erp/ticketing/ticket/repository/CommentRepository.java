package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByTicket_IdOrderByCreatedAtAsc(Long ticketId);
}
