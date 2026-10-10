package com.erp.ticketing.ticket.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int page, int size) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(), page.getTotalPages(),
                page.getNumber(), page.getSize());
    }
}
