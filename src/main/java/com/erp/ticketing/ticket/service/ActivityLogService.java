package com.erp.ticketing.ticket.service;

import com.erp.ticketing.ticket.dto.ActivityLogResponse;
import com.erp.ticketing.ticket.model.ActivityLog;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.ActivityLogRepository;
import com.erp.ticketing.user.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;

    public ActivityLogService(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
    }

    @Transactional
    public void logCreated(Ticket ticket, User actor) {
        record(ticket, actor, "CREATED", null, null, null);
    }

    @Transactional
    public void logFieldChanged(Ticket ticket, User actor, String fieldName, String oldValue, String newValue) {
        record(ticket, actor, "FIELD_CHANGED", fieldName, oldValue, newValue);
    }

    /**
     * Logged by {@code SlaMonitoringJob} when a ticket's resolution SLA has
     * been breached. There is no human actor for this - it is a system-detected
     * event, hence {@code actor = null} (actor_id is nullable).
     */
    @Transactional
    public void logSlaBreached(Ticket ticket) {
        record(ticket, null, "SLA_BREACHED", null, null, null);
    }

    private void record(Ticket ticket, User actor, String actionType, String fieldName, String oldValue, String newValue) {
        ActivityLog log = new ActivityLog();
        log.setTicket(ticket);
        log.setActor(actor);
        log.setActionType(actionType);
        log.setFieldName(fieldName);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        activityLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<ActivityLogResponse> getForTicket(Long ticketId) {
        return activityLogRepository.findByTicket_IdOrderByCreatedAtAsc(ticketId).stream()
                .map(this::toResponse)
                .toList();
    }

    private ActivityLogResponse toResponse(ActivityLog log) {
        User actor = log.getActor();
        return new ActivityLogResponse(
                log.getId(),
                log.getTicket().getId(),
                actor != null ? actor.getId() : null,
                actor != null ? actor.getName() : null,
                log.getActionType(),
                log.getFieldName(),
                log.getOldValue(),
                log.getNewValue(),
                log.getCreatedAt());
    }
}
