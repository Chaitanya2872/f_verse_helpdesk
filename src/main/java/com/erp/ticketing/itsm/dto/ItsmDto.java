package com.erp.ticketing.itsm.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ItsmDto {

    private ItsmDto() {
    }

    public record TicketRef(Long id, String ticketNumber, String title, String status) {
    }

    // ---------------------------------------------------------------- problems

    public record Problem(Long id, String problemNumber, String title, String description, String status,
                          String symptoms, String rootCause, String workaround, String permanentFix, Long ownerId,
                          String ownerName, LocalDate reviewDate, Long version, Instant createdAt,
                          Instant updatedAt, int linkedTicketCount, List<TicketRef> linkedTickets,
                          List<ChangeRef> changes) {
    }

    public record ProblemSave(String title, String description, String symptoms, String rootCause, String workaround,
                              String permanentFix, Long ownerId, LocalDate reviewDate, Long version) {
    }

    public record StatusRequest(String status, Long version) {
    }

    public record ChangeRef(Long id, String changeNumber, String title, String status) {
    }

    // ----------------------------------------------------------------- changes

    public record Change(Long id, String changeNumber, String title, String changeType, String status, String reason,
                         String scope, String risk, String impact, String affectedServices,
                         String implementationPlan, String testPlan, String rollbackPlan, Instant scheduledStart,
                         Instant scheduledEnd, Instant actualStart, Instant actualEnd, String outcome, Long ownerId,
                         String ownerName, String approvedByName, Instant approvedAt, String approvalComment,
                         Long version, Instant createdAt, List<TicketRef> linkedTickets, List<Long> problemIds) {
    }

    public record ChangeSave(String title, String changeType, String reason, String scope, String risk, String impact,
                             String affectedServices, String implementationPlan, String testPlan,
                             String rollbackPlan, Long version) {
    }

    public record ChangeDecision(boolean approve, String comment) {
    }

    public record ChangeSchedule(Instant start, Instant end) {
    }

    public record ChangeFinish(boolean success, String outcome) {
    }

    // --------------------------------------------------------- major incidents

    public record Incident(Long id, String incidentNumber, String title, String status, String severity,
                           String reason, String scope, String affectedServices, String customerImpact,
                           Long incidentLeadId, String incidentLeadName, Long commsLeadId, String commsLeadName,
                           Instant startedAt, Instant mitigatedAt, Instant resolvedAt, Instant closedAt,
                           int updateCadenceMinutes, Instant nextUpdateDueAt, boolean updateOverdue,
                           String postIncidentReview, String actionItems, Long version,
                           List<TicketRef> linkedTickets, List<IncidentUpdate> updates) {
    }

    public record IncidentUpdate(Long id, String author, String message, boolean isPublic, Instant createdAt) {
    }

    public record IncidentDeclare(String title, String severity, String reason, String scope, String affectedServices,
                                  String customerImpact, Long incidentLeadId, Long commsLeadId,
                                  Integer updateCadenceMinutes, List<Long> ticketIds) {
    }

    public record IncidentUpdateRequest(String message, Boolean isPublic) {
    }

    public record IncidentStatusRequest(String status, String postIncidentReview, String actionItems, Long version) {
    }

    /** What requesters may see of an active incident: no internal notes, only updates marked public. */
    public record PublicIncident(String incidentNumber, String title, String status, String severity,
                                 String customerImpact, Instant startedAt, List<IncidentUpdate> updates) {
    }
}
