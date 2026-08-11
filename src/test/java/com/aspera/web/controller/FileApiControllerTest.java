package com.aspera.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aspera.web.service.AsperaNodeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class FileApiControllerTest {

    @Test
    void hidesChildWhoseFullPathExceedsTransportLimit() {
        AsperaNodeService nodeService = mock(AsperaNodeService.class);
        FileApiController controller = new FileApiController(nodeService);
        String parent = "/" + String.join("/", java.util.Collections.nCopies(7, "a".repeat(255)))
                + "/" + "b".repeat(253);
        when(nodeService.browseDirectory(parent)).thenReturn(List.of(
                new AsperaNodeService.FileItem("x", "directory", 0, null),
                new AsperaNodeService.FileItem("xy", "directory", 0, null),
                new AsperaNodeService.FileItem("file.txt", "file", 0, null)));

        ResponseEntity<?> response = controller.listFolders(parent);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<AsperaNodeService.FileItem> folders = (List<AsperaNodeService.FileItem>) response.getBody();
        assertThat(folders).extracting(AsperaNodeService.FileItem::name).containsExactly("x");
    }
}
