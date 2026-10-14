package com.erp.ticketing.ticket.model;

import com.erp.ticketing.ticket.service.AttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityMatrixAndAttachmentNameTest {

    @ParameterizedTest
    @CsvSource({
            "HIGH,HIGH,P1", "HIGH,MEDIUM,P2", "HIGH,LOW,P2",
            "MEDIUM,HIGH,P2", "MEDIUM,MEDIUM,P2", "MEDIUM,LOW,P3",
            "LOW,HIGH,P3", "LOW,MEDIUM,P3", "LOW,LOW,P4"})
    void matrixMatchesTheSpecExample(Level impact, Level urgency, TicketPriority expected) {
        assertThat(PriorityMatrix.resolve(impact, urgency)).isEqualTo(expected);
    }

    @Test
    void attachmentNamesCannotCarryPathsOrHeaderInjection() {
        assertThat(AttachmentService.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentService.sanitizeFileName("C:\\temp\\evil.exe")).isEqualTo("evil.exe");
        assertThat(AttachmentService.sanitizeFileName("a\"b\r\nc.pdf")).doesNotContain("\"", "\r", "\n");
        assertThat(AttachmentService.sanitizeFileName(null)).isEqualTo("attachment");
        assertThat(AttachmentService.sanitizeFileName("   ")).isEqualTo("attachment");
    }
}
