package com.aspera.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void disabledBootstrapDoesNotInspectOrMutateDatabase() {
        DataInitializer initializer = new DataInitializer(
                userRepository, passwordEncoder, false, "admin", "REPLACE_TEST_PASSWORD", "admin@example.com");

        initializer.run();

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void weakBootstrapPasswordNeverCreatesDefaultAdmin() {
        when(userRepository.count()).thenReturn(0L);
        DataInitializer initializer = new DataInitializer(
                userRepository, passwordEncoder, true, "admin", "admin", "admin@example.com");

        initializer.run();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void bootstrapRejectsPasswordsOverBcryptUtf8ByteLimit() {
        when(userRepository.count()).thenReturn(0L);
        String oversizedMultibytePassword = "가".repeat(25);
        DataInitializer initializer = new DataInitializer(
                userRepository, passwordEncoder, true, "admin", oversizedMultibytePassword, "admin@example.com");

        initializer.run();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void bootstrapRejectsEmailThatExceedsNormalAccountLimit() {
        when(userRepository.count()).thenReturn(0L);
        String oversizedEmail = "a".repeat(245) + "@example.com";
        assertThat(oversizedEmail.length()).isGreaterThan(254);
        DataInitializer initializer = new DataInitializer(
                userRepository, passwordEncoder, true, "admin", "REPLACE_TEST_PASSWORD", oversizedEmail);

        initializer.run();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void explicitBootstrapStoresOnlyEncodedPassword() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("REPLACE_TEST_PASSWORD")).thenReturn("encoded-password");
        DataInitializer initializer = new DataInitializer(
                userRepository, passwordEncoder, true, "admin-user", "REPLACE_TEST_PASSWORD", "admin@example.com");

        initializer.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("admin-user");
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded-password");
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
    }
}
