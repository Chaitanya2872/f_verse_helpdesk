package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.ticket.dto.CategoryResponse;
import com.erp.ticketing.ticket.dto.TicketTypeResponse;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ReferenceDataController {

    private final TicketTypeRepository ticketTypeRepository;
    private final CategoryRepository categoryRepository;

    public ReferenceDataController(TicketTypeRepository ticketTypeRepository, CategoryRepository categoryRepository) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.categoryRepository = categoryRepository;
    }

    @GetMapping("/api/helpdesk/ticket-types")
    public List<TicketTypeResponse> ticketTypes() {
        return ticketTypeRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/api/helpdesk/categories")
    public List<CategoryResponse> categories(@RequestParam(required = false) Long typeId) {
        List<Category> categories = typeId != null
                ? categoryRepository.findByTicketType_Id(typeId)
                : categoryRepository.findAll();
        return categories.stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName(), c.getTicketType().getId()))
                .toList();
    }

    private TicketTypeResponse toResponse(TicketType type) {
        return new TicketTypeResponse(type.getId(), type.getName());
    }
}
