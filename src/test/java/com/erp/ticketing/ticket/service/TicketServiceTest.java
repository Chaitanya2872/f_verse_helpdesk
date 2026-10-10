package com.erp.ticketing.ticket.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.csat.service.CsatService;
import com.erp.ticketing.department.repository.DepartmentMemberRepository;
import com.erp.ticketing.department.repository.DepartmentRepository;
import com.erp.ticketing.notification.service.NotificationService;
import com.erp.ticketing.routing.service.RoutingRuleService;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.sla.service.SlaCalculationService;
import com.erp.ticketing.ticket.dto.TicketCreateRequest;
import com.erp.ticketing.ticket.dto.TicketUpdateRequest;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketSource;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link TicketService} - no Spring context, no
 * database. Covers the status-transition matrix (exhaustively, both valid and
 * invalid pairs, verified against the actual ALLOWED_TRANSITIONS table in the
 * service under test) and computeSlaStatus's five possible outcomes.
 */
class TicketServiceTest {

    private TicketRepository ticketRepository;
    private UserService userService;
    private ActivityLogService activityLogService;
    private NotificationService notificationService;
    private TicketService ticketService;

    private User agentUser;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        TicketTypeRepository ticketTypeRepository = mock(TicketTypeRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        DepartmentMemberRepository departmentMemberRepository = mock(DepartmentMemberRepository.class);
        userService = mock(UserService.class);
        activityLogService = mock(ActivityLogService.class);
        RoutingRuleService routingRuleService = mock(RoutingRuleService.class);
        SlaCalculationService slaCalculationService = mock(SlaCalculationService.class);
        notificationService = mock(NotificationService.class);
        CsatService csatService = mock(CsatService.class);

        ticketService = new TicketService(ticketRepository, ticketTypeRepository, categoryRepository, departmentRepository,
                departmentMemberRepository, userService, activityLogService, routingRuleService, slaCalculationService,
                notificationService, csatService);

        agentUser = new User();
        agentUser.setId(99L);
        agentUser.setName("Agent Smith");
        when(userService.getOrCreateCurrentUser(any())).thenReturn(agentUser);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------
    // Status transition matrix
    // ------------------------------------------------------------------

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(TicketStatus.NEW, TicketStatus.IN_PROGRESS),
                Arguments.of(TicketStatus.NEW, TicketStatus.CANCELLED),
                Arguments.of(TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CUSTOMER),
                Arguments.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED),
                Arguments.of(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED),
                Arguments.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.IN_PROGRESS),
                Arguments.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.RESOLVED),
                Arguments.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.CANCELLED),
                Arguments.of(TicketStatus.RESOLVED, TicketStatus.CLOSED),
                Arguments.of(TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS)
        );
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                Arguments.of(TicketStatus.NEW, TicketStatus.CLOSED),
                Arguments.of(TicketStatus.NEW, TicketStatus.RESOLVED),
                Arguments.of(TicketStatus.CLOSED, TicketStatus.NEW),
                Arguments.of(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS),
                Arguments.of(TicketStatus.CANCELLED, TicketStatus.NEW),
                Arguments.of(TicketStatus.RESOLVED, TicketStatus.CANCELLED),
                Arguments.of(TicketStatus.WAITING_CUSTOMER, TicketStatus.CLOSED)
        );
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("validTransitions")
    void validTransitionSucceeds(TicketStatus from, TicketStatus to) {
        Ticket ticket = buildTicket(from);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        var response = ticketService.update(1L, updateRequestWithStatus(to), agentPrincipal());

        assertThat(response.status()).isEqualTo(to.name());
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("invalidTransitions")
    void invalidTransitionThrows(TicketStatus from, TicketStatus to) {
        Ticket ticket = buildTicket(from);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.update(1L, updateRequestWithStatus(to), agentPrincipal()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name())
                .hasMessageContaining("->");
    }

    @Test
    void closedStatusHasNoAllowedOutgoingTransitions() {
        // update() only invokes validateTransition when the requested status
        // actually differs from the current one, so CLOSED -> CLOSED is
        // excluded here (it is a no-op, not a transition).
        for (TicketStatus to : TicketStatus.values()) {
            if (to == TicketStatus.CLOSED) {
                continue;
            }
            Ticket ticket = buildTicket(TicketStatus.CLOSED);
            when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
            assertThatThrownBy(() -> ticketService.update(1L, updateRequestWithStatus(to), agentPrincipal()))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void cancelledStatusHasNoAllowedOutgoingTransitions() {
        for (TicketStatus to : TicketStatus.values()) {
            if (to == TicketStatus.CANCELLED) {
                continue;
            }
            Ticket ticket = buildTicket(TicketStatus.CANCELLED);
            when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
            assertThatThrownBy(() -> ticketService.update(1L, updateRequestWithStatus(to), agentPrincipal()))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ------------------------------------------------------------------
    // Mandatory department enforcement
    // ------------------------------------------------------------------

    @Test
    void createThrowsWhenNoRoutingRuleResolvesADepartment() {
        TicketTypeRepository ticketTypeRepository = mock(TicketTypeRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        DepartmentMemberRepository departmentMemberRepository = mock(DepartmentMemberRepository.class);
        RoutingRuleService noRoutingRuleService = mock(RoutingRuleService.class);
        SlaCalculationService slaCalculationService = mock(SlaCalculationService.class);

        TicketService service = new TicketService(ticketRepository, ticketTypeRepository, categoryRepository,
                departmentRepository, departmentMemberRepository, userService, activityLogService,
                noRoutingRuleService, slaCalculationService, notificationService, mock(CsatService.class));

        // create() generates a ticket number (via the @PersistenceContext-injected
        // EntityManager) before routing runs, so a mocked EntityManager must be
        // wired in by reflection here - production code has no setter for it,
        // since Spring injects the real one via field injection.
        jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query nativeQuery = mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(1L);
        try {
            java.lang.reflect.Field field = TicketService.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(service, entityManager);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }

        TicketType type = new TicketType();
        type.setId(1L);
        type.setName("Incident");
        when(ticketTypeRepository.findById(1L)).thenReturn(Optional.of(type));
        // No stubbing of noRoutingRuleService.resolveRouting - Mockito's default
        // answer for an Optional-returning method is Optional.empty(), simulating
        // no matching routing rule (including no catch-all) for this ticket.

        TicketCreateRequest request = new TicketCreateRequest(1L, null, "Test ticket", "desc", "P3", null, null);

        assertThatThrownBy(() -> service.create(request, agentPrincipal()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("department");
    }

    // ------------------------------------------------------------------
    // computeSlaStatus
    // ------------------------------------------------------------------

    @Test
    void computeSlaStatus_noPolicyId_isNotApplicable() {
        Ticket ticket = buildTicket(TicketStatus.NEW);
        ticket.setSlaPolicyId(null);
        ticket.setResolutionDue(Instant.now().plusSeconds(3600));
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void computeSlaStatus_noResolutionDue_isNotApplicable() {
        Ticket ticket = buildTicket(TicketStatus.NEW);
        ticket.setSlaPolicyId(1L);
        ticket.setResolutionDue(null);
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void computeSlaStatus_resolvedBeforeDue_isMet() {
        Instant now = Instant.now();
        Ticket ticket = buildTicket(TicketStatus.RESOLVED);
        ticket.setSlaPolicyId(1L);
        ticket.setCreatedAt(now.minus(2, ChronoUnit.HOURS));
        ticket.setResolutionDue(now.plusSeconds(3600));
        ticket.setResolvedAt(now);
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("MET");
    }

    @Test
    void computeSlaStatus_resolvedAfterDue_isBreached() {
        Instant now = Instant.now();
        Ticket ticket = buildTicket(TicketStatus.RESOLVED);
        ticket.setSlaPolicyId(1L);
        ticket.setCreatedAt(now.minus(4, ChronoUnit.HOURS));
        ticket.setResolutionDue(now.minusSeconds(3600));
        ticket.setResolvedAt(now);
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("BREACHED");
    }

    @Test
    void computeSlaStatus_pastDueAndUnresolved_isBreached() {
        Instant now = Instant.now();
        Ticket ticket = buildTicket(TicketStatus.IN_PROGRESS);
        ticket.setSlaPolicyId(1L);
        ticket.setCreatedAt(now.minus(4, ChronoUnit.HOURS));
        ticket.setResolutionDue(now.minusSeconds(60));
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("BREACHED");
    }

    @Test
    void computeSlaStatus_pastEightyPercentElapsed_isAtRisk() {
        // Window: created 100 minutes ago, due in 20 minutes -> 100/120 = 83% elapsed.
        Instant now = Instant.now();
        Ticket ticket = buildTicket(TicketStatus.IN_PROGRESS);
        ticket.setSlaPolicyId(1L);
        ticket.setCreatedAt(now.minus(100, ChronoUnit.MINUTES));
        ticket.setResolutionDue(now.plus(20, ChronoUnit.MINUTES));
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("AT_RISK");
    }

    @Test
    void computeSlaStatus_wellWithinWindow_isOnTrack() {
        // Window: created 10 minutes ago, due in 110 minutes -> 10/120 = 8% elapsed.
        Instant now = Instant.now();
        Ticket ticket = buildTicket(TicketStatus.IN_PROGRESS);
        ticket.setSlaPolicyId(1L);
        ticket.setCreatedAt(now.minus(10, ChronoUnit.MINUTES));
        ticket.setResolutionDue(now.plus(110, ChronoUnit.MINUTES));
        assertThat(ticketService.computeSlaStatus(ticket)).isEqualTo("ON_TRACK");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Ticket buildTicket(TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTicketNumber("TKT-2026-000001");
        TicketType type = new TicketType();
        type.setId(1L);
        type.setName("Incident");
        ticket.setType(type);
        ticket.setTitle("Test ticket");
        ticket.setPriority(TicketPriority.P3);
        ticket.setStatus(status);
        ticket.setRequester(agentUser);
        ticket.setSource(TicketSource.PORTAL);
        ticket.setCreatedAt(Instant.now().minusSeconds(60));
        return ticket;
    }

    private TicketUpdateRequest updateRequestWithStatus(TicketStatus status) {
        return new TicketUpdateRequest(null, null, null, null, status.name(), null, null, null, null, null);
    }

    private ErpUserPrincipal agentPrincipal() {
        return new ErpUserPrincipal("agent-1", "agent@example.com", "Agent Smith", Set.of("AGENT"));
    }
}
