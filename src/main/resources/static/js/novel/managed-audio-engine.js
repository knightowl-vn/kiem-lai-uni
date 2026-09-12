/**
 * KiemLai Universe — Novel Reader Managed Voice Catalog Client
 *
 * Responsibilities:
 * - Passively loads and caches selectable Managed voices from GET /api/novel/narration/voices.
 * - Provides cancellation and request supersession for catalog discovery.
 * - Provides getVoices() returning safe copies of cached voices.
 * - Owns ZERO playback responsibilities (no audio elements, no manifest, no prepare).
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.ManagedAudioEngine = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.ManagedAudioEngine = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Checks if fetch is supported in the current environment.
     * @returns {boolean}
     */
    function isSupported() {
        return typeof fetch === 'function';
    }

    /**
     * Builds the lightweight public Managed voice catalog endpoint URL.
     * @returns {string}
     */
    function buildVoiceCatalogUrl() {
        return '/api/novel/narration/voices';
    }

    /**
     * Managed Audio Engine (Voice Catalog Client).
     */
    class ManagedAudioEngine {
        /**
         * @param {Object} [options]
         * @param {Function} [options.fetchFunction] - Optional custom fetch function
         */
        constructor(options = {}) {
            this.options = options;
            this.fetchFunction = options.fetchFunction || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
            this.availableVoices = [];
            this._activeVoiceCatalogFetchController = null;
            this._voiceCatalogLoadSequenceId = 0;
        }

        isSupported() {
            return Boolean(this.fetchFunction || typeof fetch === 'function');
        }

        /**
         * Returns a copy of currently cached voices.
         * @returns {Array<Object>}
         */
        getVoices() {
            return this.availableVoices.slice();
        }

        /**
         * Loads the lightweight public Managed voice catalog.
         * Supersedes any pending catalog request and protects against race conditions.
         * @returns {Promise<Object>}
         */
        async loadVoiceCatalog() {
            const sequenceId = ++this._voiceCatalogLoadSequenceId;
            const previousController = this._activeVoiceCatalogFetchController;
            if (previousController) {
                try {
                    previousController.abort();
                } catch (ignored) {}
            }

            const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
            this._activeVoiceCatalogFetchController = controller;
            const fetchFn = this.fetchFunction || fetch;

            try {
                const response = await fetchFn(buildVoiceCatalogUrl(), {
                    headers: { 'Accept': 'application/json' },
                    cache: 'no-store',
                    signal: controller ? controller.signal : undefined
                });
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status + ' when loading Managed voice catalog.');
                }

                const catalog = await response.json();
                if (sequenceId !== this._voiceCatalogLoadSequenceId ||
                    this._activeVoiceCatalogFetchController !== controller) {
                    const abortError = new Error('Managed voice catalog load was aborted or superseded.');
                    abortError.name = 'AbortError';
                    throw abortError;
                }

                if (!catalog || typeof catalog !== 'object' || !Array.isArray(catalog.voices)) {
                    throw new Error('Invalid or malformed Managed voice catalog response.');
                }

                this._activeVoiceCatalogFetchController = null;
                this.availableVoices = catalog && Array.isArray(catalog.voices) ? catalog.voices : [];
                return { voices: this.availableVoices.slice() };
            } catch (error) {
                if (this._activeVoiceCatalogFetchController === controller) {
                    this._activeVoiceCatalogFetchController = null;
                }
                throw error;
            }
        }

        /**
         * Cancels only passive Managed voice catalog discovery.
         */
        cancelVoiceCatalogLoad() {
            this._voiceCatalogLoadSequenceId++;
            if (this._activeVoiceCatalogFetchController) {
                try {
                    this._activeVoiceCatalogFetchController.abort();
                } catch (ignored) {}
                this._activeVoiceCatalogFetchController = null;
            }
        }

        /**
         * Destroys catalog client and clears cached voices.
         */
        destroy() {
            this.cancelVoiceCatalogLoad();
            this.availableVoices = [];
        }
    }

    return {
        ManagedAudioEngine: ManagedAudioEngine,
        isSupported: isSupported,
        buildVoiceCatalogUrl: buildVoiceCatalogUrl
    };
});
