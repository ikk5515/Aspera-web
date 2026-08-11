package com.aspera.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.service.UserPermissionService.MutationResult;
import com.aspera.web.service.UserPermissionService.MutationStatus;
import java.util.List;
import java.util.Map;
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
@Import({ UserPermissionService.class, AdminAccountService.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserPermissionServiceConcurrencyTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private AdminAccountService adminAccountService;

    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        workers = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void concurrentUpdateCannotRestoreADeletedPermissionFromAStaleUserGraph() throws Exception {
        User user = new User("permission-user", "encoded-password", "USER");
        FolderPermission revoked = new FolderPermission("/revoked", false, true, false, false);
        FolderPermission retained = new FolderPermission("/retained", false, true, false, false);
        user.addPermission(revoked);
        user.addPermission(retained);
        user = userRepository.saveAndFlush(user);
        long userId = user.getId();
        long revokedId = revoked.getId();
        long retainedId = retained.getId();
        CountDownLatch start = new CountDownLatch(1);

        Future<MutationResult> deletion = workers.submit(() -> {
            start.await();
            return userPermissionService.deletePermission(userId, revokedId);
        });
        Future<MutationResult> update = workers.submit(() -> {
            start.await();
            return userPermissionService.updatePermission(userId, retainedId, Map.of("canUpload", true));
        });
        start.countDown();

        assertThat(deletion.get(10, TimeUnit.SECONDS).status()).isEqualTo(MutationStatus.SUCCESS);
        assertThat(update.get(10, TimeUnit.SECONDS).status()).isEqualTo(MutationStatus.SUCCESS);

        User reloaded = userRepository.findByIdWithPermissions(userId).orElseThrow();
        assertThat(reloaded.getPermissions())
                .extracting(FolderPermission::getPath)
                .containsExactly("/retained");
        assertThat(reloaded.getPermissions().get(0).isCanUpload()).isTrue();
    }

    @Test
    void concurrentIdenticalAddsCreateOnlyOnePermission() throws Exception {
        User user = userRepository.saveAndFlush(new User("permission-user", "encoded-password", "USER"));
        long userId = user.getId();
        CountDownLatch start = new CountDownLatch(1);

        Future<MutationResult> first = workers.submit(() -> {
            start.await();
            return userPermissionService.addPermissions(
                    userId, List.of("/shared"), false, true, false, false);
        });
        Future<MutationResult> second = workers.submit(() -> {
            start.await();
            return userPermissionService.addPermissions(
                    userId, List.of("/shared"), false, true, false, false);
        });
        start.countDown();

        assertThat(List.of(
                first.get(10, TimeUnit.SECONDS).status(),
                second.get(10, TimeUnit.SECONDS).status()))
                .containsExactlyInAnyOrder(MutationStatus.SUCCESS, MutationStatus.DUPLICATE_PATHS);
        assertThat(userRepository.findByIdWithPermissions(userId).orElseThrow().getPermissions())
                .extracting(FolderPermission::getPath)
                .containsExactly("/shared");
    }

    @Test
    void concurrentPromotionCannotRestoreDeletedOrOutdatedPermissions() throws Exception {
        User user = new User("promotion-user", "encoded-password", "USER");
        FolderPermission revoked = new FolderPermission("/revoked", false, true, false, false);
        FolderPermission retained = new FolderPermission("/retained", false, true, false, false);
        user.addPermission(revoked);
        user.addPermission(retained);
        user = userRepository.saveAndFlush(user);
        long userId = user.getId();
        long revokedId = revoked.getId();
        long retainedId = retained.getId();
        CountDownLatch start = new CountDownLatch(1);

        Future<MutationResult> deletion = workers.submit(() -> {
            start.await();
            return userPermissionService.deletePermission(userId, revokedId);
        });
        Future<MutationResult> update = workers.submit(() -> {
            start.await();
            return userPermissionService.updatePermission(userId, retainedId, Map.of("canDelete", true));
        });
        Future<AdminAccountService.MutationResult> promotion = workers.submit(() -> {
            start.await();
            return adminAccountService.promoteAdmin(userId);
        });
        start.countDown();

        assertThat(deletion.get(10, TimeUnit.SECONDS).status()).isEqualTo(MutationStatus.SUCCESS);
        assertThat(update.get(10, TimeUnit.SECONDS).status()).isEqualTo(MutationStatus.SUCCESS);
        assertThat(promotion.get(10, TimeUnit.SECONDS).status())
                .isEqualTo(AdminAccountService.MutationStatus.SUCCESS);

        User reloaded = userRepository.findByIdWithPermissions(userId).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo("ADMIN");
        assertThat(reloaded.getPermissions())
                .extracting(FolderPermission::getPath)
                .containsExactly("/retained");
        assertThat(reloaded.getPermissions().get(0).isCanDelete()).isTrue();
    }
}
