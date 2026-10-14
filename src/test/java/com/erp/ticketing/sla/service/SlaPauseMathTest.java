package com.erp.ticketing.sla.service;

import com.erp.ticketing.sla.model.SlaCalendar;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Working-time maths behind pause/resume and holiday handling (BRS 9.2, 9.3). */
class SlaPauseMathTest {

    private SlaCalculationService calc;
    private SlaPolicy businessHours;

    @BeforeEach
    void setUp() {
        calc = new SlaCalculationService(mock(SlaPolicyRepository.class));
        SlaCalendar cal = new SlaCalendar();
        cal.setName("Mon-Fri 09-18");
        cal.setTimezone("UTC");
        Map<String, Object> day = Map.of("start", "09:00", "end", "18:00");
        Map<String, Object> hours = new java.util.HashMap<>();
        for (String d : List.of("monday", "tuesday", "wednesday", "thursday", "friday")) {
            hours.put(d, day);
        }
        cal.setWorkingHoursJson(hours);
        businessHours = new SlaPolicy();
        businessHours.setCalendar(cal);
    }

    // 2026-10-12 is a Monday.
    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void remainingTimeCountsOnlyWorkingMinutes() {
        // Fri 16:00 -> Mon 10:00 = 2h Friday + 1h Monday of working time.
        long minutes = calc.remainingMinutes(at("2026-10-09T16:00:00Z"), at("2026-10-12T10:00:00Z"), businessHours);
        assertThat(minutes).isEqualTo(180);
    }

    @Test
    void remainingTimeIsZeroOncePastDue() {
        assertThat(calc.remainingMinutes(at("2026-10-12T10:00:00Z"), at("2026-10-12T09:00:00Z"), businessHours)).isZero();
    }

    @Test
    void resumeAddsTheFrozenRemainderToTheNewMoment() {
        // 3h left, resumed Friday 16:00: 2h Friday, then 1h Monday morning from 09:00.
        Instant due = calc.addMinutes(at("2026-10-09T16:00:00Z"), 180, businessHours);
        assertThat(due).isEqualTo(at("2026-10-12T10:00:00Z"));
    }

    @Test
    void holidaysAreSkippedWhenAddingTime() {
        businessHours.getCalendar().setHolidaysJson(List.of("2026-10-12"));
        // Friday 17:00 + 2h: 1h Friday, Monday is a holiday, so the rest lands Tuesday 10:00.
        Instant due = calc.addMinutes(at("2026-10-09T17:00:00Z"), 120, businessHours);
        assertThat(due).isEqualTo(at("2026-10-13T10:00:00Z"));
    }

    @Test
    void holidaysAreExcludedFromRemainingTime() {
        businessHours.getCalendar().setHolidaysJson(List.of("2026-10-12"));
        long minutes = calc.remainingMinutes(at("2026-10-09T17:00:00Z"), at("2026-10-13T10:00:00Z"), businessHours);
        assertThat(minutes).isEqualTo(120);
    }

    @Test
    void roundTripPauseThenResumePreservesRemainingTime() {
        Instant due = at("2026-10-14T12:00:00Z");
        Instant pausedAt = at("2026-10-12T11:00:00Z");
        long remaining = calc.remainingMinutes(pausedAt, due, businessHours);
        // Resumed a whole week later: the same working time is left, so the new due is a week later.
        Instant newDue = calc.addMinutes(at("2026-10-19T11:00:00Z"), remaining, businessHours);
        assertThat(newDue).isEqualTo(at("2026-10-21T12:00:00Z"));
    }
}
