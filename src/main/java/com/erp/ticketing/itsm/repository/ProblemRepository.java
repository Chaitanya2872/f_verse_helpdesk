package com.erp.ticketing.itsm.repository;

import com.erp.ticketing.itsm.model.Problem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<Problem, Long> {
}
