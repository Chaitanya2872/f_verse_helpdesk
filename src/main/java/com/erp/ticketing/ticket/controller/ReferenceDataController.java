package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.form.model.FormVersion;
import com.erp.ticketing.form.repository.FormVersionRepository;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.CategoryResponse;
import com.erp.ticketing.ticket.dto.TicketTypeResponse;
import com.erp.ticketing.ticket.model.Category;
import com.erp.ticketing.ticket.model.TicketType;
import com.erp.ticketing.ticket.repository.CategoryRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import com.erp.ticketing.ticket.service.AdminReferenceDataService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
public class ReferenceDataController {

    private final TicketTypeRepository ticketTypeRepository;
    private final CategoryRepository categoryRepository;
    private final FormVersionRepository formVersionRepository;

    public ReferenceDataController(TicketTypeRepository ticketTypeRepository, CategoryRepository categoryRepository,
                                   FormVersionRepository formVersionRepository) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.categoryRepository = categoryRepository;
        this.formVersionRepository = formVersionRepository;
    }

    /**
     * The service catalog. Admins see every lifecycle state; everyone else only PUBLISHED
     * services (BRS 5.2). {@code q} matches name, description and keywords for catalog search.
     */
    @GetMapping("/api/helpdesk/ticket-types")
    public List<TicketTypeResponse> ticketTypes(@RequestParam(required = false) String q,
                                                @AuthenticationPrincipal ErpUserPrincipal principal) {
        boolean admin = principal != null && principal.roles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r));
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return ticketTypeRepository.findAll().stream()
                .filter(t -> admin || t.getLifecycleStatus() == TicketType.Lifecycle.PUBLISHED)
                .filter(t -> needle.isEmpty() || contains(t.getName(), needle) || contains(t.getDescription(), needle)
                        || contains(t.getKeywords(), needle))
                .map(t -> AdminReferenceDataService.toResponse(t, formVersionRepository
                        .findByTicketTypeIdAndStatus(t.getId(), FormVersion.Status.PUBLISHED).isPresent()))
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

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
