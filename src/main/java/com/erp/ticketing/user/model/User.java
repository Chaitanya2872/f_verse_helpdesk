package com.erp.ticketing.user.model;

import com.erp.ticketing.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A user known to this system, keyed by the stable {@code erpUserId}. Users
 * with a {@code passwordHash} can log in locally via {@code AuthService} to
 * obtain a JWT; the {@code erpUserId}/email/name fields are otherwise still
 * refreshed lazily via {@code UserService.getOrCreateCurrentUser} for
 * requests authenticated by a validated token.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    @Column(name = "erp_user_id", nullable = false, unique = true, length = 100)
    private String erpUserId;

    @Column(name = "email")
    private String email;

    @Column(name = "name")
    private String name;

    /**
     * BCrypt hash of the user's login password. Null for users that only
     * exist via legacy header-asserted identity and have never had local
     * credentials set - {@code AuthService} treats a null hash as "cannot
     * log in locally".
     */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @PrePersist
    protected void onUserCreate() {
        Instant now = Instant.now();
        if (getCreatedAt() == null) {
            setCreatedAt(now);
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUserUpdate() {
        updatedAt = Instant.now();
    }
}
