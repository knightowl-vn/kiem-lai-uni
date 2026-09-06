/**
 * KiemLai Universe — Novel Reader Managed Audio Engine
 *
 * Responsibilities:
 * - Play KiemLai managed narration audio via HTMLAudioElement.
 * - Load and cache chapter narration manifests by chapterId and voiceKey.
 * - Authoritative segment playability:
 *     READY    -> playable: true  (play generated audio)
 *     OUTDATED -> playable: true  (play existing old audio)
 *     MISSING  -> playable: false (skip during auto-advance)
 *     FAILED   -> playable: false (skip during auto-advance)
 * - Reuse a single HTMLAudioElement instance and replace .src as segments advance.
 * - Support play, pause, resume, stop, seekToSegment, previousSegment, nextSegment.
 * - Map playback rate directly to HTMLAudioElement.playbackRate without re-fetching audio.
 * - Advance sequentially to the next playable segment on 'ended' event.
 * - Cleanly transition to STOPPED / onChapterEnd when no further playable segment remains.
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
        PLAYING: 'PLAYING',
        PAUSED: 'PAUSED',
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
            this._playbackSequenceId = 0;
            this._boundOnAudioEnded = this._handleAudioEnded.bind(this);
            this._boundOnAudioError = this._handleAudioError.bind(this);
            this._boundOnAudioPlay = this._handleAudioPlay.bind(this);
            this._boundOnAudioPause = this._handleAudioPause.bind(this);

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

            const cleanVoiceKey = (voiceKey && typeof voiceKey === 'string') ? voiceKey.trim() : null;
            const cacheKey = cleanVoiceKey ? (String(chapterId) + ':' + cleanVoiceKey) : null;

            if (cacheKey && this.manifestCache.has(cacheKey)) {
                const cached = this.manifestCache.get(cacheKey);
                this.setManifest(cached, this.currentSegmentIndex);
                return cached;
            }

            if (this._activeFetchController) {
                try {
                    this._activeFetchController.abort();
                } catch (ignored) {}
            }

            const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
            this._activeFetchController = controller;

            const url = buildManifestUrl(chapterId, cleanVoiceKey);
            const fetchFn = this.fetchFunction || fetch;

            try {
                const response = await fetchFn(url, {
                    headers: { 'Accept': 'application/json' },
                    signal: controller ? controller.signal : undefined
                });

                if (!response.ok) {
                    throw new Error('HTTP ' + response.status + ' when loading narration manifest.');
                }

                const manifest = await response.json();
                this._activeFetchController = null;

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
                this._activeFetchController = null;
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
         * Starts playback from specified or current segment index.
         * If the segment is not playable, skips forward to next playable segment.
         * @param {number} [startSegmentIndex]
         */
        play(startSegmentIndex) {
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

            const playableIndex = this.findNextPlayableIndex(targetIndex);
            if (playableIndex === -1) {
                // No remaining playable segments in chapter
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
            this._playCurrentSegment();
        }

        /**
         * Internal routine to load and play audio for this.currentSegmentIndex.
         * @private
         */
        _playCurrentSegment() {
            if (!this.audio) {
                return;
            }

            const segment = this.segments[this.currentSegmentIndex];
            if (!segment || !segment.playable || !segment.audioUrl) {
                // Advance to next playable
                const nextPlayable = this.findNextPlayableIndex(this.currentSegmentIndex + 1);
                if (nextPlayable !== -1) {
                    this.currentSegmentIndex = nextPlayable;
                    this._playCurrentSegment();
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

            this.audio.playbackRate = this.rate;
            this.audio.volume = this.volume;

            this._transitionState(ManagedEngineState.PLAYING);

            this._notifySegmentStart(this.currentSegmentIndex, segment);

            const playPromise = this.audio.play();
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
         * @param {number} index
         */
        seekToSegment(index) {
            if (this.segments.length === 0) {
                return;
            }

            const targetIndex = Math.max(0, Math.min(this.segments.length - 1, Math.floor(index)));
            this.currentSegmentIndex = targetIndex;

            if (this.state === ManagedEngineState.PLAYING) {
                this._playbackSequenceId++;
                this._playCurrentSegment();
            } else {
                const seg = this.segments[targetIndex];
                if (seg && seg.audioUrl && this.audio) {
                    if (this.audio.src !== seg.audioUrl && !this.audio.src.endsWith(seg.audioUrl)) {
                        this.audio.src = seg.audioUrl;
                    }
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
         * Navigates to the next playable segment.
         */
        nextSegment() {
            const nextIdx = this.findNextPlayableIndex(this.currentSegmentIndex + 1);
            if (nextIdx !== -1) {
                this.seekToSegment(nextIdx);
            }
        }

        /**
         * Compatibility alias for nextSegment().
         */
        nextChunk() {
            this.nextSegment();
        }

        /**
         * Navigates to the previous playable segment.
         */
        previousSegment() {
            const prevIdx = this.findPreviousPlayableIndex(this.currentSegmentIndex - 1);
            if (prevIdx !== -1) {
                this.seekToSegment(prevIdx);
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
            this._notifySegmentEnd(this.currentSegmentIndex, currentSegment);

            // Advance to next playable segment
            const nextIndex = this.findNextPlayableIndex(this.currentSegmentIndex + 1);
            if (nextIndex !== -1) {
                this.currentSegmentIndex = nextIndex;
                this._playCurrentSegment();
            } else {
                // Chapter finished
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
         * Audio event: pause
         * @private
         */
        _handleAudioPause() {
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
         * @private
         */
        _notifySegmentStart(index, segment) {
            const cb = this.options.onSegmentStart || this.options.onChunkStart;
            if (typeof cb === 'function') {
                try {
                    cb(index, segment);
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
        buildManifestUrl: buildManifestUrl
    };
});
