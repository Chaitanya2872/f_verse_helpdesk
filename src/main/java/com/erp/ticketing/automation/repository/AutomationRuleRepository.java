package com.erp.ticketing.automation.repository;

import com.erp.ticketing.automation.model.AutomationRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {

    List<AutomationRule> findByEnabledTrueAndTriggerOrderByIdAsc(AutomationRule.Trigger trigger);
}
