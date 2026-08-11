package com.aspera.web.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserPermissionFetchPlanTest {

    @Autowired
    private UserRepository userRepository;

    private long userId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User user = new User("fetch-plan-user", "encoded-password", "USER");
        user.addPermission(new FolderPermission("/team", false, true, false, false));
        userId = userRepository.saveAndFlush(user).getId();
    }

    @Test
    void dashboardPageLeavesPermissionsLazy() {
        User pageUser = userRepository.findAll(PageRequest.of(0, 10)).getContent().get(0);

        assertThat(Hibernate.isInitialized(pageUser.getPermissions())).isFalse();
    }

    @Test
    void usernameAndPermissionDetailQueriesFetchPermissionsExplicitly() {
        User byUsername = userRepository.findByUsername("fetch-plan-user").orElseThrow();
        User byId = userRepository.findByIdWithPermissions(userId).orElseThrow();

        assertThat(Hibernate.isInitialized(byUsername.getPermissions())).isTrue();
        assertThat(byUsername.getPermissions()).hasSize(1);
        assertThat(Hibernate.isInitialized(byId.getPermissions())).isTrue();
        assertThat(byId.getPermissions()).hasSize(1);
    }
}
