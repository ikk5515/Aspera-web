package com.aspera.web.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "folder_permissions")
public class FolderPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 4096)
    private String path;

    private boolean canUpload;
    private boolean canDownload;
    private boolean canCreateFolder;
    private boolean canDelete;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public FolderPermission() {
    }

    public FolderPermission(String path, boolean canUpload, boolean canDownload, boolean canCreateFolder,
            boolean canDelete) {
        this.path = path;
        this.canUpload = canUpload;
        this.canDownload = canDownload;
        this.canCreateFolder = canCreateFolder;
        this.canDelete = canDelete;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isCanUpload() {
        return canUpload;
    }

    public void setCanUpload(boolean canUpload) {
        this.canUpload = canUpload;
    }

    public boolean isCanDownload() {
        return canDownload;
    }

    public void setCanDownload(boolean canDownload) {
        this.canDownload = canDownload;
    }

    public boolean isCanCreateFolder() {
        return canCreateFolder;
    }

    public void setCanCreateFolder(boolean canCreateFolder) {
        this.canCreateFolder = canCreateFolder;
    }

    public boolean isCanDelete() {
        return canDelete;
    }

    public void setCanDelete(boolean canDelete) {
        this.canDelete = canDelete;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}
