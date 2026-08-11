package com.aspera.web.controller;

import com.aspera.web.entity.User;
import com.aspera.web.entity.FolderPermission;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.security.JsonRequestSizeLimitFilter;
import com.aspera.web.security.NodePathPolicy;
import com.aspera.web.service.AsperaNodeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

@Controller
@RequestMapping("/files")
public class FileOperationsController {

    private final AsperaNodeService asperaNodeService;
    private final UserRepository userRepository;
    private static final String TRANSFER_COOKIE_PREFIX = "aspera.shares2";
    private static final int MAX_TRANSFER_PATHS = 100;
    // JSON may need two bytes for each one-byte quote in a valid path. Keeping the
    // semantic path payload at 500 KiB leaves 24 KiB for the JSON envelope while
    // remaining below the 1 MiB request filter even in that worst escaping case.
    static final int MAX_TRANSFER_PATH_UTF8_BYTES =
            (JsonRequestSizeLimitFilter.MAX_JSON_REQUEST_BYTES - (24 * 1024)) / 2;

    public FileOperationsController(AsperaNodeService asperaNodeService, UserRepository userRepository) {
        this.asperaNodeService = asperaNodeService;
        this.userRepository = userRepository;
    }

    // 권한 확인 로직 (Data Flow Validation)
    // 1. 입력: 현재 사용자(Principal)와 접근하려는 경로(path), 수행하려는 동작(action)을 받음
    // 2. 검증:
    // - 사용자가 관리자(ADMIN)라면 무조건 true를 반환
    // - 일반 사용자라면, 할당된 권한 목록(Permissions)을 순회하며 요청 경로가 권한 경로에 포함되는지(startsWith) 확인
    // - 경로가 일치하면 해당 권한 객체에서 요청 동작(UPLOAD, DELETE 등)이 허용되어 있는지 확인
    // 3. 반환: 권한이 있으면 true, 없으면 false를 반환
    private boolean checkPermission(Principal principal, String path, String action) {
        if (principal == null) {
            return false;
        }

        User user = userRepository.findByUsername(principal.getName()).orElse(null);
        return checkPermission(user, path, action);
    }

    private boolean checkPermission(User user, String path, String action) {
        if (user == null) {
            return false;
        }

        if ("ADMIN".equals(user.getRole())) {
            return true;
        }

        if (user.getPermissions() == null) {
            return false;
        }

        for (FolderPermission perm : user.getPermissions()) {
            boolean pathAllowed;
            try {
                pathAllowed = perm != null && NodePathPolicy.isSameOrDescendant(path, perm.getPath());
            } catch (IllegalArgumentException ex) {
                pathAllowed = false;
            }
            if (pathAllowed) {
                switch (action) {
                    case "UPLOAD":
                        if (perm.isCanUpload())
                            return true;
                        break;
                    case "DOWNLOAD":
                        if (perm.isCanDownload())
                            return true;
                        break;
                    case "DELETE":
                        if (perm.isCanDelete())
                            return true;
                        break;
                    case "CREATE_FOLDER":
                        if (perm.isCanCreateFolder())
                            return true;
                        break;
                }
            }
        }
        return false;
    }

    @PostMapping("/delete")
    public String deleteFile(@RequestParam("path") String path,
            Principal principal,
            RedirectAttributes redirectAttributes) {
        final String normalizedPath;
        try {
            normalizedPath = NodePathPolicy.normalizeAbsolutePath(path);
            if ("/".equals(normalizedPath)) {
                throw new IllegalArgumentException("The Node root cannot be deleted.");
            }
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/files?path=%2F";
        }

        String parentPath = "/";
        int lastSlash = normalizedPath.lastIndexOf('/');
        if (lastSlash > 0) {
            parentPath = normalizedPath.substring(0, lastSlash);
        }

        if (!checkPermission(principal, normalizedPath, "DELETE")) {
            redirectAttributes.addFlashAttribute("error", "Permission denied: delete is not allowed for this path.");
            return "redirect:/files?path=" + URLEncoder.encode(parentPath, StandardCharsets.UTF_8);
        }

        try {
            AsperaNodeService.FileItem deletedItem = asperaNodeService.deleteFile(normalizedPath);
            String msg = String.format("Successfully deleted %s: %s (%s)",
                    deletedItem.type(), deletedItem.name(), formatSize(deletedItem.size()));
            redirectAttributes.addFlashAttribute("message", msg);

        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/files?path=" + URLEncoder.encode(parentPath, StandardCharsets.UTF_8);
    }

    // 폴더 생성 처리 (Data Flow Action)
    // 1. 요청: '/files/create-folder'로 POST 요청이 들어옴 (부모 경로 parentPath, 새 폴더명
    // folderName)
    // 2. 권한 검사: 새로 생성될 폴더 경로에 대해 'CREATE_FOLDER' 권한이 있는지 확인
    // 3. 실행: AsperaNodeService.createFolder()를 호출하여 Node API에 폴더 생성을 요청
    // 4. 이동: 성공/실패 메시지와 함께 다시 파일 목록 페이지('/files')로 리다이렉트
    @PostMapping("/create-folder")
    public String createFolder(@RequestParam("parentPath") String parentPath,
            @RequestParam("folderName") String folderName,
            Principal principal,
            RedirectAttributes redirectAttributes) {

        final String normalizedParent;
        final String normalizedFolderName;
        final String newFolderPath;
        try {
            normalizedParent = NodePathPolicy.normalizeAbsolutePath(parentPath);
            newFolderPath = NodePathPolicy.join(normalizedParent, folderName);
            normalizedFolderName = newFolderPath.substring(newFolderPath.lastIndexOf('/') + 1);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/files?path=%2F";
        }

        if (!checkPermission(principal, newFolderPath, "CREATE_FOLDER")) {
            redirectAttributes.addFlashAttribute("error", "Permission denied: folder creation is not allowed here.");
            return "redirect:/files?path=" + URLEncoder.encode(normalizedParent, StandardCharsets.UTF_8);
        }

        // AsperaNodeService를 통해 실제 폴더 생성 로직 호출
        // 만약 서비스에 해당 메소드가 없다면 구현이 필요함
        // 현재 로직은 API 호출을 통해 Aspera Node에 폴더 생성을 요청하는 흐름
        try {
            asperaNodeService.createFolder(normalizedParent, normalizedFolderName);
            redirectAttributes.addFlashAttribute("message", "Folder created successfully.");
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/files?path=" + URLEncoder.encode(normalizedParent, StandardCharsets.UTF_8);
    }

    private String formatSize(long size) {
        if (size < 1024)
            return size + " B";
        if (size < 1048576)
            return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / 1048576.0);
    }

    @PostMapping(value = "/transfer-spec", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getTransferSpec(@RequestBody TransferSpecRequest request, Principal principal) {
        if (request == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Transfer request is required."));
        }
        String direction = request.direction();

        if (!"send".equals(direction) && !"receive".equals(direction)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid transfer direction."));
        }

        String path = request.path();
        java.util.List<String> paths = request.paths();
        if (path != null && paths != null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Provide either path or paths, not both."));
        }

        java.util.List<String> requestedPaths = new java.util.ArrayList<>();
        if (paths != null) {
            requestedPaths.addAll(paths);
        } else if (path != null) {
            requestedPaths.add(path);
        } else {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "No path or paths provided"));
        }

        if (requestedPaths.size() > MAX_TRANSFER_PATHS || ("send".equals(direction) && requestedPaths.size() != 1)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid number of transfer paths."));
        }

        final java.util.List<String> targetPaths;
        try {
            java.util.LinkedHashSet<String> normalizedPaths = new java.util.LinkedHashSet<>();
            int totalPathBytes = 0;
            for (String requestedPath : requestedPaths) {
                String normalizedPath = NodePathPolicy.normalizeAbsolutePath(requestedPath);
                int pathBytes = normalizedPath.getBytes(StandardCharsets.UTF_8).length;
                if (pathBytes > MAX_TRANSFER_PATH_UTF8_BYTES - totalPathBytes) {
                    return ResponseEntity.badRequest()
                            .body(java.util.Map.of("error", "Combined transfer paths are too large."));
                }
                totalPathBytes += pathBytes;
                normalizedPaths.add(normalizedPath);
            }
            targetPaths = java.util.List.copyOf(normalizedPaths);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
        if (targetPaths.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "No valid path was provided."));
        }

        User currentUser = null;
        if (principal != null) {
            currentUser = userRepository.findByUsername(principal.getName()).orElse(null);
        }

        String action = "send".equals(direction) ? "UPLOAD" : "DOWNLOAD";

        // 요청된 모든 경로에 대해 권한 확인 (하나라도 권한이 없으면 거부)
        for (String p : targetPaths) {
            if (!checkPermission(currentUser, p, action)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(java.util.Map.of("error", "Permission Denied for " + p));
            }
        }

        java.util.Map<String, Object> spec = asperaNodeService.generateMultiFileTransferSpec(direction, targetPaths);
        applyTransferMetadata(spec, currentUser, principal, direction, targetPaths);
        if (spec == null || spec.containsKey("error")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(spec == null ? java.util.Map.of("error", "Transfer service returned no response.") : spec);
        }
        return ResponseEntity.ok(spec);
    }

    record TransferSpecRequest(String direction, String path, java.util.List<String> paths) {
    }

    private void applyTransferMetadata(java.util.Map<String, Object> spec, User user, Principal principal,
            String direction, java.util.List<String> targetPaths) {
        if (spec == null || spec.isEmpty() || spec.containsKey("error")) {
            return;
        }

        String username = "unknown";
        if (user != null) {
            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                username = user.getUsername();
            }
        } else if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            username = principal.getName();
        }

        String transferLabel = buildTransferLabel(direction, targetPaths);
        String cookie = TRANSFER_COOKIE_PREFIX + ":" + java.util.UUID.randomUUID()
                + ":" + base64Value(username) + ":" + base64Value(transferLabel);
        spec.put("cookie", cookie);

        // Test: temporarily skip setting transfer-spec tags.
        // java.util.Map<String, Object> tags = new java.util.HashMap<>();
        // Object existingTags = spec.get("tags");
        // mergeTagMap(tags, existingTags);
        //
        // java.util.Map<String, Object> asperaTags = new java.util.HashMap<>();
        // Object existingAspera = tags.get("aspera");
        // mergeTagMap(asperaTags, existingAspera);
        //
        // asperaTags.put("app", TRANSFER_APP_ID);
        // asperaTags.put("app_label", TRANSFER_APP_LABEL);
        // asperaTags.put("user_id", userId);
        // asperaTags.put("username", username);
        // tags.put("aspera", asperaTags);
        // spec.put("tags", tags);
    }

    private String buildTransferLabel(String direction, java.util.List<String> targetPaths) {
        boolean isSend = "send".equalsIgnoreCase(direction);
        if (targetPaths != null && targetPaths.size() > 1) {
            return isSend ? "upload multiple items" : "download multiple items";
        }

        String targetName = "";
        if (targetPaths != null && !targetPaths.isEmpty()) {
            String raw = targetPaths.get(0);
            if (raw != null) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    if (trimmed.endsWith("/")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                    int lastSlash = trimmed.lastIndexOf('/');
                    targetName = (lastSlash >= 0 && lastSlash + 1 < trimmed.length())
                            ? trimmed.substring(lastSlash + 1)
                            : trimmed;
                }
            }
        }

        if (targetName.isBlank()) {
            return isSend ? "upload via aspera web" : "download via aspera web";
        }
        return isSend ? "upload to " + targetName : "download from " + targetName;
    }

    private String base64Value(String value) {
        if (value == null) {
            return "";
        }
        return java.util.Base64.getEncoder()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

}
