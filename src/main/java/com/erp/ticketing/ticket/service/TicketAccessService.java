package com.erp.ticketing.ticket.service;

import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.department.model.DepartmentMember;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.model.Confidentiality;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketAccessGrant;
import com.erp.ticketing.ticket.repository.TicketAccessGrantRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single place that decides who may see a ticket (BRS 3.1, 7.1, 18.1).
 *
 * <ul>
 *   <li>Requester-only callers see only tickets they requested or created.</li>
 *   <li>Admins see every STANDARD ticket; agents and managers see STANDARD tickets of the
 *       departments they belong to, plus any they requested, created or are assigned.</li>
 *   <li>RESTRICTED / CONFIDENTIAL tickets are visible only to the requester,
 *       creator, current assignee and explicitly granted users; RESTRICTED
 *       additionally opens to leads of the owning department. Platform ADMIN
 *       gets no implicit access - it must be granted.</li>
 * </ul>
 * Anything the caller cannot see is reported as not found, never forbidden, so a
 * guessed ID reveals nothing.
 */
@Service
public class TicketAccessService {

    private final TicketAccessGrantRepository grantRepository;
    private final DepartmentMemberRepository departmentMemberRepository;
    private final UserService userService;

    public TicketAccessService(TicketAccessGrantRepository grantRepository,
                                DepartmentMemberRepository departmentMemberRepository,
                                UserService userService) {
        this.grantRepository = grantRepository;
        this.departmentMemberRepository = departmentMemberRepository;
        this.userService = userService;
    }

    /**
     * Visibility of whoever is making the current request, so reports and dashboards can count only
     * tickets that person may see. Outside a request (no authenticated principal) nothing is filtered,
     * which only internal jobs ever rely on.
     */
    public Specification<Ticket> currentVisibility() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ErpUserPrincipal principal) {
            return visibleTo(principal, userService.getOrCreateCurrentUser(principal));
        }
        return Specification.where(null);
    }

    public boolean isRequesterOnly(ErpUserPrincipal principal) {
        Set<String> roles = principal.roles();
        return roles.stream().noneMatch(r ->
                "AGENT".equalsIgnoreCase(r) || "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
    }

    public boolean canView(Ticket ticket, ErpUserPrincipal principal, User user) {
        if (isRequesterOnly(principal)) {
            return isRequesterOrCreator(ticket, user);
        }
        boolean assignee = ticket.getAssignee() != null && ticket.getAssignee().getId().equals(user.getId());
        if (isRequesterOrCreator(ticket, user) || assignee) {
            return true;
        }
        if (ticket.getConfidentiality() == Confidentiality.STANDARD
                && (isAdmin(principal) || departmentIds(user.getId()).contains(ticket.getDepartment().getId()))) {
            return true;
        }
        if (grantRepository.hasActiveGrant(ticket.getId(), user.getId(), java.time.Instant.now())) {
            return true;
        }
        return ticket.getConfidentiality() == Confidentiality.RESTRICTED
                && leadsDepartment(user.getId(), ticket.getDepartment().getId());
    }

    public void assertVisible(Ticket ticket, ErpUserPrincipal principal, User user) {
        if (!canView(ticket, principal, user)) {
            throw new NotFoundException("Ticket not found: " + ticket.getId());
        }
    }

    /** Same rule as {@link #canView} expressed as a query predicate for list and search endpoints. */
    public Specification<Ticket> visibleTo(ErpUserPrincipal principal, User user) {
        Long userId = user.getId();
        if (isRequesterOnly(principal)) {
            return (root, query, cb) -> cb.or(
                    cb.equal(root.get("requester").get("id"), userId),
                    cb.equal(root.join("creator", jakarta.persistence.criteria.JoinType.LEFT).get("id"), userId));
        }
        List<DepartmentMember> memberships = departmentMemberRepository.findByUser_Id(userId);
        List<Long> ledDepartments = memberships.stream()
                .filter(DepartmentMember::isLead)
                .map(m -> m.getDepartment().getId())
                .toList();
        List<Long> memberDepartments = memberships.stream().map(m -> m.getDepartment().getId()).toList();
        boolean admin = isAdmin(principal);
        return (root, query, cb) -> {
            List<Predicate> any = new ArrayList<>();
            if (admin) {
                any.add(cb.equal(root.get("confidentiality"), Confidentiality.STANDARD));
            } else if (!memberDepartments.isEmpty()) {
                any.add(cb.and(
                        cb.equal(root.get("confidentiality"), Confidentiality.STANDARD),
                        root.get("department").get("id").in(memberDepartments)));
            }
            any.add(cb.equal(root.get("requester").get("id"), userId));
            any.add(cb.equal(root.join("creator", jakarta.persistence.criteria.JoinType.LEFT).get("id"), userId));
            any.add(cb.equal(root.join("assignee", jakarta.persistence.criteria.JoinType.LEFT).get("id"), userId));
            any.add(cb.exists(grantSubquery(root, query, cb, userId)));
            if (!ledDepartments.isEmpty()) {
                any.add(cb.and(
                        cb.equal(root.get("confidentiality"), Confidentiality.RESTRICTED),
                        root.get("department").get("id").in(ledDepartments)));
            }
            return cb.or(any.toArray(new Predicate[0]));
        };
    }

    private Subquery<Long> grantSubquery(Root<Ticket> root, CriteriaQuery<?> query, CriteriaBuilder cb, Long userId) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<TicketAccessGrant> grant = sub.from(TicketAccessGrant.class);
        sub.select(grant.<Long>get("id")).where(
                cb.equal(grant.get("ticket").get("id"), root.get("id")),
                cb.equal(grant.get("user").get("id"), userId),
                cb.or(cb.isNull(grant.get("expiresAt")),
                        cb.greaterThan(grant.<java.time.Instant>get("expiresAt"), java.time.Instant.now())));
        return sub;
    }

    /**
     * Managers and admins may grant access when they can see the ticket themselves, and the owning
     * department's manager may always grant - otherwise an unassigned confidential ticket would be
     * visible to nobody and could never be triaged. Granting is audited; it never reveals the ticket.
     */
    public boolean canGrant(Ticket ticket, ErpUserPrincipal principal, User user) {
        boolean managerial = principal.roles().stream()
                .anyMatch(r -> "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r));
        if (!managerial) {
            return false;
        }
        User deptManager = ticket.getDepartment().getManager();
        return canView(ticket, principal, user) || (deptManager != null && deptManager.getId().equals(user.getId()));
    }

    private boolean isAdmin(ErpUserPrincipal principal) {
        return principal.roles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r));
    }

    private Set<Long> departmentIds(Long userId) {
        return departmentMemberRepository.findByUser_Id(userId).stream()
                .map(m -> m.getDepartment().getId()).collect(java.util.stream.Collectors.toSet());
    }

    private boolean isRequesterOrCreator(Ticket ticket, User user) {
        return ticket.getRequester().getId().equals(user.getId())
                || (ticket.getCreator() != null && ticket.getCreator().getId().equals(user.getId()));
    }

    private boolean leadsDepartment(Long userId, Long departmentId) {
        return departmentMemberRepository.findByDepartment_IdAndUser_Id(departmentId, userId)
                .map(DepartmentMember::isLead).orElse(false);
    }
}
