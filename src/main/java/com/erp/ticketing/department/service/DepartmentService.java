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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMemberRepository departmentMemberRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final UserService userService;

    public DepartmentService(DepartmentRepository departmentRepository,
                              DepartmentMemberRepository departmentMemberRepository,
                              SlaPolicyRepository slaPolicyRepository,
                              UserService userService) {
        this.departmentRepository = departmentRepository;
        this.departmentMemberRepository = departmentMemberRepository;
        this.slaPolicyRepository = slaPolicyRepository;
        this.userService = userService;
    }

    @Transactional
    public DepartmentResponse create(DepartmentCreateRequest request) {
        Department department = new Department();
        department.setDepartmentName(request.departmentName());
        department.setDescription(request.description());
        if (request.managerId() != null) {
            department.setManager(userService.getRequiredUser(request.managerId()));
        }
        if (request.slaPolicyId() != null) {
            department.setSlaPolicyId(requireSlaPolicy(request.slaPolicyId()));
        }
        department = departmentRepository.save(department);
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
        if (request.departmentName() != null) {
            department.setDepartmentName(request.departmentName());
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
        return toResponse(department);
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
        return toMemberResponse(member);
    }

    @Transactional
    public void removeMember(Long departmentId, Long userId) {
        // 404 if the department itself doesn't exist; removing a membership that
        // doesn't exist is a no-op (DELETE is idempotent), matching typical REST semantics.
        getRequiredDepartment(departmentId);
        departmentMemberRepository.deleteByDepartment_IdAndUser_Id(departmentId, userId);
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
                department.getDescription(),
                manager != null ? manager.getId() : null,
                manager != null ? manager.getName() : null,
                department.getSlaPolicyId());
    }

    private DepartmentMemberResponse toMemberResponse(DepartmentMember member) {
        return new DepartmentMemberResponse(member.getUser().getId(), member.getUser().getName(), member.isLead());
    }
}
