(function () {
    "use strict";

    const key = "illustration-archive.theme.v1";
    let theme = "light";
    try {
        if (window.localStorage.getItem(key) === "dark") theme = "dark";
    } catch (_) { /* Theme selection is optional when storage is unavailable. */ }

    // This script runs before the stylesheet, without waiting for the page DOM.
    document.documentElement.setAttribute("data-theme", theme);

    function bindButtons() {
        const buttons = document.querySelectorAll(".theme-toggle");
        function apply() {
            document.documentElement.setAttribute("data-theme", theme);
            buttons.forEach(button => button.setAttribute("aria-pressed", String(theme === "dark")));
        }
        buttons.forEach(button => {
            button.addEventListener("click", () => {
                theme = theme === "dark" ? "light" : "dark";
                apply();
                try { window.localStorage.setItem(key, theme); }
                catch (_) { /* The current page still switches when saving fails. */ }
            });
            button.hidden = false;
        });
        apply();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindButtons, { once: true });
    } else bindButtons();
})();
