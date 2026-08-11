(function () {
    "use strict";

    var form = document.getElementById("createUserForm");
    var password = document.getElementById("password");
    if (!form || !password) return;

    function validatePasswordBytes() {
        var byteLength = new TextEncoder().encode(password.value).length;
        password.setCustomValidity(byteLength > 72
            ? "Password must be no more than 72 UTF-8 bytes."
            : "");
    }

    password.addEventListener("input", validatePasswordBytes);
    form.addEventListener("submit", validatePasswordBytes);
})();
