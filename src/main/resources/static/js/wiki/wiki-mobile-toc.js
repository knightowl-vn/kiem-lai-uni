(() => {

    "use strict";


    /* =====================================================
       CONFIG
       ===================================================== */

    const MOBILE_BREAKPOINT =
        991.98;
    const mobileMediaQuery =
        window.matchMedia(
            `(max-width: ${MOBILE_BREAKPOINT}px)`
        );

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

        return mobileMediaQuery.matches;
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
       FOCUS MANAGEMENT
       ===================================================== */

    function resolveTargetHeading(
        link
    ) {

        const href =
            link.getAttribute(
                "href"
            );


        if (
            !href
            || !href.startsWith("#")
            || href === "#"
        ) {
            return null;
        }


        let headingId;


        try {

            headingId =
                decodeURIComponent(
                    href.slice(1)
                );

        } catch (error) {

            headingId =
                href.slice(1);
        }


        return document.getElementById(
            headingId
        );
    }


    function focusHeading(
        heading
    ) {

        if (!heading) {
            return;
        }


        const previousTabIndex =
            heading.getAttribute(
                "tabindex"
            );


        heading.setAttribute(
            "tabindex",
            "-1"
        );


        window.requestAnimationFrame(
            () => {

                heading.focus(
                    {
                        preventScroll: true
                    }
                );
            }
        );


        heading.addEventListener(
            "blur",
            () => {

                if (previousTabIndex === null) {

                    heading.removeAttribute(
                        "tabindex"
                    );

                } else {

                    heading.setAttribute(
                        "tabindex",
                        previousTabIndex
                    );
                }
            },
            {
                once: true
            }
        );
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

    document.addEventListener(
        "keydown",
        (event) => {

            if (
                event.key !== "Escape"
                || !isMobileLayout()
                || !opened
            ) {
                return;
            }


            closeToc();

            toggleButton.focus();
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
                        !isMobileLayout()
                    ) {
                        return;
                    }


                    const targetHeading =
                        resolveTargetHeading(
                            link
                        );


                    closeToc();


                    /*
                     * TOC vừa bị ẩn.
                     * Chuyển focus tới section
                     * mà người dùng vừa chọn.
                     */
                    focusHeading(
                        targetHeading
                    );
                }
            );
        }
    );


    /*
     * Nếu đổi từ desktop sang mobile
     * thì bắt đầu ở trạng thái thu gọn.
     */
    mobileMediaQuery.addEventListener(
        "change",
        (event) => {

            if (event.matches) {

                /*
                 * Desktop → mobile.
                 */
                closeToc();

            } else {

                /*
                 * Mobile → desktop.
                 */
                openToc();
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