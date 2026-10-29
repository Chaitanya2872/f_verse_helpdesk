package com.erp.ticketing.workflow.service;

import com.erp.ticketing.audit.service.AuditService;
import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.ticket.model.TicketStatus;
import com.erp.ticketing.workflow.dto.WorkflowDto;
import com.erp.ticketing.workflow.repository.WorkflowTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkflowServiceTest {

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(mock(WorkflowTransitionRepository.class), mock(AuditService.class));
    }

    private static WorkflowDto.Transition t(String from, String to) {
        return new WorkflowDto.Transition(from, to);
    }

    @Test
    void baselineAppliesWhenNothingIsConfigured() {
        assertThat(service.isAllowed(1L, TicketStatus.NEW, TicketStatus.TRIAGE)).isTrue();
        assertThat(service.isAllowed(1L, TicketStatus.NEW, TicketStatus.CLOSED)).isFalse();
        assertThat(service.isAllowed(1L, TicketStatus.CLOSED, TicketStatus.REOPENED)).isFalse();
    }

    @Test
    void rejectsAnUnreachableState() {
        assertThatThrownBy(() -> service.save(1L, List.of(
                t("NEW", "IN_PROGRESS"), t("IN_PROGRESS", "CLOSED"), t("TRIAGE", "ASSIGNED")), false, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("unreachable");
    }

    @Test
    void rejectsWayOutOfAFinalState() {
        assertThatThrownBy(() -> service.save(1L, List.of(
                t("NEW", "CANCELLED"), t("CANCELLED", "NEW")), false, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("final");
    }

    @Test
    void rejectsAWorkflowThatCanNeverFinish() {
        assertThatThrownBy(() -> service.save(1L, List.of(t("NEW", "IN_PROGRESS")), false, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("CLOSED or CANCELLED");
    }

    @Test
    void approvalGateMayOnlyLeadBackToWorkOrCancellation() {
        assertThatThrownBy(() -> service.save(1L, List.of(
                t("NEW", "IN_PROGRESS"), t("IN_PROGRESS", "WAITING_APPROVAL"), t("WAITING_APPROVAL", "RESOLVED"),
                t("RESOLVED", "CLOSED")), true, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("WAITING_APPROVAL");
    }

    @Test
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> service.save(1L, List.of(t("NEW", "BOGUS")), false, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Unknown status");
    }
}
