package com.aspera.web.controller;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.service.AdminAccountService;
import com.aspera.web.service.AdminAccountService.MutationResult;
import com.aspera.web.service.UserPermissionService;
import com.aspera.web.service.UserSessionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/admin")
public class AdminController {

    static final int MAX_DASHBOARD_PAGE = 10_000;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAccountService adminAccountService;
    private final UserSessionService userSessionService;
    private final UserPermissionService userPermissionService;

    public AdminController(UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AdminAccountService adminAccountService,
            UserSessionService userSessionService,
            UserPermissionService userPermissionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminAccountService = adminAccountService;
        this.userSessionService = userSessionService;
        this.userPermissionService = userPermissionService;
    }

    // 관리자 대시보드 페이지 요청 처리 (Data Flow View)
    // 1. 요청: '/admin/dashboard'로 GET 요청이 들어옵니다. (파라미터: page, size)
    // 2. 처리:
    // - PageRequest 객체를 생성하여 페이징 정보를 설정합니다.
    // - UserRepository.findAll(pageable)을 호출하여 DB에서 해당 페이지의 사용자 목록을 조회합니다.
    // 3. 반환: 조회된 사용자 목록(Page<User>)과 페이징 메타데이터를 Model에 담아 'admin-dashboard' 뷰로
    // 전달합니다.
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "size", required = false, defaultValue = "10") int size,
            Model model, Principal principal) {

        // 페이지 번호가 1보다 작으면 1로 설정하여 오류 방지
        if (page < 1) {
            page = 1;
        }
        size = Math.max(1, Math.min(size, 100));

        Page<User> userPage;
        if (page > MAX_DASHBOARD_PAGE) {
            // 극단적인 외부 입력은 안전한 첫 페이지에서 실제 마지막 페이지를
            // 확인한 뒤 이동하여 JPA에 과도하거나 넘치는 offset을 전달하지 않는다.
            userPage = userRepository.findAll(PageRequest.of(0, size));
            int lastSafePage = Math.min(userPage.getTotalPages(), MAX_DASHBOARD_PAGE);
            page = Math.max(1, lastSafePage);
            if (page > 1) {
                userPage = userRepository.findAll(PageRequest.of(page - 1, size));
            }
        } else {
            userPage = userRepository.findAll(PageRequest.of(page - 1, size));
            int lastSafePage = Math.min(userPage.getTotalPages(), MAX_DASHBOARD_PAGE);
            if (lastSafePage == 0) {
                page = 1;
            } else if (page > lastSafePage) {
                page = lastSafePage;
                userPage = userRepository.findAll(PageRequest.of(page - 1, size));
            }
        }

        int totalPages = Math.min(userPage.getTotalPages(), MAX_DASHBOARD_PAGE);

        // 템플릿 렌더링을 위해 데이터를 모델에 추가
        model.addAttribute("users", userPage.getContent()); // 현재 페이지의 사용자 목록
        model.addAttribute("currentPage", page); // 현재 페이지 번호
        model.addAttribute("totalPages", totalPages); // 전체 페이지 수
        model.addAttribute("totalItems", userPage.getTotalElements()); // 전체 사용자 수
        model.addAttribute("pageSize", size); // 페이지 당 항목 수
        model.addAttribute("pageNumbers", buildPageNumbers(page, totalPages));
        model.addAttribute("currentUsername", principal == null ? "" : principal.getName());

        return "admin-dashboard";
    }

    @GetMapping("/users/new")
    public String createUserPage() {
        return "user-create";
    }

    // 사용자 생성 처리 (Data Flow Action)
    // 1. 요청: 사용자 생성 폼에서 입력된 값(username, email, password, role)이 POST로 전송됩니다.
    // 2. 검증: UserRepository.findByUsername()으로 중복된 ID가 있는지 확인합니다.
    // 3. 저장: 중복이 없다면 User 엔티티를 생성하고, 비밀번호를 암호화(BCrypt)한 뒤 UserRepository.save()로
    // DB에 저장합니다.
    // 4. 이동: 성공/실패 메시지(FlashAttribute)와 함께 '/admin/dashboard'로 리다이렉트합니다.
    @PostMapping("/users/create")
    public String createUser(@RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam("role") String role,
            RedirectAttributes attrs) {

        username = username == null ? "" : username.trim();
        email = email == null ? "" : email.trim();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            return redirectWithError(attrs,
                    "Username must be 3-64 characters and use letters, numbers, '.', '_' or '-'.");
        }
        if (!isValidEmail(email)) {
            return redirectWithError(attrs, "Enter a valid email address.");
        }
        int passwordBytes = password == null ? 0 : password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (password == null || password.length() < 12 || passwordBytes > 72) {
            return redirectWithError(attrs,
                    "Password must be at least 12 characters and no more than 72 UTF-8 bytes.");
        }
        if (!Set.of("USER", "ADMIN").contains(role)) {
            return redirectWithError(attrs, "Invalid user role.");
        }

        // 이미 존재하는 사용자명인지 확인
        if (userRepository.existsByUsername(username)) {
            attrs.addAttribute("error", "Username already exists.");
            return "redirect:/admin/dashboard"; // 중복 시 대시보드로 리다이렉트
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            return redirectWithError(attrs, "Username already exists.");
        }

        attrs.addFlashAttribute("message", "User " + username + " created successfully.");
        return "redirect:/admin/dashboard";
    }

    // 사용자 삭제 처리 (Data Flow Action)
    // 1. 요청: '/admin/users/delete'로 POST 요청이 전달됩니다. (삭제할 사용자 id)
    // 2. 실행: UserRepository.deleteById(id)를 호출하여 DB에서 해당 사용자를 물리적으로 삭제합니다.
    // 3. 이동: 성공 메시지와 함께 대시보드로 리다이렉트합니다.
    @PostMapping("/users/delete")
    public String deleteUser(@RequestParam("id") long id, RedirectAttributes attrs, Principal principal) {
        MutationResult result = adminAccountService.deleteUser(id, principal == null ? null : principal.getName());
        return switch (result.status()) {
            case SUCCESS -> {
                userSessionService.expireAllSessions(result.username());
                attrs.addFlashAttribute("message", "User deleted successfully.");
                yield "redirect:/admin/dashboard";
            }
            case NOT_FOUND -> redirectWithError(attrs, "User not found.");
            case SELF_ACTION_DENIED -> redirectWithError(attrs,
                    "Administrators cannot delete their own account.");
            case LAST_ADMIN_DENIED -> redirectWithError(attrs,
                    "The final administrator account cannot be deleted.");
            case NO_CHANGE -> "redirect:/admin/dashboard";
        };
    }

    // 관리자 권한 해제 (Data Flow Action)
    // 1. 요청: '/admin/users/revoke-admin'으로 POST 요청이 전달됩니다. (대상 사용자 id)
    // 2. 검증: DB에서 사용자를 조회하고, 현재 역할이 'ADMIN'인지 확인합니다.
    // 3. 실행: 역할을 'USER'로 변경하고 DB에 저장(Update)합니다.
    // 4. 이동: 처리 결과 메시지와 함께 대시보드로 리다이렉트합니다.
    @PostMapping("/users/revoke-admin")
    public String revokeAdmin(@RequestParam("id") long id, RedirectAttributes attrs, Principal principal) {
        MutationResult result = adminAccountService.revokeAdmin(id, principal == null ? null : principal.getName());
        return switch (result.status()) {
            case SUCCESS -> {
                userSessionService.expireAllSessions(result.username());
                attrs.addFlashAttribute("message", "Admin privileges revoked for " + result.username());
                yield "redirect:/admin/dashboard";
            }
            case NOT_FOUND -> redirectWithError(attrs, "User not found.");
            case SELF_ACTION_DENIED -> redirectWithError(attrs,
                    "Administrators cannot revoke their own administrator role.");
            case LAST_ADMIN_DENIED -> redirectWithError(attrs,
                    "The final administrator role cannot be revoked.");
            case NO_CHANGE -> "redirect:/admin/dashboard";
        };
    }

    @PostMapping("/users/promote-admin")
    public String promoteAdmin(@RequestParam("id") long id, RedirectAttributes attrs) {
        MutationResult result = adminAccountService.promoteAdmin(id);
        return switch (result.status()) {
            case SUCCESS -> {
                attrs.addFlashAttribute("message", "Admin privileges granted to " + result.username());
                yield "redirect:/admin/dashboard";
            }
            case NOT_FOUND -> redirectWithError(attrs, "User not found.");
            default -> "redirect:/admin/dashboard";
        };
    }

    // Permission Management
    // 권한 관리 페이지 요청 (Data Flow View)
    // 1. 요청: '/admin/users/{id}/permissions'로 GET 요청이 들어옴 (사용자 id)
    // 2. 조회: 권한 목록을 명시적으로 함께 가져오는 전용 조회로 대상을 조회
    // 3. 반환: 사용자 객체(User)를 Model에 담아 'user-permissions' 뷰를 렌더링
    // 뷰에서는 user.permissions 리스트를 순회하며 현재 권한 목록을 표시
    @GetMapping("/users/{id}/permissions")
    public String managePermissions(@PathVariable("id") long id, Model model, RedirectAttributes attrs) {
        User user = userRepository.findByIdWithPermissions(id).orElse(null);
        if (user == null) {
            return redirectWithError(attrs, "User not found.");
        }
        model.addAttribute("user", user);
        return "user-permissions";
    }

    // 권한 추가 처리 (Data Flow Action)
    // 1. 요청: '/admin/users/{id}/permissions'로 POST 요청이 들어옵니다. (대상 사용자 id, 추가할 경로 목록
    // paths, 권한 플래그들)
    // 2. 처리:
    // - 트랜잭션 서비스가 대상 사용자 행을 잠그고 최신 권한 목록을 조회
    // - 요청된 paths 리스트를 순회하며 각 경로마다 새로운 FolderPermission 객체를 생성
    // - 생성된 권한 객체를 사용자의 권한 목록에 추가
    // 3. 저장: 잠금 트랜잭션 안에서 관리되는 권한 목록 변경을 DB에 반영
    // 4. 이동: 성공 메시지와 함께 다시 권한 관리 페이지로 리다이렉트
    @PostMapping("/users/{id}/permissions")
    public String addPermission(@PathVariable("id") long id,
            @RequestParam(value = "paths", required = false) List<String> paths,
            @RequestParam(name = "canUpload", required = false) boolean canUpload,
            @RequestParam(name = "canDownload", required = false) boolean canDownload,
            @RequestParam(name = "canCreateFolder", required = false) boolean canCreateFolder,
            @RequestParam(name = "canDelete", required = false) boolean canDelete,
            RedirectAttributes attrs) {
        UserPermissionService.MutationResult result = userPermissionService.addPermissions(
                id, paths, canUpload, canDownload, canCreateFolder, canDelete);
        return switch (result.status()) {
            case SUCCESS -> {
                attrs.addFlashAttribute("message", result.affectedCount() + " permissions added successfully.");
                yield "redirect:/admin/users/" + id + "/permissions";
            }
            case USER_NOT_FOUND -> {
                attrs.addFlashAttribute("error", "User not found.");
                yield "redirect:/admin/dashboard";
            }
            case PATHS_REQUIRED -> permissionError(attrs, id, "Select at least one folder.");
            case CAPABILITY_REQUIRED -> permissionError(attrs, id, "Select at least one capability.");
            case TOO_MANY_PATHS -> permissionError(attrs, id,
                    "No more than 100 folders can be added at once.");
            case INVALID_PATH -> permissionError(attrs, id, result.detail());
            case DUPLICATE_PATHS -> permissionError(attrs, id,
                    "The selected folder permissions already exist.");
            default -> permissionError(attrs, id, "The permission request is invalid.");
        };
    }

    // 권한 수정 처리 (Data Flow API - AJAX)
    // 1. 요청: '/admin/users/{id}/permissions/{permissionId}'로 PUT 요청이 들어옵니다. (JSON
    // Body: 수정할 권한 필드)
    // 2. 조회: 트랜잭션 서비스가 사용자 행을 잠그고 permissionId에 해당하는 최신 권한을 찾음
    // 3. 수정: 요청 Body에 포함된 필드(canUpload, canDownload 등)만 선택적으로 업데이트
    // 4. 저장: 같은 잠금 트랜잭션 안에서 변경 사항을 DB에 반영
    // 5. 반환: 성공 시 200 OK를 반환 (클라이언트 JS는 이를 확인하고 UI를 업데이트하지 않거나 성공 표시)
    @PutMapping("/users/{id}/permissions/{permissionId}")
    @ResponseBody
    public ResponseEntity<?> updatePermission(
            @PathVariable("id") long userId,
            @PathVariable("permissionId") Long permissionId,
            @RequestBody Map<String, Boolean> updates) {
        UserPermissionService.MutationResult result =
                userPermissionService.updatePermission(userId, permissionId, updates);
        return switch (result.status()) {
            case SUCCESS -> ResponseEntity.ok().build();
            case USER_NOT_FOUND, PERMISSION_NOT_FOUND -> ResponseEntity.notFound().build();
            case UPDATES_REQUIRED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Permission updates are required."));
            case INVALID_UPDATES -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Permission update contains an invalid field."));
            default -> ResponseEntity.badRequest().body(Map.of("error", "The permission request is invalid."));
        };
    }

    // 권한 삭제 처리 (Data Flow Action)
    // 1. 요청: '/admin/users/{id}/permissions/delete'로 POST 요청이 들어옴 (삭제할 권한
    // permissionId)
    // 2. 실행: 사용자 행을 잠근 트랜잭션에서 해당 permissionId를 가진 최신 권한을 제거
    // 3. 이동: 성공 메시지와 함께 권한 관리 페이지로 리다이렉트
    @PostMapping("/users/{id}/permissions/delete")
    public String deletePermission(@PathVariable("id") long id, @RequestParam("permissionId") Long permissionId,
            RedirectAttributes attrs) {
        UserPermissionService.MutationResult result = userPermissionService.deletePermission(id, permissionId);
        return switch (result.status()) {
            case SUCCESS -> {
                attrs.addFlashAttribute("message", "Permission removed.");
                yield "redirect:/admin/users/" + id + "/permissions";
            }
            case USER_NOT_FOUND -> redirectWithError(attrs, "User not found.");
            case PERMISSION_NOT_FOUND -> permissionError(attrs, id, "Permission not found.");
            default -> permissionError(attrs, id, "The permission request is invalid.");
        };
    }

    private String redirectWithError(RedirectAttributes attrs, String message) {
        attrs.addAttribute("error", message);
        return "redirect:/admin/dashboard";
    }

    private String permissionError(RedirectAttributes attrs, long userId, String message) {
        attrs.addFlashAttribute("error", message);
        return "redirect:/admin/users/" + userId + "/permissions";
    }

    private boolean isValidEmail(String email) {
        return email != null && email.length() <= 254
                && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private List<Integer> buildPageNumbers(int currentPage, int totalPages) {
        if (totalPages < 1) {
            return List.of();
        }
        java.util.SortedSet<Integer> pages = new java.util.TreeSet<>();
        pages.add(1);
        pages.add(totalPages);
        int start = Math.max(1, currentPage - 2);
        int end = Math.min(totalPages, currentPage + 2);
        for (int page = start; page <= end; page++) {
            pages.add(page);
        }
        return List.copyOf(pages);
    }

}
