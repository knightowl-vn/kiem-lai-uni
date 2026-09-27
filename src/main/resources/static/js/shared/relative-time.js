/**
 * KiemLai Universe — Shared Relative Time Engine (UX-TIME-01B1)
 *
 * Responsibilities:
 * - Authoritative relative-time calculation and formatting across KiemLai surfaces.
 * - Pure formatting function: ISO-8601 Instant -> localized Vietnamese relative string.
 * - Future clock-skew contract: <= 60s ahead -> "Vừa xong"; > 60s ahead -> absolute datetime.
 * - Threshold contract:
 *     0 <= age < 60s    -> "Vừa xong"
 *     60s <= age < 60m  -> "{N} phút trước"
 *     60m <= age < 24h  -> "{N} giờ trước"
 *     24h <= age < 7d   -> "{N} ngày trước"
 *     7d <= age < 30d   -> "{N} tuần trước"
 *     age >= 30d        -> absolute datetime (dd/MM/yyyy HH:mm)
 * - Viewer-local browser timezone: uses native Date local getters; no external dependencies.
 * - Accessible DOM contract:
 *     <time data-relative-time datetime="..." title="..." aria-label="...">text</time>
 * - Scoped DOM tree formatting and single-timer live refresh (default 60s interval).
 * - Safe degradation: invalid or missing timestamps never throw and return empty strings.
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.RelativeTime = exports;
        if (typeof document !== 'undefined') {
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', function () {
                    exports.init(document);
                });
            } else {
                exports.init(document);
            }
        }
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const SECOND_MS = 1000;
    const MINUTE_MS = 60 * SECOND_MS;
    const HOUR_MS = 60 * MINUTE_MS;
    const DAY_MS = 24 * HOUR_MS;
    const WEEK_MS = 7 * DAY_MS;
    const THIRTY_DAYS_MS = 30 * DAY_MS;
    const FUTURE_TOLERANCE_MS = 60 * SECOND_MS;

    let activeTimer = null;
    let activeDoc = null;
    let activeTimerApi = null;

    /**
     * Parses input into a valid Date object or returns null if invalid/empty.
     *
     * @param {*} timestamp
     * @returns {Date|null}
     */
    function parseTimestamp(timestamp) {
        if (timestamp == null) {
            return null;
        }
        if (timestamp instanceof Date) {
            return isNaN(timestamp.getTime()) ? null : timestamp;
        }
        if (typeof timestamp === 'number') {
            if (isNaN(timestamp) || !isFinite(timestamp)) {
                return null;
            }
            const d = new Date(timestamp);
            return isNaN(d.getTime()) ? null : d;
        }
        if (typeof timestamp === 'string') {
            const trimmed = timestamp.trim();
            if (!trimmed) {
                return null;
            }
            const d = new Date(trimmed);
            return isNaN(d.getTime()) ? null : d;
        }
        return null;
    }

    /**
     * Formats a timestamp into an absolute viewer-local datetime string (dd/MM/yyyy HH:mm).
     * Returns empty string for invalid/missing input without throwing.
     *
     * @param {*} timestamp
     * @returns {string}
     */
    function formatAbsolute(timestamp) {
        const date = parseTimestamp(timestamp);
        if (!date) {
            return '';
        }
        try {
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            return day + '/' + month + '/' + year + ' ' + hours + ':' + minutes;
        } catch (_) {
            return '';
        }
    }

    /**
     * Pure relative-time formatter.
     * Converts a timestamp to human-friendly relative Vietnamese text based on reference `now`.
     *
     * @param {*} timestamp
     * @param {Date|number} [now] - Optional reference timestamp (defaults to Date.now())
     * @returns {string}
     */
    function format(timestamp, now) {
        const date = parseTimestamp(timestamp);
        if (!date) {
            return '';
        }
        let nowMs;
        if (now == null) {
            nowMs = Date.now();
        } else if (now instanceof Date) {
            nowMs = now.getTime();
        } else {
            nowMs = Number(now);
            if (isNaN(nowMs)) {
                nowMs = Date.now();
            }
        }

        const timeMs = date.getTime();
        const diffMs = nowMs - timeMs; // positive = past, negative = future

        // Future clock-skew handling
        if (diffMs < 0) {
            const futureAheadMs = -diffMs;
            if (futureAheadMs <= FUTURE_TOLERANCE_MS) {
                return 'Vừa xong';
            }
            return formatAbsolute(date);
        }

        // Past event thresholds (floor units)
        if (diffMs < MINUTE_MS) {
            return 'Vừa xong';
        }
        if (diffMs < HOUR_MS) {
            const mins = Math.floor(diffMs / MINUTE_MS);
            return mins + ' phút trước';
        }
        if (diffMs < DAY_MS) {
            const hours = Math.floor(diffMs / HOUR_MS);
            return hours + ' giờ trước';
        }
        if (diffMs < WEEK_MS) {
            const days = Math.floor(diffMs / DAY_MS);
            return days + ' ngày trước';
        }
        if (diffMs < THIRTY_DAYS_MS) {
            const weeks = Math.floor(diffMs / WEEK_MS);
            return weeks + ' tuần trước';
        }
        return formatAbsolute(date);
    }

    /**
     * Formats an individual <time> element according to the accessible DOM contract:
     * - Reads canonical `datetime` attribute
     * - Leaves `datetime` unchanged
     * - Updates `textContent`
     * - Updates `title` with exact viewer-local datetime
     * - Updates `aria-label` with relative + exact datetime (or concise exact datetime if >= 30d)
     * - Ensures `data-relative-time` attribute is present
     * - Preserves CSS classes
     *
     * @param {Element} timeElement
     * @param {Date|number} [now]
     * @returns {boolean} True if formatted successfully, false otherwise
     */
    function formatElement(timeElement, now) {
        if (!timeElement || typeof timeElement.getAttribute !== 'function') {
            return false;
        }
        const rawDatetime = timeElement.getAttribute('datetime')
            || timeElement.dateTime
            || (timeElement.dataset && timeElement.dataset.datetime);
        if (!rawDatetime) {
            return false;
        }

        const date = parseTimestamp(rawDatetime);
        if (!date) {
            return false;
        }

        const exactTime = formatAbsolute(date);
        const relativeText = format(date, now);
        if (!relativeText) {
            return false;
        }

        timeElement.textContent = relativeText;
        timeElement.setAttribute('title', exactTime);

        if (relativeText !== exactTime) {
            timeElement.setAttribute('aria-label', relativeText + ', thời gian chính xác ' + exactTime);
        } else {
            timeElement.setAttribute('aria-label', 'Thời gian chính xác ' + exactTime);
        }

        if (typeof timeElement.setAttribute === 'function') {
            timeElement.setAttribute('data-relative-time', '');
        }

        return true;
    }

    /**
     * Formats all [data-relative-time] elements within the provided root container or document.
     *
     * @param {Document|Element} [root]
     * @param {Date|number} [now]
     * @returns {number} Count of formatted elements
     */
    function formatTree(root, now) {
        const r = root || (typeof document !== 'undefined' ? document : null);
        if (!r) {
            return 0;
        }

        let count = 0;
        if (typeof r.getAttribute === 'function' && r.hasAttribute && r.hasAttribute('data-relative-time')) {
            if (formatElement(r, now)) {
                count++;
            }
        }

        if (typeof r.querySelectorAll === 'function') {
            const elements = r.querySelectorAll('[data-relative-time]');
            if (elements && elements.length > 0) {
                for (let i = 0; i < elements.length; i++) {
                    if (elements[i] !== r && formatElement(elements[i], now)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Initializes the relative time engine:
     * - Performs immediate initial formatTree()
     * - Starts a single shared refresh timer (default 60s)
     * - Multiple init() calls do NOT create duplicate timers
     *
     * @param {Document} [documentArg]
     * @param {Object} [options]
     * @param {number} [options.intervalMs]
     * @param {Function} [options.clock]
     * @param {Object} [options.timerApi]
     */
    function init(documentArg, options) {
        const doc = documentArg || (typeof document !== 'undefined' ? document : null);
        const opts = options || {};
        const clock = typeof opts.clock === 'function' ? opts.clock : (typeof opts.now === 'function' ? opts.now : null);
        const currentNow = clock ? clock() : Date.now();

        // Immediate initial formatting on the provided or active document
        formatTree(doc || activeDoc, currentNow);

        // Invariant: If activeTimer already exists, do NOT alter timerApi, clock, or start duplicate timers
        if (activeTimer != null) {
            return;
        }

        const intervalMs = typeof opts.intervalMs === 'number' ? opts.intervalMs : 60000;
        const timerApi = opts.timerApi || (typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));

        activeDoc = doc;
        activeTimerApi = timerApi;

        if (timerApi && typeof timerApi.setInterval === 'function') {
            activeTimer = timerApi.setInterval(function () {
                const tickNow = clock ? clock() : Date.now();
                formatTree(activeDoc, tickNow);
            }, intervalMs);
        }
    }

    /**
     * Clears the shared timer and resets internal state.
     *
     * @param {Object} [options]
     * @param {Object} [options.timerApi]
     */
    function destroy(options) {
        const opts = options || {};
        const timerApi = activeTimerApi || opts.timerApi || (typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
        if (activeTimer != null) {
            if (timerApi && typeof timerApi.clearInterval === 'function') {
                timerApi.clearInterval(activeTimer);
            }
            activeTimer = null;
        }
        activeDoc = null;
        activeTimerApi = null;
    }

    return {
        format: format,
        formatAbsolute: formatAbsolute,
        formatElement: formatElement,
        formatTree: formatTree,
        init: init,
        destroy: destroy
    };
});
