document.addEventListener(
    "DOMContentLoaded",
    function() {

        const sidebar =
            document.getElementById(
                "adminSidebar"
            );

        const openButton =
            document.getElementById(
                "adminMobileMenuButton"
            );

        const closeButton =
            document.getElementById(
                "adminMobileSidebarClose"
            );


        if (
            !sidebar
            || !openButton
            || !closeButton
        ) {
            return;
        }


        /* =================================================
           BACKDROP
           ================================================= */

        const backdrop =
            document.createElement(
                "button"
            );


        backdrop.type =
            "button";


        backdrop.className =
            "admin-sidebar-mobile-backdrop";


        backdrop.setAttribute(
            "aria-label",
            "Đóng menu quản trị"
        );


        document.body.appendChild(
            backdrop
        );


        /* =================================================
           STATE
           ================================================= */

        function isMobile() {

            return window.matchMedia(
                "(max-width: 760px)"
            ).matches;
        }


        function openSidebar() {

            if (!isMobile()) {
                return;
            }


            document.body.classList.add(
                "admin-mobile-nav-open"
            );


            openButton.setAttribute(
                "aria-expanded",
                "true"
            );


            requestAnimationFrame(
                function() {

                    closeButton.focus();
                }
            );
        }


        function closeSidebar(
            restoreFocus = true
        ) {

            document.body.classList.remove(
                "admin-mobile-nav-open"
            );


            openButton.setAttribute(
                "aria-expanded",
                "false"
            );


            if (
                restoreFocus
                && isMobile()
            ) {

                openButton.focus();
            }
        }


        /* =================================================
           OPEN / CLOSE
           ================================================= */

        openButton.addEventListener(
            "click",
            openSidebar
        );


        closeButton.addEventListener(
            "click",
            function() {

                closeSidebar();
            }
        );


        backdrop.addEventListener(
            "click",
            function() {

                closeSidebar();
            }
        );


        /* =================================================
           ESCAPE
           ================================================= */

        document.addEventListener(
            "keydown",
            function(event) {

                if (
                    event.key !== "Escape"
                    || !document.body.classList.contains(
                        "admin-mobile-nav-open"
                    )
                ) {
                    return;
                }


                event.preventDefault();

                closeSidebar();
            }
        );


        /* =================================================
           NAVIGATION
           ================================================= */

        sidebar
            .querySelectorAll(
                "a.sidebar-link"
            )
            .forEach(
                function(link) {

                    link.addEventListener(
                        "click",
                        function() {

                            if (
                                isMobile()
                            ) {

                                closeSidebar(
                                    false
                                );
                            }
                        }
                    );
                }
            );


        /* =================================================
           RESIZE MOBILE → DESKTOP
           ================================================= */

        window.addEventListener(
            "resize",
            function() {

                if (!isMobile()) {

                    closeSidebar(
                        false
                    );
                }
            }
        );
    }
);