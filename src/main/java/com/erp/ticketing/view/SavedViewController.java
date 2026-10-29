package com.erp.ticketing.view;

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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/helpdesk/views")
public class SavedViewController {

    private final SavedViewService service;

    public SavedViewController(SavedViewService service) {
        this.service = service;
    }

    @GetMapping
    public List<SavedViewService.View> list(@AuthenticationPrincipal ErpUserPrincipal p) {
        return service.list(p);
    }

    @PostMapping
    public ResponseEntity<SavedViewService.View> save(@RequestBody SavedViewService.Save body,
                                                      @AuthenticationPrincipal ErpUserPrincipal p) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.save(body, p));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal ErpUserPrincipal p) {
        service.delete(id, p);
        return ResponseEntity.noContent().build();
    }
}
