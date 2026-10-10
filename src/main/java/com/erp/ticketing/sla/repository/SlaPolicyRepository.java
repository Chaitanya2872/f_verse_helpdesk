package com.erp.ticketing.sla.repository;

import com.erp.ticketing.sla.model.SlaPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    List<SlaPolicy> findByActiveTrueOrderByIdAsc();
}
