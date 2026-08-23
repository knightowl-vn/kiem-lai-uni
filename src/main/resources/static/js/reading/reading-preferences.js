(() => {

    "use strict";

    /* =====================================================
       CONFIG & STORAGE KEYS
       ===================================================== */

    const FONT_SIZE_STORAGE_KEY =
        "kiemlai:reading:font-size";

    const FONT_FAMILY_STORAGE_KEY =
        "kiemlai:reading:font-family";

    const MIN_SIZE = 14;
    const DEFAULT_SIZE = 16;
    const MAX_SIZE = 24;
    const STEP = 2;

    const ALLOWED_FONTS = ["serif", "sans"];
    const DEFAULT_FONT = "serif";

    /* =====================================================
       ROOT & ACCESSIBILITY
       ===================================================== */

    const root = document.documentElement;
    let liveRegion = null;

    function ensureLiveRegion() {
        if (liveRegion || !document.body) {
            return;
        }

        liveRegion = document.createElement("span");
        liveRegion.setAttribute("role", "status");
        liveRegion.setAttribute("aria-live", "polite");
        liveRegion.setAttribute("aria-atomic", "true");

        Object.assign(liveRegion.style, {
            position: "absolute",
            width: "1px",
            height: "1px",
            padding: "0",
            margin: "-1px",
            overflow: "hidden",
            clip: "rect(0, 0, 0, 0)",
            whiteSpace: "nowrap",
            border: "0"
        });

        document.body.appendChild(liveRegion);
    }

    /* =====================================================
       NORMALIZATION & STORAGE
       ===================================================== */

    function normalizeSize(value) {
        const numericValue = Number(value);

        if (!Number.isFinite(numericValue)) {
            return DEFAULT_SIZE;
        }

        const steppedValue =
            Math.round((numericValue - MIN_SIZE) / STEP) * STEP + MIN_SIZE;

        return Math.min(MAX_SIZE, Math.max(MIN_SIZE, steppedValue));
    }

    function normalizeFont(value) {
        if (typeof value === "string" && ALLOWED_FONTS.includes(value.toLowerCase().trim())) {
            return value.toLowerCase().trim();
        }
        return DEFAULT_FONT;
    }

    function loadSavedSize() {
        try {
            const savedValue = localStorage.getItem(FONT_SIZE_STORAGE_KEY);
            if (savedValue === null) {
                return DEFAULT_SIZE;
            }
            return normalizeSize(savedValue);
        } catch (error) {
            console.warn("Không thể đọc tùy chọn cỡ chữ.", error);
            return DEFAULT_SIZE;
        }
    }

    function saveSize(size) {
        try {
            localStorage.setItem(FONT_SIZE_STORAGE_KEY, String(size));
        } catch (error) {
            console.warn("Không thể lưu tùy chọn cỡ chữ.", error);
        }
    }

    function loadSavedFont() {
        try {
            const savedValue = localStorage.getItem(FONT_FAMILY_STORAGE_KEY);
            if (savedValue === null) {
                return DEFAULT_FONT;
            }
            return normalizeFont(savedValue);
        } catch (error) {
            console.warn("Không thể đọc tùy chọn phông chữ.", error);
            return DEFAULT_FONT;
        }
    }

    function saveFont(font) {
        try {
            localStorage.setItem(FONT_FAMILY_STORAGE_KEY, font);
        } catch (error) {
            console.warn("Không thể lưu tùy chọn phông chữ.", error);
        }
    }

    /* =====================================================
       STATE & SCALE
       ===================================================== */

    let currentSize = loadSavedSize();
    let currentFont = loadSavedFont();

    function deriveScale(size) {
        return size / DEFAULT_SIZE;
    }

    function announce(message) {
        ensureLiveRegion();
        if (liveRegion) {
            liveRegion.textContent = message;
        }
    }

    /* =====================================================
       APPLY PREFERENCES
       ===================================================== */

    function applyPreferences(persist) {
        currentSize = normalizeSize(currentSize);
        currentFont = normalizeFont(currentFont);

        const scale = deriveScale(currentSize);

        // 1. Apply font size & scale tokens to <html>
        root.style.setProperty("--reading-font-size", `${currentSize}px`);
        root.style.setProperty("--reading-scale", String(scale));
        root.dataset.readingFontSize = String(currentSize);
        root.dataset.readingScale = String(scale);

        // 2. Apply font family attribute to <html>
        root.setAttribute("data-reading-font", currentFont);
        root.dataset.readingFont = currentFont;

        if (persist) {
            saveSize(currentSize);
            saveFont(currentFont);
        }

        updateControls();
    }

    function applySize(size, persist) {
        currentSize = normalizeSize(size);
        applyPreferences(persist);

        if (persist) {
            announce(`Cỡ chữ hiện tại ${currentSize}px`);
        }
    }

    function applyFont(font, persist) {
        currentFont = normalizeFont(font);
        applyPreferences(persist);

        if (persist) {
            const fontLabel = currentFont === "sans" ? "Không chân (Sans)" : "Có chân (Serif)";
            announce(`Phông chữ hiện tại ${fontLabel}`);
        }
    }

    /* =====================================================
       DOM CONTROLS & BINDINGS
       ===================================================== */

    function updateControls() {
        const isMinimum = currentSize <= MIN_SIZE;
        const isDefault = currentSize === DEFAULT_SIZE;
        const isMaximum = currentSize >= MAX_SIZE;

        const decreaseButtons = document.querySelectorAll(
            '[data-reading-font-action="decrease"]'
        );
        const resetButtons = document.querySelectorAll(
            '[data-reading-font-action="reset"]'
        );
        const increaseButtons = document.querySelectorAll(
            '[data-reading-font-action="increase"]'
        );

        decreaseButtons.forEach(button => {
            button.disabled = isMinimum;
            button.setAttribute("aria-disabled", String(isMinimum));
        });

        increaseButtons.forEach(button => {
            button.disabled = isMaximum;
            button.setAttribute("aria-disabled", String(isMaximum));
        });

        resetButtons.forEach(button => {
            button.classList.toggle("is-active", isDefault);
            button.title = `Cỡ chữ hiện tại: ${currentSize}px`;
        });

        // Font family buttons (support data-reading-font-family or data-reading-font-set)
        const fontButtons = document.querySelectorAll(
            "[data-reading-font-family], [data-reading-font-set]"
        );
        fontButtons.forEach(button => {
            const targetFont =
                button.getAttribute("data-reading-font-family") ||
                button.getAttribute("data-reading-font-set");
            const isActive = targetFont === currentFont;
            button.classList.toggle("is-active", isActive);
            button.setAttribute("aria-pressed", String(isActive));
        });
    }

    function bindEvents() {
        ensureLiveRegion();

        document.addEventListener("click", event => {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }

            // Size action button
            const sizeButton = target.closest('[data-reading-font-action]');
            if (sizeButton) {
                const action = sizeButton.getAttribute("data-reading-font-action");
                if (action === "decrease") {
                    applySize(currentSize - STEP, true);
                } else if (action === "increase") {
                    applySize(currentSize + STEP, true);
                } else if (action === "reset") {
                    applySize(DEFAULT_SIZE, true);
                }
                return;
            }

            // Font family button
            const fontButton = target.closest("[data-reading-font-family], [data-reading-font-set]");
            if (fontButton) {
                const targetFont =
                    fontButton.getAttribute("data-reading-font-family") ||
                    fontButton.getAttribute("data-reading-font-set");
                if (targetFont) {
                    applyFont(targetFont, true);
                }
            }
        });

        updateControls();
    }

    /* =====================================================
       CROSS-TAB SYNCHRONIZATION
       ===================================================== */

    window.addEventListener("storage", event => {
        if (event.key === FONT_SIZE_STORAGE_KEY && event.newValue !== null) {
            currentSize = normalizeSize(event.newValue);
            applyPreferences(false);
        } else if (event.key === FONT_FAMILY_STORAGE_KEY && event.newValue !== null) {
            currentFont = normalizeFont(event.newValue);
            applyPreferences(false);
        }
    });

    /* =====================================================
       INITIALIZATION
       ===================================================== */

    // Apply saved preferences immediately to avoid FOUT / layout shift
    applyPreferences(false);

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindEvents);
    } else {
        bindEvents();
    }

})();