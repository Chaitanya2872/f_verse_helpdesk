package com.erp.ticketing.form.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.FieldValidationException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.form.dto.FormDto;
import com.erp.ticketing.form.model.FormVersion;
import com.erp.ticketing.form.repository.FormVersionRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dynamic request forms (BRS 5.3, 5.4). The server validates every submission
 * against the form version on the ticket, regardless of what the browser did:
 * unknown fields are rejected, required fields are enforced only when visible,
 * and values of hidden conditional fields are dropped rather than stored.
 *
 * <p>Schema shape: {@code {"fields":[{"id","label","type","required","minLength","maxLength",
 * "pattern","min","max","options":[{"value","label"}],"minSelections","maxSelections",
 * "visibleWhen":{"field","equals"|"in"}}]}}. Types: text, longtext, select, multiselect,
 * number, date, checkbox, user.
 */
@Service
public class FormService {

    private static final Set<String> TYPES =
            Set.of("text", "longtext", "select", "multiselect", "number", "date", "checkbox", "user");
    private static final Pattern FIELD_ID = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");
    private static final int DEFAULT_TEXT_MAX = 500;
    private static final int DEFAULT_LONGTEXT_MAX = 20000;

    private final FormVersionRepository repository;
    private final TicketTypeRepository ticketTypeRepository;
    private final AuditService auditService;

    public FormService(FormVersionRepository repository, TicketTypeRepository ticketTypeRepository,
                       AuditService auditService) {
        this.repository = repository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public Optional<FormVersion> published(Long ticketTypeId) {
        return repository.findByTicketTypeIdAndStatus(ticketTypeId, FormVersion.Status.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public FormVersion getVersion(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Form version not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<FormDto.Version> versions(Long ticketTypeId) {
        return repository.findByTicketTypeIdOrderByVersionDesc(ticketTypeId).stream().map(this::toDto).toList();
    }

    // ------------------------------------------------------------- validation

    /**
     * Validates {@code submitted} against the form and returns the cleaned values to store.
     * Throws {@link FieldValidationException} listing every problem at once.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(FormVersion form, Map<String, Object> submitted) {
        Map<String, Object> values = submitted == null ? Map.of() : submitted;
        List<Map<String, Object>> fields = (List<Map<String, Object>>) form.getSchema().get("fields");
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> cleaned = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();

        for (Map<String, Object> field : fields) {
            String id = (String) field.get("id");
            known.add(id);
            if (!isVisible(field, values)) {
                continue;
            }
            Object value = values.get(id);
            String label = field.get("label") != null ? field.get("label").toString() : id;
            if (isEmpty(value)) {
                if (Boolean.TRUE.equals(field.get("required"))) {
                    errors.put(id, label + " is required");
                }
                continue;
            }
            String problem = check(field, value);
            if (problem != null) {
                errors.put(id, label + " " + problem);
            } else {
                cleaned.put(id, value);
            }
        }
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                errors.put(key, "Unknown field");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return cleaned;
    }

    @SuppressWarnings("unchecked")
    private boolean isVisible(Map<String, Object> field, Map<String, Object> values) {
        Object cond = field.get("visibleWhen");
        if (!(cond instanceof Map<?, ?> raw)) {
            return true;
        }
        Map<String, Object> c = (Map<String, Object>) raw;
        Object actual = values.get((String) c.get("field"));
        if (c.containsKey("equals")) {
            return actual != null && actual.toString().equals(String.valueOf(c.get("equals")));
        }
        if (c.get("in") instanceof List<?> allowed) {
            return actual != null && allowed.stream().anyMatch(a -> String.valueOf(a).equals(actual.toString()));
        }
        return true;
    }

    private boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isBlank()) || (v instanceof List<?> l && l.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private String check(Map<String, Object> field, Object value) {
        String type = (String) field.get("type");
        switch (type) {
            case "text", "longtext" -> {
                if (!(value instanceof String s)) {
                    return "must be text";
                }
                int max = intOf(field.get("maxLength"), "text".equals(type) ? DEFAULT_TEXT_MAX : DEFAULT_LONGTEXT_MAX);
                int min = intOf(field.get("minLength"), 0);
                if (s.length() > max) {
                    return "must be at most " + max + " characters";
                }
                if (s.length() < min) {
                    return "must be at least " + min + " characters";
                }
                Object pattern = field.get("pattern");
                if (pattern != null && !Pattern.compile(pattern.toString()).matcher(s).matches()) {
                    Object msg = field.get("patternMessage");
                    return msg != null ? msg.toString() : "has an invalid format";
                }
            }
            case "select" -> {
                if (!(value instanceof String s) || !optionValues(field).contains(s)) {
                    return "must be one of the listed options";
                }
            }
            case "multiselect" -> {
                if (!(value instanceof List<?> list)) {
                    return "must be a list of options";
                }
                Set<String> allowed = optionValues(field);
                for (Object o : list) {
                    if (!allowed.contains(String.valueOf(o))) {
                        return "contains an option that is not allowed";
                    }
                }
                int min = intOf(field.get("minSelections"), 0);
                int max = intOf(field.get("maxSelections"), Integer.MAX_VALUE);
                if (list.size() < min) {
                    return "needs at least " + min + " selections";
                }
                if (list.size() > max) {
                    return "allows at most " + max + " selections";
                }
            }
            case "number" -> {
                Double n = asNumber(value);
                if (n == null) {
                    return "must be a number";
                }
                if (field.get("min") != null && n < asNumber(field.get("min"))) {
                    return "must be at least " + field.get("min");
                }
                if (field.get("max") != null && n > asNumber(field.get("max"))) {
                    return "must be at most " + field.get("max");
                }
            }
            case "date" -> {
                LocalDate d;
                try {
                    d = LocalDate.parse(String.valueOf(value));
                } catch (DateTimeParseException ex) {
                    return "must be a date (yyyy-MM-dd)";
                }
                if (field.get("min") != null && d.isBefore(LocalDate.parse(field.get("min").toString()))) {
                    return "must not be before " + field.get("min");
                }
                if (field.get("max") != null && d.isAfter(LocalDate.parse(field.get("max").toString()))) {
                    return "must not be after " + field.get("max");
                }
            }
            case "checkbox" -> {
                if (!(value instanceof Boolean)) {
                    return "must be true or false";
                }
            }
            case "user" -> {
                Double n = asNumber(value);
                if (n == null || n != Math.floor(n)) {
                    return "must be a user id";
                }
            }
            default -> {
                return "has an unsupported type";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Set<String> optionValues(Map<String, Object> field) {
        Set<String> out = new HashSet<>();
        Object options = field.get("options");
        if (options instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("value") != null) {
                    out.add(m.get("value").toString());
                }
            }
        }
        return out;
    }

    private Double asNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? null : Double.valueOf(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    // ------------------------------------------------------------------ admin

    /** Structural check of a schema an admin is saving. */
    @SuppressWarnings("unchecked")
    public void checkSchema(Map<String, Object> schema) {
        if (schema == null || !(schema.get("fields") instanceof List<?> fields) || fields.isEmpty()) {
            throw new BadRequestException("A form needs a non-empty 'fields' list");
        }
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> raw)) {
                throw new BadRequestException("Every field must be an object");
            }
            Map<String, Object> f = (Map<String, Object>) raw;
            Object id = f.get("id");
            if (!(id instanceof String sid) || !FIELD_ID.matcher(sid).matches()) {
                problems.add("Field id must be lowercase letters, digits and underscores: " + id);
                continue;
            }
            if (!seen.add(sid)) {
                problems.add("Duplicate field id: " + sid);
            }
            Object type = f.get("type");
            if (!(type instanceof String t) || !TYPES.contains(t)) {
                problems.add(sid + ": unknown type " + type);
                continue;
            }
            if ((t.equals("select") || t.equals("multiselect")) && optionValues(f).isEmpty()) {
                problems.add(sid + ": a " + t + " needs options");
            }
            if (f.get("pattern") != null) {
                try {
                    Pattern.compile(f.get("pattern").toString());
                } catch (PatternSyntaxException ex) {
                    problems.add(sid + ": invalid pattern");
                }
            }
            if (f.get("visibleWhen") instanceof Map<?, ?> vw) {
                Object target = vw.get("field");
                if (target == null || !seen.contains(target.toString()) || target.equals(sid)) {
                    problems.add(sid + ": visibleWhen must refer to an earlier field");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems));
        }
    }

    @Transactional
    public FormDto.Version createDraft(Long ticketTypeId, Map<String, Object> schema) {
        if (!ticketTypeRepository.existsById(ticketTypeId)) {
            throw new NotFoundException("Ticket type not found: " + ticketTypeId);
        }
        checkSchema(schema);
        FormVersion form = new FormVersion();
        form.setTicketTypeId(ticketTypeId);
        form.setVersion(repository.findFirstByTicketTypeIdOrderByVersionDesc(ticketTypeId)
                .map(v -> v.getVersion() + 1).orElse(1));
        form.setSchema(schema);
        form = repository.save(form);
        auditService.recordForCurrentUser("FORM_DRAFT_CREATED", "FORM_VERSION", form.getId(), null,
                Map.of("ticketTypeId", ticketTypeId, "version", form.getVersion()));
        return toDto(form);
    }

    @Transactional
    public FormDto.Version updateDraft(Long id, Map<String, Object> schema) {
        FormVersion form = getVersion(id);
        if (form.getStatus() != FormVersion.Status.DRAFT) {
            throw new BadRequestException("Only a draft can be edited - create a new version instead");
        }
        checkSchema(schema);
        form.setSchema(schema);
        auditService.recordForCurrentUser("FORM_DRAFT_UPDATED", "FORM_VERSION", id, null,
                Map.of("ticketTypeId", form.getTicketTypeId(), "version", form.getVersion()));
        return toDto(repository.save(form));
    }

    @Transactional
    public FormDto.Version publish(Long id) {
        FormVersion form = getVersion(id);
        if (form.getStatus() != FormVersion.Status.DRAFT) {
            throw new BadRequestException("Only a draft can be published");
        }
        repository.findByTicketTypeIdAndStatus(form.getTicketTypeId(), FormVersion.Status.PUBLISHED).ifPresent(old -> {
            old.setStatus(FormVersion.Status.RETIRED);
            repository.saveAndFlush(old);
        });
        form.setStatus(FormVersion.Status.PUBLISHED);
        form.setPublishedAt(Instant.now());
        form = repository.save(form);
        auditService.recordForCurrentUser("FORM_PUBLISHED", "FORM_VERSION", id, null,
                Map.of("ticketTypeId", form.getTicketTypeId(), "version", form.getVersion()));
        return toDto(form);
    }

    @Transactional
    public FormDto.Version retire(Long id) {
        FormVersion form = getVersion(id);
        if (form.getStatus() != FormVersion.Status.PUBLISHED) {
            throw new BadRequestException("Only the published version can be retired");
        }
        form.setStatus(FormVersion.Status.RETIRED);
        auditService.recordForCurrentUser("FORM_RETIRED", "FORM_VERSION", id, null,
                Map.of("ticketTypeId", form.getTicketTypeId(), "version", form.getVersion()));
        return toDto(repository.save(form));
    }

    private FormDto.Version toDto(FormVersion f) {
        return new FormDto.Version(f.getId(), f.getTicketTypeId(), f.getVersion(), f.getStatus().name(),
                f.getSchema(), f.getCreatedAt(), f.getPublishedAt());
    }
}
