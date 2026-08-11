package com.aspera.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    @Test
    void blocksRepeatedFailuresByNormalizedUsernameAndClearsOnSuccess() {
        LoginAttemptService service = new LoginAttemptService(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));

        for (int i = 0; i < LoginAttemptService.USER_FAILURE_LIMIT; i++) {
            service.recordFailure("192.0.2." + i, " Admin ");
        }

        assertThat(service.isBlocked("198.51.100.20", "admin")).isTrue();
        service.recordSuccess("ADMIN");
        assertThat(service.isBlocked("198.51.100.20", "admin")).isFalse();
    }

    @Test
    void blocksRepeatedFailuresFromOneAddressAcrossUsernames() {
        LoginAttemptService service = new LoginAttemptService(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));

        for (int i = 0; i < LoginAttemptService.ADDRESS_FAILURE_LIMIT; i++) {
            service.recordFailure("192.0.2.15", "user" + i);
        }

        assertThat(service.isBlocked("192.0.2.15", "different-user")).isTrue();
    }
}
