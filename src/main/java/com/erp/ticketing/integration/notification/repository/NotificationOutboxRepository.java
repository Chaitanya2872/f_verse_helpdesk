package com.erp.ticketing.integration.notification.repository;

import com.erp.ticketing.integration.notification.OutboxStatus;
import com.erp.ticketing.integration.notification.model.NotificationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    Optional<NotificationOutbox> findByIdempotencyKey(String idempotencyKey);

    List<NotificationOutbox> findByTicketIdOrderByIdAsc(Long ticketId);

    List<NotificationOutbox> findTop100ByStatusOrderByIdDesc(OutboxStatus status);

    /**
     * Inserts a row unless one with the same idempotency key already exists. A repeat of the same event
     * therefore changes nothing, and - unlike a failed INSERT - cannot poison the surrounding transaction.
     *
     * @return 1 if a row was written, 0 if it was a duplicate
     */
    @Modifying
    @Query(value = "INSERT INTO notification_outbox (idempotency_key, event_type, channel, ticket_id, recipient_user_id, "
            + "recipient_name, recipient_email, subject, body_html, body_text, status, attempts, next_attempt_at, "
            + "correlation_id, created_at, updated_at, version) "
            + "VALUES (:key, :eventType, :channel, :ticketId, :userId, :name, :email, :subject, :html, :text, "
            + "'PENDING', 0, now(), :correlationId, now(), now(), 0) "
            + "ON CONFLICT (idempotency_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("eventType") String eventType, @Param("channel") String channel,
                       @Param("ticketId") Long ticketId, @Param("userId") Long userId, @Param("name") String name,
                       @Param("email") String email, @Param("subject") String subject, @Param("html") String html,
                       @Param("text") String text, @Param("correlationId") String correlationId);

    /** Rows that are due, locked so concurrent dispatcher instances never pick the same one. */
    @Query(value = "SELECT id FROM notification_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "AND (lease_until IS NULL OR lease_until < :now) ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Long> lockDueIds(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query("update NotificationOutbox o set o.leaseUntil = :until where o.id in :ids")
    int lease(@Param("ids") Collection<Long> ids, @Param("until") Instant until);
}
