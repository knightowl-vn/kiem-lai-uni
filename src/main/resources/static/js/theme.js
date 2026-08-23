(function () {
    const STORAGE_KEY = "kiemlai-theme";

    function getStoredTheme() {
        const storedTheme =
            localStorage.getItem(STORAGE_KEY);

        if (
            storedTheme === "dark"
            || storedTheme === "light"
        ) {
            return storedTheme;
        }

        return null;
    }

    function getSystemTheme() {
        return window.matchMedia(
            "(prefers-color-scheme: dark)"
        ).matches
            ? "dark"
            : "light";
    }

    function getInitialTheme() {
        return getStoredTheme() || getSystemTheme();
    }

    function updateCheckbox(theme) {
        const checkbox =
            document.getElementById(
                "themeToggleCheckbox"
            );

        if (checkbox) {
            checkbox.checked = theme === "dark";
        }
    }

    function updateThemeButtons(theme) {
        const themeButtons = document.querySelectorAll("[data-theme-set]");
        themeButtons.forEach(button => {
            const targetTheme = button.getAttribute("data-theme-set");
            const isActive = targetTheme === theme;
            button.classList.toggle("is-active", isActive);
            button.setAttribute("aria-pressed", String(isActive));
        });
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute(
            "data-theme",
            theme
        );

        /*
         * Có thể đặt thêm thuộc tính Bootstrap.
         */
        document.documentElement.setAttribute(
            "data-bs-theme",
            theme
        );

        updateCheckbox(theme);
        updateThemeButtons(theme);
    }

    /*
     * Áp dụng ngay khi theme.js được tải.
     */
    applyTheme(getInitialTheme());

    document.addEventListener(
        "DOMContentLoaded",
        function () {
            const checkbox =
                document.getElementById(
                    "themeToggleCheckbox"
                );

            const currentTheme =
                document.documentElement.getAttribute(
                    "data-theme"
                ) || getInitialTheme();

            applyTheme(currentTheme);

            if (checkbox) {
                checkbox.addEventListener(
                    "change",
                    function () {
                        const nextTheme =
                            checkbox.checked
                                ? "dark"
                                : "light";

                        try {
                            localStorage.setItem(
                                STORAGE_KEY,
                                nextTheme
                            );
                        } catch (e) {
                            console.warn("Không thể lưu theme.", e);
                        }

                        applyTheme(nextTheme);
                    }
                );
            }

            // Bind click on [data-theme-set="light|dark"] buttons
            document.addEventListener("click", function (event) {
                const target = event.target;
                if (!(target instanceof Element)) {
                    return;
                }
                const themeBtn = target.closest("[data-theme-set]");
                if (themeBtn) {
                    const nextTheme = themeBtn.getAttribute("data-theme-set");
                    if (nextTheme === "light" || nextTheme === "dark") {
                        try {
                            localStorage.setItem(STORAGE_KEY, nextTheme);
                        } catch (e) {
                            console.warn("Không thể lưu theme.", e);
                        }
                        applyTheme(nextTheme);
                    }
                }
            });
        }
    );

    window.addEventListener(
        "storage",
        function (event) {
            if (event.key !== STORAGE_KEY) {
                return;
            }

            const theme =
                event.newValue === "dark"
                    ? "dark"
                    : "light";

            applyTheme(theme);
        }
    );
})();