package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.AttachmentResponse;
import com.erp.ticketing.ticket.model.Attachment;
import com.erp.ticketing.ticket.service.AttachmentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@RestController
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/api/helpdesk/tickets/{ticketId}/attachments")
    public ResponseEntity<AttachmentResponse> upload(@PathVariable Long ticketId,
                                                       @RequestParam("file") MultipartFile file,
                                                       @AuthenticationPrincipal ErpUserPrincipal principal) {
        AttachmentResponse response = attachmentService.upload(ticketId, file, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/helpdesk/tickets/{ticketId}/attachments")
    public List<AttachmentResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return attachmentService.getForTicket(ticketId, principal);
    }

    @GetMapping("/api/helpdesk/attachments/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal principal) {
        Attachment attachment = attachmentService.getAuthorized(id, principal);
        Path filePath = attachmentService.resolveStoragePath(attachment);
        Resource resource = new FileSystemResource(filePath);

        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        if (attachment.getFileType() != null) {
            try {
                contentType = MediaType.parseMediaType(attachment.getFileType());
            } catch (IllegalArgumentException ignored) {
                // Fall back to a generic binary content type if the stored value isn't a valid media type.
            }
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachment.getFileName() + "\"")
                .body(resource);
    }
}
