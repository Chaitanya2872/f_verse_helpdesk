package com.erp.ticketing.form.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.FieldValidationException;
import com.erp.ticketing.form.model.FormVersion;
import com.erp.ticketing.form.repository.FormVersionRepository;
import com.erp.ticketing.ticket.repository.TicketTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FormServiceTest {

    private FormService service;
    private FormVersion form;

    @BeforeEach
    void setUp() {
        service = new FormService(mock(FormVersionRepository.class), mock(TicketTypeRepository.class),
                mock(AuditService.class));
        form = new FormVersion();
        form.setSchema(Map.of("fields", List.of(
                Map.of("id", "app_name", "label", "Application", "type", "text", "required", true, "maxLength", 20),
                Map.of("id", "env", "label", "Environment", "type", "select", "required", true,
                        "options", List.of(Map.of("value", "prod", "label", "Production"),
                                Map.of("value", "uat", "label", "UAT"))),
                Map.of("id", "prod_impact", "label", "Production impact", "type", "text", "required", true,
                        "visibleWhen", Map.of("field", "env", "equals", "prod")),
                Map.of("id", "users", "label", "Affected users", "type", "number", "min", 1, "max", 10000))));
    }

    @Test
    void acceptsAValidSubmission() {
        var cleaned = service.validate(form, Map.of("app_name", "Billing", "env", "uat", "users", 5));
        assertThat(cleaned).containsEntry("app_name", "Billing").containsEntry("env", "uat");
    }

    @Test
    void reportsEveryProblemAtOnceWithFieldKeys() {
        assertThatThrownBy(() -> service.validate(form, Map.of("env", "dev", "users", 0)))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.getErrors()).containsKeys("app_name", "env", "users"));
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> service.validate(form, Map.of("app_name", "x", "env", "uat", "is_admin", true)))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.getErrors()).containsEntry("is_admin", "Unknown field"));
    }

    @Test
    void conditionalFieldIsRequiredOnlyWhenVisible() {
        assertThat(service.validate(form, Map.of("app_name", "x", "env", "uat"))).doesNotContainKey("prod_impact");
        assertThatThrownBy(() -> service.validate(form, Map.of("app_name", "x", "env", "prod")))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.getErrors()).containsKey("prod_impact"));
    }

    @Test
    void hiddenFieldValuesAreDroppedNotStored() {
        var cleaned = service.validate(form, Map.of("app_name", "x", "env", "uat", "prod_impact", "sneaky"));
        assertThat(cleaned).doesNotContainKey("prod_impact");
    }

    @Test
    void schemaCheckRejectsBrokenDefinitions() {
        assertThatThrownBy(() -> service.checkSchema(Map.of("fields", List.of(
                Map.of("id", "Bad Id", "type", "text")))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.checkSchema(Map.of("fields", List.of(
                Map.of("id", "pick", "type", "select")))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("options");
        assertThatThrownBy(() -> service.checkSchema(Map.of("fields", List.of(
                Map.of("id", "a", "type", "text", "visibleWhen", Map.of("field", "later", "equals", "x")),
                Map.of("id", "later", "type", "text")))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("earlier");
    }
}
