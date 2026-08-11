(function () {
    "use strict";

    function preferredTheme() {
        try {
            var savedTheme = localStorage.getItem("theme");
            if (savedTheme === "light" || savedTheme === "dark") {
                return savedTheme;
            }
        } catch (ignored) {
            // Storage can be unavailable in hardened/private browser contexts.
        }

        return window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches
            ? "light"
            : "dark";
    }

    function updateThemeControl(theme) {
        var button = document.getElementById("theme-toggle");
        var icon = document.getElementById("theme-icon");
        var nextTheme = theme === "light" ? "dark" : "light";
        var label = nextTheme === "light" ? "Switch to light theme" : "Switch to dark theme";

        if (icon) {
            icon.textContent = nextTheme === "light" ? "Light" : "Dark";
        }
        if (button) {
            button.setAttribute("aria-label", label);
            button.setAttribute("title", label);
            button.setAttribute("aria-pressed", String(theme === "dark"));
        }
    }

    function toggleTheme() {
        var html = document.documentElement;
        var currentTheme = html.getAttribute("data-theme") === "light" ? "light" : "dark";
        var nextTheme = currentTheme === "light" ? "dark" : "light";
        html.setAttribute("data-theme", nextTheme);
        try {
            localStorage.setItem("theme", nextTheme);
        } catch (ignored) {
            // The selected theme still applies for the current page.
        }
        updateThemeControl(nextTheme);
    }

    var theme = preferredTheme();
    document.documentElement.setAttribute("data-theme", theme);

    document.addEventListener("DOMContentLoaded", function () {
        updateThemeControl(document.documentElement.getAttribute("data-theme") || "dark");
        var button = document.getElementById("theme-toggle");
        if (button) {
            button.addEventListener("click", toggleTheme);
        }
    });
})();
