package com.erp.ticketing.ticket.service;

import com.erp.ticketing.common.BadRequestException;
import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.AttachmentResponse;
import com.erp.ticketing.ticket.model.Attachment;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.AttachmentRepository;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** BRS 18.2 allowlist - executables, scripts and archives are rejected. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".txt", ".csv", ".log",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".msg", ".eml");

    private final AttachmentRepository attachmentRepository;
    private final TicketRepository ticketRepository;
    private final UserService userService;
    private final TicketAccessService accessService;
    private final Path storageDir;

    public AttachmentService(AttachmentRepository attachmentRepository,
                              TicketRepository ticketRepository,
                              UserService userService,
                              TicketAccessService accessService,
                              @Value("${app.attachments.storage-dir}") String storageDir) {
        this.attachmentRepository = attachmentRepository;
        this.ticketRepository = ticketRepository;
        this.userService = userService;
        this.accessService = accessService;
        this.storageDir = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create attachment storage directory: " + this.storageDir, e);
        }
    }

    @Transactional
    public AttachmentResponse upload(Long ticketId, MultipartFile file, ErpUserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file provided");
        }
        User uploader = userService.getOrCreateCurrentUser(principal);
        Ticket ticket = requireVisibleTicket(ticketId, principal, uploader);

        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BadRequestException("File exceeds the " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB limit");
        }
        String originalName = sanitizeFileName(file.getOriginalFilename());
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalName.substring(dotIndex).toLowerCase(Locale.ROOT);
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("File type not allowed: " + (extension.isEmpty() ? "(none)" : extension));
        }
        String generatedName = UUID.randomUUID() + extension;
        Path targetPath = storageDir.resolve(generatedName);

        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store attachment", e);
        }

        Attachment attachment = new Attachment();
        attachment.setTicket(ticket);
        attachment.setFileName(originalName);
        attachment.setFileType(file.getContentType());
        attachment.setFileSizeBytes(file.getSize());
        attachment.setStoragePath(generatedName);
        attachment.setUploadedBy(uploader);

        attachment = attachmentRepository.save(attachment);
        return toResponse(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> getForTicket(Long ticketId, ErpUserPrincipal principal) {
        User currentUser = userService.getOrCreateCurrentUser(principal);
        requireVisibleTicket(ticketId, principal, currentUser);
        return attachmentRepository.findByTicket_IdOrderByCreatedAtAsc(ticketId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Loads an attachment only if the caller may see its ticket (BRS 18.1: no direct object references). */
    @Transactional(readOnly = true)
    public Attachment getAuthorized(Long attachmentId, ErpUserPrincipal principal) {
        Attachment attachment = getRequired(attachmentId);
        User currentUser = userService.getOrCreateCurrentUser(principal);
        requireVisibleTicket(attachment.getTicket().getId(), principal, currentUser);
        return attachment;
    }

    /** Strips any path and control characters so the stored display name can never traverse or inject headers. */
    public static String sanitizeFileName(String raw) {
        String name = raw == null ? "attachment" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "_").trim();
        return name.isEmpty() ? "attachment" : name;
    }

    @Transactional(readOnly = true)
    public Attachment getRequired(Long attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
    }

    public Path resolveStoragePath(Attachment attachment) {
        return storageDir.resolve(attachment.getStoragePath());
    }

    private Ticket requireVisibleTicket(Long ticketId, ErpUserPrincipal principal, User currentUser) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        accessService.assertVisible(ticket, principal, currentUser);
        return ticket;
    }

    private AttachmentResponse toResponse(Attachment attachment) {
        User uploadedBy = attachment.getUploadedBy();
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getTicket().getId(),
                attachment.getComment() != null ? attachment.getComment().getId() : null,
                attachment.getFileName(),
                attachment.getFileType(),
                attachment.getFileSizeBytes(),
                uploadedBy != null ? uploadedBy.getId() : null,
                uploadedBy != null ? uploadedBy.getName() : null,
                attachment.getCreatedAt(),
                "/api/helpdesk/attachments/" + attachment.getId() + "/download");
    }
}
