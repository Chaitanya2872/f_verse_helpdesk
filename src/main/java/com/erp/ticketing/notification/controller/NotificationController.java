package com.erp.ticketing.notification.controller;

import com.erp.ticketing.notification.dto.NotificationResponse;
import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    public NotificationController(NotificationService notificationService, UserService userService) {
        this.notificationService = notificationService;
        this.userService = userService;
    }

    @GetMapping
    public List<NotificationResponse> list(@RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
                                            @AuthenticationPrincipal ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        return notificationService.listForUser(currentUser.getId(), unreadOnly);
    }

    @PutMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        return notificationService.markRead(id, currentUser.getId());
    }
}
