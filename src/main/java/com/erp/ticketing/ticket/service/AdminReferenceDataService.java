package com.erp.ticketing.ticket.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.form.model.FormVersion;
import com.erp.ticketing.form.repository.FormVersionRepository;
import com.erp.ticketing.ticket.dto.CategoryCreateRequest;
import com.erp.ticketing.ticket.dto.CategoryResponse;
import com.erp.ticketing.ticket.dto.CategoryUpdateRequest;
import com.erp.ticketing.ticket.dto.TicketTypeCreateRequest;
import com.erp.ticketing.ticket.dto.TicketTypeResponse;
import com.erp.ticketing.ticket.dto.TicketTypeUpdateRequest;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD for ticket types and categories. The existing read endpoints
 * (GET /api/helpdesk/ticket-types, GET /api/helpdesk/categories on ReferenceDataController)
 * are untouched; this service backs only the new /api/helpdesk/admin/** mutation
 * endpoints (AdminTicketTypeController / AdminCategoryController).
 */
@Service
public class AdminReferenceDataService {

    private final TicketTypeRepository ticketTypeRepository;
    private final CategoryRepository categoryRepository;
    private final FormVersionRepository formVersionRepository;
    private final AuditService auditService;

    public AdminReferenceDataService(TicketTypeRepository ticketTypeRepository, CategoryRepository categoryRepository,
                                     FormVersionRepository formVersionRepository, AuditService auditService) {
        this.formVersionRepository = formVersionRepository;
        this.auditService = auditService;
        this.ticketTypeRepository = ticketTypeRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public TicketTypeResponse createTicketType(TicketTypeCreateRequest request) {
        TicketType type = new TicketType();
        type.setName(request.name());
        type.setDescription(request.description());
        applyCatalog(type, request.instructions(), request.departmentId(), request.serviceOwnerId(),
                request.lifecycleStatus(), request.audience(), request.keywords(), request.icon(),
                request.defaultConfidentiality());
        type = ticketTypeRepository.save(type);
        auditService.recordForCurrentUser("SERVICE_CREATED", "TICKET_TYPE", type.getId(), null, snapshot(type));
        return toResponse(type);
    }

    @Transactional
    public TicketTypeResponse updateTicketType(Long id, TicketTypeUpdateRequest request) {
        TicketType type = ticketTypeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket type not found: " + id));
        if (request.name() != null) {
            type.setName(request.name());
        }
        if (request.description() != null) {
            type.setDescription(request.description());
        }
        java.util.Map<String, Object> before = snapshot(type);
        applyCatalog(type, request.instructions(), request.departmentId(), request.serviceOwnerId(),
                request.lifecycleStatus(), request.audience(), request.keywords(), request.icon(),
                request.defaultConfidentiality());
        type = ticketTypeRepository.save(type);
        auditService.recordForCurrentUser("SERVICE_UPDATED", "TICKET_TYPE", id, before, snapshot(type));
        return toResponse(type);
    }

    @Transactional
    public CategoryResponse createCategory(CategoryCreateRequest request) {
        TicketType ticketType = ticketTypeRepository.findById(request.ticketTypeId())
                .orElseThrow(() -> new NotFoundException("Ticket type not found: " + request.ticketTypeId()));
        Category category = new Category();
        category.setTicketType(ticketType);
        category.setName(request.name());
        if (request.parentCategoryId() != null) {
            category.setParentCategory(getRequiredCategory(request.parentCategoryId()));
        }
        category = categoryRepository.save(category);
        return toResponse(category);
    }

    @Transactional
    public CategoryResponse updateCategory(Long id, CategoryUpdateRequest request) {
        Category category = getRequiredCategory(id);
        if (request.name() != null) {
            category.setName(request.name());
        }
        if (request.parentCategoryId() != null) {
            category.setParentCategory(getRequiredCategory(request.parentCategoryId()));
        }
        category = categoryRepository.save(category);
        return toResponse(category);
    }

    private Category getRequiredCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found: " + id));
    }

    private void applyCatalog(TicketType type, String instructions, Long departmentId, Long serviceOwnerId,
                              String lifecycle, String audience, String keywords, String icon,
                              String defaultConfidentiality) {
        if (instructions != null) {
            type.setInstructions(instructions);
        }
        if (departmentId != null) {
            type.setDepartmentId(departmentId);
        }
        if (serviceOwnerId != null) {
            type.setServiceOwnerId(serviceOwnerId);
        }
        if (lifecycle != null) {
            try {
                type.setLifecycleStatus(TicketType.Lifecycle.valueOf(lifecycle.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("lifecycleStatus must be DRAFT, PUBLISHED, RETIRED or ARCHIVED");
            }
        }
        if (audience != null) {
            try {
                type.setAudience(TicketType.Audience.valueOf(audience.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("audience must be ALL, EMPLOYEE or CUSTOMER");
            }
        }
        if (keywords != null) {
            type.setKeywords(keywords);
        }
        if (icon != null) {
            type.setIcon(icon);
        }
        if (defaultConfidentiality != null) {
            try {
                type.setDefaultConfidentiality(com.erp.ticketing.ticket.model.Confidentiality
                        .valueOf(defaultConfidentiality.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("defaultConfidentiality must be STANDARD, RESTRICTED or CONFIDENTIAL");
            }
        }
    }

    private java.util.Map<String, Object> snapshot(TicketType t) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("departmentId", t.getDepartmentId());
        m.put("serviceOwnerId", t.getServiceOwnerId());
        m.put("lifecycleStatus", t.getLifecycleStatus().name());
        m.put("audience", t.getAudience().name());
        m.put("keywords", t.getKeywords());
        m.put("defaultConfidentiality", t.getDefaultConfidentiality().name());
        return m;
    }

    private TicketTypeResponse toResponse(TicketType type) {
        return toResponse(type, formVersionRepository
                .findByTicketTypeIdAndStatus(type.getId(), FormVersion.Status.PUBLISHED).isPresent());
    }

    public static TicketTypeResponse toResponse(TicketType type, boolean hasForm) {
        return new TicketTypeResponse(type.getId(), type.getName(), type.getDescription(), type.getInstructions(),
                type.getDepartmentId(), type.getServiceOwnerId(), type.getLifecycleStatus().name(),
                type.getAudience().name(), type.getKeywords(), type.getIcon(),
                type.getDefaultConfidentiality().name(), hasForm);
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getTicketType().getId());
    }
}
