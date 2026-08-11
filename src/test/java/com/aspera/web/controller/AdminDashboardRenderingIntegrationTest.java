package com.aspera.web.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard_render;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.bootstrap-admin.enabled=false"
})
@AutoConfigureMockMvc
class AdminDashboardRenderingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User admin = new User("render-admin", passwordEncoder.encode("REPLACE_TEST_PASSWORD"), "ADMIN");
        admin.setEmail("render@example.invalid");
        userRepository.save(admin);
    }

    @Test
    void dashboardRendersWithoutRemovedSecurityExpressionObjects() throws Exception {
        mockMvc.perform(get("/admin/dashboard")
                        .with(user("render-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-dashboard"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("render-admin")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Something went wrong"))));
    }

    @Test
    void permissionPageRendersExplicitlyFetchedLazyPermissions() throws Exception {
        User targetUser = new User("permission-target", passwordEncoder.encode("REPLACE_TEST_PASSWORD"), "USER");
        targetUser.setEmail("target@example.invalid");
        targetUser.addPermission(new FolderPermission("/team", false, true, false, false));
        targetUser = userRepository.saveAndFlush(targetUser);

        mockMvc.perform(get("/admin/users/{id}/permissions", targetUser.getId())
                        .with(user("render-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("user-permissions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/team")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Something went wrong"))));
    }
}
