package com.erp.ticketing.itsm.repository;

import com.erp.ticketing.itsm.model.MajorIncident;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorIncidentRepository extends JpaRepository<MajorIncident, Long> {
}
