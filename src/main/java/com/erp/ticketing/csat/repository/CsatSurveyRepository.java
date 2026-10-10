package com.erp.ticketing.csat.repository;

import com.erp.ticketing.csat.model.CsatSurvey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface CsatSurveyRepository extends JpaRepository<CsatSurvey, Long>, JpaSpecificationExecutor<CsatSurvey> {

    Optional<CsatSurvey> findByTicket_Id(Long ticketId);
}
