package com.erp.ticketing.automation.repository;

import com.erp.ticketing.automation.model.AutomationRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, Long> {

    long countByRuleIdAndTicketIdAndResultAndDryRunFalse(Long ruleId, Long ticketId, AutomationRun.Result result);

    List<AutomationRun> findByRuleIdOrderByIdDesc(Long ruleId, Pageable pageable);
}
