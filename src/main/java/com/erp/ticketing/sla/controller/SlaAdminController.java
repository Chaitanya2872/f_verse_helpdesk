package com.erp.ticketing.sla.controller;

import com.erp.ticketing.sla.dto.SlaCalendarCreateRequest;
import com.erp.ticketing.sla.dto.SlaCalendarResponse;
import com.erp.ticketing.sla.dto.SlaCalendarUpdateRequest;
import com.erp.ticketing.sla.dto.SlaPolicyCreateRequest;
import com.erp.ticketing.sla.dto.SlaPolicyResponse;
import com.erp.ticketing.sla.dto.SlaPolicyUpdateRequest;
import com.erp.ticketing.sla.service.SlaAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Falls under /api/helpdesk/admin/** which SecurityConfig already restricts to
 * ROLE_ADMIN, so no additional per-endpoint role matchers are needed here.
 */
@RestController
public class SlaAdminController {

    private final SlaAdminService slaAdminService;

    public SlaAdminController(SlaAdminService slaAdminService) {
        this.slaAdminService = slaAdminService;
    }

    @GetMapping("/api/helpdesk/admin/sla-calendars")
    public List<SlaCalendarResponse> listCalendars() {
        return slaAdminService.listCalendars();
    }

    @PostMapping("/api/helpdesk/admin/sla-calendars")
    public ResponseEntity<SlaCalendarResponse> createCalendar(@Valid @RequestBody SlaCalendarCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(slaAdminService.createCalendar(request));
    }

    @PutMapping("/api/helpdesk/admin/sla-calendars/{id}")
    public SlaCalendarResponse updateCalendar(@PathVariable Long id, @RequestBody SlaCalendarUpdateRequest request) {
        return slaAdminService.updateCalendar(id, request);
    }

    @GetMapping("/api/helpdesk/admin/sla-policies")
    public List<SlaPolicyResponse> listPolicies() {
        return slaAdminService.listPolicies();
    }

    @PostMapping("/api/helpdesk/admin/sla-policies")
    public ResponseEntity<SlaPolicyResponse> createPolicy(@Valid @RequestBody SlaPolicyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(slaAdminService.createPolicy(request));
    }

    @PutMapping("/api/helpdesk/admin/sla-policies/{id}")
    public SlaPolicyResponse updatePolicy(@PathVariable Long id, @RequestBody SlaPolicyUpdateRequest request) {
        return slaAdminService.updatePolicy(id, request);
    }
}
