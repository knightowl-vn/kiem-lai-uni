document.addEventListener("DOMContentLoaded", () => {
    const searchContainer =
            document.getElementById("navbarWikiSearch");

    const toggleButton =
            document.getElementById("navbarWikiSearchToggle");

    const searchForm =
            document.getElementById("navbarWikiSearchForm");

    const searchInput =
            document.getElementById("navbarWikiKeyword");

    if (
        !searchContainer
        || !toggleButton
        || !searchForm
        || !searchInput
    ) {
        return;
    }

    const openSearch = () => {
        searchContainer.classList.add("is-open");

        toggleButton.setAttribute(
            "aria-expanded",
            "true"
        );

        toggleButton.setAttribute(
            "aria-label",
            "Đóng tìm kiếm Wiki"
        );

        window.setTimeout(() => {
            searchInput.focus();
            searchInput.select();
        }, 180);
    };

    const closeSearch = () => {
        searchContainer.classList.remove("is-open");

        toggleButton.setAttribute(
            "aria-expanded",
            "false"
        );

        toggleButton.setAttribute(
            "aria-label",
            "Mở tìm kiếm Wiki"
        );
    };

    toggleButton.addEventListener("click", () => {
        const isOpen =
                searchContainer.classList.contains(
                    "is-open"
                );

        if (isOpen) {
            closeSearch();
        } else {
            openSearch();
        }
    });

    document.addEventListener("keydown", event => {
        if (
            event.key === "Escape"
            && searchContainer.classList.contains(
                "is-open"
            )
        ) {
            closeSearch();
            toggleButton.focus();
        }
    });

    document.addEventListener("click", event => {
        if (
            searchContainer.classList.contains(
                "is-open"
            )
            && !searchContainer.contains(event.target)
        ) {
            closeSearch();
        }
    });

    searchForm.addEventListener("submit", () => {
        searchInput.value =
                searchInput.value.trim();
    });
});