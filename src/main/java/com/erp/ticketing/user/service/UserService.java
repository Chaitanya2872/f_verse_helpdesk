package com.erp.ticketing.user.service;

import com.erp.ticketing.common.NotFoundException;
import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Resolves the {@link User} row for the currently authenticated ERP
     * principal, creating it on first sight and refreshing email/name if the
     * upstream identity headers have changed since we last saw this user.
     * There is no separate registration flow - this IS the user lifecycle.
     * Runs in its own transaction (REQUIRES_NEW) since callers such as
     * TicketService.list()/get() are read-only transactions, and this method
     * needs a writable one when it has to insert/update the user row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User getOrCreateCurrentUser(ErpUserPrincipal principal) {
        User user = userRepository.findByErpUserId(principal.erpUserId())
                .orElseGet(() -> createUserWithRetry(principal));

        // X-User-Email/X-User-Name are optional per request (TRD 3.1) - a request
        // that omits them must not blank out a value we already captured earlier.
        boolean changed = false;
        if (principal.email() != null && !principal.email().equals(user.getEmail())) {
            user.setEmail(principal.email());
            changed = true;
        }
        if (principal.name() != null && !principal.name().equals(user.getName())) {
            user.setName(principal.name());
            changed = true;
        }

        if (user.getId() == null || changed) {
            user = userRepository.save(user);
        }
        return user;
    }

    private User createUserWithRetry(ErpUserPrincipal principal) {
        User created = new User();
        created.setErpUserId(principal.erpUserId());
        created.setEmail(principal.email());
        created.setName(principal.name());
        created.setActive(true);

        try {
            return userRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            return userRepository.findByErpUserId(principal.erpUserId())
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public User getRequiredUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }
}
