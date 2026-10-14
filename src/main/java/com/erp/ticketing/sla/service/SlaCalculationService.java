package com.erp.ticketing.sla.service;

import com.erp.ticketing.sla.dto.SlaDueDates;
import com.erp.ticketing.sla.model.SlaCalendar;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import com.erp.ticketing.ticket.model.TicketPriority;
import com.erp.ticketing.ticket.model.TicketType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves which {@link SlaPolicy} applies to a ticket and computes its due
 * dates by walking forward through the policy's calendar's working hours.
 */
@Service
public class SlaCalculationService {

    // Safety cap on how many calendar days computeDueDates will scan forward
    // looking for working time before giving up - guards against an infinite
    // loop if a calendar is misconfigured with every day off.
    private static final int MAX_DAYS_SCANNED = 3650;

    private final SlaPolicyRepository slaPolicyRepository;

    public SlaCalculationService(SlaPolicyRepository slaPolicyRepository) {
        this.slaPolicyRepository = slaPolicyRepository;
    }

    /**
     * Loads active SLA policies ordered by id ascending and returns the first
     * whose conditionsJson either has no "priority"/"ticketTypeId" keys
     * (wildcard) or whose present keys match the ticket's actual type id /
     * priority string.
     */
    @Transactional(readOnly = true)
    public Optional<SlaPolicy> resolvePolicy(TicketType type, TicketPriority priority) {
        List<SlaPolicy> policies = slaPolicyRepository.findByActiveTrueOrderByIdAsc();
        for (SlaPolicy policy : policies) {
            if (matches(policy.getConditionsJson(), type, priority)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    private boolean matches(Map<String, Object> conditions, TicketType type, TicketPriority priority) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        Object priorityCondition = conditions.get("priority");
        if (priorityCondition != null) {
            if (priority == null || !priorityCondition.toString().equalsIgnoreCase(priority.name())) {
                return false;
            }
        }
        Object typeCondition = conditions.get("ticketTypeId");
        if (typeCondition != null) {
            if (type == null || type.getId() == null || !(typeCondition instanceof Number number)
                    || number.longValue() != type.getId()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds first_response_minutes/resolution_minutes working-minutes to
     * {@code from}, walking forward through the policy's calendar's working
     * hours (skipping non-working days/hours entirely). If the policy has no
     * calendar, or the calendar's workingHoursJson is null, the calendar is
     * treated as always-open and the minutes are added directly.
     */
    public SlaDueDates computeDueDates(Instant from, SlaPolicy policy) {
        SlaCalendar calendar = policy.getCalendar();
        Instant firstResponseDue = addWorkingMinutes(from, policy.getFirstResponseMinutes(), calendar);
        Instant resolutionDue = addWorkingMinutes(from, policy.getResolutionMinutes(), calendar);
        return new SlaDueDates(firstResponseDue, resolutionDue);
    }

    /** Working minutes left between {@code now} and {@code due} on the policy's calendar; never negative. */
    public long remainingMinutes(Instant now, Instant due, SlaPolicy policy) {
        if (due == null || !due.isAfter(now)) {
            return 0;
        }
        SlaCalendar calendar = policy == null ? null : policy.getCalendar();
        if (calendar == null || calendar.getWorkingHoursJson() == null || calendar.getWorkingHoursJson().isEmpty()) {
            return Duration.between(now, due).toMinutes();
        }
        ZoneId zone = ZoneId.of(calendar.getTimezone() != null ? calendar.getTimezone() : "UTC");
        Set<LocalDate> holidays = holidays(calendar);
        ZonedDateTime start = now.atZone(zone);
        ZonedDateTime end = due.atZone(zone);
        long total = 0;
        for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            DayWindow window = holidays.contains(day) ? null : windowFor(day.getDayOfWeek(), calendar.getWorkingHoursJson());
            if (window == null) {
                continue;
            }
            ZonedDateTime windowStart = day.atTime(window.start()).atZone(zone);
            ZonedDateTime windowEnd = day.atTime(window.end()).atZone(zone);
            ZonedDateTime from = start.isAfter(windowStart) ? start : windowStart;
            ZonedDateTime to = end.isBefore(windowEnd) ? end : windowEnd;
            if (to.isAfter(from)) {
                total += Duration.between(from, to).toMinutes();
            }
        }
        return total;
    }

    /** Adds working minutes on the policy's calendar to {@code from} (resume after a pause). */
    public Instant addMinutes(Instant from, long minutes, SlaPolicy policy) {
        return addWorkingMinutes(from, (int) Math.min(minutes, Integer.MAX_VALUE), policy == null ? null : policy.getCalendar());
    }

    private Set<LocalDate> holidays(SlaCalendar calendar) {
        Set<LocalDate> result = new HashSet<>();
        if (calendar.getHolidaysJson() != null) {
            for (String h : calendar.getHolidaysJson()) {
                try {
                    result.add(LocalDate.parse(h));
                } catch (DateTimeParseException ignored) {
                    // Admin input is validated on save; a corrupt row must not break SLA maths.
                }
            }
        }
        return result;
    }

    private Instant addWorkingMinutes(Instant from, int minutes, SlaCalendar calendar) {
        if (calendar == null || calendar.getWorkingHoursJson() == null || calendar.getWorkingHoursJson().isEmpty()) {
            return from.plus(Duration.ofMinutes(minutes));
        }

        Map<String, Object> workingHours = calendar.getWorkingHoursJson();
        ZoneId zone = ZoneId.of(calendar.getTimezone() != null ? calendar.getTimezone() : "UTC");
        Set<LocalDate> holidays = holidays(calendar);
        ZonedDateTime cursor = from.atZone(zone);
        long remaining = minutes;
        int daysScanned = 0;

        while (remaining > 0) {
            if (daysScanned > MAX_DAYS_SCANNED) {
                throw new IllegalStateException(
                        "SLA calendar '" + calendar.getName() + "' has no working hours configured on any day");
            }

            DayWindow window = holidays.contains(cursor.toLocalDate()) ? null : windowFor(cursor.getDayOfWeek(), workingHours);
            if (window == null) {
                cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
                daysScanned++;
                continue;
            }

            ZonedDateTime dayStart = cursor.with(window.start());
            ZonedDateTime dayEnd = cursor.with(window.end());
            if (cursor.isBefore(dayStart)) {
                cursor = dayStart;
            }
            if (!cursor.isBefore(dayEnd)) {
                cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
                daysScanned++;
                continue;
            }

            long availableMinutesToday = Duration.between(cursor, dayEnd).toMinutes();
            if (availableMinutesToday >= remaining) {
                cursor = cursor.plusMinutes(remaining);
                remaining = 0;
            } else {
                remaining -= availableMinutesToday;
                cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
                daysScanned++;
            }
        }

        return cursor.toInstant();
    }

    private DayWindow windowFor(DayOfWeek day, Map<String, Object> workingHoursJson) {
        Object raw = workingHoursJson.get(day.name().toLowerCase(Locale.ROOT));
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object startRaw = map.get("start");
        Object endRaw = map.get("end");
        if (startRaw == null || endRaw == null) {
            return null;
        }
        LocalTime start = LocalTime.parse(startRaw.toString());
        LocalTime end = LocalTime.parse(endRaw.toString());
        if (!start.isBefore(end)) {
            return null;
        }
        return new DayWindow(start, end);
    }

    private record DayWindow(LocalTime start, LocalTime end) {
    }
}
