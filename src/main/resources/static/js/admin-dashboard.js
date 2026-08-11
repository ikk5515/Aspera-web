(function () {
    "use strict";

    document.querySelectorAll("form.confirm-action[data-confirm-message]").forEach(function (form) {
        form.addEventListener("submit", function (event) {
            if (!window.confirm(form.dataset.confirmMessage)) {
                event.preventDefault();
            }
        });
    });
})();
