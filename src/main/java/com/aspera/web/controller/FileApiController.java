package com.aspera.web.controller;

import com.aspera.web.security.NodePathPolicy;
import com.aspera.web.service.AsperaNodeService;
import com.aspera.web.service.AsperaNodeService.NodeApiException;
import com.aspera.web.service.AsperaNodeService.FileItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/api")
public class FileApiController {

    private final AsperaNodeService asperaNodeService;

    public FileApiController(AsperaNodeService asperaNodeService) {
        this.asperaNodeService = asperaNodeService;
    }

    @GetMapping("/folders")
    public ResponseEntity<?> listFolders(@RequestParam(value = "path", defaultValue = "/") String path) {
        final String normalizedPath;
        try {
            normalizedPath = NodePathPolicy.normalizeAbsolutePath(path);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        try {
            List<FileItem> allItems = asperaNodeService.browseDirectory(normalizedPath);
            List<FileItem> folders = allItems.stream()
                    .filter(item -> "directory".equalsIgnoreCase(item.type()))
                    .filter(item -> isSafeChildPath(normalizedPath, item.name()))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(folders);
        } catch (NodeApiException ex) {
            return ResponseEntity.status(502).body(Map.of("error", ex.getMessage()));
        }
    }

    private boolean isSafeChildPath(String parentPath, String childName) {
        try {
            NodePathPolicy.join(parentPath, childName);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
