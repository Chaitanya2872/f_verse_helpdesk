package com.erp.ticketing.department.service;

import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.department.dto.DepartmentCreateRequest;
import com.erp.ticketing.department.dto.DepartmentMemberResponse;
import com.erp.ticketing.department.dto.DepartmentResponse;
import com.erp.ticketing.department.dto.DepartmentUpdateRequest;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.model.DepartmentMember;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import com.erp.ticketing.audit.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMemberRepository departmentMemberRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final UserService userService;
    private final AuditService auditService;

    public DepartmentService(DepartmentRepository departmentRepository,
                              DepartmentMemberRepository departmentMemberRepository,
                              SlaPolicyRepository slaPolicyRepository,
                              UserService userService,
                              AuditService auditService) {
        this.auditService = auditService;
        this.departmentRepository = departmentRepository;
        this.departmentMemberRepository = departmentMemberRepository;
        this.slaPolicyRepository = slaPolicyRepository;
        this.userService = userService;
    }

    @Transactional
    public DepartmentResponse create(DepartmentCreateRequest request) {
        Department department = new Department();
        department.setDepartmentName(request.departmentName());
        department.setCode(normalizeCode(request.code(), request.departmentName()));
        department.setDescription(request.description());
        if (request.managerId() != null) {
            department.setManager(userService.getRequiredUser(request.managerId()));
        }
        if (request.slaPolicyId() != null) {
            department.setSlaPolicyId(requireSlaPolicy(request.slaPolicyId()));
        }
        department = departmentRepository.save(department);
        auditService.recordForCurrentUser("DEPARTMENT_CREATED", "DEPARTMENT", department.getId(), null, snapshot(department));
        return toResponse(department);
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> list() {
        return departmentRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DepartmentResponse get(Long id) {
        return toResponse(getRequiredDepartment(id));
    }

    @Transactional
    public DepartmentResponse update(Long id, DepartmentUpdateRequest request) {
        Department department = getRequiredDepartment(id);
        java.util.Map<String, Object> before = snapshot(department);
        if (request.departmentName() != null) {
            department.setDepartmentName(request.departmentName());
        }
        if (request.code() != null && !request.code().isBlank()) {
            department.setCode(normalizeCode(request.code(), department.getDepartmentName()));
        }
        if (request.description() != null) {
            department.setDescription(request.description());
        }
        if (request.managerId() != null) {
            department.setManager(userService.getRequiredUser(request.managerId()));
        }
        if (request.slaPolicyId() != null) {
            department.setSlaPolicyId(requireSlaPolicy(request.slaPolicyId()));
        }
        department = departmentRepository.save(department);
        auditService.recordForCurrentUser("DEPARTMENT_UPDATED", "DEPARTMENT", id, before, snapshot(department));
        return toResponse(department);
    }

    /**
     * Upper-case letters and digits, at most 5. When none is given the code is the initials of the
     * name ("Human Resources" gives HR), or its first three letters for a single word.
     */
    static String normalizeCode(String requested, String departmentName) {
        String code = requested == null ? "" : requested.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (code.isEmpty()) {
            StringBuilder initials = new StringBuilder();
            for (String word : departmentName.trim().split("\\W+")) {
                if (!word.isEmpty()) {
                    initials.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            code = initials.toString().replaceAll("[^A-Z0-9]", "");
            if (code.length() < 2) {
                String letters = departmentName.toUpperCase().replaceAll("[^A-Z0-9]", "");
                code = letters.substring(0, Math.min(3, letters.length()));
            }
        }
        if (code.isEmpty()) {
            code = "HD";
        }
        return code.substring(0, Math.min(5, code.length()));
    }

    private java.util.Map<String, Object> snapshot(Department d) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", d.getDepartmentName());
        m.put("code", d.getCode());
        m.put("managerId", d.getManager() != null ? d.getManager().getId() : null);
        m.put("slaPolicyId", d.getSlaPolicyId());
        return m;
    }

    @Transactional
    public DepartmentMemberResponse addMember(Long departmentId, Long userId, boolean isLead) {
        Department department = getRequiredDepartment(departmentId);
        User user = userService.getRequiredUser(userId);
        DepartmentMember member = departmentMemberRepository.findByDepartment_IdAndUser_Id(departmentId, userId)
                .orElseGet(DepartmentMember::new);
        member.setDepartment(department);
        member.setUser(user);
        member.setLead(isLead);
        member = departmentMemberRepository.save(member);
        auditService.recordForCurrentUser("DEPARTMENT_MEMBER_SET", "DEPARTMENT", departmentId, null,
                java.util.Map.of("userId", userId, "lead", isLead));
        return toMemberResponse(member);
    }

    @Transactional
    public void removeMember(Long departmentId, Long userId) {
        // 404 if the department itself doesn't exist; removing a membership that
        // doesn't exist is a no-op (DELETE is idempotent), matching typical REST semantics.
        getRequiredDepartment(departmentId);
        departmentMemberRepository.deleteByDepartment_IdAndUser_Id(departmentId, userId);
        auditService.recordForCurrentUser("DEPARTMENT_MEMBER_REMOVED", "DEPARTMENT", departmentId,
                java.util.Map.of("userId", userId), null);
    }

    @Transactional(readOnly = true)
    public List<DepartmentMemberResponse> listMembers(Long departmentId) {
        getRequiredDepartment(departmentId);
        return departmentMemberRepository.findByDepartment_Id(departmentId).stream().map(this::toMemberResponse).toList();
    }

    private Long requireSlaPolicy(Long slaPolicyId) {
        if (!slaPolicyRepository.existsById(slaPolicyId)) {
            throw new NotFoundException("SLA policy not found: " + slaPolicyId);
        }
        return slaPolicyId;
    }

    private Department getRequiredDepartment(Long id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Department not found: " + id));
    }

    private DepartmentResponse toResponse(Department department) {
        User manager = department.getManager();
        return new DepartmentResponse(
                department.getId(),
                department.getDepartmentName(),
                department.getCode(),
                department.getDescription(),
                manager != null ? manager.getId() : null,
                manager != null ? manager.getName() : null,
                department.getSlaPolicyId());
    }

    private DepartmentMemberResponse toMemberResponse(DepartmentMember member) {
        return new DepartmentMemberResponse(member.getUser().getId(), member.getUser().getName(), member.isLead());
    }
}
