package com.erp.ticketing.view;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Named ticket-list filters (BRS 4.2). A view only stores filter values for the ticket list, so
 * loading one can never show a ticket the caller could not already see. Managers and admins may share
 * a view with everyone; everyone can keep private ones.
 */
@Service
public class SavedViewService {

    private static final Set<String> FILTER_KEYS = Set.of("status", "priority", "typeId", "categoryId", "assigneeId",
            "requesterId", "departmentId", "openOnly", "unassigned", "slaStatus", "search", "createdFrom", "createdTo",
            "externalRef", "confidentiality", "resolutionCode", "reopened", "source", "sort");
    private static final int MAX_PER_USER = 50;

    public record View(Long id, String name, Map<String, Object> filters, boolean shared, boolean mine,
                       String ownerName, Instant createdAt) {
    }

    public record Save(String name, Map<String, Object> filters, Boolean shared) {
    }

    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final ObjectMapper mapper;

    public SavedViewService(JdbcTemplate jdbc, UserService userService, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.userService = userService;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<View> list(ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        return jdbc.query("SELECT v.id, v.name, v.filters_json::text, v.shared, v.user_id, u.name, v.created_at "
                        + "FROM saved_views v JOIN users u ON u.id = v.user_id WHERE v.user_id = ? OR v.shared "
                        + "ORDER BY (v.user_id = ?) DESC, v.name",
                (rs, i) -> new View(rs.getLong(1), rs.getString(2), parse(rs.getString(3)), rs.getBoolean(4),
                        rs.getLong(5) == me.getId(), rs.getString(6), rs.getTimestamp(7).toInstant()),
                me.getId(), me.getId());
    }

    @Transactional
    public View save(Save request, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new BadRequestException("A view needs a name of up to 100 characters");
        }
        Map<String, Object> filters = clean(request.filters());
        boolean shared = Boolean.TRUE.equals(request.shared());
        if (shared && principal.roles().stream().noneMatch(r -> "MANAGER".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r))) {
            throw new BadRequestException("Only managers and admins can share a view");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM saved_views WHERE user_id = ?", Integer.class, me.getId());
        Integer same = jdbc.queryForObject("SELECT count(*) FROM saved_views WHERE user_id = ? AND name = ?", Integer.class, me.getId(), name);
        if ((same == null || same == 0) && count != null && count >= MAX_PER_USER) {
            throw new BadRequestException("You can keep at most " + MAX_PER_USER + " saved views");
        }
        Long id = jdbc.queryForObject("INSERT INTO saved_views (user_id, name, filters_json, shared) VALUES (?, ?, ?::jsonb, ?) "
                        + "ON CONFLICT (user_id, name) DO UPDATE SET filters_json = EXCLUDED.filters_json, shared = EXCLUDED.shared "
                        + "RETURNING id", Long.class, me.getId(), name, write(filters), shared);
        return new View(id, name, filters, shared, true, me.getName(), Instant.now());
    }

    @Transactional
    public void delete(Long id, ErpUserPrincipal principal) {
        User me = userService.getOrCreateCurrentUser(principal);
        int n = jdbc.update("DELETE FROM saved_views WHERE id = ? AND user_id = ?", id, me.getId());
        if (n == 0) {
            throw new NotFoundException("View not found: " + id);
        }
    }

    /** Keeps only known filter keys with simple values, so a view cannot carry anything but list filters. */
    private Map<String, Object> clean(Map<String, Object> filters) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (filters == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            if (!FILTER_KEYS.contains(e.getKey())) {
                throw new BadRequestException("Unknown filter: " + e.getKey());
            }
            Object v = e.getValue();
            if (v == null || (v instanceof String s && s.isBlank())) {
                continue;
            }
            if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                throw new BadRequestException("Filter " + e.getKey() + " must be a single value");
            }
            if (v instanceof String s && s.length() > 200) {
                throw new BadRequestException("Filter " + e.getKey() + " is too long");
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    private String write(Map<String, Object> filters) {
        try {
            return mapper.writeValueAsString(filters);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }
}
