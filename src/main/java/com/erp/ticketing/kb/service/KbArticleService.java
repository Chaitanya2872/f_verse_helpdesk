package com.erp.ticketing.kb.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.kb.dto.KbArticleCreateRequest;
import com.erp.ticketing.kb.dto.KbArticleResponse;
import com.erp.ticketing.kb.dto.KbArticleUpdateRequest;
import com.erp.ticketing.kb.dto.PageResponse;
import com.erp.ticketing.kb.model.KbArticle;
import com.erp.ticketing.kb.model.KbArticleStatus;
import com.erp.ticketing.kb.repository.KbArticleRepository;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class KbArticleService {

    private final KbArticleRepository kbArticleRepository;
    private final UserService userService;

    public KbArticleService(KbArticleRepository kbArticleRepository, UserService userService) {
        this.kbArticleRepository = kbArticleRepository;
        this.userService = userService;
    }

    @Transactional
    public KbArticleResponse create(KbArticleCreateRequest request, ErpUserPrincipal principal) {
        User author = userService.getOrCreateCurrentUser(principal);
        KbArticle article = new KbArticle();
        article.setTitle(request.title());
        article.setContent(request.content());
        article.setCategory(request.category());
        article.setTags(request.tags());
        article.setStatus(KbArticleStatus.DRAFT);
        article.setAuthor(author);
        article = kbArticleRepository.save(article);
        return toResponse(article);
    }

    @Transactional(readOnly = true)
    public PageResponse<KbArticleResponse> list(String search, String category, Pageable pageable) {
        Specification<KbArticle> spec = Specification.where(
                (root, query, cb) -> cb.equal(root.get("status"), KbArticleStatus.PUBLISHED));

        if (category != null && !category.isBlank()) {
            String normalized = category.trim().toLowerCase();
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("category")), normalized));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(buildSearchSpec(search));
        }

        Page<KbArticle> page = kbArticleRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    /**
     * Free-text substring match (case-insensitive), OR'd across title, content
     * and tags - mirrors TicketService.buildSearchSpec. tags is nullable, so
     * it is wrapped in cb.coalesce(...) to avoid a null LIKE comparand.
     */
    private Specification<KbArticle> buildSearchSpec(String search) {
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("content")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("tags"), "")), pattern));
    }

    @Transactional
    public KbArticleResponse get(Long id, ErpUserPrincipal principal) {
        KbArticle article = getRequired(id);
        if (article.getStatus() == KbArticleStatus.DRAFT) {
            User currentUser = userService.getOrCreateCurrentUser(principal);
            boolean isAuthor = article.getAuthor().getId().equals(currentUser.getId());
            if (!isAuthor && !hasAnyRole(principal, "ADMIN", "MANAGER")) {
                // 404 rather than 403 to avoid leaking existence of drafts the caller can't see (mirrors TicketService.assertVisible).
                throw new NotFoundException("Article not found: " + id);
            }
        } else {
            kbArticleRepository.incrementViewCount(id);
            article.setViewCount(article.getViewCount() + 1); // reflect the just-applied increment in this response
        }
        return toResponse(article);
    }

    @Transactional
    public KbArticleResponse update(Long id, KbArticleUpdateRequest request, ErpUserPrincipal principal) {
        KbArticle article = getRequired(id);
        assertCanEdit(article, principal);

        if (request.title() != null) {
            article.setTitle(request.title());
        }
        if (request.content() != null) {
            article.setContent(request.content());
        }
        if (request.category() != null) {
            article.setCategory(request.category());
        }
        if (request.tags() != null) {
            article.setTags(request.tags());
        }

        article = kbArticleRepository.save(article);
        return toResponse(article);
    }

    @Transactional
    public KbArticleResponse setStatus(Long id, KbArticleStatus newStatus, ErpUserPrincipal principal) {
        KbArticle article = getRequired(id);
        assertCanEdit(article, principal);
        article.setStatus(newStatus);
        article = kbArticleRepository.save(article);
        return toResponse(article);
    }

    @Transactional
    public void delete(Long id) {
        kbArticleRepository.delete(getRequired(id));
    }

    private void assertCanEdit(KbArticle article, ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        boolean isAuthor = article.getAuthor().getId().equals(currentUser.getId());
        if (!isAuthor && !hasAnyRole(principal, "ADMIN", "MANAGER")) {
            throw new BadRequestException("Only the article's author, a manager, or an admin can edit it");
        }
    }

    private boolean hasAnyRole(ErpUserPrincipal principal, String... roles) {
        Set<String> have = principal.roles();
        for (String r : roles) {
            if (have.stream().anyMatch(r::equalsIgnoreCase)) {
                return true;
            }
        }
        return false;
    }

    private KbArticle getRequired(Long id) {
        return kbArticleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Article not found: " + id));
    }

    private KbArticleResponse toResponse(KbArticle a) {
        return new KbArticleResponse(a.getId(), a.getTitle(), a.getContent(), a.getCategory(), a.getTags(),
                a.getStatus().name(), a.getAuthor().getId(), a.getAuthor().getName(), a.getViewCount(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
