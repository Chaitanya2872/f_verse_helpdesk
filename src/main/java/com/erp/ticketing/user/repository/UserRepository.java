package com.erp.ticketing.user.repository;

import com.erp.ticketing.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByErpUserId(String erpUserId);

    Optional<User> findByEmailIgnoreCase(String email);

    @Query("select u from User u where u.active = true and (lower(u.name) like lower(concat('%', :q, '%')) "
            + "or lower(u.email) like lower(concat('%', :q, '%'))) order by u.name")
    List<User> search(@Param("q") String q, Pageable pageable);
}
