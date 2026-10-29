package com.erp.ticketing.integration.notification;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.integration.notification.model.DeliveryAttempt;
import com.erp.ticketing.integration.notification.model.NotificationOutbox;
import com.erp.ticketing.integration.notification.model.TicketNotificationRecipient;
import com.erp.ticketing.integration.notification.repository.DeliveryAttemptRepository;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.integration.notification.repository.TicketNotificationRecipientRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Admin-only (the {@code /admin/**} matcher in SecurityConfig) management of who receives ticket
 * notifications, and visibility of / retry for the outbox. Responses carry delivery state only - never the
 * notification-service's raw responses or any secret.
 */
@RestController
@RequestMapping("/api/helpdesk/admin")
public class NotificationAdminController {

    public record RecipientRequest(Long userId, String eventType, Long departmentId) {
    }

    public record RecipientResponse(Long id, Long userId, String userName, String eventType, Long departmentId,
                                    String departmentName, boolean active) {
    }

    public record OutboxResponse(Long id, String eventType, String channel, Long ticketId, Long recipientUserId,
                                 String status, int attempts, Instant nextAttemptAt, String lastError,
                                 Instant createdAt, Instant acceptedAt) {
    }

    public record AttemptResponse(int attemptNo, String outcome, Integer httpStatus, String detail, Instant attemptedAt) {
    }

    private final TicketNotificationRecipientRepository recipients;
    private final NotificationOutboxRepository outbox;
    private final DeliveryAttemptRepository attempts;
    private final NotificationDispatcher dispatcher;
    private final UserService userService;
    private final DepartmentRepository departments;

    public NotificationAdminController(TicketNotificationRecipientRepository recipients,
                                       NotificationOutboxRepository outbox,
                                       DeliveryAttemptRepository attempts,
                                       NotificationDispatcher dispatcher,
                                       UserService userService,
                                       DepartmentRepository departments) {
        this.recipients = recipients;
        this.outbox = outbox;
        this.attempts = attempts;
        this.dispatcher = dispatcher;
        this.userService = userService;
        this.departments = departments;
    }

    @GetMapping("/notification-recipients")
    @Transactional(readOnly = true)
    public List<RecipientResponse> listRecipients() {
        return recipients.findAllWithDetails().stream().map(NotificationAdminController::toResponse).toList();
    }

    @PostMapping("/notification-recipients")
    @Transactional
    public ResponseEntity<RecipientResponse> addRecipient(@RequestBody RecipientRequest request) {
        if (request.userId() == null || request.eventType() == null) {
            throw new BadRequestException("userId and eventType are required");
        }
        NotificationEventType type;
        try {
            type = NotificationEventType.valueOf(request.eventType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid eventType: " + request.eventType());
        }
        if (type == NotificationEventType.TICKET_ASSIGNED) {
            throw new BadRequestException("The handler is notified automatically; only TICKET_CREATED and "
                    + "TICKET_RESOLVED have configurable recipients");
        }
        User user = userService.getRequiredUser(request.userId());
        Department department = request.departmentId() == null ? null : departments.findById(request.departmentId())
                .orElseThrow(() -> new NotFoundException("Department not found: " + request.departmentId()));

        boolean duplicate = department == null
                ? recipients.existsByUser_IdAndEventTypeAndDepartmentIsNull(user.getId(), type)
                : recipients.existsByUser_IdAndEventTypeAndDepartment_Id(user.getId(), type, department.getId());
        if (duplicate) {
            throw new BadRequestException("This user already receives " + type + " notifications for that scope");
        }

        TicketNotificationRecipient row = new TicketNotificationRecipient();
        row.setUser(user);
        row.setEventType(type);
        row.setDepartment(department);
        try {
            row = recipients.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("This user already receives " + type + " notifications for that scope");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(row));
    }

    @DeleteMapping("/notification-recipients/{id}")
    @Transactional
    public ResponseEntity<Void> removeRecipient(@PathVariable Long id) {
        if (!recipients.existsById(id)) {
            throw new NotFoundException("Recipient not found: " + id);
        }
        recipients.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notification-outbox")
    @Transactional(readOnly = true)
    public List<OutboxResponse> listOutbox(@RequestParam(defaultValue = "FAILED") String status) {
        OutboxStatus parsed;
        try {
            parsed = OutboxStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid status: " + status);
        }
        return outbox.findTop100ByStatusOrderByIdDesc(parsed).stream().map(NotificationAdminController::toResponse).toList();
    }

    @GetMapping("/notification-outbox/{id}/attempts")
    @Transactional(readOnly = true)
    public List<AttemptResponse> listAttempts(@PathVariable Long id) {
        return attempts.findByOutboxIdOrderByAttemptNoAsc(id).stream().map(NotificationAdminController::toResponse).toList();
    }

    @PostMapping("/notification-outbox/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        if (!dispatcher.retry(id)) {
            throw new BadRequestException("Only a FAILED notification can be retried");
        }
        return ResponseEntity.accepted().build();
    }

    private static RecipientResponse toResponse(TicketNotificationRecipient r) {
        Department d = r.getDepartment();
        return new RecipientResponse(r.getId(), r.getUser().getId(), r.getUser().getName(), r.getEventType().name(),
                d != null ? d.getId() : null, d != null ? d.getDepartmentName() : null, r.isActive());
    }

    private static OutboxResponse toResponse(NotificationOutbox o) {
        return new OutboxResponse(o.getId(), o.getEventType().name(), o.getChannel().name(), o.getTicketId(),
                o.getRecipientUserId(), o.getStatus().name(), o.getAttempts(), o.getNextAttemptAt(), o.getLastError(),
                o.getCreatedAt(), o.getAcceptedAt());
    }

    private static AttemptResponse toResponse(DeliveryAttempt a) {
        return new AttemptResponse(a.getAttemptNo(), a.getOutcome(), a.getHttpStatus(), a.getDetail(), a.getAttemptedAt());
    }
}
