package com.erp.ticketing.user.controller;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.dto.CurrentUserResponse;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import com.erp.ticketing.user.dto.UserSummaryResponse;
import com.erp.ticketing.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/helpdesk")
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    public UserController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    /**
     * People who have used the helpdesk (accounts are created on first contact), for user pickers.
     * Needs at least two characters so the directory cannot be dumped; non-staff get names only.
     */
    @GetMapping("/users")
    public java.util.List<UserSummaryResponse> search(@RequestParam String q,
                                                      @AuthenticationPrincipal ErpUserPrincipal principal) {
        String needle = q == null ? "" : q.trim();
        if (needle.length() < 2) {
            return java.util.List.of();
        }
        boolean staff = principal.roles().stream().anyMatch(r ->
                "AGENT".equalsIgnoreCase(r) || "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
        return userRepository.search(needle, PageRequest.of(0, 10)).stream()
                .map(u -> new UserSummaryResponse(u.getId(), u.getName(), staff ? u.getEmail() : null))
                .toList();
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
