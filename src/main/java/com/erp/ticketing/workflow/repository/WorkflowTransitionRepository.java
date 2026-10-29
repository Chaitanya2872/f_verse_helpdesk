package com.erp.ticketing.workflow.repository;

import com.erp.ticketing.workflow.model.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, Long> {

    List<WorkflowTransition> findByTicketTypeId(Long ticketTypeId);

    List<WorkflowTransition> findByTicketTypeIdIsNull();

    void deleteByTicketTypeId(Long ticketTypeId);

    void deleteByTicketTypeIdIsNull();
}
