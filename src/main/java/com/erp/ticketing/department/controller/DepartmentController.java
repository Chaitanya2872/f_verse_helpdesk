package com.erp.ticketing.department.controller;

import com.erp.ticketing.department.dto.DepartmentCreateRequest;
import com.erp.ticketing.department.dto.DepartmentMemberAddRequest;
import com.erp.ticketing.department.dto.DepartmentMemberResponse;
import com.erp.ticketing.department.dto.DepartmentResponse;
import com.erp.ticketing.department.dto.DepartmentUpdateRequest;
import com.erp.ticketing.department.service.DepartmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Department CRUD and membership management. Reads are open to any
 * authenticated role (needed for ticket-assignment dropdowns); mutating
 * endpoints are restricted to ROLE_ADMIN via matchers in
 * {@link com.erp.ticketing.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/helpdesk/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @GetMapping
    public List<DepartmentResponse> list() {
        return departmentService.list();
    }

    @GetMapping("/{id}/members")
    public List<DepartmentMemberResponse> members(@PathVariable Long id) {
        return departmentService.listMembers(id);
    }

    @PostMapping
    public ResponseEntity<DepartmentResponse> create(@Valid @RequestBody DepartmentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(request));
    }

    @PutMapping("/{id}")
    public DepartmentResponse update(@PathVariable Long id, @RequestBody DepartmentUpdateRequest request) {
        return departmentService.update(id, request);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<DepartmentMemberResponse> addMember(@PathVariable Long id, @Valid @RequestBody DepartmentMemberAddRequest request) {
        DepartmentMemberResponse response = departmentService.addMember(id, request.userId(), request.isLead());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        departmentService.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }
}
