package com.aspera.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.service.AsperaNodeService;
import com.aspera.web.service.AsperaNodeService.NodeApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class FileBrowserControllerTest {

    @Mock
    private AsperaNodeService nodeService;

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FileBrowserController(nodeService, userRepository)).build();
        principal = new UsernamePasswordAuthenticationToken("alice", "ignored");
    }

    @Test
    void invalidPathRendersBrowserErrorWithoutCallingNode() throws Exception {
        mockMvc.perform(get("/files").principal(principal).param("path", "/team/../secret"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-browser"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("files", List.of()));

        verify(nodeService, never()).browseDirectory("/team/../secret");
    }

    @Test
    void paginationParametersCannotCauseDivisionOrIndexFailure() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        when(nodeService.browseDirectory("/")).thenReturn(List.of());

        mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("size", "0")
                        .param("page", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", 1))
                .andExpect(model().attribute("currentPage", 1));
    }

    @Test
    void paginationModelContainsOnlyBoundedPageNumbers() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        List<AsperaNodeService.FileItem> items = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> new AsperaNodeService.FileItem(
                        "file-" + index, "file", index, "2026-01-01T00:00:00Z"))
                .toList();
        when(nodeService.browseDirectory("/")).thenReturn(items);

        mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("size", "1")
                        .param("page", "500"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalPages", 1000))
                .andExpect(model().attribute("pageNumbers", List.of(1, 498, 499, 500, 501, 502, 1000)));
    }

    @Test
    void nodeFailureStaysOnBrowserWithSafeError() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        when(nodeService.browseDirectory("/team"))
                .thenThrow(new NodeApiException("Unable to load files from the Node service."));

        mockMvc.perform(get("/files").principal(principal).param("path", "/team"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-browser"))
                .andExpect(model().attribute("error", "Unable to load files from the Node service."));
    }

    @Test
    void siblingPrefixIsFilteredFromFolderListing() throws Exception {
        User user = new User("alice", "encoded", "USER");
        user.addPermission(new FolderPermission("/team", false, true, false, false));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(nodeService.browseDirectory("/")).thenReturn(List.of(
                new AsperaNodeService.FileItem("team", "directory", 0, null),
                new AsperaNodeService.FileItem("team2", "directory", 0, null)));

        MvcResult result = mockMvc.perform(get("/files").principal(principal).param("path", "/"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FileBrowserController.DisplayFileItem> files =
                (List<FileBrowserController.DisplayFileItem>) result.getModelAndView().getModel().get("files");
        assertThat(files).extracting(FileBrowserController.DisplayFileItem::name).containsExactly("team");
    }

    @Test
    void directorySizeEndpointRejectsUnauthorizedPath() throws Exception {
        User user = new User("alice", "encoded", "USER");
        user.addPermission(new FolderPermission("/team", false, true, false, false));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/files/dir-sizes")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"/team2/private\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("A requested path is not permitted."));

        verify(nodeService, never()).calculateDirectorySizes(List.of("/team2/private"));
    }

    @Test
    void directorySizeEndpointCannotRecurseIntoAncestorOfAllowedRoot() throws Exception {
        User user = new User("alice", "encoded", "USER");
        user.addPermission(new FolderPermission("/team/project", false, true, false, false));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/files/dir-sizes")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"/team\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("A requested path is not permitted."));

        verify(nodeService, never()).calculateDirectorySizes(anyList());
    }

    @Test
    void sizeSortDoesNotRecurseIntoAncestorOfAllowedRoot() throws Exception {
        User user = new User("alice", "encoded", "USER");
        user.addPermission(new FolderPermission("/team/project", false, true, false, false));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(nodeService.browseDirectory("/"))
                .thenReturn(List.of(new AsperaNodeService.FileItem("team", "directory", 0, null)));

        mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("path", "/")
                        .param("sort", "size"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-browser"));

        verify(nodeService, never()).calculateDirectorySizes(anyList());
        verify(nodeService, never()).calculateDirectorySize(anyString());
        verify(nodeService, never()).getCachedDirectorySize(anyString());
    }

    @Test
    void sizeSortKeepsMissingBatchResultUnknownWithoutSynchronousFallback() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        when(nodeService.browseDirectory("/"))
                .thenReturn(List.of(new AsperaNodeService.FileItem("large-folder", "directory", 0, null)));
        when(nodeService.calculateDirectorySizes(List.of("/large-folder"))).thenReturn(Map.of());

        MvcResult result = mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("path", "/")
                        .param("sort", "size"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-browser"))
                .andExpect(model().attribute("sizeSortPartial", true))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FileBrowserController.DisplayFileItem> files =
                (List<FileBrowserController.DisplayFileItem>) result.getModelAndView().getModel().get("files");
        assertThat(files).singleElement()
                .extracting(FileBrowserController.DisplayFileItem::size)
                .isEqualTo("...");
        verify(nodeService).calculateDirectorySizes(List.of("/large-folder"));
        verify(nodeService, never()).calculateDirectorySize(anyString());
        verify(nodeService, never()).getCachedDirectorySize(anyString());
    }

    @Test
    void sizeSortPlacesUnknownBatchResultsAfterKnownSizesInDescendingOrder() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        when(nodeService.browseDirectory("/"))
                .thenReturn(List.of(
                        new AsperaNodeService.FileItem("unknown-folder", "directory", Long.MAX_VALUE, null),
                        new AsperaNodeService.FileItem("known-large", "directory", 0, null),
                        new AsperaNodeService.FileItem("known-small", "directory", 0, null)));
        when(nodeService.calculateDirectorySizes(
                List.of("/unknown-folder", "/known-large", "/known-small")))
                .thenReturn(Map.of("/known-large", 200L, "/known-small", 100L));

        MvcResult result = mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("path", "/")
                        .param("sort", "size")
                        .param("order", "desc"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-browser"))
                .andExpect(model().attribute("sizeSortPartial", true))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FileBrowserController.DisplayFileItem> files =
                (List<FileBrowserController.DisplayFileItem>) result.getModelAndView().getModel().get("files");
        assertThat(files).extracting(FileBrowserController.DisplayFileItem::name)
                .containsExactly("known-large", "known-small", "unknown-folder");
        assertThat(files.get(2).size()).isEqualTo("...");
        verify(nodeService, never()).calculateDirectorySize(anyString());
        verify(nodeService, never()).getCachedDirectorySize(anyString());
    }

    @Test
    void normalNameSortStillUsesOnlyFastDirectorySizeCache() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        when(nodeService.browseDirectory("/"))
                .thenReturn(List.of(new AsperaNodeService.FileItem("cached-folder", "directory", 0, null)));
        when(nodeService.getCachedDirectorySize("/cached-folder")).thenReturn(2048L);

        MvcResult result = mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("path", "/"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FileBrowserController.DisplayFileItem> files =
                (List<FileBrowserController.DisplayFileItem>) result.getModelAndView().getModel().get("files");
        assertThat(files).singleElement()
                .extracting(FileBrowserController.DisplayFileItem::size)
                .isEqualTo("2.0 KB");
        verify(nodeService).getCachedDirectorySize("/cached-folder");
        verify(nodeService, never()).calculateDirectorySizes(anyList());
        verify(nodeService, never()).calculateDirectorySize(anyString());
    }

    @Test
    void childPathBeyondUtf8LimitRemainsVisibleButCannotTriggerNodeActions() throws Exception {
        User admin = new User("alice", "encoded", "ADMIN");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(admin));
        java.util.ArrayList<String> segments = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            segments.add("a".repeat(255));
        }
        segments.add("b".repeat(254));
        String nearLimitParent = "/" + String.join("/", segments);
        when(nodeService.browseDirectory(nearLimitParent))
                .thenReturn(List.of(new AsperaNodeService.FileItem("x", "directory", 0, null)));

        MvcResult result = mockMvc.perform(get("/files")
                        .principal(principal)
                        .param("path", nearLimitParent)
                        .param("sort", "size"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-browser"))
                .andExpect(model().attribute("invalidChildPathsPresent", true))
                .andExpect(model().attribute("sizeSortPartial", true))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FileBrowserController.DisplayFileItem> files =
                (List<FileBrowserController.DisplayFileItem>) result.getModelAndView().getModel().get("files");
        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.name()).isEqualTo("x");
            assertThat(file.path()).isEmpty();
            assertThat(file.size()).isEqualTo("...");
            assertThat(file.pathSafe()).isFalse();
            assertThat(file.canDelete()).isFalse();
            assertThat(file.canDownload()).isFalse();
        });
        verify(nodeService, never()).calculateDirectorySizes(anyList());
        verify(nodeService, never()).calculateDirectorySize(anyString());
        verify(nodeService, never()).getCachedDirectorySize(anyString());
    }
}
