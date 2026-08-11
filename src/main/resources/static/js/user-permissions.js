(function () {
    "use strict";

    var page = document.body;
    var userId = String(page.dataset.userId || "");
    var foldersUrl = page.dataset.foldersUrl || "/admin/api/folders";
    var permissionsUrl = page.dataset.permissionsUrl || ("/admin/users/" + encodeURIComponent(userId) + "/permissions");
    var existingPaths = new Set(Array.from(document.querySelectorAll("[data-existing-path]"), function (row) {
        return row.dataset.existingPath;
    }).filter(Boolean));
    var MAX_SELECTED_PATHS = 100;
    var MAX_RENDERED_FOLDERS = 200;
    var allowedPermissionFields = new Set(["canUpload", "canDownload", "canCreateFolder", "canDelete"]);
    var selectedPathsSet = new Set();
    var currentPath = "/";
    var currentFolders = [];
    var sortDescending = true;
    var modalOpener = null;
    var folderRequestController = null;

    function showPermissionStatus(message, isError) {
        var status = document.getElementById("permissionStatus");
        status.textContent = message;
        status.className = "alert " + (isError ? "alert-error" : "alert-success");
        status.setAttribute("role", isError ? "alert" : "status");
        status.hidden = false;
    }

    async function updatePermission(permissionId, field, checkbox) {
        var checked = checkbox.checked;
        var tokenMeta = document.querySelector("meta[name='_csrf']");
        var headerMeta = document.querySelector("meta[name='_csrf_header']");

        if (!permissionId || !allowedPermissionFields.has(field) || !tokenMeta || !headerMeta) {
            checkbox.checked = !checked;
            showPermissionStatus("The permission could not be saved. Refresh the page and try again.", true);
            return;
        }

        var headers = { "Content-Type": "application/json", "Accept": "application/json" };
        headers[headerMeta.content] = tokenMeta.content;
        checkbox.disabled = true;
        checkbox.setAttribute("aria-busy", "true");

        try {
            var response = await fetch(permissionsUrl + "/" + encodeURIComponent(permissionId), {
                method: "PUT",
                headers: headers,
                body: JSON.stringify({ [field]: checked })
            });
            if (!response.ok) {
                throw new Error("request-failed");
            }
            showPermissionStatus("Permission updated.", false);
        } catch (error) {
            checkbox.checked = !checked;
            showPermissionStatus("The permission could not be saved. Your previous setting was restored.", true);
        } finally {
            checkbox.disabled = false;
            checkbox.removeAttribute("aria-busy");
        }
    }

    function normalizePath(path) {
        var value = typeof path === "string" ? path.replace(/\\/g, "/") : "/";
        var parts = [];
        value.split("/").forEach(function (part) {
            if (!part || part === ".") return;
            if (part === "..") {
                parts.pop();
            } else {
                parts.push(part);
            }
        });
        return "/" + parts.join("/");
    }

    function updateSelectedCount() {
        document.getElementById("selectedCount").textContent = String(selectedPathsSet.size);
        var atLimit = selectedPathsSet.size >= MAX_SELECTED_PATHS;
        document.querySelectorAll("#folderList input[type='checkbox']").forEach(function (checkbox) {
            checkbox.disabled = atLimit && !checkbox.checked;
        });
    }

    function setFolderListState(message, className) {
        var list = document.getElementById("folderList");
        var state = document.createElement("div");
        state.className = className;
        state.textContent = message;
        list.replaceChildren(state);
    }

    function folderPath(name) {
        return normalizePath((currentPath === "/" ? "" : currentPath) + "/" + name);
    }

    function isTransportSafePath(path) {
        return new TextEncoder().encode(path).length <= 2048;
    }

    function renderFolders(folders) {
        var list = document.getElementById("folderList");
        var pathLimitOmitted = folders.some(function (folder) {
            return !isTransportSafePath(folderPath(folder.name));
        });
        var visibleFolders = folders.filter(function (folder) {
            var path = folderPath(folder.name);
            return isTransportSafePath(path) && !existingPaths.has(path);
        });

        if (visibleFolders.length === 0) {
            var message = pathLimitOmitted
                ? "No selectable folders fit within the 2048-byte path limit."
                : (folders.length > 0
                    ? "Every folder shown here already has a permission."
                    : "No matching subfolders were found.");
            setFolderListState(message, "empty-state");
            return;
        }

        var fragment = document.createDocumentFragment();
        visibleFolders.slice(0, MAX_RENDERED_FOLDERS).forEach(function (folder) {
            var fullPath = folderPath(folder.name);
            var item = document.createElement("div");
            item.className = "folder-item";

            var choice = document.createElement("label");
            choice.className = "folder-choice";

            var checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.checked = selectedPathsSet.has(fullPath);
            checkbox.disabled = selectedPathsSet.size >= MAX_SELECTED_PATHS && !checkbox.checked;
            checkbox.setAttribute("aria-label", "Select " + folder.name);
            checkbox.addEventListener("change", function () {
                if (checkbox.checked && selectedPathsSet.size >= MAX_SELECTED_PATHS) {
                    checkbox.checked = false;
                    showPermissionStatus("You can grant permissions to up to 100 folders at a time.", true);
                } else if (checkbox.checked) {
                    selectedPathsSet.add(fullPath);
                } else {
                    selectedPathsSet.delete(fullPath);
                }
                updateSelectedCount();
            });

            var icon = document.createElement("span");
            icon.textContent = "📁";
            icon.setAttribute("aria-hidden", "true");

            var name = document.createElement("span");
            name.className = "folder-name";
            name.textContent = folder.name;

            var openButton = document.createElement("button");
            openButton.type = "button";
            openButton.className = "btn btn-secondary btn-sm";
            openButton.textContent = "Open";
            openButton.setAttribute("aria-label", "Open " + folder.name);
            openButton.addEventListener("click", function () {
                loadFolders(fullPath);
            });

            choice.append(checkbox, icon, name);
            item.append(choice, openButton);
            fragment.appendChild(item);
        });
        if (visibleFolders.length > MAX_RENDERED_FOLDERS) {
            var limitNotice = document.createElement("div");
            limitNotice.className = "helper-text folder-list-limit";
            limitNotice.textContent = "Showing the first " + MAX_RENDERED_FOLDERS + " of "
                + visibleFolders.length + " folders. Refine the search to find another folder.";
            fragment.appendChild(limitNotice);
        }
        if (pathLimitOmitted) {
            var pathNotice = document.createElement("div");
            pathNotice.className = "helper-text folder-list-limit";
            pathNotice.textContent = "Some folders are hidden because their full path exceeds the 2048-byte limit.";
            fragment.appendChild(pathNotice);
        }
        list.replaceChildren(fragment);
    }

    async function loadFolders(path) {
        if (path === "..") {
            var parts = currentPath.split("/").filter(Boolean);
            parts.pop();
            currentPath = "/" + parts.join("/");
        } else {
            currentPath = normalizePath(path);
        }

        document.getElementById("currentModalPath").textContent = currentPath;
        var list = document.getElementById("folderList");
        list.setAttribute("aria-busy", "true");
        setFolderListState("Loading folders…", "loading-state");

        if (folderRequestController) {
            folderRequestController.abort();
        }
        var requestController = new AbortController();
        folderRequestController = requestController;

        try {
            var url = new URL(foldersUrl, window.location.origin);
            url.searchParams.set("path", currentPath);
            var response = await fetch(url.toString(), {
                signal: requestController.signal,
                headers: { "Accept": "application/json" }
            });
            if (!response.ok) {
                throw new Error("request-failed");
            }
            var value = await response.json();
            currentFolders = Array.isArray(value)
                ? value.filter(function (folder) { return folder && typeof folder.name === "string"; })
                : [];
            renderFolders(currentFolders);
        } catch (error) {
            if (error.name !== "AbortError") {
                currentFolders = [];
                setFolderListState("Folders could not be loaded. Check the Node connection and try again.", "error-state");
            }
        } finally {
            if (folderRequestController === requestController) {
                folderRequestController = null;
                list.setAttribute("aria-busy", "false");
            }
        }
    }

    function openFolderModal() {
        var modal = document.getElementById("folderModal");
        modalOpener = document.activeElement;
        modal.hidden = false;
        document.body.classList.add("modal-open");
        updateSelectedCount();
        loadFolders(currentPath);
        requestAnimationFrame(function () {
            document.getElementById("folderSearch").focus();
        });
    }

    function closeFolderModal() {
        var modal = document.getElementById("folderModal");
        modal.hidden = true;
        document.body.classList.remove("modal-open");
        if (folderRequestController) {
            folderRequestController.abort();
            folderRequestController = null;
        }
        if (modalOpener && typeof modalOpener.focus === "function") {
            modalOpener.focus();
        }
    }

    function filterFolders() {
        var query = document.getElementById("folderSearch").value.trim().toLocaleLowerCase();
        var filtered = currentFolders.filter(function (folder) {
            return folder.name.toLocaleLowerCase().includes(query);
        });
        renderFolders(filtered);
    }

    function sortFolders() {
        sortDescending = !sortDescending;
        currentFolders.sort(function (left, right) {
            var result = left.name.localeCompare(right.name, undefined, { sensitivity: "base" });
            return sortDescending ? -result : result;
        });
        var button = document.getElementById("folderSortButton");
        button.textContent = sortDescending ? "Z–A" : "A–Z";
        button.setAttribute("aria-label", sortDescending ? "Folders sorted Z to A" : "Folders sorted A to Z");
        filterFolders();
    }

    function renderSelectedFolders() {
        var container = document.getElementById("selectedFoldersContainer");
        var inputsContainer = document.getElementById("hiddenPathsInputs");
        container.replaceChildren();
        inputsContainer.replaceChildren();

        if (selectedPathsSet.size === 0) {
            var empty = document.createElement("div");
            empty.className = "empty-state";
            empty.textContent = "No folders selected.";
            container.appendChild(empty);
            return;
        }

        if (selectedPathsSet.size > MAX_SELECTED_PATHS) {
            showPermissionStatus("You can grant permissions to up to 100 folders at a time.", true);
            return;
        }

        selectedPathsSet.forEach(function (path) {
            var tag = document.createElement("span");
            tag.className = "selected-tag";

            var text = document.createElement("span");
            text.className = "selected-tag-text";
            text.textContent = path;

            var removeButton = document.createElement("button");
            removeButton.type = "button";
            removeButton.textContent = "×";
            removeButton.setAttribute("aria-label", "Remove " + path);
            removeButton.addEventListener("click", function () {
                selectedPathsSet.delete(path);
                renderSelectedFolders();
                updateSelectedCount();
            });

            var input = document.createElement("input");
            input.type = "hidden";
            input.name = "paths";
            input.value = path;

            tag.append(text, removeButton);
            container.appendChild(tag);
            inputsContainer.appendChild(input);
        });
    }

    function confirmFolderSelection() {
        renderSelectedFolders();
        closeFolderModal();
    }

    document.querySelectorAll(".permission-toggle").forEach(function (checkbox) {
        checkbox.addEventListener("change", function () {
            updatePermission(checkbox.dataset.permissionId, checkbox.dataset.permissionField, checkbox);
        });
    });

    document.querySelectorAll(".confirm-permission-removal").forEach(function (form) {
        form.addEventListener("submit", function (event) {
            if (!window.confirm("Remove every capability assigned to this folder?")) {
                event.preventDefault();
            }
        });
    });

    document.getElementById("openFolderPicker").addEventListener("click", openFolderModal);
    document.getElementById("closeFolderPicker").addEventListener("click", closeFolderModal);
    document.getElementById("cancelFolderPicker").addEventListener("click", closeFolderModal);
    document.getElementById("confirmFolderSelection").addEventListener("click", confirmFolderSelection);
    document.getElementById("folderSearch").addEventListener("input", filterFolders);
    document.getElementById("folderSortButton").addEventListener("click", sortFolders);
    document.getElementById("refreshFolders").addEventListener("click", function () {
        loadFolders(currentPath);
    });
    document.getElementById("folderUp").addEventListener("click", function () {
        loadFolders("..");
    });

    document.getElementById("addPermissionForm").addEventListener("submit", function (event) {
        if (selectedPathsSet.size === 0) {
            event.preventDefault();
            showPermissionStatus("Select at least one folder before granting permissions.", true);
            document.getElementById("openFolderPicker").focus();
            return;
        }

        var capability = this.querySelector("input[type='checkbox']:checked");
        if (!capability) {
            event.preventDefault();
            showPermissionStatus("Select at least one allowed action.", true);
            this.querySelector("input[type='checkbox']").focus();
        }
    });

    document.getElementById("folderModal").addEventListener("click", function (event) {
        if (event.target === this) closeFolderModal();
    });

    document.getElementById("folderModal").addEventListener("keydown", function (event) {
        if (event.key === "Escape") {
            event.preventDefault();
            closeFolderModal();
            return;
        }
        if (event.key !== "Tab") return;

        var focusable = Array.from(this.querySelectorAll(
            "button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]"
        )).filter(function (element) { return element.offsetParent !== null; });
        if (focusable.length === 0) return;
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });
})();
