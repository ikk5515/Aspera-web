package com.aspera.web.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class FolderPermissionPersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void persistsPathAtNodePolicyMaximumLength() {
        String path = "/" + "a".repeat(2047);
        User user = new User("long-path-user", "encoded-password", "USER");
        user.addPermission(new FolderPermission(path, true, true, false, false));

        User saved = userRepository.saveAndFlush(user);
        User reloaded = userRepository.findByIdWithPermissions(saved.getId()).orElseThrow();

        assertThat(reloaded.getPermissions()).singleElement()
                .extracting(FolderPermission::getPath)
                .isEqualTo(path);
    }
}
