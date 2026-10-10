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

    private Instant addWorkingMinutes(Instant from, int minutes, SlaCalendar calendar) {
        if (calendar == null || calendar.getWorkingHoursJson() == null || calendar.getWorkingHoursJson().isEmpty()) {
            return from.plus(Duration.ofMinutes(minutes));
        }

        Map<String, Object> workingHours = calendar.getWorkingHoursJson();
        ZoneId zone = ZoneId.of(calendar.getTimezone() != null ? calendar.getTimezone() : "UTC");
        ZonedDateTime cursor = from.atZone(zone);
        long remaining = minutes;
        int daysScanned = 0;

        while (remaining > 0) {
            if (daysScanned > MAX_DAYS_SCANNED) {
                throw new IllegalStateException(
                        "SLA calendar '" + calendar.getName() + "' has no working hours configured on any day");
            }

            DayWindow window = windowFor(cursor.getDayOfWeek(), workingHours);
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
