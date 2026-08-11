package com.aspera.web.config;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean bootstrapEnabled;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final String bootstrapEmail;

    public DataInitializer(UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap-admin.enabled:false}") boolean bootstrapEnabled,
            @Value("${app.bootstrap-admin.username:}") String bootstrapUsername,
            @Value("${app.bootstrap-admin.password:}") String bootstrapPassword,
            @Value("${app.bootstrap-admin.email:}") String bootstrapEmail) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.bootstrapEmail = bootstrapEmail;
    }

    @Override
    public void run(String... args) {
        if (!bootstrapEnabled) {
            return;
        }
        if (userRepository.count() != 0) {
            return;
        }

        int passwordBytes = bootstrapPassword == null
                ? 0
                : bootstrapPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bootstrapPassword == null || bootstrapPassword.length() < 12 || passwordBytes > 72) {
            log.warn("Administrator bootstrap is enabled, but BOOTSTRAP_ADMIN_PASSWORD must be at least 12 "
                    + "characters and no more than 72 UTF-8 bytes.");
            return;
        }

        String username = bootstrapUsername == null ? "" : bootstrapUsername.trim();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            log.warn("Administrator bootstrap skipped because BOOTSTRAP_ADMIN_USERNAME is invalid.");
            return;
        }

        String email = bootstrapEmail == null ? "" : bootstrapEmail.trim();
        if (!isValidEmail(email)) {
            log.warn("Administrator bootstrap skipped because BOOTSTRAP_ADMIN_EMAIL is invalid.");
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(bootstrapPassword));
        admin.setEmail(email);
        admin.setRole("ADMIN");
        userRepository.save(admin);
        log.info("One-time administrator bootstrap completed for username '{}'.", username);
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254
                && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }
}
