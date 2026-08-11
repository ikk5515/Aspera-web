package com.aspera.web.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Bounded, in-memory protection against repeated form-login guesses.
 * A reverse proxy should enforce an additional distributed rate limit.
 */
@Service
public class LoginAttemptService {

    static final int USER_FAILURE_LIMIT = 8;
    static final int ADDRESS_FAILURE_LIMIT = 24;
    private static final int MAX_USER_ENTRIES = 10_000;
    private static final int MAX_ADDRESS_ENTRIES = 5_000;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long BLOCK_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long ENTRY_TTL_MILLIS = Duration.ofHours(1).toMillis();

    private final Clock clock;
    private final Map<String, AttemptState> usernames = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<String, AttemptState> addresses = new LinkedHashMap<>(128, 0.75f, true);

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isBlocked(String remoteAddress, String username) {
        long now = clock.millis();
        return blocked(usernames.get(normalizeUsername(username)), now)
                || blocked(addresses.get(normalizeAddress(remoteAddress)), now);
    }

    public synchronized void recordFailure(String remoteAddress, String username) {
        long now = clock.millis();
        record(usernames, normalizeUsername(username), USER_FAILURE_LIMIT, MAX_USER_ENTRIES, now);
        record(addresses, normalizeAddress(remoteAddress), ADDRESS_FAILURE_LIMIT, MAX_ADDRESS_ENTRIES, now);
    }

    public synchronized void recordSuccess(String username) {
        usernames.remove(normalizeUsername(username));
    }

    private boolean blocked(AttemptState state, long now) {
        if (state == null) {
            return false;
        }
        state.lastSeen = now;
        if (state.blockedUntil > now) {
            return true;
        }
        if (now - state.windowStarted >= WINDOW_MILLIS) {
            state.windowStarted = now;
            state.failures = 0;
            state.blockedUntil = 0;
        }
        return false;
    }

    private void record(Map<String, AttemptState> entries, String key, int failureLimit, int maximumEntries,
            long now) {
        AttemptState state = entries.get(key);
        if (state == null) {
            removeExpired(entries, now);
            trimToSize(entries, maximumEntries - 1);
            state = new AttemptState(now);
            entries.put(key, state);
        }

        state.lastSeen = now;
        if (state.blockedUntil > now) {
            return;
        }
        if (now - state.windowStarted >= WINDOW_MILLIS) {
            state.windowStarted = now;
            state.failures = 0;
        }
        state.failures++;
        if (state.failures >= failureLimit) {
            state.failures = 0;
            state.blockedUntil = now + BLOCK_MILLIS;
        }
    }

    private void removeExpired(Map<String, AttemptState> entries, long now) {
        entries.entrySet().removeIf(entry -> now - entry.getValue().lastSeen >= ENTRY_TTL_MILLIS);
    }

    private void trimToSize(Map<String, AttemptState> entries, int targetSize) {
        Iterator<String> oldestFirst = entries.keySet().iterator();
        while (entries.size() > targetSize && oldestFirst.hasNext()) {
            oldestFirst.next();
            oldestFirst.remove();
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return "<empty>";
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "<empty>";
        }
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private String normalizeAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "<unknown>";
        }
        String normalized = remoteAddress.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static final class AttemptState {
        private long windowStarted;
        private long lastSeen;
        private int failures;
        private long blockedUntil;

        private AttemptState(long now) {
            this.windowStarted = now;
            this.lastSeen = now;
        }
    }
}
