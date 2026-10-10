package com.erp.ticketing.security;

import java.util.Set;

/**
 * The authenticated principal resolved from a validated JWT (see
 * {@link JwtAuthenticationFilter}). This is a plain value object, not a JPA
 * entity - {@code erpUserId} is the stable login key (JWT subject) used to
 * look up/create the corresponding {@link com.erp.ticketing.user.model.User}.
 */
public record ErpUserPrincipal(String erpUserId, String email, String name, Set<String> roles) {
}
