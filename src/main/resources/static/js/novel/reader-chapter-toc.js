/**
 * Public Novel Reader: Chapter Table of Contents (TOC) Drawer.
 *
 * Controls: .js-novel-toc-trigger, [aria-controls="novelTocDrawer"]
 * Drawer:   #novelTocDrawer
 * Backdrop: #novelTocBackdrop
 * Close:    #novelTocCloseBtn
 *
 * Responsibilities:
 * - Open, close, and toggle drawer state.
 * - Backdrop click & Escape key closing.
 * - Synchronize aria-expanded and aria-hidden states.
 * - Prevent body background scroll while drawer is open.
 * - Auto-scroll active TOC chapter item into view upon opening.
 * - Manage keyboard focus (close button on open, trigger on close).
 */
(() => {
    "use strict";

    const DRAWER_ID = "novelTocDrawer";
    const BACKDROP_ID = "novelTocBackdrop";
    const CLOSE_BTN_ID = "novelTocCloseBtn";
    const TRIGGER_SELECTOR = '.js-novel-toc-trigger, [aria-controls="novelTocDrawer"]';
    const ACTIVE_ITEM_SELECTOR = ".novel-toc-item.is-active, [aria-current='page']";

    let lastActiveTrigger = null;

    /**
     * Retrieves the DOM elements for TOC drawer.
     */
    function getElements() {
        return {
            drawer: document.getElementById(DRAWER_ID),
            backdrop: document.getElementById(BACKDROP_ID),
            closeBtn: document.getElementById(CLOSE_BTN_ID),
            triggers: document.querySelectorAll(TRIGGER_SELECTOR)
        };
    }

    /**
     * Opens the Table of Contents drawer.
     *
     * @param {HTMLElement} [trigger]
     */
    function openDrawer(trigger) {
        const { drawer, backdrop, closeBtn, triggers } = getElements();
        if (!drawer || !backdrop) {
            return;
        }

        lastActiveTrigger = trigger || null;

        drawer.hidden = false;
        backdrop.hidden = false;

        /* Force reflow for CSS transition */
        void drawer.offsetHeight;

        drawer.classList.add("is-open");
        backdrop.classList.add("is-open");
        document.body.classList.add("has-toc-open");

        triggers.forEach((btn) => {
            btn.setAttribute("aria-expanded", "true");
        });

        /* Auto-scroll active item into visible scroll area */
        const activeItem = drawer.querySelector(ACTIVE_ITEM_SELECTOR);
        if (activeItem) {
            activeItem.scrollIntoView({ block: "center", behavior: "smooth" });
        }

        if (closeBtn) {
            closeBtn.focus();
        }
    }

    /**
     * Closes the Table of Contents drawer.
     */
    function closeDrawer() {
        const { drawer, backdrop, triggers } = getElements();
        if (!drawer || !backdrop) {
            return;
        }

        drawer.classList.remove("is-open");
        backdrop.classList.remove("is-open");
        document.body.classList.remove("has-toc-open");

        triggers.forEach((btn) => {
            btn.setAttribute("aria-expanded", "false");
        });

        /* Hide after transition completes */
        setTimeout(() => {
            if (!drawer.classList.contains("is-open")) {
                drawer.hidden = true;
                backdrop.hidden = true;
            }
        }, 300);

        if (lastActiveTrigger && typeof lastActiveTrigger.focus === "function") {
            lastActiveTrigger.focus();
        }
    }

    /**
     * Toggles the Table of Contents drawer open/closed.
     *
     * @param {HTMLElement} trigger
     */
    function toggleDrawer(trigger) {
        const { drawer } = getElements();
        if (!drawer) {
            return;
        }

        if (drawer.classList.contains("is-open")) {
            closeDrawer();
        } else {
            openDrawer(trigger);
        }
    }

    /* Event delegation on document */
    document.addEventListener("click", (event) => {
        const trigger = event.target.closest(TRIGGER_SELECTOR);
        if (trigger) {
            event.preventDefault();
            toggleDrawer(trigger);
            return;
        }

        const closeBtn = event.target.closest(`#${CLOSE_BTN_ID}`);
        if (closeBtn) {
            event.preventDefault();
            closeDrawer();
            return;
        }

        const backdrop = event.target.closest(`#${BACKDROP_ID}`);
        if (backdrop) {
            event.preventDefault();
            closeDrawer();
        }
    });

    /* Keyboard navigation: Escape key closes the drawer */
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            const { drawer } = getElements();
            if (drawer && drawer.classList.contains("is-open")) {
                event.preventDefault();
                closeDrawer();
            }
        }
    });
})();
