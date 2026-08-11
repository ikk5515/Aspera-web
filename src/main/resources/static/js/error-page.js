(function () {
    "use strict";

    var backButton = document.getElementById("error-back");
    var homeLink = document.getElementById("error-home");
    if (!backButton || !homeLink) {
        return;
    }

    backButton.addEventListener("click", function () {
        if (window.history.length > 1) {
            window.history.back();
            return;
        }
        homeLink.click();
    });
})();
