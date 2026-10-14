package com.erp.ticketing.workflow.dto;

import java.util.List;

public final class WorkflowDto {

    private WorkflowDto() {
    }

    public record Transition(String from, String to) {
    }

    /** {@code overridden} is false when the type has no rows of its own and inherits the default workflow. */
    public record Workflow(Long ticketTypeId, boolean overridden, List<Transition> transitions) {
    }

    public record SaveRequest(List<Transition> transitions) {
    }
}
