package com.erp.ticketing.approval.repository;

import com.erp.ticketing.approval.model.ApprovalTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, Long> {

    List<ApprovalTask> findByTicket_IdOrderByStepOrderAscIdAsc(Long ticketId);

    List<ApprovalTask> findByTicket_IdAndStatusIn(Long ticketId, Collection<ApprovalTask.Status> statuses);

    List<ApprovalTask> findByApprover_IdInAndStatusOrderByCreatedAtAsc(Collection<Long> approverIds, ApprovalTask.Status status);

    List<ApprovalTask> findByStatusAndDueAtBefore(ApprovalTask.Status status, Instant cutoff);

    @org.springframework.data.jpa.repository.Query("select t from ApprovalTask t where t.status in :statuses "
            + "and t.decidedAt is not null and t.createdAt >= :from and t.createdAt <= :to")
    List<ApprovalTask> findDecidedCreatedBetween(
            @org.springframework.data.repository.query.Param("statuses") Collection<ApprovalTask.Status> statuses,
            @org.springframework.data.repository.query.Param("from") Instant from,
            @org.springframework.data.repository.query.Param("to") Instant to);
}
