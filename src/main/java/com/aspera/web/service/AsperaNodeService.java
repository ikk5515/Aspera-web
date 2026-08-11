package com.aspera.web.service;

import com.aspera.web.security.NodePathPolicy;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AsperaNodeService {

    private static final Logger log = LoggerFactory.getLogger(AsperaNodeService.class);
    private static final long DIRECTORY_SIZE_CACHE_TTL_MS = 300_000;
    private static final int MAX_DIRECTORY_DEPTH = 64;
    private static final int MAX_DIRECTORY_COUNT = 2_000;
    private static final int MAX_DIRECTORY_ITEM_COUNT = 50_000;
    private static final int MAX_NODE_BROWSE_ITEMS = 10_000;
    private static final int MAX_DIRECTORY_SIZE_CACHE_ENTRIES = 512;
    private static final int MAX_BATCH_PATHS = 100;
    private static final int DIRECTORY_SIZE_QUEUE_CAPACITY = MAX_BATCH_PATHS;
    private static final long DIRECTORY_TRAVERSAL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long DIRECTORY_SIZE_BATCH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int MAX_TRANSFER_RESPONSE_CHARS = 5_000_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicReference<NodeConfig> nodeConfig;
    private final Set<String> allowedNodeOrigins;
    private final ConcurrentHashMap<String, DirectorySizeCacheEntry> directorySizeCache = new ConcurrentHashMap<>();
    private final Object directorySizeCacheLock = new Object();
    private final ThreadPoolExecutor directorySizeExecutor = createDirectorySizeExecutor();

    @Autowired
    public AsperaNodeService(RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${aspera.node.url:}") String nodeUrl,
            @Value("${aspera.node.username:}") String nodeUser,
            @Value("${aspera.node.password:}") String nodePassword,
            @Value("${aspera.node.allowed-origins:}") String allowedNodeOrigins) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.nodeConfig = new AtomicReference<>(initialNodeConfig(nodeUrl, nodeUser, nodePassword));
        this.allowedNodeOrigins = parseAllowedOrigins(allowedNodeOrigins);
    }

    public AsperaNodeService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this(restTemplate, objectMapper, "", "", "", "");
    }

    public String getNodeUrl() {
        return nodeConfig.get().url();
    }

    public String getNodeUser() {
        return nodeConfig.get().username();
    }

    public synchronized void updateConfig(String newNodeUrl, String newNodeUser, String newNodePassword) {
        String validatedUrl = validateNodeUrl(newNodeUrl);
        String validatedUser = validateNodeUser(newNodeUser);
        NodeConfig current = nodeConfig.get();
        if (!validatedUrl.equals(current.url()) && !allowedNodeOrigins.contains(validatedUrl)) {
            throw new IllegalArgumentException(
                    "Node URL is not in the deployment allowlist. Configure ASPERA_NODE_ALLOWED_ORIGINS first.");
        }
        boolean passwordProvided = newNodePassword != null && !newNodePassword.isBlank();
        boolean identityChanged = !validatedUrl.equals(current.url()) || !validatedUser.equals(current.username());
        if (identityChanged && !passwordProvided) {
            throw new IllegalArgumentException("Node password is required when the Node URL or username changes.");
        }

        String validatedPassword = current.password();
        if (passwordProvided) {
            if (newNodePassword.length() > 4096 || containsControlCharacter(newNodePassword)) {
                throw new IllegalArgumentException("Node password is invalid.");
            }
            validatedPassword = newNodePassword;
        }
        if (validatedPassword == null || validatedPassword.isBlank()) {
            throw new IllegalArgumentException("Node password is required.");
        }

        synchronized (directorySizeCacheLock) {
            nodeConfig.set(new NodeConfig(
                    validatedUrl,
                    validatedUser,
                    validatedPassword,
                    current.generation() + 1));
            directorySizeCache.clear();
        }
    }

    private record NodeConfig(String url, String username, String password, long generation) {
    }

    private static NodeConfig initialNodeConfig(String rawUrl, String rawUser, String rawPassword) {
        String url = rawUrl == null || rawUrl.isBlank() ? "" : validateNodeUrl(rawUrl);
        String user = rawUser == null || rawUser.isBlank() ? "" : validateNodeUser(rawUser);
        return new NodeConfig(url, user, rawPassword == null ? "" : rawPassword, 0);
    }

    private static Set<String> parseAllowedOrigins(String rawOrigins) {
        if (rawOrigins == null || rawOrigins.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(rawOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .map(AsperaNodeService::validateNodeUrl)
                .collect(Collectors.toUnmodifiableSet());
    }

    record NodeFileItem(String basename, String type, long size, String mtime) {
    }

    record NodeListResponse(List<NodeFileItem> items) {
    }

    public record FileItem(String name, String type, long size, String modifiedTime) {
    }

    public static class NodeApiException extends RuntimeException {
        public NodeApiException(String message) {
            super(message);
        }

        public NodeApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public List<FileItem> browseDirectory(String path) {
        try {
            NodeConfig config = requireConfiguredNodeConfig();
            return browseDirectory(path, config);
        } catch (Exception ex) {
            log.warn("Node browse request failed ({})", ex.getClass().getSimpleName());
            throw new NodeApiException("Unable to load files from the Node service.", ex);
        }
    }

    private List<FileItem> browseDirectory(String path, NodeConfig config) throws Exception {
        String browsePath = NodePathPolicy.normalizeAbsolutePath(path == null ? "/" : path);
        Map<String, String> body = Map.of("path", browsePath);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), createHeaders(config));
        ResponseEntity<NodeListResponse> response = restTemplate.postForEntity(
                nodeApiUrl(config, "/files/browse"), request, NodeListResponse.class);
        requireSuccessfulNodeResponse(response);

        NodeListResponse responseBody = response.getBody();
        if (responseBody == null || responseBody.items() == null) {
            return Collections.emptyList();
        }
        if (responseBody.items().size() > maximumNodeBrowseItems()) {
            throw new NodeApiException("Node browse response exceeded the item limit.");
        }

        return responseBody.items().stream()
                .filter(item -> item != null && NodePathPolicy.isSafeChildName(item.basename()))
                .filter(item -> "file".equals(item.type()) || "directory".equals(item.type()))
                .map(item -> new FileItem(item.basename(), item.type(), Math.max(0, item.size()), item.mtime()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private FileItem getFileItem(String path, NodeConfig config) throws Exception {
        String normalizedPath = NodePathPolicy.normalizeAbsolutePath(path);
        if ("/".equals(normalizedPath)) {
            return null;
        }
        int lastSlash = normalizedPath.lastIndexOf('/');
        String parent = lastSlash == 0 ? "/" : normalizedPath.substring(0, lastSlash);
        String filename = normalizedPath.substring(lastSlash + 1);
        return browseDirectory(parent, config).stream()
                .filter(file -> file.name().equals(filename))
                .findFirst()
                .orElse(null);
    }

    public FileItem deleteFile(String path) throws IOException {
        String normalizedPath;
        try {
            normalizedPath = NodePathPolicy.normalizeAbsolutePath(path);
            if ("/".equals(normalizedPath)) {
                throw new IllegalArgumentException("The Node root cannot be deleted.");
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }

        try {
            NodeConfig config = requireConfiguredNodeConfig();
            FileItem item = getFileItem(normalizedPath, config);
            String name = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
            String type = item == null ? "unknown" : item.type();
            long size = item == null ? 0 : item.size();
            if (item != null && "directory".equals(type)) {
                size = calculateDirectorySize(normalizedPath, config);
            }

            Map<String, Object> body = Map.of("paths", List.of(Map.of("path", normalizedPath)));
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), createHeaders(config));
            ResponseEntity<String> response = restTemplate.postForEntity(
                    nodeApiUrl(config, "/files/delete"), request, String.class);
            requireSuccessfulNodeResponse(response);
            invalidatePathCaches(normalizedPath, config);

            return item == null
                    ? new FileItem(name, type, size, "")
                    : new FileItem(item.name(), item.type(), size, item.modifiedTime());
        } catch (Exception ex) {
            log.warn("Node delete request failed ({})", ex.getClass().getSimpleName());
            throw new IOException("The file could not be deleted from the Node service.", ex);
        }
    }

    public void createFolder(String parentPath, String folderName) throws IOException {
        final String normalizedParent;
        final String newPath;
        try {
            normalizedParent = NodePathPolicy.normalizeAbsolutePath(parentPath);
            newPath = NodePathPolicy.join(normalizedParent, folderName);
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }

        try {
            NodeConfig config = requireConfiguredNodeConfig();
            Map<String, Object> body = Map.of(
                    "paths", List.of(Map.of("path", newPath, "type", "directory")));
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), createHeaders(config));
            ResponseEntity<String> response = restTemplate.postForEntity(
                    nodeApiUrl(config, "/files/create"), request, String.class);
            requireSuccessfulNodeResponse(response);
            invalidatePathCaches(normalizedParent, config);
        } catch (Exception ex) {
            log.warn("Node folder-create request failed ({})", ex.getClass().getSimpleName());
            throw new IOException("The folder could not be created on the Node service.", ex);
        }
    }

    public long calculateDirectorySize(String parentPath) {
        try {
            NodeConfig config = requireConfiguredNodeConfig();
            String normalizedPath = NodePathPolicy.normalizeAbsolutePath(parentPath);
            return calculateDirectorySize(normalizedPath, config);
        } catch (Exception ex) {
            log.warn("Node directory-size request failed ({})", ex.getClass().getSimpleName());
            return -1;
        }
    }

    private long calculateDirectorySize(String normalizedPath, NodeConfig config) {
        DirectoryTraversalContext traversal = new DirectoryTraversalContext(
                deadlineAfter(directoryTraversalTimeoutNanos()));
        try {
            return calculateDirectorySize(normalizedPath, config, traversal, 0);
        } catch (Exception ex) {
            log.warn("Node directory-size request failed ({})", ex.getClass().getSimpleName());
            return -1;
        } finally {
            traversal.cancel();
        }
    }

    private long calculateDirectorySize(
            String parentPath,
            NodeConfig config,
            DirectoryTraversalContext traversal,
            int depth) throws Exception {
        traversal.checkActive();

        Long cached = getCachedDirectorySize(parentPath, config);
        if (cached != null) {
            return cached;
        }

        traversal.enterDirectory(parentPath, depth);
        try {
            List<FileItem> items = browseDirectory(parentPath, config);
            traversal.addItems(items.size());

            long totalSize = 0;
            for (FileItem item : items) {
                traversal.checkActive();
                long childSize;
                if ("file".equals(item.type())) {
                    childSize = item.size();
                } else if ("directory".equals(item.type())) {
                    childSize = calculateDirectorySize(
                            NodePathPolicy.join(parentPath, item.name()), config, traversal, depth + 1);
                } else {
                    continue;
                }
                totalSize = saturatingAdd(totalSize, childSize);
            }
            traversal.checkActive();
            cacheDirectorySize(parentPath, totalSize, config);
            return totalSize;
        } finally {
            traversal.leaveDirectory(parentPath);
        }
    }

    public Long getCachedDirectorySize(String parentPath) {
        final String normalizedPath;
        try {
            normalizedPath = NodePathPolicy.normalizeAbsolutePath(parentPath);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        return getCachedDirectorySize(normalizedPath, nodeConfig.get());
    }

    private Long getCachedDirectorySize(String normalizedPath, NodeConfig config) {
        synchronized (directorySizeCacheLock) {
            long currentGeneration = nodeConfig.get().generation();
            if (config.generation() != currentGeneration) {
                return null;
            }
            DirectorySizeCacheEntry cached = directorySizeCache.get(normalizedPath);
            if (cached == null) {
                return null;
            }
            if (cached.generation() != currentGeneration
                    || (System.currentTimeMillis() - cached.timestamp()) > DIRECTORY_SIZE_CACHE_TTL_MS) {
                directorySizeCache.remove(normalizedPath, cached);
                return null;
            }
            return cached.size();
        }
    }

    public Map<String, Long> calculateDirectorySizes(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> normalizedPaths = paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(NodePathPolicy::normalizeAbsolutePath)
                .distinct()
                .limit(MAX_BATCH_PATHS)
                .toList();
        Map<String, Long> results = new LinkedHashMap<>();
        normalizedPaths.forEach(path -> results.put(path, -1L));

        final NodeConfig config;
        try {
            config = requireConfiguredNodeConfig();
        } catch (Exception ex) {
            log.warn("Node directory-size batch could not start ({})", ex.getClass().getSimpleName());
            return Collections.unmodifiableMap(results);
        }

        long batchDeadline = deadlineAfter(directorySizeBatchTimeoutNanos());
        CompletionService<DirectorySizeResult> completion = new ExecutorCompletionService<>(directorySizeExecutor);
        List<Future<DirectorySizeResult>> futures = new ArrayList<>();
        List<DirectoryTraversalContext> traversals = new ArrayList<>();
        int submitted = 0;
        int rejected = 0;

        for (String path : normalizedPaths) {
            Long cached = getCachedDirectorySize(path, config);
            if (cached != null) {
                results.put(path, cached);
                continue;
            }

            DirectoryTraversalContext traversal = new DirectoryTraversalContext(
                    Math.min(batchDeadline, deadlineAfter(directoryTraversalTimeoutNanos())));
            try {
                Future<DirectorySizeResult> future = completion.submit(
                        () -> new DirectorySizeResult(path, calculateDirectorySizeSafely(path, config, traversal)));
                traversals.add(traversal);
                futures.add(future);
                submitted++;
            } catch (RejectedExecutionException ex) {
                traversal.cancel();
                rejected++;
            }
        }
        if (rejected > 0) {
            log.warn("Rejected {} directory-size tasks because the bounded executor is saturated.", rejected);
        }

        try {
            while (submitted > 0) {
                long remaining = batchDeadline - System.nanoTime();
                if (remaining <= 0) {
                    log.warn("Directory-size batch reached its deadline; unfinished work was cancelled.");
                    break;
                }

                Future<DirectorySizeResult> completed = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (completed == null) {
                    log.warn("Directory-size batch reached its deadline; unfinished work was cancelled.");
                    break;
                }
                submitted--;
                try {
                    DirectorySizeResult result = completed.get();
                    results.put(result.path(), result.size());
                } catch (CancellationException ex) {
                    // Keep the pre-filled -1 value for cancelled work.
                } catch (ExecutionException ex) {
                    log.warn("Directory-size worker failed ({})", ex.getCause() == null
                            ? ex.getClass().getSimpleName()
                            : ex.getCause().getClass().getSimpleName());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Directory-size batch was interrupted; unfinished work was cancelled.");
        } finally {
            traversals.forEach(DirectoryTraversalContext::cancel);
            futures.forEach(future -> future.cancel(true));
        }
        return Collections.unmodifiableMap(results);
    }

    public Map<String, Object> generateMultiFileTransferSpec(String direction, List<String> paths) {
        if (!"send".equals(direction) && !"receive".equals(direction)) {
            return error("Direction must be 'send' or 'receive'.");
        }

        final List<String> normalizedPaths;
        try {
            normalizedPaths = paths == null ? List.of() : paths.stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(NodePathPolicy::normalizeAbsolutePath)
                    .distinct()
                    .limit(MAX_BATCH_PATHS + 1L)
                    .toList();
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
        if (normalizedPaths.isEmpty()) {
            return error("At least one path is required.");
        }
        if (normalizedPaths.size() > MAX_BATCH_PATHS || ("send".equals(direction) && normalizedPaths.size() != 1)) {
            return error("Too many transfer paths were requested.");
        }

        try {
            NodeConfig config = requireConfiguredNodeConfig();
            Map<String, Object> transferRequest = new LinkedHashMap<>();
            if ("receive".equals(direction)) {
                transferRequest.put("paths", normalizedPaths.stream()
                        .map(path -> Map.of("source", path))
                        .toList());
            } else {
                transferRequest.put("destination_root", normalizedPaths.get(0));
                transferRequest.put("paths", List.of());
            }
            transferRequest.put("token_generation", Map.of("type", "transfer"));

            Map<String, Object> body = Map.of(
                    "transfer_requests", List.of(Map.of("transfer_request", transferRequest)));
            HttpHeaders headers = createHeaders(config);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    nodeApiUrl(config,
                            "receive".equals(direction) ? "/files/download_setup" : "/files/upload_setup"),
                    HttpMethod.POST, entity, String.class);
            requireSuccessfulNodeResponse(response);

            String rawBody = response.getBody();
            if (rawBody == null || rawBody.isBlank() || rawBody.length() > MAX_TRANSFER_RESPONSE_CHARS) {
                return error("The Node service returned an invalid transfer response.");
            }
            Map<String, Object> responseMap = objectMapper.readValue(rawBody, new TypeReference<>() {
            });
            Object rawSpecs = responseMap.get("transfer_specs");
            if (!(rawSpecs instanceof List<?> specs) || specs.isEmpty() || !(specs.get(0) instanceof Map<?, ?> first)) {
                return error("The Node service did not return a transfer specification.");
            }
            if (first.get("error") instanceof Map<?, ?> nodeError) {
                return error(safeNodeError(nodeError));
            }
            Object rawSpec = first.get("transfer_spec");
            if (!(rawSpec instanceof Map<?, ?>)) {
                return error("The Node service returned an invalid transfer specification.");
            }

            Map<String, Object> spec = objectMapper.convertValue(rawSpec, new TypeReference<>() {
            });
            if (spec.containsKey("token")) {
                spec.put("authentication", "token");
            }
            return spec;
        } catch (NodeApiException ex) {
            log.warn("Node transfer request was not successful ({})", ex.getClass().getSimpleName());
            return error("The Node transfer service could not complete the request.");
        } catch (RestClientException ex) {
            log.warn("Node transfer request failed ({})", ex.getClass().getSimpleName());
            return error("The Node transfer service is unavailable.");
        } catch (Exception ex) {
            log.warn("Node transfer response could not be processed ({})", ex.getClass().getSimpleName());
            return error("The Node service returned an invalid transfer response.");
        }
    }

    @PreDestroy
    public void shutdownDirectorySizeExecutor() {
        directorySizeExecutor.shutdownNow();
    }

    private long calculateDirectorySizeSafely(
            String path,
            NodeConfig config,
            DirectoryTraversalContext traversal) {
        try {
            String normalizedPath = NodePathPolicy.normalizeAbsolutePath(path);
            return calculateDirectorySize(normalizedPath, config, traversal, 0);
        } catch (Exception ex) {
            log.debug("Directory-size worker returned an unknown size ({})", ex.getClass().getSimpleName());
            return -1;
        } finally {
            traversal.cancel();
        }
    }

    private static ThreadPoolExecutor createDirectorySizeExecutor() {
        int poolSize = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "directory-size-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DIRECTORY_SIZE_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void cacheDirectorySize(String path, long size, NodeConfig config) {
        long now = System.currentTimeMillis();
        synchronized (directorySizeCacheLock) {
            long currentGeneration = nodeConfig.get().generation();
            if (config.generation() != currentGeneration) {
                return;
            }
            directorySizeCache.entrySet().removeIf(
                    entry -> entry.getValue().generation() != currentGeneration
                            || now - entry.getValue().timestamp() > DIRECTORY_SIZE_CACHE_TTL_MS);

            int maximumEntries = maximumDirectorySizeCacheEntries();
            if (!directorySizeCache.containsKey(path)) {
                while (directorySizeCache.size() >= maximumEntries) {
                    Map.Entry<String, DirectorySizeCacheEntry> oldest = directorySizeCache.entrySet().stream()
                            .min(Map.Entry.comparingByValue(
                                    java.util.Comparator.comparingLong(DirectorySizeCacheEntry::timestamp)))
                            .orElse(null);
                    if (oldest == null) {
                        break;
                    }
                    directorySizeCache.remove(oldest.getKey(), oldest.getValue());
                }
            }
            directorySizeCache.put(path, new DirectorySizeCacheEntry(size, now, currentGeneration));
        }
    }

    private static long deadlineAfter(long timeoutNanos) {
        return System.nanoTime() + timeoutNanos;
    }

    int maximumTraversalDepth() {
        return MAX_DIRECTORY_DEPTH;
    }

    int maximumTraversalDirectories() {
        return MAX_DIRECTORY_COUNT;
    }

    int maximumTraversalItems() {
        return MAX_DIRECTORY_ITEM_COUNT;
    }

    int maximumNodeBrowseItems() {
        return MAX_NODE_BROWSE_ITEMS;
    }

    int maximumDirectorySizeCacheEntries() {
        return MAX_DIRECTORY_SIZE_CACHE_ENTRIES;
    }

    long directoryTraversalTimeoutNanos() {
        return DIRECTORY_TRAVERSAL_TIMEOUT_NANOS;
    }

    long directorySizeBatchTimeoutNanos() {
        return DIRECTORY_SIZE_BATCH_TIMEOUT_NANOS;
    }

    private HttpHeaders createHeaders(NodeConfig config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(config.username(), config.password(), StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String nodeApiUrl(NodeConfig config, String endpoint) {
        return config.url() + endpoint;
    }

    private static void requireSuccessfulNodeResponse(ResponseEntity<?> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new NodeApiException("The Node service returned an unsuccessful response.");
        }
    }

    private NodeConfig requireConfiguredNodeConfig() {
        NodeConfig snapshot = nodeConfig.get();
        String validatedUrl = validateNodeUrl(snapshot.url());
        String validatedUser = validateNodeUser(snapshot.username());
        if (snapshot.password() == null || snapshot.password().isBlank()) {
            throw new NodeApiException("Node API credentials are not configured.");
        }
        return new NodeConfig(validatedUrl, validatedUser, snapshot.password(), snapshot.generation());
    }

    private static String validateNodeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048 || containsControlCharacter(rawUrl)) {
            throw new IllegalArgumentException("Node URL is invalid.");
        }
        try {
            URI uri = new URI(rawUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Node URL must be an HTTPS origin without credentials or a path.");
            }
            String normalized = uri.toString();
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Node URL is invalid.", ex);
        }
    }

    private static String validateNodeUser(String rawUser) {
        if (rawUser == null || rawUser.isBlank() || rawUser.length() > 128 || rawUser.indexOf(':') >= 0
                || containsControlCharacter(rawUser)) {
            throw new IllegalArgumentException("Node username is invalid.");
        }
        return rawUser.trim();
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static long saturatingAdd(long left, long right) {
        if (right < 0) {
            throw new NodeApiException("Invalid directory size.");
        }
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private void invalidatePathCaches(String changedPath, NodeConfig config) {
        synchronized (directorySizeCacheLock) {
            directorySizeCache.entrySet().removeIf(entry -> {
                if (entry.getValue().generation() != config.generation()) {
                    return false;
                }
                try {
                    return NodePathPolicy.overlaps(entry.getKey(), changedPath);
                } catch (IllegalArgumentException ex) {
                    return true;
                }
            });
        }
    }

    private static Map<String, Object> error(String message) {
        return Collections.singletonMap("error", message == null || message.isBlank()
                ? "The Node request could not be completed."
                : message);
    }

    private static String safeNodeError(Map<?, ?> error) {
        Object messageValue = error.get("user_message");
        String message = messageValue == null ? "Node rejected the transfer request." : messageValue.toString();
        message = message.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        return message.isBlank() ? "Node rejected the transfer request." : message;
    }

    private record DirectorySizeResult(String path, long size) {
    }

    private final class DirectoryTraversalContext {
        private final long deadlineNanos;
        private final Set<String> activePaths = new HashSet<>();
        private int directoryCount;
        private int itemCount;
        private volatile boolean cancelled;

        private DirectoryTraversalContext(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        private void enterDirectory(String path, int depth) {
            checkActive();
            if (depth > maximumTraversalDepth()
                    || directoryCount >= maximumTraversalDirectories()
                    || activePaths.contains(path)) {
                throw new NodeApiException("Directory traversal limit exceeded.");
            }
            activePaths.add(path);
            directoryCount++;
        }

        private void addItems(int count) {
            checkActive();
            if (count < 0 || count > maximumTraversalItems() - itemCount) {
                throw new NodeApiException("Directory item traversal limit exceeded.");
            }
            itemCount += count;
        }

        private void leaveDirectory(String path) {
            activePaths.remove(path);
        }

        private void checkActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                cancelled = true;
                throw new NodeApiException("Directory traversal was cancelled.");
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                cancelled = true;
                throw new NodeApiException("Directory traversal deadline exceeded.");
            }
        }

        private void cancel() {
            cancelled = true;
        }
    }

    private record DirectorySizeCacheEntry(long size, long timestamp, long generation) {
    }
}
