(() => {

    "use strict";

    /* =====================================================
       NOVEL CHAPTER READER — READING SETTINGS POPOVER
       Manages open/close interactions for the Gear popover.
       Preference state & storage remain in shared reading-preferences.js & theme.js.
       ===================================================== */

    function initNovelReadingSettings() {
        const trigger = document.getElementById("novelReadingSettingsTrigger");
        const popover = document.getElementById("novelReadingSettingsPopover");

        if (!trigger || !popover) {
            return;
        }

        function openPopover() {
            popover.removeAttribute("hidden");
            trigger.setAttribute("aria-expanded", "true");
            trigger.classList.add("is-active");
        }

        function closePopover(restoreFocus) {
            if (popover.hasAttribute("hidden")) {
                return;
            }
            popover.setAttribute("hidden", "");
            trigger.setAttribute("aria-expanded", "false");
            trigger.classList.remove("is-active");
            if (restoreFocus) {
                trigger.focus();
            }
        }

        // Toggle popover on trigger click
        trigger.addEventListener("click", event => {
            event.stopPropagation();
            const isHidden = popover.hasAttribute("hidden");
            if (isHidden) {
                openPopover();
            } else {
                closePopover(false);
            }
        });

        // Close on clicking outside (outside-click detection preserves clicks inside popover)
        document.addEventListener("click", event => {
            const target = event.target;
            if (target instanceof Element && !popover.contains(target) && !trigger.contains(target)) {
                closePopover(false);
            }
        });

        // Close on Escape key and return focus to trigger
        document.addEventListener("keydown", event => {
            if (event.key === "Escape" && !popover.hasAttribute("hidden")) {
                closePopover(true);
            }
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initNovelReadingSettings);
    } else {
        initNovelReadingSettings();
    }

})();
