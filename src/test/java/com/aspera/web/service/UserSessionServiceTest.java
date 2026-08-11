package com.aspera.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;

class UserSessionServiceTest {

    @Test
    void expiresEverySessionForTargetUserOnly() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        User target = (User) User.withUsername("target-admin").password("encoded").roles("ADMIN").build();
        User other = (User) User.withUsername("other-admin").password("encoded").roles("ADMIN").build();
        registry.registerNewSession("target-1", target);
        registry.registerNewSession("target-2", target);
        registry.registerNewSession("other-1", other);
        UserSessionService service = new UserSessionService(registry);

        int expired = service.expireAllSessions("target-admin");

        assertThat(expired).isEqualTo(2);
        assertThat(registry.getSessionInformation("target-1").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("target-2").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("other-1").isExpired()).isFalse();
    }
}
