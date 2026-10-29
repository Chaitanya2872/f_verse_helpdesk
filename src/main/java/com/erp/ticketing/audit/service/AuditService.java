package com.erp.ticketing.audit.service;

import com.erp.ticketing.audit.model.AuditEvent;
import com.erp.ticketing.audit.repository.AuditEventRepository;
import com.erp.ticketing.security.CorrelationIdFilter;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.service.UserService;
import com.erp.ticketing.user.model.User;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Writes append-only audit rows inside the calling transaction, so an audited
 * change and its audit record commit or roll back together.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final UserService userService;

    public AuditService(AuditEventRepository repository, UserService userService) {
        this.repository = repository;
        this.userService = userService;
    }

    /** Records on behalf of whoever is authenticated on this request - for admin services that take no principal. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordForCurrentUser(String action, String targetType, Object targetId,
                                     Map<String, Object> before, Map<String, Object> after) {
        User actor = null;
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ErpUserPrincipal principal) {
            actor = userService.getOrCreateCurrentUser(principal);
        }
        record(actor, action, targetType, targetId, before, after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(User actor, String action, String targetType, Object targetId,
                       Map<String, Object> before, Map<String, Object> after) {
        AuditEvent event = new AuditEvent();
        event.setActor(actor);
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId == null ? null : targetId.toString());
        event.setSourceChannel("WEB");
        event.setCorrelationId(MDC.get(CorrelationIdFilter.MDC_KEY));
        event.setBefore(before);
        event.setAfter(after);
        repository.save(event);
    }
}
