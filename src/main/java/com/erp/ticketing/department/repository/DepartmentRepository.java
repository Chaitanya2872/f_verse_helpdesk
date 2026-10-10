package com.erp.ticketing.department.repository;

import com.erp.ticketing.department.model.Department;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
}
