/**
 * KiemLai Universe — Novel Reader Narration Controller
 *
 * Responsibilities:
 * - Coordinate NarrationTextParser and NarrationEngine (BrowserTtsEngine).
 * - Manage player UI state, DOM bindings, and accessible announcements.
 * - Initialize chunked narration from the rendered chapter body.
 * - Handle play/pause, prev/next chunk navigation, voice selection, and playback rate changes.
 * - Manage visual Follow Mode: highlight active narrated content and keep it smoothly in view.
 * - Synchronize sentence progress and navigation controls immediately on pause/idle/stopped navigation.
 * - Maintain natural chapter completion semantics (total/total, replay from start).
 * - Gracefully handle unsupported browsers, no-voice environments, and empty chapter text.
 * - Clean up active playback, highlights, and event listeners on page unload.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NarrationController = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NarrationController = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Default element selector configuration.
     */
    const DEFAULT_SELECTORS = {
        player: '#novelNarrationPlayer',
        body: '.novel-reader-chapter-body',
        playPauseBtn: '#novelNarrationPlayPauseBtn',
        playIcon: '.novel-narration-icon--play',
        pauseIcon: '.novel-narration-icon--pause',
        prevBtn: '#novelNarrationPrevBtn',
        nextBtn: '#novelNarrationNextBtn',
        voiceSelect: '#novelNarrationVoiceSelect',
        rateSelect: '#novelNarrationRateSelect',
        progressCurrent: '#novelNarrationProgressCurrent',
        progressTotal: '#novelNarrationProgressTotal',
        statusText: '#novelNarrationStatusText',
        followToggle: '#novelNarrationFollowToggle'
    };

    /**
     * Active highlight CSS class applied to narrated DOM elements.
     */
    const HIGHLIGHT_CLASS = 'novel-narration-highlight';

    /**
     * Narration Controller managing player UI and TTS engine orchestration.
     */
    class NarrationController {
        /**
         * @param {Object} [config]
         * @param {Object} [config.selectors] - Custom DOM selector overrides
         * @param {Object} [config.engine] - Custom engine instance (defaults to BrowserTtsEngine)
         * @param {Object} [config.parser] - Custom parser instance (defaults to NarrationTextParser)
         * @param {boolean} [config.followMode=true] - Initial follow mode state
         */
        constructor(config = {}) {
            this.selectors = Object.assign({}, DEFAULT_SELECTORS, config.selectors);
            this.parser = config.parser || (typeof window !== 'undefined' ? (window.NarrationTextParser || (window.KiemLai && window.KiemLai.NarrationTextParser)) : null);

            this.dom = {
                player: null,
                body: null,
                playPauseBtn: null,
                playIcon: null,
                pauseIcon: null,
                prevBtn: null,
                nextBtn: null,
                voiceSelect: null,
                rateSelect: null,
                progressCurrent: null,
                progressTotal: null,
                statusText: null,
                followToggle: null
            };

            this.chunks = [];
            this.initialized = false;
            this.isCompleted = false;
            this.followMode = typeof config.followMode === 'boolean' ? config.followMode : true;
            this.activeHighlightedElement = null;

            // Bound handlers for cleanup
            this._boundOnPlayPause = this._handlePlayPause.bind(this);
            this._boundOnPrev = this._handlePrev.bind(this);
            this._boundOnNext = this._handleNext.bind(this);
            this._boundOnVoiceChange = this._handleVoiceChange.bind(this);
            this._boundOnRateChange = this._handleRateChange.bind(this);
            this._boundOnFollowChange = this._handleFollowChange.bind(this);
            this._boundOnUnload = this._handleUnload.bind(this);

            // Engine callbacks
            const engineOptions = {
                onStateChange: this._onEngineStateChange.bind(this),
                onChunkStart: this._onEngineChunkStart.bind(this),
                onChunkEnd: this._onEngineChunkEnd.bind(this),
                onChapterEnd: this._onEngineChapterEnd.bind(this),
                onError: this._onEngineError.bind(this),
                onVoicesChanged: this._onEngineVoicesChanged.bind(this)
            };

            if (config.engine) {
                this.engine = config.engine;
            } else {
                const EngineClass = typeof window !== 'undefined'
                    ? (window.BrowserTtsEngine ? window.BrowserTtsEngine.BrowserTtsEngine || window.BrowserTtsEngine : (window.KiemLai && window.KiemLai.BrowserTtsEngine ? window.KiemLai.BrowserTtsEngine.BrowserTtsEngine || window.KiemLai.BrowserTtsEngine : null))
                    : null;
                this.engine = EngineClass ? new EngineClass(engineOptions) : null;
            }
        }

        /**
         * Initializes controller, queries DOM elements, parses text, and sets up UI bindings.
         * @returns {boolean} True if successfully initialized
         */
        init() {
            if (typeof document === 'undefined') {
                return false;
            }

            this._queryDomElements();

            if (!this.dom.player || !this.dom.body) {
                return false;
            }

            if (!this.engine || !this.engine.isSupported()) {
                this._renderUnsupportedState();
                return false;
            }

            this.isCompleted = false;
            this._parseAndLoadChunks();
            this._populateVoiceDropdown(this.engine.getSortedVoices ? this.engine.getSortedVoices() : this.engine.getVoices());
            this._bindEventListeners();
            this._updateProgressDisplay(0, this.chunks.length);
            this._updateNavButtons();

            if (this.dom.followToggle) {
                this.dom.followToggle.checked = this.followMode;
                this.dom.followToggle.setAttribute('aria-checked', String(this.followMode));
            }

            this.initialized = true;
            return true;
        }

        /**
         * Queries and caches DOM element references.
         * @private
         */
        _queryDomElements() {
            const sel = this.selectors;
            this.dom.player = document.querySelector(sel.player);
            this.dom.body = document.querySelector(sel.body);
            this.dom.followToggle = document.querySelector(sel.followToggle);

            if (!this.dom.player) {
                return;
            }

            this.dom.playPauseBtn = this.dom.player.querySelector(sel.playPauseBtn);
            this.dom.playIcon = this.dom.player.querySelector(sel.playIcon);
            this.dom.pauseIcon = this.dom.player.querySelector(sel.pauseIcon);
            this.dom.prevBtn = this.dom.player.querySelector(sel.prevBtn);
            this.dom.nextBtn = this.dom.player.querySelector(sel.nextBtn);
            this.dom.voiceSelect = this.dom.player.querySelector(sel.voiceSelect);
            this.dom.rateSelect = this.dom.player.querySelector(sel.rateSelect);
            this.dom.progressCurrent = this.dom.player.querySelector(sel.progressCurrent);
            this.dom.progressTotal = this.dom.player.querySelector(sel.progressTotal);
            this.dom.statusText = this.dom.player.querySelector(sel.statusText);
        }

        /**
         * Renders unsupported browser UI state.
         * @private
         */
        _renderUnsupportedState() {
            if (this.dom.player) {
                this.dom.player.classList.add('is-unsupported');
            }
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.disabled = true;
                this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
            }
            if (this.dom.prevBtn) {
                this.dom.prevBtn.disabled = true;
            }
            if (this.dom.nextBtn) {
                this.dom.nextBtn.disabled = true;
            }
            if (this.dom.voiceSelect) {
                this.dom.voiceSelect.disabled = true;
                this.dom.voiceSelect.innerHTML = '<option value="">Không hỗ trợ giọng đọc</option>';
            }
            if (this.dom.rateSelect) {
                this.dom.rateSelect.disabled = true;
            }
            if (this.dom.followToggle) {
                this.dom.followToggle.disabled = true;
            }
            this._setStatusMessage('Trình duyệt không hỗ trợ Web Speech API.');
        }

        /**
         * Extracts and segments text chunks from the chapter body container.
         * @private
         */
        _parseAndLoadChunks() {
            if (!this.parser || typeof this.parser.parseChapterBody !== 'function') {
                console.warn('[NarrationController] NarrationTextParser not available.');
                this.chunks = [];
                return;
            }

            this.chunks = this.parser.parseChapterBody(this.dom.body);
            if (this.engine) {
                this.engine.loadChunks(this.chunks);
            }

            if (this.chunks.length === 0) {
                this._setStatusMessage('Không tìm thấy nội dung văn bản để đọc.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.disabled = true;
                }
            } else {
                this._setStatusMessage('Sẵn sàng phát giọng đọc (' + this.chunks.length + ' câu).');
            }
        }

        /**
         * Binds DOM event listeners.
         * @private
         */
        _bindEventListeners() {
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.addEventListener('click', this._boundOnPlayPause);
            }
            if (this.dom.prevBtn) {
                this.dom.prevBtn.addEventListener('click', this._boundOnPrev);
            }
            if (this.dom.nextBtn) {
                this.dom.nextBtn.addEventListener('click', this._boundOnNext);
            }
            if (this.dom.voiceSelect) {
                this.dom.voiceSelect.addEventListener('change', this._boundOnVoiceChange);
            }
            if (this.dom.rateSelect) {
                this.dom.rateSelect.addEventListener('change', this._boundOnRateChange);
            }
            if (this.dom.followToggle) {
                this.dom.followToggle.addEventListener('change', this._boundOnFollowChange);
            }

            window.addEventListener('beforeunload', this._boundOnUnload);
            window.addEventListener('pagehide', this._boundOnUnload);
        }

        /**
         * Populates the voice selector dropdown with Vietnamese voices grouped first.
         * @param {Array<SpeechSynthesisVoice>} voices
         * @private
         */
        _populateVoiceDropdown(voices) {
            if (!this.dom.voiceSelect) {
                return;
            }

            if (!Array.isArray(voices) || voices.length === 0) {
                this.dom.voiceSelect.innerHTML = '<option value="">Không tìm thấy giọng đọc trên thiết bị</option>';
                return;
            }

            const viVoices = [];
            const otherVoices = [];

            for (let i = 0; i < voices.length; i++) {
                const voice = voices[i];
                const lang = (voice.lang || '').toLowerCase();
                if (lang.startsWith('vi') || lang.includes('vi-vn') || lang.includes('vi_vn')) {
                    viVoices.push(voice);
                } else {
                    otherVoices.push(voice);
                }
            }

            this.dom.voiceSelect.innerHTML = '';

            // 1. Vietnamese Voices Group
            if (viVoices.length > 0) {
                const viGroup = document.createElement('optgroup');
                viGroup.label = 'Giọng đọc Tiếng Việt';

                for (let i = 0; i < viVoices.length; i++) {
                    const v = viVoices[i];
                    const option = document.createElement('option');
                    option.value = v.voiceURI || v.name;
                    option.textContent = v.name + (v.default ? ' (Mặc định)' : '');
                    viGroup.appendChild(option);
                }

                this.dom.voiceSelect.appendChild(viGroup);
            }

            // 2. Other System Voices Group
            if (otherVoices.length > 0) {
                const otherGroup = document.createElement('optgroup');
                otherGroup.label = viVoices.length > 0 ? 'Giọng đọc khác' : 'Tất cả giọng đọc';

                for (let i = 0; i < otherVoices.length; i++) {
                    const v = otherVoices[i];
                    const option = document.createElement('option');
                    option.value = v.voiceURI || v.name;
                    option.textContent = v.name + ' (' + (v.lang || 'N/A') + ')';
                    otherGroup.appendChild(option);
                }

                this.dom.voiceSelect.appendChild(otherGroup);
            }

            // Select active voice in engine
            const activeVoice = this.engine && this.engine.selectedVoice;
            if (activeVoice) {
                this.dom.voiceSelect.value = activeVoice.voiceURI || activeVoice.name;
            } else if (viVoices.length > 0) {
                this.dom.voiceSelect.value = viVoices[0].voiceURI || viVoices[0].name;
                if (this.engine) {
                    this.engine.setVoice(viVoices[0]);
                }
            }
        }

        /**
         * Sets follow mode ON or OFF.
         * When turned OFF: immediately clears any active highlight without pausing TTS.
         * When turned ON during playback: immediately highlights and scrolls to current chunk.
         * @param {boolean} enabled
         */
        setFollowMode(enabled) {
            this.followMode = Boolean(enabled);

            if (this.dom.followToggle) {
                this.dom.followToggle.checked = this.followMode;
                this.dom.followToggle.setAttribute('aria-checked', String(this.followMode));
            }

            if (!this.followMode) {
                this._clearHighlight();
            } else {
                // If turned ON during active/paused playback, synchronize highlight & view immediately
                if (this.engine && this.chunks.length > 0) {
                    const curIndex = this.engine.getCurrentChunkIndex();
                    const curChunk = this.chunks[curIndex];
                    if (curChunk && curChunk.element) {
                        const state = this.engine.getState();
                        if (state === 'PLAYING' || state === 'PAUSED') {
                            this._highlightChunk(curChunk);
                            if (!this._isElementComfortablyVisible(curChunk.element)) {
                                this._scrollElementIntoView(curChunk.element);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Handles Follow Mode toggle switch change.
         * @private
         */
        _handleFollowChange() {
            if (this.dom.followToggle) {
                this.setFollowMode(this.dom.followToggle.checked);
            }
        }

        /**
         * Highlights the element corresponding to the given chunk.
         * Avoids redundant DOM mutations when consecutive chunks belong to the same source element.
         * @param {Object} chunk
         * @returns {boolean} True if the highlighted element changed or was newly applied
         * @private
         */
        _highlightChunk(chunk) {
            if (!this.followMode) {
                return false;
            }

            if (!chunk || !chunk.element || chunk.element.nodeType !== 1) {
                this._clearHighlight();
                return false;
            }

            // Deduplicate: If the same source element is already highlighted, avoid redundant work
            if (this.activeHighlightedElement === chunk.element) {
                return false;
            }

            this._clearHighlight();

            chunk.element.classList.add(HIGHLIGHT_CLASS);
            this.activeHighlightedElement = chunk.element;
            return true;
        }

        /**
         * Clears all active narration highlights.
         * @private
         */
        _clearHighlight() {
            if (this.activeHighlightedElement) {
                this.activeHighlightedElement.classList.remove(HIGHLIGHT_CLASS);
                this.activeHighlightedElement = null;
            }

            if (this.dom.body) {
                const leftovers = this.dom.body.querySelectorAll('.' + HIGHLIGHT_CLASS);
                for (let i = 0; i < leftovers.length; i++) {
                    leftovers[i].classList.remove(HIGHLIGHT_CLASS);
                }
            }
        }

        /**
         * Checks whether an element is comfortably visible within the viewport.
         * Handles both standard elements and tall blocks exceeding viewport height.
         * @param {HTMLElement} element
         * @returns {boolean}
         * @private
         */
        _isElementComfortablyVisible(element) {
            if (!element || typeof element.getBoundingClientRect !== 'function' || typeof window === 'undefined') {
                return true;
            }

            const rect = element.getBoundingClientRect();
            const windowHeight = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
            const topMargin = 70;
            const bottomMargin = 70;
            const comfortableHeight = windowHeight - topMargin - bottomMargin;

            if (comfortableHeight <= 0) {
                return true;
            }

            // 1. Standard element that fits within comfortable viewport area
            if (rect.height <= comfortableHeight) {
                return rect.top >= topMargin && rect.bottom <= (windowHeight - bottomMargin);
            }

            // 2. Tall element exceeding comfortable viewport area:
            // Considered visible if top is within view, or if element spans/occupies comfortable reading zone
            const topInView = rect.top >= topMargin && rect.top <= (windowHeight - bottomMargin);
            const coversViewport = rect.top <= topMargin && rect.bottom >= (windowHeight - bottomMargin);
            const partiallyVisible = rect.top <= (windowHeight - bottomMargin) && rect.bottom >= (topMargin + Math.min(60, rect.height * 0.2));

            return topInView || coversViewport || partiallyVisible;
        }

        /**
         * Smoothly scrolls an element into the comfortable view area.
         * For tall elements, aligns to start; for standard elements, centers smoothly.
         * @param {HTMLElement} element
         * @private
         */
        _scrollElementIntoView(element) {
            if (!element || typeof element.scrollIntoView !== 'function') {
                return;
            }

            try {
                let blockAlign = 'center';
                if (typeof element.getBoundingClientRect === 'function' && typeof window !== 'undefined') {
                    const rect = element.getBoundingClientRect();
                    const windowHeight = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
                    const comfortableHeight = windowHeight - 140;
                    if (comfortableHeight > 0 && rect.height > comfortableHeight) {
                        blockAlign = 'start';
                    }
                }

                element.scrollIntoView({
                    behavior: 'smooth',
                    block: blockAlign,
                    inline: 'nearest'
                });
            } catch (e) {
                // Fallback for older browsers
                element.scrollIntoView(true);
            }
        }

        /**
         * Handles Play/Pause button click.
         * If natural completion occurred, restarts from chunk 0.
         * @private
         */
        _handlePlayPause() {
            if (!this.engine || this.chunks.length === 0) {
                return;
            }

            if (this.isCompleted) {
                this.isCompleted = false;
                this.engine.play(0);
                return;
            }

            const state = this.engine.getState();
            if (state === 'PLAYING') {
                this.engine.pause();
            } else if (state === 'PAUSED') {
                this.engine.resume();
            } else {
                this.engine.play(this.engine.getCurrentChunkIndex());
            }
        }

        /**
         * Handles Previous Sentence button click and synchronizes UI immediately.
         * @private
         */
        _handlePrev() {
            if (this.engine) {
                this.isCompleted = false;
                this.engine.previousChunk();
                this._syncNavigationAndProgress();
            }
        }

        /**
         * Handles Next Sentence button click and synchronizes UI immediately.
         * @private
         */
        _handleNext() {
            if (this.engine) {
                this.isCompleted = false;
                this.engine.nextChunk();
                this._syncNavigationAndProgress();
            }
        }

        /**
         * Navigates to a specific chunk and synchronizes UI immediately.
         * @param {number} index
         */
        seekToChunk(index) {
            if (this.engine) {
                this.isCompleted = false;
                this.engine.seekToChunk(index);
                this._syncNavigationAndProgress();
            }
        }

        /**
         * Synchronizes sentence progress counter, nav buttons, and status when navigating while paused/idle/stopped.
         * @private
         */
        _syncNavigationAndProgress() {
            if (!this.engine || this.chunks.length === 0) {
                return;
            }

            const curIndex = this.engine.getCurrentChunkIndex();
            const curChunk = this.chunks[curIndex];
            const currentNum = curIndex + 1;
            const totalNum = this.chunks.length;

            this._updateProgressDisplay(currentNum, totalNum);
            this._updateNavButtons();

            const state = this.engine.getState();
            if (state !== 'PLAYING') {
                if (curChunk && curChunk.element && this.followMode) {
                    const elementChanged = this._highlightChunk(curChunk);
                    if (elementChanged && !this._isElementComfortablyVisible(curChunk.element)) {
                        this._scrollElementIntoView(curChunk.element);
                    }
                }

                if (state === 'PAUSED') {
                    this._setStatusMessage('Đang ở câu ' + currentNum + ' / ' + totalNum + ' (Tạm dừng)');
                } else {
                    this._setStatusMessage('Đang ở câu ' + currentNum + ' / ' + totalNum);
                }
            }
        }

        /**
         * Handles Voice selector change.
         * @private
         */
        _handleVoiceChange() {
            if (!this.engine || !this.dom.voiceSelect) {
                return;
            }
            const voiceVal = this.dom.voiceSelect.value;
            this.engine.setVoice(voiceVal);
        }

        /**
         * Handles Rate selector change.
         * @private
         */
        _handleRateChange() {
            if (!this.engine || !this.dom.rateSelect) {
                return;
            }
            const rateVal = parseFloat(this.dom.rateSelect.value);
            if (Number.isFinite(rateVal)) {
                this.engine.setRate(rateVal);
            }
        }

        /**
         * Handles page unload/hide cleanup.
         * @private
         */
        _handleUnload() {
            this.isCompleted = false;
            this._clearHighlight();
            if (this.engine) {
                this.engine.stop();
            }
        }

        /**
         * Engine Callback: State transition.
         * @param {string} newState
         * @private
         */
        _onEngineStateChange(newState) {
            const isPlaying = newState === 'PLAYING';

            if (this.dom.playIcon && this.dom.pauseIcon) {
                this.dom.playIcon.style.display = isPlaying ? 'none' : '';
                this.dom.pauseIcon.style.display = isPlaying ? '' : 'none';
            }

            if (this.dom.playPauseBtn) {
                let label = 'Phát giọng đọc';
                if (isPlaying) {
                    label = 'Tạm dừng giọng đọc';
                } else if (newState === 'PAUSED') {
                    label = 'Tiếp tục đọc';
                } else if (this.isCompleted) {
                    label = 'Phát lại từ đầu';
                }
                this.dom.playPauseBtn.setAttribute('aria-label', label);
                this.dom.playPauseBtn.title = label;
                this.dom.playPauseBtn.classList.toggle('is-playing', isPlaying);
            }

            if (newState === 'PAUSED') {
                this._setStatusMessage('Đã tạm dừng giọng đọc.');
            } else if (newState === 'STOPPED') {
                if (this.isCompleted) {
                    this._updateProgressDisplay(this.chunks.length, this.chunks.length);
                    this._setStatusMessage('Đã đọc xong chương.');
                } else {
                    this._clearHighlight();
                    this._updateProgressDisplay(0, this.chunks.length);
                    this._setStatusMessage('Đã dừng phát.');
                }
            }

            this._updateNavButtons();
        }

        /**
         * Engine Callback: Chunk start.
         * Highlights chunk element and scrolls it smoothly into comfortable view if followMode is enabled.
         * Avoids redundant scrolling and class toggling when consecutive chunks share the same DOM element.
         * @param {number} chunkIndex
         * @param {Object} chunk
         * @private
         */
        _onEngineChunkStart(chunkIndex, chunk) {
            this.isCompleted = false;
            const currentNum = chunkIndex + 1;
            const totalNum = this.chunks.length;

            this._updateProgressDisplay(currentNum, totalNum);
            this._setStatusMessage('Đang đọc câu ' + currentNum + ' / ' + totalNum);
            this._updateNavButtons();

            if (this.followMode && chunk && chunk.element) {
                const elementChanged = this._highlightChunk(chunk);
                if (elementChanged && !this._isElementComfortablyVisible(chunk.element)) {
                    this._scrollElementIntoView(chunk.element);
                }
            }
        }

        /**
         * Engine Callback: Chunk end.
         * @param {number} chunkIndex
         * @param {Object} chunk
         * @private
         */
        _onEngineChunkEnd(chunkIndex, chunk) {
            // Intentionally passive; next chunk will trigger _onEngineChunkStart
        }

        /**
         * Engine Callback: Natural Chapter end.
         * Clears highlight, displays total/total, and configures replay from start.
         * @private
         */
        _onEngineChapterEnd() {
            this.isCompleted = true;
            this._clearHighlight();
            this._updateProgressDisplay(this.chunks.length, this.chunks.length);
            this._setStatusMessage('Đã đọc xong chương.');
            this._updateNavButtons();
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.setAttribute('aria-label', 'Phát lại từ đầu');
                this.dom.playPauseBtn.title = 'Phát lại từ đầu';
            }
        }

        /**
         * Engine Callback: Error.
         * @param {any} error
         * @private
         */
        _onEngineError(error) {
            this._setStatusMessage('Xảy ra lỗi khi phát giọng đọc.');
        }

        /**
         * Engine Callback: Voices changed.
         * @param {Array<SpeechSynthesisVoice>} voices
         * @private
         */
        _onEngineVoicesChanged(voices) {
            this._populateVoiceDropdown(voices);
        }

        /**
         * Updates sentence progress display in DOM.
         * @param {number} current
         * @param {number} total
         * @private
         */
        _updateProgressDisplay(current, total) {
            if (this.dom.progressCurrent) {
                this.dom.progressCurrent.textContent = String(current);
            }
            if (this.dom.progressTotal) {
                this.dom.progressTotal.textContent = String(total);
            }
        }

        /**
         * Updates status live region text.
         * @param {string} message
         * @private
         */
        _setStatusMessage(message) {
            if (this.dom.statusText) {
                this.dom.statusText.textContent = message;
            }
        }

        /**
         * Updates previous/next sentence buttons disabled states.
         * @private
         */
        _updateNavButtons() {
            if (!this.engine || this.chunks.length === 0) {
                if (this.dom.prevBtn) {
                    this.dom.prevBtn.disabled = true;
                    this.dom.prevBtn.setAttribute('aria-disabled', 'true');
                }
                if (this.dom.nextBtn) {
                    this.dom.nextBtn.disabled = true;
                    this.dom.nextBtn.setAttribute('aria-disabled', 'true');
                }
                return;
            }

            const currentIndex = this.engine.getCurrentChunkIndex();
            const total = this.chunks.length;

            if (this.dom.prevBtn) {
                const canPrev = currentIndex > 0;
                this.dom.prevBtn.disabled = !canPrev;
                this.dom.prevBtn.setAttribute('aria-disabled', String(!canPrev));
            }

            if (this.dom.nextBtn) {
                const canNext = !this.isCompleted && (currentIndex < total - 1);
                this.dom.nextBtn.disabled = !canNext;
                this.dom.nextBtn.setAttribute('aria-disabled', String(!canNext));
            }
        }

        /**
         * Destroys controller and releases engine, highlights, and listeners.
         */
        destroy() {
            this._clearHighlight();

            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.removeEventListener('click', this._boundOnPlayPause);
            }
            if (this.dom.prevBtn) {
                this.dom.prevBtn.removeEventListener('click', this._boundOnPrev);
            }
            if (this.dom.nextBtn) {
                this.dom.nextBtn.removeEventListener('click', this._boundOnNext);
            }
            if (this.dom.voiceSelect) {
                this.dom.voiceSelect.removeEventListener('change', this._boundOnVoiceChange);
            }
            if (this.dom.rateSelect) {
                this.dom.rateSelect.removeEventListener('change', this._boundOnRateChange);
            }
            if (this.dom.followToggle) {
                this.dom.followToggle.removeEventListener('change', this._boundOnFollowChange);
            }

            window.removeEventListener('beforeunload', this._boundOnUnload);
            window.removeEventListener('pagehide', this._boundOnUnload);

            if (this.engine && typeof this.engine.destroy === 'function') {
                this.engine.destroy();
            }

            this.chunks = [];
            this.isCompleted = false;
            this.initialized = false;
        }
    }

    // Auto-initialize when loaded in browser DOM
    if (typeof document !== 'undefined') {
        const autoInit = function () {
            const playerEl = document.querySelector(DEFAULT_SELECTORS.player);
            const bodyEl = document.querySelector(DEFAULT_SELECTORS.body);
            if (playerEl && bodyEl) {
                const controller = new NarrationController();
                controller.init();
                if (window.KiemLai) {
                    window.KiemLai.activeNarrationController = controller;
                }
            }
        };

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', autoInit);
        } else {
            autoInit();
        }
    }

    return {
        NarrationController: NarrationController,
        DEFAULT_SELECTORS: Object.freeze(DEFAULT_SELECTORS),
        HIGHLIGHT_CLASS: HIGHLIGHT_CLASS
    };
});
