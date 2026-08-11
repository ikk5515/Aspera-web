package com.aspera.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AsperaNodeServiceResourceLimitTest {

    private static final String NODE_URL = "https://node.example.com:9092";

    @Mock
    private RestTemplate restTemplate;

    private AsperaNodeService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownDirectorySizeExecutor();
        }
    }

    @Test
    void wholeTraversalItemBudgetReturnsUnknownInsteadOfContinuingRecursion() {
        service = limitedService(64, 2_000, 2, 100, 512,
                TimeUnit.SECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(5));
        AsperaNodeService.NodeListResponse root = new AsperaNodeService.NodeListResponse(List.of(
                new AsperaNodeService.NodeFileItem("a", "directory", 0, null),
                new AsperaNodeService.NodeFileItem("b", "directory", 0, null)));
        AsperaNodeService.NodeListResponse child = new AsperaNodeService.NodeListResponse(List.of(
                new AsperaNodeService.NodeFileItem("file.txt", "file", 10, null)));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.ok(root), ResponseEntity.ok(child));

        assertThat(service.calculateDirectorySize("/team")).isEqualTo(-1);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class));
    }

    @Test
    void wholeTraversalDirectoryBudgetReturnsUnknownBeforeOpeningAnotherDirectory() {
        service = limitedService(64, 1, 100, 100, 512,
                TimeUnit.SECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(5));
        AsperaNodeService.NodeListResponse root = new AsperaNodeService.NodeListResponse(List.of(
                new AsperaNodeService.NodeFileItem("child", "directory", 0, null)));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.ok(root));

        assertThat(service.calculateDirectorySize("/team")).isEqualTo(-1);
        verify(restTemplate, times(1)).postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class));
    }

    @Test
    void traversalDeadlineReturnsUnknownWithoutCallingNode() {
        service = limitedService(64, 100, 100, 100, 512,
                0, TimeUnit.SECONDS.toNanos(5));

        assertThat(service.calculateDirectorySize("/team")).isEqualTo(-1);
        verify(restTemplate, never()).postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class));
    }

    @Test
    void batchDeadlineCancelsRunningWorkAndDoesNotJoinIndefinitely() throws Exception {
        service = limitedService(64, 100, 100, 100, 512,
                TimeUnit.SECONDS.toNanos(5), TimeUnit.MILLISECONDS.toNanos(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        interrupted.countDown();
                        throw new RestClientException("interrupted");
                    }
                    return ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of()));
                });

        long startedAt = System.nanoTime();
        Map<String, Long> result;
        try {
            result = service.calculateDirectorySizes(List.of("/slow"));
        } finally {
            release.countDown();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(result).containsEntry("/slow", -1L);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void saturatedBoundedExecutorRejectsWorkAsUnknown() throws Exception {
        service = defaultService();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(
                service, "directorySizeExecutor");
        assertThat(executor).isNotNull();

        CountDownLatch activeWorkers = new CountDownLatch(executor.getMaximumPoolSize());
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            activeWorkers.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            for (int i = 0; i < executor.getMaximumPoolSize(); i++) {
                executor.submit(blocker);
            }
            assertThat(activeWorkers.await(1, TimeUnit.SECONDS)).isTrue();

            int queueCapacity = executor.getQueue().remainingCapacity();
            assertThat(queueCapacity).isPositive();
            for (int i = 0; i < queueCapacity; i++) {
                executor.submit(blocker);
            }
            assertThat(executor.getQueue().remainingCapacity()).isZero();

            assertThat(service.calculateDirectorySizes(List.of("/rejected")))
                    .containsEntry("/rejected", -1L);
            verify(restTemplate, never()).postForEntity(anyString(), any(HttpEntity.class),
                    eq(AsperaNodeService.NodeListResponse.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    void directorySizeCacheNeverExceedsItsEntryLimit() {
        service = limitedService(64, 100, 100, 100, 2,
                TimeUnit.SECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(5));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.ok(new AsperaNodeService.NodeListResponse(List.of())));

        assertThat(service.calculateDirectorySize("/one")).isZero();
        assertThat(service.calculateDirectorySize("/two")).isZero();
        assertThat(service.calculateDirectorySize("/three")).isZero();

        long cachedEntryCount = List.of("/one", "/two", "/three").stream()
                .filter(path -> service.getCachedDirectorySize(path) != null)
                .count();
        assertThat(cachedEntryCount).isEqualTo(2);
    }

    @Test
    void oversizedNodeBrowseResponseIsRejectedBeforeTraversalUsesIt() {
        service = limitedService(64, 100, 100, 2, 512,
                TimeUnit.SECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(5));
        AsperaNodeService.NodeListResponse response = new AsperaNodeService.NodeListResponse(List.of(
                new AsperaNodeService.NodeFileItem("one", "file", 1, null),
                new AsperaNodeService.NodeFileItem("two", "file", 1, null),
                new AsperaNodeService.NodeFileItem("three", "file", 1, null)));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(AsperaNodeService.NodeListResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        assertThatThrownBy(() -> service.browseDirectory("/team"))
                .isInstanceOf(AsperaNodeService.NodeApiException.class)
                .hasCauseInstanceOf(AsperaNodeService.NodeApiException.class);
    }

    private AsperaNodeService defaultService() {
        return new AsperaNodeService(
                restTemplate, new ObjectMapper(), NODE_URL, "node", "strong-password", NODE_URL);
    }

    private AsperaNodeService limitedService(int maximumDepth,
            int maximumDirectories,
            int maximumItems,
            int maximumBrowseItems,
            int maximumCacheEntries,
            long traversalTimeoutNanos,
            long batchTimeoutNanos) {
        return new AsperaNodeService(
                restTemplate, new ObjectMapper(), NODE_URL, "node", "strong-password", NODE_URL) {
            @Override
            int maximumTraversalDepth() {
                return maximumDepth;
            }

            @Override
            int maximumTraversalDirectories() {
                return maximumDirectories;
            }

            @Override
            int maximumTraversalItems() {
                return maximumItems;
            }

            @Override
            int maximumNodeBrowseItems() {
                return maximumBrowseItems;
            }

            @Override
            int maximumDirectorySizeCacheEntries() {
                return maximumCacheEntries;
            }

            @Override
            long directoryTraversalTimeoutNanos() {
                return traversalTimeoutNanos;
            }

            @Override
            long directorySizeBatchTimeoutNanos() {
                return batchTimeoutNanos;
            }
        };
    }
}
