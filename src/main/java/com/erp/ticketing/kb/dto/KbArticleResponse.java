package com.erp.ticketing.kb.dto;

import java.time.Instant;

public record KbArticleResponse(
        Long id, String title, String content, String category, String tags, String status,
        Long authorId, String authorName, int viewCount, Instant createdAt, Instant updatedAt) {
}
