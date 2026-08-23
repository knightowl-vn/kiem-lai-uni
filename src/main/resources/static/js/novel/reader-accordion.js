/**
 * Public Novel Reader: Volume Accordion & Lazy-Loaded Chapter List.
 *
 * Controls: .novel-reader-volume-trigger
 * Panels:   .novel-reader-volume-content
 *
 * Behavior:
 * - Exclusive accordion: opening a volume collapses all other open volumes.
 * - Toggle: clicking an already open volume collapses it.
 * - Lazy-loading: on first open, fetches GET /novel/volumes/{volumeId}/chapters.
 * - DOM Caching: successfully loaded volumes are marked and never re-fetched.
 * - Duplicate guard: in-flight tracking prevents duplicate simultaneous fetches.
 * - Error handling: failed requests show an error state with retry option.
 * - Accessibility: synchronizes aria-expanded and hidden attributes.
 */
(() => {
    "use strict";

    const TRIGGER_SELECTOR = ".novel-reader-volume-trigger";
    const CONTENT_SELECTOR = ".novel-reader-volume-content";
    const ACTIVE_TRIGGER_SELECTOR = '.novel-reader-volume-trigger[aria-expanded="true"]';

    /* In-flight fetch tracker to prevent duplicate simultaneous requests */
    const inFlightVolumeFetches = new Set();

    /**
     * Finds the content panel controlled by a given trigger button.
     *
     * @param {HTMLButtonElement} trigger
     * @returns {HTMLElement|null}
     */
    function findControlledPanel(trigger) {
        const controlsId = trigger.getAttribute("aria-controls");
        if (controlsId) {
            const panel = document.getElementById(controlsId);
            if (panel) {
                return panel;
            }
        }

        const volumeArticle = trigger.closest(".novel-reader-volume");
        if (volumeArticle) {
            return volumeArticle.querySelector(CONTENT_SELECTOR);
        }

        return null;
    }

    /**
     * Collapses a specific volume accordion item.
     *
     * @param {HTMLButtonElement} trigger
     */
    function collapseVolume(trigger) {
        if (!trigger) {
            return;
        }

        trigger.setAttribute("aria-expanded", "false");
        const panel = findControlledPanel(trigger);
        if (panel) {
            panel.hidden = true;
        }
    }

    /**
     * Collapses all currently open volume accordion items except the specified one.
     *
     * @param {HTMLButtonElement} [exceptTrigger]
     */
    function collapseAllOtherVolumes(exceptTrigger) {
        const openTriggers = document.querySelectorAll(ACTIVE_TRIGGER_SELECTOR);
        openTriggers.forEach((trigger) => {
            if (trigger !== exceptTrigger) {
                collapseVolume(trigger);
            }
        });
    }

    /**
     * Expands a specific volume accordion item.
     *
     * @param {HTMLButtonElement} trigger
     */
    function expandVolume(trigger) {
        if (!trigger) {
            return;
        }

        trigger.setAttribute("aria-expanded", "true");
        const panel = findControlledPanel(trigger);
        if (panel) {
            panel.hidden = false;
        }
    }

    /**
     * Fetches and injects the chapter list HTML fragment for a given volume.
     *
     * @param {string} volumeId
     * @param {HTMLElement} panel
     * @param {HTMLButtonElement} trigger
     * @returns {Promise<void>}
     */
    async function loadVolumeChapters(volumeId, panel, trigger) {
        if (!volumeId || !panel || inFlightVolumeFetches.has(volumeId)) {
            return;
        }

        inFlightVolumeFetches.add(volumeId);

        /* Set loading state */
        panel.innerHTML =
            '<div class="novel-reader-volume-loading" role="status" aria-live="polite">' +
            "Đang tải danh sách chương..." +
            "</div>";

        const endpoint = `/novel/volumes/${encodeURIComponent(volumeId)}/chapters`;

        try {
            const response = await fetch(endpoint, {
                method: "GET",
                headers: {
                    "Accept": "text/html, */*",
                    "X-Requested-With": "XMLHttpRequest"
                }
            });

            if (!response.ok) {
                throw new Error(`HTTP error ${response.status}`);
            }

            const htmlFragment = await response.text();

            panel.innerHTML = htmlFragment;
            panel.dataset.loaded = "true";
        } catch (error) {
            /* Error state: do NOT mark as loaded so subsequent clicks can retry */
            panel.innerHTML =
                '<div class="novel-reader-volume-loading novel-reader-volume-error" role="alert">' +
                '<p style="margin: 0 0 10px 0;">Không thể tải danh sách chương. Vui lòng thử lại.</p>' +
                '<button type="button" class="novel-reader-retry-btn" style="cursor: pointer; padding: 6px 14px; border-radius: 8px; border: 1px solid var(--reader-border, #e7e3dd); background: var(--reader-surface, #ffffff); color: var(--reader-text, #24221f); font-size: 12px; font-weight: 600;">' +
                "Thử lại" +
                "</button>" +
                "</div>";

            const retryBtn = panel.querySelector(".novel-reader-retry-btn");
            if (retryBtn) {
                retryBtn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    loadVolumeChapters(volumeId, panel, trigger);
                }, { once: true });
            }
        } finally {
            inFlightVolumeFetches.delete(volumeId);
        }
    }

    /**
     * Handles volume trigger button click.
     *
     * @param {HTMLButtonElement} trigger
     */
    function handleVolumeTriggerClick(trigger) {
        const isCurrentlyExpanded = trigger.getAttribute("aria-expanded") === "true";
        const volumeId = trigger.getAttribute("data-volume-id");
        const panel = findControlledPanel(trigger);

        if (!panel) {
            return;
        }

        if (isCurrentlyExpanded) {
            /* If already open, toggle closed */
            collapseVolume(trigger);
        } else {
            /* Exclusive accordion: close all other open volumes first */
            collapseAllOtherVolumes(trigger);
            expandVolume(trigger);

            /* Lazy load on first expand if not already loaded and not in flight */
            if (panel.dataset.loaded !== "true" && !inFlightVolumeFetches.has(volumeId)) {
                loadVolumeChapters(volumeId, panel, trigger);
            }
        }
    }

    /* Event delegation on document */
    document.addEventListener("click", (event) => {
        const trigger = event.target.closest(TRIGGER_SELECTOR);
        if (!trigger) {
            return;
        }

        event.preventDefault();
        handleVolumeTriggerClick(trigger);
    });

})();
