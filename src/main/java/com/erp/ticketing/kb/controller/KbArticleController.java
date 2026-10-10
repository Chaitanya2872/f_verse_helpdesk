package com.erp.ticketing.kb.controller;

import com.erp.ticketing.kb.dto.KbArticleCreateRequest;
import com.erp.ticketing.kb.dto.KbArticleResponse;
import com.erp.ticketing.kb.dto.KbArticleUpdateRequest;
import com.erp.ticketing.kb.dto.PageResponse;
import com.erp.ticketing.kb.model.KbArticleStatus;
import com.erp.ticketing.kb.service.KbArticleService;
import com.erp.ticketing.security.ErpUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge base self-service articles. List/get (published-only) are open to
 * any authenticated role via the general /api/helpdesk/** SecurityConfig matcher;
 * create/update/publish/unpublish are AGENT/MANAGER/ADMIN, delete is
 * ADMIN-only - see the dedicated /api/helpdesk/kb/articles* matchers in SecurityConfig.
 */
@RestController
@RequestMapping("/api/helpdesk/kb/articles")
public class KbArticleController {

    private final KbArticleService kbArticleService;

    public KbArticleController(KbArticleService kbArticleService) {
        this.kbArticleService = kbArticleService;
    }

    @GetMapping
    public PageResponse<KbArticleResponse> list(@RequestParam(required = false) String search,
                                                 @RequestParam(required = false) String category,
                                                 @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return kbArticleService.list(search, category, pageable);
    }

    @GetMapping("/{id}")
    public KbArticleResponse get(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return kbArticleService.get(id, principal);
    }

    @PostMapping
    public ResponseEntity<KbArticleResponse> create(@Valid @RequestBody KbArticleCreateRequest request,
                                                     @AuthenticationPrincipal ErpUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(kbArticleService.create(request, principal));
    }

    @PutMapping("/{id}")
    public KbArticleResponse update(@PathVariable Long id, @RequestBody KbArticleUpdateRequest request,
                                     @AuthenticationPrincipal ErpUserPrincipal principal) {
        return kbArticleService.update(id, request, principal);
    }

    @PostMapping("/{id}/publish")
    public KbArticleResponse publish(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return kbArticleService.setStatus(id, KbArticleStatus.PUBLISHED, principal);
    }

    @PostMapping("/{id}/unpublish")
    public KbArticleResponse unpublish(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return kbArticleService.setStatus(id, KbArticleStatus.DRAFT, principal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        kbArticleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
