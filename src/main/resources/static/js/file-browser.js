(function () {
    "use strict";

    var page = document.getElementById("fileBrowserPage");
    if (!page) return;

    var currentDirectory = page.dataset.currentDirectory || "/";
    var endpoints = {
        directorySizes: page.dataset.directorySizesUrl || "/files/dir-sizes",
        transferSpec: page.dataset.transferSpecUrl || "/files/transfer-spec",
        deleteItem: page.dataset.deleteItemUrl || "/files/delete"
    };
    var asperaWeb;
    var connectInstalled = false;
    var noticeTimer;
    var MAX_TRANSFER_PATHS = 100;
    var MAX_TRANSFER_PATH_UTF8_BYTES = 500 * 1024;

    function csrfHeaders(includeJson) {
        var token = document.querySelector("meta[name='_csrf']");
        var header = document.querySelector("meta[name='_csrf_header']");
        if (!token || !header) return null;
        var headers = {};
        headers[header.content] = token.content;
        if (includeJson) headers["Content-Type"] = "application/json";
        return headers;
    }

    function showNotice(message, isError) {
        var notice = document.getElementById("ui-notification");
        clearTimeout(noticeTimer);
        notice.textContent = message;
        notice.className = "alert ui-notification " + (isError ? "alert-error" : "alert-success");
        notice.setAttribute("role", isError ? "alert" : "status");
        notice.hidden = false;
        if (!isError) {
            noticeTimer = setTimeout(function () { notice.hidden = true; }, 8000);
        }
    }

    async function requestJson(url, options) {
        var response = await fetch(url, options);
        var body = null;
        try {
            body = await response.json();
        } catch (ignored) {
            // A non-JSON error body is intentionally not exposed to the page.
        }
        if (!response.ok || !body) throw new Error("request-failed");
        return body;
    }

    function validateTransferPaths(paths) {
        if (!Array.isArray(paths) || paths.length === 0) {
            return "Select at least one item to transfer.";
        }
        if (paths.length > MAX_TRANSFER_PATHS) {
            return "You can transfer up to 100 items at a time.";
        }
        var pathBytes = new Blob(paths.map(function (path) { return String(path || ""); })).size;
        if (pathBytes > MAX_TRANSFER_PATH_UTF8_BYTES) {
            return "The combined path names are too large. Select fewer items and try again.";
        }
        return null;
    }

    function toggleSortDropdown() {
        var menu = document.getElementById("sortDropdownContent");
        var button = document.getElementById("sortDropdownBtn");
        var willOpen = menu.hidden;
        menu.hidden = !willOpen;
        button.setAttribute("aria-expanded", String(willOpen));
        if (willOpen) {
            var firstControl = menu.querySelector("input, button");
            if (firstControl) firstControl.focus();
        }
    }

    function closeSortDropdown() {
        var menu = document.getElementById("sortDropdownContent");
        var button = document.getElementById("sortDropdownBtn");
        menu.hidden = true;
        button.setAttribute("aria-expanded", "false");
    }

    function enforceSingleSort(activeCheckbox) {
        document.querySelectorAll(".sort-check").forEach(function (checkbox) {
            if (checkbox !== activeCheckbox) checkbox.checked = false;
        });
    }

    function syncSortRowState() {
        document.querySelectorAll(".sort-option-row").forEach(function (row) {
            var checkbox = row.querySelector(".sort-check");
            var enabled = Boolean(checkbox && checkbox.checked);
            row.classList.toggle("disabled", !enabled);
            row.querySelectorAll(".order-btn").forEach(function (button) {
                button.disabled = !enabled;
                button.setAttribute("aria-disabled", String(!enabled));
                button.setAttribute("aria-pressed", String(button.classList.contains("active")));
            });
        });
    }

    function toggleRowState(checkbox) {
        if (checkbox.checked) enforceSingleSort(checkbox);
        syncSortRowState();
    }

    function normalizeSortSelection() {
        var selected = Array.from(document.querySelectorAll(".sort-check:checked"));
        if (selected.length > 0) enforceSingleSort(selected[0]);
    }

    function setOrder(button, order) {
        if (order !== "asc" && order !== "desc") return;
        var group = button.closest(".order-toggle-group");
        group.querySelector("input[name='order']").value = order;
        group.querySelectorAll(".order-btn").forEach(function (candidate) {
            var active = candidate === button;
            candidate.classList.toggle("active", active);
            candidate.setAttribute("aria-pressed", String(active));
        });
    }

    function submitSort() {
        var form = document.getElementById("sortForm");
        form.querySelectorAll(".sort-option-row").forEach(function (row) {
            var selected = row.querySelector(".sort-check").checked;
            row.querySelector("input[name='order']").disabled = !selected;
        });
        form.requestSubmit();
    }

    async function fetchDirectorySizes() {
        var cells = Array.from(document.querySelectorAll(
            "td.dir-size[data-size-path][data-size-status='pending']"
        ));
        if (cells.length === 0) return;
        var paths = Array.from(new Set(cells.map(function (cell) {
            return cell.dataset.sizePath;
        }).filter(Boolean)));
        var headers = csrfHeaders(true);
        if (paths.length === 0 || !headers) return;

        try {
            var data = await requestJson(endpoints.directorySizes, {
                method: "POST",
                headers: headers,
                body: JSON.stringify({ paths: paths })
            });
            cells.forEach(function (cell) {
                var path = cell.dataset.sizePath;
                if (Object.prototype.hasOwnProperty.call(data, path)) {
                    cell.textContent = data[path];
                    cell.dataset.sizeStatus = "loaded";
                }
            });
        } catch (ignored) {
            cells.forEach(function (cell) {
                cell.textContent = "—";
                cell.dataset.sizeStatus = "error";
                cell.title = "Folder size is unavailable";
            });
        }
    }

    function initializeParentDirectoryLink() {
        var link = document.getElementById("parentDirectoryLink");
        if (!link) return;
        var parts = String(link.dataset.currentPath || "/").split("/").filter(Boolean);
        parts.pop();
        var parentPath = "/" + parts.join("/");
        var url = new URL(link.href, window.location.href);
        url.searchParams.set("path", parentPath);
        link.href = url.pathname + url.search;
    }

    function updateBulkActionState() {
        var checkboxes = Array.from(document.querySelectorAll("input[name='selectedFiles']"));
        var checkedCount = checkboxes.filter(function (checkbox) { return checkbox.checked; }).length;
        var button = document.getElementById("btnDownloadSelected");
        var selectAll = document.getElementById("selectAll");
        var selectableCount = Math.min(checkboxes.length, MAX_TRANSFER_PATHS);
        var limitReached = checkedCount >= MAX_TRANSFER_PATHS;
        button.hidden = checkedCount === 0;
        button.setAttribute("aria-label", "Download " + checkedCount + " selected file" + (checkedCount === 1 ? "" : "s"));
        selectAll.checked = selectableCount > 0 && checkedCount === selectableCount;
        selectAll.indeterminate = checkedCount > 0 && checkedCount < selectableCount;
        selectAll.setAttribute("aria-label", checkboxes.length > MAX_TRANSFER_PATHS
            ? "Select the first 100 items"
            : "Select all items");
        checkboxes.forEach(function (checkbox) {
            checkbox.disabled = limitReached && !checkbox.checked;
            checkbox.setAttribute("aria-disabled", String(checkbox.disabled));
        });
    }

    function toggleSelectAll() {
        var selectAll = document.getElementById("selectAll");
        var checkboxes = Array.from(document.querySelectorAll("input[name='selectedFiles']"));
        checkboxes.forEach(function (checkbox, index) {
            checkbox.checked = selectAll.checked && index < MAX_TRANSFER_PATHS;
        });
        if (selectAll.checked && checkboxes.length > MAX_TRANSFER_PATHS) {
            showNotice("Up to 100 items can be downloaded at once. The first 100 were selected.", true);
        }
        updateBulkActionState();
    }

    function handleFileSelectionChange(checkbox) {
        var checkedCount = document.querySelectorAll("input[name='selectedFiles']:checked").length;
        if (checkedCount > MAX_TRANSFER_PATHS) {
            checkbox.checked = false;
            showNotice("You can download up to 100 items at a time.", true);
        }
        updateBulkActionState();
    }

    function updateConnectStatus(text, state) {
        var textElement = document.getElementById("connect-status-text");
        var button = document.getElementById("connect-status");
        textElement.textContent = text;
        button.classList.remove("connect-status-success", "connect-status-warning", "connect-status-error");
        if (state) button.classList.add("connect-status-" + state);
        button.setAttribute("aria-label", "Aspera Connect status: " + text);
    }

    function installApp() {
        var popup = window.open("https://www.ibm.com/aspera/connect/", "_blank", "noopener,noreferrer");
        if (popup) popup.opener = null;
    }

    function requestConnectInstall() {
        if (asperaWeb && typeof asperaWeb.install === "function") {
            asperaWeb.install();
            showNotice("Follow the Aspera Connect installation prompt, then try again.", false);
        } else {
            showNotice("Aspera Connect is not ready. Use the Connect status button to install it.", true);
        }
    }

    function runConnectLogic() {
        var aw4 = window.AW4;
        var statusTimeout = setTimeout(function () {
            if (!connectInstalled) updateConnectStatus("Not detected", "warning");
        }, 3000);

        try {
            asperaWeb = new aw4.Connect({
                sdkLocation: "https://d3gcli72yxqn2z.cloudfront.net/connect/v4",
                id: "aspera-web-client"
            });
        } catch (ignored) {
            clearTimeout(statusTimeout);
            updateConnectStatus("Unavailable", "error");
            return;
        }

        var statusEvent = aw4.Connect.EVENT.STATUS;
        var extensionInstall = statusEvent.EXTENSION_INSTALL || "extension_install";
        var initializing = statusEvent.INITIALIZING || "initializing";

        asperaWeb.addEventListener(statusEvent, function (eventType, data) {
            var status = eventType;
            if (eventType === "status" || eventType === statusEvent) {
                if (typeof data === "string") status = data;
                else if (data && data.status) status = data.status;
                else if (data && data.type) status = data.type;
            }

            if (status === statusEvent.INITIALIZED || status === "initialized" || status === "INITIALIZED" ||
                status === statusEvent.RUNNING || status === "running" || status === "RUNNING") {
                clearTimeout(statusTimeout);
                connectInstalled = true;
                updateConnectStatus("Ready", "success");
            } else if (status === statusEvent.OUTDATED || status === "outdated" || status === "OUTDATED") {
                clearTimeout(statusTimeout);
                connectInstalled = false;
                updateConnectStatus("Update needed", "warning");
            } else if (status === statusEvent.FAILED || status === "failed" || status === "FAILED") {
                clearTimeout(statusTimeout);
                connectInstalled = false;
                updateConnectStatus("Failed", "error");
            } else if (status === extensionInstall || status === "extension_install" || status === "EXTENSION_INSTALL") {
                clearTimeout(statusTimeout);
                connectInstalled = false;
                updateConnectStatus("Extension needed", "warning");
            } else if (status === initializing || status === "initializing" || status === "INITIALIZING") {
                updateConnectStatus("Checking…", null);
            }
        });

        asperaWeb.initSession("aspera-connect-container");
    }

    function initConnect() {
        var attempts = 0;
        var waitForSdk = setInterval(function () {
            attempts += 1;
            if (window.AW4 && window.AW4.Connect) {
                clearInterval(waitForSdk);
                runConnectLogic();
            } else if (attempts >= 50) {
                clearInterval(waitForSdk);
                updateConnectStatus("Unavailable", "error");
            }
        }, 100);
    }

    function handleUpload() {
        if (!connectInstalled) {
            requestConnectInstall();
            return;
        }

        asperaWeb.showSelectFileDialog({
            success: async function (fileResources) {
                var files = fileResources && fileResources.dataTransfer
                    ? fileResources.dataTransfer.files
                    : [];
                if (!files || files.length === 0) return;
                var pathValidationError = validateTransferPaths([currentDirectory]);
                if (pathValidationError) {
                    showNotice(pathValidationError, true);
                    return;
                }
                var headers = csrfHeaders(true);
                if (!headers) {
                    showNotice("Your session could not be verified. Refresh the page and try again.", true);
                    return;
                }

                try {
                    var spec = await requestJson(endpoints.transferSpec, {
                        method: "POST",
                        headers: headers,
                        body: JSON.stringify({ direction: "send", path: currentDirectory })
                    });
                    if (spec.error || !spec.remote_host) throw new Error("invalid-spec");
                    spec.paths = Array.from(files).map(function (file) {
                        return { source: file.path || file.name };
                    });
                    asperaWeb.startTransfer(spec, {});
                    showNotice("Upload started in Aspera Connect.", false);
                } catch (ignored) {
                    showNotice("The upload could not be started. Check your access and try again.", true);
                }
            },
            error: function () {
                showNotice("The file picker could not be opened. Restart Aspera Connect and try again.", true);
            }
        });
    }

    async function handleDownloadSelected() {
        if (!connectInstalled) {
            requestConnectInstall();
            return;
        }
        var paths = Array.from(document.querySelectorAll("input[name='selectedFiles']:checked"), function (checkbox) {
            return checkbox.value;
        });
        var pathValidationError = validateTransferPaths(paths);
        if (pathValidationError) {
            showNotice(pathValidationError, true);
            return;
        }
        var headers = csrfHeaders(true);
        if (!headers) {
            showNotice("Your session could not be verified. Refresh the page and try again.", true);
            return;
        }

        try {
            var spec = await requestJson(endpoints.transferSpec, {
                method: "POST",
                headers: headers,
                body: JSON.stringify({ direction: "receive", paths: paths })
            });
            if (!spec.remote_host && !spec.transfer_specs) throw new Error("invalid-spec");
            asperaWeb.startTransfer(spec, {});
            showNotice(paths.length + " downloads started in Aspera Connect.", false);
        } catch (ignored) {
            showNotice("The selected downloads could not be started. Check your access and try again.", true);
        }
    }

    async function handleDownload(filename) {
        if (!connectInstalled) {
            requestConnectInstall();
            return;
        }
        var headers = csrfHeaders(true);
        if (!headers) {
            showNotice("Your session could not be verified. Refresh the page and try again.", true);
            return;
        }

        var fullPath = currentDirectory === "/" ? "/" + filename : currentDirectory + "/" + filename;
        var pathValidationError = validateTransferPaths([fullPath]);
        if (pathValidationError) {
            showNotice(pathValidationError, true);
            return;
        }
        try {
            var spec = await requestJson(endpoints.transferSpec, {
                method: "POST",
                headers: headers,
                body: JSON.stringify({ direction: "receive", path: fullPath })
            });
            if (!spec.remote_host) throw new Error("invalid-spec");
            if (Array.isArray(spec.paths) && spec.paths.length > 0) spec.paths[0].destination = filename;
            asperaWeb.startTransfer(spec, {});
            showNotice("Download started in Aspera Connect.", false);
        } catch (ignored) {
            showNotice("The download could not be started. Check your access and try again.", true);
        }
    }

    function handleDelete(filename) {
        if (!window.confirm("Permanently delete “" + filename + "”? This action cannot be undone.")) return;
        var token = document.querySelector("meta[name='_csrf']");
        if (!token) {
            showNotice("Your session could not be verified. Refresh the page and try again.", true);
            return;
        }

        var fullPath = currentDirectory === "/" ? "/" + filename : currentDirectory + "/" + filename;
        var form = document.createElement("form");
        form.method = "POST";
        form.action = endpoints.deleteItem;
        form.hidden = true;

        var pathInput = document.createElement("input");
        pathInput.type = "hidden";
        pathInput.name = "path";
        pathInput.value = fullPath;

        var csrfInput = document.createElement("input");
        csrfInput.type = "hidden";
        csrfInput.name = "_csrf";
        csrfInput.value = token.content;
        form.append(pathInput, csrfInput);
        document.body.appendChild(form);
        form.submit();
    }

    document.getElementById("sortDropdownBtn").addEventListener("click", toggleSortDropdown);
    document.getElementById("applySort").addEventListener("click", submitSort);
    document.getElementById("selectAll").addEventListener("change", toggleSelectAll);
    document.getElementById("btnDownloadSelected").addEventListener("click", handleDownloadSelected);
    document.getElementById("connect-status").addEventListener("click", function () {
        if (!connectInstalled) installApp();
    });

    var uploadButton = document.getElementById("uploadFiles");
    if (uploadButton) uploadButton.addEventListener("click", handleUpload);

    document.querySelectorAll(".sort-check").forEach(function (checkbox) {
        checkbox.addEventListener("change", function () { toggleRowState(checkbox); });
    });
    document.querySelectorAll(".order-btn").forEach(function (button) {
        button.addEventListener("click", function () { setOrder(button, button.dataset.order); });
    });
    document.querySelectorAll(".file-checkbox").forEach(function (checkbox) {
        checkbox.addEventListener("change", function () { handleFileSelectionChange(checkbox); });
    });
    document.querySelectorAll(".btn-download[data-filename]").forEach(function (button) {
        button.addEventListener("click", function () { handleDownload(button.dataset.filename); });
    });
    document.querySelectorAll(".btn-delete[data-filename]").forEach(function (button) {
        button.addEventListener("click", function () { handleDelete(button.dataset.filename); });
    });

    document.addEventListener("click", function (event) {
        if (!event.target.closest("#sortDropdownBtn") && !event.target.closest("#sortDropdownContent")) {
            closeSortDropdown();
        }
    });
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && !document.getElementById("sortDropdownContent").hidden) {
            closeSortDropdown();
            document.getElementById("sortDropdownBtn").focus();
        }
    });

    normalizeSortSelection();
    syncSortRowState();
    initializeParentDirectoryLink();
    updateBulkActionState();
    fetchDirectorySizes();

    if (document.readyState === "complete") initConnect();
    else window.addEventListener("load", initConnect, { once: true });
})();
