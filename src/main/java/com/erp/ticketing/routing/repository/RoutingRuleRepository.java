package com.erp.ticketing.routing.repository;

import com.erp.ticketing.routing.model.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    List<RoutingRule> findByActiveTrueOrderByOrderIndexAsc();

    List<RoutingRule> findAllByOrderByOrderIndexAsc();
}
