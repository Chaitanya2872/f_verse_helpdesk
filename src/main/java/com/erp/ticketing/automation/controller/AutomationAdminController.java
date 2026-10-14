package com.erp.ticketing.automation.controller;

import com.erp.ticketing.automation.dto.AutomationDto;
import com.erp.ticketing.automation.service.AutomationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only via the /admin/** matcher. Rules are created disabled unless {@code enabled} is set. */
@RestController
@RequestMapping("/api/helpdesk/admin/automation-rules")
public class AutomationAdminController {

    private final AutomationService service;

    public AutomationAdminController(AutomationService service) {
        this.service = service;
    }

    @GetMapping
    public List<AutomationDto.Rule> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<AutomationDto.Rule> create(@RequestBody AutomationDto.SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public AutomationDto.Rule update(@PathVariable Long id, @RequestBody AutomationDto.SaveRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/enable")
    public AutomationDto.Rule enable(@PathVariable Long id) {
        return service.setEnabled(id, true);
    }

    @PostMapping("/{id}/disable")
    public AutomationDto.Rule disable(@PathVariable Long id) {
        return service.setEnabled(id, false);
    }

    @PostMapping("/{id}/dry-run")
    public AutomationDto.DryRunResult dryRun(@PathVariable Long id, @RequestParam Long ticketId) {
        return service.dryRun(id, ticketId);
    }

    @GetMapping("/{id}/runs")
    public List<AutomationDto.Run> runs(@PathVariable Long id) {
        return service.runs(id);
    }
}
