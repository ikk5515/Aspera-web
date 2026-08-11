package com.aspera.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aspera.web.service.UserSessionService;
import com.aspera.web.security.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockHttpSession;

@SpringJUnitConfig
@WebAppConfiguration
@ContextConfiguration(classes = SecuritySessionExpiryIntegrationTest.TestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecuritySessionExpiryIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionRegistry sessionRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void expiredRegisteredSessionCannotContinueUsingAdminAuthority() throws Exception {
        MockHttpSession session = login();
        assertThat(sessionRegistry.getSessionInformation(session.getId())).isNotNull();

        new UserSessionService(sessionRegistry).expireAllSessions("target-admin");

        mockMvc.perform(get("/admin/probe").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void expiredApiSessionReceivesJsonUnauthorizedResponse() throws Exception {
        MockHttpSession session = login();
        new UserSessionService(sessionRegistry).expireAllSessions("target-admin");

        mockMvc.perform(get("/admin/api/probe").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Session expired. Sign in again."));
    }

    @Test
    void expiredPermissionUpdateSessionReceivesJsonUnauthorizedResponse() throws Exception {
        MockHttpSession session = login();
        new UserSessionService(sessionRegistry).expireAllSessions("target-admin");

        mockMvc.perform(put("/admin/users/9/permissions/12")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"canUpload\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Session expired. Sign in again."));
    }

    @Test
    void repeatedLoginFailuresAreRateLimitedBeforeAuthentication() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.10");
                                return request;
                            })
                            .param("username", "target-admin")
                            .param("password", "incorrect-password"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"));
        }

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .param("username", "target-admin")
                        .param("password", "REPLACE_TEST_PASSWORD"))
                .andExpect(status().isTooManyRequests());
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "target-admin")
                        .param("password", "REPLACE_TEST_PASSWORD"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Configuration
    @EnableWebMvc
    @Import({ SecurityConfig.class, LoginAttemptService.class })
    static class TestConfiguration {

        @Bean
        UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
            return new InMemoryUserDetailsManager(User.withUsername("target-admin")
                    .password(passwordEncoder.encode("REPLACE_TEST_PASSWORD"))
                    .roles("ADMIN")
                    .build());
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping({ "/admin/probe", "/admin/api/probe" })
        String probe() {
            return "ok";
        }

        @PutMapping("/admin/users/{userId}/permissions/{permissionId}")
        String updatePermissionProbe() {
            return "ok";
        }
    }
}
