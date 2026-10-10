package com.erp.ticketing.kb.dto;

import jakarta.validation.constraints.NotBlank;

public record KbArticleCreateRequest(@NotBlank String title, @NotBlank String content, String category, String tags) {
}
