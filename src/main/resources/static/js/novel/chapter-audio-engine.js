/** H.9G: one immutable chapter source; persisted cues only describe its timeline. */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        root.ChapterAudioEngine = factory();
    }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    function buildPlaybackUrl(chapterId, voiceKey) {
        return '/api/novel/chapters/' + encodeURIComponent(String(chapterId)) +
            '/narration/playback?voiceKey=' + encodeURIComponent(voiceKey);
    }

    function buildPrepareUrl(chapterId) {
        return '/api/novel/chapters/' + encodeURIComponent(String(chapterId)) +
            '/narration/prepare';
    }

    function resolveCsrfToken(doc) {
        if (!doc || typeof doc.querySelector !== 'function') return null;
        const metaToken = doc.querySelector('meta[name="_csrf"]');
        const metaHeader = doc.querySelector('meta[name="_csrf_header"]');
        if (metaToken && metaHeader) {
            const token = metaToken.getAttribute('content');
            const header = metaHeader.getAttribute('content');
            if (token && header) {
                return { header, token };
            }
        }
        const fallback = doc.querySelector('[data-csrf-token][data-csrf-header]');
        if (fallback) {
            const token = fallback.getAttribute('data-csrf-token');
            const header = fallback.getAttribute('data-csrf-header');
            if (token && header) {
                return { header, token };
            }
        }
        return null;
    }

    function isChapterPlayable(metadata) {
        return Boolean(metadata && metadata.availability === 'READY' && metadata.playable === true &&
            (metadata.freshness === 'CURRENT' || metadata.freshness === 'STALE_VOICE') &&
            metadata.audioUrl && Array.isArray(metadata.cues) && metadata.cues.length > 0);
    }

    class ChapterAudioEngine {
        constructor(options = {}) {
            this.options = options;
            this.fetchFunction = options.fetchFunction || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
            this.audioFactory = options.audioFactory || (() => new Audio());
            this.audio = null;
            this.metadata = null;
            this.cues = [];
            this.state = 'IDLE';
            this.rate = 1;
            this._generation = 0;
            this._playId = 0;
            this._wantsPlay = false;
            this._fetchController = null;
            this._listeners = [];
            this._activeCueIndex = -1;
            this._pendingTime = null;
        }

        isSupported() { return typeof Audio !== 'undefined' || Boolean(this.options.audioFactory); }
        getState() { return this.state; }
        getCapabilities() { return { canSeekTime: true, canSeekSentence: true, supportsRate: true }; }
        getCurrentChunkIndex() { return this._activeCueIndex; }
        getCurrentChunk() { return this.cues[this._activeCueIndex] || null; }
        getChunks() { return this.cues; }
        getSegments() { return this.cues; }
        getSelectedVoiceKey() { return this.metadata ? this.metadata.voiceKey : null; }
        getProgress() {
            const durationSeconds = this.audio ? Number(this.audio.duration) : 0;
            const hasDuration = Number.isFinite(durationSeconds) && durationSeconds > 0;
            const rawCurrentTime = Number(this._time());
            const currentTimeSeconds = Math.max(0, hasDuration
                ? Math.min(durationSeconds, Number.isFinite(rawCurrentTime) ? rawCurrentTime : 0)
                : (Number.isFinite(rawCurrentTime) ? rawCurrentTime : 0));
            return {
                currentTimeSeconds,
                durationSeconds: hasDuration ? durationSeconds : 0,
                progressRatio: hasDuration ? currentTimeSeconds / durationSeconds : 0
            };
        }

        _transitionState(state) {
            if (this.state === state) return;
            const previous = this.state;
            this.state = state;
            if (this.options.onStateChange) this.options.onStateChange(state, previous);
        }

        /**
         * Passively probes chapter playback metadata without mutating active playback state.
         * Distinguishes 'ready', 'unavailable', and 'unknown'.
         *
         * @param {string|number} chapterId
         * @param {string} voiceKey
         * @param {Object} [options]
         * @param {AbortSignal} [options.signal]
         * @returns {Promise<{ status: 'ready', metadata: Object } | { status: 'unavailable', metadata: Object } | { status: 'unknown' }>}
         */
        async probePlaybackMetadata(chapterId, voiceKey, options = {}) {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The operation was aborted');
                abortErr.name = 'AbortError';
                throw abortErr;
            }
            try {
                const response = await this.fetchFunction(buildPlaybackUrl(chapterId, voiceKey), {
                    method: 'GET',
                    cache: 'no-store',
                    signal: options.signal,
                    headers: { Accept: 'application/json' }
                });
                if (!response.ok) {
                    return { status: 'unknown' };
                }
                const metadata = await response.json();
                if (!metadata || typeof metadata !== 'object') {
                    return { status: 'unknown' };
                }
                if (String(metadata.chapterId) !== String(chapterId) || metadata.voiceKey !== voiceKey) {
                    return { status: 'unknown' };
                }

                if (metadata.availability === 'READY') {
                    if (isChapterPlayable(metadata)) {
                        return { status: 'ready', metadata: metadata };
                    }
                    if (metadata.freshness === 'STALE_CONTENT' && metadata.playable === false) {
                        return { status: 'unavailable', metadata: metadata };
                    }
                    return { status: 'unknown' };
                }

                if (metadata.availability === 'MISSING' || metadata.availability === 'FAILED') {
                    if (metadata.playable === false) {
                        return { status: 'unavailable', metadata: metadata };
                    }
                    return { status: 'unknown' };
                }

                if (metadata.availability === 'BUILDING') {
                    return { status: 'unknown' };
                }

                return { status: 'unknown' };
            } catch (error) {
                if (error && (error.name === 'AbortError' || error.message === 'The operation was aborted')) {
                    throw error;
                }
                return { status: 'unknown' };
            }
        }

        /**
         * Passively fetches chapter playback metadata without mutating active playback state.
         * Used for next-chapter preload foundation.
         */
        async fetchPlaybackMetadata(chapterId, voiceKey, options = {}) {
            try {
                const result = await this.probePlaybackMetadata(chapterId, voiceKey, options);
                return (result && result.status === 'ready') ? result.metadata : null;
            } catch (error) {
                if (error && (error.name === 'AbortError' || error.message === 'The operation was aborted')) return null;
                throw error;
            }
        }

        buildPrepareUrl(chapterId) {
            return buildPrepareUrl(chapterId);
        }

        buildPlaybackUrl(chapterId, voiceKey) {
            return buildPlaybackUrl(chapterId, voiceKey);
        }

        _resolveCsrf(options = {}) {
            if (options && options.csrfHeader && options.csrfToken) {
                return { header: options.csrfHeader, token: options.csrfToken };
            }
            if (this.options && this.options.csrfHeader && this.options.csrfToken) {
                return { header: this.options.csrfHeader, token: this.options.csrfToken };
            }
            const doc = (options && options.document) || (this.options && this.options.document) ||
                (typeof document !== 'undefined' ? document : null);
            return resolveCsrfToken(doc);
        }

        /**
         * Passively requests chapter narration playback preparation without mutating active playback state.
         *
         * @param {string|number} chapterId
         * @param {string} voiceKey
         * @param {Object} [options]
         * @param {AbortSignal} [options.signal]
         * @param {Function} [options.fetchFunction]
         * @param {Document} [options.document]
         * @param {string} [options.csrfHeader]
         * @param {string} [options.csrfToken]
         * @returns {Promise<{ status: 'BUILDING', response?: Object } | { status: 'REJECTED' } | { status: 'UNAVAILABLE' } | { status: 'UNKNOWN' }>}
         */
        async requestPlaybackPreparation(chapterId, voiceKey, options = {}) {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The operation was aborted');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (chapterId === null || chapterId === undefined || String(chapterId).trim() === '') {
                return { status: 'UNKNOWN' };
            }

            if (typeof voiceKey !== 'string' || voiceKey.trim() === '') {
                return { status: 'UNKNOWN' };
            }

            const fetchFn = (options && options.fetchFunction) || this.fetchFunction ||
                (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
            if (!fetchFn) {
                return { status: 'UNKNOWN' };
            }

            const csrf = this._resolveCsrf(options);
            if (!csrf || !csrf.token || !csrf.header) {
                return { status: 'UNKNOWN' };
            }

            try {
                const url = buildPrepareUrl(chapterId);
                const headers = {
                    'Accept': 'application/json',
                    'Content-Type': 'application/json',
                    [csrf.header]: csrf.token
                };

                const response = await fetchFn(url, {
                    method: 'POST',
                    cache: 'no-store',
                    headers: headers,
                    body: JSON.stringify({ voiceKey: voiceKey }),
                    signal: options.signal
                });

                if (response.status === 202) {
                    const payload = await response.json();
                    if (!payload || typeof payload !== 'object') {
                        return { status: 'UNKNOWN' };
                    }
                    if (String(payload.chapterId) !== String(chapterId)) {
                        return { status: 'UNKNOWN' };
                    }
                    if (payload.voiceKey !== voiceKey) {
                        return { status: 'UNKNOWN' };
                    }
                    if (payload.availability !== 'BUILDING') {
                        return { status: 'UNKNOWN' };
                    }
                    return {
                        status: 'BUILDING',
                        response: payload
                    };
                }

                if (response.status === 503) {
                    return { status: 'REJECTED' };
                }

                if (response.status === 400 || response.status === 404) {
                    return { status: 'UNAVAILABLE' };
                }

                return { status: 'UNKNOWN' };
            } catch (error) {
                if (error && (error.name === 'AbortError' || error.message === 'The operation was aborted')) {
                    throw error;
                }
                return { status: 'UNKNOWN' };
            }
        }

        async loadPlayback(chapterId, voiceKey, preloadedMetadata = null) {
            this.stop();
            const generation = this._generation;
            const controller = new AbortController();
            this._fetchController = controller;
            this._transitionState('LOADING');
            try {
                let metadata = preloadedMetadata;
                let isPreloadValid = false;

                if (metadata) {
                    isPreloadValid = String(metadata.chapterId) === String(chapterId) &&
                                     metadata.voiceKey === voiceKey &&
                                     isChapterPlayable(metadata);
                }

                if (!isPreloadValid) {
                    const response = await this.fetchFunction(buildPlaybackUrl(chapterId, voiceKey), {
                        method: 'GET', cache: 'no-store', signal: controller.signal,
                        headers: { Accept: 'application/json' }
                    });
                    if (!response.ok) throw new Error('Chapter playback unavailable');
                    metadata = await response.json();
                }

                if (generation !== this._generation || controller.signal.aborted) return null;

                if (!isPreloadValid) {
                    if (String(metadata.chapterId) !== String(chapterId) || metadata.voiceKey !== voiceKey) return null;
                    if (!isChapterPlayable(metadata)) {
                        this._transitionState('IDLE');
                        return null;
                    }
                }
                this.metadata = metadata;
                // Ordering is a view; persisted intervals and identifiers are never rewritten.
                this.cues = metadata.cues.slice().sort((a, b) => a.cueOrdinal - b.cueOrdinal);
                const audio = this.audioFactory();
                this.audio = audio;
                const ownsAudio = () => generation === this._generation && this.audio === audio;
                const listen = (name, callback) => {
                    const guarded = () => { if (ownsAudio()) callback(); };
                    audio.addEventListener(name, guarded);
                    this._listeners.push([name, guarded]);
                };
                listen('timeupdate', () => this._syncCue());
                listen('seeked', () => this._syncCue());
                listen('loadedmetadata', () => {
                    if (this._pendingTime !== null) {
                        audio.currentTime = this._pendingTime;
                        this._pendingTime = null;
                    }
                    this._syncCue();
                });
                listen('playing', () => {
                    if (this._wantsPlay) this._transitionState('PLAYING');
                    else audio.pause();
                });
                listen('pause', () => {
                    // Natural completion may dispatch pause before ended. Explicit pause clears intent itself.
                    if (audio.ended) return;
                    if (this._wantsPlay) this.pause();
                });
                listen('ended', () => {
                    if (!audio.ended || !this._wantsPlay || this.state !== 'PLAYING') return;
                    this._wantsPlay = false;
                    ++this._playId;
                    this._syncCue();
                    this._transitionState('STOPPED');
                    if (this.options.onChapterEnd) this.options.onChapterEnd();
                });
                listen('error', () => this._fail());
                audio.preload = 'metadata';
                audio.playbackRate = this.rate;
                audio.src = metadata.audioUrl;
                this._transitionState('IDLE');
                return metadata;
            } catch (error) {
                if (generation !== this._generation || controller.signal.aborted) return null;
                this._transitionState('IDLE');
                return null;
            } finally {
                if (this._fetchController === controller) this._fetchController = null;
            }
        }

        _time() { return this._pendingTime !== null ? this._pendingTime : (this.audio ? this.audio.currentTime : 0); }

        _syncCue() {
            const millis = this._time() * 1000;
            const index = this.cues.findIndex(cue => millis >= cue.startMillis && millis < cue.endMillis);
            if (index !== this._activeCueIndex) {
                this._activeCueIndex = index;
                if (this.options.onCueChange) this.options.onCueChange(index, this.cues[index] || null);
            }
            if (this.options.onProgress) this.options.onProgress(this.getProgress());
        }

        async play(index) {
            if (!this.audio || !this.metadata) return;
            if (Number.isInteger(index) && index >= 0) this.seekToChunk(index);
            else if (this.audio.ended) this._seekTime(0);
            const audio = this.audio;
            const generation = this._generation;
            const playId = ++this._playId;
            this._wantsPlay = true;
            this._transitionState('PLAYING');
            this._syncCue();
            try {
                await audio.play();
                if (generation !== this._generation || this.audio !== audio) {
                    audio.pause();
                    return;
                }
                if (!this._wantsPlay) audio.pause();
            } catch (error) {
                if (generation !== this._generation || playId !== this._playId || !this._wantsPlay) return;
                this._fail();
            }
        }

        pause() {
            this._wantsPlay = false;
            ++this._playId;
            this._transitionState('PAUSED');
            if (this.audio) this.audio.pause();
        }

        resume() { return this.play(); }

        _duration() {
            if (this.audio && Number.isFinite(this.audio.duration)) return this.audio.duration;
            return this.metadata && Number.isFinite(this.metadata.durationMillis)
                ? this.metadata.durationMillis / 1000 : Infinity;
        }

        _seekTime(seconds) {
            if (!this.audio || !Number.isFinite(seconds)) return;
            const duration = this._duration();
            const target = Math.max(0, Math.min(duration, seconds));
            const isExplicitEnd = target >= duration && duration > 0;

            if (isExplicitEnd && this.state === 'PLAYING') {
                this._wantsPlay = false;
                ++this._playId;
                this._transitionState('STOPPED');
                if (this.audio.readyState !== 0) {
                    this.audio.pause();
                }
            }

            if (this.audio.readyState === 0) this._pendingTime = target;
            else {
                this.audio.currentTime = target;
                this._pendingTime = null;
            }
            this._syncCue();
        }

        seekBySeconds(deltaSeconds) {
            if (Number.isFinite(deltaSeconds)) this._seekTime(this._time() + deltaSeconds);
        }

        seekToRatio(ratio) {
            const duration = this.getProgress().durationSeconds;
            if (!Number.isFinite(ratio) || duration <= 0) return;
            this._seekTime(Math.max(0, Math.min(1, ratio)) * duration);
        }

        seekToChunk(index) {
            const cue = this.cues[index];
            if (cue) this._seekTime(cue.startMillis / 1000);
        }

        _previousIndex() {
            if (this._activeCueIndex >= 0) return this._activeCueIndex - 1;
            const millis = this._time() * 1000;
            for (let i = this.cues.length - 1; i >= 0; --i) {
                if (this.cues[i].endMillis <= millis) return i;
            }
            return -1;
        }

        _nextIndex() {
            if (this._activeCueIndex >= 0) return this._activeCueIndex + 1;
            return this.cues.findIndex(cue => cue.startMillis > this._time() * 1000);
        }

        canPrevious() { return this._previousIndex() >= 0; }
        canNext() { const index = this._nextIndex(); return index >= 0 && index < this.cues.length; }
        previousChunk() { if (this.canPrevious()) this.seekToChunk(this._previousIndex()); }
        nextChunk() { if (this.canNext()) this.seekToChunk(this._nextIndex()); }

        setRate(rate) {
            if (!Number.isFinite(Number(rate))) return;
            this.rate = Math.max(0.5, Math.min(2, Number(rate)));
            if (this.audio) this.audio.playbackRate = this.rate;
        }

        _fail() {
            this._wantsPlay = false;
            ++this._playId;
            if (this.audio) this.audio.pause();
            this._transitionState('ERROR');
            if (this.options.onError) this.options.onError();
        }

        stop() {
            ++this._generation;
            ++this._playId;
            this._wantsPlay = false;
            if (this._fetchController) this._fetchController.abort();
            this._fetchController = null;
            if (this.audio) {
                const audio = this.audio;
                this.audio = null;
                this._listeners.forEach(([name, callback]) => audio.removeEventListener(name, callback));
                audio.pause();
                audio.removeAttribute('src');
                audio.load();
            }
            this._listeners = [];
            this.metadata = null;
            this.cues = [];
            this._activeCueIndex = -1;
            this._pendingTime = null;
            this._transitionState('STOPPED');
        }

        cancel() { this.stop(); }
        destroy() { this.stop(); }
    }

    ChapterAudioEngine.buildPlaybackUrl = buildPlaybackUrl;
    ChapterAudioEngine.buildPrepareUrl = buildPrepareUrl;
    ChapterAudioEngine.resolveCsrfToken = resolveCsrfToken;
    ChapterAudioEngine.isChapterPlayable = isChapterPlayable;

    return { ChapterAudioEngine, buildPlaybackUrl, buildPrepareUrl, resolveCsrfToken, isChapterPlayable };
});
