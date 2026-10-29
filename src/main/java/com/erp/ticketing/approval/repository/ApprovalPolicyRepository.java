package com.erp.ticketing.approval.repository;

import com.erp.ticketing.approval.model.ApprovalPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, Long> {

    List<ApprovalPolicy> findByTicketTypeIdAndActiveTrueOrderByIdDesc(Long ticketTypeId);

    List<ApprovalPolicy> findByTicketTypeIdIsNullAndActiveTrueOrderByIdDesc();

    boolean existsByTicketTypeIdAndActiveTrue(Long ticketTypeId);

    boolean existsByTicketTypeIdIsNullAndActiveTrue();
}
