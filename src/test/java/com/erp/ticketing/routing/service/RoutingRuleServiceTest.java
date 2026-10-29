package com.erp.ticketing.routing.service;

import com.erp.ticketing.department.model.Department;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.routing.dto.RoutingResolution;
import com.erp.ticketing.routing.model.RoutingRule;
import com.erp.ticketing.routing.repository.RoutingRuleRepository;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link RoutingRuleService#resolveRouting} - no
 * Spring context, no database. The repository call under test
 * (findByActiveTrueOrderByOrderIndexAsc) already does the "active" filtering
 * and order_index ordering at the SQL level, so these tests simulate that by
 * controlling exactly what list (and what order) the mocked repository hands
 * back, then assert on the first-match-wins resolution logic.
 */
class RoutingRuleServiceTest {

    private final RoutingRuleRepository routingRuleRepository = mock(RoutingRuleRepository.class);
    private final RoutingRuleService service = new RoutingRuleService(routingRuleRepository,
            mock(TicketTypeRepository.class), mock(CategoryRepository.class), mock(DepartmentRepository.class),
            mock(UserService.class), mock(com.erp.ticketing.audit.service.AuditService.class));

    @Test
    void specificRuleWinsOverWildcardWhenBothActiveAndOrderedFirst() {
        TicketType incident = ticketType(1L);
        RoutingRule specific = rule(1L, 0, incident, null, TicketPriority.P1, department(10L, "Escalations"), true);
        RoutingRule wildcard = rule(2L, 1, null, null, null, department(1L, "General Support"), true);
        when(routingRuleRepository.findByActiveTrueOrderByOrderIndexAsc()).thenReturn(List.of(specific, wildcard));

        RoutingResolution resolution = service.resolveRouting(incident, null, TicketPriority.P1).orElseThrow();

        assertThat(resolution.departmentId()).isEqualTo(10L);
    }

    @Test
    void fallsBackToWildcardWhenSpecificRuleConditionsDoNotMatch() {
        TicketType incident = ticketType(1L);
        RoutingRule specific = rule(1L, 0, incident, null, TicketPriority.P1, department(10L, "Escalations"), true);
        RoutingRule wildcard = rule(2L, 1, null, null, null, department(1L, "General Support"), true);
        when(routingRuleRepository.findByActiveTrueOrderByOrderIndexAsc()).thenReturn(List.of(specific, wildcard));

        // Same ticket type but a priority the specific rule does not claim.
        RoutingResolution resolution = service.resolveRouting(incident, null, TicketPriority.P3).orElseThrow();

        assertThat(resolution.departmentId()).isEqualTo(1L);
    }

    @Test
    void inactiveRulesAreNeverConsidered() {
        // The repository query itself filters out inactive rules (findByActiveTrue...),
        // so an inactive rule that would otherwise match first is simulated here by
        // simply never including it in what the mocked repository returns.
        TicketType incident = ticketType(1L);
        RoutingRule onlyActiveWildcard = rule(2L, 1, null, null, null, department(1L, "General Support"), true);
        when(routingRuleRepository.findByActiveTrueOrderByOrderIndexAsc()).thenReturn(List.of(onlyActiveWildcard));

        RoutingResolution resolution = service.resolveRouting(incident, null, TicketPriority.P1).orElseThrow();

        assertThat(resolution.departmentId()).isEqualTo(1L);
    }

    @Test
    void orderIndexTiesResolveToWhicheverRuleTheRepositoryReturnsFirst() {
        TicketType incident = ticketType(1L);
        // Both rules share order_index=5 and both match; the repository is the
        // sole source of ordering truth for ties, so the service must simply
        // take the first match in whatever order it is handed.
        RoutingRule firstInTieOrder = rule(1L, 5, incident, null, null, department(20L, "Department A"), true);
        RoutingRule secondInTieOrder = rule(2L, 5, incident, null, null, department(30L, "Department B"), true);
        when(routingRuleRepository.findByActiveTrueOrderByOrderIndexAsc())
                .thenReturn(List.of(firstInTieOrder, secondInTieOrder));

        RoutingResolution resolution = service.resolveRouting(incident, null, TicketPriority.P2).orElseThrow();

        assertThat(resolution.departmentId()).isEqualTo(20L);
    }

    @Test
    void noMatchReturnsEmpty() {
        TicketType incident = ticketType(1L);
        TicketType serviceRequest = ticketType(2L);
        RoutingRule incidentOnly = rule(1L, 0, incident, null, null, department(10L, "Escalations"), true);
        when(routingRuleRepository.findByActiveTrueOrderByOrderIndexAsc()).thenReturn(List.of(incidentOnly));

        assertThat(service.resolveRouting(serviceRequest, null, TicketPriority.P2)).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private RoutingRule rule(Long id, int orderIndex, TicketType type, Category category, TicketPriority priority,
                              Department department, boolean active) {
        RoutingRule rule = new RoutingRule();
        rule.setId(id);
        rule.setName("rule-" + id);
        rule.setOrderIndex(orderIndex);
        rule.setTicketType(type);
        rule.setCategory(category);
        rule.setPriority(priority);
        rule.setDepartment(department);
        rule.setActive(active);
        return rule;
    }

    private TicketType ticketType(Long id) {
        TicketType type = new TicketType();
        type.setId(id);
        type.setName("type-" + id);
        return type;
    }

    private Department department(Long id, String name) {
        Department department = new Department();
        department.setId(id);
        department.setDepartmentName(name);
        return department;
    }
}
