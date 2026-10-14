package com.erp.ticketing.sla.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.sla.dto.SlaCalendarCreateRequest;
import com.erp.ticketing.sla.dto.SlaCalendarResponse;
import com.erp.ticketing.sla.dto.SlaCalendarUpdateRequest;
import com.erp.ticketing.sla.dto.SlaPolicyCreateRequest;
import com.erp.ticketing.sla.dto.SlaPolicyResponse;
import com.erp.ticketing.sla.dto.SlaPolicyUpdateRequest;
import com.erp.ticketing.sla.model.SlaCalendar;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.repository.SlaCalendarRepository;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for SLA calendars/policies, layered over the same
 * SlaCalendarRepository/SlaPolicyRepository that SlaCalculationService
 * already reads from. conditionsJson / workingHoursJson shapes accepted here
 * must match what SlaCalculationService parses - see its javadoc and
 * resolvePolicy()/addWorkingMinutes() for the exact contract.
 */
@Service
public class SlaAdminService {

    private final SlaCalendarRepository slaCalendarRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final AuditService auditService;

    public SlaAdminService(SlaCalendarRepository slaCalendarRepository, SlaPolicyRepository slaPolicyRepository,
                           AuditService auditService) {
        this.auditService = auditService;
        this.slaCalendarRepository = slaCalendarRepository;
        this.slaPolicyRepository = slaPolicyRepository;
    }

    @Transactional(readOnly = true)
    public List<SlaCalendarResponse> listCalendars() {
        return slaCalendarRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public SlaCalendarResponse createCalendar(SlaCalendarCreateRequest request) {
        SlaCalendar calendar = new SlaCalendar();
        calendar.setName(request.name());
        if (request.timezone() != null) {
            calendar.setTimezone(validTimezone(request.timezone()));
        }
        calendar.setWorkingHoursJson(request.workingHoursJson());
        if (request.holidays() != null) {
            calendar.setHolidaysJson(validHolidays(request.holidays()));
        }
        calendar = slaCalendarRepository.save(calendar);
        auditService.recordForCurrentUser("SLA_CALENDAR_CREATED", "SLA_CALENDAR", calendar.getId(), null, snapshot(calendar));
        return toResponse(calendar);
    }

    @Transactional
    public SlaCalendarResponse updateCalendar(Long id, SlaCalendarUpdateRequest request) {
        SlaCalendar calendar = getRequiredCalendar(id);
        Map<String, Object> before = snapshot(calendar);
        if (request.name() != null) {
            calendar.setName(request.name());
        }
        if (request.timezone() != null) {
            calendar.setTimezone(validTimezone(request.timezone()));
        }
        if (request.workingHoursJson() != null) {
            calendar.setWorkingHoursJson(request.workingHoursJson());
        }
        if (request.holidays() != null) {
            calendar.setHolidaysJson(validHolidays(request.holidays()));
        }
        calendar = slaCalendarRepository.save(calendar);
        auditService.recordForCurrentUser("SLA_CALENDAR_UPDATED", "SLA_CALENDAR", id, before, snapshot(calendar));
        return toResponse(calendar);
    }

    @Transactional(readOnly = true)
    public List<SlaPolicyResponse> listPolicies() {
        return slaPolicyRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public SlaPolicyResponse createPolicy(SlaPolicyCreateRequest request) {
        SlaPolicy policy = new SlaPolicy();
        policy.setName(request.name());
        policy.setDescription(request.description());
        policy.setConditionsJson(request.conditionsJson());
        policy.setFirstResponseMinutes(request.firstResponseMinutes());
        policy.setResolutionMinutes(request.resolutionMinutes());
        if (request.calendarId() != null) {
            policy.setCalendar(getRequiredCalendar(request.calendarId()));
        }
        policy.setActive(request.isActive() == null || request.isActive());
        applyTuning(policy, request.pauseStatuses(), request.warningPercent(), request.escalateAfterBreachMinutes());
        policy = slaPolicyRepository.save(policy);
        auditService.recordForCurrentUser("SLA_POLICY_CREATED", "SLA_POLICY", policy.getId(), null, snapshot(policy));
        return toResponse(policy);
    }

    @Transactional
    public SlaPolicyResponse updatePolicy(Long id, SlaPolicyUpdateRequest request) {
        SlaPolicy policy = slaPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SLA policy not found: " + id));
        Map<String, Object> before = snapshot(policy);
        if (request.name() != null) {
            policy.setName(request.name());
        }
        if (request.description() != null) {
            policy.setDescription(request.description());
        }
        if (request.conditionsJson() != null) {
            policy.setConditionsJson(request.conditionsJson());
        }
        if (request.firstResponseMinutes() != null) {
            policy.setFirstResponseMinutes(request.firstResponseMinutes());
        }
        if (request.resolutionMinutes() != null) {
            policy.setResolutionMinutes(request.resolutionMinutes());
        }
        if (request.calendarId() != null) {
            policy.setCalendar(getRequiredCalendar(request.calendarId()));
        }
        if (request.isActive() != null) {
            policy.setActive(request.isActive());
        }
        applyTuning(policy, request.pauseStatuses(), request.warningPercent(), request.escalateAfterBreachMinutes());
        policy.setVersion(policy.getVersion() + 1);
        policy = slaPolicyRepository.save(policy);
        auditService.recordForCurrentUser("SLA_POLICY_UPDATED", "SLA_POLICY", id, before, snapshot(policy));
        return toResponse(policy);
    }

    private void applyTuning(SlaPolicy policy, List<String> pauseStatuses, Integer warningPercent, Integer escalateAfter) {
        if (pauseStatuses != null) {
            for (String status : pauseStatuses) {
                try {
                    TicketStatus.valueOf(status.trim().toUpperCase());
                } catch (IllegalArgumentException | NullPointerException ex) {
                    throw new BadRequestException("Unknown pause status: " + status);
                }
            }
            policy.setPauseStatuses(String.join(",", pauseStatuses.stream().map(s -> s.trim().toUpperCase()).toList()));
        }
        if (warningPercent != null) {
            if (warningPercent < 1 || warningPercent > 99) {
                throw new BadRequestException("warningPercent must be between 1 and 99");
            }
            policy.setWarningPercent(warningPercent);
        }
        if (escalateAfter != null) {
            if (escalateAfter < 0) {
                throw new BadRequestException("escalateAfterBreachMinutes cannot be negative");
            }
            policy.setEscalateAfterBreachMinutes(escalateAfter);
        }
    }

    private String validTimezone(String tz) {
        try {
            ZoneId.of(tz);
            return tz;
        } catch (java.time.DateTimeException ex) {
            throw new BadRequestException("Unknown timezone: " + tz);
        }
    }

    private List<String> validHolidays(List<String> holidays) {
        for (String h : holidays) {
            try {
                LocalDate.parse(h);
            } catch (DateTimeParseException | NullPointerException ex) {
                throw new BadRequestException("Holiday must be an ISO date (yyyy-MM-dd): " + h);
            }
        }
        return holidays;
    }

    private Map<String, Object> snapshot(SlaCalendar c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("timezone", c.getTimezone());
        m.put("workingHours", c.getWorkingHoursJson());
        m.put("holidays", c.getHolidaysJson());
        return m;
    }

    private Map<String, Object> snapshot(SlaPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("conditions", p.getConditionsJson());
        m.put("firstResponseMinutes", p.getFirstResponseMinutes());
        m.put("resolutionMinutes", p.getResolutionMinutes());
        m.put("calendarId", p.getCalendar() != null ? p.getCalendar().getId() : null);
        m.put("active", p.isActive());
        m.put("version", p.getVersion());
        m.put("pauseStatuses", p.getPauseStatuses());
        m.put("warningPercent", p.getWarningPercent());
        m.put("escalateAfterBreachMinutes", p.getEscalateAfterBreachMinutes());
        return m;
    }

    private SlaCalendar getRequiredCalendar(Long id) {
        return slaCalendarRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SLA calendar not found: " + id));
    }

    private SlaCalendarResponse toResponse(SlaCalendar calendar) {
        return new SlaCalendarResponse(calendar.getId(), calendar.getName(), calendar.getTimezone(),
                calendar.getWorkingHoursJson(), calendar.getHolidaysJson());
    }

    private SlaPolicyResponse toResponse(SlaPolicy policy) {
        return new SlaPolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getDescription(),
                policy.getConditionsJson(),
                policy.getFirstResponseMinutes(),
                policy.getResolutionMinutes(),
                policy.getCalendar() != null ? policy.getCalendar().getId() : null,
                policy.isActive(),
                policy.getVersion(),
                java.util.Arrays.stream(policy.getPauseStatuses().split(",")).filter(s -> !s.isBlank()).toList(),
                policy.getWarningPercent(),
                policy.getEscalateAfterBreachMinutes());
    }
}
