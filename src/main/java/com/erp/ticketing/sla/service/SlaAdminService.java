package com.erp.ticketing.sla.service;

import com.erp.ticketing.common.NotFoundException;
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

import java.util.List;

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

    public SlaAdminService(SlaCalendarRepository slaCalendarRepository, SlaPolicyRepository slaPolicyRepository) {
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
            calendar.setTimezone(request.timezone());
        }
        calendar.setWorkingHoursJson(request.workingHoursJson());
        calendar = slaCalendarRepository.save(calendar);
        return toResponse(calendar);
    }

    @Transactional
    public SlaCalendarResponse updateCalendar(Long id, SlaCalendarUpdateRequest request) {
        SlaCalendar calendar = getRequiredCalendar(id);
        if (request.name() != null) {
            calendar.setName(request.name());
        }
        if (request.timezone() != null) {
            calendar.setTimezone(request.timezone());
        }
        if (request.workingHoursJson() != null) {
            calendar.setWorkingHoursJson(request.workingHoursJson());
        }
        calendar = slaCalendarRepository.save(calendar);
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
        policy = slaPolicyRepository.save(policy);
        return toResponse(policy);
    }

    @Transactional
    public SlaPolicyResponse updatePolicy(Long id, SlaPolicyUpdateRequest request) {
        SlaPolicy policy = slaPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SLA policy not found: " + id));
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
        policy = slaPolicyRepository.save(policy);
        return toResponse(policy);
    }

    private SlaCalendar getRequiredCalendar(Long id) {
        return slaCalendarRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SLA calendar not found: " + id));
    }

    private SlaCalendarResponse toResponse(SlaCalendar calendar) {
        return new SlaCalendarResponse(calendar.getId(), calendar.getName(), calendar.getTimezone(), calendar.getWorkingHoursJson());
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
                policy.isActive());
    }
}
