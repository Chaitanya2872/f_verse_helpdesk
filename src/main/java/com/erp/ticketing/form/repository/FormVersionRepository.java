package com.erp.ticketing.form.repository;

import com.erp.ticketing.form.model.FormVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormVersionRepository extends JpaRepository<FormVersion, Long> {

    Optional<FormVersion> findByTicketTypeIdAndStatus(Long ticketTypeId, FormVersion.Status status);

    List<FormVersion> findByTicketTypeIdOrderByVersionDesc(Long ticketTypeId);

    Optional<FormVersion> findFirstByTicketTypeIdOrderByVersionDesc(Long ticketTypeId);
}
