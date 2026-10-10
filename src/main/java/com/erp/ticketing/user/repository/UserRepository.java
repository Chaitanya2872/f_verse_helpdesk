package com.erp.ticketing.user.repository;

import com.erp.ticketing.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByErpUserId(String erpUserId);

    Optional<User> findByEmailIgnoreCase(String email);
}
