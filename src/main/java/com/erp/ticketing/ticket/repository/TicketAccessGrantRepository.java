package com.erp.ticketing.ticket.repository;

import com.erp.ticketing.ticket.model.TicketAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketAccessGrantRepository extends JpaRepository<TicketAccessGrant, Long> {

    List<TicketAccessGrant> findByTicket_Id(Long ticketId);

    Optional<TicketAccessGrant> findByTicket_IdAndUser_Id(Long ticketId, Long userId);

    @Query("select count(g) > 0 from TicketAccessGrant g where g.ticket.id = :ticketId and g.user.id = :userId "
            + "and (g.expiresAt is null or g.expiresAt > :now)")
    boolean hasActiveGrant(@Param("ticketId") Long ticketId, @Param("userId") Long userId, @Param("now") Instant now);
}
