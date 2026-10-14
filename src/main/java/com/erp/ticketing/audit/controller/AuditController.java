package com.erp.ticketing.audit.controller;

import com.erp.ticketing.audit.dto.AuditEventResponse;
import com.erp.ticketing.audit.model.AuditEvent;
import com.erp.ticketing.audit.repository.AuditEventRepository;
import com.erp.ticketing.ticket.dto.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit trail; the /admin/** matcher in SecurityConfig restricts it to ADMIN. */
@RestController
@RequestMapping("/api/helpdesk/admin/audit")
public class AuditController {

    private final AuditEventRepository repository;

    public AuditController(AuditEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> list(@RequestParam(required = false) String targetType,
                                                  @RequestParam(required = false) String targetId,
                                                  @RequestParam(required = false) String action,
                                                  @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Specification<AuditEvent> spec = Specification.where(null);
        if (targetType != null && !targetType.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("targetType"), targetType));
        }
        if (targetId != null && !targetId.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("targetId"), targetId));
        }
        if (action != null && !action.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("action"), action));
        }
        return PageResponse.of(repository.findAll(spec, pageable).map(e -> new AuditEventResponse(
                e.getId(),
                e.getActor() != null ? e.getActor().getId() : null,
                e.getActor() != null ? e.getActor().getName() : null,
                e.getAction(), e.getTargetType(), e.getTargetId(), e.getCorrelationId(),
                e.getBefore(), e.getAfter(), e.getCreatedAt())));
    }
}
