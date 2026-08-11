package com.aspera.web.service;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.security.NodePathPolicy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPermissionService {

    private static final int MAX_PATHS_PER_REQUEST = 100;
    private static final Set<String> ALLOWED_UPDATE_FIELDS =
            Set.of("canUpload", "canDownload", "canCreateFolder", "canDelete");

    private final UserRepository userRepository;

    public UserPermissionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public MutationResult addPermissions(long userId, List<String> paths,
            boolean canUpload, boolean canDownload, boolean canCreateFolder, boolean canDelete) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return MutationResult.of(MutationStatus.USER_NOT_FOUND);
        }
        if (paths == null || paths.isEmpty()) {
            return MutationResult.of(MutationStatus.PATHS_REQUIRED);
        }
        if (!canUpload && !canDownload && !canCreateFolder && !canDelete) {
            return MutationResult.of(MutationStatus.CAPABILITY_REQUIRED);
        }
        if (paths.size() > MAX_PATHS_PER_REQUEST) {
            return MutationResult.of(MutationStatus.TOO_MANY_PATHS);
        }

        LinkedHashSet<String> normalizedPaths = new LinkedHashSet<>();
        try {
            for (String path : paths) {
                if (path != null && !path.isBlank()) {
                    normalizedPaths.add(NodePathPolicy.normalizeAbsolutePath(path));
                }
            }
        } catch (IllegalArgumentException ex) {
            return new MutationResult(MutationStatus.INVALID_PATH, 0, ex.getMessage());
        }
        if (normalizedPaths.isEmpty()) {
            return MutationResult.of(MutationStatus.PATHS_REQUIRED);
        }

        int addedCount = 0;
        for (String path : normalizedPaths) {
            boolean duplicate = user.getPermissions().stream()
                    .anyMatch(existing -> existing != null && sameNormalizedPath(existing.getPath(), path));
            if (duplicate) {
                continue;
            }
            user.addPermission(new FolderPermission(
                    path, canUpload, canDownload, canCreateFolder, canDelete));
            addedCount++;
        }

        if (addedCount == 0) {
            return MutationResult.of(MutationStatus.DUPLICATE_PATHS);
        }
        return new MutationResult(MutationStatus.SUCCESS, addedCount, null);
    }

    @Transactional
    public MutationResult updatePermission(long userId, Long permissionId, Map<String, Boolean> updates) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return MutationResult.of(MutationStatus.USER_NOT_FOUND);
        }
        if (permissionId == null || updates == null || updates.isEmpty()) {
            return MutationResult.of(MutationStatus.UPDATES_REQUIRED);
        }
        if (!ALLOWED_UPDATE_FIELDS.containsAll(updates.keySet())
                || updates.values().stream().anyMatch(Objects::isNull)) {
            return MutationResult.of(MutationStatus.INVALID_UPDATES);
        }

        FolderPermission permission = user.getPermissions().stream()
                .filter(candidate -> candidate != null && Objects.equals(candidate.getId(), permissionId))
                .findFirst()
                .orElse(null);
        if (permission == null) {
            return MutationResult.of(MutationStatus.PERMISSION_NOT_FOUND);
        }

        if (updates.containsKey("canUpload")) {
            permission.setCanUpload(updates.get("canUpload"));
        }
        if (updates.containsKey("canDownload")) {
            permission.setCanDownload(updates.get("canDownload"));
        }
        if (updates.containsKey("canCreateFolder")) {
            permission.setCanCreateFolder(updates.get("canCreateFolder"));
        }
        if (updates.containsKey("canDelete")) {
            permission.setCanDelete(updates.get("canDelete"));
        }
        return MutationResult.of(MutationStatus.SUCCESS);
    }

    @Transactional
    public MutationResult deletePermission(long userId, Long permissionId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return MutationResult.of(MutationStatus.USER_NOT_FOUND);
        }
        if (permissionId == null) {
            return MutationResult.of(MutationStatus.PERMISSION_NOT_FOUND);
        }

        FolderPermission permission = user.getPermissions().stream()
                .filter(candidate -> candidate != null && Objects.equals(candidate.getId(), permissionId))
                .findFirst()
                .orElse(null);
        if (permission == null) {
            return MutationResult.of(MutationStatus.PERMISSION_NOT_FOUND);
        }
        user.removePermission(permission);
        return MutationResult.of(MutationStatus.SUCCESS);
    }

    private static boolean sameNormalizedPath(String first, String second) {
        try {
            return NodePathPolicy.normalizeAbsolutePath(first)
                    .equals(NodePathPolicy.normalizeAbsolutePath(second));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public enum MutationStatus {
        SUCCESS,
        USER_NOT_FOUND,
        PATHS_REQUIRED,
        CAPABILITY_REQUIRED,
        TOO_MANY_PATHS,
        INVALID_PATH,
        DUPLICATE_PATHS,
        UPDATES_REQUIRED,
        INVALID_UPDATES,
        PERMISSION_NOT_FOUND
    }

    public record MutationResult(MutationStatus status, int affectedCount, String detail) {
        static MutationResult of(MutationStatus status) {
            return new MutationResult(status, 0, null);
        }
    }
}
