package com.aspera.web.controller;

import java.util.List;

import com.aspera.web.entity.User;
import com.aspera.web.security.NodePathPolicy;
import com.aspera.web.service.AsperaNodeService;
import com.aspera.web.service.AsperaNodeService.NodeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class FileBrowserController {

    private final AsperaNodeService asperaNodeService;
    private final com.aspera.web.repository.UserRepository userRepository;

    public FileBrowserController(AsperaNodeService asperaNodeService,
            com.aspera.web.repository.UserRepository userRepository) {
        this.asperaNodeService = asperaNodeService;
        this.userRepository = userRepository;
    }

    // 파일 브라우저 페이지 요청 처리 (Data Flow View)
    // 1. 요청: '/files'로 GET 요청이 들어옴 (파라미터: path, page, sort 등)
    // 2. 데이터 조회: AsperaNodeService.browseDirectory(path)를 호출하여 Node API로부터 해당 경로의
    // 파일 목록을 받아옴
    // 3. 권한 필터링: 현재 로그인한 사용자의 권한(FolderPermission)을 확인하여, 접근 불가능한 파일/폴더는 목록에서 제외
    // 4. 정렬 및 페이징: 사용자가 요청한 정렬 기준(sort, order)과 페이지(page, size)에 맞춰 목록을 가공
    // 5. 반환: 가공된 파일 목록(displayItems)과 페이징 정보를 Model에 담아 'file-browser' 뷰로 전달
    @GetMapping("/files")
    public String browse(@RequestParam(name = "path", required = false, defaultValue = "/") String requestedPath,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "size", required = false, defaultValue = "50") int size,
            @RequestParam(name = "sort", required = false) java.util.List<String> sort,
            @RequestParam(name = "order", required = false) java.util.List<String> order,
            @RequestParam(name = "groupBy", required = false, defaultValue = "folders") String groupBy,
            java.security.Principal principal,
            Model model) {
        String username = principal == null ? "unknown" : principal.getName();
        com.aspera.web.entity.User currentUser = principal == null
                ? null
                : userRepository.findByUsername(username).orElse(null);
        boolean isAdmin = currentUser != null && "ADMIN".equals(currentUser.getRole());

        model.addAttribute("currentUsername", principal == null ? "User" : username);
        model.addAttribute("isAdmin", isAdmin);

        final String path;
        try {
            path = NodePathPolicy.normalizeAbsolutePath(requestedPath);
        } catch (IllegalArgumentException ex) {
            return renderBrowserError(model, "/", ex.getMessage());
        }

        if (!isAdmin && !canBrowsePath(currentUser, path)) {
            return renderBrowserError(model, "/", "You do not have permission to browse that path.");
        }

        java.util.List<AsperaNodeService.FileItem> allFiles;
        try {
            allFiles = new java.util.ArrayList<>(asperaNodeService.browseDirectory(path));
        } catch (NodeApiException | IllegalArgumentException ex) {
            return renderBrowserError(model, path, ex.getMessage());
        }

        // 권한에 따른 파일 목록 필터링
        // 관리자가 아니고 로그인한 사용자인 경우, 사용자에게 할당된 권한과 경로를 비교하여 접근 가능한 파일만 추려냄.
        if (!isAdmin && currentUser != null) {
            final com.aspera.web.entity.User user = currentUser;
            allFiles = new java.util.ArrayList<>(allFiles.stream().filter(item -> {
                String fullPath = safeChildPath(path, item.name());
                if (fullPath == null) {
                    // Keep the Node response visible for diagnosis, but later render it
                    // without navigation or mutation actions because no safe request path
                    // can be produced for this item.
                    return true;
                }
                return user.getPermissions().stream().anyMatch(perm -> {
                    try {
                        return perm != null && NodePathPolicy.overlaps(fullPath, perm.getPath());
                    } catch (IllegalArgumentException ex) {
                        return false;
                    }
                });
            }).toList());
        } else if (!isAdmin && currentUser == null) {
            allFiles = new java.util.ArrayList<>(); // 비로그인 사용자는 파일 목록을 볼 수 없음 (빈 리스트 반환)
        }

        // 1. 그룹핑 정렬 (우선 순위 정렬)
        // 'folders'로 그룹핑하면 디렉토리를 상단에, 'files'로 그룹핑하면 디렉토리를 하단에 배치.
        java.util.Comparator<AsperaNodeService.FileItem> primaryComparator = (f1, f2) -> 0;
        if ("folders".equalsIgnoreCase(groupBy)) {
            primaryComparator = (f1, f2) -> {
                if (f1.type().equals(f2.type()))
                    return 0;
                return "directory".equals(f1.type()) ? -1 : 1;
            };
        } else if ("files".equalsIgnoreCase(groupBy)) {
            primaryComparator = (f1, f2) -> {
                if (f1.type().equals(f2.type()))
                    return 0;
                return "directory".equals(f1.type()) ? 1 : -1;
            };
        }

        // 2. 속성별 다중 정렬 (크기, 날짜, 이름 등)
        if (sort == null || sort.isEmpty()) {
            sort = java.util.List.of("name");
        } else {
            sort = sort.stream()
                    .filter(key -> "name".equals(key) || "size".equals(key) || "date".equals(key))
                    .limit(3)
                    .toList();
            if (sort.isEmpty()) {
                sort = java.util.List.of("name");
            }
        }
        if (order == null || order.isEmpty()) {
            order = java.util.List.of("asc");
        } else {
            order = order.stream()
                    .map(value -> "desc".equalsIgnoreCase(value) ? "desc" : "asc")
                    .limit(3)
                    .toList();
        }

        boolean sizeSortSelected = sort.contains("size");
        java.util.Map<String, Long> sizeByPath = new java.util.HashMap<>();
        boolean sizeSortPartial = false;
        boolean invalidChildPathsPresent = allFiles.stream()
                .anyMatch(item -> safeChildPath(path, item.name()) == null);

        // Avoid recursive folder size calculation unless size sorting is requested.
        if (sizeSortSelected) {
            java.util.List<String> directoryPaths = new java.util.ArrayList<>();
            for (AsperaNodeService.FileItem item : allFiles) {
                String fullPath = safeChildPath(path, item.name());
                if (fullPath == null) {
                    continue;
                }
                if ("directory".equals(item.type())) {
                    if (isAdmin || canCalculateDirectorySize(currentUser, fullPath)) {
                        directoryPaths.add(fullPath);
                    }
                } else {
                    sizeByPath.put(fullPath, item.size());
                }
            }

            if (!directoryPaths.isEmpty()) {
                sizeByPath.putAll(asperaNodeService.calculateDirectorySizes(directoryPaths));
            }
            sizeSortPartial = allFiles.stream()
                    .anyMatch(item -> knownSizeForSort(item, path, sizeByPath) == null);
        }

        java.util.Comparator<AsperaNodeService.FileItem> chainComparator = primaryComparator;

        for (int i = 0; i < sort.size(); i++) {
            String sortKey = sort.get(i);
            String sortOrder = (i < order.size()) ? order.get(i) : "asc"; // Default to asc if missing

            java.util.Comparator<AsperaNodeService.FileItem> nextComparator;
            switch (sortKey) {
                case "size": {
                    java.util.Comparator<AsperaNodeService.FileItem> knownSizesFirst =
                            java.util.Comparator.comparing(
                                    item -> knownSizeForSort(item, path, sizeByPath) == null);
                    java.util.Comparator<AsperaNodeService.FileItem> byKnownSize =
                            java.util.Comparator.comparingLong(item -> {
                                Long knownSize = knownSizeForSort(item, path, sizeByPath);
                                return knownSize == null ? 0 : knownSize;
                            });
                    if ("desc".equalsIgnoreCase(sortOrder)) {
                        byKnownSize = byKnownSize.reversed();
                    }
                    nextComparator = knownSizesFirst.thenComparing(byKnownSize);
                    break;
                }
                case "date":
                    nextComparator = java.util.Comparator.comparing(AsperaNodeService.FileItem::modifiedTime,
                            java.util.Comparator.nullsLast(String::compareTo));
                    break;
                case "name":
                default:
                    nextComparator = java.util.Comparator.comparing(AsperaNodeService.FileItem::name,
                            String.CASE_INSENSITIVE_ORDER);
                    break;
            }

            if (!"size".equals(sortKey) && "desc".equalsIgnoreCase(sortOrder)) {
                nextComparator = nextComparator.reversed();
            }

            // Chain it
            chainComparator = chainComparator.thenComparing(nextComparator);
        }

        // 최종 리스트 정렬 실행
        allFiles.sort(chainComparator);

        size = Math.max(1, Math.min(size, 200));
        int totalItems = allFiles.size();
        int totalPages = (int) Math.ceil((double) totalItems / size);

        if (page < 1)
            page = 1;
        if (totalPages == 0) {
            page = 1;
        } else if (page > totalPages) {
            page = totalPages;
        }

        int start = (page - 1) * size;
        int end = Math.min(start + size, totalItems);

        java.util.List<AsperaNodeService.FileItem> pagedRawFiles = (start >= totalItems)
                ? java.util.Collections.emptyList()
                : allFiles.subList(start, end);

        // 현재 보고 있는 폴더(path)에 대한 업로드/폴더생성 권한 계산
        // 관리자는 무조건 허용. 일반 사용자는 해당 경로에 대한 'upload' 또는 'createFolder' 권한이 명시적으로 있어야 함.
        boolean canUpload = isAdmin;
        boolean canCreateFolder = isAdmin;

        if (!isAdmin && currentUser != null) {
            canUpload = currentUser.getPermissions().stream()
                    .anyMatch(perm -> permissionCovers(path, perm) && perm.isCanUpload());
            canCreateFolder = currentUser.getPermissions().stream()
                    .anyMatch(perm -> permissionCovers(path, perm) && perm.isCanCreateFolder());
        }

        // Convert to View DTOs with formatted dates
        final boolean finalIsAdmin = isAdmin;
        final String finalUsername = username;
        final com.aspera.web.entity.User finalUser = currentUser;

        var pagedFiles = pagedRawFiles.stream()
                .map(item -> toDisplayItem(item, path, finalIsAdmin, finalUsername, finalUser, sizeByPath,
                        sizeSortSelected))
                .toList();

        model.addAttribute("files", pagedFiles);
        model.addAttribute("currentPath", path);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageNumbers", buildPageNumbers(page, totalPages));
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("pageSize", size);
        model.addAttribute("currentGroupBy", groupBy);

        // Pass lists back for View logic checks
        model.addAttribute("currentSorts", sort);
        model.addAttribute("currentOrders", order);
        model.addAttribute("sizeSortPartial", sizeSortPartial);
        model.addAttribute("invalidChildPathsPresent", invalidChildPathsPresent);

        // Pass folder-level flags
        model.addAttribute("canUpload", canUpload);
        model.addAttribute("canCreateFolder", canCreateFolder);

        return "file-browser";
    }

    @PostMapping("/files/dir-sizes")
    @ResponseBody
    public ResponseEntity<?> fetchDirectorySizes(@RequestBody DirectorySizeRequest request,
            java.security.Principal principal) {
        if (request == null || request.paths() == null || request.paths().isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyMap());
        }

        User currentUser = principal == null
                ? null
                : userRepository.findByUsername(principal.getName()).orElse(null);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "Authenticated user was not found."));
        }

        final java.util.List<String> paths;
        try {
            paths = request.paths().stream()
                    .filter(p -> p != null && !p.isBlank())
                    .map(NodePathPolicy::normalizeAbsolutePath)
                    .distinct()
                    .limit(101)
                    .toList();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }

        if (paths.isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyMap());
        }
        if (paths.size() > 100) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Too many paths were requested."));
        }
        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        if (!isAdmin && paths.stream().anyMatch(path -> !canCalculateDirectorySize(currentUser, path))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "A requested path is not permitted."));
        }

        java.util.Map<String, Long> sizes = asperaNodeService.calculateDirectorySizes(paths);
        java.util.Map<String, String> formatted = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> entry : sizes.entrySet()) {
            formatted.put(entry.getKey(), formatSize(entry.getValue()));
        }

        return ResponseEntity.ok(formatted);
    }

    static record DirectorySizeRequest(java.util.List<String> paths) {
    }

    private boolean canBrowsePath(User user, String path) {
        if (user == null || user.getPermissions() == null) {
            return false;
        }
        return user.getPermissions().stream().anyMatch(permission -> {
            try {
                return permission != null && NodePathPolicy.overlaps(path, permission.getPath());
            } catch (IllegalArgumentException ex) {
                return false;
            }
        });
    }

    private boolean canCalculateDirectorySize(User user, String path) {
        if (user == null || user.getPermissions() == null) {
            return false;
        }
        return user.getPermissions().stream().anyMatch(permission -> {
            try {
                return permission != null
                        && NodePathPolicy.isSameOrDescendant(path, permission.getPath());
            } catch (IllegalArgumentException ex) {
                return false;
            }
        });
    }

    private boolean permissionCovers(String path, com.aspera.web.entity.FolderPermission permission) {
        if (permission == null) {
            return false;
        }
        try {
            return NodePathPolicy.isSameOrDescendant(path, permission.getPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String renderBrowserError(Model model, String currentPath, String message) {
        model.addAttribute("files", java.util.Collections.emptyList());
        model.addAttribute("currentPath", currentPath);
        model.addAttribute("currentPage", 1);
        model.addAttribute("totalPages", 0);
        model.addAttribute("pageNumbers", List.of());
        model.addAttribute("totalItems", 0);
        model.addAttribute("pageSize", 50);
        model.addAttribute("currentGroupBy", "folders");
        model.addAttribute("currentSorts", java.util.List.of("name"));
        model.addAttribute("currentOrders", java.util.List.of("asc"));
        model.addAttribute("sizeSortPartial", false);
        model.addAttribute("invalidChildPathsPresent", false);
        model.addAttribute("canUpload", false);
        model.addAttribute("canCreateFolder", false);
        model.addAttribute("error", message == null || message.isBlank()
                ? "The requested folder could not be displayed."
                : message);
        return "file-browser";
    }

    // DTO 변환 로직: AsperaNodeService의 FileItem을 화면에 표시하기 위한 DisplayFileItem으로 변환
    private DisplayFileItem toDisplayItem(AsperaNodeService.FileItem item, String parentPath, boolean isAdmin,
            String username, com.aspera.web.entity.User user, java.util.Map<String, Long> sizeByPath,
            boolean includeDirSizes) {
        String formattedDate = item.modifiedTime();
        try {
            if (formattedDate != null && !formattedDate.isEmpty()) {
                java.time.Instant instant = java.time.Instant.parse(formattedDate);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                        .ofPattern("yy-MM-dd HH:mm:ss")
                        .withZone(java.time.ZoneId.of("Asia/Seoul"));
                formattedDate = formatter.format(instant);
            }
        } catch (Exception e) {
            // ignore
        }

        String fullPath = safeChildPath(parentPath, item.name());
        boolean pathSafe = fullPath != null;

        long sizeBytes = pathSafe ? item.size() : -1;
        if ("directory".equals(item.type())) {
            boolean mayCalculateSize = pathSafe && (isAdmin || canCalculateDirectorySize(user, fullPath));
            Long cached = pathSafe && sizeByPath != null ? sizeByPath.get(fullPath) : null;
            if (cached != null) {
                sizeBytes = cached;
            } else if (includeDirSizes) {
                // The batch owns the request-wide deadline and work limit. A missing
                // result must stay unknown instead of starting an unbounded sequence
                // of synchronous recursive calls while rendering the page.
                sizeBytes = -1;
            } else if (mayCalculateSize) {
                Long quickSize = asperaNodeService.getCachedDirectorySize(fullPath);
                if (quickSize != null) {
                    sizeBytes = quickSize;
                } else {
                    sizeBytes = -1;
                }
            } else {
                sizeBytes = -1;
            }
        }

        String formattedSize = formatSize(sizeBytes);

        // 개별 파일/폴더에 대한 삭제 및 다운로드 권한 확인
        // 관리자는 모두 허용. 일반 사용자는 해당 파일 경로가 권한 경로 하위에 있고, 구체적인 권한 플래그(delete, download)가
        // 있어야 함.
        boolean canDelete = isAdmin && pathSafe;
        boolean canDownload = isAdmin && pathSafe;

        if (pathSafe && !isAdmin && user != null) {
            canDelete = user.getPermissions().stream()
                    .anyMatch(perm -> permissionCovers(fullPath, perm) && perm.isCanDelete());

            canDownload = user.getPermissions().stream()
                    .anyMatch(perm -> permissionCovers(fullPath, perm) && perm.isCanDownload());
        } else if (!isAdmin && user == null) {
            canDelete = false;
            canDownload = false;
        }

        return new DisplayFileItem(item.name(), pathSafe ? fullPath : "", item.type(), formattedSize, formattedDate,
                canDelete, canDownload, pathSafe);
    }

    private Long knownSizeForSort(AsperaNodeService.FileItem item, String parentPath,
            java.util.Map<String, Long> sizeByPath) {
        String fullPath = safeChildPath(parentPath, item.name());
        if (fullPath == null) {
            return null;
        }
        Long size = sizeByPath.get(fullPath);
        return size != null && size >= 0 ? size : null;
    }

    private String safeChildPath(String parentPath, String childName) {
        try {
            return NodePathPolicy.join(parentPath, childName);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 0) {
            return "...";
        }
        if (bytes < 1024)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public record DisplayFileItem(String name, String path, String type, String size, String modifiedTime,
            boolean canDelete,
            boolean canDownload,
            boolean pathSafe) {
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
