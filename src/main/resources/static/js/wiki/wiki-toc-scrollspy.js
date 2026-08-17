(() => {

    "use strict";


    /* =====================================================
       ELEMENTS
       ===================================================== */

    const tocList =
            document.querySelector(
                ".wiki-public-reading-sidebar "
                + ".wiki-toc-list"
            );


    /*
     * Bài không có TOC.
     */
    if (!tocList) {
        return;
    }


    const links =
            Array.from(
                tocList.querySelectorAll(
                    'a[href^="#"]'
                )
            );


    if (links.length === 0) {
        return;
    }


    /* =====================================================
       MAP LINK -> HEADING
       ===================================================== */

    const entries =
            links
                .map((link) => {

                    const href =
                            link.getAttribute(
                                "href"
                            );

                    if (
                            !href
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


                    const heading =
                            document.getElementById(
                                headingId
                            );

                    const item =
                            link.closest("li");


                    if (
                            !heading
                            || !item
                    ) {
                        return null;
                    }


                    return {
                        id: headingId,
                        heading,
                        link,
                        item
                    };
                })
                .filter(Boolean);


    if (entries.length === 0) {
        return;
    }


    /* =====================================================
       STATE
       ===================================================== */

    let activeId = null;

    let ticking = false;


    /*
     * Navbar fixed + một ít khoảng thở.
     */
    const ACTIVE_OFFSET =
            145;


    /* =====================================================
       ACTIVE STATE
       ===================================================== */

    function clearActiveState() {

        entries.forEach(
            (entry) => {

                entry.link.classList.remove(
                    "is-active",
                    "is-parent-active"
                );

                entry.item.classList.remove(
                    "is-active",
                    "is-parent-active"
                );

                entry.link.removeAttribute(
                    "aria-current"
                );
            }
        );
    }


    function findParentLevel2Item(
            item
    ) {

        let previousItem =
                item.previousElementSibling;


        while (previousItem) {

            if (
                    previousItem.classList.contains(
                        "wiki-toc-level-2"
                    )
            ) {
                return previousItem;
            }

            previousItem =
                    previousItem
                            .previousElementSibling;
        }


        return null;
    }


    function activateEntry(
            entry
    ) {

        if (
                !entry
                || activeId === entry.id
        ) {
            return;
        }


        clearActiveState();


        entry.item.classList.add(
            "is-active"
        );

        entry.link.classList.add(
            "is-active"
        );

        entry.link.setAttribute(
            "aria-current",
            "location"
        );


        /*
         * Nếu đang ở H3:
         *
         * 3   Nguyên lý tu hành
         * 3.1 Căn cốt
         *
         * thì H2 cha cũng được nhấn nhẹ.
         */
        if (
                entry.item.classList.contains(
                    "wiki-toc-level-3"
                )
        ) {

            const parentItem =
                    findParentLevel2Item(
                        entry.item
                    );


            if (parentItem) {

                parentItem.classList.add(
                    "is-parent-active"
                );


                const parentLink =
                        parentItem.querySelector(
                            "a"
                        );


                if (parentLink) {

                    parentLink.classList.add(
                        "is-parent-active"
                    );
                }
            }
        }


        activeId =
                entry.id;
    }


    /* =====================================================
       DETECT CURRENT SECTION
       ===================================================== */

    function resolveCurrentEntry() {

        /*
         * Mặc định section đầu tiên.
         */
        let currentEntry =
                entries[0];


        for (
            const entry
            of entries
        ) {

            const top =
                    entry.heading
                            .getBoundingClientRect()
                            .top;


            if (
                    top <= ACTIVE_OFFSET
            ) {

                currentEntry =
                        entry;

            } else {

                /*
                 * Heading được sắp theo DOM,
                 * gặp heading chưa tới viewport
                 * thì dừng.
                 */
                break;
            }
        }


        /*
         * Khi đã cuộn tới cuối bài,
         * luôn active section cuối.
         */
        const reachedBottom =
                window.innerHeight
                + window.scrollY
                >= document.documentElement
                        .scrollHeight
                        - 4;


        if (reachedBottom) {

            currentEntry =
                    entries[
                        entries.length - 1
                    ];
        }


        return currentEntry;
    }


    function updateActiveSection() {

        ticking = false;


        activateEntry(
            resolveCurrentEntry()
        );
    }


    function requestUpdate() {

        if (ticking) {
            return;
        }


        ticking = true;


        window.requestAnimationFrame(
            updateActiveSection
        );
    }


    /* =====================================================
       EVENTS
       ===================================================== */

    window.addEventListener(
        "scroll",
        requestUpdate,
        {
            passive: true
        }
    );


    window.addEventListener(
        "resize",
        requestUpdate
    );


    window.addEventListener(
        "hashchange",
        requestUpdate
    );


    /*
     * Click TOC:
     * active ngay, không cần chờ scroll event.
     */
    entries.forEach(
        (entry) => {

            entry.link.addEventListener(
                "click",
                () => {

                    activateEntry(
                        entry
                    );
                }
            );
        }
    );


    /* =====================================================
       INIT
       ===================================================== */

    requestUpdate();


    /*
     * Browser có thể xử lý #anchor
     * sau lần render đầu tiên.
     */
    window.requestAnimationFrame(
        requestUpdate
    );

})();