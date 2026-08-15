(() => {

    "use strict";


    /* =====================================================
       CONFIG
       ===================================================== */

    const MOBILE_BREAKPOINT =
            991.98;


    /* =====================================================
       ELEMENTS
       ===================================================== */

    const toggleButton =
            document.querySelector(
                "[data-wiki-toc-toggle]"
            );

    const sidebar =
            document.querySelector(
                ".wiki-public-reading-sidebar"
            );

    const toc =
            document.getElementById(
                "wikiPublicToc"
            );


    /*
     * Bài không có TOC.
     */
    if (
            !toggleButton
            || !sidebar
            || !toc
    ) {
        return;
    }


    const tocLinks =
            Array.from(
                toc.querySelectorAll(
                    'a[href^="#"]'
                )
            );


    /* =====================================================
       STATE
       ===================================================== */

    let opened =
            false;


    function isMobileLayout() {

        return window.matchMedia(
            `(max-width: ${MOBILE_BREAKPOINT}px)`
        ).matches;
    }


    /* =====================================================
       RENDER
       ===================================================== */

    function render() {

        sidebar.classList.toggle(
            "is-toc-open",
            opened
        );


        toggleButton.setAttribute(
            "aria-expanded",
            String(opened)
        );


        const icon =
                toggleButton.querySelector(
                    ".wiki-public-toc-toggle-icon"
                );


        if (icon) {

            icon.textContent =
                    opened
                            ? "▴"
                            : "▾";
        }
    }


    function openToc() {

        opened = true;

        render();
    }


    function closeToc() {

        opened = false;

        render();
    }


    /* =====================================================
       EVENTS
       ===================================================== */

    toggleButton.addEventListener(
        "click",
        () => {

            opened =
                    !opened;

            render();
        }
    );


    /*
     * Mobile:
     * sau khi chọn một heading,
     * thu mục lục lại để trả không gian cho bài.
     */
    tocLinks.forEach(
        (link) => {

            link.addEventListener(
                "click",
                () => {

                    if (
                            isMobileLayout()
                    ) {
                        closeToc();
                    }
                }
            );
        }
    );


    /*
     * Nếu đổi từ desktop sang mobile
     * thì bắt đầu ở trạng thái thu gọn.
     */
    window.addEventListener(
        "resize",
        () => {

            if (
                    isMobileLayout()
            ) {
                closeToc();
            }
        }
    );


    /* =====================================================
       INIT
       ===================================================== */

    if (
            isMobileLayout()
    ) {
        closeToc();

    } else {

        /*
         * Desktop không phụ thuộc state này,
         * nhưng giữ DOM nhất quán.
         */
        openToc();
    }

})();