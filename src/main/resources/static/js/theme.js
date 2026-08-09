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

            if (!checkbox) {
                return;
            }

            checkbox.addEventListener(
                "change",
                function () {
                    const nextTheme =
                        checkbox.checked
                            ? "dark"
                            : "light";

                    localStorage.setItem(
                        STORAGE_KEY,
                        nextTheme
                    );

                    applyTheme(nextTheme);
                }
            );
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