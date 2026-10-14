package com.erp.ticketing.itsm.controller;

import com.erp.ticketing.itsm.dto.ItsmDto;
import com.erp.ticketing.itsm.service.ChangeService;
import com.erp.ticketing.itsm.service.MajorIncidentService;
import com.erp.ticketing.itsm.service.ProblemService;
import com.erp.ticketing.security.ErpUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Problem, change and major-incident endpoints. Everything except {@code /major-incidents/public}
 * is staff-only (see SecurityConfig); manager-only actions are enforced in the services.
 */
@RestController
@RequestMapping("/api/helpdesk")
public class ItsmController {

    private final ProblemService problems;
    private final ChangeService changes;
    private final MajorIncidentService incidents;

    public ItsmController(ProblemService problems, ChangeService changes, MajorIncidentService incidents) {
        this.problems = problems;
        this.changes = changes;
        this.incidents = incidents;
    }

    // ---- problems

    @GetMapping("/problems")
    public List<ItsmDto.Problem> problems(@RequestParam(required = false) String status,
                                          @AuthenticationPrincipal ErpUserPrincipal p) {
        return problems.list(status, p);
    }

    @GetMapping("/problems/{id}")
    public ItsmDto.Problem problem(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal p) {
        return problems.get(id, p);
    }

    @PostMapping("/problems")
    public ResponseEntity<ItsmDto.Problem> createProblem(@RequestBody ItsmDto.ProblemSave body,
                                                         @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(problems.create(body, p));
    }

    @PutMapping("/problems/{id}")
    public ItsmDto.Problem updateProblem(@PathVariable Long id, @RequestBody ItsmDto.ProblemSave body,
                                         @AuthenticationPrincipal ErpUserPrincipal p) {
        return problems.update(id, body, p);
    }

    @PostMapping("/problems/{id}/status")
    public ItsmDto.Problem problemStatus(@PathVariable Long id, @RequestBody ItsmDto.StatusRequest body,
                                         @AuthenticationPrincipal ErpUserPrincipal p) {
        return problems.changeStatus(id, body, p);
    }

    @PostMapping("/problems/{id}/tickets/{ticketId}")
    public ItsmDto.Problem linkProblemTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                             @AuthenticationPrincipal ErpUserPrincipal p) {
        return problems.linkTicket(id, ticketId, p);
    }

    @DeleteMapping("/problems/{id}/tickets/{ticketId}")
    public ItsmDto.Problem unlinkProblemTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                               @AuthenticationPrincipal ErpUserPrincipal p) {
        return problems.unlinkTicket(id, ticketId, p);
    }

    // ---- changes

    @GetMapping("/changes")
    public List<ItsmDto.Change> changes(@RequestParam(required = false) String status,
                                        @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.list(status, p);
    }

    @GetMapping("/changes/calendar")
    public List<ItsmDto.Change> calendar(@RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @AuthenticationPrincipal ErpUserPrincipal p) {
        Instant start = from != null ? from : Instant.now().minus(java.time.Duration.ofDays(1));
        Instant end = to != null ? to : start.plus(java.time.Duration.ofDays(60));
        return changes.calendar(start, end, p);
    }

    @GetMapping("/changes/{id}")
    public ItsmDto.Change change(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.get(id, p);
    }

    @PostMapping("/changes")
    public ResponseEntity<ItsmDto.Change> createChange(@RequestBody ItsmDto.ChangeSave body,
                                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(changes.create(body, p));
    }

    @PutMapping("/changes/{id}")
    public ItsmDto.Change updateChange(@PathVariable Long id, @RequestBody ItsmDto.ChangeSave body,
                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.update(id, body, p);
    }

    @PostMapping("/changes/{id}/submit")
    public ItsmDto.Change submitChange(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.submit(id, p);
    }

    @PostMapping("/changes/{id}/decision")
    public ItsmDto.Change decideChange(@PathVariable Long id, @RequestBody ItsmDto.ChangeDecision body,
                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.decide(id, body, p);
    }

    @PostMapping("/changes/{id}/schedule")
    public ItsmDto.Change scheduleChange(@PathVariable Long id, @RequestBody ItsmDto.ChangeSchedule body,
                                         @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.schedule(id, body, p);
    }

    @PostMapping("/changes/{id}/start")
    public ItsmDto.Change startChange(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.start(id, p);
    }

    @PostMapping("/changes/{id}/finish")
    public ItsmDto.Change finishChange(@PathVariable Long id, @RequestBody ItsmDto.ChangeFinish body,
                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.finish(id, body, p);
    }

    @PostMapping("/changes/{id}/cancel")
    public ItsmDto.Change cancelChange(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.cancel(id, body.get("reason"), p);
    }

    @PostMapping("/changes/{id}/tickets/{ticketId}")
    public ItsmDto.Change linkChangeTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                           @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.linkTicket(id, ticketId, p);
    }

    @PostMapping("/changes/{id}/problems/{problemId}")
    public ItsmDto.Change linkChangeProblem(@PathVariable Long id, @PathVariable Long problemId,
                                            @AuthenticationPrincipal ErpUserPrincipal p) {
        return changes.linkProblem(id, problemId, p);
    }

    // ---- major incidents

    /** Open to every role: active incidents with only their customer-facing details and public updates. */
    @GetMapping("/major-incidents/public")
    public List<ItsmDto.PublicIncident> publicIncidents() {
        return incidents.publicView();
    }

    @GetMapping("/major-incidents")
    public List<ItsmDto.Incident> incidents(@RequestParam(defaultValue = "false") boolean activeOnly,
                                            @AuthenticationPrincipal ErpUserPrincipal p) {
        return incidents.list(activeOnly, p);
    }

    @GetMapping("/major-incidents/{id}")
    public ItsmDto.Incident incident(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal p) {
        return incidents.get(id, p);
    }

    @PostMapping("/major-incidents")
    public ResponseEntity<ItsmDto.Incident> declare(@RequestBody ItsmDto.IncidentDeclare body,
                                                    @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(incidents.declare(body, p));
    }

    @PostMapping("/major-incidents/{id}/updates")
    public ItsmDto.Incident postUpdate(@PathVariable Long id, @RequestBody ItsmDto.IncidentUpdateRequest body,
                                       @AuthenticationPrincipal ErpUserPrincipal p) {
        return incidents.postUpdate(id, body, p);
    }

    @PostMapping("/major-incidents/{id}/status")
    public ItsmDto.Incident incidentStatus(@PathVariable Long id, @RequestBody ItsmDto.IncidentStatusRequest body,
                                           @AuthenticationPrincipal ErpUserPrincipal p) {
        return incidents.changeStatus(id, body, p);
    }

    @PostMapping("/major-incidents/{id}/tickets/{ticketId}")
    public ItsmDto.Incident linkIncidentTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                               @AuthenticationPrincipal ErpUserPrincipal p) {
        return incidents.linkTicket(id, ticketId, p);
    }
}
