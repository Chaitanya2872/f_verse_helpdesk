package com.erp.ticketing.sla.service;

import com.erp.ticketing.sla.dto.SlaDueDates;
import com.erp.ticketing.sla.model.SlaCalendar;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketType;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link SlaCalculationService} - no Spring
 * context, no database. Mirrors the seeded "P1 Critical" (business-hours,
 * priority-specific) and "Default SLA" (24x7, wildcard) policies from
 * V5__sla.sql without touching the real database.
 */
class SlaCalculationServiceTest {

    private final SlaPolicyRepository slaPolicyRepository = mock(SlaPolicyRepository.class);
    private final SlaCalculationService service = new SlaCalculationService(slaPolicyRepository);

    // ------------------------------------------------------------------
    // resolvePolicy
    // ------------------------------------------------------------------

    @Test
    void resolvePolicy_picksSpecificPolicyWhenPriorityMatches() {
        SlaPolicy p1Policy = policy(1L, Map.of("priority", "P1"), 30, 240, null);
        SlaPolicy defaultPolicy = policy(2L, null, 480, 2880, null);
        when(slaPolicyRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(p1Policy, defaultPolicy));

        TicketType incident = ticketType(1L);
        Optional<SlaPolicy> resolved = service.resolvePolicy(incident, TicketPriority.P1);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo(1L);
    }

    @Test
    void resolvePolicy_fallsBackToWildcardWhenPriorityDoesNotMatch() {
        SlaPolicy p1Policy = policy(1L, Map.of("priority", "P1"), 30, 240, null);
        SlaPolicy defaultPolicy = policy(2L, null, 480, 2880, null);
        when(slaPolicyRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(p1Policy, defaultPolicy));

        TicketType incident = ticketType(1L);
        Optional<SlaPolicy> resolved = service.resolvePolicy(incident, TicketPriority.P3);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo(2L);
    }

    @Test
    void resolvePolicy_matchesOnTicketTypeIdCondition() {
        SlaPolicy typeSpecific = policy(1L, Map.of("ticketTypeId", 4), 15, 120, null);
        SlaPolicy defaultPolicy = policy(2L, null, 480, 2880, null);
        when(slaPolicyRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(typeSpecific, defaultPolicy));

        assertThat(service.resolvePolicy(ticketType(4L), TicketPriority.P4).get().getId()).isEqualTo(1L);
        assertThat(service.resolvePolicy(ticketType(2L), TicketPriority.P4).get().getId()).isEqualTo(2L);
    }

    @Test
    void resolvePolicy_returnsEmptyWhenNoPolicyMatchesAndNoWildcardExists() {
        SlaPolicy p1Policy = policy(1L, Map.of("priority", "P1"), 30, 240, null);
        when(slaPolicyRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(p1Policy));

        assertThat(service.resolvePolicy(ticketType(1L), TicketPriority.P4)).isEmpty();
    }

    // ------------------------------------------------------------------
    // computeDueDates - 24x7 calendar (simple minute addition)
    // ------------------------------------------------------------------

    @Test
    void computeDueDates_24x7Calendar_simplyAddsMinutes() {
        SlaCalendar calendar = new SlaCalendar();
        calendar.setId(1L);
        calendar.setName("24x7");
        calendar.setTimezone("UTC");
        calendar.setWorkingHoursJson(null);

        SlaPolicy defaultPolicy = policy(2L, null, 480, 2880, calendar);

        Instant from = Instant.parse("2026-08-11T10:15:00Z"); // an arbitrary Tuesday
        SlaDueDates dueDates = service.computeDueDates(from, defaultPolicy);

        assertThat(dueDates.firstResponseDue()).isEqualTo(from.plusSeconds(480 * 60));
        assertThat(dueDates.resolutionDue()).isEqualTo(from.plusSeconds(2880 * 60));
    }

    // ------------------------------------------------------------------
    // computeDueDates - business-hours calendar (Mon-Fri 09:00-18:00 UTC)
    // ------------------------------------------------------------------

    @Test
    void computeDueDates_businessHoursCalendar_ticketCreatedSaturdayNightRollsForwardToMonday() {
        SlaCalendar calendar = businessHoursCalendar();
        SlaPolicy p1Policy = policy(1L, Map.of("priority", "P1"), 30, 240, calendar);

        // Saturday 22:00 UTC - well outside Mon-Fri 09:00-18:00 working hours.
        LocalDate saturday = LocalDate.of(2026, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        Instant from = saturday.atTime(22, 0).toInstant(ZoneOffset.UTC);

        SlaDueDates dueDates = service.computeDueDates(from, p1Policy);

        // Due dates must land on the following Monday, inside the 09:00-18:00 window.
        var firstResponseZoned = dueDates.firstResponseDue().atZone(ZoneOffset.UTC);
        var resolutionZoned = dueDates.resolutionDue().atZone(ZoneOffset.UTC);

        assertThat(firstResponseZoned.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(firstResponseZoned.toLocalTime()).isEqualTo(LocalTime.of(9, 30));

        assertThat(resolutionZoned.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(resolutionZoned.toLocalTime()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void computeDueDates_businessHoursCalendar_fromWithinWorkingHoursStaysSameDay() {
        SlaCalendar calendar = businessHoursCalendar();
        SlaPolicy p1Policy = policy(1L, Map.of("priority", "P1"), 30, 240, calendar);

        // A Wednesday at 10:00 UTC, well within the 09:00-18:00 window, with plenty
        // of room left in the day for both the 30-minute and 240-minute additions.
        LocalDate wednesday = LocalDate.of(2026, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
        Instant from = wednesday.atTime(10, 0).toInstant(ZoneOffset.UTC);

        SlaDueDates dueDates = service.computeDueDates(from, p1Policy);

        var firstResponseZoned = dueDates.firstResponseDue().atZone(ZoneOffset.UTC);
        var resolutionZoned = dueDates.resolutionDue().atZone(ZoneOffset.UTC);

        assertThat(firstResponseZoned.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(firstResponseZoned.toLocalTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(resolutionZoned.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(resolutionZoned.toLocalTime()).isEqualTo(LocalTime.of(14, 0));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SlaCalendar businessHoursCalendar() {
        SlaCalendar calendar = new SlaCalendar();
        calendar.setId(2L);
        calendar.setName("Business Hours (Mon-Fri 09:00-18:00 UTC)");
        calendar.setTimezone("UTC");
        Map<String, Object> hours = Map.of("start", "09:00", "end", "18:00");
        calendar.setWorkingHoursJson(Map.of(
                "monday", hours,
                "tuesday", hours,
                "wednesday", hours,
                "thursday", hours,
                "friday", hours));
        return calendar;
    }

    private SlaPolicy policy(Long id, Map<String, Object> conditions, int firstResponseMinutes,
                              int resolutionMinutes, SlaCalendar calendar) {
        SlaPolicy policy = new SlaPolicy();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setConditionsJson(conditions);
        policy.setFirstResponseMinutes(firstResponseMinutes);
        policy.setResolutionMinutes(resolutionMinutes);
        policy.setCalendar(calendar);
        policy.setActive(true);
        return policy;
    }

    private TicketType ticketType(Long id) {
        TicketType type = new TicketType();
        type.setId(id);
        type.setName("type-" + id);
        return type;
    }
}
