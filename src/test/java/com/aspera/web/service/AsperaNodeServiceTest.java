package com.aspera.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AsperaNodeServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private AsperaNodeService service;

    @BeforeEach
    void setUp() {
        service = new AsperaNodeService(
                restTemplate,
                new ObjectMapper(),
                "",
                "",
                "",
                "https://node.example.com:9092,https://node-a.example.com:9092,https://node-b.example.com:9092");
    }

    @AfterEach
    void tearDown() {
        service.shutdownDirectorySizeExecutor();
    }

    @Test
    void rejectsInsecureOrAmbiguousNodeOrigins() {
        assertThatThrownBy(() -> service.updateConfig("http://node.example.com:9092", "node", "strong-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS origin");
        assertThatThrownBy(() -> service.updateConfig("https://user@node.example.com/api", "node", "strong-password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changingOriginOrUsernameRequiresAnExplicitNewPassword() {
        service.updateConfig("https://node-a.example.com:9092", "user-a", "password-a-strong");

        assertThatThrownBy(() -> service.updateConfig(
                "https://node-b.example.com:9092", "user-a", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password is required");
        assertThatThrownBy(() -> service.updateConfig(
                "https://node-a.example.com:9092", "user-b", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password is required");

        assertThat(service.getNodeUrl()).isEqualTo("https://node-a.example.com:9092");
        assertThat(service.getNodeUser()).isEqualTo("user-a");
        service.updateConfig("https://node-a.example.com:9092", "user-a", "");
    }

    @Test
    void firstRuntimeOriginMustComeFromDeploymentAllowlist() {
        AsperaNodeService serviceWithoutAllowlist = new AsperaNodeService(restTemplate, new ObjectMapper());
        try {
            assertThatThrownBy(() -> serviceWithoutAllowlist.updateConfig(
                    "https://unapproved.example.com:9092", "node", "strong-password"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deployment allowlist");
        } finally {
            serviceWithoutAllowlist.shutdownDirectorySizeExecutor();
        }
    }

    @Test
    void deploymentConfiguredCurrentOriginRemainsAllowedWithoutAllowlistEntry() {
        AsperaNodeService configuredService = new AsperaNodeService(
                restTemplate,
                new ObjectMapper(),
                "https://configured.example.com:9092",
                "node",
                "strong-password",
                "");
        try {
            configuredService.updateConfig("https://configured.example.com:9092", "node", "");
            assertThat(configuredService.getNodeUrl()).isEqualTo("https://configured.example.com:9092");
        } finally {
            configuredService.shutdownDirectorySizeExecutor();
        }
    }

    @Test
    void concurrentConfigUpdatesNeverMixOriginAndBasicCredentialsWithinARequest() throws Exception {
        String urlA = "https://node-a.example.com:9092";
        String urlB = "https://node-b.example.com:9092";
        service.updateConfig(urlA, "user-a", "password-a-strong");
        Set<String> observedRequests = ConcurrentHashMap.newKeySet();
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    HttpEntity<?> request = invocation.getArgument(1);
                    String authorization = request.getHeaders().getFirst("Authorization");
                    String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)),
                            StandardCharsets.UTF_8);
                    observedRequests.add(url + "|" + credentials);
                    return ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of()));
                });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> requests = workers.submit(() -> {
                await(start);
                for (int i = 0; i < 500; i++) {
                    service.browseDirectory("/team");
                }
            });
            Future<?> updates = workers.submit(() -> {
                await(start);
                for (int i = 0; i < 500; i++) {
                    if ((i & 1) == 0) {
                        service.updateConfig(urlB, "user-b", "password-b-strong");
                    } else {
                        service.updateConfig(urlA, "user-a", "password-a-strong");
                    }
                }
            });
            start.countDown();
            requests.get(10, TimeUnit.SECONDS);
            updates.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertThat(observedRequests).isNotEmpty().allMatch(request ->
                request.equals(urlA + "/files/browse|user-a:password-a-strong")
                        || request.equals(urlB + "/files/browse|user-b:password-b-strong"));
    }

    @Test
    void deletePinsOneConfigSnapshotAcrossLookupAndMutation() throws Exception {
        String urlA = "https://node-a.example.com:9092";
        String urlB = "https://node-b.example.com:9092";
        service.updateConfig(urlA, "user-a", "password-a-strong");
        Set<String> observedRequests = ConcurrentHashMap.newKeySet();
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenAnswer(invocation -> {
                    observedRequests.add(requestIdentity(invocation.getArgument(0), invocation.getArgument(1)));
                    lookupStarted.countDown();
                    await(releaseLookup);
                    return ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of(
                            new AsperaNodeService.NodeFileItem("file.txt", "file", 7, null))));
                });
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    observedRequests.add(requestIdentity(invocation.getArgument(0), invocation.getArgument(1)));
                    return ResponseEntity.ok("");
                });

        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<AsperaNodeService.FileItem> deletion = worker.submit(() -> service.deleteFile("/team/file.txt"));
        try {
            assertThat(lookupStarted.await(2, TimeUnit.SECONDS)).isTrue();
            service.updateConfig(urlB, "user-b", "password-b-strong");
        } finally {
            releaseLookup.countDown();
        }

        try {
            assertThat(deletion.get(5, TimeUnit.SECONDS).size()).isEqualTo(7);
        } finally {
            worker.shutdownNow();
        }
        assertThat(observedRequests).containsExactlyInAnyOrder(
                urlA + "/files/browse|user-a:password-a-strong",
                urlA + "/files/delete|user-a:password-a-strong");
    }

    @Test
    void directoryTraversalPinsConfigAndCannotReinsertOldGenerationCacheEntries() throws Exception {
        String urlA = "https://node-a.example.com:9092";
        String urlB = "https://node-b.example.com:9092";
        service.updateConfig(urlA, "user-a", "password-a-strong");
        Set<String> observedRequests = ConcurrentHashMap.newKeySet();
        AtomicInteger requestSequence = new AtomicInteger();
        CountDownLatch rootBrowseStarted = new CountDownLatch(1);
        CountDownLatch releaseRootBrowse = new CountDownLatch(1);

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenAnswer(invocation -> {
                    observedRequests.add(requestIdentity(invocation.getArgument(0), invocation.getArgument(1)));
                    if (requestSequence.getAndIncrement() == 0) {
                        rootBrowseStarted.countDown();
                        await(releaseRootBrowse);
                        return ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of(
                                new AsperaNodeService.NodeFileItem("child", "directory", 0, null))));
                    }
                    return ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of(
                            new AsperaNodeService.NodeFileItem("file.txt", "file", 7, null))));
                });

        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<Long> traversal = worker.submit(() -> service.calculateDirectorySize("/team"));
        try {
            assertThat(rootBrowseStarted.await(2, TimeUnit.SECONDS)).isTrue();
            service.updateConfig(urlB, "user-b", "password-b-strong");
        } finally {
            releaseRootBrowse.countDown();
        }

        try {
            assertThat(traversal.get(5, TimeUnit.SECONDS)).isEqualTo(7);
        } finally {
            worker.shutdownNow();
        }
        assertThat(requestSequence).hasValue(2);
        assertThat(observedRequests).containsExactly(
                urlA + "/files/browse|user-a:password-a-strong");
        assertThat(service.getCachedDirectorySize("/team")).isNull();
        assertThat(service.getCachedDirectorySize("/team/child")).isNull();
    }

    @Test
    void directorySizeBatchSharesOneConfigSnapshotAcrossAllWorkers() throws Exception {
        String urlA = "https://node-a.example.com:9092";
        String urlB = "https://node-b.example.com:9092";
        service.updateConfig(urlA, "user-a", "password-a-strong");
        Set<String> observedRequests = ConcurrentHashMap.newKeySet();
        CountDownLatch firstBrowseStarted = new CountDownLatch(1);
        CountDownLatch releaseBrowses = new CountDownLatch(1);

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenAnswer(invocation -> {
                    observedRequests.add(requestIdentity(invocation.getArgument(0), invocation.getArgument(1)));
                    firstBrowseStarted.countDown();
                    await(releaseBrowses);
                    return ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of()));
                });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<Map<String, Long>> batch = caller.submit(
                () -> service.calculateDirectorySizes(List.of("/one", "/two")));
        try {
            assertThat(firstBrowseStarted.await(2, TimeUnit.SECONDS)).isTrue();
            service.updateConfig(urlB, "user-b", "password-b-strong");
        } finally {
            releaseBrowses.countDown();
        }

        try {
            assertThat(batch.get(5, TimeUnit.SECONDS)).containsEntry("/one", 0L).containsEntry("/two", 0L);
        } finally {
            caller.shutdownNow();
        }
        assertThat(observedRequests).containsExactly(
                urlA + "/files/browse|user-a:password-a-strong");
        assertThat(service.getCachedDirectorySize("/one")).isNull();
        assertThat(service.getCachedDirectorySize("/two")).isNull();
    }

    @Test
    void nodeFailureReturnsOnlyGenericClientMessage() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenThrow(new RestClientException("token=super-secret; internal response"));

        assertThatThrownBy(() -> service.browseDirectory("/team"))
                .isInstanceOf(AsperaNodeService.NodeApiException.class)
                .hasMessage("Unable to load files from the Node service.")
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void browseFiltersUnsafeNodeEntries() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        AsperaNodeService.NodeListResponse response = new AsperaNodeService.NodeListResponse(List.of(
                new AsperaNodeService.NodeFileItem("safe.txt", "file", 10, null),
                new AsperaNodeService.NodeFileItem("other.txt ", "file", 15, null),
                new AsperaNodeService.NodeFileItem("child ", "directory", 0, null),
                new AsperaNodeService.NodeFileItem("../escape", "file", 20, null),
                new AsperaNodeService.NodeFileItem("link", "symlink", 30, null)));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        assertThat(service.browseDirectory("/team"))
                .containsExactly(new AsperaNodeService.FileItem("safe.txt", "file", 10, null));
    }

    @Test
    void boundaryWhitespaceIsRejectedWithoutRewritingDeleteTransferOrRecursiveTargets() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");

        assertThatThrownBy(() -> service.deleteFile("/team/file.txt "))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("whitespace");
        assertThat(service.generateMultiFileTransferSpec("receive", List.of("/team/file.txt ")))
                .containsKey("error");
        assertThat(service.calculateDirectorySize("/team ")).isEqualTo(-1);
        assertThatThrownBy(() -> service.browseDirectory(" "))
                .isInstanceOf(AsperaNodeService.NodeApiException.class);
        assertThatThrownBy(() -> service.createFolder("/team", "folder "))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("whitespace");

        verify(restTemplate, never()).postForEntity(
                anyString(), any(HttpEntity.class), eq(AsperaNodeService.NodeListResponse.class));
        verify(restTemplate, never()).postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
        verify(restTemplate, never()).exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void browseRejectsRedirectResponseInsteadOfTreatingItAsAnEmptyDirectory() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .body(new AsperaNodeService.NodeListResponse(List.of())));

        assertThatThrownBy(() -> service.browseDirectory("/team"))
                .isInstanceOf(AsperaNodeService.NodeApiException.class)
                .hasMessage("Unable to load files from the Node service.");
    }

    @Test
    void deleteRejectsRedirectResponseInsteadOfReportingSuccess() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of(
                        new AsperaNodeService.NodeFileItem("file.txt", "file", 7, null)))));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).body(""));

        assertThatThrownBy(() -> service.deleteFile("/team/file.txt"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("The file could not be deleted from the Node service.");
    }

    @Test
    void createFolderRejectsRedirectResponseInsteadOfReportingSuccess() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).body(""));

        assertThatThrownBy(() -> service.createFolder("/team", "folder"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("The folder could not be created on the Node service.");
    }

    @Test
    void directoryTraversalRejectsRedirectResponseInsteadOfCachingZero() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .body(new AsperaNodeService.NodeListResponse(List.of())));

        assertThat(service.calculateDirectorySize("/team")).isEqualTo(-1);
        assertThat(service.getCachedDirectorySize("/team")).isNull();
    }

    @Test
    void transferSetupRejectsRedirectResponseWithGenericUpstreamError() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).body("redirected"));

        assertThat(service.generateMultiFileTransferSpec("receive", List.of("/team/file.txt")))
                .containsEntry("error", "The Node transfer service could not complete the request.");
    }

    @Test
    void transferValidationRunsBeforeNodeRequest() {
        assertThat(service.generateMultiFileTransferSpec("other", List.of("/team")))
                .containsEntry("error", "Direction must be 'send' or 'receive'.");
        assertThat(service.generateMultiFileTransferSpec("receive", List.of("/team/../secret")))
                .containsKey("error");

        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void validTransferResponseSetsTokenAuthenticationWithoutLoggingBody() {
        service.updateConfig("https://node.example.com:9092", "node", "strong-password");
        String responseBody = "{\"transfer_specs\":[{\"transfer_spec\":{"
                + "\"remote_host\":\"node.example.com\",\"token\":\"transfer-token\"}}]}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        Map<String, Object> spec = service.generateMultiFileTransferSpec("receive", List.of("/team/file.txt"));

        assertThat(spec).containsEntry("authentication", "token")
                .containsEntry("remote_host", "node.example.com")
                .containsEntry("token", "transfer-token");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static String requestIdentity(String url, HttpEntity<?> request) {
        String authorization = request.getHeaders().getFirst("Authorization");
        String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)),
                StandardCharsets.UTF_8);
        return url + "|" + credentials;
    }
}
