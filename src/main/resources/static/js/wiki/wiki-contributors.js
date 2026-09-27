/**
 * KiemLai Universe — Wiki Public Contributors Modal Interaction (MS-05H9.1)
 *
 * Responsibilities:
 * 1. Listen for trigger clicks on elements with [data-wiki-contributors-modal-trigger].
 * 2. Open #wikiContributorsModal, handle body scroll locking.
 * 3. Handle closing via close button, backdrop click, or ESC key.
 * 4. Return focus cleanly to the trigger element that opened the modal.
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.WikiContributors = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.WikiContributors = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    let lastActiveElement = null;

    function init() {
        const modal = document.getElementById('wikiContributorsModal');
        if (!modal) {
            return;
        }

        const triggers = document.querySelectorAll('[data-wiki-contributors-modal-trigger]');
        const closeButtons = modal.querySelectorAll('[data-wiki-contributors-modal-close]');

        function openModal(triggerEl) {
            lastActiveElement = triggerEl || document.activeElement;
            modal.removeAttribute('hidden');
            document.body.style.overflow = 'hidden';

            const closeBtn = modal.querySelector('.wiki-contributors-modal-close-btn');
            if (closeBtn) {
                closeBtn.focus();
            }
        }

        function closeModal() {
            modal.setAttribute('hidden', '');
            document.body.style.overflow = '';

            if (lastActiveElement && typeof lastActiveElement.focus === 'function') {
                lastActiveElement.focus();
                lastActiveElement = null;
            }
        }

        triggers.forEach(function (trigger) {
            trigger.addEventListener('click', function (e) {
                e.preventDefault();
                openModal(trigger);
            });
        });

        closeButtons.forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.preventDefault();
                closeModal();
            });
        });

        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !modal.hasAttribute('hidden')) {
                e.preventDefault();
                closeModal();
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return {
        init: init
    };
});
