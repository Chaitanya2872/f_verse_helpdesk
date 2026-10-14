package com.erp.ticketing.approval.job;

import com.erp.ticketing.approval.service.ApprovalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically marks approval tasks past their deadline as EXPIRED and notifies the requester. */
@Component
public class ApprovalTimeoutJob {

    private final ApprovalService approvalService;

    @Value("${app.approvals.timeout-job.enabled:true}")
    private boolean enabled;

    public ApprovalTimeoutJob(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Scheduled(fixedDelayString = "${app.approvals.timeout-job.fixed-delay-ms:300000}")
    public void run() {
        if (enabled) {
            approvalService.expireOverdue();
        }
    }
}
