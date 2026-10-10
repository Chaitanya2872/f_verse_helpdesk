package com.erp.ticketing.ticket.controller;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.ticket.dto.CommentCreateRequest;
import com.erp.ticketing.ticket.dto.CommentResponse;
import com.erp.ticketing.ticket.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk/tickets/{ticketId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public List<CommentResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal ErpUserPrincipal principal) {
        return commentService.getForTicket(ticketId, principal);
    }

    @PostMapping
    public ResponseEntity<CommentResponse> create(@PathVariable Long ticketId,
                                                   @Valid @RequestBody CommentCreateRequest request,
                                                   @AuthenticationPrincipal ErpUserPrincipal principal) {
        CommentResponse response = commentService.create(ticketId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
