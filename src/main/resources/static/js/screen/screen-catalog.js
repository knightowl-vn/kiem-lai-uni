/**
 * Screen Public Catalog — Title Overflow Detection & Marquee Support (MS-08F1)
 *
 * Measures whether Vietnamese and original movie titles exceed their card width.
 * Dynamically applies .is-overflowing and sets --overflow-dist and --scroll-duration
 * CSS custom properties for hardware-accelerated horizontal reveal on hover.
 */
(function () {
    'use strict';

    function isMarqueeEligible() {
        // Marquee hover reveal is only active on desktop viewports (>640px) with fine hover pointer
        if (typeof window.matchMedia !== 'undefined') {
            return window.matchMedia('(min-width: 641px) and (hover: hover)').matches;
        }
        return window.innerWidth > 640;
    }

    function updateTitleOverflow() {
        var titles = document.querySelectorAll('.screen-card-title, .screen-card-original-title');
        if (!titles.length) return;

        var eligible = isMarqueeEligible();

        titles.forEach(function (container) {
            var textSpan = container.querySelector('.screen-card-title-text');
            if (!textSpan) return;

            // Remove overflowing class to measure natural container & text width
            container.classList.remove('is-overflowing');
            textSpan.style.removeProperty('--overflow-dist');
            textSpan.style.removeProperty('--scroll-duration');

            if (!eligible) {
                return;
            }

            // Temporarily unconstrain max-width to measure exact unclipped scrollWidth across all browsers
            var prevMaxWidth = textSpan.style.maxWidth;
            textSpan.style.maxWidth = 'none';

            var containerWidth = container.clientWidth;
            var textWidth = textSpan.scrollWidth;

            textSpan.style.maxWidth = prevMaxWidth;

            // Threshold: 1px buffer to ignore sub-pixel rounding differences
            if (textWidth > containerWidth + 1) {
                var overflowDist = Math.ceil(textWidth - containerWidth);
                // Proportional duration: ~50px per second, clamped between 1.8s and 6.0s
                var duration = Math.max(1.8, Math.min(6.0, overflowDist / 50)).toFixed(2);

                container.classList.add('is-overflowing');
                textSpan.style.setProperty('--overflow-dist', overflowDist + 'px');
                textSpan.style.setProperty('--scroll-duration', duration + 's');
            }
        });
    }

    // Initialize on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', updateTitleOverflow);
    } else {
        updateTitleOverflow();
    }

    // Re-evaluate when custom or system web fonts are fully loaded (prevents font-swap layout shift)
    if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(updateTitleOverflow);
    }

    // Responsive update on grid resize
    if (typeof window.ResizeObserver !== 'undefined') {
        var grid = document.querySelector('.screen-catalog-grid');
        if (grid) {
            var resizeObserver = new ResizeObserver(function () {
                updateTitleOverflow();
            });
            resizeObserver.observe(grid);
        }
    } else {
        window.addEventListener('resize', updateTitleOverflow, { passive: true });
    }

    // Expose for testing if window scope is present
    if (typeof window !== 'undefined') {
        window.updateScreenCatalogTitleOverflow = updateTitleOverflow;
    }
})();
