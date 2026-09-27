/**
 * KiemLai Universe — Ephemeral Composer Draft Persistence Foundation (UX-DRAFT-01A)
 *
 * Responsibilities:
 * - Persists unsent user text for a short duration (default 5 minutes) across
 *   accidental page reloads and auth redirects using browser sessionStorage.
 * - Namespaced key design: kiemlai:draft:<domain-context>:<target-id>:<composer-type>
 * - Disappears when tab/session is closed; purges expired entries on encounter.
 * - Safe against quota limits, restricted storage, and malformed data.
 * - Does NOT persist blank/empty text (emptiness trims, original text preserved exactly).
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.EphemeralDraftStore = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.EphemeralDraftStore = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Creates an EphemeralDraftStore instance with optional dependency injection.
     *
     * @param {Object} [options]
     * @param {Storage} [options.storage] - Backing storage, defaults to global sessionStorage
     * @param {Function} [options.clock] - Function returning current epoch millis, defaults to Date.now
     * @param {number} [options.defaultTtlMs] - Default TTL in milliseconds (default: 300,000)
     * @returns {Object} Store instance with save, load, remove methods
     */
    function createStore(options) {
        const opts = options || {};
        const defaultTtl = typeof opts.defaultTtlMs === 'number' && opts.defaultTtlMs > 0
            ? opts.defaultTtlMs
            : DEFAULT_TTL_MS;

        function resolveStorage() {
            try {
                if (opts.storage) {
                    return opts.storage;
                }
                if (typeof window !== 'undefined' && window.sessionStorage) {
                    return window.sessionStorage;
                }
                if (typeof sessionStorage !== 'undefined') {
                    return sessionStorage;
                }
                if (typeof globalThis !== 'undefined' && globalThis.sessionStorage) {
                    return globalThis.sessionStorage;
                }
            } catch (_) {
                return null;
            }
            return null;
        }

        function now() {
            if (typeof opts.clock === 'function') {
                try {
                    return opts.clock();
                } catch (_) {
                    return Date.now();
                }
            }
            return Date.now();
        }

        /**
         * Saves draft text under the specified key.
         * Blank/empty text removes the draft rather than storing empty records.
         * Preserves the user's original text exactly.
         *
         * @param {string} key - Namespaced storage key
         * @param {string} value - User draft text
         * @returns {boolean} true if stored, false if removed or unavailable
         */
        function save(key, value) {
            if (!key || typeof key !== 'string') {
                return false;
            }

            // Trim only for emptiness detection
            if (value == null || typeof value !== 'string' || value.trim().length === 0) {
                remove(key);
                return false;
            }

            try {
                const storage = resolveStorage();
                if (!storage) {
                    return false;
                }

                const record = {
                    value: value,
                    savedAt: now()
                };
                storage.setItem(key, JSON.stringify(record));
                return true;
            } catch (_) {
                // Storage quota exceeded or blocked (e.g. strict privacy mode) -> fail safely
                return false;
            }
        }

        /**
         * Loads draft text for the specified key if present and not expired.
         * Corrupted or expired records are removed on encounter and return null.
         *
         * @param {string} key - Namespaced storage key
         * @returns {string|null} Stored draft text or null
         */
        function load(key) {
            if (!key || typeof key !== 'string') {
                return null;
            }

            try {
                const storage = resolveStorage();
                if (!storage) {
                    return null;
                }

                const raw = storage.getItem(key);
                if (raw == null) {
                    return null;
                }

                let record;
                try {
                    record = JSON.parse(raw);
                } catch (_) {
                    // Malformed JSON -> purge corrupted entry
                    remove(key);
                    return null;
                }

                if (!record || typeof record !== 'object' || typeof record.value !== 'string' || typeof record.savedAt !== 'number') {
                    remove(key);
                    return null;
                }

                const currentTime = now();
                const age = currentTime - record.savedAt;

                if (age < 0 || age > defaultTtl) {
                    // Expired (or clock skewed backwards into future) -> purge
                    remove(key);
                    return null;
                }

                return record.value;
            } catch (_) {
                return null;
            }
        }

        /**
         * Idempotently removes draft text for the specified key.
         *
         * @param {string} key - Namespaced storage key
         */
        function remove(key) {
            if (!key || typeof key !== 'string') {
                return;
            }

            try {
                const storage = resolveStorage();
                if (!storage) {
                    return;
                }

                storage.removeItem(key);
            } catch (_) {
                // Fail safely
            }
        }

        return {
            save: save,
            load: load,
            remove: remove
        };
    }

    // Default global singleton
    const defaultStore = createStore();

    return {
        DEFAULT_TTL_MS: DEFAULT_TTL_MS,
        save: defaultStore.save,
        load: defaultStore.load,
        remove: defaultStore.remove,
        createStore: createStore
    };
});
