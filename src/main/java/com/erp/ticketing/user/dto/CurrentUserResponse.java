package com.erp.ticketing.user.dto;

import java.util.Set;

public record CurrentUserResponse(Long id, String erpUserId, String email, String name, Set<String> roles) {
}
