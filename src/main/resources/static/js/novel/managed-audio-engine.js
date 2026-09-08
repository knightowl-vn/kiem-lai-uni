/**
 * KiemLai Universe — Novel Reader Managed Audio Engine
 *
 * Responsibilities:
 * - Play KiemLai managed narration audio via HTMLAudioElement.
 * - Load and cache chapter narration manifests by chapterId and voiceKey.
 * - Canonical segment playability & fresh authority:
 *     READY    -> play generated audio
 *     OUTDATED -> play cached audio immediately
 *     MISSING  -> dynamically prepared via fresh POST /prepare; becomes BLOCKED without skipping if still unavailable
 *     FAILED   -> dynamically prepared via fresh POST /prepare; becomes BLOCKED without skipping if still unavailable
 * - Reuse a single HTMLAudioElement instance and replace .src upon fresh preparation authority.
 * - Support play, pause, resume, stop, seekToSegment, previousSegment, nextSegment.
 * - Map playback rate directly to HTMLAudioElement.playbackRate without re-fetching audio.
 * - Advance sequentially to adjacent next segment on 'ended' event via fresh /prepare.
 * - Cleanly transition to STOPPED / onChapterEnd only when the actual final chapter segment ends naturally.
 * - Non-playable adjacent segments become BLOCKED without skipping.
 * - Never expose provider details, storage keys, or internal failure diagnostics.
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
     * Managed engine playback states.
     */
    const ManagedEngineState = Object.freeze({
        UNINITIALIZED: 'UNINITIALIZED',
        IDLE: 'IDLE',
        LOADING: 'LOADING',
        PREPARING: 'PREPARING',
        PLAYING: 'PLAYING',
        PAUSED: 'PAUSED',
        BLOCKED: 'BLOCKED',
        STOPPED: 'STOPPED',
        ERROR: 'ERROR'
    });

    /**
     * Engine capabilities for Managed Audio.
     */
    const ENGINE_CAPABILITIES = Object.freeze({
        canSeekTime: true,
        canSeekSentence: true,
        supportsBackgroundPlayback: true,
        supportsPitch: false,
        supportsRate: true
    });

    /**
     * Checks if HTMLAudioElement is supported in the current browser environment.
     * @returns {boolean}
     */
    function isSupported() {
        return typeof window !== 'undefined' && typeof window.Audio !== 'undefined';
    }

    /**
     * Builds the public narration manifest endpoint URL.
     * @param {string|number} chapterId
     * @param {string} [voiceKey]
     * @returns {string}
     */
    function buildManifestUrl(chapterId, voiceKey) {
        let url = '/api/novel/chapters/' + encodeURIComponent(String(chapterId)) + '/narration/manifest';
        if (voiceKey && typeof voiceKey === 'string' && voiceKey.trim().length > 0) {
            url += '?voiceKey=' + encodeURIComponent(voiceKey.trim());
        }
        return url;
    }

    /**
     * Builds the canonical public narration segment prepare endpoint URL (H.7D1).
     * @param {string|number} chapterId
     * @param {string} segmentId
     * @returns {string}
     */
    function buildPrepareUrl(chapterId, segmentId) {
        return '/api/novel/chapters/' + encodeURIComponent(String(chapterId)) +
               '/narration/segments/' + encodeURIComponent(String(segmentId)) +
               '/prepare';
    }

    /**
     * Resolves CSRF token and header name from standard meta tags or DOM data attributes.
     * @returns {{header: string, token: string}|null}
     */
    function resolveCsrfToken() {
        if (typeof document === 'undefined') {
            return null;
        }
        const metaToken = document.querySelector('meta[name="_csrf"]');
        const metaHeader = document.querySelector('meta[name="_csrf_header"]');
        if (metaToken && metaHeader && metaToken.content && metaHeader.content) {
            return {
                header: metaHeader.content,
                token: metaToken.content
            };
        }
        const elWithCsrf = document.querySelector('[data-csrf-token][data-csrf-header]') ||
                           document.getElementById('novelNarrationPlayer') ||
                           document.getElementById('novelReadingProgressTracker') ||
                           document.getElementById('novelReadingHistoryTracker') ||
                           document.getElementById('novelChapterBookmarkBtn');
        if (elWithCsrf) {
            const token = elWithCsrf.getAttribute('data-csrf-token') || (elWithCsrf.dataset && elWithCsrf.dataset.csrfToken);
            const header = elWithCsrf.getAttribute('data-csrf-header') || (elWithCsrf.dataset && elWithCsrf.dataset.csrfHeader);
            if (token && header) {
                return {
                    header: header,
                    token: token
                };
            }
        }
        return null;
    }

    /**
     * Diagnostic timing logger gated by window.__KIEMLAI_NARRATION_DEBUG__ (H.7D8 Audit).
     * @param {string} eventName
     * @param {Object} details
     */
    function debugLogNarration(eventName, details) {
        if (typeof window !== 'undefined' && window.__KIEMLAI_NARRATION_DEBUG__ === true) {
            const now = (typeof performance !== 'undefined' && typeof performance.now === 'function')
                ? performance.now()
                : Date.now();
            const logEntry = Object.assign({ event: eventName, timestamp: now }, details);
            console.log('[KiemLai Narration Debug]', logEntry);
        }
    }

    /**
     * Managed Audio Engine implementation.
     */
    class ManagedAudioEngine {
        /**
         * @param {Object} [options]
         * @param {Function} [options.onStateChange]
         * @param {Function} [options.onChunkStart]  - Alias for onSegmentStart
         * @param {Function} [options.onSegmentStart]
         * @param {Function} [options.onChunkEnd]    - Alias for onSegmentEnd
         * @param {Function} [options.onSegmentEnd]
         * @param {Function} [options.onChapterEnd]
         * @param {Function} [options.onError]
         * @param {Function} [options.onManifestLoaded]
         * @param {Object} [options.audioElement]   - Optional audio element override (useful for testing)
         * @param {Function} [options.fetchFunction] - Optional custom fetch function
         */
        constructor(options = {}) {
            this.options = options;
            this.state = ManagedEngineState.UNINITIALIZED;
            this.supported = isSupported();

            // Manifest data
            this.chapterId = null;
            this.selectedVoice = null;
            this.availableVoices = [];
            this.segments = []; // Array of segment DTOs from manifest
            this.currentSegmentIndex = 0;

            // Audio element & properties
            this.audio = options.audioElement || (this.supported ? new Audio() : null);
            this.rate = 1.0;
            this.volume = 1.0;

            // In-memory cache: Map<chapterId:voiceKey, manifestDTO>
            this.manifestCache = new Map();

            // Fetch coordination
            this.fetchFunction = options.fetchFunction || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
            this._activeFetchController = null;
            this._activeManifestFetchController = null;
            this._activePrefetchController = null;
            this._playbackSequenceId = 0;
            this._manifestLoadSequenceId = 0;
            this._prefetchSequenceId = 0;
            this._warmedSegment = null;
            this._retiredAudio = null;
            this._boundOnAudioEnded = this._handleAudioEnded.bind(this);
            this._boundOnAudioError = this._handleAudioError.bind(this);
            this._boundOnAudioPlay = this._handleAudioPlay.bind(this);
            this._boundOnAudioPause = this._handleAudioPause.bind(this);
            this._boundOnAudioPlaying = this._handleAudioPlaying.bind(this);

            if (this.audio) {
                this._bindAudioListeners();
            }

            if (this.supported) {
                this._transitionState(ManagedEngineState.IDLE);
            } else {
                this._transitionState(ManagedEngineState.ERROR);
            }
        }

        /**
         * Binds HTMLAudioElement event listeners.
         * @private
         */
        _bindAudioListeners() {
            if (!this.audio) {
                return;
            }
            this.audio.addEventListener('ended', this._boundOnAudioEnded);
            this.audio.addEventListener('error', this._boundOnAudioError);
            this.audio.addEventListener('play', this._boundOnAudioPlay);
            this.audio.addEventListener('pause', this._boundOnAudioPause);
            this.audio.addEventListener('playing', this._boundOnAudioPlaying);
        }

        /**
         * Removes HTMLAudioElement event listeners.
         * @private
         */
        _unbindAudioListeners() {
            if (!this.audio) {
                return;
            }
            this.audio.removeEventListener('ended', this._boundOnAudioEnded);
            this.audio.removeEventListener('error', this._boundOnAudioError);
            this.audio.removeEventListener('play', this._boundOnAudioPlay);
            this.audio.removeEventListener('pause', this._boundOnAudioPause);
            this.audio.removeEventListener('playing', this._boundOnAudioPlaying);
        }

        /**
         * Releases the previously retired Audio resource outside the audible fast-path boundary.
         * @private
         */
        _releaseRetiredAudioResource() {
            if (!this._retiredAudio || this._retiredAudio === this.audio) {
                this._retiredAudio = null;
                return;
            }

            try {
                this._retiredAudio.pause();
                this._retiredAudio.src = '';
            } catch (ignored) {}
            this._retiredAudio = null;
        }

        /**
         * Returns engine capabilities.
         * @returns {Object}
         */
        getCapabilities() {
            return ENGINE_CAPABILITIES;
        }

        /**
         * Returns whether HTMLAudioElement is supported.
         * @returns {boolean}
         */
        isSupported() {
            return this.supported;
        }

        /**
         * Returns current playback state.
         * @returns {string}
         */
        getState() {
            return this.state;
        }

        /**
         * Returns list of available managed voices from current manifest.
         * @returns {Array<Object>}
         */
        getVoices() {
            return this.availableVoices.slice();
        }

        /**
         * Returns currently selected voice DTO.
         * @returns {Object|null}
         */
        getSelectedVoice() {
            return this.selectedVoice;
        }

        /**
         * Returns currently selected voiceKey.
         * @returns {string|null}
         */
        getSelectedVoiceKey() {
            if (this.selectedVoice && this.selectedVoice.voiceKey) {
                return this.selectedVoice.voiceKey;
            }
            if (this.availableVoices && this.availableVoices.length > 0) {
                const def = this.availableVoices.find(v => v.defaultVoice) || this.availableVoices[0];
                return def ? def.voiceKey : null;
            }
            return null;
        }

        /**
         * Returns currently loaded segments.
         * @returns {Array<Object>}
         */
        getSegments() {
            return this.segments.slice();
        }

        /**
         * Compatibility alias for getSegments().
         * @returns {Array<Object>}
         */
        getChunks() {
            return this.getSegments();
        }

        /**
         * Returns current segment index.
         * @returns {number}
         */
        getCurrentSegmentIndex() {
            return this.currentSegmentIndex;
        }

        /**
         * Compatibility alias for getCurrentSegmentIndex().
         * @returns {number}
         */
        getCurrentChunkIndex() {
            return this.currentSegmentIndex;
        }

        /**
         * Returns total segments count.
         * @returns {number}
         */
        getTotalSegments() {
            return this.segments.length;
        }

        /**
         * Compatibility alias for getTotalSegments().
         * @returns {number}
         */
        getTotalChunks() {
            return this.segments.length;
        }

        /**
         * Returns current segment DTO.
         * @returns {Object|null}
         */
        getCurrentSegment() {
            return this.segments[this.currentSegmentIndex] || null;
        }

        /**
         * Compatibility alias for getCurrentSegment().
         * @returns {Object|null}
         */
        getCurrentChunk() {
            return this.getCurrentSegment();
        }

        /**
         * Transitions engine state and notifies listener.
         * @param {string} newState
         * @private
         */
        _transitionState(newState) {
            if (this.state === newState) {
                return;
            }
            const prevState = this.state;
            this.state = newState;

            if (typeof this.options.onStateChange === 'function') {
                try {
                    this.options.onStateChange(newState, prevState);
                } catch (e) {
                    console.error('[ManagedAudioEngine] Error in onStateChange callback:', e);
                }
            }
        }

        /**
         * Sets playback rate (0.5 to 2.0).
         * Directly adjusts HTMLAudioElement.playbackRate without re-requesting audio.
         * @param {number} rate
         */
        setRate(rate) {
            const num = Number(rate);
            if (Number.isFinite(num)) {
                this.rate = Math.max(0.5, Math.min(2.0, num));
                if (this.audio) {
                    this.audio.playbackRate = this.rate;
                }
            }
        }

        /**
         * Sets volume (0.0 to 1.0).
         * @param {number} volume
         */
        setVolume(volume) {
            const num = Number(volume);
            if (Number.isFinite(num)) {
                this.volume = Math.max(0.0, Math.min(1.0, num));
                if (this.audio) {
                    this.audio.volume = this.volume;
                }
            }
        }

        /**
         * Applies an already fetched manifest DTO.
         * @param {Object} manifest
         * @param {number} [startSegmentIndex=0]
         */
        setManifest(manifest, startSegmentIndex = 0) {
            this.stop();

            if (!manifest || typeof manifest !== 'object') {
                this.segments = [];
                this.availableVoices = [];
                this.selectedVoice = null;
                this._transitionState(ManagedEngineState.STOPPED);
                return;
            }

            this.chapterId = manifest.chapterId || this.chapterId;
            this.availableVoices = Array.isArray(manifest.availableVoices) ? manifest.availableVoices : [];
            this.selectedVoice = manifest.selectedVoice || null;
            this.segments = Array.isArray(manifest.segments) ? manifest.segments : [];

            // Cache manifest
            if (this.chapterId && this.selectedVoice && this.selectedVoice.voiceKey) {
                const cacheKey = String(this.chapterId) + ':' + this.selectedVoice.voiceKey;
                this.manifestCache.set(cacheKey, manifest);
            }

            const rawIndex = Number(startSegmentIndex);
            this.currentSegmentIndex = Number.isFinite(rawIndex)
                ? Math.max(0, Math.min(Math.max(0, this.segments.length - 1), Math.floor(rawIndex)))
                : 0;

            this._transitionState(this.segments.length > 0 ? ManagedEngineState.IDLE : ManagedEngineState.STOPPED);

            if (typeof this.options.onManifestLoaded === 'function') {
                try {
                    this.options.onManifestLoaded(manifest);
                } catch (e) {
                    console.error('[ManagedAudioEngine] Error in onManifestLoaded callback:', e);
                }
            }
        }

        /**
         * Fetches and applies the narration manifest for chapterId and voiceKey.
         * Uses in-memory cache if available.
         * @param {string|number} chapterId
         * @param {string} [voiceKey]
         * @returns {Promise<Object>}
         */
        async loadManifest(chapterId, voiceKey = null) {
            if (!chapterId) {
                throw new Error('chapterId is required to load narration manifest.');
            }

            const sequenceId = ++this._manifestLoadSequenceId;

            const prevController = this._activeManifestFetchController;
            if (prevController) {
                try {
                    prevController.abort();
                } catch (ignored) {}
                if (this._activeManifestFetchController === prevController) {
                    this._activeManifestFetchController = null;
                }
            }

            const cleanVoiceKey = (voiceKey && typeof voiceKey === 'string') ? voiceKey.trim() : null;
            const cacheKey = cleanVoiceKey ? (String(chapterId) + ':' + cleanVoiceKey) : null;

            if (cacheKey && this.manifestCache.has(cacheKey)) {
                const cached = this.manifestCache.get(cacheKey);
                this.setManifest(cached, this.currentSegmentIndex);
                return cached;
            }

            const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
            this._activeManifestFetchController = controller;

            const url = buildManifestUrl(chapterId, cleanVoiceKey);
            const fetchFn = this.fetchFunction || fetch;

            try {
                const response = await fetchFn(url, {
                    headers: { 'Accept': 'application/json' },
                    cache: 'no-store',
                    signal: controller ? controller.signal : undefined
                });

                if (!response.ok) {
                    throw new Error('HTTP ' + response.status + ' when loading narration manifest.');
                }

                const manifest = await response.json();

                // Manifest commit guard: sequence ownership AND controller ownership
                if (sequenceId !== this._manifestLoadSequenceId || this._activeManifestFetchController !== controller) {
                    const abortError = new Error('Manifest load was aborted or superseded.');
                    abortError.name = 'AbortError';
                    throw abortError;
                }

                if (this._activeManifestFetchController === controller) {
                    this._activeManifestFetchController = null;
                }

                // Cache under both the requested voiceKey and the manifest's resolved selectedVoiceKey
                if (cleanVoiceKey) {
                    this.manifestCache.set(String(chapterId) + ':' + cleanVoiceKey, manifest);
                }
                if (manifest.selectedVoice && manifest.selectedVoice.voiceKey) {
                    this.manifestCache.set(String(chapterId) + ':' + manifest.selectedVoice.voiceKey, manifest);
                }

                this.setManifest(manifest, this.currentSegmentIndex);
                return manifest;
            } catch (error) {
                if (error && error.name === 'AbortError') {
                    throw error;
                }
                if (sequenceId !== this._manifestLoadSequenceId || this._activeManifestFetchController !== controller) {
                    throw error;
                }
                if (this._activeManifestFetchController === controller) {
                    this._activeManifestFetchController = null;
                }
                console.warn('[ManagedAudioEngine] Failed to fetch manifest:', error);
                this._handleError(error);
                throw error;
            }
        }

        /**
         * Finds the first playable segment index at or after startIndex.
         * Returns -1 if no playable segment is found.
         * @param {number} startIndex
         * @returns {number}
         */
        findNextPlayableIndex(startIndex) {
            const start = Math.max(0, startIndex);
            for (let i = start; i < this.segments.length; i++) {
                const seg = this.segments[i];
                if (seg && seg.playable === true && seg.audioUrl) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Finds the first playable segment index at or before startIndex.
         * Returns -1 if no playable segment is found.
         * @param {number} startIndex
         * @returns {number}
         */
        findPreviousPlayableIndex(startIndex) {
            const start = Math.min(this.segments.length - 1, startIndex);
            for (let i = start; i >= 0; i--) {
                const seg = this.segments[i];
                if (seg && seg.playable === true && seg.audioUrl) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Checks if there are any playable segments in current manifest.
         * @returns {boolean}
         */
        hasPlayableSegments() {
            return this.findNextPlayableIndex(0) !== -1;
        }

        /**
         * Cancels any pending adjacent speculative prefetch and clears warmed standby audio.
         * @private
         */
        _cancelPrefetch() {
            this._prefetchSequenceId++;
            if (this._activePrefetchController) {
                try {
                    this._activePrefetchController.abort();
                } catch (ignored) {}
                this._activePrefetchController = null;
            }
            if (this._warmedSegment) {
                const standby = this._warmedSegment.standbyAudio || this._warmedSegment.preloadAudio;
                if (standby && standby !== this.audio) {
                    try {
                        standby.pause();
                        standby.src = '';
                    } catch (ignored) {}
                }
            }
            this._warmedSegment = null;
        }

        /**
         * Speculatively prepares and warms ONLY the adjacent next segment (N + 1) into an isolated standby Audio element while segment N is PLAYING.
         * Never mutates active playback state or replaces active HTMLAudioElement.
         * @param {number} currentIndex
         * @private
         */
        _scheduleAdjacentWarmUp(currentIndex) {
            if (this.state !== ManagedEngineState.PLAYING) {
                return;
            }

            const nextIndex = currentIndex + 1;
            if (nextIndex >= this.segments.length) {
                return; // No adjacent N+1 exists (end of chapter)
            }

            const nextSegment = this.segments[nextIndex];
            if (!nextSegment || !nextSegment.segmentId) {
                return;
            }

            const voiceKey = this.getSelectedVoiceKey();
            if (!this.chapterId || !voiceKey) {
                return;
            }

            debugLogNarration('warmup-start', {
                segmentIndex: nextIndex,
                segmentId: nextSegment.segmentId,
                voiceKey: voiceKey
            });

            this._cancelPrefetch();

            const prefetchSequence = this._prefetchSequenceId;
            const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
            this._activePrefetchController = controller;

            const requestedChapterId = this.chapterId;
            const requestedSegmentId = nextSegment.segmentId;
            const requestedVoiceKey = voiceKey;
            const requestedNextIndex = nextIndex;

            const specStartTime = (typeof performance !== 'undefined' && typeof performance.now === 'function')
                ? performance.now()
                : Date.now();
            debugLogNarration('speculative-prepare-start', {
                segmentIndex: requestedNextIndex,
                segmentId: requestedSegmentId,
                voiceKey: requestedVoiceKey
            });

            this.prepareSegmentPlayback(
                requestedChapterId,
                requestedSegmentId,
                requestedVoiceKey,
                controller ? controller.signal : undefined
            ).then(playbackDto => {
                const specElapsed = ((typeof performance !== 'undefined' && typeof performance.now === 'function')
                    ? performance.now()
                    : Date.now()) - specStartTime;
                debugLogNarration('speculative-prepare-end', {
                    segmentIndex: requestedNextIndex,
                    segmentId: requestedSegmentId,
                    voiceKey: requestedVoiceKey,
                    elapsedMs: Math.round(specElapsed * 100) / 100,
                    playableNow: Boolean(playbackDto && playbackDto.playableNow)
                });

                if (prefetchSequence !== this._prefetchSequenceId || this._activePrefetchController !== controller) {
                    return;
                }
                if (this._activePrefetchController === controller) {
                    this._activePrefetchController = null;
                }
                if (this.chapterId !== requestedChapterId || this.getSelectedVoiceKey() !== requestedVoiceKey) {
                    return;
                }
                if (this.state !== ManagedEngineState.PLAYING || this.currentSegmentIndex !== currentIndex) {
                    return;
                }

                if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl) {
                    let standbyAudio = null;
                    if (typeof window !== 'undefined' && typeof window.Audio !== 'undefined') {
                        try {
                            standbyAudio = new Audio();
                            standbyAudio.preload = 'auto';

                            this._warmedSegment = {
                                prefetchSequence: prefetchSequence,
                                chapterId: requestedChapterId,
                                sourceIndex: currentIndex,
                                segmentIndex: requestedNextIndex,
                                segmentId: requestedSegmentId,
                                voiceKey: requestedVoiceKey,
                                audioUrl: playbackDto.audioUrl,
                                playbackDto: playbackDto,
                                standbyAudio: standbyAudio,
                                preloadAudio: standbyAudio,
                                isReady: false,
                                consumed: false
                            };
                            const warmEntry = this._warmedSegment;

                            const onStandbyReady = (event) => {
                                if (event && event.type === 'canplay') {
                                    debugLogNarration('standby-canplay', {
                                        segmentIndex: requestedNextIndex,
                                        segmentId: requestedSegmentId,
                                        voiceKey: requestedVoiceKey,
                                        readyState: standbyAudio.readyState
                                    });
                                } else if (event && event.type === 'canplaythrough') {
                                    debugLogNarration('standby-canplaythrough', {
                                        segmentIndex: requestedNextIndex,
                                        segmentId: requestedSegmentId,
                                        voiceKey: requestedVoiceKey,
                                        readyState: standbyAudio.readyState
                                    });
                                }
                                if (prefetchSequence !== this._prefetchSequenceId) {
                                    return;
                                }
                                if (this.chapterId !== requestedChapterId || this.getSelectedVoiceKey() !== requestedVoiceKey) {
                                    return;
                                }
                                warmEntry.isReady = true;
                            };

                            standbyAudio.addEventListener('canplay', onStandbyReady, { once: true });
                            standbyAudio.addEventListener('canplaythrough', onStandbyReady, { once: true });

                            standbyAudio.src = playbackDto.audioUrl;
                            debugLogNarration('standby-src-assigned', {
                                segmentIndex: requestedNextIndex,
                                segmentId: requestedSegmentId,
                                voiceKey: requestedVoiceKey,
                                readyState: standbyAudio.readyState
                            });

                            if (typeof standbyAudio.load === 'function') {
                                standbyAudio.load();
                            }

                            if (standbyAudio.readyState >= 3) {
                                warmEntry.isReady = true;
                            }
                        } catch (ignored) {}
                    }
                }
            }).catch(() => {
                if (this._activePrefetchController === controller) {
                    this._activePrefetchController = null;
                }
            });
        }

        /**
         * Invokes canonical POST /prepare endpoint for the specified segment and voiceKey (H.7D1).
         * @param {string|number} chapterId
         * @param {string} segmentId
         * @param {string} voiceKey
         * @param {AbortSignal} [signal]
         * @returns {Promise<Object>} PublicReaderNarrationPlaybackDTO
         */
        async prepareSegmentPlayback(chapterId, segmentId, voiceKey, signal) {
            if (!chapterId || !segmentId || !voiceKey) {
                throw new Error('chapterId, segmentId, and voiceKey are required to prepare narration playback.');
            }

            const url = buildPrepareUrl(chapterId, segmentId);
            const fetchFn = this.fetchFunction || fetch;
            const headers = {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            };

            const csrf = (typeof this.options.csrfProvider === 'function')
                ? this.options.csrfProvider()
                : resolveCsrfToken();
            if (csrf && csrf.header && csrf.token) {
                headers[csrf.header] = csrf.token;
            }

            const response = await fetchFn(url, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({ voiceKey: String(voiceKey).trim() }),
                signal: signal
            });

            if (!response.ok) {
                throw new Error('HTTP ' + response.status + ' when preparing narration segment playback.');
            }

            return await response.json();
        }

        /**
         * Starts playback from specified or current segment index.
         * Triggers canonical on-demand preparation via POST /prepare when chapter and segment IDs exist.
         * Promotes standby Audio element if adjacent double-buffer matches.
         * Supports cross-segment seek offsets and state preservation.
         * @param {number} [startSegmentIndex]
         * @param {Object} [seekOptions]
         * @param {number} [seekOptions.seekFromStart]
         * @param {number} [seekOptions.seekFromEnd]
         * @param {boolean} [seekOptions.paused]
         * @returns {Promise<void>}
         */
        async play(startSegmentIndex, seekOptions = null) {
            if (!this.supported) {
                this._handleError(new Error('HTMLAudioElement is not supported in this browser.'));
                return;
            }

            if (this.segments.length === 0) {
                return;
            }

            let targetIndex = (typeof startSegmentIndex === 'number')
                ? Math.max(0, Math.min(this.segments.length - 1, Math.floor(startSegmentIndex)))
                : this.currentSegmentIndex;

            this.currentSegmentIndex = targetIndex;
            const segment = this.segments[targetIndex];
            if (!segment) {
                return;
            }

            this._releaseRetiredAudioResource();

            // Invalidate and abort any in-flight manifest network request when starting playback
            if (this._activeManifestFetchController) {
                this._manifestLoadSequenceId++;
                const manifestCtrl = this._activeManifestFetchController;
                try {
                    manifestCtrl.abort();
                } catch (ignored) {}
                if (this._activeManifestFetchController === manifestCtrl) {
                    this._activeManifestFetchController = null;
                }
            }

            const voiceKey = this.getSelectedVoiceKey();

            // On-demand preparation via POST /prepare if chapterId, segmentId, and voiceKey exist
            if (this.chapterId && segment.segmentId && voiceKey) {
                // Start-of-prepare safety: pause any currently playing audio before preparing new segment
                if (this.audio) {
                    try {
                        this.audio.pause();
                    } catch (ignored) {}
                }

                if (this._activeFetchController) {
                    try {
                        this._activeFetchController.abort();
                    } catch (ignored) {}
                }

                const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
                this._activeFetchController = controller;

                this._playbackSequenceId++;
                const sequenceId = this._playbackSequenceId;
                const requestedIndex = targetIndex;
                const requestedSegmentId = segment.segmentId;

                this._transitionState(ManagedEngineState.PREPARING);

                const canonicalStartTime = (typeof performance !== 'undefined' && typeof performance.now === 'function')
                    ? performance.now()
                    : Date.now();
                debugLogNarration('canonical-prepare-start', {
                    segmentIndex: requestedIndex,
                    segmentId: requestedSegmentId,
                    voiceKey: voiceKey
                });

                try {
                    const playbackDto = await this.prepareSegmentPlayback(
                        this.chapterId,
                        requestedSegmentId,
                        voiceKey,
                        controller ? controller.signal : undefined
                    );

                    const canonicalElapsed = ((typeof performance !== 'undefined' && typeof performance.now === 'function')
                        ? performance.now()
                        : Date.now()) - canonicalStartTime;
                    debugLogNarration('canonical-prepare-end', {
                        segmentIndex: requestedIndex,
                        segmentId: requestedSegmentId,
                        voiceKey: voiceKey,
                        elapsedMs: Math.round(canonicalElapsed * 100) / 100
                    });

                    const currentSegment = this.segments[this.currentSegmentIndex];
                    const isStale = (sequenceId !== this._playbackSequenceId) ||
                                    (this.currentSegmentIndex !== requestedIndex) ||
                                    (!currentSegment || currentSegment.segmentId !== requestedSegmentId);

                    if (isStale) {
                        return; // Stale/superseded by another user action or navigation
                    }

                    this._activeFetchController = null;

                    if (playbackDto && playbackDto.playableNow && playbackDto.audioUrl) {
                        segment.playable = true;
                        segment.audioUrl = playbackDto.audioUrl;
                        segment.healthStatus = playbackDto.healthStatus;

                        const isWarmedMatch = this._warmedSegment &&
                            this._warmedSegment.chapterId === this.chapterId &&
                            this._warmedSegment.segmentId === requestedSegmentId &&
                            this._warmedSegment.voiceKey === voiceKey &&
                            this._warmedSegment.audioUrl === playbackDto.audioUrl &&
                            (this._warmedSegment.isReady === true || (this._warmedSegment.standbyAudio && this._warmedSegment.standbyAudio.readyState >= 3)) &&
                            (this._warmedSegment.standbyAudio || this._warmedSegment.preloadAudio);

                        debugLogNarration('warmed-match', {
                            segmentIndex: requestedIndex,
                            segmentId: requestedSegmentId,
                            voiceKey: voiceKey,
                            isWarmedMatch: Boolean(isWarmedMatch),
                            hasWarmedSegment: Boolean(this._warmedSegment),
                            isReady: this._warmedSegment ? this._warmedSegment.isReady : null,
                            standbyReadyState: (this._warmedSegment && this._warmedSegment.standbyAudio) ? this._warmedSegment.standbyAudio.readyState : null
                        });

                        const targetUrl = playbackDto.audioUrl;

                        if (isWarmedMatch) {
                            // Deterministic Double Buffering: PROMOTE buffered standby audio element
                            debugLogNarration('standby-promoted', {
                                segmentIndex: requestedIndex,
                                segmentId: requestedSegmentId,
                                voiceKey: voiceKey
                            });
                            const promotedAudio = this._warmedSegment.standbyAudio || this._warmedSegment.preloadAudio;
                            this._unbindAudioListeners();
                            if (this.audio && this.audio !== promotedAudio) {
                                try {
                                    this.audio.pause();
                                    this.audio.src = '';
                                } catch (ignored) {}
                            }
                            this.audio = promotedAudio;
                            this._bindAudioListeners();
                            this._warmedSegment = null;
                        } else {
                            debugLogNarration('fallback-src-assigned', {
                                segmentIndex: requestedIndex,
                                segmentId: requestedSegmentId,
                                voiceKey: voiceKey
                            });
                            this._cancelPrefetch();
                            if (this.audio) {
                                if (this.audio.src !== targetUrl && !this.audio.src.endsWith(targetUrl)) {
                                    this.audio.src = targetUrl;
                                }
                            }
                        }

                        if (this.audio) {
                            this.audio.playbackRate = this.rate;
                            this.audio.volume = this.volume;

                            const targetAudio = this.audio;
                            const targetSequenceId = sequenceId;
                            const targetSegmentIndex = requestedIndex;
                            const targetSegmentId = requestedSegmentId;
                            const targetChapterId = this.chapterId;
                            const targetVoiceKey = voiceKey;

                            const isSeekAuthorityValid = () => {
                                return targetAudio === this.audio &&
                                       targetSequenceId === this._playbackSequenceId &&
                                       this.currentSegmentIndex === targetSegmentIndex &&
                                       this.chapterId === targetChapterId &&
                                       this.getSelectedVoiceKey() === targetVoiceKey;
                            };

                            if (seekOptions && typeof seekOptions.seekFromStart === 'number') {
                                const seekStartOffset = Math.max(0, seekOptions.seekFromStart);
                                let seekApplied = false;
                                const applySeekStart = () => {
                                    if (seekApplied || !isSeekAuthorityValid()) {
                                        return;
                                    }
                                    seekApplied = true;
                                    try {
                                        targetAudio.currentTime = seekStartOffset;
                                    } catch (ignored) {}
                                };

                                if (targetAudio.readyState >= 1) {
                                    applySeekStart();
                                } else {
                                    targetAudio.addEventListener('loadedmetadata', applySeekStart, { once: true });
                                    targetAudio.addEventListener('canplay', applySeekStart, { once: true });
                                }
                            } else if (seekOptions && typeof seekOptions.seekFromEnd === 'number') {
                                const seekEndAmount = Math.max(0, seekOptions.seekFromEnd);
                                let seekApplied = false;
                                const applySeekEnd = () => {
                                    if (seekApplied || !isSeekAuthorityValid()) {
                                        return;
                                    }
                                    if (Number.isFinite(targetAudio.duration) && targetAudio.duration > 0) {
                                        seekApplied = true;
                                        try {
                                            targetAudio.currentTime = Math.max(0, targetAudio.duration - seekEndAmount);
                                        } catch (ignored) {}
                                    }
                                };
                                if (targetAudio.readyState >= 1 && Number.isFinite(targetAudio.duration) && targetAudio.duration > 0) {
                                    applySeekEnd();
                                } else {
                                    targetAudio.addEventListener('loadedmetadata', applySeekEnd, { once: true });
                                    targetAudio.addEventListener('canplay', applySeekEnd, { once: true });
                                }
                            } else {
                                try {
                                    this.audio.currentTime = 0;
                                } catch (ignored) {}
                            }
                        }

                        const shouldBePaused = Boolean(seekOptions && seekOptions.paused);

                        if (shouldBePaused) {
                            this._transitionState(ManagedEngineState.PAUSED);
                            this._notifySegmentStart(requestedIndex, segment, playbackDto);
                            if (this.audio) {
                                try {
                                    this.audio.pause();
                                } catch (ignored) {}
                            }
                        } else {
                            this._transitionState(ManagedEngineState.PLAYING);
                            this._notifySegmentStart(requestedIndex, segment, playbackDto);

                            if (this.audio) {
                                debugLogNarration('audio-play-called', {
                                    segmentIndex: requestedIndex,
                                    segmentId: requestedSegmentId,
                                    voiceKey: voiceKey,
                                    readyState: this.audio ? this.audio.readyState : null
                                });
                                const playPromise = this.audio.play();
                                this._scheduleAdjacentWarmUp(requestedIndex);
                                if (playPromise && typeof playPromise.catch === 'function') {
                                    playPromise.catch((err) => {
                                        if (sequenceId !== this._playbackSequenceId) {
                                            return;
                                        }
                                        if (err && err.name === 'AbortError') {
                                            if (this.state === ManagedEngineState.PLAYING) {
                                                this._transitionState(ManagedEngineState.PAUSED);
                                            }
                                            return;
                                        }
                                        this._handleError(err);
                                    });
                                }
                            }
                        }
                    } else {
                        segment.playable = false;
                        if (playbackDto && playbackDto.healthStatus) {
                            segment.healthStatus = playbackDto.healthStatus;
                        }
                        this._transitionState(ManagedEngineState.BLOCKED);
                        this._notifyBlocked(requestedIndex, segment, playbackDto);
                    }
                } catch (error) {
                    if (error && error.name === 'AbortError') {
                        return;
                    }
                    const currentSegment = this.segments[this.currentSegmentIndex];
                    if (sequenceId !== this._playbackSequenceId ||
                        this.currentSegmentIndex !== requestedIndex ||
                        !currentSegment ||
                        currentSegment.segmentId !== requestedSegmentId) {
                        return;
                    }
                    this._activeFetchController = null;
                    console.warn('[ManagedAudioEngine] Preparation error for segment:', requestedSegmentId, error);
                    this._handleError(error);
                }
                return;
            }

            // Direct playback fallback (e.g. offline testing or pre-populated audioUrl)
            const playableIndex = this.findNextPlayableIndex(targetIndex);
            if (playableIndex === -1) {
                this._transitionState(ManagedEngineState.STOPPED);
                if (typeof this.options.onChapterEnd === 'function') {
                    try {
                        this.options.onChapterEnd();
                    } catch (e) {
                        console.error('[ManagedAudioEngine] Error in onChapterEnd callback:', e);
                    }
                }
                return;
            }

            this.currentSegmentIndex = playableIndex;
            this._playbackSequenceId++;
            this._playCurrentSegment(seekOptions);
        }

        /**
         * Internal routine to load and play audio for this.currentSegmentIndex.
         * @param {Object} [seekOptions]
         * @private
         */
        _playCurrentSegment(seekOptions = null) {
            if (!this.audio) {
                return;
            }

            const segment = this.segments[this.currentSegmentIndex];
            if (!segment || !segment.playable || !segment.audioUrl) {
                // Advance to next playable
                const nextPlayable = this.findNextPlayableIndex(this.currentSegmentIndex + 1);
                if (nextPlayable !== -1) {
                    this.currentSegmentIndex = nextPlayable;
                    this._playCurrentSegment(seekOptions);
                } else {
                    this._transitionState(ManagedEngineState.STOPPED);
                    if (typeof this.options.onChapterEnd === 'function') {
                        try {
                            this.options.onChapterEnd();
                        } catch (e) {
                            console.error('[ManagedAudioEngine] Error in onChapterEnd callback:', e);
                        }
                    }
                }
                return;
            }

            const sequenceId = this._playbackSequenceId;
            const targetUrl = segment.audioUrl;

            // Reuse existing audio element and update src
            if (this.audio.src !== targetUrl && !this.audio.src.endsWith(targetUrl)) {
                this.audio.src = targetUrl;
            }

            if (this.audio) {
                this.audio.playbackRate = this.rate;
                this.audio.volume = this.volume;

                const targetAudio = this.audio;
                const targetSequenceId = sequenceId;
                const targetSegmentIndex = this.currentSegmentIndex;
                const targetSegmentId = segment.segmentId;
                const targetChapterId = this.chapterId;
                const targetVoiceKey = this.getSelectedVoiceKey();

                const isSeekAuthorityValid = () => {
                    return targetAudio === this.audio &&
                           targetSequenceId === this._playbackSequenceId &&
                           this.currentSegmentIndex === targetSegmentIndex &&
                           this.chapterId === targetChapterId &&
                           this.getSelectedVoiceKey() === targetVoiceKey;
                };

                if (seekOptions && typeof seekOptions.seekFromStart === 'number') {
                    const seekStartOffset = Math.max(0, seekOptions.seekFromStart);
                    let seekApplied = false;
                    const applySeekStart = () => {
                        if (seekApplied || !isSeekAuthorityValid()) {
                            return;
                        }
                        seekApplied = true;
                        try {
                            targetAudio.currentTime = seekStartOffset;
                        } catch (ignored) {}
                    };

                    if (targetAudio.readyState >= 1) {
                        applySeekStart();
                    } else {
                        targetAudio.addEventListener('loadedmetadata', applySeekStart, { once: true });
                        targetAudio.addEventListener('canplay', applySeekStart, { once: true });
                    }
                } else if (seekOptions && typeof seekOptions.seekFromEnd === 'number') {
                    const seekEndAmount = Math.max(0, seekOptions.seekFromEnd);
                    let seekApplied = false;
                    const applySeekEnd = () => {
                        if (seekApplied || !isSeekAuthorityValid()) {
                            return;
                        }
                        if (Number.isFinite(targetAudio.duration) && targetAudio.duration > 0) {
                            seekApplied = true;
                            try {
                                targetAudio.currentTime = Math.max(0, targetAudio.duration - seekEndAmount);
                            } catch (ignored) {}
                        }
                    };
                    if (targetAudio.readyState >= 1 && Number.isFinite(targetAudio.duration) && targetAudio.duration > 0) {
                        applySeekEnd();
                    } else {
                        targetAudio.addEventListener('loadedmetadata', applySeekEnd, { once: true });
                        targetAudio.addEventListener('canplay', applySeekEnd, { once: true });
                    }
                } else {
                    try {
                        this.audio.currentTime = 0;
                    } catch (ignored) {}
                }
            }

            const shouldBePaused = Boolean(seekOptions && seekOptions.paused);

            if (shouldBePaused) {
                this._transitionState(ManagedEngineState.PAUSED);
                this._notifySegmentStart(this.currentSegmentIndex, segment);
                try {
                    this.audio.pause();
                } catch (ignored) {}
            } else {
                this._transitionState(ManagedEngineState.PLAYING);
                this._notifySegmentStart(this.currentSegmentIndex, segment);

                const playPromise = this.audio.play();
                this._scheduleAdjacentWarmUp(this.currentSegmentIndex);
                if (playPromise && typeof playPromise.catch === 'function') {
                    playPromise.catch((err) => {
                        if (sequenceId !== this._playbackSequenceId) {
                            return; // Stale/superseded by another action
                        }
                        if (err && err.name === 'AbortError') {
                            if (this.state === ManagedEngineState.PLAYING) {
                                this._transitionState(ManagedEngineState.PAUSED);
                            }
                            return;
                        }
                        // NotAllowedError or genuine playback failure must leave PLAYING and trigger safe error
                        this._handleError(err);
                    });
                }
            }
        }

        /**
         * Pauses active audio playback.
         */
        pause() {
            if (this.state !== ManagedEngineState.PLAYING && this.state !== ManagedEngineState.LOADING) {
                return;
            }

            if (this.audio) {
                try {
                    this.audio.pause();
                } catch (ignored) {}
            }

            this._transitionState(ManagedEngineState.PAUSED);
        }

        /**
         * Resumes paused audio playback.
         */
        resume() {
            if (this.state === ManagedEngineState.PAUSED) {
                if (this.audio && this.audio.src && this.audio.paused) {
                    this._playbackSequenceId++;
                    const sequenceId = this._playbackSequenceId;
                    this._transitionState(ManagedEngineState.PLAYING);
                    const playPromise = this.audio.play();
                    this._scheduleAdjacentWarmUp(this.currentSegmentIndex);
                    if (playPromise && typeof playPromise.catch === 'function') {
                        playPromise.catch((err) => {
                            if (sequenceId !== this._playbackSequenceId) {
                                return; // Stale/superseded
                            }
                            if (err && err.name === 'AbortError') {
                                if (this.state === ManagedEngineState.PLAYING) {
                                    this._transitionState(ManagedEngineState.PAUSED);
                                }
                                return;
                            }
                            this._handleError(err);
                        });
                    }
                } else {
                    this.play(this.currentSegmentIndex);
                }
            }
        }

        /**
         * Stops audio playback and resets segment cursor to 0 (or first playable segment).
         */
        stop() {
            this._cancelPrefetch();
            this._releaseRetiredAudioResource();
            this._playbackSequenceId++;
            if (this.audio) {
                try {
                    this.audio.pause();
                    this.audio.currentTime = 0;
                } catch (ignored) {}
            }

            this.currentSegmentIndex = 0;
            this._transitionState(ManagedEngineState.STOPPED);
        }

        /**
         * Seeks to a specific segment index.
         * Enforces fresh /prepare authority and prevents stale audio resumption.
         * @param {number} index
         */
        seekToSegment(index) {
            if (this.segments.length === 0) {
                return;
            }

            this._cancelPrefetch();

            if (this.state === ManagedEngineState.PREPARING) {
                this.cancel();
            }

            const prevIndex = this.currentSegmentIndex;
            const prevState = this.state;
            const targetIndex = Math.max(0, Math.min(this.segments.length - 1, Math.floor(index)));
            this.currentSegmentIndex = targetIndex;

            if (prevState === ManagedEngineState.PLAYING) {
                if (targetIndex !== prevIndex) {
                    this.play(targetIndex);
                }
            } else if (prevState === ManagedEngineState.PAUSED) {
                if (targetIndex !== prevIndex) {
                    this._playbackSequenceId++;
                    this._transitionState(ManagedEngineState.IDLE);
                }
            } else if (prevState === ManagedEngineState.BLOCKED) {
                if (targetIndex !== prevIndex) {
                    this._transitionState(ManagedEngineState.IDLE);
                }
            }
        }

        /**
         * Compatibility alias for seekToSegment(index).
         * @param {number} index
         */
        seekToChunk(index) {
            this.seekToSegment(index);
        }

        /**
         * Seeks playback by deltaSeconds (relative time seek) with cross-segment boundary navigation.
         * Clamps within chapter start/end and carries remaining time across segment boundaries.
         * Preserves PLAYING vs PAUSED state without starting playback if paused.
         * @param {number} deltaSeconds
         */
        seekBySeconds(deltaSeconds) {
            if (!this.audio) {
                return;
            }
            const delta = Number(deltaSeconds);
            if (!Number.isFinite(delta) || delta === 0) {
                return;
            }

            const wasPaused = (this.state === ManagedEngineState.PAUSED);
            const curTime = Number.isFinite(this.audio.currentTime) ? this.audio.currentTime : 0;
            const duration = (Number.isFinite(this.audio.duration) && this.audio.duration > 0) ? this.audio.duration : null;

            if (delta < 0) {
                const rewindAmount = Math.abs(delta);
                if (curTime >= rewindAmount) {
                    // Rewind within current segment
                    try {
                        this.audio.currentTime = curTime - rewindAmount;
                    } catch (ignored) {}
                    if (wasPaused) {
                        try {
                            this.audio.pause();
                        } catch (ignored) {}
                    }
                } else {
                    // Carry remaining rewind into previous segment
                    const carry = rewindAmount - curTime;
                    if (this.currentSegmentIndex === 0) {
                        // Segment 0 rewind clamps to chapter start
                        try {
                            this.audio.currentTime = 0;
                        } catch (ignored) {}
                        if (wasPaused) {
                            try {
                                this.audio.pause();
                            } catch (ignored) {}
                        }
                    } else {
                        // Cross boundary into previous segment
                        const prevIndex = this.currentSegmentIndex - 1;
                        this.play(prevIndex, { seekFromEnd: carry, paused: wasPaused });
                    }
                }
            } else {
                const forwardAmount = delta;
                if (duration !== null && (curTime + forwardAmount >= duration)) {
                    // Carry overflow into adjacent next segment
                    const overflow = (curTime + forwardAmount) - duration;
                    if (this.currentSegmentIndex < this.segments.length - 1) {
                        // Cross boundary into adjacent next segment
                        const nextIndex = this.currentSegmentIndex + 1;
                        this.play(nextIndex, { seekFromStart: overflow, paused: wasPaused });
                    } else {
                        // Final segment forward clamps/completes safely
                        try {
                            this.audio.currentTime = duration;
                        } catch (ignored) {}
                        if (wasPaused) {
                            try {
                                this.audio.pause();
                            } catch (ignored) {}
                        }
                    }
                } else {
                    // Forward within current segment
                    let targetTime = curTime + forwardAmount;
                    if (duration !== null) {
                        targetTime = Math.min(duration, targetTime);
                    }
                    try {
                        this.audio.currentTime = targetTime;
                    } catch (ignored) {}
                    if (wasPaused) {
                        try {
                            this.audio.pause();
                        } catch (ignored) {}
                    }
                }
            }
        }

        /**
         * Navigates to the next adjacent segment.
         */
        nextSegment() {
            if (this.currentSegmentIndex + 1 < this.segments.length) {
                this.seekToSegment(this.currentSegmentIndex + 1);
            }
        }

        /**
         * Compatibility alias for nextSegment().
         */
        nextChunk() {
            this.nextSegment();
        }

        /**
         * Navigates to the previous adjacent segment.
         */
        previousSegment() {
            if (this.currentSegmentIndex - 1 >= 0) {
                this.seekToSegment(this.currentSegmentIndex - 1);
            }
        }

        /**
         * Compatibility alias for previousSegment().
         */
        previousChunk() {
            this.previousSegment();
        }

        /**
         * Cancels active playback or fetch operations.
         */
        cancel() {
            this._playbackSequenceId++;
            this._manifestLoadSequenceId++;
            this._cancelPrefetch();
            this._releaseRetiredAudioResource();
            if (this.audio) {
                try {
                    this.audio.pause();
                } catch (ignored) {}
            }
            if (this._activeFetchController) {
                try {
                    this._activeFetchController.abort();
                } catch (ignored) {}
                this._activeFetchController = null;
            }
            if (this._activeManifestFetchController) {
                try {
                    this._activeManifestFetchController.abort();
                } catch (ignored) {}
                this._activeManifestFetchController = null;
            }
            this._transitionState(this.segments.length > 0 ? ManagedEngineState.IDLE : ManagedEngineState.STOPPED);
        }

        /**
         * Audio event: ended
         * @private
         */
        _handleAudioEnded() {
            if (this.state !== ManagedEngineState.PLAYING) {
                return;
            }

            const currentSegment = this.segments[this.currentSegmentIndex];
            debugLogNarration('segment-ended', {
                segmentIndex: this.currentSegmentIndex,
                segmentId: currentSegment ? currentSegment.segmentId : null,
                voiceKey: this.getSelectedVoiceKey()
            });

            this._notifySegmentEnd(this.currentSegmentIndex, currentSegment);

            // Sequentially advance to adjacent next segment via prepared fast path or canonical /prepare
            const nextIndex = this.currentSegmentIndex + 1;
            if (nextIndex < this.segments.length) {
                if (this._canUsePreparedFastPath(nextIndex)) {
                    this._playPreparedAdjacentSegment(nextIndex);
                } else {
                    this.play(nextIndex);
                }
            } else {
                // Chapter finished: actual final manifest segment ended
                this._transitionState(ManagedEngineState.STOPPED);
                if (typeof this.options.onChapterEnd === 'function') {
                    try {
                        this.options.onChapterEnd();
                    } catch (e) {
                        console.error('[ManagedAudioEngine] Error in onChapterEnd callback:', e);
                    }
                }
            }
        }

        /**
         * Audio event: error
         * @param {Event} event
         * @private
         */
        _handleAudioError(event) {
            console.warn('[ManagedAudioEngine] HTMLAudioElement error:', event);
            this._handleError(event);
        }

        /**
         * Audio event: play
         * @private
         */
        _handleAudioPlay() {
            if (this.state !== ManagedEngineState.PLAYING) {
                this._transitionState(ManagedEngineState.PLAYING);
            }
        }

        /**
         * Audio event: playing
         * @private
         */
        _handleAudioPlaying() {
            debugLogNarration('audio-playing-event', {
                segmentIndex: this.currentSegmentIndex,
                segmentId: (this.segments[this.currentSegmentIndex]) ? this.segments[this.currentSegmentIndex].segmentId : null,
                voiceKey: this.getSelectedVoiceKey()
            });
        }

        /**
         * Audio event: pause
         * @private
         */
        _handleAudioPause() {
            if (this.audio && this.audio.ended) {
                return;
            }
            if (this.state === ManagedEngineState.PLAYING) {
                this._transitionState(ManagedEngineState.PAUSED);
            }
        }

        /**
         * Triggers error callback and transitions to ERROR state.
         * @param {any} error
         * @private
         */
        _handleError(error) {
            this._transitionState(ManagedEngineState.ERROR);
            if (typeof this.options.onError === 'function') {
                try {
                    this.options.onError(error);
                } catch (e) {
                    console.error('[ManagedAudioEngine] Error in onError callback:', e);
                }
            }
        }

        /**
         * Notifies segment start listeners (once per segment start).
         * @param {number} index
         * @param {Object} segment
         * @param {Object} [playbackDto]
         * @private
         */
        _notifySegmentStart(index, segment, playbackDto) {
            const cb = this.options.onSegmentStart || this.options.onChunkStart;
            if (typeof cb === 'function') {
                try {
                    cb(index, segment, playbackDto);
                } catch (e) {
                    console.error('[ManagedAudioEngine] Error in onSegmentStart listener:', e);
                }
            }
        }

        /**
         * Notifies segment end listeners (once per segment end).
         * @param {number} index
         * @param {Object} segment
         * @private
         */
        _notifySegmentEnd(index, segment) {
            const cb = this.options.onSegmentEnd || this.options.onChunkEnd;
            if (typeof cb === 'function') {
                try {
                    cb(index, segment);
                } catch (e) {
                    console.error('[ManagedAudioEngine] Error in onSegmentEnd listener:', e);
                }
            }
        }

        /**
         * Notifies blocked listeners when a segment cannot be played.
         * @param {number} index
         * @param {Object} segment
         * @param {Object|null} [playbackDto]
         * @private
         */
        _notifyBlocked(index, segment, playbackDto) {
            const cb = this.options.onBlocked;
            if (typeof cb === 'function') {
                try {
                    cb(index, segment, playbackDto);
                } catch (e) {
                    console.error('[ManagedAudioEngine] Error in onBlocked listener:', e);
                }
            }
        }

        /**
         * Checks whether a valid single-use prepared authority exists for natural adjacent N -> N+1 continuation (MS-04.9H.7D8).
         * @param {number} targetIndex
         * @returns {boolean}
         * @private
         */
        _canUsePreparedFastPath(targetIndex) {
            if (!this._warmedSegment || this._warmedSegment.consumed) {
                return false;
            }

            const warmed = this._warmedSegment;
            const currentVoiceKey = this.getSelectedVoiceKey();

            // 1. Prefetch sequence ownership
            if (warmed.prefetchSequence !== this._prefetchSequenceId) {
                return false;
            }

            // 2. Chapter identity
            if (!this.chapterId || warmed.chapterId !== this.chapterId) {
                return false;
            }

            // 3. Exact adjacent index (N -> N+1)
            if (typeof targetIndex !== 'number' || warmed.segmentIndex !== targetIndex || targetIndex !== this.currentSegmentIndex + 1) {
                return false;
            }

            // 4. Exact segmentId matching target segment
            const targetSegment = this.segments[targetIndex];
            if (!targetSegment || !targetSegment.segmentId || warmed.segmentId !== targetSegment.segmentId) {
                return false;
            }

            // 5. Exact voiceKey matching active voice
            if (!currentVoiceKey || warmed.voiceKey !== currentVoiceKey) {
                return false;
            }

            // 6. Valid successful playback DTO and matching audioUrl
            if (!warmed.playbackDto || !warmed.playbackDto.playableNow || !warmed.audioUrl || warmed.audioUrl !== warmed.playbackDto.audioUrl) {
                return false;
            }

            // 7. Standby audio element exists
            const standby = warmed.standbyAudio || warmed.preloadAudio;
            if (!standby) {
                return false;
            }

            return true;
        }

        /**
         * Executes natural adjacent N -> N+1 playback via the single-use prepared fast path without a second /prepare (MS-04.9H.7D8).
         * @param {number} nextIndex
         * @private
         */
        _playPreparedAdjacentSegment(nextIndex) {
            if (!this._canUsePreparedFastPath(nextIndex)) {
                debugLogNarration('prepared-fast-path-miss', {
                    segmentIndex: nextIndex,
                    voiceKey: this.getSelectedVoiceKey(),
                    reason: 'authority_invalid_or_missing'
                });
                this.play(nextIndex);
                return;
            }

            const warmed = this._warmedSegment;
            this._warmedSegment = null;
            warmed.consumed = true;

            debugLogNarration('prepared-fast-path-hit', {
                segmentIndex: nextIndex,
                segmentId: warmed.segmentId,
                voiceKey: warmed.voiceKey,
                isReady: warmed.isReady,
                readyState: (warmed.standbyAudio || warmed.preloadAudio) ? (warmed.standbyAudio || warmed.preloadAudio).readyState : null
            });
            debugLogNarration('prepared-authority-consumed', {
                segmentIndex: nextIndex,
                segmentId: warmed.segmentId,
                voiceKey: warmed.voiceKey
            });

            this._playbackSequenceId++;
            const sequenceId = this._playbackSequenceId;
            const segment = this.segments[nextIndex];
            const playbackDto = warmed.playbackDto;

            this.currentSegmentIndex = nextIndex;
            segment.playable = true;
            segment.audioUrl = playbackDto.audioUrl;
            segment.healthStatus = playbackDto.healthStatus;

            const promotedAudio = warmed.standbyAudio || warmed.preloadAudio;
            const previousAudio = this.audio;
            this._unbindAudioListeners();
            this.audio = promotedAudio;
            this._bindAudioListeners();
            if (previousAudio && previousAudio !== promotedAudio) {
                this._releaseRetiredAudioResource();
                this._retiredAudio = previousAudio;
            }

            if (this.audio) {
                this.audio.playbackRate = this.rate;
                this.audio.volume = this.volume;
            }

            this._transitionState(ManagedEngineState.PLAYING);
            this._notifySegmentStart(nextIndex, segment, playbackDto);
            debugLogNarration('standby-promoted', {
                segmentIndex: nextIndex,
                segmentId: segment.segmentId,
                voiceKey: warmed.voiceKey
            });

            if (this.audio) {
                debugLogNarration('audio-play-called', {
                    segmentIndex: nextIndex,
                    segmentId: segment.segmentId,
                    voiceKey: warmed.voiceKey,
                    readyState: this.audio.readyState
                });

                const playPromise = this.audio.play();
                this._scheduleAdjacentWarmUp(nextIndex);
                if (playPromise && typeof playPromise.catch === 'function') {
                    playPromise.catch((err) => {
                        if (sequenceId !== this._playbackSequenceId) {
                            return;
                        }
                        if (err && err.name === 'AbortError') {
                            if (this.state === ManagedEngineState.PLAYING) {
                                this._transitionState(ManagedEngineState.PAUSED);
                            }
                            return;
                        }
                        this._handleError(err);
                    });
                }
            }
        }

        /**
         * Destroys engine instance and releases listeners and audio resources.
         */
        destroy() {
            this.cancel();
            this._unbindAudioListeners();
            if (this.audio) {
                try {
                    this.audio.src = '';
                } catch (ignored) {}
            }
            this._releaseRetiredAudioResource();
            this.segments = [];
            this.availableVoices = [];
            this.selectedVoice = null;
            this.manifestCache.clear();
            this._transitionState(ManagedEngineState.STOPPED);
        }
    }

    return {
        ManagedAudioEngine: ManagedAudioEngine,
        ManagedEngineState: ManagedEngineState,
        ENGINE_CAPABILITIES: ENGINE_CAPABILITIES,
        isSupported: isSupported,
        buildManifestUrl: buildManifestUrl,
        buildPrepareUrl: buildPrepareUrl,
        resolveCsrfToken: resolveCsrfToken
    };
});
