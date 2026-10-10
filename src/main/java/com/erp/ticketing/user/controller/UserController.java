package com.erp.ticketing.user.controller;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.dto.CurrentUserResponse;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/helpdesk")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal ErpUserPrincipal principal) {
        // Ensure the users table reflects the latest identity headers on every call,
        // and capture the resolved row so callers get the internal numeric id too -
        // needed by the frontend to build "my tickets" filters against
        // TicketResponse.assigneeId/requesterId, which are this internal id, not erpUserId.
        User currentUser = userService.getOrCreateCurrentUser(principal);
        return new CurrentUserResponse(currentUser.getId(), principal.erpUserId(), principal.email(), principal.name(), principal.roles());
    }
}
