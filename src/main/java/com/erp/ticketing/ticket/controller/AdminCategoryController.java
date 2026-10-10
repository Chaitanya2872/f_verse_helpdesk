package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.ticket.dto.CategoryCreateRequest;
import com.erp.ticketing.ticket.dto.CategoryResponse;
import com.erp.ticketing.ticket.dto.CategoryUpdateRequest;
import com.erp.ticketing.ticket.service.AdminReferenceDataService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Falls under /api/helpdesk/admin/** which SecurityConfig already restricts to
 * ROLE_ADMIN, so no additional per-endpoint role matchers are needed here.
 * GET /api/helpdesk/categories stays on ReferenceDataController unchanged - this
 * controller only adds the admin mutation endpoints.
 */
@RestController
@RequestMapping("/api/helpdesk/admin/categories")
public class AdminCategoryController {

    private final AdminReferenceDataService adminReferenceDataService;

    public AdminCategoryController(AdminReferenceDataService adminReferenceDataService) {
        this.adminReferenceDataService = adminReferenceDataService;
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminReferenceDataService.createCategory(request));
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable Long id, @RequestBody CategoryUpdateRequest request) {
        return adminReferenceDataService.updateCategory(id, request);
    }
}
