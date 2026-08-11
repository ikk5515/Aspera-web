package com.aspera.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.service.AdminAccountService;
import com.aspera.web.service.AdminAccountService.MutationResult;
import com.aspera.web.service.AdminAccountService.MutationStatus;
import com.aspera.web.service.UserPermissionService;
import com.aspera.web.service.UserSessionService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

@ExtendWith(MockitoExtension.class)
class AdminControllerSecurityMutationTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AdminAccountService adminAccountService;
    @Mock
    private UserSessionService userSessionService;
    @Mock
    private UserPermissionService userPermissionService;

    private AdminController controller;
    private Principal actor;

    @BeforeEach
    void setUp() {
        controller = new AdminController(
                userRepository, passwordEncoder, adminAccountService, userSessionService, userPermissionService);
        actor = () -> "acting-admin";
    }

    @Test
    void successfulDeleteExpiresExistingTargetSessions() {
        when(adminAccountService.deleteUser(7L, "acting-admin"))
                .thenReturn(new MutationResult(MutationStatus.SUCCESS, "target-admin"));

        String view = controller.deleteUser(7L, new RedirectAttributesModelMap(), actor);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
        verify(userSessionService).expireAllSessions("target-admin");
    }

    @Test
    void successfulDemotionExpiresExistingAdminSessions() {
        when(adminAccountService.revokeAdmin(8L, "acting-admin"))
                .thenReturn(new MutationResult(MutationStatus.SUCCESS, "target-admin"));

        controller.revokeAdmin(8L, new RedirectAttributesModelMap(), actor);

        verify(userSessionService).expireAllSessions("target-admin");
    }

    @Test
    void promotionUsesTransactionalAccountService() {
        when(adminAccountService.promoteAdmin(10L))
                .thenReturn(new MutationResult(MutationStatus.SUCCESS, "target-user"));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        String view = controller.promoteAdmin(10L, attributes);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
        assertThat(attributes.getFlashAttributes().get("message"))
                .isEqualTo("Admin privileges granted to target-user");
        verify(adminAccountService).promoteAdmin(10L);
    }

    @Test
    void dashboardClampsExtremePageBeforeCreatingJpaOffset() {
        User user = new User("listed-user", "encoded", "USER");
        PageRequest firstPageRequest = PageRequest.of(0, 100);
        PageRequest lastPageRequest = PageRequest.of(2, 100);
        when(userRepository.findAll(firstPageRequest))
                .thenReturn(new PageImpl<>(List.of(user), firstPageRequest, 250));
        when(userRepository.findAll(lastPageRequest))
                .thenReturn(new PageImpl<>(List.of(user), lastPageRequest, 250));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.dashboard(Integer.MAX_VALUE, 100, model, actor);

        assertThat(view).isEqualTo("admin-dashboard");
        assertThat(model.get("currentPage")).isEqualTo(3);
        assertThat(model.get("totalPages")).isEqualTo(3);
        assertThat(model.get("pageNumbers")).isEqualTo(List.of(1, 2, 3));
        assertThat(model.get("currentUsername")).isEqualTo("acting-admin");
        verify(userRepository).findAll(firstPageRequest);
        verify(userRepository).findAll(lastPageRequest);
        verify(userRepository, never()).findAll(PageRequest.of(Integer.MAX_VALUE - 1, 100));
    }

    @Test
    void dashboardBuildsOnlyBoundedPageNumberLinks() {
        User user = new User("listed-user", "encoded", "USER");
        PageRequest requestedPage = PageRequest.of(4_999, 100);
        when(userRepository.findAll(requestedPage))
                .thenReturn(new PageImpl<>(List.of(user), requestedPage, 1_000_000));
        ExtendedModelMap model = new ExtendedModelMap();

        controller.dashboard(5_000, 100, model, actor);

        assertThat(model.get("pageNumbers"))
                .isEqualTo(List.of(1, 4_998, 4_999, 5_000, 5_001, 5_002, 10_000));
    }

    @Test
    void createUserRequiresTwelveCharactersNotOnlyTwelveUtf8Bytes() {
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        String view = controller.createUser(
                "valid-user", "user@example.com", "가나다라", "USER", attributes);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
        assertThat(attributes.get("error"))
                .isEqualTo("Password must be at least 12 characters and no more than 72 UTF-8 bytes.");
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lastAdministratorDenialDoesNotExpireSession() {
        when(adminAccountService.revokeAdmin(8L, "acting-admin"))
                .thenReturn(new MutationResult(MutationStatus.LAST_ADMIN_DENIED, null));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        String view = controller.revokeAdmin(8L, attributes, actor);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
        assertThat(attributes.get("error")).isEqualTo("The final administrator role cannot be revoked.");
        verify(userSessionService, never()).expireAllSessions(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void addPermissionKeepsExistingValidationRedirectAndMessage() {
        when(userPermissionService.addPermissions(
                9L, List.of("/one"), false, false, false, false))
                .thenReturn(new UserPermissionService.MutationResult(
                        UserPermissionService.MutationStatus.CAPABILITY_REQUIRED, 0, null));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        String view = controller.addPermission(
                9L, List.of("/one"), false, false, false, false, attributes);

        assertThat(view).isEqualTo("redirect:/admin/users/9/permissions");
        assertThat(attributes.getFlashAttributes().get("error"))
                .isEqualTo("Select at least one capability.");
    }

    @Test
    void updatePermissionKeepsExistingBadRequestBody() {
        Map<String, Boolean> updates = Map.of();
        when(userPermissionService.updatePermission(9L, 12L, updates))
                .thenReturn(new UserPermissionService.MutationResult(
                        UserPermissionService.MutationStatus.UPDATES_REQUIRED, 0, null));

        org.springframework.http.ResponseEntity<?> response =
                controller.updatePermission(9L, 12L, updates);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .isEqualTo(Map.of("error", "Permission updates are required."));
    }

    @Test
    void deletePermissionKeepsExistingNotFoundRedirectAndMessage() {
        when(userPermissionService.deletePermission(9L, 12L))
                .thenReturn(new UserPermissionService.MutationResult(
                        UserPermissionService.MutationStatus.PERMISSION_NOT_FOUND, 0, null));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        String view = controller.deletePermission(9L, 12L, attributes);

        assertThat(view).isEqualTo("redirect:/admin/users/9/permissions");
        assertThat(attributes.getFlashAttributes().get("error")).isEqualTo("Permission not found.");
    }
}
