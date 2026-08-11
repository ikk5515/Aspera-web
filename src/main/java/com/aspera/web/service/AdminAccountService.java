package com.aspera.web.service;

import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccountService {

    private final UserRepository userRepository;

    public AdminAccountService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public MutationResult deleteUser(long userId, String actorUsername) {
        List<User> lockedAdmins = userRepository.findAdminsForUpdate();
        User target = findTarget(userId, lockedAdmins);
        if (target == null) {
            return MutationResult.of(MutationStatus.NOT_FOUND);
        }
        if (Objects.equals(actorUsername, target.getUsername())) {
            return MutationResult.of(MutationStatus.SELF_ACTION_DENIED);
        }
        if ("ADMIN".equals(target.getRole()) && lockedAdmins.size() <= 1) {
            return MutationResult.of(MutationStatus.LAST_ADMIN_DENIED);
        }

        String username = target.getUsername();
        userRepository.delete(target);
        return new MutationResult(MutationStatus.SUCCESS, username);
    }

    @Transactional
    public MutationResult revokeAdmin(long userId, String actorUsername) {
        List<User> lockedAdmins = userRepository.findAdminsForUpdate();
        User target = findTarget(userId, lockedAdmins);
        if (target == null) {
            return MutationResult.of(MutationStatus.NOT_FOUND);
        }
        if (Objects.equals(actorUsername, target.getUsername())) {
            return MutationResult.of(MutationStatus.SELF_ACTION_DENIED);
        }
        if (!"ADMIN".equals(target.getRole())) {
            return MutationResult.of(MutationStatus.NO_CHANGE);
        }
        if (lockedAdmins.size() <= 1) {
            return MutationResult.of(MutationStatus.LAST_ADMIN_DENIED);
        }

        target.setRole("USER");
        userRepository.save(target);
        return new MutationResult(MutationStatus.SUCCESS, target.getUsername());
    }

    @Transactional
    public MutationResult promoteAdmin(long userId) {
        User target = userRepository.findByIdForUpdate(userId).orElse(null);
        if (target == null) {
            return MutationResult.of(MutationStatus.NOT_FOUND);
        }
        if ("ADMIN".equals(target.getRole())) {
            return new MutationResult(MutationStatus.NO_CHANGE, target.getUsername());
        }

        target.setRole("ADMIN");
        return new MutationResult(MutationStatus.SUCCESS, target.getUsername());
    }

    private User findTarget(long userId, List<User> lockedAdmins) {
        return lockedAdmins.stream()
                .filter(user -> Objects.equals(user.getId(), userId))
                .findFirst()
                .orElseGet(() -> userRepository.findById(userId).orElse(null));
    }

    public enum MutationStatus {
        SUCCESS,
        NOT_FOUND,
        SELF_ACTION_DENIED,
        LAST_ADMIN_DENIED,
        NO_CHANGE
    }

    public record MutationResult(MutationStatus status, String username) {
        static MutationResult of(MutationStatus status) {
            return new MutationResult(status, null);
        }
    }
}
