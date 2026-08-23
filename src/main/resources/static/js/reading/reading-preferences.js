(() => {

    "use strict";


    /* =====================================================
       CONFIG
       ===================================================== */

    const STORAGE_KEY =
        "kiemlai:reading:font-size";

    const MIN_SIZE =
        14;

    const DEFAULT_SIZE =
        16;

    const MAX_SIZE =
        24;

    const STEP =
        2;


    /* =====================================================
       ROOT
       ===================================================== */

    const root =
        document.documentElement;

    /* =====================================================
       ACCESSIBILITY ANNOUNCER
       ===================================================== */

    const liveRegion =
        document.createElement(
            "span"
        );


    liveRegion.setAttribute(
        "role",
        "status"
    );

    liveRegion.setAttribute(
        "aria-live",
        "polite"
    );

    liveRegion.setAttribute(
        "aria-atomic",
        "true"
    );


    Object.assign(
        liveRegion.style,
        {
            position: "absolute",
            width: "1px",
            height: "1px",
            padding: "0",
            margin: "-1px",
            overflow: "hidden",
            clip: "rect(0, 0, 0, 0)",
            whiteSpace: "nowrap",
            border: "0"
        }
    );


    document.body.appendChild(
        liveRegion
    );

    /* =====================================================
       STORAGE
       ===================================================== */

    function normalizeSize(value) {

        const numericValue =
            Number(value);

        if (
            !Number.isFinite(
                numericValue
            )
        ) {
            return DEFAULT_SIZE;
        }

        const steppedValue =
            Math.round(
                (
                    numericValue
                    - MIN_SIZE
                )
                / STEP
            )
            * STEP
            + MIN_SIZE;

        return Math.min(
            MAX_SIZE,
            Math.max(
                MIN_SIZE,
                steppedValue
            )
        );
    }


    function loadSavedSize() {

        try {

            const savedValue =
                localStorage.getItem(
                    STORAGE_KEY
                );

            if (savedValue === null) {
                return DEFAULT_SIZE;
            }

            return normalizeSize(
                savedValue
            );

        } catch (error) {

            console.warn(
                "Không thể đọc tùy chọn cỡ chữ.",
                error
            );

            return DEFAULT_SIZE;
        }
    }


    function saveSize(size) {

        try {

            localStorage.setItem(
                STORAGE_KEY,
                String(size)
            );

        } catch (error) {

            console.warn(
                "Không thể lưu tùy chọn cỡ chữ.",
                error
            );
        }
    }


    /* =====================================================
       STATE
       ===================================================== */

    let currentSize =
        loadSavedSize();

    function announceSize() {

        liveRegion.textContent =
            `Cỡ chữ hiện tại ${currentSize}px`;
    }


    /* =====================================================
       APPLY
       ===================================================== */

    function applySize(
        size,
        persist
    ) {

        currentSize =
            normalizeSize(
                size
            );


        root.style.setProperty(
            "--reading-font-size",
            `${currentSize}px`
        );


        root.dataset.readingFontSize =
            String(currentSize);


        if (persist) {

            saveSize(
                currentSize
            );

            announceSize();
        }

        updateControls();
    }


    /* =====================================================
       CONTROLS
       ===================================================== */

    const decreaseButtons =
        document.querySelectorAll(
            '[data-reading-font-action="decrease"]'
        );

    const resetButtons =
        document.querySelectorAll(
            '[data-reading-font-action="reset"]'
        );

    const increaseButtons =
        document.querySelectorAll(
            '[data-reading-font-action="increase"]'
        );


    function updateControls() {

        const isMinimum =
            currentSize <= MIN_SIZE;

        const isDefault =
            currentSize === DEFAULT_SIZE;

        const isMaximum =
            currentSize >= MAX_SIZE;


        decreaseButtons.forEach(
            button => {

                button.disabled =
                    isMinimum;

                button.setAttribute(
                    "aria-disabled",
                    String(isMinimum)
                );
            }
        );


        increaseButtons.forEach(
            button => {

                button.disabled =
                    isMaximum;

                button.setAttribute(
                    "aria-disabled",
                    String(isMaximum)
                );
            }
        );


        resetButtons.forEach(
            button => {

                button.classList.toggle(
                    "is-active",
                    isDefault
                );

                button.title =
                    `Cỡ chữ hiện tại: ${currentSize}px`;
            }
        );
    }


    /* =====================================================
       EVENTS
       ===================================================== */

    decreaseButtons.forEach(
        button => {

            button.addEventListener(
                "click",
                () => {

                    applySize(
                        currentSize - STEP,
                        true
                    );
                }
            );
        }
    );


    resetButtons.forEach(
        button => {

            button.addEventListener(
                "click",
                () => {

                    applySize(
                        DEFAULT_SIZE,
                        true
                    );
                }
            );
        }
    );


    increaseButtons.forEach(
        button => {

            button.addEventListener(
                "click",
                () => {

                    applySize(
                        currentSize + STEP,
                        true
                    );
                }
            );
        }
    );


    /* =====================================================
       INIT
       ===================================================== */

    /*
     * Luôn apply preference đã lưu,
     * kể cả trang hiện tại không có toolbar.
     */
    applySize(
        currentSize,
        false
    );

})();