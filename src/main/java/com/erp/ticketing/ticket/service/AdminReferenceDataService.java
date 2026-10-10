package com.erp.ticketing.ticket.service;

import com.erp.ticketing.common.NotFoundException;
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

    public AdminReferenceDataService(TicketTypeRepository ticketTypeRepository, CategoryRepository categoryRepository) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public TicketTypeResponse createTicketType(TicketTypeCreateRequest request) {
        TicketType type = new TicketType();
        type.setName(request.name());
        type.setDescription(request.description());
        type = ticketTypeRepository.save(type);
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
        type = ticketTypeRepository.save(type);
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

    private TicketTypeResponse toResponse(TicketType type) {
        return new TicketTypeResponse(type.getId(), type.getName());
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getTicketType().getId());
    }
}
