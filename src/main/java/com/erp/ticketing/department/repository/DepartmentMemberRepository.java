package com.erp.ticketing.department.repository;

import com.erp.ticketing.department.model.DepartmentMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentMemberRepository extends JpaRepository<DepartmentMember, DepartmentMember.DepartmentMemberId> {

    List<DepartmentMember> findByDepartment_Id(Long departmentId);

    List<DepartmentMember> findByDepartment_IdAndLeadTrue(Long departmentId);

    /** Used by dashboard/reporting to resolve which department(s) a given user (manager/agent) belongs to. */
    List<DepartmentMember> findByUser_Id(Long userId);

    Optional<DepartmentMember> findByDepartment_IdAndUser_Id(Long departmentId, Long userId);

    void deleteByDepartment_IdAndUser_Id(Long departmentId, Long userId);
}
