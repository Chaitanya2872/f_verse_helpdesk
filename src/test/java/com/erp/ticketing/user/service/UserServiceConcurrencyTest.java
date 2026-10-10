package com.erp.ticketing.user.service;

import com.erp.ticketing.security.ErpUserPrincipal;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceConcurrencyTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void getOrCreateCurrentUser_whenRaceInsertHitsUniqueConstraint_returnsExistingUser() {
        String erpUserId = "DEV-USER-1";
        ErpUserPrincipal principal = new ErpUserPrincipal(erpUserId, "user@example.com", "Race User", Set.of("AGENT"));

        User existingUser = new User();
        existingUser.setId(42L);
        existingUser.setErpUserId(erpUserId);
        existingUser.setEmail("old@example.com");
        existingUser.setName("Old Name");
        existingUser.setActive(true);

        when(userRepository.findByErpUserId(erpUserId))
                .thenReturn(Optional.empty(), Optional.of(existingUser));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    user.setId(42L);
                    return user;
                });

        User result = userService.getOrCreateCurrentUser(principal);

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getErpUserId()).isEqualTo(erpUserId);
        assertThat(result.getEmail()).isEqualTo("user@example.com");
        assertThat(result.getName()).isEqualTo("Race User");
    }
}
