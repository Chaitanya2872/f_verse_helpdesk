package com.erp.ticketing.reference;

import com.erp.ticketing.integration.asset.AssetManagerClient;
import com.erp.ticketing.security.ErpUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk")
public class ReferenceController {

    private final ReferenceService service;
    private final AssetManagerClient assetManager;

    public ReferenceController(ReferenceService service, AssetManagerClient assetManager) {
        this.service = service;
        this.assetManager = assetManager;
    }

    // ---- tickets (anyone who can see the ticket)

    @GetMapping("/tickets/{ticketId}/erp-links")
    public List<ReferenceService.Reference> ticketLinks(@PathVariable Long ticketId,
                                                        @AuthenticationPrincipal ErpUserPrincipal p) {
        return service.forTicket(ticketId, p);
    }

    @PostMapping("/tickets/{ticketId}/erp-links")
    public ResponseEntity<ReferenceService.Reference> addTicketLink(@PathVariable Long ticketId,
                                                                    @RequestBody ReferenceService.Request body,
                                                                    @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addToTicket(ticketId, body, p));
    }

    @DeleteMapping("/tickets/{ticketId}/erp-links/{linkId}")
    public ResponseEntity<Void> removeTicketLink(@PathVariable Long ticketId, @PathVariable Long linkId,
                                                 @AuthenticationPrincipal ErpUserPrincipal p) {
        service.removeFromTicket(ticketId, linkId, p);
        return ResponseEntity.noContent().build();
    }

    // ---- problems / changes / major incidents (staff, see SecurityConfig)

    @GetMapping("/problems/{id}/references")
    public List<ReferenceService.Reference> problemRefs(@PathVariable Long id) {
        return service.forOwner("PROBLEM", id);
    }

    @PostMapping("/problems/{id}/references")
    public ResponseEntity<ReferenceService.Reference> addProblemRef(@PathVariable Long id,
                                                                    @RequestBody ReferenceService.Request body,
                                                                    @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addToOwner("PROBLEM", id, body, p));
    }

    @DeleteMapping("/problems/{id}/references/{linkId}")
    public ResponseEntity<Void> removeProblemRef(@PathVariable Long id, @PathVariable Long linkId) {
        service.removeFromOwner("PROBLEM", id, linkId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/changes/{id}/references")
    public List<ReferenceService.Reference> changeRefs(@PathVariable Long id) {
        return service.forOwner("CHANGE", id);
    }

    @PostMapping("/changes/{id}/references")
    public ResponseEntity<ReferenceService.Reference> addChangeRef(@PathVariable Long id,
                                                                   @RequestBody ReferenceService.Request body,
                                                                   @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addToOwner("CHANGE", id, body, p));
    }

    @DeleteMapping("/changes/{id}/references/{linkId}")
    public ResponseEntity<Void> removeChangeRef(@PathVariable Long id, @PathVariable Long linkId) {
        service.removeFromOwner("CHANGE", id, linkId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/major-incidents/{id}/references")
    public List<ReferenceService.Reference> incidentRefs(@PathVariable Long id) {
        return service.forOwner("MAJOR_INCIDENT", id);
    }

    @PostMapping("/major-incidents/{id}/references")
    public ResponseEntity<ReferenceService.Reference> addIncidentRef(@PathVariable Long id,
                                                                     @RequestBody ReferenceService.Request body,
                                                                     @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addToOwner("MAJOR_INCIDENT", id, body, p));
    }

    @DeleteMapping("/major-incidents/{id}/references/{linkId}")
    public ResponseEntity<Void> removeIncidentRef(@PathVariable Long id, @PathVariable Long linkId) {
        service.removeFromOwner("MAJOR_INCIDENT", id, linkId);
        return ResponseEntity.noContent().build();
    }

    // ---- lookups

    /** Assets from the Asset Manager; empty while that integration is switched off. */
    @GetMapping("/erp/assets")
    public List<AssetManagerClient.AssetSummary> assets(@RequestParam(required = false) String query) {
        return assetManager.search(query);
    }

    /**
     * Search endpoints the selector calls for systems that are not connected. They return an empty list
     * until an integration owns the source of truth (BRS 14.4); references are then entered by id and label instead.
     */
    @GetMapping({"/erp/customers", "/erp/sales-orders"})
    public List<Object> lookups(@RequestParam(required = false) String query) {
        return List.of();
    }
}
