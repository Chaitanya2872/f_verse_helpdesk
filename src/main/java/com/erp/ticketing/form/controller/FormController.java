package com.erp.ticketing.form.controller;

import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.form.dto.FormDto;
import com.erp.ticketing.form.model.FormVersion;
import com.erp.ticketing.form.service.FormService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk")
public class FormController {

    private final FormService formService;

    public FormController(FormService formService) {
        this.formService = formService;
    }

    /** The published form a requester fills in for a request type; 404 when the type has no form. */
    @GetMapping("/ticket-types/{typeId}/form")
    public FormDto.Version published(@PathVariable Long typeId) {
        FormVersion form = formService.published(typeId)
                .orElseThrow(() -> new NotFoundException("No published form for ticket type " + typeId));
        return new FormDto.Version(form.getId(), form.getTicketTypeId(), form.getVersion(), form.getStatus().name(),
                form.getSchema(), form.getCreatedAt(), form.getPublishedAt());
    }

    // ---- admin (ADMIN-only via the /admin/** matcher)

    @GetMapping("/admin/ticket-types/{typeId}/forms")
    public List<FormDto.Version> versions(@PathVariable Long typeId) {
        return formService.versions(typeId);
    }

    @PostMapping("/admin/ticket-types/{typeId}/forms")
    public FormDto.Version createDraft(@PathVariable Long typeId, @RequestBody FormDto.SaveRequest request) {
        return formService.createDraft(typeId, request.schema());
    }

    @PutMapping("/admin/forms/{id}")
    public FormDto.Version updateDraft(@PathVariable Long id, @RequestBody FormDto.SaveRequest request) {
        return formService.updateDraft(id, request.schema());
    }

    @PostMapping("/admin/forms/{id}/publish")
    public FormDto.Version publish(@PathVariable Long id) {
        return formService.publish(id);
    }

    @PostMapping("/admin/forms/{id}/retire")
    public FormDto.Version retire(@PathVariable Long id) {
        return formService.retire(id);
    }
}
