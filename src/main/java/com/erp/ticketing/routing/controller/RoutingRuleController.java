package com.erp.ticketing.routing.controller;

import com.erp.ticketing.routing.dto.RoutingRuleRequest;
import com.erp.ticketing.routing.dto.RoutingRuleResponse;
import com.erp.ticketing.routing.service.RoutingRuleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Falls under /api/helpdesk/admin/** which SecurityConfig already restricts to
 * ROLE_ADMIN, so no additional per-endpoint role matchers are needed here.
 */
@RestController
@RequestMapping("/api/helpdesk/admin/routing-rules")
public class RoutingRuleController {

    private final RoutingRuleService routingRuleService;

    public RoutingRuleController(RoutingRuleService routingRuleService) {
        this.routingRuleService = routingRuleService;
    }

    @GetMapping
    public List<RoutingRuleResponse> list() {
        return routingRuleService.list();
    }

    @PostMapping
    public ResponseEntity<RoutingRuleResponse> create(@Valid @RequestBody RoutingRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routingRuleService.create(request));
    }

    @PutMapping("/{id}")
    public RoutingRuleResponse update(@PathVariable Long id, @RequestBody RoutingRuleRequest request) {
        return routingRuleService.update(id, request);
    }
}
