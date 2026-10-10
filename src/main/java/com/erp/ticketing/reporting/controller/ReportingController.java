package com.erp.ticketing.reporting.controller;

import com.erp.ticketing.reporting.dto.DashboardSummaryResponse;
import com.erp.ticketing.reporting.dto.EscalationResponse;
import com.erp.ticketing.reporting.dto.ReportSummaryResponse;
import com.erp.ticketing.reporting.dto.SlaComplianceResponse;
import com.erp.ticketing.reporting.dto.SlaComplianceTrendResponse;
import com.erp.ticketing.reporting.dto.CsatSummaryResponse;
import com.erp.ticketing.reporting.dto.DepartmentWorkloadResponse;
import com.erp.ticketing.reporting.dto.TicketVolumeTrendResponse;
import com.erp.ticketing.reporting.dto.TicketsByPriorityResponse;
import com.erp.ticketing.reporting.dto.TicketsByStatusResponse;
import com.erp.ticketing.reporting.dto.TicketsByTypeResponse;
import com.erp.ticketing.reporting.service.ReportingService;
import com.erp.ticketing.security.ErpUserPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * Cross-department aggregate reporting. Restricted to AGENT/MANAGER/ADMIN by
 * the dedicated /api/helpdesk/reports/** matcher in SecurityConfig (raw requesters
 * shouldn't see cross-department aggregate data), inserted ahead of the
 * general /api/helpdesk/** matcher.
 */
@RestController
@RequestMapping("/api/helpdesk/reports")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/summary")
    public ReportSummaryResponse summary(@RequestParam(required = false) String dateFrom,
                                          @RequestParam(required = false) String dateTo,
                                          @RequestParam(required = false) Long typeId,
                                          @RequestParam(required = false) Long departmentId) {
        return reportingService.summary(dateFrom, dateTo, typeId, departmentId);
    }

    @GetMapping("/sla-compliance")
    public SlaComplianceResponse slaCompliance(@RequestParam(required = false) String dateFrom,
                                                @RequestParam(required = false) String dateTo,
                                                @RequestParam(required = false) Long typeId,
                                                @RequestParam(required = false) Long departmentId) {
        return reportingService.slaCompliance(dateFrom, dateTo, typeId, departmentId);
    }

    @GetMapping("/tickets-by-type")
    public List<TicketsByTypeResponse> ticketsByType(@RequestParam(required = false) String dateFrom,
                                                       @RequestParam(required = false) String dateTo,
                                                       @RequestParam(required = false) Long typeId,
                                                       @RequestParam(required = false) Long departmentId) {
        return reportingService.ticketsByType(dateFrom, dateTo, typeId, departmentId);
    }

    /**
     * Unlike every other endpoint in this controller, this one is reachable by
     * REQUESTER too - see the dedicated /api/helpdesk/reports/dashboard/summary matcher
     * in SecurityConfig, declared ahead of the general /api/helpdesk/reports/** matcher.
     */
    @GetMapping("/dashboard/summary")
    public DashboardSummaryResponse dashboardSummary(@AuthenticationPrincipal ErpUserPrincipal principal) {
        return reportingService.dashboardSummary(principal);
    }

    @GetMapping("/ticket-volume-trend")
    public List<TicketVolumeTrendResponse> ticketVolumeTrend(@RequestParam(required = false) Integer days) {
        return reportingService.ticketVolumeTrend(days);
    }

    @GetMapping("/tickets-by-status")
    public List<TicketsByStatusResponse> ticketsByStatus(@RequestParam(required = false) String dateFrom,
                                                           @RequestParam(required = false) String dateTo,
                                                           @RequestParam(required = false) Long typeId,
                                                           @RequestParam(required = false) Long departmentId) {
        return reportingService.ticketsByStatus(dateFrom, dateTo, typeId, departmentId);
    }

    @GetMapping("/tickets-by-priority")
    public List<TicketsByPriorityResponse> ticketsByPriority(@RequestParam(required = false) String dateFrom,
                                                               @RequestParam(required = false) String dateTo,
                                                               @RequestParam(required = false) Long typeId,
                                                               @RequestParam(required = false) Long departmentId) {
        return reportingService.ticketsByPriority(dateFrom, dateTo, typeId, departmentId);
    }

    @GetMapping("/department-workload")
    public List<DepartmentWorkloadResponse> departmentWorkload(@RequestParam(required = false) Long departmentId,
                                                                @AuthenticationPrincipal ErpUserPrincipal principal) {
        return reportingService.departmentWorkload(departmentId, principal);
    }

    @GetMapping("/sla-compliance-trend")
    public List<SlaComplianceTrendResponse> slaComplianceTrend(@RequestParam(required = false) Integer days) {
        return reportingService.slaComplianceTrend(days);
    }

    @GetMapping("/escalations")
    public List<EscalationResponse> escalations(@RequestParam(required = false) Long departmentId,
                                                 @RequestParam(required = false) Long assigneeId) {
        return reportingService.escalations(departmentId, assigneeId);
    }

    @GetMapping("/csat")
    public CsatSummaryResponse csat(@RequestParam(required = false) String dateFrom,
                                     @RequestParam(required = false) String dateTo,
                                     @RequestParam(required = false) Long departmentId) {
        return reportingService.csatSummary(dateFrom, dateTo, departmentId);
    }

    @GetMapping("/tickets/export.csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(@RequestParam(required = false) String dateFrom,
                                                             @RequestParam(required = false) String dateTo,
                                                             @RequestParam(required = false) Long typeId,
                                                             @RequestParam(required = false) Long departmentId,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) String priority,
                                                             @RequestParam(required = false) Long assigneeId,
                                                             @RequestParam(required = false) Long requesterId,
                                                             @RequestParam(required = false) String ticketIds) {
        StreamingResponseBody body = outputStream -> reportingService.streamTicketsCsv(
                dateFrom, dateTo, typeId, departmentId, status, priority, assigneeId, requesterId, ticketIds, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tickets-export.csv\"")
                .body(body);
    }
}
