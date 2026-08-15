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
       ELEMENTS
       ===================================================== */

    const root =
            document.documentElement;

    const decreaseButton =
            document.querySelector(
                '[data-wiki-font-action="decrease"]'
            );

    const resetButton =
            document.querySelector(
                '[data-wiki-font-action="reset"]'
            );

    const increaseButton =
            document.querySelector(
                '[data-wiki-font-action="increase"]'
            );


    /*
     * Không phải trang đọc Wiki.
     */
    if (
            !decreaseButton
            || !resetButton
            || !increaseButton
    ) {
        return;
    }


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
                "Không thể đọc cỡ chữ đã lưu.",
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
                "Không thể lưu cỡ chữ.",
                error
            );
        }
    }


    /* =====================================================
       STATE
       ===================================================== */

    let currentSize =
            loadSavedSize();


    /* =====================================================
       UI
       ===================================================== */

    function updateControls() {

        const isMinimum =
                currentSize <= MIN_SIZE;

        const isDefault =
                currentSize === DEFAULT_SIZE;

        const isMaximum =
                currentSize >= MAX_SIZE;


        decreaseButton.disabled =
                isMinimum;

        increaseButton.disabled =
                isMaximum;


        decreaseButton.setAttribute(
                "aria-disabled",
                String(isMinimum)
        );

        increaseButton.setAttribute(
                "aria-disabled",
                String(isMaximum)
        );


        /*
         * Nút A sáng lên khi đang ở
         * kích thước mặc định.
         */
        resetButton.classList.toggle(
                "is-active",
                isDefault
        );

        resetButton.setAttribute(
                "aria-pressed",
                String(isDefault)
        );


        /*
         * Tooltip cho biết kích thước hiện tại.
         */
        resetButton.title =
                `Cỡ chữ hiện tại: ${currentSize}px`;
    }


    function applySize(
            size,
            persist
    ) {

        currentSize =
                normalizeSize(
                    size
                );


        root.style.setProperty(
                "--wiki-reading-font-size",
                `${currentSize}px`
        );


        root.dataset.wikiFontSize =
                String(currentSize);


        updateControls();


        if (persist) {

            saveSize(
                currentSize
            );
        }
    }


    /* =====================================================
       EVENTS
       ===================================================== */

    decreaseButton.addEventListener(
        "click",
        () => {

            applySize(
                currentSize - STEP,
                true
            );
        }
    );


    resetButton.addEventListener(
        "click",
        () => {

            applySize(
                DEFAULT_SIZE,
                true
            );
        }
    );


    increaseButton.addEventListener(
        "click",
        () => {

            applySize(
                currentSize + STEP,
                true
            );
        }
    );


    /* =====================================================
       INIT
       ===================================================== */

    applySize(
        currentSize,
        false
    );

})();