package com.aspera.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.service.AdminAccountService.MutationResult;
import com.aspera.web.service.AdminAccountService.MutationStatus;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import(AdminAccountService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminAccountServiceConcurrencyTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminAccountService adminAccountService;

    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void refusesToRemoveTheOnlyAdministrator() {
        User onlyAdmin = userRepository.save(user("only-admin", "ADMIN"));

        MutationResult result = adminAccountService.revokeAdmin(onlyAdmin.getId(), "different-admin");

        assertThat(result.status()).isEqualTo(MutationStatus.LAST_ADMIN_DENIED);
        assertThat(userRepository.countByRole("ADMIN")).isEqualTo(1);
    }

    @Test
    void concurrentDeleteAndRevokeCannotRemoveEveryAdministrator() throws Exception {
        User first = userRepository.save(user("admin-one", "ADMIN"));
        User second = userRepository.save(user("admin-two", "ADMIN"));
        CountDownLatch start = new CountDownLatch(1);

        Future<MutationResult> delete = workers.submit(() -> {
            start.await();
            return adminAccountService.deleteUser(first.getId(), "external-operator");
        });
        Future<MutationResult> revoke = workers.submit(() -> {
            start.await();
            return adminAccountService.revokeAdmin(second.getId(), "external-operator");
        });
        start.countDown();

        List<MutationStatus> statuses = List.of(
                delete.get(10, TimeUnit.SECONDS).status(),
                revoke.get(10, TimeUnit.SECONDS).status());
        assertThat(statuses).containsExactlyInAnyOrder(
                MutationStatus.SUCCESS, MutationStatus.LAST_ADMIN_DENIED);
        assertThat(userRepository.countByRole("ADMIN")).isEqualTo(1);
    }

    private User user(String username, String role) {
        return new User(username, "encoded-password", role);
    }
}
