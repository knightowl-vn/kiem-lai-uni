/**
 * KiemLai Universe — Browser TTS Engine
 *
 * Responsibilities:
 * - Encapsulate window.speechSynthesis and SpeechSynthesisUtterance operations.
 * - Detect unsupported browsers cleanly with graceful fallback.
 * - Discover available voices immediately and subscribe to the 'voiceschanged' event.
 * - Prioritize Vietnamese voices ('vi', 'vi-VN') while exposing all available system voices.
 * - Manage playback lifecycle (play, pause, resume, stop, seekToChunk, nextChunk, prevChunk).
 * - Speak exactly one chunk at a time to prevent Chromium 15-second cutoff issues.
 * - Guard against stale callbacks from cancelled or superseded utterances using sequence tokening.
 * - Eliminate pending-utterance lifecycle races when pausing, seeking, or stopping before onstart.
 * - Expose engine capabilities (no exact time seek, sentence/chunk seek supported).
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.BrowserTtsEngine = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.BrowserTtsEngine = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Engine playback states.
     */
    const EngineState = Object.freeze({
        UNINITIALIZED: 'UNINITIALIZED',
        IDLE: 'IDLE',
        PLAYING: 'PLAYING',
        PAUSED: 'PAUSED',
        STOPPED: 'STOPPED',
        ERROR: 'ERROR'
    });

    /**
     * Normalized default engine capabilities.
     */
    const ENGINE_CAPABILITIES = Object.freeze({
        canSeekTime: false,
        canSeekSentence: true,
        supportsBackgroundPlayback: false,
        supportsPitch: true,
        supportsRate: true
    });

    /**
     * Checks if SpeechSynthesis is supported in the current environment.
     *
     * @returns {boolean}
     */
    function isSupported() {
        return typeof window !== 'undefined'
            && 'speechSynthesis' in window
            && typeof window.SpeechSynthesisUtterance !== 'undefined';
    }

    /**
     * Checks if a voice has a Vietnamese language tag.
     *
     * @param {SpeechSynthesisVoice} voice
     * @returns {boolean}
     */
    function isVietnameseVoice(voice) {
        if (!voice || !voice.lang) {
            return false;
        }
        const lang = voice.lang.toLowerCase();
        return lang.startsWith('vi') || lang.includes('vi-vn') || lang.includes('vi_vn');
    }

    /**
     * Browser TTS Engine implementation.
     */
    class BrowserTtsEngine {
        /**
         * @param {Object} [options]
         * @param {Function} [options.onStateChange]
         * @param {Function} [options.onChunkStart]
         * @param {Function} [options.onChunkEnd]
         * @param {Function} [options.onChapterEnd]
         * @param {Function} [options.onError]
         * @param {Function} [options.onVoicesChanged]
         */
        constructor(options = {}) {
            this.options = options;
            this.state = EngineState.UNINITIALIZED;
            this.supported = isSupported();

            this.chunks = [];
            this.currentChunkIndex = 0;

            this.selectedVoice = null;
            this.rate = 1.0;
            this.pitch = 1.0;
            this.volume = 1.0;

            this.availableVoices = [];
            this.utteranceSequenceId = 0;
            this.currentUtterance = null;

            this._boundOnVoicesChanged = this._handleVoicesChanged.bind(this);

            if (this.supported) {
                this._initVoiceDiscovery();
                this._transitionState(EngineState.IDLE);
            } else {
                this._transitionState(EngineState.ERROR);
            }
        }

        /**
         * Initializes voice discovery and binds event listeners.
         * @private
         */
        _initVoiceDiscovery() {
            if (!this.supported) {
                return;
            }

            // Populate voices immediately if available
            this._updateVoices();

            // Subscribe to dynamic voiceschanged event (common in Chrome/Edge)
            if (window.speechSynthesis) {
                if (typeof window.speechSynthesis.addEventListener === 'function') {
                    window.speechSynthesis.addEventListener('voiceschanged', this._boundOnVoicesChanged);
                } else {
                    window.speechSynthesis.onvoiceschanged = this._boundOnVoicesChanged;
                }
            }
        }

        /**
         * Updates the internal list of available voices.
         * @private
         */
        _updateVoices() {
            if (!this.supported || !window.speechSynthesis) {
                return;
            }

            try {
                const voices = window.speechSynthesis.getVoices() || [];
                if (Array.isArray(voices) && voices.length > 0) {
                    this.availableVoices = voices;
                    this._autoSelectDefaultVoice();
                    if (this.state === EngineState.ERROR && this.selectedVoice) {
                        this._transitionState(this.chunks.length > 0 ? EngineState.IDLE : EngineState.STOPPED);
                    }
                }
            } catch (error) {
                console.warn('[BrowserTtsEngine] Failed to retrieve system voices:', error);
            }
        }

        /**
         * Automatically selects a Vietnamese voice if none is currently selected.
         * Never falls back to foreign non-Vietnamese voices.
         * @private
         */
        _autoSelectDefaultVoice() {
            if (this.selectedVoice && isVietnameseVoice(this.selectedVoice) && this.availableVoices.some(v => v.voiceURI === this.selectedVoice.voiceURI)) {
                return;
            }

            const viVoices = this.getVietnameseVoices();
            if (viVoices.length > 0) {
                this.selectedVoice = viVoices[0];
            } else {
                this.selectedVoice = null;
            }
        }

        /**
         * Handles the browser's voiceschanged event.
         * @private
         */
        _handleVoicesChanged() {
            this._updateVoices();
            if (typeof this.options.onVoicesChanged === 'function') {
                try {
                    this.options.onVoicesChanged(this.getSortedVoices());
                } catch (e) {
                    console.error('[BrowserTtsEngine] Error in onVoicesChanged listener:', e);
                }
            }
        }

        /**
         * Transitions engine to a new state and notifies listeners.
         * @param {string} newState
         * @private
         */
        _transitionState(newState) {
            if (this.state === newState) {
                return;
            }
            const previousState = this.state;
            this.state = newState;

            if (typeof this.options.onStateChange === 'function') {
                try {
                    this.options.onStateChange(newState, previousState);
                } catch (e) {
                    console.error('[BrowserTtsEngine] Error in onStateChange listener:', e);
                }
            }
        }

        /**
         * Returns engine capabilities.
         * @returns {EngineCapabilities}
         */
        getCapabilities() {
            return ENGINE_CAPABILITIES;
        }

        /**
         * Returns whether the browser supports SpeechSynthesis.
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
         * Returns all available voices.
         * @returns {Array<SpeechSynthesisVoice>}
         */
        getVoices() {
            if (this.availableVoices.length === 0 && this.supported) {
                this._updateVoices();
            }
            return this.availableVoices.slice();
        }

        /**
         * Returns available Vietnamese voices only.
         * @returns {Array<SpeechSynthesisVoice>}
         */
        getVietnameseVoices() {
            const all = this.getVoices();
            return all.filter(voice => isVietnameseVoice(voice));
        }

        /**
         * Returns available Vietnamese voices.
         * Only exposes Vietnamese voices for the novel reader experience.
         * @returns {Array<SpeechSynthesisVoice>}
         */
        getSortedVoices() {
            return this.getVietnameseVoices();
        }

        /**
         * Selects a voice by SpeechSynthesisVoice object, voiceURI, or name.
         * Only accepts Vietnamese voices.
         * @param {SpeechSynthesisVoice|string} voiceOrIdentifier
         */
        setVoice(voiceOrIdentifier) {
            if (!voiceOrIdentifier) {
                this.selectedVoice = null;
                return;
            }

            if (typeof voiceOrIdentifier === 'object' && voiceOrIdentifier.voiceURI) {
                if (isVietnameseVoice(voiceOrIdentifier)) {
                    this.selectedVoice = voiceOrIdentifier;
                } else {
                    this.selectedVoice = null;
                }
                return;
            }

            if (typeof voiceOrIdentifier === 'string') {
                const viVoices = this.getVietnameseVoices();
                const found = viVoices.find(v =>
                    v.voiceURI === voiceOrIdentifier || v.name === voiceOrIdentifier
                );
                if (found) {
                    this.selectedVoice = found;
                } else {
                    this.selectedVoice = null;
                }
            }
        }

        /**
         * Sets playback rate (0.5 to 2.0).
         * @param {number} rate
         */
        setRate(rate) {
            const num = Number(rate);
            if (Number.isFinite(num)) {
                this.rate = Math.max(0.5, Math.min(2.0, num));
            }
        }

        /**
         * Sets pitch (0.5 to 1.5).
         * @param {number} pitch
         */
        setPitch(pitch) {
            const num = Number(pitch);
            if (Number.isFinite(num)) {
                this.pitch = Math.max(0.5, Math.min(1.5, num));
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
            }
        }

        /**
         * Loads chunk units into the engine.
         * @param {Array<{index: number, text: string, paragraphIndex: number, element?: any}>} chunks
         * @param {number} [startChunkIndex=0]
         */
        loadChunks(chunks, startChunkIndex = 0) {
            this.cancel();
            this.chunks = Array.isArray(chunks) ? chunks : [];
            this.currentChunkIndex = Math.max(0, Math.min(this.chunks.length - 1, startChunkIndex || 0));
            this._transitionState(this.chunks.length > 0 ? EngineState.IDLE : EngineState.STOPPED);
        }

        /**
         * Starts or resumes speech from the specified or current chunk index.
         * Updates state to PLAYING immediately so pause/seek during pending onstart works reliably.
         * @param {number} [startChunkIndex]
         */
        play(startChunkIndex) {
            if (!this.supported) {
                this._handleError(new Error('SpeechSynthesis is not supported in this browser.'));
                return;
            }

            if (this.chunks.length === 0) {
                return;
            }

            if (!this.selectedVoice && this.getVietnameseVoices().length === 0) {
                this._handleError(new Error('Thiết bị chưa có giọng đọc Tiếng Việt.'));
                return;
            }

            if (typeof startChunkIndex === 'number') {
                this.currentChunkIndex = Math.max(0, Math.min(this.chunks.length - 1, startChunkIndex));
            }

            this._transitionState(EngineState.PLAYING);
            this._speakCurrentChunk();
        }

        /**
         * Pauses playback by canceling active/pending speech and preserving cursor position.
         */
        pause() {
            if (this.state !== EngineState.PLAYING) {
                return;
            }

            this.cancel();
            this._transitionState(EngineState.PAUSED);
        }

        /**
         * Resumes playback from current chunk index.
         */
        resume() {
            if (this.state === EngineState.PAUSED) {
                this.play(this.currentChunkIndex);
            }
        }

        /**
         * Stops playback and resets chunk cursor to 0.
         */
        stop() {
            this.cancel();
            this.currentChunkIndex = 0;
            this._transitionState(EngineState.STOPPED);
        }

        /**
         * Navigates to a specific chunk index.
         * If currently playing (or queued pending start), cancels previous utterance and speaks new chunk.
         * @param {number} index
         */
        seekToChunk(index) {
            if (this.chunks.length === 0) {
                return;
            }

            const targetIndex = Math.max(0, Math.min(this.chunks.length - 1, index));
            this.currentChunkIndex = targetIndex;

            if (this.state === EngineState.PLAYING) {
                this._speakCurrentChunk();
            }
        }

        /**
         * Navigates to the next chunk.
         */
        nextChunk() {
            if (this.currentChunkIndex < this.chunks.length - 1) {
                this.seekToChunk(this.currentChunkIndex + 1);
            }
        }

        /**
         * Navigates to the previous chunk.
         */
        previousChunk() {
            if (this.currentChunkIndex > 0) {
                this.seekToChunk(this.currentChunkIndex - 1);
            }
        }

        /**
         * Cancels any active/queued utterance and increments the sequence token to invalidate pending callbacks.
         */
        cancel() {
            // Increment sequence token so any in-flight utterance callbacks are dropped
            this.utteranceSequenceId++;

            if (this.supported && typeof window !== 'undefined' && window.speechSynthesis) {
                try {
                    window.speechSynthesis.cancel();
                } catch (e) {
                    console.warn('[BrowserTtsEngine] Error calling speechSynthesis.cancel():', e);
                }
            }

            this.currentUtterance = null;
        }

        /**
         * Speaks the chunk at this.currentChunkIndex.
         * @private
         */
        _speakCurrentChunk() {
            if (this.currentChunkIndex >= this.chunks.length) {
                this.cancel();
                this.currentChunkIndex = Math.max(0, this.chunks.length - 1);
                this._transitionState(EngineState.STOPPED);
                if (typeof this.options.onChapterEnd === 'function') {
                    try {
                        this.options.onChapterEnd();
                    } catch (e) {
                        console.error('[BrowserTtsEngine] Error in onChapterEnd listener:', e);
                    }
                }
                return;
            }

            const chunk = this.chunks[this.currentChunkIndex];
            const textToSpeak = (chunk && chunk.text) ? chunk.text.trim() : '';

            if (!textToSpeak) {
                // Skip empty chunk and advance
                this.currentChunkIndex++;
                this._speakCurrentChunk();
                return;
            }

            // Invalidate any prior utterance callbacks and cancel prior speech
            this.cancel();
            const utteranceId = this.utteranceSequenceId;

            const utterance = new SpeechSynthesisUtterance(textToSpeak);
            this.currentUtterance = utterance;

            if (this.selectedVoice && isVietnameseVoice(this.selectedVoice)) {
                utterance.voice = this.selectedVoice;
                utterance.lang = this.selectedVoice.lang || 'vi-VN';
            } else {
                this._autoSelectDefaultVoice();
                if (this.selectedVoice && isVietnameseVoice(this.selectedVoice)) {
                    utterance.voice = this.selectedVoice;
                    utterance.lang = this.selectedVoice.lang || 'vi-VN';
                } else {
                    utterance.lang = 'vi-VN';
                    this._handleError(new Error('Thiết bị chưa có giọng đọc Tiếng Việt.'));
                    return;
                }
            }

            utterance.rate = this.rate;
            utterance.pitch = this.pitch;
            utterance.volume = this.volume;

            utterance.onstart = () => {
                if (utteranceId !== this.utteranceSequenceId || this.state !== EngineState.PLAYING) {
                    return; // Stale or cancelled callback
                }
                if (typeof this.options.onChunkStart === 'function') {
                    try {
                        this.options.onChunkStart(this.currentChunkIndex, chunk);
                    } catch (e) {
                        console.error('[BrowserTtsEngine] Error in onChunkStart listener:', e);
                    }
                }
            };

            utterance.onend = () => {
                if (utteranceId !== this.utteranceSequenceId || this.state !== EngineState.PLAYING) {
                    return; // Stale or cancelled callback
                }

                if (typeof this.options.onChunkEnd === 'function') {
                    try {
                        this.options.onChunkEnd(this.currentChunkIndex, chunk);
                    } catch (e) {
                        console.error('[BrowserTtsEngine] Error in onChunkEnd listener:', e);
                    }
                }

                // Advance to next chunk sequentially
                this.currentChunkIndex++;
                this._speakCurrentChunk();
            };

            utterance.onerror = (event) => {
                if (utteranceId !== this.utteranceSequenceId) {
                    return; // Stale callback
                }

                // Intentional cancels or interruptions in browser speech synthesis are not fatal errors
                if (event && (event.error === 'canceled' || event.error === 'interrupted')) {
                    return;
                }

                console.warn('[BrowserTtsEngine] Utterance error:', event);
                this._handleError(event);
            };

            try {
                window.speechSynthesis.speak(utterance);
            } catch (error) {
                console.error('[BrowserTtsEngine] Error calling speechSynthesis.speak():', error);
                this._handleError(error);
            }
        }

        /**
         * Handles error and triggers callback.
         * @param {any} error
         * @private
         */
        _handleError(error) {
            this._transitionState(EngineState.ERROR);
            if (typeof this.options.onError === 'function') {
                try {
                    this.options.onError(error);
                } catch (e) {
                    console.error('[BrowserTtsEngine] Error in onError listener:', e);
                }
            }
        }

        /**
         * Returns current chunk.
         * @returns {Object|null}
         */
        getCurrentChunk() {
            return this.chunks[this.currentChunkIndex] || null;
        }

        /**
         * Returns current chunk index.
         * @returns {number}
         */
        getCurrentChunkIndex() {
            return this.currentChunkIndex;
        }

        /**
         * Returns total chunks count.
         * @returns {number}
         */
        getTotalChunks() {
            return this.chunks.length;
        }

        /**
         * Destroys engine and cleans up listeners.
         */
        destroy() {
            this.cancel();
            if (this.supported && typeof window !== 'undefined' && window.speechSynthesis) {
                if (typeof window.speechSynthesis.removeEventListener === 'function') {
                    window.speechSynthesis.removeEventListener('voiceschanged', this._boundOnVoicesChanged);
                }
                if (window.speechSynthesis.onvoiceschanged === this._boundOnVoicesChanged) {
                    window.speechSynthesis.onvoiceschanged = null;
                }
            }
            this.chunks = [];
            this.selectedVoice = null;
            this.availableVoices = [];
            this._transitionState(EngineState.STOPPED);
        }
    }

    return {
        BrowserTtsEngine: BrowserTtsEngine,
        EngineState: EngineState,
        ENGINE_CAPABILITIES: ENGINE_CAPABILITIES,
        isSupported: isSupported
    };
});
