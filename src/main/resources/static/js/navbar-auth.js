/**
 * KiemLai Universe — Global Navbar Auth Return Link Helper (MS-05E5G3A2a)
 *
 * Responsibilities:
 * - Dynamically attaches current page (pathname + search + hash) as returnTo
 *   to guest Login and Register links in the global navbar.
 * - Prevents attaching returnTo when already on technical/auth pages
 *   (/login, /register, /logout, /error, /access-denied, /forgot-password, /reset-password).
 * - Safe client-side execution; server-side SafeReturnToValidator still validates all redirects.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NavbarAuth = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NavbarAuth = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const TECHNICAL_PATHS = [
        '/login',
        '/register',
        '/logout',
        '/error',
        '/access-denied',
        '/forgot-password',
        '/reset-password'
    ];

    /**
     * Determines whether the given pathname is an auth/technical page where
     * attaching returnTo would cause redirect loops or undesirable UX.
     *
     * @param {string} pathname
     * @returns {boolean}
     */
    function isTechnicalPage(pathname) {
        if (!pathname || typeof pathname !== 'string') {
            return true;
        }
        for (let i = 0; i < TECHNICAL_PATHS.length; i++) {
            const prefix = TECHNICAL_PATHS[i];
            if (pathname === prefix || pathname.startsWith(prefix + '/')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the current page return target (pathname + search + hash).
     * Returns null if currently on a technical/auth page or window is unavailable.
     *
     * @param {Window|null} win
     * @returns {string|null}
     */
    function getCurrentReturnTarget(win) {
        const w = win || (typeof window !== 'undefined' ? window : null);
        if (!w || !w.location) {
            return null;
        }
        const pathname = w.location.pathname || '';
        if (isTechnicalPage(pathname)) {
            return null;
        }
        const search = w.location.search || '';
        const hash = w.location.hash || '';
        const target = pathname + search + hash;
        return target.startsWith('/') ? target : '/' + target;
    }

    /**
     * Updates navbar guest auth links with returnTo for the current page.
     *
     * @param {Document|null} doc
     * @param {Window|null} win
     */
    function updateNavbarAuthLinks(doc, win) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        const w = win || (typeof window !== 'undefined' ? window : null);
        if (!d || !w) {
            return;
        }

        const returnTarget = getCurrentReturnTarget(w);
        if (!returnTarget) {
            return;
        }

        const authLinks = d.querySelectorAll('.js-navbar-auth-link');
        for (let i = 0; i < authLinks.length; i++) {
            const link = authLinks[i];
            const authType = link.getAttribute('data-auth-type');
            const basePath = authType === 'register' ? '/register' : '/login';
            link.href = basePath + '?returnTo=' + encodeURIComponent(returnTarget);
        }
    }

    /**
     * Initializes navbar auth link behaviors and click listener for dynamic hash changes.
     *
     * @param {Document|null} doc
     * @param {Window|null} win
     */
    function initNavbarAuth(doc, win) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        const w = win || (typeof window !== 'undefined' ? window : null);
        if (!d) {
            return;
        }

        updateNavbarAuthLinks(d, w);

        // Intercept clicks to capture the latest dynamic hash (e.g. user scrolled/tapped an anchor)
        d.addEventListener('click', function (e) {
            const link = e.target && e.target.closest ? e.target.closest('.js-navbar-auth-link') : null;
            if (!link) {
                return;
            }
            const returnTarget = getCurrentReturnTarget(w);
            if (returnTarget) {
                const authType = link.getAttribute('data-auth-type');
                const basePath = authType === 'register' ? '/register' : '/login';
                link.href = basePath + '?returnTo=' + encodeURIComponent(returnTarget);
            }
        });
    }

    /**
     * Computes the login redirect URL after successful registration,
     * preserving returnTo if present in the given search query string.
     *
     * @param {string} searchQuery
     * @param {string} [origin]
     * @returns {string}
     */
    function computeRegistrationSuccessUrl(searchQuery, origin) {
        const baseOrigin = origin || (typeof window !== 'undefined' && window.location ? window.location.origin : 'http://localhost');
        const targetUrl = new URL('/login', baseOrigin);
        const currentParams = new URLSearchParams(searchQuery || '');
        const returnTo = currentParams.get('returnTo');
        if (returnTo && returnTo.trim().length > 0) {
            const params = new URLSearchParams();
            params.set('returnTo', returnTo.trim());
            targetUrl.search = '?registered&' + params.toString();
        } else {
            targetUrl.search = '?registered';
        }
        return targetUrl.toString();
    }

    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initNavbarAuth(document, window);
            });
        } else {
            initNavbarAuth(document, window);
        }
    }

    return {
        TECHNICAL_PATHS,
        isTechnicalPage,
        getCurrentReturnTarget,
        updateNavbarAuthLinks,
        initNavbarAuth,
        computeRegistrationSuccessUrl
    };
});
