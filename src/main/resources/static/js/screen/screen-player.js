/**
 * Screen Public Watch — Modern HTML5 Video Player Controller (MS-08G2 / MS-08G2-UX01)
 *
 * Progressively enhances native HTML5 video playback on Public Screen Watch with:
 * - Compact runtime error & retry recovery UX (MS-08G2 / G2-C03 invariants)
 * - Accessible playback controls (Play/Pause, -10s / +10s seek, timeline, buffered bar)
 * - Volume/Mute, Playback speed selector (0.5x - 2x), Fullscreen & PiP
 * - Scoped keyboard shortcuts and mobile double-tap seeking
 * - Client-side Resume Watching (throttled localStorage + resume dialog)
 * - Next Episode control bar action and unobtrusive near-end prompt
 */
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.ScreenPlayer = factory();
    }
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    var MEDIA_ERR_ABORTED = 1;
    var MEDIA_ERR_NETWORK = 2;
    var MEDIA_ERR_DECODE = 3;
    var MEDIA_ERR_SRC_NOT_SUPPORTED = 4;

    var ALLOWED_SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
    var STORAGE_KEY_PREFIX = 'kiemlai.screen.watchProgress.v1:';
    var SPEED_STORAGE_KEY = 'kiemlai.screen.playbackSpeed.v1';
    var RESUME_MIN_SECONDS = 30;
    var RESUME_END_MARGIN_SECONDS = 30;
    var SAVE_THROTTLE_MS = 5000;
    var SEEK_STEP_SECONDS = 10;
    var NEXT_UP_WINDOW_SECONDS = 45;
    var NEXT_UP_MIN_DURATION_SECONDS = 120;

    /**
     * Maps MediaError codes to localized Vietnamese user-facing messages.
     *
     * @param {MediaError|number|null|undefined} error
     * @returns {string} Localized explanation
     */
    function getErrorMessage(error) {
        var code = null;
        if (error !== null && error !== undefined) {
            if (typeof error === 'object' && typeof error.code === 'number') {
                code = error.code;
            } else if (typeof error === 'number') {
                code = error;
            }
        }

        switch (code) {
            case MEDIA_ERR_ABORTED:
                return 'Quá trình tải video đã bị hủy. Vui lòng thử lại.';
            case MEDIA_ERR_NETWORK:
                return 'Mất kết nối mạng trong quá trình phát video. Vui lòng kiểm tra đường truyền.';
            case MEDIA_ERR_DECODE:
                return 'Định dạng video hoặc âm thanh không được trình duyệt này hỗ trợ.';
            case MEDIA_ERR_SRC_NOT_SUPPORTED:
                return 'Không thể tải video từ máy chủ hoặc định dạng chưa sẵn sàng.';
            default:
                return 'Không thể tải video. Vui lòng kiểm tra kết nối mạng hoặc thử lại.';
        }
    }

    /**
     * Formats seconds into M:SS or H:MM:SS, or '--:--' when unknown/non-finite.
     *
     * @param {number|null|undefined} seconds
     * @returns {string}
     */
    function formatTime(seconds) {
        if (typeof seconds !== 'number' || !isFinite(seconds) || seconds < 0) {
            return '--:--';
        }
        var total = Math.floor(seconds);
        var hours = Math.floor(total / 3600);
        var minutes = Math.floor((total % 3600) / 60);
        var secs = total % 60;
        var secStr = secs < 10 ? '0' + secs : String(secs);
        if (hours > 0) {
            var minStr = minutes < 10 ? '0' + minutes : String(minutes);
            return hours + ':' + minStr + ':' + secStr;
        }
        return minutes + ':' + secStr;
    }

    /**
     * Normalizes a video source URL to a stable path without query parameters or hashes.
     *
     * @param {string|null|undefined} rawSrc
     * @returns {string}
     */
    function normalizeVideoSrc(rawSrc) {
        if (!rawSrc || typeof rawSrc !== 'string') {
            return '';
        }
        var trimmed = rawSrc.trim();
        if (!trimmed) {
            return '';
        }
        var noHash = trimmed.split('#')[0];
        var noQuery = noHash.split('?')[0];
        return noQuery;
    }

    /**
     * Normalizes a URL or path to a clean path string for safe comparison.
     * Strips scheme, host, port, query parameters, hash fragments, and trailing slashes.
     *
     * @param {string|null|undefined} url
     * @returns {string} Normalized path (e.g. '/media/assets/123/variants/w600')
     */
    function normalizePath(url) {
        if (!url || typeof url !== 'string') {
            return '';
        }
        var trimmed = url.trim();
        if (!trimmed) {
            return '';
        }
        var pathPart = trimmed;
        var schemeIdx = pathPart.indexOf('://');
        if (schemeIdx !== -1) {
            var afterScheme = pathPart.substring(schemeIdx + 3);
            var slashIdx = afterScheme.indexOf('/');
            pathPart = slashIdx !== -1 ? afterScheme.substring(slashIdx) : '/';
        }
        pathPart = pathPart.split('#')[0].split('?')[0];
        if (pathPart.length > 1 && pathPart.charAt(pathPart.length - 1) === '/') {
            pathPart = pathPart.substring(0, pathPart.length - 1);
        }
        return pathPart;
    }

    /**
     * Determines whether a decodable video frame is genuinely available for display.
     * Checks that the media is not actively seeking and has reached at least
     * HAVE_CURRENT_DATA (readyState >= 2).
     *
     * @param {HTMLMediaElement|Object} vid
     * @returns {boolean}
     */
    function isVideoFrameReady(vid) {
        if (!vid) {
            return false;
        }
        if (vid.seeking === true) {
            return false;
        }
        if (typeof vid.readyState === 'number') {
            return vid.readyState >= 2;
        }
        return false;
    }

    function setElementHidden(el, hidden) {
        if (!el) {
            return;
        }
        if (hidden) {
            if (typeof el.setAttribute === 'function') {
                el.setAttribute('hidden', '');
            }
            el.hidden = true;
        } else {
            if (typeof el.removeAttribute === 'function') {
                el.removeAttribute('hidden');
            }
            el.hidden = false;
        }
    }

    /**
     * Initializes the Screen Watch player controller.
     *
     * @param {Document|HTMLElement} [root] Optional container or document (defaults to global document)
     * @param {Object} [options] Optional overrides (storage, now, etc.)
     * @returns {Object|null} Controller instance or null if required elements are not present
     */
    function init(root, options) {
        var context = root || (typeof document !== 'undefined' ? document : null);
        if (!context || typeof context.querySelector !== 'function') {
            return null;
        }

        var opts = options || {};
        var video = context.querySelector('.screen-watch-video') || context.querySelector('video');
        var errorContainer = context.querySelector('#screenPlayerError') || context.querySelector('.screen-watch-player-error');

        if (!video || !errorContainer) {
            return null;
        }

        // Prevent duplicate listener attachment if init() is called repeatedly on the same video
        if (video._screenPlayerController && typeof video._screenPlayerController.destroy === 'function') {
            video._screenPlayerController.destroy();
        }

        var playerWrap = context.querySelector('#screenPlayerWrap')
            || context.querySelector('.screen-watch-player-wrap')
            || video.parentNode
            || null;

        var errorMessageEl = errorContainer.querySelector('#screenPlayerErrorMessage')
            || errorContainer.querySelector('.screen-watch-player-error-message');
        var retryBtn = errorContainer.querySelector('#screenPlayerRetryBtn')
            || errorContainer.querySelector('.screen-watch-player-retry-btn');

        // Optional UX01 controls and overlays
        var controlsContainer = context.querySelector('#screenPlayerControls')
            || context.querySelector('.screen-player-controls');
        var bufferingEl = context.querySelector('#screenPlayerBuffering');
        var seekFeedbackEl = context.querySelector('#screenPlayerSeekFeedback');
        var seekFeedbackTextEl = context.querySelector('#screenPlayerSeekFeedbackText')
            || (seekFeedbackEl && seekFeedbackEl.querySelector ? seekFeedbackEl.querySelector('span') : null);
        var bigPlayBtn = context.querySelector('#screenPlayerBigPlayBtn');

        var playPauseBtn = context.querySelector('#screenPlayerPlayPauseBtn');
        var playIcon = playPauseBtn && playPauseBtn.querySelector ? playPauseBtn.querySelector('.screen-player-icon--play') : null;
        var pauseIcon = playPauseBtn && playPauseBtn.querySelector ? playPauseBtn.querySelector('.screen-player-icon--pause') : null;

        var rewindBtn = context.querySelector('#screenPlayerRewindBtn');
        var forwardBtn = context.querySelector('#screenPlayerForwardBtn');

        var muteBtn = context.querySelector('#screenPlayerMuteBtn');
        var volumeIcon = muteBtn && muteBtn.querySelector ? muteBtn.querySelector('.screen-player-icon--volume') : null;
        var mutedIcon = muteBtn && muteBtn.querySelector ? muteBtn.querySelector('.screen-player-icon--muted') : null;
        var volumeSlider = context.querySelector('#screenPlayerVolumeSlider');

        var currentTimeEl = context.querySelector('#screenPlayerCurrentTime');
        var durationEl = context.querySelector('#screenPlayerDuration');
        var seekSlider = context.querySelector('#screenPlayerSeekSlider');
        var playedBar = context.querySelector('#screenPlayerPlayedBar');
        var bufferedBar = context.querySelector('#screenPlayerBufferedBar');

        var speedBtn = context.querySelector('#screenPlayerSpeedBtn');
        var speedLabel = context.querySelector('#screenPlayerSpeedLabel');
        var speedMenu = context.querySelector('#screenPlayerSpeedMenu');

        var settingsWrap = context.querySelector('#screenPlayerSettingsWrap')
            || context.querySelector('.screen-player-settings-wrap');
        var settingsBtn = context.querySelector('#screenPlayerSettingsBtn')
            || context.querySelector('.screen-player-settings-btn');
        var settingsMenu = context.querySelector('#screenPlayerSettingsMenu')
            || context.querySelector('.screen-player-settings-menu');
        var settingsSpeedBadge = context.querySelector('#screenPlayerSettingsSpeedBadge')
            || (settingsMenu && settingsMenu.querySelector ? settingsMenu.querySelector('.screen-player-settings-speed-badge') : null);

        function collectSpeedOptions() {
            var arr = [];
            if (speedMenu && typeof speedMenu.querySelectorAll === 'function') {
                var sm = speedMenu.querySelectorAll('.screen-player-speed-option');
                for (var i = 0; i < sm.length; i++) {
                    arr.push(sm[i]);
                }
            }
            if (settingsMenu && typeof settingsMenu.querySelectorAll === 'function') {
                var setm = settingsMenu.querySelectorAll('.screen-player-speed-option');
                for (var j = 0; j < setm.length; j++) {
                    arr.push(setm[j]);
                }
            }
            return arr;
        }
        var speedOptions = collectSpeedOptions();

        var pipBtn = context.querySelector('#screenPlayerPipBtn');
        var fullscreenBtn = context.querySelector('#screenPlayerFullscreenBtn');
        var expandIcon = fullscreenBtn && fullscreenBtn.querySelector ? fullscreenBtn.querySelector('.screen-player-icon--expand') : null;
        var compressIcon = fullscreenBtn && fullscreenBtn.querySelector ? fullscreenBtn.querySelector('.screen-player-icon--compress') : null;

        var resumeModal = context.querySelector('#screenPlayerResumeModal');
        var resumeTitleEl = context.querySelector('#screenPlayerResumeTitle');
        var resumeMessageEl = context.querySelector('#screenPlayerResumeMessage');
        var resumeConfirmBtn = context.querySelector('#screenPlayerResumeConfirmBtn');
        var resumeResetBtn = context.querySelector('#screenPlayerResumeResetBtn');

        var nextEpBtn = context.querySelector('#screenPlayerNextEpBtn');
        var nextUpPrompt = context.querySelector('#screenPlayerNextUpPrompt');
        var nextUpDismissBtn = context.querySelector('#screenPlayerNextUpDismissBtn');

        var posterEl = context.querySelector('#screenPlayerPoster')
            || context.querySelector('.screen-player-poster');
        var posterImgEl = posterEl && posterEl.querySelector
            ? (posterEl.querySelector('#screenPlayerPosterImg') || posterEl.querySelector('img'))
            : (context.querySelector('#screenPlayerPosterImg') || null);

        var sources = [];
        if (typeof video.querySelectorAll === 'function') {
            sources = video.querySelectorAll('source');
        } else if (video.getElementsByTagName) {
            sources = video.getElementsByTagName('source');
        }

        // Metadata & Identity
        var entryId = (playerWrap && typeof playerWrap.getAttribute === 'function' && playerWrap.getAttribute('data-entry-id')) || '';
        var entryLabel = (playerWrap && typeof playerWrap.getAttribute === 'function' && playerWrap.getAttribute('data-entry-label')) || '';
        var rawVideoSrc = (playerWrap && typeof playerWrap.getAttribute === 'function' && playerWrap.getAttribute('data-video-src'))
            || (sources.length > 0 && typeof sources[0].getAttribute === 'function' ? sources[0].getAttribute('src') : '')
            || video.currentSrc
            || video.src
            || '';
        var videoSrc = normalizeVideoSrc(rawVideoSrc);
        var attrDuration = playerWrap && typeof playerWrap.getAttribute === 'function'
            ? parseFloat(playerWrap.getAttribute('data-duration-seconds'))
            : NaN;

        var storage = opts.storage !== undefined
            ? opts.storage
            : (typeof window !== 'undefined' && window ? getSafeLocalStorage(window) : (typeof localStorage !== 'undefined' ? localStorage : null));
        var nowFn = typeof opts.now === 'function' ? opts.now : function () { return Date.now(); };

        var retrySeq = 0;
        var hasPlayedOrSeeked = false;
        var hasRenderedVideoFrame = false;
        var lastFailedPosterUrl = null;
        var lastKnownPosition = 0;
        var pendingSeekPosition = null;
        var pendingRetryRestorePosition = null;
        var lastSavedAt = 0;
        var resumePromptPending = false;
        var resumeCandidate = null;
        var resumePreviousActiveElement = null;
        var nextUpDismissed = false;
        var seekFeedbackTimer = null;
        var idleTimer = null;
        var touchTapTimer = null;
        var pendingTouchSide = null;
        var lastTouchTime = 0;
        var lastTouchSide = null;
        var TOUCH_DOUBLE_TAP_WINDOW_MS = (opts && typeof opts.touchDoubleTapWindowMs === 'number')
            ? opts.touchDoubleTapWindowMs
            : 280;

        function getSafeLocalStorage(win) {
            try {
                return win.localStorage || null;
            } catch (e) {
                return null;
            }
        }

        function getStorageKey() {
            if (!entryId) {
                return '';
            }
            return STORAGE_KEY_PREFIX + entryId;
        }

        function getEffectiveDuration() {
            if (typeof video.duration === 'number' && isFinite(video.duration) && video.duration > 0) {
                return video.duration;
            }
            if (video.seekable && typeof video.seekable.length === 'number' && video.seekable.length > 0 && typeof video.seekable.end === 'function') {
                try {
                    var seekableEnd = video.seekable.end(video.seekable.length - 1);
                    if (typeof seekableEnd === 'number' && isFinite(seekableEnd) && seekableEnd > 0) {
                        return seekableEnd;
                    }
                } catch (e) {
                    // Ignore seekable access error
                }
            }
            if (typeof attrDuration === 'number' && isFinite(attrDuration) && attrDuration > 0) {
                return attrDuration;
            }
            return null;
        }

        function getCurrentTimeSafe() {
            if (typeof video.currentTime === 'number' && isFinite(video.currentTime) && video.currentTime >= 0) {
                return video.currentTime;
            }
            return 0;
        }

        // Progressive Enhancement Setup
        if (playerWrap && playerWrap.classList) {
            playerWrap.classList.add('is-enhanced');
            if (video.paused !== false) {
                playerWrap.classList.add('is-paused');
            } else {
                playerWrap.classList.add('is-playing');
            }
        }
        if (controlsContainer) {
            video.controls = false;
            if (typeof video.removeAttribute === 'function') {
                video.removeAttribute('controls');
            }
        }

        // =====================================================================
        // Error & Retry Logic (MS-08G2 / G2-C03 preserved invariants)
        // =====================================================================

        function showError(customMessage) {
            var msg = customMessage || getErrorMessage(video.error);
            if (errorMessageEl) {
                errorMessageEl.textContent = msg;
            }
            setElementHidden(errorContainer, false);
            setBuffering(false);
        }

        function hideError() {
            setElementHidden(errorContainer, true);
        }

        function handleRetry() {
            var thisRetry = ++retrySeq;
            hideError();

            var restorePos = lastKnownPosition > 0 ? lastKnownPosition : getCurrentTimeSafe();
            if (restorePos > 0) {
                pendingRetryRestorePosition = restorePos;
            }

            if (typeof video.load === 'function') {
                try {
                    video.load();
                } catch (loadErr) {
                    if (thisRetry === retrySeq) {
                        showError('Không thể tải lại video. Vui lòng bấm Thử lại.');
                    }
                    return;
                }
            }

            if (pendingRetryRestorePosition !== null && pendingRetryRestorePosition > 0) {
                try {
                    video.currentTime = pendingRetryRestorePosition;
                } catch (seekErr) {
                    // Will be restored when loadedmetadata fires
                }
            }

            if (typeof video.play === 'function') {
                try {
                    var playPromise = video.play();
                    if (playPromise && typeof playPromise.catch === 'function') {
                        var chained = playPromise;
                        if (typeof chained.then === 'function') {
                            chained = chained.then(function () {
                                if (thisRetry === retrySeq) {
                                    hideError();
                                }
                            });
                        }
                        if (typeof chained.catch === 'function') {
                            chained.catch(function () {
                                if (thisRetry === retrySeq) {
                                    syncPlayState(false);
                                    showError('Không thể phát lại video. Vui lòng bấm Thử lại hoặc tương tác trực tiếp với trình phát.');
                                }
                            });
                        }
                    }
                } catch (playErr) {
                    if (thisRetry === retrySeq) {
                        syncPlayState(false);
                        showError('Không thể phát lại video. Vui lòng bấm Thử lại hoặc tương tác trực tiếp với trình phát.');
                    }
                }
            }
        }

        function onVideoError() {
            retrySeq++;
            var cur = getCurrentTimeSafe();
            if (cur > 0) {
                lastKnownPosition = cur;
            }
            if (hasPlayedOrSeeked && lastKnownPosition >= RESUME_MIN_SECONDS) {
                writeProgressRecord(lastKnownPosition);
            }
            showError();
        }

        function onSourceError() {
            retrySeq++;
            var cur = getCurrentTimeSafe();
            if (cur > 0) {
                lastKnownPosition = cur;
            }
            if (hasPlayedOrSeeked && lastKnownPosition >= RESUME_MIN_SECONDS) {
                writeProgressRecord(lastKnownPosition);
            }
            showError();
        }

        function hidePoster() {
            hasRenderedVideoFrame = true;
            if (posterEl) {
                setElementHidden(posterEl, true);
            }
        }

        function onPosterImgError() {
            if (!posterImgEl) {
                return;
            }
            var currentSrc = posterImgEl.src || (typeof posterImgEl.getAttribute === 'function' ? posterImgEl.getAttribute('src') : '') || '';
            var normCurrent = normalizePath(currentSrc);

            // Guard against duplicate handling of the same failed URL (e.g. dual error triggers or repeated events)
            if (normCurrent && normCurrent === lastFailedPosterUrl) {
                return;
            }
            lastFailedPosterUrl = normCurrent;

            var fallbackSrc = typeof posterImgEl.getAttribute === 'function' ? posterImgEl.getAttribute('data-fallback-src') : null;
            var secondarySrc = typeof posterImgEl.getAttribute === 'function' ? posterImgEl.getAttribute('data-secondary-fallback-src') : null;
            var triedFallback = typeof posterImgEl.getAttribute === 'function' ? posterImgEl.getAttribute('data-tried-fallback') : null;
            var triedSecondary = typeof posterImgEl.getAttribute === 'function' ? posterImgEl.getAttribute('data-tried-secondary') : null;

            var normFallback = normalizePath(fallbackSrc);
            var normSecondary = normalizePath(secondarySrc);

            if (!triedFallback && normFallback && normFallback !== normCurrent) {
                if (typeof posterImgEl.setAttribute === 'function') {
                    posterImgEl.setAttribute('data-tried-fallback', 'true');
                }
                posterImgEl.src = fallbackSrc;
                if (video) {
                    video.poster = fallbackSrc;
                }
            } else if (!triedSecondary && normSecondary && normSecondary !== normCurrent) {
                if (typeof posterImgEl.setAttribute === 'function') {
                    posterImgEl.setAttribute('data-tried-secondary', 'true');
                }
                posterImgEl.src = secondarySrc;
                if (video) {
                    video.poster = secondarySrc;
                }
            } else {
                if (posterImgEl.style) {
                    posterImgEl.style.display = 'none';
                }
            }
        }

        function onPosterImgLoad() {
            if (posterImgEl && video && posterImgEl.src) {
                video.poster = posterImgEl.src;
            }
        }

        function onRecovery() {
            retrySeq++;
            hideError();
            hidePoster();
            setBuffering(false);
            hasPlayedOrSeeked = true;
            syncPlayState(true);
        }

        function onRetryClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            handleRetry();
        }

        // =====================================================================
        // Play / Pause & Buffering State
        // =====================================================================

        function setBuffering(isBuffering) {
            setElementHidden(bufferingEl, !isBuffering);
        }

        function syncPlayState(isPlaying) {
            // Do NOT hide poster here: wait for decodable frame (playing / loadeddata / timeupdate)
            if (playerWrap && playerWrap.classList) {
                if (isPlaying) {
                    playerWrap.classList.add('is-playing');
                    playerWrap.classList.remove('is-paused');
                } else {
                    playerWrap.classList.add('is-paused');
                    playerWrap.classList.remove('is-playing');
                }
            }
            if (playPauseBtn && typeof playPauseBtn.setAttribute === 'function') {
                playPauseBtn.setAttribute('aria-label', isPlaying ? 'Tạm dừng' : 'Phát');
            }
            setElementHidden(playIcon, isPlaying);
            setElementHidden(pauseIcon, !isPlaying);
            setElementHidden(bigPlayBtn, isPlaying);
        }

        function playVideo() {
            if (typeof video.play !== 'function') {
                return;
            }
            try {
                var p = video.play();
                if (p && typeof p.then === 'function') {
                    p.then(function () {
                        hasPlayedOrSeeked = true;
                        syncPlayState(true);
                    }, function () {
                        syncPlayState(false);
                    });
                } else {
                    hasPlayedOrSeeked = true;
                    syncPlayState(true);
                }
            } catch (err) {
                syncPlayState(false);
            }
        }

        function pauseVideo() {
            if (typeof video.pause === 'function') {
                try {
                    video.pause();
                } catch (e) {
                    // Ignore pause error
                }
            }
            syncPlayState(false);
        }

        function togglePlay() {
            var isCurrentlyPaused = (typeof video.paused === 'boolean') ? video.paused : true;
            if (playerWrap && playerWrap.classList && playerWrap.classList.contains('is-playing') && typeof video.paused !== 'boolean') {
                isCurrentlyPaused = false;
            }
            if (isCurrentlyPaused) {
                playVideo();
            } else {
                pauseVideo();
            }
        }

        // =====================================================================
        // Seeking (-10s / +10s & Timeline)
        // =====================================================================

        function showSeekFeedback(label) {
            if (!seekFeedbackEl) {
                return;
            }
            if (seekFeedbackTextEl) {
                seekFeedbackTextEl.textContent = label;
            }
            setElementHidden(seekFeedbackEl, false);
            if (seekFeedbackTimer) {
                clearTimeout(seekFeedbackTimer);
            }
            seekFeedbackTimer = setTimeout(function () {
                setElementHidden(seekFeedbackEl, true);
                seekFeedbackTimer = null;
            }, 650);
        }

        function seekTo(targetSeconds) {
            if (typeof targetSeconds !== 'number' || !isFinite(targetSeconds)) {
                return getCurrentTimeSafe();
            }
            var dur = getEffectiveDuration();
            var clamped = Math.max(0, targetSeconds);
            if (dur !== null && dur > 0) {
                clamped = Math.min(dur, clamped);
            }
            if (!isFinite(clamped)) {
                return getCurrentTimeSafe();
            }
            try {
                video.currentTime = clamped;
            } catch (e) {
                pendingSeekPosition = clamped;
            }
            hasPlayedOrSeeked = true;
            lastKnownPosition = clamped;
            updateTimeline();
            return clamped;
        }

        function seekRelative(deltaSeconds) {
            if (typeof deltaSeconds !== 'number' || !isFinite(deltaSeconds)) {
                return getCurrentTimeSafe();
            }
            var cur = getCurrentTimeSafe();
            var dur = getEffectiveDuration();
            var target;
            if (deltaSeconds < 0) {
                target = Math.max(0, cur + deltaSeconds);
            } else {
                if (dur !== null && dur > 0) {
                    target = Math.min(dur, Math.max(0, cur + deltaSeconds));
                } else {
                    // Duration is unknown: avoid seeking past unknown end or producing NaN
                    target = cur;
                }
            }
            var nextPos = seekTo(target);
            saveProgressImmediate();
            return nextPos;
        }

        function updateTimeline() {
            var cur = getCurrentTimeSafe();
            var dur = getEffectiveDuration();

            if (currentTimeEl) {
                currentTimeEl.textContent = formatTime(cur);
            }
            if (durationEl) {
                if (dur !== null && dur > 0) {
                    durationEl.textContent = formatTime(dur);
                } else if (!durationEl.textContent || durationEl.textContent.trim() === '') {
                    durationEl.textContent = '--:--';
                }
            }

            var pct = (dur !== null && dur > 0)
                ? Math.min(100, Math.max(0, (cur / dur) * 100))
                : 0;

            if (playedBar && playedBar.style) {
                playedBar.style.width = pct + '%';
            }
            if (seekSlider) {
                var rounded = String(Math.round(pct * 10) / 10);
                seekSlider.value = rounded;
                if (typeof seekSlider.setAttribute === 'function') {
                    seekSlider.setAttribute('value', rounded);
                }
            }

            if (bufferedBar && bufferedBar.style) {
                var bufPct = 0;
                if (dur !== null && dur > 0 && video.buffered && typeof video.buffered.length === 'number' && video.buffered.length > 0 && typeof video.buffered.end === 'function') {
                    try {
                        var bufEnd = video.buffered.end(video.buffered.length - 1);
                        if (typeof bufEnd === 'number' && isFinite(bufEnd) && bufEnd >= 0) {
                            bufPct = Math.min(100, Math.max(0, (bufEnd / dur) * 100));
                        }
                    } catch (e) {
                        bufPct = 0;
                    }
                }
                bufferedBar.style.width = bufPct + '%';
            }
        }

        // =====================================================================
        // Volume & Mute
        // =====================================================================

        function syncVolumeUI() {
            var vol = (typeof video.volume === 'number' && isFinite(video.volume)) ? video.volume : 1;
            var isMuted = Boolean(video.muted) || vol === 0;
            if (muteBtn && typeof muteBtn.setAttribute === 'function') {
                muteBtn.setAttribute('aria-label', isMuted ? 'Bật tiếng' : 'Tắt tiếng');
            }
            setElementHidden(volumeIcon, isMuted);
            setElementHidden(mutedIcon, !isMuted);
            if (volumeSlider) {
                var sliderVal = isMuted ? '0' : String(vol);
                volumeSlider.value = sliderVal;
            }
        }

        function toggleMute() {
            video.muted = !video.muted;
            syncVolumeUI();
        }

        function setVolume(val) {
            var num = Number(val);
            if (!isFinite(num)) {
                return;
            }
            var clamped = Math.min(1, Math.max(0, num));
            video.volume = clamped;
            video.muted = (clamped === 0);
            syncVolumeUI();
        }

        // =====================================================================
        // Playback Speed (0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x)
        // =====================================================================

        function syncSpeedUI(rate) {
            var activeRate = (typeof rate === 'number' && ALLOWED_SPEEDS.indexOf(rate) !== -1) ? rate : 1;
            if (speedLabel) {
                speedLabel.textContent = activeRate + 'x';
            }
            if (settingsSpeedBadge) {
                settingsSpeedBadge.textContent = activeRate + 'x';
            }
            for (var i = 0; i < speedOptions.length; i++) {
                var opt = speedOptions[i];
                var optSpeed = parseFloat(opt.getAttribute ? opt.getAttribute('data-speed') : '');
                var isMatch = (optSpeed === activeRate);
                if (opt.classList) {
                    if (isMatch) {
                        opt.classList.add('is-active');
                    } else {
                        opt.classList.remove('is-active');
                    }
                }
                if (typeof opt.setAttribute === 'function') {
                    opt.setAttribute('aria-checked', isMatch ? 'true' : 'false');
                }
            }
        }

        function setSpeedMenuOpen(open) {
            setElementHidden(speedMenu, !open);
            if (speedBtn && typeof speedBtn.setAttribute === 'function') {
                speedBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
            }
            if (open) {
                setSettingsMenuOpen(false);
            }
        }

        function setSettingsMenuOpen(open) {
            setElementHidden(settingsMenu, !open);
            if (settingsBtn && typeof settingsBtn.setAttribute === 'function') {
                settingsBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
            }
            if (open) {
                setSpeedMenuOpen(false);
            }
        }

        function setPlaybackSpeed(rate) {
            var num = Number(rate);
            var validRate = ALLOWED_SPEEDS.indexOf(num) !== -1 ? num : 1;
            video.playbackRate = validRate;
            syncSpeedUI(validRate);
            setSpeedMenuOpen(false);
            setSettingsMenuOpen(false);
            if (storage && typeof storage.setItem === 'function') {
                try {
                    storage.setItem(SPEED_STORAGE_KEY, String(validRate));
                } catch (e) {
                    // Ignore quota/security errors
                }
            }
            return validRate;
        }

        function initSavedSpeed() {
            var initialSpeed = 1;
            if (storage && typeof storage.getItem === 'function') {
                try {
                    var raw = storage.getItem(SPEED_STORAGE_KEY);
                    if (raw !== null) {
                        var parsed = parseFloat(raw);
                        if (ALLOWED_SPEEDS.indexOf(parsed) !== -1) {
                            initialSpeed = parsed;
                        }
                    }
                } catch (e) {
                    initialSpeed = 1;
                }
            }
            video.playbackRate = initialSpeed;
            syncSpeedUI(initialSpeed);
        }

        // =====================================================================
        // Fullscreen & Picture-in-Picture
        // =====================================================================

        function getOwnerDoc() {
            if (context && (context.fullscreenElement !== undefined || context.exitFullscreen || context.webkitExitFullscreen)) {
                return context;
            }
            if (typeof document !== 'undefined') {
                return document;
            }
            return context;
        }

        function isFullscreenActive() {
            var doc = getOwnerDoc();
            return Boolean(
                (doc && (doc.fullscreenElement || doc.webkitFullscreenElement))
                || video.webkitDisplayingFullscreen
            );
        }

        function syncFullscreenUI() {
            var active = isFullscreenActive();
            setSpeedMenuOpen(false);
            setSettingsMenuOpen(false);
            if (playerWrap && playerWrap.classList) {
                if (active) {
                    playerWrap.classList.add('is-fullscreen');
                } else {
                    playerWrap.classList.remove('is-fullscreen');
                }
            }
            if (fullscreenBtn && typeof fullscreenBtn.setAttribute === 'function') {
                fullscreenBtn.setAttribute('aria-label', active ? 'Thoát toàn màn hình' : 'Toàn màn hình');
            }
            setElementHidden(expandIcon, active);
            setElementHidden(compressIcon, !active);
        }

        function toggleFullscreen() {
            var doc = getOwnerDoc();
            var target = playerWrap || video;
            try {
                if (isFullscreenActive()) {
                    if (doc && typeof doc.exitFullscreen === 'function') {
                        var exitP = doc.exitFullscreen();
                        if (exitP && typeof exitP.catch === 'function') {
                            exitP.catch(function () {});
                        }
                    } else if (doc && typeof doc.webkitExitFullscreen === 'function') {
                        doc.webkitExitFullscreen();
                    } else if (typeof video.webkitExitFullscreen === 'function') {
                        video.webkitExitFullscreen();
                    }
                } else {
                    if (target && typeof target.requestFullscreen === 'function') {
                        var reqP = target.requestFullscreen();
                        if (reqP && typeof reqP.catch === 'function') {
                            reqP.catch(function () {});
                        }
                    } else if (target && typeof target.webkitRequestFullscreen === 'function') {
                        target.webkitRequestFullscreen();
                    } else if (typeof video.webkitEnterFullscreen === 'function') {
                        video.webkitEnterFullscreen();
                    } else if (typeof video.requestFullscreen === 'function') {
                        var vidP = video.requestFullscreen();
                        if (vidP && typeof vidP.catch === 'function') {
                            vidP.catch(function () {});
                        }
                    }
                }
            } catch (e) {
                // Fail safely if fullscreen is blocked by browser permissions
            }
            syncFullscreenUI();
        }

        function initPipSupport() {
            if (!pipBtn) {
                return;
            }
            var doc = getOwnerDoc();
            var supported = Boolean(
                doc
                && doc.pictureInPictureEnabled
                && typeof video.requestPictureInPicture === 'function'
                && !video.disablePictureInPicture
            );
            setElementHidden(pipBtn, !supported);
        }

        function togglePip() {
            var doc = getOwnerDoc();
            if (!doc || typeof video.requestPictureInPicture !== 'function') {
                return;
            }
            try {
                if (doc.pictureInPictureElement) {
                    if (typeof doc.exitPictureInPicture === 'function') {
                        var exitP = doc.exitPictureInPicture();
                        if (exitP && typeof exitP.catch === 'function') {
                            exitP.catch(function () {});
                        }
                    }
                } else {
                    var reqP = video.requestPictureInPicture();
                    if (reqP && typeof reqP.catch === 'function') {
                        reqP.catch(function () {});
                    }
                }
            } catch (e) {
                // Ignore PiP rejection
            }
        }

        // =====================================================================
        // Resume Watching (localStorage Persistence & Dialog)
        // =====================================================================

        function readProgressRecord() {
            var key = getStorageKey();
            if (!key || !storage || typeof storage.getItem !== 'function') {
                return null;
            }
            try {
                var raw = storage.getItem(key);
                if (!raw) {
                    return null;
                }
                var parsed = JSON.parse(raw);
                if (!parsed || typeof parsed !== 'object') {
                    return null;
                }
                if (parsed.entryId !== entryId) {
                    return null;
                }
                if (normalizeVideoSrc(parsed.videoSrc) !== videoSrc || !videoSrc) {
                    // Video source changed (e.g. replaced Media asset) -> clear stale record
                    clearSavedProgress();
                    return null;
                }
                var pos = Number(parsed.positionSeconds);
                if (!isFinite(pos) || pos < 0) {
                    return null;
                }
                return {
                    entryId: String(parsed.entryId),
                    videoSrc: normalizeVideoSrc(parsed.videoSrc),
                    positionSeconds: Math.floor(pos),
                    durationSeconds: isFinite(Number(parsed.durationSeconds)) && Number(parsed.durationSeconds) > 0
                        ? Math.floor(Number(parsed.durationSeconds))
                        : null,
                    updatedAt: Number(parsed.updatedAt) || 0
                };
            } catch (e) {
                return null;
            }
        }

        function writeProgressRecord(positionSeconds) {
            var key = getStorageKey();
            if (!key || !videoSrc || !storage || typeof storage.setItem !== 'function') {
                return false;
            }
            if (typeof positionSeconds !== 'number' || !isFinite(positionSeconds) || positionSeconds <= 0) {
                return false;
            }
            var dur = getEffectiveDuration();
            if (dur !== null && dur > RESUME_MIN_SECONDS + RESUME_END_MARGIN_SECONDS && positionSeconds >= dur - RESUME_END_MARGIN_SECONDS) {
                clearSavedProgress();
                return false;
            }
            var payload = {
                entryId: entryId,
                videoSrc: videoSrc,
                positionSeconds: Math.floor(positionSeconds),
                durationSeconds: dur !== null ? Math.floor(dur) : null,
                updatedAt: nowFn()
            };
            try {
                storage.setItem(key, JSON.stringify(payload));
                lastSavedAt = payload.updatedAt;
                return true;
            } catch (e) {
                return false;
            }
        }

        function clearSavedProgress() {
            var key = getStorageKey();
            if (!key || !storage || typeof storage.removeItem !== 'function') {
                return;
            }
            try {
                storage.removeItem(key);
            } catch (e) {
                // Ignore storage error
            }
        }

        function saveProgressThrottled() {
            if (!hasPlayedOrSeeked || resumePromptPending) {
                return false;
            }
            var cur = getCurrentTimeSafe();
            if (cur <= 0) {
                return false;
            }
            lastKnownPosition = cur;
            var now = nowFn();
            if (now - lastSavedAt < SAVE_THROTTLE_MS) {
                return false;
            }
            return writeProgressRecord(cur);
        }

        function saveProgressImmediate() {
            if (!hasPlayedOrSeeked || resumePromptPending) {
                return false;
            }
            var cur = getCurrentTimeSafe();
            if (cur <= 0) {
                return false;
            }
            lastKnownPosition = cur;
            return writeProgressRecord(cur);
        }

        function isEligibleForResume(record) {
            if (!record || typeof record.positionSeconds !== 'number' || !isFinite(record.positionSeconds)) {
                return false;
            }
            if (record.positionSeconds < RESUME_MIN_SECONDS) {
                return false;
            }
            var dur = getEffectiveDuration() || record.durationSeconds;
            if (typeof dur === 'number' && isFinite(dur) && dur > 0) {
                if (record.positionSeconds >= dur - RESUME_END_MARGIN_SECONDS) {
                    return false;
                }
            }
            return true;
        }

        function showResumeModal(record) {
            if (!resumeModal || !record) {
                return;
            }
            resumeCandidate = record;
            resumePromptPending = true;
            if (resumeTitleEl && entryLabel) {
                resumeTitleEl.textContent = 'Tiếp tục xem ' + entryLabel + '?';
            }
            if (resumeMessageEl) {
                resumeMessageEl.textContent = 'Lần trước bạn đã xem đến ' + formatTime(record.positionSeconds) + '.';
            }
            setElementHidden(resumeModal, false);

            var activeEl = null;
            if (context && typeof context.activeElement !== 'undefined') {
                activeEl = context.activeElement;
            } else if (typeof document !== 'undefined') {
                activeEl = document.activeElement;
            }
            if (activeEl && activeEl !== resumeConfirmBtn && activeEl !== resumeResetBtn) {
                resumePreviousActiveElement = activeEl;
            }

            if (resumeConfirmBtn && typeof resumeConfirmBtn.focus === 'function') {
                try {
                    resumeConfirmBtn.focus();
                } catch (e) {
                    // Ignore focus error in headless/restricted environment
                }
            }
        }

        function returnFocusAfterDismissal() {
            var target = null;
            if (playPauseBtn && typeof playPauseBtn.focus === 'function') {
                target = playPauseBtn;
            } else if (bigPlayBtn && !bigPlayBtn.hidden && typeof bigPlayBtn.focus === 'function') {
                target = bigPlayBtn;
            } else if (playerWrap && typeof playerWrap.focus === 'function') {
                target = playerWrap;
            } else if (resumePreviousActiveElement && typeof resumePreviousActiveElement.focus === 'function') {
                target = resumePreviousActiveElement;
            }
            if (target && typeof target.focus === 'function') {
                try {
                    target.focus();
                } catch (e) {
                    // Ignore focus error
                }
            }
            resumePreviousActiveElement = null;
        }

        function hideResumeModal() {
            resumePromptPending = false;
            setElementHidden(resumeModal, true);
        }

        function dismissResumeModal() {
            hideResumeModal();
            returnFocusAfterDismissal();
        }

        function onResumeModalKeyDown(e) {
            if (e && e.key === 'Escape') {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                if (typeof e.stopPropagation === 'function') {
                    e.stopPropagation();
                }
                dismissResumeModal();
            }
        }

        function evaluateInitialResume() {
            if (!resumeModal) {
                return;
            }
            var record = readProgressRecord();
            if (!record) {
                hideResumeModal();
                return;
            }
            if (isEligibleForResume(record)) {
                showResumeModal(record);
            } else {
                hideResumeModal();
            }
        }

        function handleResumeConfirm(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            var targetPos = resumeCandidate ? resumeCandidate.positionSeconds : 0;
            hideResumeModal();
            returnFocusAfterDismissal();
            if (targetPos > 0) {
                seekTo(targetPos);
                pendingSeekPosition = targetPos;
            }
            playVideo();
        }

        function handleResumeReset(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            hideResumeModal();
            returnFocusAfterDismissal();
            resumeCandidate = null;
            pendingSeekPosition = null;
            lastKnownPosition = 0;
            clearSavedProgress();
            seekTo(0);
            playVideo();
        }

        // =====================================================================
        // Next Episode Near-End Suggestion
        // =====================================================================

        function evaluateNextUpPrompt() {
            if (!nextUpPrompt || nextUpDismissed) {
                return;
            }
            var dur = getEffectiveDuration();
            var cur = getCurrentTimeSafe();
            if (video.ended) {
                setElementHidden(nextUpPrompt, false);
                return;
            }
            if (dur !== null && dur >= NEXT_UP_MIN_DURATION_SECONDS && cur > 0 && cur >= dur - NEXT_UP_WINDOW_SECONDS) {
                setElementHidden(nextUpPrompt, false);
            } else {
                setElementHidden(nextUpPrompt, true);
            }
        }

        function onNextUpDismiss(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            nextUpDismissed = true;
            setElementHidden(nextUpPrompt, true);
        }

        // =====================================================================
        // Keyboard Shortcuts & Strict Text-Field Isolation
        // =====================================================================

        function isEditableTarget(target) {
            if (!target || typeof target !== 'object') {
                return false;
            }
            if (target.isContentEditable === true) {
                return true;
            }
            if (typeof target.getAttribute === 'function' && target.getAttribute('contenteditable') === 'true') {
                return true;
            }
            var tag = typeof target.tagName === 'string' ? target.tagName.toUpperCase() : '';
            if (tag === 'TEXTAREA' || tag === 'SELECT') {
                return true;
            }
            if (tag === 'INPUT') {
                var inputType = (typeof target.getAttribute === 'function' && target.getAttribute('type'))
                    || target.type
                    || 'text';
                var normalizedType = String(inputType).toLowerCase();
                // Range sliders inside the player are not text fields, but any text-like input is isolated
                if (normalizedType !== 'range') {
                    return true;
                }
            }
            // Also check if target is inside the comment section
            var node = target;
            while (node) {
                if (node.id === 'screenDiscussion' || (node.classList && node.classList.contains('screen-discussion-section'))) {
                    return true;
                }
                node = node.parentNode;
            }
            return false;
        }

        function handleKeyDown(e) {
            if (!e) {
                return false;
            }
            var target = e.target || (context && context.activeElement) || null;
            if (isEditableTarget(target)) {
                return false;
            }
            if (e.ctrlKey || e.altKey || e.metaKey) {
                return false;
            }

            var key = e.key || '';
            if (key === 'ArrowLeft') {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                seekRelative(-SEEK_STEP_SECONDS);
                showSeekFeedback('-10 giây');
                return true;
            }
            if (key === 'ArrowRight') {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                seekRelative(SEEK_STEP_SECONDS);
                showSeekFeedback('+10 giây');
                return true;
            }
            if (key === ' ' || key === 'Spacebar' || key === 'Space' || key === 'k' || key === 'K') {
                var targetTag = (target && typeof target.tagName === 'string') ? target.tagName.toUpperCase() : '';
                if (key !== 'k' && key !== 'K' && (targetTag === 'BUTTON' || targetTag === 'A')) {
                    // Let native button/link activation handle Space
                    return false;
                }
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                togglePlay();
                return true;
            }
            if (key === 'f' || key === 'F') {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                toggleFullscreen();
                return true;
            }
            if (key === 'm' || key === 'M') {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                toggleMute();
                return true;
            }
            if (key === 'Escape') {
                if (speedMenu && !speedMenu.hidden) {
                    setSpeedMenuOpen(false);
                    if (speedBtn && typeof speedBtn.focus === 'function') {
                        speedBtn.focus();
                    }
                    return true;
                }
                if (settingsMenu && !settingsMenu.hidden) {
                    setSettingsMenuOpen(false);
                    if (settingsBtn && typeof settingsBtn.focus === 'function') {
                        settingsBtn.focus();
                    }
                    return true;
                }
                if (resumeModal && !resumeModal.hidden) {
                    dismissResumeModal();
                    return true;
                }
            }
            return false;
        }

        // =====================================================================
        // Video Surface Click & Touch Double-Tap Seek
        // =====================================================================

        function wakeControls() {
            if (!playerWrap || !playerWrap.classList) {
                return;
            }
            playerWrap.classList.add('is-controls-visible');
            if (idleTimer) {
                clearTimeout(idleTimer);
            }
            idleTimer = setTimeout(function () {
                if (playerWrap && playerWrap.classList) {
                    playerWrap.classList.remove('is-controls-visible');
                }
                idleTimer = null;
            }, 3000);
        }

        function onVideoClick(e) {
            wakeControls();
            if (speedMenu && !speedMenu.hidden) {
                setSpeedMenuOpen(false);
                return;
            }
            if (settingsMenu && !settingsMenu.hidden) {
                setSettingsMenuOpen(false);
                return;
            }

            var isTouch = Boolean(e && (e.pointerType === 'touch' || e.isTouch === true));
            if (!isTouch) {
                // Mouse clicks must not acquire an unnecessary touch-gesture delay
                if (touchTapTimer) {
                    clearTimeout(touchTapTimer);
                    touchTapTimer = null;
                    pendingTouchSide = null;
                }
                togglePlay();
                return;
            }

            // Touch interaction on video surface
            var rect = typeof video.getBoundingClientRect === 'function'
                ? video.getBoundingClientRect()
                : { left: 0, width: (e && e.width) || 360 };
            var width = rect.width || 360;
            var clientX = (e && typeof e.clientX === 'number') ? e.clientX : (width * 0.5);
            var relX = clientX - (rect.left || 0);
            var side = relX < width * 0.4 ? 'left' : (relX > width * 0.6 ? 'right' : 'center');

            if (side === 'center') {
                if (touchTapTimer) {
                    clearTimeout(touchTapTimer);
                    touchTapTimer = null;
                    pendingTouchSide = null;
                }
                togglePlay();
                return;
            }

            var now = nowFn();
            if (touchTapTimer && pendingTouchSide === side && (now - lastTouchTime) <= TOUCH_DOUBLE_TAP_WINDOW_MS) {
                // Confirmed double-tap on same left/right zone within gesture window:
                // cancel pending single-tap action and seek without toggling play/pause!
                clearTimeout(touchTapTimer);
                touchTapTimer = null;
                pendingTouchSide = null;
                lastTouchTime = 0;

                if (side === 'left') {
                    seekRelative(-SEEK_STEP_SECONDS);
                    showSeekFeedback('-10 giây');
                } else {
                    seekRelative(SEEK_STEP_SECONDS);
                    showSeekFeedback('+10 giây');
                }
                return;
            }

            if (touchTapTimer) {
                clearTimeout(touchTapTimer);
                touchTapTimer = null;
                pendingTouchSide = null;
                togglePlay();
            }

            lastTouchTime = now;
            pendingTouchSide = side;
            touchTapTimer = setTimeout(function () {
                touchTapTimer = null;
                pendingTouchSide = null;
                lastTouchTime = 0;
                togglePlay();
            }, TOUCH_DOUBLE_TAP_WINDOW_MS);
        }

        // =====================================================================
        // Video & Control Event Listeners
        // =====================================================================

        function onPlay() {
            hasPlayedOrSeeked = true;
            // First-frame visibility: poster is NOT hidden here.
            // It remains visible while buffering until decodable frame is ready (playing, loadeddata while playing, or timeupdate).
            syncPlayState(true);
        }

        function onLoadedData() {
            if (video.paused === false) {
                hidePoster();
            }
        }

        function onPause() {
            syncPlayState(false);
            setBuffering(false);
            saveProgressImmediate();
        }

        function onWaiting() {
            setBuffering(true);
        }

        function onCanPlay() {
            setBuffering(false);
            if (!hasRenderedVideoFrame && (hasPlayedOrSeeked || (typeof video.currentTime === 'number' && video.currentTime > 0)) && isVideoFrameReady(video)) {
                hidePoster();
            }
        }

        function onSeeked() {
            if (!hasRenderedVideoFrame && (hasPlayedOrSeeked || (typeof video.currentTime === 'number' && video.currentTime > 0)) && isVideoFrameReady(video)) {
                hidePoster();
            }
        }

        function onLoadedMetadata() {
            if (pendingRetryRestorePosition !== null && pendingRetryRestorePosition > 0) {
                var restoreTarget = pendingRetryRestorePosition;
                pendingRetryRestorePosition = null;
                seekTo(restoreTarget);
            } else if (pendingSeekPosition !== null && pendingSeekPosition > 0) {
                var seekTarget = pendingSeekPosition;
                pendingSeekPosition = null;
                seekTo(seekTarget);
            } else if (resumeCandidate && resumePromptPending) {
                if (!isEligibleForResume(resumeCandidate)) {
                    hideResumeModal();
                }
            } else if (!hasPlayedOrSeeked) {
                evaluateInitialResume();
            }
            updateTimeline();
        }

        function onTimeUpdate() {
            var cur = getCurrentTimeSafe();
            if (cur > 0) {
                lastKnownPosition = cur;
                if (hasRenderedVideoFrame || (video.paused === false && isVideoFrameReady(video))) {
                    hidePoster();
                }
            }
            updateTimeline();
            saveProgressThrottled();
            evaluateNextUpPrompt();
        }

        function onProgress() {
            updateTimeline();
        }

        function onDurationChange() {
            updateTimeline();
            if (resumeCandidate && resumePromptPending && !isEligibleForResume(resumeCandidate)) {
                hideResumeModal();
            }
        }

        function onEnded() {
            syncPlayState(false);
            setBuffering(false);
            clearSavedProgress();
            evaluateNextUpPrompt();
        }

        function onVolumeChange() {
            syncVolumeUI();
        }

        function onRateChange() {
            if (typeof video.playbackRate === 'number' && isFinite(video.playbackRate)) {
                syncSpeedUI(video.playbackRate);
            }
        }

        function onPlayPauseBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            togglePlay();
        }

        function onRewindBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            seekRelative(-SEEK_STEP_SECONDS);
            showSeekFeedback('-10 giây');
        }

        function onForwardBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            seekRelative(SEEK_STEP_SECONDS);
            showSeekFeedback('+10 giây');
        }

        function onSeekSliderInput() {
            if (!seekSlider) {
                return;
            }
            var dur = getEffectiveDuration();
            if (dur === null || dur <= 0) {
                return;
            }
            var pct = parseFloat(seekSlider.value);
            if (!isFinite(pct)) {
                return;
            }
            var target = (Math.min(100, Math.max(0, pct)) / 100) * dur;
            seekTo(target);
            saveProgressImmediate();
        }

        function onMuteBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            toggleMute();
        }

        function onVolumeSliderInput() {
            if (!volumeSlider) {
                return;
            }
            setVolume(volumeSlider.value);
        }

        function onSpeedBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            if (e && typeof e.stopPropagation === 'function') {
                e.stopPropagation();
            }
            var isOpen = speedMenu ? !speedMenu.hidden : false;
            setSpeedMenuOpen(!isOpen);
        }

        function onSettingsBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            if (e && typeof e.stopPropagation === 'function') {
                e.stopPropagation();
            }
            var isOpen = settingsMenu ? !settingsMenu.hidden : false;
            setSettingsMenuOpen(!isOpen);
        }

        function onSpeedOptionClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            if (e && typeof e.stopPropagation === 'function') {
                e.stopPropagation();
            }
            var btn = (e && (e.currentTarget || e.target)) || null;
            if (!btn || typeof btn.getAttribute !== 'function') {
                return;
            }
            var rate = parseFloat(btn.getAttribute('data-speed'));
            setPlaybackSpeed(rate);
        }

        function onDocumentClick(e) {
            var target = e && e.target;
            if (!target) {
                return;
            }
            if (speedMenu && !speedMenu.hidden) {
                var isSpeedBtn = speedBtn && (speedBtn === target || (typeof speedBtn.contains === 'function' && speedBtn.contains(target)));
                var isSpeedMenu = speedMenu === target || (typeof speedMenu.contains === 'function' && speedMenu.contains(target));
                if (!isSpeedBtn && !isSpeedMenu) {
                    setSpeedMenuOpen(false);
                }
            }
            if (settingsMenu && !settingsMenu.hidden) {
                var isSettingsBtn = settingsBtn && (settingsBtn === target || (typeof settingsBtn.contains === 'function' && settingsBtn.contains(target)));
                var isSettingsMenu = settingsMenu === target || (typeof settingsMenu.contains === 'function' && settingsMenu.contains(target));
                if (!isSettingsBtn && !isSettingsMenu) {
                    setSettingsMenuOpen(false);
                }
            }
        }

        function onFullscreenBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            toggleFullscreen();
        }

        function onPipBtnClick(e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            togglePip();
        }

        function onPageLifecycleSave() {
            saveProgressImmediate();
        }

        // Attach video event listeners (strictly 1 'error' and 1 'playing' listener for G2 compatibility)
        if (typeof video.addEventListener === 'function') {
            video.addEventListener('error', onVideoError);
            video.addEventListener('playing', onRecovery);
            video.addEventListener('loadeddata', onLoadedData);
            video.addEventListener('play', onPlay);
            video.addEventListener('pause', onPause);
            video.addEventListener('waiting', onWaiting);
            video.addEventListener('canplay', onCanPlay);
            video.addEventListener('seeked', onSeeked);
            video.addEventListener('loadedmetadata', onLoadedMetadata);
            video.addEventListener('timeupdate', onTimeUpdate);
            video.addEventListener('progress', onProgress);
            video.addEventListener('durationchange', onDurationChange);
            video.addEventListener('ended', onEnded);
            video.addEventListener('volumechange', onVolumeChange);
            video.addEventListener('ratechange', onRateChange);
            video.addEventListener('click', onVideoClick);
        }

        for (var i = 0; i < sources.length; i++) {
            if (typeof sources[i].addEventListener === 'function') {
                sources[i].addEventListener('error', onSourceError);
            }
        }

        if (retryBtn && typeof retryBtn.addEventListener === 'function') {
            retryBtn.addEventListener('click', onRetryClick);
        }
        if (bigPlayBtn && typeof bigPlayBtn.addEventListener === 'function') {
            bigPlayBtn.addEventListener('click', onPlayPauseBtnClick);
        }
        if (playPauseBtn && typeof playPauseBtn.addEventListener === 'function') {
            playPauseBtn.addEventListener('click', onPlayPauseBtnClick);
        }
        if (rewindBtn && typeof rewindBtn.addEventListener === 'function') {
            rewindBtn.addEventListener('click', onRewindBtnClick);
        }
        if (forwardBtn && typeof forwardBtn.addEventListener === 'function') {
            forwardBtn.addEventListener('click', onForwardBtnClick);
        }
        if (seekSlider && typeof seekSlider.addEventListener === 'function') {
            seekSlider.addEventListener('input', onSeekSliderInput);
            seekSlider.addEventListener('change', onSeekSliderInput);
        }
        if (muteBtn && typeof muteBtn.addEventListener === 'function') {
            muteBtn.addEventListener('click', onMuteBtnClick);
        }
        if (volumeSlider && typeof volumeSlider.addEventListener === 'function') {
            volumeSlider.addEventListener('input', onVolumeSliderInput);
            volumeSlider.addEventListener('change', onVolumeSliderInput);
        }
        if (speedBtn && typeof speedBtn.addEventListener === 'function') {
            speedBtn.addEventListener('click', onSpeedBtnClick);
        }
        if (settingsBtn && typeof settingsBtn.addEventListener === 'function') {
            settingsBtn.addEventListener('click', onSettingsBtnClick);
        }
        for (var k = 0; k < speedOptions.length; k++) {
            if (typeof speedOptions[k].addEventListener === 'function') {
                speedOptions[k].addEventListener('click', onSpeedOptionClick);
            }
        }
        if (fullscreenBtn && typeof fullscreenBtn.addEventListener === 'function') {
            fullscreenBtn.addEventListener('click', onFullscreenBtnClick);
        }
        if (pipBtn && typeof pipBtn.addEventListener === 'function') {
            pipBtn.addEventListener('click', onPipBtnClick);
        }
        if (resumeConfirmBtn && typeof resumeConfirmBtn.addEventListener === 'function') {
            resumeConfirmBtn.addEventListener('click', handleResumeConfirm);
        }
        if (resumeResetBtn && typeof resumeResetBtn.addEventListener === 'function') {
            resumeResetBtn.addEventListener('click', handleResumeReset);
        }
        if (resumeModal && typeof resumeModal.addEventListener === 'function') {
            resumeModal.addEventListener('keydown', onResumeModalKeyDown);
        }
        if (nextUpDismissBtn && typeof nextUpDismissBtn.addEventListener === 'function') {
            nextUpDismissBtn.addEventListener('click', onNextUpDismiss);
        }
        if (playerWrap && typeof playerWrap.addEventListener === 'function') {
            playerWrap.addEventListener('keydown', handleKeyDown);
            playerWrap.addEventListener('mousemove', wakeControls);
        }

        var ownerDoc = getOwnerDoc();
        if (ownerDoc && typeof ownerDoc.addEventListener === 'function') {
            ownerDoc.addEventListener('fullscreenchange', syncFullscreenUI);
            ownerDoc.addEventListener('webkitfullscreenchange', syncFullscreenUI);
            ownerDoc.addEventListener('visibilitychange', onPageLifecycleSave);
            ownerDoc.addEventListener('click', onDocumentClick);
        }
        var globalWin = typeof window !== 'undefined' ? window : null;
        if (globalWin && typeof globalWin.addEventListener === 'function') {
            globalWin.addEventListener('pagehide', onPageLifecycleSave);
            globalWin.addEventListener('beforeunload', onPageLifecycleSave);
        }

        // Initialize UI State
        initSavedSpeed();
        initPipSupport();
        syncVolumeUI();
        updateTimeline();
        evaluateInitialResume();

        if (posterImgEl) {
            // Neutralize any legacy or residual inline onerror handler
            posterImgEl.onerror = null;
            if (typeof posterImgEl.removeAttribute === 'function') {
                posterImgEl.removeAttribute('onerror');
            }

            if (typeof posterImgEl.addEventListener === 'function') {
                posterImgEl.addEventListener('error', onPosterImgError);
                posterImgEl.addEventListener('load', onPosterImgLoad);
            }

            // Early-load check: if image already completed before init() ran
            if (posterImgEl.complete) {
                if (typeof posterImgEl.naturalWidth === 'number' && posterImgEl.naturalWidth === 0) {
                    onPosterImgError();
                } else if (typeof posterImgEl.naturalWidth === 'number' && posterImgEl.naturalWidth > 0) {
                    onPosterImgLoad();
                }
            }
        }
        if (isVideoFrameReady(video) && (video.paused === false || (typeof video.currentTime === 'number' && video.currentTime > 0))) {
            hidePoster();
        }

        function destroy() {
            if (seekFeedbackTimer) {
                clearTimeout(seekFeedbackTimer);
                seekFeedbackTimer = null;
            }
            if (idleTimer) {
                clearTimeout(idleTimer);
                idleTimer = null;
            }
            if (touchTapTimer) {
                clearTimeout(touchTapTimer);
                touchTapTimer = null;
            }
            pendingTouchSide = null;
            lastTouchTime = 0;

            if (typeof video.removeEventListener === 'function') {
                video.removeEventListener('error', onVideoError);
                video.removeEventListener('playing', onRecovery);
                video.removeEventListener('loadeddata', onLoadedData);
                video.removeEventListener('play', onPlay);
                video.removeEventListener('pause', onPause);
                video.removeEventListener('waiting', onWaiting);
                video.removeEventListener('canplay', onCanPlay);
                video.removeEventListener('seeked', onSeeked);
                video.removeEventListener('loadedmetadata', onLoadedMetadata);
                video.removeEventListener('timeupdate', onTimeUpdate);
                video.removeEventListener('progress', onProgress);
                video.removeEventListener('durationchange', onDurationChange);
                video.removeEventListener('ended', onEnded);
                video.removeEventListener('volumechange', onVolumeChange);
                video.removeEventListener('ratechange', onRateChange);
                video.removeEventListener('click', onVideoClick);
            }
            for (var j = 0; j < sources.length; j++) {
                if (typeof sources[j].removeEventListener === 'function') {
                    sources[j].removeEventListener('error', onSourceError);
                }
            }
            if (retryBtn && typeof retryBtn.removeEventListener === 'function') {
                retryBtn.removeEventListener('click', onRetryClick);
            }
            if (bigPlayBtn && typeof bigPlayBtn.removeEventListener === 'function') {
                bigPlayBtn.removeEventListener('click', onPlayPauseBtnClick);
            }
            if (playPauseBtn && typeof playPauseBtn.removeEventListener === 'function') {
                playPauseBtn.removeEventListener('click', onPlayPauseBtnClick);
            }
            if (rewindBtn && typeof rewindBtn.removeEventListener === 'function') {
                rewindBtn.removeEventListener('click', onRewindBtnClick);
            }
            if (forwardBtn && typeof forwardBtn.removeEventListener === 'function') {
                forwardBtn.removeEventListener('click', onForwardBtnClick);
            }
            if (seekSlider && typeof seekSlider.removeEventListener === 'function') {
                seekSlider.removeEventListener('input', onSeekSliderInput);
                seekSlider.removeEventListener('change', onSeekSliderInput);
            }
            if (muteBtn && typeof muteBtn.removeEventListener === 'function') {
                muteBtn.removeEventListener('click', onMuteBtnClick);
            }
            if (volumeSlider && typeof volumeSlider.removeEventListener === 'function') {
                volumeSlider.removeEventListener('input', onVolumeSliderInput);
                volumeSlider.removeEventListener('change', onVolumeSliderInput);
            }
            if (speedBtn && typeof speedBtn.removeEventListener === 'function') {
                speedBtn.removeEventListener('click', onSpeedBtnClick);
            }
            if (settingsBtn && typeof settingsBtn.removeEventListener === 'function') {
                settingsBtn.removeEventListener('click', onSettingsBtnClick);
            }
            for (var m = 0; m < speedOptions.length; m++) {
                if (typeof speedOptions[m].removeEventListener === 'function') {
                    speedOptions[m].removeEventListener('click', onSpeedOptionClick);
                }
            }
            if (fullscreenBtn && typeof fullscreenBtn.removeEventListener === 'function') {
                fullscreenBtn.removeEventListener('click', onFullscreenBtnClick);
            }
            if (pipBtn && typeof pipBtn.removeEventListener === 'function') {
                pipBtn.removeEventListener('click', onPipBtnClick);
            }
            if (resumeConfirmBtn && typeof resumeConfirmBtn.removeEventListener === 'function') {
                resumeConfirmBtn.removeEventListener('click', handleResumeConfirm);
            }
            if (resumeResetBtn && typeof resumeResetBtn.removeEventListener === 'function') {
                resumeResetBtn.removeEventListener('click', handleResumeReset);
            }
            if (resumeModal && typeof resumeModal.removeEventListener === 'function') {
                resumeModal.removeEventListener('keydown', onResumeModalKeyDown);
            }
            if (nextUpDismissBtn && typeof nextUpDismissBtn.removeEventListener === 'function') {
                nextUpDismissBtn.removeEventListener('click', onNextUpDismiss);
            }
            if (playerWrap && typeof playerWrap.removeEventListener === 'function') {
                playerWrap.removeEventListener('keydown', handleKeyDown);
                playerWrap.removeEventListener('mousemove', wakeControls);
            }
            if (ownerDoc && typeof ownerDoc.removeEventListener === 'function') {
                ownerDoc.removeEventListener('fullscreenchange', syncFullscreenUI);
                ownerDoc.removeEventListener('webkitfullscreenchange', syncFullscreenUI);
                ownerDoc.removeEventListener('visibilitychange', onPageLifecycleSave);
                ownerDoc.removeEventListener('click', onDocumentClick);
            }
            if (globalWin && typeof globalWin.removeEventListener === 'function') {
                globalWin.removeEventListener('pagehide', onPageLifecycleSave);
                globalWin.removeEventListener('beforeunload', onPageLifecycleSave);
            }
            if (posterImgEl && typeof posterImgEl.removeEventListener === 'function') {
                posterImgEl.removeEventListener('error', onPosterImgError);
                posterImgEl.removeEventListener('load', onPosterImgLoad);
            }

            if (controlsContainer) {
                video.controls = true;
                if (typeof video.setAttribute === 'function') {
                    video.setAttribute('controls', '');
                }
            }
            if (playerWrap && playerWrap.classList) {
                playerWrap.classList.remove('is-enhanced');
            }
            video._screenPlayerController = null;
        }

        var controller = {
            video: video,
            playerWrap: playerWrap,
            errorContainer: errorContainer,
            errorMessageEl: errorMessageEl,
            retryBtn: retryBtn,
            resumeModal: resumeModal,
            nextEpBtn: nextEpBtn,
            nextUpPrompt: nextUpPrompt,
            showError: showError,
            hideError: hideError,
            handleRetry: handleRetry,
            getErrorMessage: getErrorMessage,
            formatTime: formatTime,
            togglePlay: togglePlay,
            seekTo: seekTo,
            seekRelative: seekRelative,
            setPlaybackSpeed: setPlaybackSpeed,
            speedBtn: speedBtn,
            speedMenu: speedMenu,
            setSpeedMenuOpen: setSpeedMenuOpen,
            isSpeedMenuOpen: function () { return speedMenu ? !speedMenu.hidden : false; },
            settingsBtn: settingsBtn,
            settingsMenu: settingsMenu,
            setSettingsMenuOpen: setSettingsMenuOpen,
            isSettingsMenuOpen: function () { return settingsMenu ? !settingsMenu.hidden : false; },
            pipBtn: pipBtn,
            fullscreenBtn: fullscreenBtn,
            toggleMute: toggleMute,
            setVolume: setVolume,
            toggleFullscreen: toggleFullscreen,
            handleKeyDown: handleKeyDown,
            saveProgressImmediate: saveProgressImmediate,
            clearSavedProgress: clearSavedProgress,
            getLastKnownPosition: function () { return lastKnownPosition; },
            getPosterElement: function () { return posterEl; },
            getPosterImgElement: function () { return posterImgEl; },
            hasRenderedFirstFrame: function () { return hasRenderedVideoFrame; },
            isVideoFrameReady: function () { return isVideoFrameReady(video); },
            hidePoster: hidePoster,
            destroy: destroy
        };

        video._screenPlayerController = controller;
        return controller;
    }

    return {
        init: init,
        getErrorMessage: getErrorMessage,
        formatTime: formatTime,
        normalizeVideoSrc: normalizeVideoSrc,
        normalizePath: normalizePath,
        isVideoFrameReady: isVideoFrameReady,
        ALLOWED_SPEEDS: ALLOWED_SPEEDS,
        STORAGE_KEY_PREFIX: STORAGE_KEY_PREFIX,
        SPEED_STORAGE_KEY: SPEED_STORAGE_KEY,
        MEDIA_ERR_ABORTED: MEDIA_ERR_ABORTED,
        MEDIA_ERR_NETWORK: MEDIA_ERR_NETWORK,
        MEDIA_ERR_DECODE: MEDIA_ERR_DECODE,
        MEDIA_ERR_SRC_NOT_SUPPORTED: MEDIA_ERR_SRC_NOT_SUPPORTED
    };
}));
