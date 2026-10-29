package com.erp.ticketing.itsm.repository;

import com.erp.ticketing.itsm.model.ChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {
}
