package com.erp.ticketing.sla.repository;

import com.erp.ticketing.sla.model.SlaCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaCalendarRepository extends JpaRepository<SlaCalendar, Long> {
}
