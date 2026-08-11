package com.aspera.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aspera.web.entity.FolderPermission;
import com.aspera.web.entity.User;
import com.aspera.web.repository.UserRepository;
import com.aspera.web.service.AsperaNodeService;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class FileOperationsControllerTest {

    @Mock
    private AsperaNodeService nodeService;

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FileOperationsController(nodeService, userRepository)).build();
        principal = new UsernamePasswordAuthenticationToken("alice", "ignored");
    }

    @Test
    void rejectsUnknownDirectionBeforePermissionOrNodeCall() throws Exception {
        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"delete\",\"path\":\"/team/file.txt\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid transfer direction."));

        verify(nodeService, never()).generateMultiFileTransferSpec(eq("delete"), anyList());
    }

    @Test
    void rejectsTraversalBeforeNodeCall() throws Exception {
        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"receive\",\"path\":\"/team/../secret\"}"))
                .andExpect(status().isBadRequest());

        verify(nodeService, never()).generateMultiFileTransferSpec(eq("receive"), anyList());
    }

    @Test
    void permissionRootDoesNotMatchSiblingPrefix() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userWithDownloadPermission("/team")));

        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"receive\",\"path\":\"/team2/file.txt\"}"))
                .andExpect(status().isForbidden());

        verify(nodeService, never()).generateMultiFileTransferSpec(eq("receive"), anyList());
    }

    @Test
    void validAuthorizedTransferUsesNormalizedPath() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userWithDownloadPermission("/team")));
        Map<String, Object> nodeSpec = new HashMap<>();
        nodeSpec.put("remote_host", "node.example.com");
        when(nodeService.generateMultiFileTransferSpec("receive", List.of("/team/file.txt")))
                .thenReturn(nodeSpec);

        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"receive\",\"path\":\"/team//file.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remote_host").value("node.example.com"))
                .andExpect(jsonPath("$.cookie").isNotEmpty());

        verify(nodeService).generateMultiFileTransferSpec("receive", List.of("/team/file.txt"));
    }

    @Test
    void longMultiplePathsAreHandledInJsonBody() throws Exception {
        User user = userWithDownloadPermission("/");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        List<String> paths = List.of(
                longPath("a"), longPath("b"), longPath("c"),
                longPath("d"), longPath("e"), longPath("f"));
        String json = "{\"direction\":\"receive\",\"paths\":[\""
                + String.join("\",\"", paths) + "\"]}";
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(8192);

        Map<String, Object> nodeSpec = new HashMap<>();
        nodeSpec.put("remote_host", "node.example.com");
        when(nodeService.generateMultiFileTransferSpec("receive", paths)).thenReturn(nodeSpec);

        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remote_host").value("node.example.com"));

        verify(nodeService).generateMultiFileTransferSpec("receive", paths);
    }

    @Test
    void transferSpecUsesJsonBodyInsteadOfQueryParameters() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userWithDownloadPermission("/team")));
        Map<String, Object> nodeSpec = new HashMap<>();
        nodeSpec.put("remote_host", "node.example.com");
        when(nodeService.generateMultiFileTransferSpec("receive", List.of("/team/body-file.txt")))
                .thenReturn(nodeSpec);

        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .queryParam("direction", "delete")
                        .queryParam("path", "/team/query-file.txt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"receive\",\"path\":\"/team/body-file.txt\"}"))
                .andExpect(status().isOk());

        verify(nodeService).generateMultiFileTransferSpec("receive", List.of("/team/body-file.txt"));
        verify(nodeService, never()).generateMultiFileTransferSpec(eq("delete"), anyList());
    }

    @Test
    void rejectsAmbiguousSingleAndMultiplePathFields() throws Exception {
        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"receive\",\"path\":\"/team/one\","
                                + "\"paths\":[\"/team/two\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Provide either path or paths, not both."));

        verify(nodeService, never()).generateMultiFileTransferSpec(eq("receive"), anyList());
    }

    @Test
    void rejectsIndividualMultibytePathBeyondNodePolicyBeforeNodeCall() throws Exception {
        String path = multibytePath(0);
        String json = "{\"direction\":\"receive\",\"path\":\"" + path + "\"}";
        assertThat(path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(2048);
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(com.aspera.web.security.JsonRequestSizeLimitFilter.MAX_JSON_REQUEST_BYTES);

        mockMvc.perform(post("/files/transfer-spec")
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Path exceeds the 2048-byte UTF-8 limit."));

        verify(nodeService, never()).generateMultiFileTransferSpec(eq("receive"), anyList());
    }

    @Test
    void deleteCannotUseSiblingPrefixPermission() throws Exception {
        User user = userWithDownloadPermission("/team");
        user.getPermissions().get(0).setCanDelete(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/files/delete")
                        .principal(principal)
                        .param("path", "/team2/file.txt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files?path=%2Fteam2"));

        verify(nodeService, never()).deleteFile("/team2/file.txt");
    }

    private User userWithDownloadPermission(String root) {
        User user = new User("alice", "encoded", "USER");
        FolderPermission permission = new FolderPermission(root, false, true, false, false);
        user.addPermission(permission);
        return user;
    }

    private String longPath(String marker) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            path.append('/').append(marker).append("x".repeat(240));
        }
        return path.toString();
    }

    private String multibytePath(int index) {
        StringBuilder path = new StringBuilder("/batch-").append(index);
        String segment = "\uac00".repeat(240);
        for (int i = 0; i < 14; i++) {
            path.append('/').append(segment);
        }
        return path.toString();
    }
}
