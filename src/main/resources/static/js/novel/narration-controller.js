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
        rewindBtn: '#novelNarrationRewindBtn',
        forwardBtn: '#novelNarrationForwardBtn',
        progressBar: '#novelNarrationProgressBar',
        progressFill: '#novelNarrationProgressFill',
        voiceSelect: '#novelNarrationVoiceSelect',
        rateSelect: '#novelNarrationRateSelect',
        progressCurrent: '#novelNarrationProgressCurrent',
        progressTotal: '#novelNarrationProgressTotal',
        statusText: '#novelNarrationStatusText',
        followToggle: '#novelNarrationFollowToggle',
        autoNextToggle: '#novelNarrationAutoNextToggle',
        fallbackToggle: '#novelNarrationFallbackToggle',
        collapseToggle: '#novelNarrationCollapseToggle',
        settingsTrigger: '#novelNarrationSettingsTrigger',
        settingsPanel: '#novelNarrationSettingsPanel',
        settingsCloseBtn: '#novelNarrationSettingsCloseBtn',
        nextChapterLink: '.novel-chapter-nav-btn--next, a[rel="next"]'
    };

    /**
     * Active highlight CSS class applied to narrated DOM elements.
     */
    const HIGHLIGHT_CLASS = 'novel-narration-highlight';

    /**
     * Storage keys for Narration preferences and resume state.
     */
    const STORAGE_KEYS = {
        preferences: 'kiemlai:narration:preferences:v1',
        resume: 'kiemlai:narration:resume:v1'
    };

    /**
     * Narration Controller managing player UI and TTS engine orchestration.
     */
    class NarrationController {
        /**
         * @param {Object} [config]
         * @param {Object} [config.selectors] - Custom DOM selector overrides
         * @param {Object} [config.engine] - Custom engine instance (defaults to BrowserTtsEngine)
         * @param {Object} [config.parser] - Custom parser instance (defaults to NarrationTextParser)
         * @param {string} [config.chapterId] - Explicit chapter ID
         * @param {Object} [config.storageKeys] - Custom storage key overrides
         * @param {boolean} [config.followMode=true] - Initial follow mode state
         * @param {boolean} [config.autoNext=false] - Initial auto-next state
         * @param {boolean} [config.fallbackToDevice=false] - Initial device fallback state
         */
        constructor(config = {}) {
            this.config = config;
            this.selectors = Object.assign({}, DEFAULT_SELECTORS, config.selectors);
            this.storageKeys = Object.assign({}, STORAGE_KEYS, config.storageKeys);
            this.parser = config.parser || (typeof window !== 'undefined' ? (window.NarrationTextParser || (window.KiemLai && window.KiemLai.NarrationTextParser)) : null);

            this.dom = {
                player: null,
                body: null,
                playPauseBtn: null,
                playIcon: null,
                pauseIcon: null,
                prevBtn: null,
                nextBtn: null,
                rewindBtn: null,
                forwardBtn: null,
                progressBar: null,
                progressFill: null,
                voiceSelect: null,
                rateSelect: null,
                progressCurrent: null,
                progressTotal: null,
                statusText: null,
                followToggle: null,
                autoNextToggle: null,
                fallbackToggle: null,
                collapseToggle: null,
                settingsTrigger: null,
                settingsPanel: null,
                settingsCloseBtn: null
            };

            this.chunks = [];
            this.chapterId = config.chapterId || null;
            this.initialized = false;
            this.isCompleted = false;
            this.isUnloaded = false;
            this.hasMeaningfulResume = false;
            this.isAutoplayContinuation = false;
            this.followMode = typeof config.followMode === 'boolean' ? config.followMode : true;
            this.autoNext = typeof config.autoNext === 'boolean' ? config.autoNext : false;
            this.fallbackToDevice = typeof config.fallbackToDevice === 'boolean' ? config.fallbackToDevice : false;
            this.activeHighlightedElement = null;
            this.activeNarrationSegmentId = null;
            this.activeChapterHighlightedElements = new Set();
            this.savedVoicePreference = null;
            this.isNavigatingToNext = false;
            this._hasUserExplicitlySelectedVoice = false;
            this._managedCatalogResolved = false;
            this._cachedManagedVoices = [];
            this._autoNextTimeoutId = null;
            this._transitionAbortController = null;
            this._transitionSequenceId = 0;
            this._voiceSelectionSequenceId = 0;
            this._chapterSelectionId = 0;

            // Bound handlers for cleanup
            this._boundOnPlayPause = this._handlePlayPause.bind(this);
            this._boundOnPrev = this._handlePrev.bind(this);
            this._boundOnNext = this._handleNext.bind(this);
            this._boundOnRewind = this._handleRewind.bind(this);
            this._boundOnForward = this._handleForward.bind(this);
            this._boundOnProgressBarClick = this._handleProgressBarClick.bind(this);
            this._boundOnProgressBarKeydown = this._handleProgressBarKeydown.bind(this);
            this._boundOnVoiceChange = this._handleVoiceChange.bind(this);
            this._boundOnRateChange = this._handleRateChange.bind(this);
            this._boundOnFollowChange = this._handleFollowChange.bind(this);
            this._boundOnAutoNextChange = this._handleAutoNextChange.bind(this);
            this._boundOnFallbackChange = this._handleFallbackChange.bind(this);
            this._boundOnCollapseToggle = this._handleCollapseToggleClick.bind(this);
            this._boundOnSettingsTriggerClick = this._handleSettingsTriggerClick.bind(this);
            this._boundOnSettingsCloseClick = this._handleSettingsCloseClick.bind(this);
            this._boundOnDocumentClick = this._handleDocumentClick.bind(this);
            this._boundOnDocumentKeydown = this._handleDocumentKeydown.bind(this);
            this._boundOnPopState = this._handlePopState.bind(this);
            this._boundOnUnload = this._handleUnload.bind(this);
            this._boundOnStorage = this._handleStorageEvent.bind(this);

            // Engine callbacks
            const deviceEngineOptions = {
                onStateChange: (newState, prevState) => this._onEngineStateChange(newState, prevState, 'device'),
                onChunkStart: (chunkIndex, chunk) => this._onEngineChunkStart(chunkIndex, chunk, 'device'),
                onChunkEnd: (chunkIndex, chunk) => this._onEngineChunkEnd(chunkIndex, chunk, 'device'),
                onChapterEnd: () => this._onEngineChapterEnd('device'),
                onError: (error) => this._onEngineError(error, 'device'),
                onVoicesChanged: (voices) => this._onEngineVoicesChanged(voices)
            };

            const managedEngineOptions = {
                onStateChange: (newState, prevState) => this._onEngineStateChange(newState, prevState, 'managed'),
                onSegmentStart: (segIndex, seg, playbackDto) => this._onEngineChunkStart(segIndex, seg, 'managed', playbackDto),
                onSegmentEnd: (segIndex, seg) => this._onEngineChunkEnd(segIndex, seg, 'managed'),
                onChapterEnd: () => this._onEngineChapterEnd('managed'),
                onBlocked: (segIndex, seg, playbackDto) => this._onEngineBlocked(segIndex, seg, 'managed', playbackDto),
                onError: (error) => this._onEngineError(error, 'managed'),
                onManifestLoaded: (manifest) => this._onManifestLoaded(manifest)
            };
            // Legacy callbacks cannot update the dock while chapter audio owns playback.
            Object.keys(managedEngineOptions).forEach(name => {
                if (name === 'onManifestLoaded') return;
                const callback = managedEngineOptions[name];
                managedEngineOptions[name] = (...args) => {
                    if (this.engine === this.managedEngine) callback(...args);
                };
            });

            if (config.deviceEngine) {
                this.deviceEngine = config.deviceEngine;
            } else if (config.engine && config.engineType !== 'managed') {
                this.deviceEngine = config.engine;
            } else {
                const DeviceEngineClass = typeof window !== 'undefined'
                    ? (window.BrowserTtsEngine ? window.BrowserTtsEngine.BrowserTtsEngine || window.BrowserTtsEngine : (window.KiemLai && window.KiemLai.BrowserTtsEngine ? window.KiemLai.BrowserTtsEngine.BrowserTtsEngine || window.KiemLai.BrowserTtsEngine : null))
                    : null;
                this.deviceEngine = DeviceEngineClass ? new DeviceEngineClass(deviceEngineOptions) : null;
            }

            if (config.managedEngine) {
                this.managedEngine = config.managedEngine;
            } else if (config.engine && config.engineType === 'managed') {
                this.managedEngine = config.engine;
            } else {
                const ManagedEngineClass = typeof window !== 'undefined'
                    ? (window.ManagedAudioEngine ? window.ManagedAudioEngine.ManagedAudioEngine || window.ManagedAudioEngine : (window.KiemLai && window.KiemLai.ManagedAudioEngine ? window.KiemLai.ManagedAudioEngine.ManagedAudioEngine || window.KiemLai.ManagedAudioEngine : null))
                    : null;
                this.managedEngine = ManagedEngineClass ? new ManagedEngineClass(managedEngineOptions) : null;
            }

            const ChapterEngineClass = typeof window !== 'undefined' && window.ChapterAudioEngine
                ? window.ChapterAudioEngine.ChapterAudioEngine : null;
            this.chapterEngine = config.chapterEngine || (ChapterEngineClass ? new ChapterEngineClass({
                onStateChange: (state, previous) => {
                    if (this.engine === this.chapterEngine) this._onEngineStateChange(state, previous, 'managed');
                },
                onCueChange: (index, cue) => this._onChapterCueChange(index, cue),
                onChapterEnd: () => {
                    if (this.engine === this.chapterEngine) this._onEngineChapterEnd('managed');
                },
                onError: () => this._offerLegacyChapterFallback()
            }) : null);

            this.activeEngineType = 'device';
            this.engine = this.deviceEngine || this.managedEngine;
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

            const deviceSupported = this.deviceEngine && this.deviceEngine.isSupported();
            const managedSupported = this.managedEngine && this.managedEngine.isSupported();

            if (!deviceSupported && !managedSupported) {
                this._renderUnsupportedState();
                return false;
            }

            // Resolve chapter identity from DOM if not passed in config
            if (!this.chapterId) {
                this.chapterId = (this.dom.body && this.dom.body.getAttribute('data-chapter-id')) ||
                                 (this.dom.player && this.dom.player.getAttribute('data-chapter-id')) ||
                                 null;
            }

            this._initStorageAndPreferences();
            this.isCompleted = false;
            this.isUnloaded = false;
            this.hasMeaningfulResume = false;
            this.isNavigatingToNext = false;
            this._hasUserExplicitlySelectedVoice = false;
            this._managedCatalogResolved = false;
            this._parseAndLoadChunks();

            // Populate voice dropdown with available device voices immediately without premature activation
            const deviceVoices = (this.deviceEngine && this.deviceEngine.getSortedVoices)
                ? this.deviceEngine.getSortedVoices()
                : (this.deviceEngine ? this.deviceEngine.getVoices() : []);
            this._populateVoiceDropdown(deviceVoices, [], { skipActivation: true });

            // Asynchronously fetch initial managed narration manifest
            this._loadInitialManagedVoices();

            this._bindEventListeners();

            const hasAutoplayIntent = this._checkAndConsumeAutoplayIntent();
            if (hasAutoplayIntent) {
                this._attemptAutoplayContinuation();
            } else {
                this._restoreResumePosition();
            }

            if (this.dom.followToggle) {
                this.dom.followToggle.checked = this.followMode;
                this.dom.followToggle.setAttribute('aria-checked', String(this.followMode));
            }

            if (this.dom.autoNextToggle) {
                this.dom.autoNextToggle.checked = this.autoNext;
                this.dom.autoNextToggle.setAttribute('aria-checked', String(this.autoNext));
            }

            if (this.dom.fallbackToggle) {
                this.dom.fallbackToggle.checked = this.fallbackToDevice;
                this.dom.fallbackToggle.setAttribute('aria-checked', String(this.fallbackToDevice));
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
            this.dom.autoNextToggle = document.querySelector(sel.autoNextToggle);
            this.dom.fallbackToggle = document.querySelector(sel.fallbackToggle);

            if (!this.dom.player) {
                return;
            }

            this.dom.playPauseBtn = this.dom.player.querySelector(sel.playPauseBtn);
            this.dom.playIcon = this.dom.player.querySelector(sel.playIcon);
            this.dom.pauseIcon = this.dom.player.querySelector(sel.pauseIcon);
            this.dom.prevBtn = this.dom.player.querySelector(sel.prevBtn);
            this.dom.nextBtn = this.dom.player.querySelector(sel.nextBtn);
            this.dom.rewindBtn = this.dom.player.querySelector(sel.rewindBtn);
            this.dom.forwardBtn = this.dom.player.querySelector(sel.forwardBtn);
            this.dom.progressBar = this.dom.player.querySelector(sel.progressBar);
            this.dom.progressFill = this.dom.player.querySelector(sel.progressFill);
            this.dom.voiceSelect = this.dom.player.querySelector(sel.voiceSelect);
            this.dom.rateSelect = this.dom.player.querySelector(sel.rateSelect);
            this.dom.progressCurrent = this.dom.player.querySelector(sel.progressCurrent);
            this.dom.progressTotal = this.dom.player.querySelector(sel.progressTotal);
            this.dom.statusText = this.dom.player.querySelector(sel.statusText);
            this.dom.collapseToggle = this.dom.player.querySelector(sel.collapseToggle);
            this.dom.settingsTrigger = this.dom.player.querySelector(sel.settingsTrigger);
            this.dom.settingsPanel = this.dom.player.querySelector(sel.settingsPanel) || document.querySelector(sel.settingsPanel);

            if (this.dom.settingsPanel) {
                this.dom.settingsCloseBtn = this.dom.settingsPanel.querySelector(sel.settingsCloseBtn);
                if (!this.dom.voiceSelect) {
                    this.dom.voiceSelect = this.dom.settingsPanel.querySelector(sel.voiceSelect);
                }
                if (!this.dom.rateSelect) {
                    this.dom.rateSelect = this.dom.settingsPanel.querySelector(sel.rateSelect);
                }
                if (!this.dom.followToggle) {
                    this.dom.followToggle = this.dom.settingsPanel.querySelector(sel.followToggle);
                }
                if (!this.dom.autoNextToggle) {
                    this.dom.autoNextToggle = this.dom.settingsPanel.querySelector(sel.autoNextToggle);
                }
                if (!this.dom.fallbackToggle) {
                    this.dom.fallbackToggle = this.dom.settingsPanel.querySelector(sel.fallbackToggle);
                }
            }
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
            if (this.dom.rewindBtn) {
                this.dom.rewindBtn.disabled = true;
            }
            if (this.dom.forwardBtn) {
                this.dom.forwardBtn.disabled = true;
            }
            if (this.dom.collapseToggle) {
                this.dom.collapseToggle.disabled = true;
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
            if (this.dom.autoNextToggle) {
                this.dom.autoNextToggle.disabled = true;
            }
            if (this.dom.fallbackToggle) {
                this.dom.fallbackToggle.disabled = true;
            }
            if (this.dom.settingsTrigger) {
                this.dom.settingsTrigger.disabled = true;
            }
            this._setStatusMessage('Trình duyệt không hỗ trợ Web Speech API.');
        }

        /**
         * Checks if the narration dock is currently collapsed.
         * @returns {boolean}
         */
        isCollapsed() {
            return Boolean(this.dom.player && this.dom.player.classList.contains('is-collapsed'));
        }

        /**
         * Collapses the narration dock into a compact floating headphone button.
         * Does not interrupt or pause active playback.
         */
        collapseDock() {
            if (this.isSettingsOpen()) {
                this.closeSettings();
            }
            if (this.dom.player) {
                this.dom.player.classList.add('is-collapsed');
            }
            if (this.dom.collapseToggle) {
                this.dom.collapseToggle.setAttribute('aria-expanded', 'false');
                this.dom.collapseToggle.setAttribute('aria-label', 'Mở rộng thanh giọng đọc');
                this.dom.collapseToggle.title = 'Mở rộng thanh giọng đọc';
            }
        }

        /**
         * Expands the narration dock to show full controls.
         */
        expandDock() {
            if (this.dom.player) {
                this.dom.player.classList.remove('is-collapsed');
            }
            if (this.dom.collapseToggle) {
                this.dom.collapseToggle.setAttribute('aria-expanded', 'true');
                this.dom.collapseToggle.setAttribute('aria-label', 'Thu gọn thanh giọng đọc');
                this.dom.collapseToggle.title = 'Thu gọn thanh giọng đọc';
            }
        }

        /**
         * Toggles between collapsed and expanded narration dock states.
         */
        toggleDock() {
            if (this.isCollapsed()) {
                this.expandDock();
            } else {
                this.collapseDock();
            }
        }

        /**
         * Handles collapse/expand toggle button click.
         * @param {MouseEvent} [event]
         * @private
         */
        _handleCollapseToggleClick(event) {
            if (event && typeof event.stopPropagation === 'function') {
                event.stopPropagation();
            }
            this.toggleDock();
        }

        /**
         * Checks if the narration settings popover is currently open.
         * @returns {boolean}
         */
        isSettingsOpen() {
            return Boolean(this.dom.settingsPanel && !this.dom.settingsPanel.hidden);
        }

        /**
         * Opens the narration settings popover panel.
         */
        openSettings() {
            if (this.dom.settingsPanel) {
                this.dom.settingsPanel.hidden = false;
                this.dom.settingsPanel.removeAttribute('hidden');
            }
            if (this.dom.settingsTrigger) {
                this.dom.settingsTrigger.setAttribute('aria-expanded', 'true');
                this.dom.settingsTrigger.classList.add('is-active');
            }
        }

        /**
         * Closes the narration settings popover panel.
         */
        closeSettings() {
            if (this.dom.settingsPanel) {
                this.dom.settingsPanel.hidden = true;
                this.dom.settingsPanel.setAttribute('hidden', '');
            }
            if (this.dom.settingsTrigger) {
                this.dom.settingsTrigger.setAttribute('aria-expanded', 'false');
                this.dom.settingsTrigger.classList.remove('is-active');
            }

            // On settings close, synchronize current highlighted block and scroll into comfortable view if needed
            if (this.chapterEngine && this.engine === this.chapterEngine) {
                this._syncChapterHighlight(true);
                return;
            }
            if (this.followMode && this.engine && this.chunks.length > 0) {
                const state = this.engine.getState();
                if (state === 'PLAYING' || state === 'PAUSED') {
                    const curIndex = this.engine.getCurrentChunkIndex();
                    const curChunk = this.chunks[curIndex];
                    if (curChunk && curChunk.element) {
                        this._highlightChunk(curChunk);
                        if (!this._isElementComfortablyVisible(curChunk.element)) {
                            this._scrollElementIntoView(curChunk.element);
                        }
                    }
                }
            }
        }

        /**
         * Handles settings trigger button click. Toggles popover without interrupting playback.
         * @param {MouseEvent} [event]
         * @private
         */
        _handleSettingsTriggerClick(event) {
            if (event && typeof event.stopPropagation === 'function') {
                event.stopPropagation();
            }
            if (this.isSettingsOpen()) {
                this.closeSettings();
            } else {
                this.openSettings();
            }
        }

        /**
         * Handles settings panel close button click.
         * @param {MouseEvent} [event]
         * @private
         */
        _handleSettingsCloseClick(event) {
            if (event && typeof event.stopPropagation === 'function') {
                event.stopPropagation();
            }
            this.closeSettings();
        }

        /**
         * Handles outside document click to dismiss settings popover.
         * @param {MouseEvent} event
         * @private
         */
        _handleDocumentClick(event) {
            const target = event.target;

            if (this.isSettingsOpen()) {
                if ((!this.dom.settingsPanel || !this.dom.settingsPanel.contains(target)) &&
                    (!this.dom.settingsTrigger || !this.dom.settingsTrigger.contains(target))) {
                    this.closeSettings();
                }
            }
        }

        /**
         * Handles document keydown for Escape key dismissal.
         * @param {KeyboardEvent} event
         * @private
         */
        _handleDocumentKeydown(event) {
            if (event.key === 'Escape') {
                if (this.isSettingsOpen()) {
                    this.closeSettings();
                    if (this.dom.settingsTrigger && typeof this.dom.settingsTrigger.focus === 'function') {
                        this.dom.settingsTrigger.focus();
                    }
                }
            }
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
         * Initializes and restores global narration preferences from localStorage.
         * @private
         */
        _initStorageAndPreferences() {
            const prefs = this._loadPreferences();
            if (prefs) {
                if (typeof prefs.rate === 'number' && Number.isFinite(prefs.rate)) {
                    const clampedRate = Math.max(0.5, Math.min(2.0, prefs.rate));
                    if (this.engine) {
                        this.engine.setRate(clampedRate);
                    }
                    if (this.dom.rateSelect) {
                        this.dom.rateSelect.value = String(clampedRate);
                    }
                }
                if (typeof prefs.followMode === 'boolean') {
                    this.followMode = prefs.followMode;
                    if (this.dom.followToggle) {
                        this.dom.followToggle.checked = this.followMode;
                        this.dom.followToggle.setAttribute('aria-checked', String(this.followMode));
                    }
                }
                if (typeof prefs.autoNext === 'boolean') {
                    this.autoNext = prefs.autoNext;
                    if (this.dom.autoNextToggle) {
                        this.dom.autoNextToggle.checked = this.autoNext;
                        this.dom.autoNextToggle.setAttribute('aria-checked', String(this.autoNext));
                    }
                }
                if (typeof prefs.fallbackToDevice === 'boolean') {
                    this.fallbackToDevice = prefs.fallbackToDevice;
                    if (this.dom.fallbackToggle) {
                        this.dom.fallbackToggle.checked = this.fallbackToDevice;
                        this.dom.fallbackToggle.setAttribute('aria-checked', String(this.fallbackToDevice));
                    }
                }
                if (prefs.voice && typeof prefs.voice === 'object') {
                    if (prefs.voice.type === 'managed' || (prefs.voice.voiceKey && !prefs.voice.voiceURI)) {
                        this.savedVoicePreference = {
                            type: 'managed',
                            voiceKey: prefs.voice.voiceKey || ''
                        };
                    } else {
                        this.savedVoicePreference = {
                            type: 'device',
                            voiceURI: prefs.voice.voiceURI || '',
                            name: prefs.voice.name || '',
                            lang: prefs.voice.lang || ''
                        };
                    }
                }
            }
        }

        /**
         * Detects autoplay continuation intent from URL query parameters.
         * Consumes/removes the autoplay query parameter via history.replaceState to prevent repeated continuation on reload.
         * @returns {boolean} True if autoplay continuation was requested
         * @private
         */
        _checkAndConsumeAutoplayIntent() {
            if (typeof window === 'undefined' || !window.location) {
                return false;
            }

            try {
                const urlParams = new URLSearchParams(window.location.search);
                const autoplayParam = urlParams.get('autoplay');
                const hasIntent = autoplayParam === 'true' || autoplayParam === '1';

                if (hasIntent && window.history && typeof window.history.replaceState === 'function') {
                    urlParams.delete('autoplay');
                    const newSearch = urlParams.toString();
                    const cleanUrl = window.location.pathname + (newSearch ? '?' + newSearch : '') + window.location.hash;
                    window.history.replaceState(window.history.state, '', cleanUrl);
                }

                return hasIntent;
            } catch (e) {
                return false;
            }
        }

        /**
         * Attempts narration playback continuation from chunk 0 when autoplay intent is present.
         * Restores clean ready state and displays graceful notice if speech synthesis is blocked or unsupported.
         * @private
         */
        _attemptAutoplayContinuation() {
            if (!this.engine || this.chunks.length === 0) {
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }

            this._updateProgressDisplay(0, this.chunks.length);
            this._updateNavButtons();
            this._setStatusMessage('Đang tự động phát giọng đọc...');
            this.isAutoplayContinuation = true;

            try {
                this.engine.play(0);

                // If playback didn't actually start after a short delay (e.g. browser blocked un-interacted speech), restore gracefully
                if (typeof window !== 'undefined') {
                    window.setTimeout(() => {
                        if (this.isAutoplayContinuation) {
                            this.isAutoplayContinuation = false;
                            if (this.engine) {
                                try {
                                    if (typeof this.engine.pause === 'function') {
                                        this.engine.pause();
                                    }
                                    if (typeof this.engine.cancel === 'function') {
                                        this.engine.cancel();
                                    }
                                } catch (ignored) {}
                            }
                            this._updateProgressDisplay(0, this.chunks.length);
                            this._updateNavButtons();
                            this._setStatusMessage('Đã sang chương mới. Nhấn Phát để tiếp tục.');
                            if (this.dom.playPauseBtn) {
                                this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                                this.dom.playPauseBtn.title = 'Phát giọng đọc';
                                this.dom.playPauseBtn.classList.remove('is-playing');
                            }
                        }
                    }, 1200);
                }
            } catch (e) {
                console.warn('[NarrationController] Autoplay continuation was blocked or failed:', e);
                this.isAutoplayContinuation = false;
                if (this.engine) {
                    try {
                        if (typeof this.engine.pause === 'function') {
                            this.engine.pause();
                        }
                        if (typeof this.engine.cancel === 'function') {
                            this.engine.cancel();
                        }
                    } catch (ignored) {}
                }
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                this._setStatusMessage('Đã sang chương mới. Nhấn Phát để tiếp tục.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                    this.dom.playPauseBtn.title = 'Phát giọng đọc';
                    this.dom.playPauseBtn.classList.remove('is-playing');
                }
            }
        }

        /**
         * Attempts to restore saved narration resume position for the current chapter.
         * Rejects/clears stale resume data if chapterId or totalChunks do not match.
         * Sets up initial UI state without autoplaying and without forcing page scroll.
         * @private
         */
        _restoreResumePosition() {
            if (!this.chapterId || this.chunks.length === 0) {
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }

            const saved = this._loadResumePosition();
            if (!saved) {
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }

            // Reject resume from a different chapter
            if (saved.chapterId !== this.chapterId) {
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }

            // Stale resume detection: total chunks count changed (e.g. chapter revised)
            if (typeof saved.totalChunks === 'number' && saved.totalChunks !== this.chunks.length) {
                console.info('[NarrationController] Resetting stale resume: total chunks mismatch (saved ' + saved.totalChunks + ' vs current ' + this.chunks.length + ').');
                this._clearSavedResume();
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }

            // Validate chunk index
            const rawIndex = Number(saved.chunkIndex);
            if (!Number.isFinite(rawIndex) || rawIndex <= 0) {
                // Resume is at beginning or invalid
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }

            // Clamp index safely
            const restoredIndex = Math.max(0, Math.min(this.chunks.length - 1, Math.floor(rawIndex)));

            if (this.engine) {
                this.engine.seekToChunk(restoredIndex);
            }

            this.hasMeaningfulResume = true;

            const currentNum = restoredIndex + 1;
            const totalNum = this.chunks.length;

            this._updateProgressDisplay(currentNum, totalNum);
            this._updateNavButtons();

            // Clear "continue from sentence X / Y" ready status
            this._setStatusMessage('Sẵn sàng đọc tiếp từ câu ' + currentNum + ' / ' + totalNum + '.');

            if (this.dom.playPauseBtn) {
                const label = 'Tiếp tục đọc (từ câu ' + currentNum + ')';
                this.dom.playPauseBtn.setAttribute('aria-label', label);
                this.dom.playPauseBtn.title = label;
            }

            // Follow Mode must NOT force an initial page scroll merely from restoring resume!
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
            if (this.dom.rewindBtn) {
                this.dom.rewindBtn.addEventListener('click', this._boundOnRewind);
            }
            if (this.dom.forwardBtn) {
                this.dom.forwardBtn.addEventListener('click', this._boundOnForward);
            }
            if (this.dom.progressBar) {
                this.dom.progressBar.addEventListener('click', this._boundOnProgressBarClick);
                this.dom.progressBar.addEventListener('keydown', this._boundOnProgressBarKeydown);
            }
            if (this.dom.collapseToggle) {
                this.dom.collapseToggle.addEventListener('click', this._boundOnCollapseToggle);
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
            if (this.dom.autoNextToggle) {
                this.dom.autoNextToggle.addEventListener('change', this._boundOnAutoNextChange);
            }
            if (this.dom.fallbackToggle) {
                this.dom.fallbackToggle.addEventListener('change', this._boundOnFallbackChange);
            }
            if (this.dom.settingsTrigger) {
                this.dom.settingsTrigger.addEventListener('click', this._boundOnSettingsTriggerClick);
            }
            if (this.dom.settingsCloseBtn) {
                this.dom.settingsCloseBtn.addEventListener('click', this._boundOnSettingsCloseClick);
            }

            document.addEventListener('click', this._boundOnDocumentClick);
            document.addEventListener('keydown', this._boundOnDocumentKeydown);

            window.addEventListener('popstate', this._boundOnPopState);
            window.addEventListener('beforeunload', this._boundOnUnload);
            window.addEventListener('pagehide', this._boundOnUnload);
            window.addEventListener('storage', this._boundOnStorage);
        }

        /**
         * Handles browser popstate (back/forward) by reloading historical page state cleanly.
         * @param {PopStateEvent} event
         * @private
         */
        _handlePopState(event) {
            if (typeof window !== 'undefined' && window.location) {
                window.location.reload();
            }
        }

        /**
         * Resolves the best matching Vietnamese voice from available voices based on saved preference and fallbacks.
         * Only returns Vietnamese voices; never falls back to foreign voices.
         * @param {Array<SpeechSynthesisVoice>} voices
         * @returns {SpeechSynthesisVoice|null}
         * @private
         */
        _resolveBestMatchingVoice(voices) {
            if (!Array.isArray(voices) || voices.length === 0) {
                return null;
            }

            const viVoices = voices.filter(isVietnameseVoice);
            if (viVoices.length === 0) {
                return null;
            }

            if (this.savedVoicePreference) {
                const savedURI = this.savedVoicePreference.voiceURI;
                const savedName = this.savedVoicePreference.name;

                // 1. Exact match by voiceURI or name within Vietnamese voices
                const exactMatch = viVoices.find(v =>
                    (savedURI && v.voiceURI === savedURI) || (savedName && v.name === savedName)
                );
                if (exactMatch) {
                    return exactMatch;
                }
            }

            // 2. Active voice in engine if it is a Vietnamese voice
            if (this.engine && this.engine.selectedVoice && isVietnameseVoice(this.engine.selectedVoice)) {
                const cur = this.engine.selectedVoice;
                const curMatch = viVoices.find(v => v.voiceURI === cur.voiceURI || v.name === cur.name);
                if (curMatch) {
                    return curMatch;
                }
            }

            // 3. System default Vietnamese voice or first available Vietnamese voice
            return viVoices.find(v => v.default) || viVoices[0] || null;
        }

        /**
         * Loads initial managed narration manifest for available voices discovery and auto-selection.
         * Always uses default manifest endpoint (without voiceKey) for initial catalog discovery.
         * Re-checks user intent and playback state after every await to avoid overriding user interaction.
         * @private
         */
        async _loadInitialManagedVoices() {
            if (!this.managedEngine || !this.chapterId) {
                return;
            }

            const catalogChapterId = this.chapterId;
            const catalogSelection = this._voiceSelectionSequenceId;
            const catalogIsCurrent = () => !this.isUnloaded && this.chapterId === catalogChapterId &&
                this._voiceSelectionSequenceId === catalogSelection;
            try {
                // 1. Always discover catalog from default manifest (without voiceKey)
                const defaultManifest = await this.managedEngine.loadManifest(this.chapterId);
                if (!catalogIsCurrent()) return;
                this._managedCatalogResolved = true;
                const availableVoices = (defaultManifest && Array.isArray(defaultManifest.availableVoices))
                    ? defaultManifest.availableVoices
                    : [];

                const deviceVoices = (this.deviceEngine && this.deviceEngine.getSortedVoices)
                    ? this.deviceEngine.getSortedVoices()
                    : (this.deviceEngine ? this.deviceEngine.getVoices() : []);

                const isInterrupted = () => {
                    const engineState = this.engine ? this.engine.getState() : null;
                    const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';
                    const userExplicitlySelected = Boolean(this._hasUserExplicitlySelectedVoice);
                    return isPlaybackActive || userExplicitlySelected;
                };

                // 2. Check if user already started playback or explicitly changed voice while default manifest was loading
                if (isInterrupted()) {
                    // Populate options in dropdown without stopping or switching active engine
                    this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });
                    return;
                }

                // 3. Restore and validate saved managed preference only after catalog discovery
                if (this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey) {
                    const targetKey = this.savedVoicePreference.voiceKey;
                    const voiceExists = availableVoices.some(v => v.voiceKey === targetKey);

                    if (voiceExists) {
                        const defaultSelectedKey = defaultManifest.selectedVoice ? defaultManifest.selectedVoice.voiceKey : null;
                        if (targetKey !== defaultSelectedKey) {
                            try {
                                await this.managedEngine.loadManifest(this.chapterId, targetKey);
                                if (!catalogIsCurrent()) return;
                            } catch (keyedErr) {
                                console.warn('[NarrationController] Failed to load manifest for saved voiceKey:', targetKey, keyedErr);
                            }
                        }
                    } else {
                        // Stale saved managed voice: catalog has arrived and confirms absence
                        this.savedVoicePreference = null;
                        this._savePreferences();
                    }
                }

                if (!catalogIsCurrent()) return;
                // 4. Re-check user intent after the keyed manifest await before any final automatic activation
                if (isInterrupted()) {
                    this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });
                    return;
                }

                // 5. Populate dropdown and activate active/default voice
                this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: false });
            } catch (e) {
                if (!catalogIsCurrent()) return;
                console.warn('[NarrationController] Initial managed manifest load skipped or failed:', e);
                this._managedCatalogResolved = true;
                const deviceVoices = (this.deviceEngine && this.deviceEngine.getSortedVoices)
                    ? this.deviceEngine.getSortedVoices()
                    : (this.deviceEngine ? this.deviceEngine.getVoices() : []);
                if (!this._hasUserExplicitlySelectedVoice && (!this.engine || (this.engine.getState() !== 'PLAYING' && this.engine.getState() !== 'PAUSED'))) {
                    this._populateVoiceDropdown(deviceVoices, [], { skipActivation: false });
                }
            }
        }

        /**
         * Engine Callback: Manifest loaded by ManagedAudioEngine.
         * Only synchronizes chunks if the loaded manifest matches the current active managed voice selection.
         * @param {Object} manifest
         * @private
         */
        _onManifestLoaded(manifest) {
            if (this.engine === this.managedEngine && this.activeEngineType === 'managed' && manifest && Array.isArray(manifest.segments)) {
                const currentSelectedKey = this.savedVoicePreference && this.savedVoicePreference.type === 'managed'
                    ? this.savedVoicePreference.voiceKey
                    : null;
                if (!currentSelectedKey || !manifest.selectedVoice || manifest.selectedVoice.voiceKey === currentSelectedKey) {
                    this.chunks = manifest.segments;
                }
            }
        }

        /**
         * Populates the voice selector dropdown with categorized optgroups:
         * 1. "Giọng Kiếm Lai" (Managed voices)
         * 2. "Thiết bị" (Device voices)
         * If no voices exist in either category, shows clean unavailable state and disables playback.
         * @param {Array<SpeechSynthesisVoice>} [deviceVoices]
         * @param {Array<Object>} [managedVoices]
         * @param {Object} [options]
         * @param {boolean} [options.skipActivation=false]
         * @private
         */
        _populateVoiceDropdown(deviceVoices, managedVoices, options = {}) {
            if (!this.dom.voiceSelect) {
                return;
            }

            const skipActivation = Boolean(options && options.skipActivation);
            const currentSelectedValue = this.dom.voiceSelect.value;

            const viVoices = (Array.isArray(deviceVoices)
                ? deviceVoices
                : (this.deviceEngine && this.deviceEngine.getSortedVoices ? this.deviceEngine.getSortedVoices() : (this.deviceEngine ? this.deviceEngine.getVoices() : []))
            ).filter(isVietnameseVoice);

            const mVoices = (Array.isArray(managedVoices) && managedVoices.length > 0)
                ? managedVoices
                : (this._cachedManagedVoices && this._cachedManagedVoices.length > 0
                    ? this._cachedManagedVoices
                    : (this.managedEngine ? this.managedEngine.getVoices() : []));

            if (Array.isArray(managedVoices) && managedVoices.length > 0) {
                this._cachedManagedVoices = managedVoices;
            }

            if (viVoices.length === 0 && mVoices.length === 0) {
                this.dom.voiceSelect.innerHTML = '<option value="">Chưa có giọng đọc Tiếng Việt</option>';
                this.dom.voiceSelect.disabled = true;
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.disabled = true;
                    this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                }
                this._setStatusMessage('Thiết bị chưa có giọng đọc Tiếng Việt.');
                return;
            }

            const hadNoVoices = this.dom.voiceSelect.disabled || (this.dom.statusText && this.dom.statusText.textContent === 'Thiết bị chưa có giọng đọc Tiếng Việt.');

            this.dom.voiceSelect.disabled = false;
            if (this.dom.playPauseBtn && this.chunks.length > 0) {
                this.dom.playPauseBtn.disabled = false;
                this.dom.playPauseBtn.removeAttribute('aria-disabled');
            }

            this.dom.voiceSelect.innerHTML = '';

            // Group 1: Giọng Kiếm Lai (Managed Voices)
            if (mVoices.length > 0) {
                const managedGroup = document.createElement('optgroup');
                managedGroup.label = 'Giọng Kiếm Lai';
                for (let i = 0; i < mVoices.length; i++) {
                    const mv = mVoices[i];
                    const option = document.createElement('option');
                    option.value = 'managed:' + mv.voiceKey;
                    option.textContent = mv.displayName + (mv.defaultVoice ? ' (Mặc định)' : '');
                    managedGroup.appendChild(option);
                }
                this.dom.voiceSelect.appendChild(managedGroup);
            }

            // Group 2: Thiết bị (Device Voices)
            if (viVoices.length > 0) {
                const deviceGroup = document.createElement('optgroup');
                deviceGroup.label = 'Thiết bị';
                for (let i = 0; i < viVoices.length; i++) {
                    const dv = viVoices[i];
                    const option = document.createElement('option');
                    option.value = 'device:' + (dv.voiceURI || dv.name);
                    option.textContent = dv.name + (dv.default ? ' (Mặc định)' : '');
                    deviceGroup.appendChild(option);
                }
                this.dom.voiceSelect.appendChild(deviceGroup);
            }

            if (skipActivation) {
                // Preserve current selection if it still exists in the dropdown
                let preserved = false;
                const targetVal = currentSelectedValue || (this.activeEngineType === 'managed' && this.managedEngine && this.managedEngine.getSelectedVoiceKey ? ('managed:' + this.managedEngine.getSelectedVoiceKey()) : null);
                if (targetVal) {
                    for (let i = 0; i < this.dom.voiceSelect.options.length; i++) {
                        if (this.dom.voiceSelect.options[i].value === targetVal) {
                            this.dom.voiceSelect.selectedIndex = i;
                            preserved = true;
                            break;
                        }
                    }
                }
                this._updateNavButtons();
                return;
            }

            // Select active voice using preference & fallback resolution
            this._selectActiveVoiceInDropdown(viVoices, mVoices);

            this._updateNavButtons();

            // When voices arrive after initial empty state, restore ready/resume status if stale
            if (hadNoVoices) {
                const engineState = this.engine ? this.engine.getState() : null;
                if (engineState !== 'PLAYING' && engineState !== 'PAUSED') {
                    if (this.isCompleted) {
                        this._setStatusMessage('Đã đọc xong chương.');
                    } else if (this.engine && this.chunks.length > 0) {
                        const curIndex = this.engine.getCurrentChunkIndex();
                        if (curIndex > 0 && this.hasMeaningfulResume) {
                            const currentNum = curIndex + 1;
                            const totalNum = this.chunks.length;
                            this._setStatusMessage('Sẵn sàng đọc tiếp từ câu ' + currentNum + ' / ' + totalNum + '.');
                            if (this.dom.playPauseBtn) {
                                const label = 'Tiếp tục đọc (từ câu ' + currentNum + ')';
                                this.dom.playPauseBtn.setAttribute('aria-label', label);
                                this.dom.playPauseBtn.title = label;
                            }
                        } else {
                            this._setStatusMessage('Sẵn sàng phát giọng đọc (' + this.chunks.length + ' câu).');
                            if (this.dom.playPauseBtn) {
                                const label = 'Phát giọng đọc';
                                this.dom.playPauseBtn.setAttribute('aria-label', label);
                                this.dom.playPauseBtn.title = label;
                            }
                        }
                    }
                }
            }
        }

        /**
         * Resolves and selects the active option in voice dropdown based on saved preferences or fallbacks.
         * Does not invalidate pending managed preference before catalog resolution is complete.
         * @param {Array<SpeechSynthesisVoice>} viVoices
         * @param {Array<Object>} mVoices
         * @private
         */
        _selectActiveVoiceInDropdown(viVoices, mVoices) {
            // 1. If saved preference exists
            if (this.savedVoicePreference) {
                if (this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey) {
                    const targetKey = this.savedVoicePreference.voiceKey;
                    const found = mVoices.find(v => v.voiceKey === targetKey);
                    if (found) {
                        this.dom.voiceSelect.value = 'managed:' + found.voiceKey;
                        this._activateEngine('managed', found.voiceKey);
                        return;
                    } else if (this._managedCatalogResolved) {
                        // Stale saved managed voice -> ONLY clear preference after catalog resolution confirms absence!
                        this.savedVoicePreference = null;
                        this._savePreferences();
                    }
                } else if (this.savedVoicePreference.type === 'device') {
                    const matchedDevice = this._resolveBestMatchingVoice(viVoices);
                    if (matchedDevice) {
                        this.dom.voiceSelect.value = 'device:' + (matchedDevice.voiceURI || matchedDevice.name);
                        this._activateEngine('device', matchedDevice);
                        return;
                    }
                }
            }

            // 2. Default resolution: prefer default managed voice if available, otherwise device voice
            if (mVoices.length > 0) {
                const defManaged = mVoices.find(v => v.defaultVoice) || mVoices[0];
                this.dom.voiceSelect.value = 'managed:' + defManaged.voiceKey;
                this._activateEngine('managed', defManaged.voiceKey);
            } else if (viVoices.length > 0) {
                const matchedDevice = this._resolveBestMatchingVoice(viVoices);
                if (matchedDevice) {
                    this.dom.voiceSelect.value = 'device:' + (matchedDevice.voiceURI || matchedDevice.name);
                    this._activateEngine('device', matchedDevice);
                }
            }
        }

        /**
         * Activates either 'managed' or 'device' engine cleanly, stopping the previous engine.
         * @param {string} type - 'managed' | 'device'
         * @param {any} [identifier]
         * @private
         */
        _activateEngine(type, identifier) {
            this._invalidateChapterPlayback();
            if (type === 'managed' && this.managedEngine) {
                if (this.deviceEngine) {
                    this.deviceEngine.stop();
                }
                if (this.managedEngine) {
                    this.managedEngine.stop();
                }
                this._clearHighlight();

                this.activeEngineType = 'managed';
                this.activeEngine = this.managedEngine;
                this.engine = this.managedEngine;

                if (typeof identifier === 'string') {
                    const requestedChapterId = this.chapterId;
                    this._selectManagedPlayback(requestedChapterId, identifier).then(manifest => {
                        if (this.activeEngineType !== 'managed') {
                            return;
                        }
                        if (this.chapterId !== requestedChapterId) {
                            return;
                        }
                        const currentSelectedKey = this.savedVoicePreference && this.savedVoicePreference.type === 'managed'
                            ? this.savedVoicePreference.voiceKey
                            : null;
                        if (currentSelectedKey && currentSelectedKey !== identifier) {
                            return;
                        }
                        if (this.dom.voiceSelect && this.dom.voiceSelect.value !== ('managed:' + identifier)) {
                            return;
                        }
                        this.chunks = (manifest && Array.isArray(manifest.segments))
                            ? manifest.segments
                            : (this.managedEngine.getSegments ? this.managedEngine.getSegments() : []);
                        this._syncNavigationAndProgress();
                    }).catch(err => {
                        if (err.name === 'AbortError' || this.chapterId !== requestedChapterId) return;
                        this._setStatusMessage('Không thể tải giọng đã chọn. Vui lòng chọn lại giọng để thử lại.');
                        console.warn('[NarrationController] Error activating managed voice manifest:', err);
                    });
                } else {
                    this.chunks = this.managedEngine.getSegments();
                    this._syncNavigationAndProgress();
                }
            } else if (this.deviceEngine) {
                if (this.managedEngine) {
                    this.managedEngine.stop();
                }
                if (this.managedEngine && typeof this.managedEngine.cancel === 'function') {
                    this.managedEngine.cancel();
                }
                if (this.deviceEngine) {
                    this.deviceEngine.stop();
                }
                this._clearHighlight();

                this.activeEngineType = 'device';
                this.activeEngine = this.deviceEngine;
                this.engine = this.deviceEngine;

                if (identifier) {
                    this.deviceEngine.setVoice(identifier);
                }
                this._parseAndLoadChunks();
            }
        }

        _invalidateChapterPlayback() {
            ++this._chapterSelectionId;
            if (this.chapterEngine) this.chapterEngine.stop();
            this._clearHighlight();
        }

        async _selectManagedPlayback(chapterId, voiceKey, legacyOnly = false) {
            this._invalidateChapterPlayback();
            const selection = this._chapterSelectionId;
            const voiceSequence = this._voiceSelectionSequenceId;
            const isCurrent = () => !this.isUnloaded && this.chapterId === chapterId &&
                this.activeEngineType === 'managed' && selection === this._chapterSelectionId &&
                voiceSequence === this._voiceSelectionSequenceId;
            const assertCurrent = () => {
                if (!isCurrent()) throw new DOMException('Playback selection changed', 'AbortError');
            };
            this._cancelPendingAutoNext();
            this.isCompleted = false;
            this._clearHighlight();
            this.managedEngine.stop();
            this.managedEngine.cancel();
            if (this.deviceEngine) this.deviceEngine.stop();
            this.chunks = [];
            this._updateProgressDisplay(0, 0);
            this._updateNavButtons();
            if (this.dom.playPauseBtn) this.dom.playPauseBtn.disabled = true;

            if (!legacyOnly && this.chapterEngine && this.chapterEngine.isSupported()) {
                this.engine = this.activeEngine = this.chapterEngine;
                const metadata = await this.chapterEngine.loadPlayback(chapterId, voiceKey);
                assertCurrent();
                if (metadata) {
                    this.chapterEngine.setRate(this.dom.rateSelect ? this.dom.rateSelect.value : this.managedEngine.rate);
                    this.chunks = this.chapterEngine.getSegments();
                    this.chapterEngine.seekBySeconds(0);
                    this._updateProgressDisplay(0, this.chunks.length);
                    this._updateNavButtons();
                    if (this.dom.playPauseBtn) {
                        this.dom.playPauseBtn.disabled = false;
                        this.dom.playPauseBtn.removeAttribute('aria-disabled');
                    }
                    this._setStatusMessage('Sẵn sàng phát âm thanh cả chương.');
                    return { segments: this.chunks, availableVoices: this.managedEngine.getVoices() };
                }
                this.chapterEngine.stop();
            }

            assertCurrent();
            this.engine = this.activeEngine = this.managedEngine;
            let manifest;
            try {
                manifest = await this.managedEngine.loadManifest(chapterId, voiceKey);
            } catch (error) {
                assertCurrent();
                throw error;
            }
            assertCurrent();
            if (!manifest || !manifest.selectedVoice || manifest.selectedVoice.voiceKey !== voiceKey) {
                this.managedEngine.stop();
                throw new Error('Selected voice unavailable');
            }
            this.chunks = manifest.segments || [];
            this._updateProgressDisplay(0, this.chunks.length);
            this._updateNavButtons();
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.disabled = this.chunks.length === 0;
                this.dom.playPauseBtn.setAttribute('aria-disabled', String(this.chunks.length === 0));
            }
            return manifest;
        }

        _onChapterCueChange(index, cue) {
            if (this.engine !== this.chapterEngine || this.isUnloaded) return;
            if (!cue) {
                // A persisted gap has no active segment, including when seeking while paused.
                this._clearHighlight();
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }
            this._onEngineChunkStart(index, cue, 'managed');
            if (this.chapterEngine.getState() === 'PAUSED') this._onEngineStateChange('PAUSED', 'PAUSED', 'managed');
        }

        _resolveChapterCueElements(segmentId) {
            if (!segmentId || !this.dom.body) return [];
            return Array.from(this.dom.body.querySelectorAll('[data-narration-segment-ids]')).filter(element =>
                (element.getAttribute('data-narration-segment-ids') || '').split(/\s+/).includes(segmentId));
        }

        _syncChapterHighlight(restoreVisibility = false) {
            if (!this.chapterEngine || this.engine !== this.chapterEngine) return;
            const state = this.chapterEngine.getState();
            const cue = this.chapterEngine.getCurrentChunk();
            if (!this.followMode || this.isCompleted || this.isUnloaded || !cue ||
                (state !== 'PLAYING' && state !== 'PAUSED')) {
                this._clearHighlight();
                return;
            }
            const elements = this._resolveChapterCueElements(cue.segmentId);
            const next = new Set(elements);
            const changed = next.size !== this.activeChapterHighlightedElements.size ||
                elements.some(element => !this.activeChapterHighlightedElements.has(element));
            this.activeNarrationSegmentId = cue.segmentId;
            for (const element of this.activeChapterHighlightedElements) {
                if (!next.has(element)) element.classList.remove(HIGHLIGHT_CLASS);
            }
            for (const element of next) {
                if (!this.activeChapterHighlightedElements.has(element)) element.classList.add(HIGHLIGHT_CLASS);
            }
            this.activeChapterHighlightedElements = next;
            const scrollTarget = elements[0];
            if (scrollTarget && (changed || restoreVisibility) && !this.isSettingsOpen() &&
                !this._isElementComfortablyVisible(scrollTarget)) {
                this._scrollElementIntoView(scrollTarget);
            }
        }

        async _offerLegacyChapterFallback() {
            if (this.engine !== this.chapterEngine || this.isUnloaded) return;
            const chapterId = this.chapterId;
            const voiceKey = this.chapterEngine.getSelectedVoiceKey();
            try {
                await this._selectManagedPlayback(chapterId, voiceKey, true);
                this._setStatusMessage('Không thể tải âm thanh cả chương. Nhấn Phát để nghe từng đoạn với giọng đã chọn.');
            } catch (error) {
                if (error.name !== 'AbortError') {
                    this._setStatusMessage('Không thể tải giọng đã chọn. Vui lòng chọn lại giọng để thử lại.');
                }
            }
        }

        /**
         * Sets follow mode ON or OFF.
         * When turned OFF: immediately clears any active highlight without pausing TTS.
         * When turned ON during playback: immediately highlights and scrolls to current chunk.
         * @param {boolean} enabled
         * @param {boolean} [persist=true]
         */
        setFollowMode(enabled, persist = true) {
            this.followMode = Boolean(enabled);

            if (this.dom.followToggle) {
                this.dom.followToggle.checked = this.followMode;
                this.dom.followToggle.setAttribute('aria-checked', String(this.followMode));
            }

            if (persist) {
                this._savePreferences();
            }

            if (this.chapterEngine && this.engine === this.chapterEngine) {
                this._syncChapterHighlight(true);
                return;
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
                            const elementChanged = this._highlightChunk(curChunk);
                            if (!this.isSettingsOpen() && (elementChanged || !this._isElementComfortablyVisible(curChunk.element))) {
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
                this.setFollowMode(this.dom.followToggle.checked, true);
            }
        }

        /**
         * Sets auto-next mode ON or OFF.
         * If turned OFF while next-chapter navigation is pending, cancels the timer and resets navigation state immediately.
         * Turning ON does not schedule navigation on its own (only natural chapter completion schedules navigation).
         * @param {boolean} enabled
         * @param {boolean} [persist=true]
         */
        setAutoNext(enabled, persist = true) {
            this.autoNext = Boolean(enabled);

            if (!this.autoNext) {
                if (this.isNavigatingToNext || this._autoNextTimeoutId) {
                    this._cancelPendingAutoNext();
                    if (this.isCompleted) {
                        this._setStatusMessage('Đã đọc xong chương.');
                    }
                }
            }

            if (this.dom.autoNextToggle) {
                this.dom.autoNextToggle.checked = this.autoNext;
                this.dom.autoNextToggle.setAttribute('aria-checked', String(this.autoNext));
            }

            if (persist) {
                this._savePreferences();
            }
        }

        /**
         * Handles Auto-Next toggle switch change.
         * @private
         */
        _handleAutoNextChange() {
            if (this.dom.autoNextToggle) {
                this.setAutoNext(this.dom.autoNextToggle.checked, true);
            }
        }

        /**
         * Sets device fallback mode ON or OFF.
         * @param {boolean} enabled
         * @param {boolean} [persist=true]
         */
        setFallbackToDevice(enabled, persist = true) {
            this.fallbackToDevice = Boolean(enabled);

            if (this.dom.fallbackToggle) {
                this.dom.fallbackToggle.checked = this.fallbackToDevice;
                this.dom.fallbackToggle.setAttribute('aria-checked', String(this.fallbackToDevice));
            }

            if (persist) {
                this._savePreferences();
            }
        }

        /**
         * Handles Device Fallback toggle switch change.
         * @private
         */
        _handleFallbackChange() {
            if (this.dom.fallbackToggle) {
                this.setFallbackToDevice(this.dom.fallbackToggle.checked, true);
            }
        }

        /**
         * Switches temporarily to Device TTS engine during playback/preparation failure if fallback is enabled.
         * Preserves savedVoicePreference intact.
         * @param {string} [customMessage]
         * @returns {boolean} True if fallback was performed
         * @private
         */
        _fallbackToDeviceTts(customMessage) {
            if (!this.fallbackToDevice || !this.deviceEngine || !this.deviceEngine.isSupported()) {
                return false;
            }

            this._invalidateChapterPlayback();
            if (this.managedEngine) {
                try {
                    this.managedEngine.stop();
                } catch (ignored) {}
                if (typeof this.managedEngine.cancel === 'function') {
                    try {
                        this.managedEngine.cancel();
                    } catch (ignored) {}
                }
            }

            this.activeEngineType = 'device';
            this.activeEngine = this.deviceEngine;
            this.engine = this.deviceEngine;

            // Preserve user's selected Managed preference for later chapters (do NOT overwrite savedVoicePreference)

            if (this.parser && typeof this.parser.parseChapterBody === 'function' && this.dom.body) {
                this.chunks = this.parser.parseChapterBody(this.dom.body);
            } else {
                this.chunks = [];
            }

            if (this.deviceEngine) {
                this.deviceEngine.loadChunks(this.chunks, 0);
            }

            const deviceVoices = (this.deviceEngine && this.deviceEngine.getSortedVoices)
                ? this.deviceEngine.getSortedVoices()
                : (this.deviceEngine ? this.deviceEngine.getVoices() : []);
            const mVoices = (this._cachedManagedVoices && this._cachedManagedVoices.length > 0)
                ? this._cachedManagedVoices
                : (this.managedEngine ? this.managedEngine.getVoices() : []);
            this._populateVoiceDropdown(deviceVoices, mVoices, { skipActivation: true });
            const selectedDeviceVoice = this.deviceEngine.selectedVoice;
            if (selectedDeviceVoice && this.dom.voiceSelect) {
                this.dom.voiceSelect.value = 'device:' + (selectedDeviceVoice.voiceURI || selectedDeviceVoice.name);
            }

            if (this.chunks.length === 0) {
                this._updateProgressDisplay(0, 0);
                this._updateNavButtons();
                this._setStatusMessage('Không tìm thấy nội dung văn bản để đọc.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.disabled = true;
                    this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                }
            } else {
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                this._setStatusMessage(customMessage || 'Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.disabled = false;
                    this.dom.playPauseBtn.removeAttribute('aria-disabled');
                }
                if (this.deviceEngine) {
                    this.deviceEngine.play(0);
                }
            }
            return true;
        }

        /**
         * Resolves the target URL for the next chapter from reader navigation.
         * Returns the clean chapter URL without modifying query parameters.
         * @returns {string|null} Full destination URL or null if no next chapter exists
         * @private
         */
        _resolveNextChapterUrl() {
            if (typeof document === 'undefined') {
                return null;
            }

            const nextEl = document.querySelector(this.selectors.nextChapterLink || '.novel-chapter-nav-btn--next, a[rel="next"]');
            if (!nextEl || nextEl.tagName !== 'A') {
                return null;
            }

            const rawHref = nextEl.getAttribute('href');
            if (!rawHref || rawHref === '#' || rawHref.startsWith('javascript:')) {
                return null;
            }

            try {
                const base = (typeof window !== 'undefined' && window.location) ? window.location.href : 'http://localhost';
                const resolvedUrl = new URL(rawHref, base);
                return resolvedUrl.toString();
            } catch (e) {
                return rawHref;
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
            for (const element of this.activeChapterHighlightedElements) {
                element.classList.remove(HIGHLIGHT_CLASS);
            }
            this.activeChapterHighlightedElements.clear();
            this.activeNarrationSegmentId = null;
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
         * Computes the viewport clearance margins accounting for top navigation and dynamic fixed bottom dock height.
         * @returns {{windowHeight: number, topMargin: number, bottomMargin: number, comfortableHeight: number}}
         * @private
         */
        _getViewportClearance() {
            const windowHeight = (typeof window !== 'undefined' && window.innerHeight) || (typeof document !== 'undefined' && document.documentElement && document.documentElement.clientHeight) || 0;
            const topMargin = 70; // Top navigation bar clearance

            let dockHeight = 0;
            if (this.dom.player && typeof this.dom.player.getBoundingClientRect === 'function') {
                const rect = this.dom.player.getBoundingClientRect();
                dockHeight = rect.height || this.dom.player.offsetHeight || 0;
            }

            // Fallback dock height if not measured yet (standard dock is ~60px)
            if (!dockHeight || dockHeight <= 0) {
                dockHeight = 60;
            }

            const comfortableGap = 24;
            const bottomMargin = Math.round(dockHeight + comfortableGap);
            const comfortableHeight = Math.max(0, windowHeight - topMargin - bottomMargin);

            return {
                windowHeight: windowHeight,
                topMargin: topMargin,
                bottomMargin: bottomMargin,
                comfortableHeight: comfortableHeight
            };
        }

        /**
         * Checks whether an element is comfortably visible within the viewport.
         * Handles both standard elements and tall blocks exceeding viewport height.
         * Dynamically measures fixed bottom dock height for clearance.
         * @param {HTMLElement} element
         * @returns {boolean}
         * @private
         */
        _isElementComfortablyVisible(element) {
            if (!element || typeof element.getBoundingClientRect !== 'function' || typeof window === 'undefined') {
                return true;
            }

            const rect = element.getBoundingClientRect();
            const { windowHeight, topMargin, bottomMargin, comfortableHeight } = this._getViewportClearance();

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
         * Uses dynamic fixed bottom dock height to determine tall-block alignment.
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
                    const { comfortableHeight } = this._getViewportClearance();
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
         * Cancels any scheduled auto-next continuation timer and active transition fetch.
         * Invalidates any in-flight transitions by incrementing the sequence ID.
         * @private
         */
        _cancelPendingAutoNext() {
            if (this._autoNextTimeoutId) {
                clearTimeout(this._autoNextTimeoutId);
                this._autoNextTimeoutId = null;
            }
            if (this._transitionAbortController) {
                try {
                    this._transitionAbortController.abort();
                } catch (ignored) {}
                this._transitionAbortController = null;
            }
            this._transitionSequenceId++;
            this.isNavigatingToNext = false;
        }

        /**
         * Handles Play/Pause button click.
         * If natural completion occurred, restarts from chunk 0.
         * @private
         */
        _handlePlayPause() {
            this._cancelPendingAutoNext();
            this.isAutoplayContinuation = false;

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
            } else if (state === 'PREPARING') {
                if (typeof this.engine.cancel === 'function') {
                    this.engine.cancel();
                }
                this._setStatusMessage('Đã dừng chuẩn bị giọng đọc. Nhấn Phát để thử lại.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                    this.dom.playPauseBtn.title = 'Phát giọng đọc';
                    this.dom.playPauseBtn.classList.remove('is-playing');
                }
            } else {
                if (this.engine === this.chapterEngine) this.engine.play();
                else this.engine.play(this.engine.getCurrentChunkIndex());
            }
        }

        /**
         * Handles Previous Sentence button click and synchronizes UI immediately.
         * @private
         */
        _handlePrev() {
            this._cancelPendingAutoNext();
            this.isAutoplayContinuation = false;

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
            this._cancelPendingAutoNext();
            this.isAutoplayContinuation = false;

            if (this.engine) {
                this.isCompleted = false;
                this.engine.nextChunk();
                this._syncNavigationAndProgress();
            }
        }

        /**
         * Handles Rewind 5 seconds button click (Managed Audio only).
         * @private
         */
        _handleRewind() {
            this._cancelPendingAutoNext();
            this.isAutoplayContinuation = false;
            if (this.activeEngineType === 'managed' && this.engine && typeof this.engine.seekBySeconds === 'function') {
                this.isCompleted = false;
                this.engine.seekBySeconds(-5);
            }
        }

        /**
         * Handles Forward 5 seconds button click (Managed Audio only).
         * @private
         */
        _handleForward() {
            this._cancelPendingAutoNext();
            this.isAutoplayContinuation = false;
            if (this.activeEngineType === 'managed' && this.engine && typeof this.engine.seekBySeconds === 'function') {
                this.isCompleted = false;
                this.engine.seekBySeconds(5);
            }
        }

        /**
         * Handles click/tap on horizontal progress bar to seek directly to a chunk.
         * @param {MouseEvent} event
         * @private
         */
        _handleProgressBarClick(event) {
            if (!this.dom.progressBar || this.chunks.length === 0) {
                return;
            }

            const rect = this.dom.progressBar.getBoundingClientRect();
            if (rect.width <= 0) {
                return;
            }

            const clickX = event.clientX - rect.left;
            const ratio = Math.max(0, Math.min(1, clickX / rect.width));
            const targetIndex = Math.min(this.chunks.length - 1, Math.floor(ratio * this.chunks.length));

            this.seekToChunk(targetIndex);
        }

        /**
         * Handles keyboard interaction on progress bar (Left/Right arrow keys for chunk seeking).
         * @param {KeyboardEvent} event
         * @private
         */
        _handleProgressBarKeydown(event) {
            if (this.chunks.length === 0) {
                return;
            }

            if (event.key === 'ArrowLeft') {
                event.preventDefault();
                this._handlePrev();
            } else if (event.key === 'ArrowRight') {
                event.preventDefault();
                this._handleNext();
            }
        }

        /**
         * Navigates to a specific chunk and synchronizes UI immediately.
         * @param {number} index
         */
        seekToChunk(index) {
            this._cancelPendingAutoNext();
            this.isAutoplayContinuation = false;

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
            if (this.engine === this.chapterEngine && curIndex < 0) {
                this._clearHighlight();
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                return;
            }
            const curChunk = this.chunks[curIndex];
            const currentNum = curIndex + 1;
            const totalNum = this.chunks.length;

            this._updateProgressDisplay(currentNum, totalNum);
            this._updateNavButtons();

            this.hasMeaningfulResume = true;
            this._saveResumePosition(curIndex);

            if (this.chapterEngine && this.engine === this.chapterEngine) this._syncChapterHighlight();
            const state = this.engine.getState();
            if (state !== 'PLAYING') {
                if (curChunk && curChunk.element && this.followMode) {
                    const elementChanged = this._highlightChunk(curChunk);
                    if (!this.isSettingsOpen() && elementChanged && !this._isElementComfortablyVisible(curChunk.element)) {
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
         * Handles Voice selector change and persists preference globally.
         * Stops active playback before switching voice or manifest.
         * Enforces monotonic sequence token to prevent rapid-selection races.
         * @private
         */
        async _handleVoiceChange() {
            this._invalidateChapterPlayback();
            this._hasUserExplicitlySelectedVoice = true;
            const selectionSequence = ++this._voiceSelectionSequenceId;

            if (!this.dom.voiceSelect) {
                return;
            }
            const voiceVal = this.dom.voiceSelect.value;
            if (!voiceVal) {
                return;
            }

            // Always stop any active playback (both device and managed) when changing voice
            if (this.deviceEngine) {
                this.deviceEngine.stop();
            }
            if (this.managedEngine) {
                this.managedEngine.stop();
            }
            if (this.managedEngine && typeof this.managedEngine.cancel === 'function') {
                this.managedEngine.cancel();
            }
            if (this.engine && this.engine !== this.deviceEngine && this.engine !== this.managedEngine) {
                this.engine.stop();
            }
            this._cancelPendingAutoNext();
            this.isCompleted = false;
            this.hasMeaningfulResume = false;
            this.isAutoplayContinuation = false;
            this._clearSavedResume();
            this._clearHighlight();

            if (this.dom.player) {
                this.dom.player.setAttribute('aria-busy', 'false');
            }
            if (this.dom.statusText) {
                this.dom.statusText.setAttribute('aria-busy', 'false');
            }
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                this.dom.playPauseBtn.title = 'Phát giọng đọc';
                this.dom.playPauseBtn.classList.remove('is-playing');
            }
            if (this.dom.playIcon && this.dom.pauseIcon) {
                this.dom.playIcon.style.display = '';
                this.dom.pauseIcon.style.display = 'none';
            }

            if (voiceVal.startsWith('managed:')) {
                const voiceKey = voiceVal.substring('managed:'.length);

                this.activeEngineType = 'managed';
                this.activeEngine = this.managedEngine;
                this.engine = this.managedEngine;

                this.savedVoicePreference = {
                    type: 'managed',
                    voiceKey: voiceKey
                };
                this._savePreferences();

                // Clear chunks and progress, disable play button, and show loading status before fetching manifest
                this.chunks = [];
                this._updateProgressDisplay(0, 0);
                this._updateNavButtons();
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.disabled = true;
                    this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                }
                this._setStatusMessage('Đang tải giọng đọc Kiếm Lai...');

                if (this.managedEngine && this.chapterId) {
                    try {
                        const manifest = await this._selectManagedPlayback(this.chapterId, voiceKey);

                        // Sequence & current selection guard against rapid selection races
                        if (selectionSequence !== this._voiceSelectionSequenceId ||
                            !this.dom.voiceSelect ||
                            this.dom.voiceSelect.value !== voiceVal) {
                            return;
                        }

                        this.chunks = (manifest && Array.isArray(manifest.segments))
                            ? manifest.segments
                            : (this.managedEngine.getSegments ? this.managedEngine.getSegments() : []);
                        this._updateProgressDisplay(0, this.chunks.length);
                        this._updateNavButtons();
                        if (this.dom.playPauseBtn) {
                            this.dom.playPauseBtn.disabled = (this.chunks.length === 0);
                            if (this.chunks.length > 0) {
                                this.dom.playPauseBtn.removeAttribute('aria-disabled');
                            } else {
                                this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                            }
                        }
                        this._setStatusMessage('Sẵn sàng phát giọng đọc Kiếm Lai (' + this.chunks.length + ' đoạn).');
                    } catch (e) {
                        if (e.name === 'AbortError') return;
                        if (selectionSequence !== this._voiceSelectionSequenceId ||
                            !this.dom.voiceSelect ||
                            this.dom.voiceSelect.value !== voiceVal) {
                            return;
                        }
                        this.chunks = [];
                        this._updateProgressDisplay(0, 0);
                        this._updateNavButtons();
                        if (this.dom.playPauseBtn) {
                            this.dom.playPauseBtn.disabled = true;
                            this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                        }
                        this._setStatusMessage('Không thể tải giọng đọc Kiếm Lai. Vui lòng chọn giọng khác.');
                    }
                }
            } else {
                let voiceIdentifier = voiceVal;
                if (voiceVal.startsWith('device:')) {
                    voiceIdentifier = voiceVal.substring('device:'.length);
                }

                this.activeEngineType = 'device';
                this.activeEngine = this.deviceEngine;
                this.engine = this.deviceEngine;

                if (this.deviceEngine) {
                    this.deviceEngine.setVoice(voiceIdentifier);
                    const selected = this.deviceEngine.selectedVoice;
                    if (selected) {
                        this.savedVoicePreference = {
                            type: 'device',
                            voiceURI: selected.voiceURI || '',
                            name: selected.name || '',
                            lang: selected.lang || ''
                        };
                        this._savePreferences();
                    }
                }

                this._parseAndLoadChunks();
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                this._setStatusMessage('Sẵn sàng phát giọng đọc (' + this.chunks.length + ' câu).');
            }
        }

        /**
         * Handles Rate selector change and persists preference globally.
         * Adjusts playback rate on active engines without re-requesting audio.
         * @private
         */
        _handleRateChange() {
            if (!this.dom.rateSelect) {
                return;
            }
            const rateVal = parseFloat(this.dom.rateSelect.value);
            if (Number.isFinite(rateVal)) {
                if (this.deviceEngine && typeof this.deviceEngine.setRate === 'function') {
                    this.deviceEngine.setRate(rateVal);
                }
                if (this.managedEngine && typeof this.managedEngine.setRate === 'function') {
                    this.managedEngine.setRate(rateVal);
                }
                if (this.engine && typeof this.engine.setRate === 'function') {
                    this.engine.setRate(rateVal);
                }
                this._savePreferences();
            }
        }

        /**
         * Handles page unload/hide cleanup.
         * Saves current resume position at most once before tearing down engine state.
         * Ensures duplicate lifecycle events (e.g. beforeunload followed by pagehide) are idempotent.
         * @private
         */
        _handleUnload() {
            if (this.isUnloaded) {
                return;
            }
            this.isUnloaded = true;
            this._cancelPendingAutoNext();

            if (!this.isCompleted && this.hasMeaningfulResume && this.engine && this.chunks.length > 0 && this.chapterId) {
                const currentIndex = this.engine.getCurrentChunkIndex();
                if (typeof currentIndex === 'number' && currentIndex >= 0) {
                    this._saveResumePosition(currentIndex);
                }
            }

            this._invalidateChapterPlayback();
            this._clearHighlight();
            if (this.deviceEngine) {
                this.deviceEngine.stop();
            }
            if (this.managedEngine) {
                this.managedEngine.stop();
            }
            if (this.engine && this.engine !== this.deviceEngine && this.engine !== this.managedEngine) {
                this.engine.stop();
            }
        }

        /**
         * Handles cross-tab storage synchronization for narration preferences.
         * @param {StorageEvent} event
         * @private
         */
        _handleStorageEvent(event) {
            if (!event || !event.key) {
                return;
            }

            if (event.key === this.storageKeys.preferences && event.newValue) {
                try {
                    const prefs = JSON.parse(event.newValue);
                    if (prefs && prefs.version === 1) {
                        if (typeof prefs.rate === 'number' && Number.isFinite(prefs.rate)) {
                            const clampedRate = Math.max(0.5, Math.min(2.0, prefs.rate));
                            if (this.engine && this.engine.getState() !== 'PLAYING') {
                                this.engine.setRate(clampedRate);
                                if (this.dom.rateSelect) {
                                    this.dom.rateSelect.value = String(clampedRate);
                                }
                            }
                        }
                        if (typeof prefs.followMode === 'boolean') {
                            this.setFollowMode(prefs.followMode, false);
                        }
                        if (typeof prefs.autoNext === 'boolean') {
                            this.setAutoNext(prefs.autoNext, false);
                        }
                        if (typeof prefs.fallbackToDevice === 'boolean') {
                            this.setFallbackToDevice(prefs.fallbackToDevice, false);
                        }
                        if (prefs.voice && typeof prefs.voice === 'object') {
                            if (prefs.voice.type === 'managed' || (prefs.voice.voiceKey && !prefs.voice.voiceURI)) {
                                this.savedVoicePreference = {
                                    type: 'managed',
                                    voiceKey: prefs.voice.voiceKey || ''
                                };
                            } else {
                                this.savedVoicePreference = {
                                    type: 'device',
                                    voiceURI: prefs.voice.voiceURI || '',
                                    name: prefs.voice.name || '',
                                    lang: prefs.voice.lang || ''
                                };
                            }
                            if (this.engine && this.engine.getState() !== 'PLAYING') {
                                const deviceVoices = this.deviceEngine && this.deviceEngine.getSortedVoices
                                    ? this.deviceEngine.getSortedVoices()
                                    : (this.deviceEngine ? this.deviceEngine.getVoices() : []);
                                const managedVoices = this.managedEngine ? this.managedEngine.getVoices() : [];
                                this._populateVoiceDropdown(deviceVoices, managedVoices);
                            }
                        }
                    }
                } catch (e) {
                    // Ignore malformed storage updates
                }
            }
        }

        /**
         * Loads saved preferences from localStorage.
         * @returns {Object|null}
         * @private
         */
        _loadPreferences() {
            try {
                if (typeof localStorage === 'undefined') {
                    return null;
                }
                const raw = localStorage.getItem(this.storageKeys.preferences);
                if (!raw) {
                    return null;
                }
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object' && parsed.version === 1) {
                    return parsed;
                }
                return null;
            } catch (e) {
                console.warn('[NarrationController] Unable to read narration preferences from localStorage:', e);
                return null;
            }
        }

        /**
         * Persists current preferences (voice, rate, followMode, autoNext, fallbackToDevice) into localStorage.
         * @private
         */
        _savePreferences() {
            try {
                if (typeof localStorage === 'undefined') {
                    return;
                }
                const payload = {
                    version: 1,
                    rate: (this.engine && typeof this.engine.rate === 'number') ? this.engine.rate : 1.0,
                    followMode: Boolean(this.followMode),
                    autoNext: Boolean(this.autoNext),
                    fallbackToDevice: Boolean(this.fallbackToDevice),
                    voice: this.savedVoicePreference ? (
                        this.savedVoicePreference.type === 'managed'
                            ? {
                                type: 'managed',
                                voiceKey: this.savedVoicePreference.voiceKey || ''
                            }
                            : {
                                type: 'device',
                                voiceURI: this.savedVoicePreference.voiceURI || '',
                                name: this.savedVoicePreference.name || '',
                                lang: this.savedVoicePreference.lang || ''
                            }
                    ) : null
                };
                localStorage.setItem(this.storageKeys.preferences, JSON.stringify(payload));
            } catch (e) {
                console.warn('[NarrationController] Unable to save narration preferences to localStorage:', e);
            }
        }

        /**
         * Loads saved narration resume position from localStorage.
         * @returns {Object|null}
         * @private
         */
        _loadResumePosition() {
            try {
                if (typeof localStorage === 'undefined') {
                    return null;
                }
                const raw = localStorage.getItem(this.storageKeys.resume);
                if (!raw) {
                    return null;
                }
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object' && parsed.version === 1) {
                    return parsed;
                }
                return null;
            } catch (e) {
                console.warn('[NarrationController] Unable to read narration resume position from localStorage:', e);
                return null;
            }
        }

        /**
         * Persists latest narration resume position into localStorage.
         * @param {number} chunkIndex
         * @private
         */
        _saveResumePosition(chunkIndex) {
            if (!this.chapterId || this.chunks.length === 0 || typeof chunkIndex !== 'number') {
                return;
            }

            try {
                if (typeof localStorage === 'undefined') {
                    return;
                }
                const payload = {
                    version: 1,
                    chapterId: String(this.chapterId),
                    chunkIndex: Math.max(0, Math.min(this.chunks.length - 1, Math.floor(chunkIndex))),
                    totalChunks: this.chunks.length,
                    savedAt: Date.now()
                };
                localStorage.setItem(this.storageKeys.resume, JSON.stringify(payload));
            } catch (e) {
                console.warn('[NarrationController] Unable to save narration resume position to localStorage:', e);
            }
        }

        /**
         * Clears saved resume position from localStorage.
         * @private
         */
        _clearSavedResume() {
            try {
                if (typeof localStorage !== 'undefined') {
                    localStorage.removeItem(this.storageKeys.resume);
                }
            } catch (e) {
                console.warn('[NarrationController] Unable to clear narration resume position from localStorage:', e);
            }
        }

        /**
         * Engine Callback: State transition.
         * @param {string} newState
         * @param {string} [prevState]
         * @param {string} [engineType]
         * @private
         */
        _onEngineStateChange(newState, prevState, engineType) {
            if (engineType && engineType !== this.activeEngineType) {
                return;
            }

            const isPlaying = newState === 'PLAYING';
            const isPreparing = newState === 'PREPARING';

            if (this.dom.player) {
                this.dom.player.setAttribute('aria-busy', String(isPreparing));
            }
            if (this.dom.statusText) {
                this.dom.statusText.setAttribute('aria-busy', String(isPreparing));
            }

            if (this.dom.playIcon && this.dom.pauseIcon) {
                this.dom.playIcon.style.display = isPlaying ? 'none' : '';
                this.dom.pauseIcon.style.display = isPlaying ? '' : 'none';
            }

            let label = 'Phát giọng đọc';
            if (isPlaying) {
                label = 'Tạm dừng giọng đọc';
            } else if (newState === 'PREPARING') {
                label = 'Dừng chuẩn bị giọng đọc';
            } else if (newState === 'BLOCKED' || newState === 'ERROR') {
                label = 'Thử lại';
            } else if (newState === 'PAUSED') {
                label = 'Tiếp tục đọc';
            } else if (this.isCompleted) {
                label = 'Phát lại từ đầu';
            }

            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.setAttribute('aria-label', label);
                this.dom.playPauseBtn.title = label;
                this.dom.playPauseBtn.classList.toggle('is-playing', isPlaying);
            }

            if (newState === 'PREPARING') {
                this._setStatusMessage('Đang chuẩn bị giọng đọc...');
            } else if (newState === 'BLOCKED') {
                this._setStatusMessage('Đoạn đọc này hiện chưa sẵn sàng. Vui lòng thử lại sau.');
            } else if (newState === 'PAUSED') {
                this._setStatusMessage('Đã tạm dừng giọng đọc.');
            } else if (newState === 'IDLE' && prevState === 'PREPARING') {
                this._setStatusMessage('Đã dừng chuẩn bị giọng đọc. Nhấn Phát để thử lại.');
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

            // Play may retain the cue established by the initial IDLE seek, so no new cue event is required.
            if (this.chapterEngine && this.engine === this.chapterEngine) this._syncChapterHighlight();
            this._updateNavButtons();
        }

        /**
         * Engine Callback: Segment blocked (e.g. not playable yet).
         * @param {number} segIndex
         * @param {Object} seg
         * @param {string} [engineType]
         * @param {Object} [playbackDto]
         * @private
         */
        _onEngineBlocked(segIndex, seg, engineType, playbackDto) {
            if (engineType && engineType !== this.activeEngineType) {
                return;
            }

            if (this.fallbackToDevice && this.deviceEngine && this.deviceEngine.isSupported()) {
                const handled = this._fallbackToDeviceTts('Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');
                if (handled) {
                    return;
                }
            }

            this._setStatusMessage('Đoạn đọc này hiện chưa sẵn sàng. Vui lòng thử lại sau.');
            if (this.dom.player) {
                this.dom.player.setAttribute('aria-busy', 'false');
            }
            if (this.dom.statusText) {
                this.dom.statusText.setAttribute('aria-busy', 'false');
            }
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.setAttribute('aria-label', 'Thử lại');
                this.dom.playPauseBtn.title = 'Thử lại';
                this.dom.playPauseBtn.classList.remove('is-playing');
            }
        }

        /**
         * Engine Callback: Chunk start.
         * Highlights chunk element and scrolls it smoothly into comfortable view if followMode is enabled.
         * Avoids redundant scrolling and class toggling when consecutive chunks share the same DOM element.
         * Persists position into resume storage.
         * Displays subtle update notice if segment playback is outdated / refreshRecommended.
         * @param {number} chunkIndex
         * @param {Object} chunk
         * @param {string} [engineType]
         * @param {Object} [playbackDto]
         * @private
         */
        _onEngineChunkStart(chunkIndex, chunk, engineType, playbackDto) {
            if (engineType && engineType !== this.activeEngineType) {
                return;
            }

            this.isCompleted = false;
            this.hasMeaningfulResume = true;
            this.isAutoplayContinuation = false;
            const currentNum = chunkIndex + 1;
            const totalNum = this.chunks.length;

            this._updateProgressDisplay(currentNum, totalNum);

            if (playbackDto && (playbackDto.refreshRecommended === true || playbackDto.healthStatus === 'OUTDATED')) {
                this._setStatusMessage('Đang phát bản giọng đọc hiện có; bản mới có thể đang được cập nhật.');
            } else {
                this._setStatusMessage('Đang đọc câu ' + currentNum + ' / ' + totalNum);
            }

            this._updateNavButtons();

            this._saveResumePosition(chunkIndex);

            if (this.chapterEngine && this.engine === this.chapterEngine) {
                this._syncChapterHighlight();
                return;
            }
            if (this.followMode && chunk && chunk.element) {
                const elementChanged = this._highlightChunk(chunk);
                if (!this.isSettingsOpen() && elementChanged && !this._isElementComfortablyVisible(chunk.element)) {
                    this._scrollElementIntoView(chunk.element);
                }
            }
        }

        /**
         * Engine Callback: Chunk end.
         * @param {number} chunkIndex
         * @param {Object} chunk
         * @param {string} [engineType]
         * @private
         */
        _onEngineChunkEnd(chunkIndex, chunk, engineType) {
            // Intentionally passive; next chunk will trigger _onEngineChunkStart
        }

        /**
         * Engine Callback: Natural Chapter end.
         * Clears highlight and saved resume, displays total/total, and configures replay from start.
         * When autoNext is enabled, initiates seamless in-page chapter transition.
         * @param {string} [engineType]
         * @private
         */
        _onEngineChapterEnd(engineType) {
            if (engineType && engineType !== this.activeEngineType) {
                return;
            }

            this.isCompleted = true;
            this.hasMeaningfulResume = false;
            this.isAutoplayContinuation = false;
            this._clearSavedResume();
            this._clearHighlight();
            this._updateProgressDisplay(this.chunks.length, this.chunks.length);
            this._setStatusMessage('Đã đọc xong chương.');
            this._updateNavButtons();
            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.setAttribute('aria-label', 'Phát lại từ đầu');
                this.dom.playPauseBtn.title = 'Phát lại từ đầu';
            }

            // H.9G ends here. Chapter audio must not enter the legacy Auto Next path.
            if (this.engine === this.chapterEngine) return;

            if (this.autoNext && !this.isNavigatingToNext) {
                const nextUrl = this._resolveNextChapterUrl();
                if (nextUrl) {
                    this.isNavigatingToNext = true;
                    this._setStatusMessage('Đã đọc xong chương. Đang chuyển sang chương tiếp theo...');
                    if (typeof window !== 'undefined') {
                        this._autoNextTimeoutId = window.setTimeout(() => {
                            this._autoNextTimeoutId = null;
                            if (this.isNavigatingToNext && !this.isUnloaded) {
                                this._transitionToNextChapter(nextUrl);
                            }
                        }, 500);
                    }
                }
            }
        }

        /**
         * Performs seamless in-page transition to the next chapter.
         * Fetches chapter HTML, validates fragments, updates DOM in-place, synchronizes history & events, and resumes narration.
         * @param {string} nextUrl
         * @returns {Promise<void>}
         * @private
         */
        async _transitionToNextChapter(nextUrl) {
            if (!nextUrl || this.isUnloaded) {
                this.isNavigatingToNext = false;
                return;
            }

            if (this._transitionAbortController) {
                try {
                    this._transitionAbortController.abort();
                } catch (ignored) {}
            }

            const currentSequenceId = ++this._transitionSequenceId;
            const abortController = new AbortController();
            this._transitionAbortController = abortController;
            this.isNavigatingToNext = true;

            try {
                const response = await fetch(nextUrl, {
                    signal: abortController.signal,
                    headers: {
                        'Accept': 'text/html'
                    }
                });

                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }

                const htmlText = await response.text();

                if (this.isUnloaded || !this.isNavigatingToNext || currentSequenceId !== this._transitionSequenceId) {
                    return; // Stale or cancelled transition
                }

                if (typeof DOMParser === 'undefined') {
                    throw new Error('DOMParser is not supported.');
                }

                const parser = new DOMParser();
                const fetchedDoc = parser.parseFromString(htmlText, 'text/html');

                const validation = this._validateFetchedChapterDocument(fetchedDoc);
                if (!validation.valid) {
                    throw new Error('Validation failed: ' + validation.reason);
                }

                this._applyChapterTransition(fetchedDoc, nextUrl, validation);
            } catch (error) {
                if (error && error.name === 'AbortError') {
                    return; // Intentional abort, no error state
                }

                if (currentSequenceId !== this._transitionSequenceId || !this.isNavigatingToNext) {
                    return;
                }

                console.warn('[NarrationController] Seamless chapter transition failed:', error);
                this.isNavigatingToNext = false;
                this._transitionAbortController = null;

                // Remain on Chapter A without partial DOM commits
                this._setStatusMessage('Không thể tự động tải chương sau. Vui lòng bấm "Chương sau" để tiếp tục.');
            }
        }

        /**
         * Validates that the fetched document contains all required reader DOM fragments and non-empty chapter ID.
         * @param {Document} doc
         * @returns {{valid: boolean, reason?: string, newChapterId?: string, bodyEl?: Element, breadcrumbEl?: Element, headerEl?: Element, navTopEl?: Element, navBottomEl?: Element, title?: string}}
         * @private
         */
        _validateFetchedChapterDocument(doc) {
            if (!doc) {
                return { valid: false, reason: 'Document is null or undefined' };
            }

            const title = (doc.title || '').trim();
            if (!title) {
                return { valid: false, reason: 'Missing document title' };
            }

            const bodyEl = doc.querySelector(this.selectors.body || '.novel-reader-chapter-body');
            if (!bodyEl) {
                return { valid: false, reason: 'Missing chapter body container' };
            }

            const newChapterId = (bodyEl.getAttribute('data-chapter-id') || (bodyEl.dataset && bodyEl.dataset.chapterId) || '').trim();
            if (!newChapterId) {
                return { valid: false, reason: 'Missing data-chapter-id on chapter body' };
            }

            const breadcrumbEl = doc.querySelector('.novel-chapter-breadcrumb');
            if (!breadcrumbEl) {
                return { valid: false, reason: 'Missing breadcrumb container' };
            }

            const headerEl = doc.querySelector('.novel-chapter-header');
            if (!headerEl) {
                return { valid: false, reason: 'Missing chapter header container' };
            }

            const navTopEl = doc.querySelector('.novel-chapter-nav--top');
            if (!navTopEl) {
                return { valid: false, reason: 'Missing top navigation' };
            }

            const navBottomEl = doc.querySelector('.novel-chapter-nav--bottom');
            if (!navBottomEl) {
                return { valid: false, reason: 'Missing bottom navigation' };
            }

            return {
                valid: true,
                title: title,
                newChapterId: newChapterId,
                bodyEl: bodyEl,
                breadcrumbEl: breadcrumbEl,
                headerEl: headerEl,
                navTopEl: navTopEl,
                navBottomEl: navBottomEl
            };
        }

        /**
         * Commits fetched chapter data to current DOM in-place and starts narration for the new chapter.
         * @param {Document} fetchedDoc
         * @param {string} nextUrl
         * @param {Object} validation
         * @private
         */
        _applyChapterTransition(fetchedDoc, nextUrl, validation) {
            this._invalidateChapterPlayback();
            const newChapterId = validation.newChapterId;

            // 1. Chapter Prose Body (keep stable DOM node)
            if (this.dom.body) {
                this.dom.body.innerHTML = validation.bodyEl.innerHTML;
                this.dom.body.setAttribute('data-chapter-id', newChapterId);
            }
            if (this.dom.player) {
                this.dom.player.setAttribute('data-chapter-id', newChapterId);
            }

            // 2. Breadcrumb
            const curBreadcrumb = document.querySelector('.novel-chapter-breadcrumb');
            if (curBreadcrumb && validation.breadcrumbEl) {
                curBreadcrumb.innerHTML = validation.breadcrumbEl.innerHTML;
            }

            // 3. Chapter Header
            const curHeader = document.querySelector('.novel-chapter-header');
            if (curHeader && validation.headerEl) {
                curHeader.innerHTML = validation.headerEl.innerHTML;
            }

            // 4. Top & Bottom Navigation
            const curNavTop = document.querySelector('.novel-chapter-nav--top');
            if (curNavTop && validation.navTopEl) {
                curNavTop.innerHTML = validation.navTopEl.innerHTML;
            }

            const curNavBottom = document.querySelector('.novel-chapter-nav--bottom');
            if (curNavBottom && validation.navBottomEl) {
                curNavBottom.innerHTML = validation.navBottomEl.innerHTML;
            }

            // 5. Bookmark Button (preserve stable node, update attributes & label)
            const curBookmarkBtn = document.getElementById('novelChapterBookmarkBtn');
            const fetchedBookmarkBtn = fetchedDoc.getElementById('novelChapterBookmarkBtn');
            if (curBookmarkBtn && fetchedBookmarkBtn) {
                curBookmarkBtn.setAttribute('data-chapter-id', fetchedBookmarkBtn.getAttribute('data-chapter-id') || newChapterId);
                curBookmarkBtn.setAttribute('data-bookmark-url', fetchedBookmarkBtn.getAttribute('data-bookmark-url') || '');
                const isBookmarked = fetchedBookmarkBtn.getAttribute('data-bookmarked') === 'true';
                curBookmarkBtn.setAttribute('data-bookmarked', String(isBookmarked));
                if (fetchedBookmarkBtn.getAttribute('data-csrf-token')) {
                    curBookmarkBtn.setAttribute('data-csrf-token', fetchedBookmarkBtn.getAttribute('data-csrf-token'));
                }
                if (fetchedBookmarkBtn.getAttribute('data-csrf-header')) {
                    curBookmarkBtn.setAttribute('data-csrf-header', fetchedBookmarkBtn.getAttribute('data-csrf-header'));
                }
                curBookmarkBtn.classList.toggle('is-bookmarked', isBookmarked);

                const curText = curBookmarkBtn.querySelector('.novel-bookmark-btn-text');
                const fetchedText = fetchedBookmarkBtn.querySelector('.novel-bookmark-btn-text');
                if (curText && fetchedText) {
                    curText.textContent = fetchedText.textContent;
                }
                curBookmarkBtn.disabled = false;
            }

            // 6. Reading Trackers (Progress & History)
            const curProgressTracker = document.getElementById('novelReadingProgressTracker');
            const fetchedProgressTracker = fetchedDoc.getElementById('novelReadingProgressTracker');
            if (curProgressTracker && fetchedProgressTracker) {
                curProgressTracker.setAttribute('data-chapter-id', fetchedProgressTracker.getAttribute('data-chapter-id') || newChapterId);
                if (fetchedProgressTracker.getAttribute('data-csrf-token')) {
                    curProgressTracker.setAttribute('data-csrf-token', fetchedProgressTracker.getAttribute('data-csrf-token'));
                }
                if (fetchedProgressTracker.getAttribute('data-csrf-header')) {
                    curProgressTracker.setAttribute('data-csrf-header', fetchedProgressTracker.getAttribute('data-csrf-header'));
                }
            } else if (curProgressTracker) {
                curProgressTracker.setAttribute('data-chapter-id', newChapterId);
            }

            const curHistoryTracker = document.getElementById('novelReadingHistoryTracker');
            const fetchedHistoryTracker = fetchedDoc.getElementById('novelReadingHistoryTracker');
            if (curHistoryTracker && fetchedHistoryTracker) {
                curHistoryTracker.setAttribute('data-chapter-id', fetchedHistoryTracker.getAttribute('data-chapter-id') || newChapterId);
                curHistoryTracker.setAttribute('data-history-url', fetchedHistoryTracker.getAttribute('data-history-url') || ('/novel/chapters/' + encodeURIComponent(newChapterId) + '/history'));
                if (fetchedHistoryTracker.getAttribute('data-csrf-token')) {
                    curHistoryTracker.setAttribute('data-csrf-token', fetchedHistoryTracker.getAttribute('data-csrf-token'));
                }
                if (fetchedHistoryTracker.getAttribute('data-csrf-header')) {
                    curHistoryTracker.setAttribute('data-csrf-header', fetchedHistoryTracker.getAttribute('data-csrf-header'));
                }
            } else if (curHistoryTracker) {
                curHistoryTracker.setAttribute('data-chapter-id', newChapterId);
                curHistoryTracker.setAttribute('data-history-url', '/novel/chapters/' + encodeURIComponent(newChapterId) + '/history');
            }

            // 7. TOC Drawer Active Chapter
            const curTocList = document.querySelector('#novelTocDrawer .novel-toc-list');
            const fetchedTocList = fetchedDoc.querySelector('#novelTocDrawer .novel-toc-list');
            if (curTocList && fetchedTocList) {
                curTocList.innerHTML = fetchedTocList.innerHTML;
            }

            // 8. Document Title & Browser History
            document.title = validation.title;

            let chapterSlug = '';
            try {
                const parsedUrl = new URL(nextUrl, (typeof window !== 'undefined' && window.location) ? window.location.href : 'http://localhost');
                const parts = parsedUrl.pathname.split('/').filter(Boolean);
                chapterSlug = parts[parts.length - 1] || '';
            } catch (ignored) {}

            if (typeof window !== 'undefined' && window.history && typeof window.history.pushState === 'function') {
                window.history.pushState({ chapterId: newChapterId, slug: chapterSlug }, '', nextUrl);
            }

            // 9. Update NarrationController Chapter State before event dispatch
            this.chapterId = newChapterId;
            this.chunks = [];
            this.isCompleted = false;
            this.hasMeaningfulResume = false;
            this.isAutoplayContinuation = false;
            this.isNavigatingToNext = false;
            this._transitionAbortController = null;
            this._clearSavedResume();
            this._clearHighlight();
            this._updateProgressDisplay(0, 0);
            this._updateNavButtons();

            // 10. Dispatch custom chapter-changed event
            if (typeof document !== 'undefined') {
                document.dispatchEvent(new CustomEvent('kiemlai:chapter-changed', {
                    detail: {
                        chapterId: newChapterId,
                        slug: chapterSlug,
                        url: nextUrl
                    }
                }));
            }

            // 11. Parse & Load Chapter B Chunks
            const shouldAttemptManaged = Boolean(this.managedEngine && (this.activeEngineType === 'managed' || (this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)));

            if (shouldAttemptManaged) {
                const requestedChapterId = newChapterId;
                const activeEngineVoiceKey = (this.managedEngine && typeof this.managedEngine.getSelectedVoiceKey === 'function')
                    ? this.managedEngine.getSelectedVoiceKey()
                    : null;
                const requestedVoiceKey = activeEngineVoiceKey || ((this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)
                    ? this.savedVoicePreference.voiceKey
                    : null);
                const requestedVoiceSequence = this._voiceSelectionSequenceId;

                this.activeEngineType = 'managed';
                this.activeEngine = this.managedEngine;
                this.engine = this.managedEngine;

                const handleManagedFailure = () => {
                    if (this.managedEngine && typeof this.managedEngine.setManifest === 'function') {
                        this.managedEngine.setManifest(null);
                    }

                    // Fallback Policy: Device TTS fallback only when explicitly enabled
                    if (this.fallbackToDevice && this.deviceEngine && this.deviceEngine.isSupported()) {
                        this.activeEngineType = 'device';
                        this.activeEngine = this.deviceEngine;
                        this.engine = this.deviceEngine;

                        // Preserve user's selected Managed preference for later chapters (do NOT overwrite savedVoicePreference)

                        if (this.parser && typeof this.parser.parseChapterBody === 'function' && this.dom.body) {
                            this.chunks = this.parser.parseChapterBody(this.dom.body);
                        } else {
                            this.chunks = [];
                        }

                        if (this.deviceEngine) {
                            this.deviceEngine.loadChunks(this.chunks, 0);
                        }

                        const deviceVoices = (this.deviceEngine && this.deviceEngine.getSortedVoices)
                            ? this.deviceEngine.getSortedVoices()
                            : (this.deviceEngine ? this.deviceEngine.getVoices() : []);
                        const mVoices = (this._cachedManagedVoices && this._cachedManagedVoices.length > 0)
                            ? this._cachedManagedVoices
                            : (this.managedEngine ? this.managedEngine.getVoices() : []);
                        this._populateVoiceDropdown(deviceVoices, mVoices, { skipActivation: true });
                        const selectedDeviceVoice = this.deviceEngine.selectedVoice;
                        if (selectedDeviceVoice && this.dom.voiceSelect) {
                            this.dom.voiceSelect.value = 'device:' + (selectedDeviceVoice.voiceURI || selectedDeviceVoice.name);
                        }

                        if (this.chunks.length === 0) {
                            this._updateProgressDisplay(0, 0);
                            this._updateNavButtons();
                            this._setStatusMessage('Không tìm thấy nội dung văn bản để đọc.');
                            if (this.dom.playPauseBtn) {
                                this.dom.playPauseBtn.disabled = true;
                                this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                            }
                        } else {
                            this._updateProgressDisplay(0, this.chunks.length);
                            this._updateNavButtons();
                            this._setStatusMessage('Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');
                            if (this.dom.playPauseBtn) {
                                this.dom.playPauseBtn.disabled = false;
                                this.dom.playPauseBtn.removeAttribute('aria-disabled');
                            }
                            if (this.deviceEngine) {
                                this.deviceEngine.play(0);
                            }
                        }
                        return;
                    }

                    // Safe failure without fallback:
                    this.chunks = [];
                    this._updateProgressDisplay(0, 0);
                    this._setStatusMessage('Không thể tải giọng đọc Kiếm Lai cho chương mới. Vui lòng chọn giọng khác hoặc sử dụng Giọng thiết bị.');
                    if (this.dom.playPauseBtn) {
                        this.dom.playPauseBtn.disabled = true;
                        this.dom.playPauseBtn.setAttribute('aria-disabled', 'true');
                        this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                        this.dom.playPauseBtn.title = 'Phát giọng đọc';
                        this.dom.playPauseBtn.classList.remove('is-playing');
                    }
                    if (this.dom.voiceSelect) {
                        this.dom.voiceSelect.disabled = false;
                    }
                    this._updateNavButtons();
                };

                if (!requestedVoiceKey) {
                    handleManagedFailure();
                    return;
                }

                this._selectManagedPlayback(requestedChapterId, requestedVoiceKey).then(manifest => {
                    if (this.chapterId !== requestedChapterId) {
                        return;
                    }
                    if (this.activeEngineType !== 'managed') {
                        return;
                    }
                    if (this._voiceSelectionSequenceId !== requestedVoiceSequence) {
                        return;
                    }
                    const currentEngineVoiceKey = (this.managedEngine && typeof this.managedEngine.getSelectedVoiceKey === 'function')
                        ? this.managedEngine.getSelectedVoiceKey()
                        : null;
                    const currentSelectedKey = currentEngineVoiceKey || ((this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)
                        ? this.savedVoicePreference.voiceKey
                        : null);
                    if (currentSelectedKey !== requestedVoiceKey) {
                        return;
                    }
                    if (this.dom.voiceSelect && requestedVoiceKey && this.dom.voiceSelect.value && this.dom.voiceSelect.value !== ('managed:' + requestedVoiceKey)) {
                        return;
                    }

                    const availableVoices = (manifest && Array.isArray(manifest.availableVoices))
                        ? manifest.availableVoices
                        : (this.managedEngine.getVoices ? this.managedEngine.getVoices() : []);
                    if (Array.isArray(availableVoices) && availableVoices.length > 0) {
                        this._cachedManagedVoices = availableVoices;
                    }
                    const deviceVoices = (this.deviceEngine && this.deviceEngine.getSortedVoices)
                        ? this.deviceEngine.getSortedVoices()
                        : (this.deviceEngine ? this.deviceEngine.getVoices() : []);
                    this._populateVoiceDropdown(deviceVoices, availableVoices, { skipActivation: true });

                    this.chunks = (manifest && Array.isArray(manifest.segments))
                        ? manifest.segments
                        : (this.managedEngine.getSegments ? this.managedEngine.getSegments() : []);
                    if (this.chunks.length === 0) {
                        handleManagedFailure();
                    } else {
                        this._updateProgressDisplay(0, this.chunks.length);
                        this._updateNavButtons();
                        this._setStatusMessage('Sẵn sàng phát giọng đọc (' + this.chunks.length + ' đoạn).');
                        if (this.dom.playPauseBtn) {
                            this.dom.playPauseBtn.disabled = false;
                            this.dom.playPauseBtn.removeAttribute('aria-disabled');
                        }
                        if (this.engine === this.managedEngine) {
                            this.managedEngine.play(0);
                        }
                    }
                }).catch(err => {
                    if (this.chapterId !== requestedChapterId ||
                        this.activeEngineType !== 'managed' ||
                        this._voiceSelectionSequenceId !== requestedVoiceSequence) {
                        return;
                    }
                    const currentEngineVoiceKey = (this.managedEngine && typeof this.managedEngine.getSelectedVoiceKey === 'function')
                        ? this.managedEngine.getSelectedVoiceKey()
                        : null;
                    const currentSelectedKey = currentEngineVoiceKey || ((this.savedVoicePreference && this.savedVoicePreference.type === 'managed' && this.savedVoicePreference.voiceKey)
                        ? this.savedVoicePreference.voiceKey
                        : null);
                    if (currentSelectedKey !== requestedVoiceKey) {
                        return;
                    }
                    if (err && err.name === 'AbortError') {
                        return;
                    }
                    console.warn('[NarrationController] Error loading next chapter manifest:', err);
                    handleManagedFailure();
                });
            } else {
                if (this.parser && typeof this.parser.parseChapterBody === 'function' && this.dom.body) {
                    this.chunks = this.parser.parseChapterBody(this.dom.body);
                } else {
                    this.chunks = [];
                }

                if (this.deviceEngine) {
                    this.deviceEngine.loadChunks(this.chunks, 0);
                }

                if (this.chunks.length === 0) {
                    this._updateProgressDisplay(0, 0);
                    this._updateNavButtons();
                    this._setStatusMessage('Không tìm thấy nội dung văn bản để đọc.');
                    if (this.dom.playPauseBtn) {
                        this.dom.playPauseBtn.disabled = true;
                    }
                } else {
                    this._updateProgressDisplay(0, this.chunks.length);
                    this._updateNavButtons();
                    this._setStatusMessage('Sẵn sàng phát giọng đọc (' + this.chunks.length + ' câu).');
                    if (this.dom.playPauseBtn) {
                        this.dom.playPauseBtn.disabled = false;
                        this.dom.playPauseBtn.removeAttribute('aria-disabled');
                    }
                    if (this.deviceEngine) {
                        this.deviceEngine.play(0);
                    }
                }
            }
        }

        /**
         * Engine Callback: Engine playback or network error.
         * Authoritative handler for engine-specific error messaging, autoplay-continuation recovery,
         * and Managed narration retry/accessibility UX.
         * @param {any} error
         * @param {string} [engineType]
         * @private
         */
        _onEngineError(error, engineType) {
            if (engineType && engineType !== this.activeEngineType) {
                return;
            }

            if (error && (error.name === 'AbortError' || error === 'AbortError')) {
                return;
            }

            if (this.dom.player) {
                this.dom.player.setAttribute('aria-busy', 'false');
            }
            if (this.dom.statusText) {
                this.dom.statusText.setAttribute('aria-busy', 'false');
            }

            if (this.isAutoplayContinuation) {
                this.isAutoplayContinuation = false;
                this._updateProgressDisplay(0, this.chunks.length);
                this._updateNavButtons();
                this._setStatusMessage('Đã sang chương mới. Nhấn Phát để tiếp tục.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                    this.dom.playPauseBtn.title = 'Phát giọng đọc';
                    this.dom.playPauseBtn.classList.remove('is-playing');
                }
                return;
            }

            if (engineType === 'managed') {
                if (this.fallbackToDevice && this.deviceEngine && this.deviceEngine.isSupported()) {
                    const handled = this._fallbackToDeviceTts('Giọng Kiếm Lai không khả dụng, đã tự động chuyển sang Giọng thiết bị.');
                    if (handled) {
                        return;
                    }
                }

                this._setStatusMessage('Không thể phát âm thanh giọng đọc Kiếm Lai. Vui lòng thử lại hoặc chọn Giọng thiết bị.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.setAttribute('aria-label', 'Thử lại');
                    this.dom.playPauseBtn.title = 'Thử lại';
                    this.dom.playPauseBtn.classList.remove('is-playing');
                }
                this._updateNavButtons();
            } else {
                this._setStatusMessage('Xảy ra lỗi khi phát giọng đọc.');
                if (this.dom.playPauseBtn) {
                    this.dom.playPauseBtn.setAttribute('aria-label', 'Phát giọng đọc');
                    this.dom.playPauseBtn.title = 'Phát giọng đọc';
                    this.dom.playPauseBtn.classList.remove('is-playing');
                }
                this._updateNavButtons();
            }
        }

        /**
         * Engine Callback: Voices changed.
         * Re-evaluates saved voice preference against newly available system voices.
         * Skips automatic activation if narration is playing/paused, user explicitly picked a voice,
         * or managed catalog discovery has not resolved yet (preventing Chrome bootstrap race).
         * @param {Array<SpeechSynthesisVoice>} voices
         * @private
         */
        _onEngineVoicesChanged(voices) {
            const mVoices = this.managedEngine ? this.managedEngine.getVoices() : [];
            const engineState = this.engine ? this.engine.getState() : null;
            const isPlaybackActive = engineState === 'PLAYING' || engineState === 'PAUSED';
            const skipActivation = isPlaybackActive || Boolean(this._hasUserExplicitlySelectedVoice) || !this._managedCatalogResolved;
            this._populateVoiceDropdown(voices, mVoices, { skipActivation });
        }

        /**
         * Updates sentence progress display and horizontal progress bar in DOM.
         * @param {number} current
         * @param {number} total
         * @private
         */
        _updateProgressDisplay(current, total) {
            const currentNum = Number(current) || 0;
            const totalNum = Number(total) || 0;

            if (this.dom.progressCurrent) {
                this.dom.progressCurrent.textContent = String(currentNum);
            }
            if (this.dom.progressTotal) {
                this.dom.progressTotal.textContent = String(totalNum);
            }

            if (this.dom.progressBar) {
                let percent = 0;
                if (totalNum > 0) {
                    percent = Math.max(0, Math.min(100, Math.round((currentNum / totalNum) * 100)));
                }

                this.dom.progressBar.setAttribute('aria-valuenow', String(percent));
                this.dom.progressBar.setAttribute('aria-valuetext', currentNum + ' trên ' + totalNum + ' câu');

                if (this.dom.progressFill) {
                    this.dom.progressFill.style.width = percent + '%';
                }
            }
        }

        /**
         * Updates accessibility live region status message.
         * @param {string} msg
         * @private
         */
        _setStatusMessage(msg) {
            if (this.dom.statusText && typeof msg === 'string') {
                this.dom.statusText.textContent = msg;
            }
        }

        /**
         * Updates disabled state of Prev/Next sentence buttons based on current chunk index.
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
                if (this.dom.rewindBtn) {
                    this.dom.rewindBtn.disabled = true;
                    this.dom.rewindBtn.setAttribute('aria-disabled', 'true');
                }
                if (this.dom.forwardBtn) {
                    this.dom.forwardBtn.disabled = true;
                    this.dom.forwardBtn.setAttribute('aria-disabled', 'true');
                }
                return;
            }

            const currentIndex = this.engine.getCurrentChunkIndex();
            const total = this.chunks.length;
            const canPrev = this.engine === this.chapterEngine ? this.chapterEngine.canPrevious() : currentIndex > 0;
            const canNext = this.engine === this.chapterEngine ? this.chapterEngine.canNext() : !this.isCompleted && (currentIndex < total - 1);

            if (this.dom.prevBtn) {
                this.dom.prevBtn.disabled = !canPrev;
                this.dom.prevBtn.setAttribute('aria-disabled', String(!canPrev));
            }

            if (this.dom.nextBtn) {
                this.dom.nextBtn.disabled = !canNext;
                this.dom.nextBtn.setAttribute('aria-disabled', String(!canNext));
            }

            const isManaged = this.activeEngineType === 'managed';
            if (this.dom.rewindBtn) {
                this.dom.rewindBtn.disabled = !isManaged;
                this.dom.rewindBtn.setAttribute('aria-disabled', String(!isManaged));
            }
            if (this.dom.forwardBtn) {
                this.dom.forwardBtn.disabled = !isManaged;
                this.dom.forwardBtn.setAttribute('aria-disabled', String(!isManaged));
            }
        }

        /**
         * Destroys controller and releases engine, highlights, and listeners.
         */
        destroy() {
            this._handleUnload();
            this._cancelPendingAutoNext();

            if (this.dom.playPauseBtn) {
                this.dom.playPauseBtn.removeEventListener('click', this._boundOnPlayPause);
            }
            if (this.dom.prevBtn) {
                this.dom.prevBtn.removeEventListener('click', this._boundOnPrev);
            }
            if (this.dom.nextBtn) {
                this.dom.nextBtn.removeEventListener('click', this._boundOnNext);
            }
            if (this.dom.rewindBtn) {
                this.dom.rewindBtn.removeEventListener('click', this._boundOnRewind);
            }
            if (this.dom.forwardBtn) {
                this.dom.forwardBtn.removeEventListener('click', this._boundOnForward);
            }
            if (this.dom.progressBar) {
                this.dom.progressBar.removeEventListener('click', this._boundOnProgressBarClick);
                this.dom.progressBar.removeEventListener('keydown', this._boundOnProgressBarKeydown);
            }
            if (this.dom.collapseToggle) {
                this.dom.collapseToggle.removeEventListener('click', this._boundOnCollapseToggle);
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
            if (this.dom.autoNextToggle) {
                this.dom.autoNextToggle.removeEventListener('change', this._boundOnAutoNextChange);
            }
            if (this.dom.fallbackToggle) {
                this.dom.fallbackToggle.removeEventListener('change', this._boundOnFallbackChange);
            }
            if (this.dom.settingsTrigger) {
                this.dom.settingsTrigger.removeEventListener('click', this._boundOnSettingsTriggerClick);
            }
            if (this.dom.settingsCloseBtn) {
                this.dom.settingsCloseBtn.removeEventListener('click', this._boundOnSettingsCloseClick);
            }

            if (typeof document !== 'undefined') {
                document.removeEventListener('click', this._boundOnDocumentClick);
                document.removeEventListener('keydown', this._boundOnDocumentKeydown);
            }

            window.removeEventListener('popstate', this._boundOnPopState);
            window.removeEventListener('beforeunload', this._boundOnUnload);
            window.removeEventListener('pagehide', this._boundOnUnload);
            window.removeEventListener('storage', this._boundOnStorage);

            if (this.deviceEngine && typeof this.deviceEngine.destroy === 'function') {
                this.deviceEngine.destroy();
            }
            if (this.managedEngine && typeof this.managedEngine.destroy === 'function') {
                this.managedEngine.destroy();
            }
            if (this.engine && this.engine !== this.deviceEngine && this.engine !== this.managedEngine && typeof this.engine.destroy === 'function') {
                this.engine.destroy();
            }

            this.chunks = [];
            this.isCompleted = false;
            this.hasMeaningfulResume = false;
            this.isAutoplayContinuation = false;
            this._hasUserExplicitlySelectedVoice = false;
            this._managedCatalogResolved = false;
            this._voiceSelectionSequenceId = 0;
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
        STORAGE_KEYS: Object.freeze(STORAGE_KEYS),
        HIGHLIGHT_CLASS: HIGHLIGHT_CLASS
    };
});
