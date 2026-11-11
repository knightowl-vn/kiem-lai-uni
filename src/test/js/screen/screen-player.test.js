const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const ScreenPlayer = require(path.join(__dirname, '../../../main/resources/static/js/screen/screen-player.js'));

function createMockElement(tagName, attrs = {}, textContent = '') {
    const listeners = {};
    const attributes = { ...attrs };
    const children = [];
    const classNames = new Set((attrs.class || '').split(/\s+/).filter(Boolean));

    const element = {
        tagName: tagName.toUpperCase(),
        id: attrs.id || '',
        style: {},
        value: attrs.value !== undefined ? String(attrs.value) : '',
        classList: {
            add: (c) => classNames.add(c),
            remove: (c) => classNames.delete(c),
            contains: (c) => classNames.has(c)
        },
        hidden: 'hidden' in attributes,
        textContent: textContent,
        children: children,
        parentNode: null,
        complete: false,
        naturalWidth: 0,
        get src() {
            return attributes['src'];
        },
        set src(v) {
            attributes['src'] = String(v);
        },
        getAttribute: (k) => (k in attributes ? attributes[k] : null),
        setAttribute: (k, v) => {
            attributes[k] = String(v);
            if (k === 'hidden') element.hidden = true;
            if (k === 'id') element.id = String(v);
            if (k === 'value') element.value = String(v);
            if (k === 'class') {
                classNames.clear();
                String(v).split(/\s+/).filter(Boolean).forEach(c => classNames.add(c));
            }
        },
        removeAttribute: (k) => {
            delete attributes[k];
            if (k === 'hidden') element.hidden = false;
        },
        addEventListener: (type, handler) => {
            listeners[type] = listeners[type] || [];
            listeners[type].push(handler);
        },
        removeEventListener: (type, handler) => {
            if (listeners[type]) {
                listeners[type] = listeners[type].filter(h => h !== handler);
            }
        },
        dispatchEvent: (evt) => {
            const type = typeof evt === 'string' ? evt : evt.type;
            const onProp = 'on' + type;
            if (typeof element[onProp] === 'function') {
                try {
                    element[onProp](evt);
                } catch (e) {
                    // Ignore inline handler error in test mock
                }
            }
            const handlers = listeners[type] ? [...listeners[type]] : [];
            for (const h of handlers) {
                h(evt);
            }
        },
        getListeners: (type) => listeners[type] || [],
        querySelector: (sel) => {
            function search(node) {
                for (const child of node.children) {
                    if (matches(child, sel)) return child;
                    const found = search(child);
                    if (found) return found;
                }
                return null;
            }
            return search(element);
        },
        querySelectorAll: (sel) => {
            const matchesArr = [];
            function search(node) {
                for (const child of node.children) {
                    if (matches(child, sel)) matchesArr.push(child);
                    search(child);
                }
            }
            search(element);
            return matchesArr;
        },
        appendChild: (child) => {
            child.parentNode = element;
            children.push(child);
            return child;
        },
        contains: (other) => {
            if (!other) return false;
            if (other === element) return true;
            let current = other.parentNode;
            while (current) {
                if (current === element) return true;
                current = current.parentNode;
            }
            return false;
        },
        focus: function () {
            let root = element;
            while (root.parentNode) {
                root = root.parentNode;
            }
            root.activeElement = element;
        }
    };

    function matches(el, sel) {
        if (sel.startsWith('#')) return el.id === sel.slice(1);
        if (sel.startsWith('.')) return el.classList.contains(sel.slice(1));
        if (sel.toLowerCase() === el.tagName.toLowerCase()) return true;
        return false;
    }

    return element;
}

function createDOMFixture() {
    const doc = createMockElement('document');
    const wrap = createMockElement('div', { class: 'screen-watch-player-wrap' });
    const video = createMockElement('video', { class: 'screen-watch-video' });
    const source = createMockElement('source', { src: '/media/assets/123/content', type: 'video/mp4' });
    video.appendChild(source);

    // Mock HTMLMediaElement methods with native read-only paused property
    let _isPaused = true;
    Object.defineProperty(video, 'paused', {
        get: () => _isPaused,
        set: () => {
            throw new TypeError('Cannot set property paused of #<HTMLMediaElement> which has only a getter');
        },
        configurable: true
    });
    video._playCalled = 0;
    video._pauseCalled = 0;
    video._loadCalled = 0;
    video.load = () => { video._loadCalled = (video._loadCalled || 0) + 1; };
    video.play = () => {
        video._playCalled = (video._playCalled || 0) + 1;
        if (video._playShouldReject) {
            _isPaused = true;
            return Promise.reject(new Error('Autoplay prevented'));
        }
        _isPaused = false;
        return Promise.resolve();
    };
    video.pause = () => {
        video._pauseCalled = (video._pauseCalled || 0) + 1;
        _isPaused = true;
    };
    video.error = null;
    video.seeking = false;
    video.readyState = 0;

    const errorContainer = createMockElement('div', {
        id: 'screenPlayerError',
        class: 'screen-watch-player-error',
        role: 'alert',
        'aria-live': 'assertive',
        hidden: ''
    });

    const errorTitle = createMockElement('h2', { class: 'screen-watch-player-error-title' }, 'Không thể phát video');
    const errorMessage = createMockElement('p', {
        id: 'screenPlayerErrorMessage',
        class: 'screen-watch-player-error-message'
    }, 'Đã xảy ra lỗi.');
    const retryBtn = createMockElement('button', {
        id: 'screenPlayerRetryBtn',
        class: 'screen-watch-player-retry-btn',
        type: 'button',
        'aria-label': 'Thử lại phát video'
    }, 'Thử lại');

    errorContainer.appendChild(errorTitle);
    errorContainer.appendChild(errorMessage);
    errorContainer.appendChild(retryBtn);

    wrap.appendChild(video);
    wrap.appendChild(errorContainer);
    doc.appendChild(wrap);

    return {
        doc,
        wrap,
        video,
        source,
        errorContainer,
        errorMessage,
        retryBtn
    };
}

function createMemoryStorage(initial = {}) {
    const store = { ...initial };
    return {
        store,
        getItem(k) {
            return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null;
        },
        setItem(k, v) {
            store[k] = String(v);
        },
        removeItem(k) {
            delete store[k];
        }
    };
}

function createUX01Fixture(customAttrs = {}) {
    const base = createDOMFixture();
    const { doc, wrap, video } = base;

    wrap.setAttribute('id', 'screenPlayerWrap');
    wrap.setAttribute('data-title-id', customAttrs.titleId || 'title-001');
    wrap.setAttribute('data-entry-id', customAttrs.entryId !== undefined ? customAttrs.entryId : 'entry-ep-1');
    wrap.setAttribute('data-entry-slug', customAttrs.entrySlug || 'tap-1');
    wrap.setAttribute('data-entry-type', customAttrs.entryType || 'EPISODE');
    wrap.setAttribute('data-entry-label', customAttrs.entryLabel || 'Tập 1');
    wrap.setAttribute('data-video-src', customAttrs.videoSrc || '/media/assets/123/content');
    if (customAttrs.durationSeconds !== undefined) {
        wrap.setAttribute('data-duration-seconds', String(customAttrs.durationSeconds));
    }
    if (customAttrs.hasOutro !== undefined) {
        wrap.setAttribute('data-has-outro', customAttrs.hasOutro ? 'true' : 'false');
    }
    if (customAttrs.outroStartSeconds !== undefined) {
        wrap.setAttribute('data-outro-start-seconds', String(customAttrs.outroStartSeconds));
    }
    if (customAttrs.outroEndSeconds !== undefined) {
        wrap.setAttribute('data-outro-end-seconds', String(customAttrs.outroEndSeconds));
    }
    if (customAttrs.nextUrl !== undefined) {
        wrap.setAttribute('data-next-url', customAttrs.nextUrl);
    } else if (customAttrs.hasNext !== false) {
        wrap.setAttribute('data-next-url', '/screen/kiem-lai-phan-1/watch/tap-2');
    }
    if (customAttrs.nextLabel !== undefined) {
        wrap.setAttribute('data-next-label', customAttrs.nextLabel || 'Tập 2');
    }
    if (customAttrs.hasNext !== undefined) {
        wrap.setAttribute('data-has-next', customAttrs.hasNext ? 'true' : 'false');
    }

    let _isPaused = customAttrs.paused !== undefined ? Boolean(customAttrs.paused) : true;
    Object.defineProperty(video, 'paused', {
        get: () => _isPaused,
        set: () => {
            throw new TypeError('Cannot set property paused of #<HTMLMediaElement> which has only a getter');
        },
        configurable: true
    });

    video.setAttribute('controls', '');
    video.controls = true;
    video.currentTime = customAttrs.videoCurrentTime !== undefined ? customAttrs.videoCurrentTime : 0;
    video.duration = customAttrs.videoDuration !== undefined ? customAttrs.videoDuration : 1576;
    video.volume = 1;
    video.muted = false;
    video.seeking = customAttrs.videoSeeking !== undefined ? customAttrs.videoSeeking : false;
    video.readyState = customAttrs.videoReadyState !== undefined
        ? customAttrs.videoReadyState
        : (customAttrs.videoCurrentTime !== undefined && customAttrs.videoCurrentTime > 0 ? 2 : 0);
    video.playbackRate = 1;
    video.load = () => { video._loadCalled = (video._loadCalled || 0) + 1; };
    video.play = () => {
        video._playCalled = (video._playCalled || 0) + 1;
        if (video._playShouldReject) {
            _isPaused = true;
            return Promise.reject(new Error('Autoplay prevented'));
        }
        _isPaused = false;
        return Promise.resolve();
    };
    video.pause = () => {
        video._pauseCalled = (video._pauseCalled || 0) + 1;
        _isPaused = true;
    };

    const buffering = createMockElement('div', { id: 'screenPlayerBuffering', class: 'screen-player-buffering', hidden: '' });
    const seekFeedback = createMockElement('div', { id: 'screenPlayerSeekFeedback', class: 'screen-player-seek-feedback', hidden: '' });
    const seekFeedbackText = createMockElement('span', { id: 'screenPlayerSeekFeedbackText' }, '10 giây');
    seekFeedback.appendChild(seekFeedbackText);

    const bigPlayBtn = createMockElement('button', { id: 'screenPlayerBigPlayBtn', class: 'screen-player-big-play' });

    const resumeModal = createMockElement('div', { id: 'screenPlayerResumeModal', class: 'screen-player-resume-modal', role: 'dialog', 'aria-modal': 'false', hidden: '' });
    const resumeTitle = createMockElement('h2', { id: 'screenPlayerResumeTitle' }, 'Tiếp tục xem Tập 1?');
    const resumeMessage = createMockElement('p', { id: 'screenPlayerResumeMessage' }, 'Lần trước bạn đã xem đến 0:00.');
    const resumeConfirmBtn = createMockElement('button', { id: 'screenPlayerResumeConfirmBtn', type: 'button' }, 'Xem tiếp');
    const resumeResetBtn = createMockElement('button', { id: 'screenPlayerResumeResetBtn', type: 'button' }, 'Xem từ đầu');
    resumeModal.appendChild(resumeTitle);
    resumeModal.appendChild(resumeMessage);
    resumeModal.appendChild(resumeConfirmBtn);
    resumeModal.appendChild(resumeResetBtn);

    let nextUpPrompt = null;
    let nextUpDismissBtn = null;
    let nextEpBtn = null;
    if (customAttrs.hasNext !== false) {
        nextUpPrompt = createMockElement('div', { id: 'screenPlayerNextUpPrompt', class: 'screen-player-next-up', hidden: '' });
        nextUpDismissBtn = createMockElement('button', { id: 'screenPlayerNextUpDismissBtn' });
        nextUpPrompt.appendChild(nextUpDismissBtn);
        wrap.appendChild(nextUpPrompt);
    }

    const controls = createMockElement('div', { id: 'screenPlayerControls', class: 'screen-player-controls' });
    const bufferedBar = createMockElement('div', { id: 'screenPlayerBufferedBar', class: 'screen-player-buffered-bar' });
    const playedBar = createMockElement('div', { id: 'screenPlayerPlayedBar', class: 'screen-player-played-bar' });
    const seekSlider = createMockElement('input', { id: 'screenPlayerSeekSlider', type: 'range', value: '0' });

    const playPauseBtn = createMockElement('button', { id: 'screenPlayerPlayPauseBtn', 'aria-label': 'Phát' });
    const playIcon = createMockElement('svg', { class: 'screen-player-icon screen-player-icon--play' });
    const pauseIcon = createMockElement('svg', { class: 'screen-player-icon screen-player-icon--pause', hidden: '' });
    playPauseBtn.appendChild(playIcon);
    playPauseBtn.appendChild(pauseIcon);

    const rewindBtn = createMockElement('button', { id: 'screenPlayerRewindBtn', 'aria-label': 'Tua lùi 10 giây' });
    const forwardBtn = createMockElement('button', { id: 'screenPlayerForwardBtn', 'aria-label': 'Tua tới 10 giây' });

    const muteBtn = createMockElement('button', { id: 'screenPlayerMuteBtn', 'aria-label': 'Tắt tiếng' });
    const volumeIcon = createMockElement('svg', { class: 'screen-player-icon screen-player-icon--volume' });
    const mutedIcon = createMockElement('svg', { class: 'screen-player-icon screen-player-icon--muted', hidden: '' });
    muteBtn.appendChild(volumeIcon);
    muteBtn.appendChild(mutedIcon);
    const volumeSlider = createMockElement('input', { id: 'screenPlayerVolumeSlider', type: 'range', value: '1' });

    const currentTimeEl = createMockElement('span', { id: 'screenPlayerCurrentTime' }, '0:00');
    const durationEl = createMockElement('span', { id: 'screenPlayerDuration' }, '--:--');

    const speedBtn = createMockElement('button', { id: 'screenPlayerSpeedBtn', 'aria-expanded': 'false' });
    const speedLabel = createMockElement('span', { id: 'screenPlayerSpeedLabel' }, '1x');
    speedBtn.appendChild(speedLabel);

    const speedMenu = createMockElement('div', { id: 'screenPlayerSpeedMenu', hidden: '' });
    const speedOptionButtons = {};
    [0.5, 0.75, 1, 1.25, 1.5, 2].forEach(s => {
        const opt = createMockElement('button', {
            class: s === 1 ? 'screen-player-speed-option is-active' : 'screen-player-speed-option',
            'data-speed': String(s),
            'aria-checked': s === 1 ? 'true' : 'false'
        }, s + '×');
        speedMenu.appendChild(opt);
        speedOptionButtons[s] = opt;
    });

    const settingsWrap = createMockElement('div', { id: 'screenPlayerSettingsWrap', class: 'screen-player-settings-wrap' });
    const settingsBtn = createMockElement('button', {
        id: 'screenPlayerSettingsBtn',
        class: 'screen-player-settings-btn',
        'aria-label': 'Cài đặt',
        'aria-haspopup': 'true',
        'aria-expanded': 'false',
        title: 'Cài đặt'
    });
    const settingsMenu = createMockElement('div', {
        id: 'screenPlayerSettingsMenu',
        class: 'screen-player-settings-menu',
        role: 'menu',
        'aria-label': 'Cài đặt phát video',
        hidden: ''
    });
    const settingsSpeedBadge = createMockElement('span', {
        id: 'screenPlayerSettingsSpeedBadge',
        class: 'screen-player-settings-speed-badge'
    }, '1x');
    settingsMenu.appendChild(settingsSpeedBadge);
    const settingsSpeedOptionButtons = {};
    [0.5, 0.75, 1, 1.25, 1.5, 2].forEach(s => {
        const opt = createMockElement('button', {
            class: s === 1 ? 'screen-player-speed-option is-active' : 'screen-player-speed-option',
            'data-speed': String(s),
            'aria-checked': s === 1 ? 'true' : 'false'
        }, s + '×');
        settingsMenu.appendChild(opt);
        settingsSpeedOptionButtons[s] = opt;
    });
    settingsWrap.appendChild(settingsBtn);
    settingsWrap.appendChild(settingsMenu);

    const pipBtn = createMockElement('button', { id: 'screenPlayerPipBtn', hidden: '' });
    const fullscreenBtn = createMockElement('button', { id: 'screenPlayerFullscreenBtn', 'aria-label': 'Toàn màn hình' });
    const expandIcon = createMockElement('svg', { class: 'screen-player-icon screen-player-icon--expand' });
    const compressIcon = createMockElement('svg', { class: 'screen-player-icon screen-player-icon--compress', hidden: '' });
    fullscreenBtn.appendChild(expandIcon);
    fullscreenBtn.appendChild(compressIcon);

    controls.appendChild(bufferedBar);
    controls.appendChild(playedBar);
    controls.appendChild(seekSlider);
    controls.appendChild(playPauseBtn);
    controls.appendChild(rewindBtn);
    controls.appendChild(forwardBtn);
    controls.appendChild(muteBtn);
    controls.appendChild(volumeSlider);
    controls.appendChild(currentTimeEl);
    controls.appendChild(durationEl);
    if (customAttrs.hasNext !== false) {
        nextEpBtn = createMockElement('a', {
            id: 'screenPlayerNextEpBtn',
            href: '/screen/kiem-lai-phan-1/watch/tap-2'
        }, 'Tập tiếp theo');
        controls.appendChild(nextEpBtn);
    }
    controls.appendChild(speedBtn);
    controls.appendChild(speedMenu);
    controls.appendChild(settingsWrap);
    controls.appendChild(pipBtn);
    controls.appendChild(fullscreenBtn);

    let poster = null;
    let posterImg = null;
    if (customAttrs.hasStageImage !== false) {
        poster = createMockElement('div', { id: 'screenPlayerPoster', class: 'screen-player-poster' });
        posterImg = createMockElement('img', {
            id: 'screenPlayerPosterImg',
            class: 'screen-player-poster-img',
            src: customAttrs.posterSrc !== undefined ? customAttrs.posterSrc : '/media/assets/thumb-asset-1/variants/w600',
            'data-fallback-src': customAttrs.posterFallbackSrc !== undefined ? customAttrs.posterFallbackSrc : '/media/assets/thumb-asset-1/content',
            'data-secondary-fallback-src': customAttrs.posterSecondarySrc !== undefined ? customAttrs.posterSecondarySrc : '/media/assets/backdrop-asset-1/content'
        });
        poster.appendChild(posterImg);
        wrap.appendChild(poster);
    }

    const skipOutroBtn = createMockElement('button', {
        id: 'screenPlayerSkipOutroBtn',
        class: 'screen-player-skip-outro-btn',
        hidden: ''
    }, 'Bỏ qua phần kết');

    let nextOutroLink = null;
    if (customAttrs.hasNext !== false && customAttrs.includeNextOutroLink !== false) {
        nextOutroLink = createMockElement('a', {
            id: 'screenPlayerNextOutroLink',
            class: 'screen-player-skip-outro-btn screen-player-next-outro-link',
            href: customAttrs.nextUrl || '/screen/kiem-lai-phan-1/watch/tap-2',
            'aria-label': 'Tập tiếp theo',
            title: 'Tập tiếp theo',
            hidden: ''
        }, 'Tập tiếp theo');
        wrap.appendChild(nextOutroLink);
    }

    wrap.appendChild(buffering);
    wrap.appendChild(seekFeedback);
    wrap.appendChild(bigPlayBtn);
    wrap.appendChild(resumeModal);
    wrap.appendChild(skipOutroBtn);
    wrap.appendChild(controls);

    return {
        ...base,
        poster,
        posterImg,
        buffering,
        seekFeedback,
        seekFeedbackText,
        bigPlayBtn,
        resumeModal,
        resumeTitle,
        resumeMessage,
        resumeConfirmBtn,
        resumeResetBtn,
        nextUpPrompt,
        nextUpDismissBtn,
        skipOutroBtn,
        nextOutroLink,
        nextEpBtn,
        controls,
        bufferedBar,
        playedBar,
        seekSlider,
        playPauseBtn,
        playIcon,
        pauseIcon,
        rewindBtn,
        forwardBtn,
        muteBtn,
        volumeIcon,
        mutedIcon,
        volumeSlider,
        currentTimeEl,
        durationEl,
        speedBtn,
        speedLabel,
        speedMenu,
        speedOptionButtons,
        settingsWrap,
        settingsBtn,
        settingsMenu,
        settingsSpeedBadge,
        settingsSpeedOptionButtons,
        pipBtn,
        fullscreenBtn,
        expandIcon,
        compressIcon
    };
}

describe('ScreenPlayer — Native HTML5 Video Player Controller (MS-08G2)', () => {

    test('1. Initialization with valid DOM elements attaches event listeners', () => {
        const fixture = createDOMFixture();
        const controller = ScreenPlayer.init(fixture.doc);

        assert.ok(controller, 'ScreenPlayer.init should return controller object');
        assert.strictEqual(controller.video, fixture.video);
        assert.strictEqual(controller.errorContainer, fixture.errorContainer);

        assert.strictEqual(fixture.video.getListeners('error').length, 1);
        assert.strictEqual(fixture.video.getListeners('playing').length, 1);
        assert.strictEqual(fixture.source.getListeners('error').length, 1);
        assert.strictEqual(fixture.retryBtn.getListeners('click').length, 1);
    });

    test('2. Initialization with missing elements exits safely without throwing', () => {
        assert.strictEqual(ScreenPlayer.init(null), null);
        assert.strictEqual(ScreenPlayer.init({}), null);

        const emptyDoc = createMockElement('document');
        assert.strictEqual(ScreenPlayer.init(emptyDoc), null);

        const docWithoutError = createMockElement('document');
        docWithoutError.appendChild(createMockElement('video', { class: 'screen-watch-video' }));
        assert.strictEqual(ScreenPlayer.init(docWithoutError), null);
    });

    test('3. Initial state has error container hidden', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.errorContainer.hidden, true);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), '');
    });

    test('4. Video error with MEDIA_ERR_NETWORK (code 2) displays network error message and removes hidden', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        fixture.video.error = { code: 2 };
        fixture.video.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Mất kết nối mạng trong quá trình phát video. Vui lòng kiểm tra đường truyền.'
        );
    });

    test('5. Video error with MEDIA_ERR_DECODE (code 3) displays decode error message and removes hidden', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        fixture.video.error = { code: 3 };
        fixture.video.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Định dạng video hoặc âm thanh không được trình duyệt này hỗ trợ.'
        );
    });

    test('6. Video error with MEDIA_ERR_SRC_NOT_SUPPORTED (code 4) displays src not supported message and removes hidden', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        fixture.video.error = { code: 4 };
        fixture.video.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể tải video từ máy chủ hoặc định dạng chưa sẵn sàng.'
        );
    });

    test('7. Video error with MEDIA_ERR_ABORTED (code 1) displays aborted message and removes hidden', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        fixture.video.error = { code: 1 };
        fixture.video.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Quá trình tải video đã bị hủy. Vui lòng thử lại.'
        );
    });

    test('8. Video error with null video.error displays fallback general message', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        fixture.video.error = null;
        fixture.video.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể tải video. Vui lòng kiểm tra kết nối mạng hoặc thử lại.'
        );
    });

    test('9. Source error event triggers error overlay with fallback message when video.error is null', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        fixture.video.error = null;
        fixture.source.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể tải video. Vui lòng kiểm tra kết nối mạng hoặc thử lại.'
        );
    });

    test('10. Clicking retry button calls video.load() and video.play(), and re-hides the error container', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        // First trigger an error
        fixture.video.error = { code: 2 };
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // Click retry
        let prevented = false;
        fixture.retryBtn.dispatchEvent({
            type: 'click',
            preventDefault: () => { prevented = true; }
        });

        assert.strictEqual(prevented, true, 'preventDefault should be called on click event');
        assert.strictEqual(fixture.errorContainer.hidden, true, 'Error container should be hidden after retry');
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), '');
        assert.strictEqual(fixture.video._loadCalled, 1, 'video.load() should have been called');
        assert.strictEqual(fixture.video._playCalled, 1, 'video.play() should have been called');
    });

    test('11. Play promise rejection restores actionable error notice', async () => {
        const fixture = createDOMFixture();
        fixture.video._playShouldReject = true;
        ScreenPlayer.init(fixture.doc);

        // 1. Dispatch a media network error
        fixture.video.error = { code: 2 };
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // 2. Click Retry
        fixture.retryBtn.dispatchEvent({
            type: 'click',
            preventDefault: () => {}
        });

        assert.strictEqual(fixture.video._loadCalled, 1);
        assert.strictEqual(fixture.video._playCalled, 1);

        // 3. Wait for the play promise rejection handler
        await Promise.resolve();
        await Promise.resolve();

        // 4. Assert error notice is restored and actionable
        assert.strictEqual(fixture.errorContainer.hidden, false, 'Error container should NOT remain hidden when retry play() rejects');
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể phát lại video. Vui lòng bấm Thử lại hoặc tương tác trực tiếp với trình phát.'
        );
        assert.ok(fixture.retryBtn, 'Retry button should remain available');
    });

    test('12. Authoritative recovery event (playing) hides the error container; readiness events do not', () => {
        const fixture = createDOMFixture();
        ScreenPlayer.init(fixture.doc);

        // Show error
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // loadeddata (readiness event) does NOT hide error
        fixture.video.dispatchEvent({ type: 'loadeddata' });
        assert.strictEqual(fixture.errorContainer.hidden, false, 'loadeddata must not hide error');

        // canplay (readiness event) does NOT hide error
        fixture.video.dispatchEvent({ type: 'canplay' });
        assert.strictEqual(fixture.errorContainer.hidden, false, 'canplay must not hide error');

        // playing event confirms genuine playback recovery and hides error
        fixture.video.dispatchEvent({ type: 'playing' });
        assert.strictEqual(fixture.errorContainer.hidden, true, 'playing event must hide error');
    });

    test('13. destroy() removes event listeners cleanly', () => {
        const fixture = createDOMFixture();
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.video.getListeners('error').length, 1);
        assert.strictEqual(fixture.video.getListeners('playing').length, 1);
        assert.strictEqual(fixture.source.getListeners('error').length, 1);
        assert.strictEqual(fixture.retryBtn.getListeners('click').length, 1);

        controller.destroy();

        assert.strictEqual(fixture.video.getListeners('error').length, 0);
        assert.strictEqual(fixture.video.getListeners('playing').length, 0);
        assert.strictEqual(fixture.source.getListeners('error').length, 0);
        assert.strictEqual(fixture.retryBtn.getListeners('click').length, 0);
    });

    test('14. Synchronous play exception remains safe and restores actionable error UI', () => {
        const fixture = createDOMFixture();
        fixture.video.play = () => {
            throw new Error('Immediate play error');
        };
        ScreenPlayer.init(fixture.doc);

        // Trigger error then retry
        fixture.video.dispatchEvent({ type: 'error' });
        assert.doesNotThrow(() => {
            fixture.retryBtn.dispatchEvent({
                type: 'click',
                preventDefault: () => {}
            });
        });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể phát lại video. Vui lòng bấm Thử lại hoặc tương tác trực tiếp với trình phát.'
        );
    });

    test('15. Synchronous load exception remains safe and restores actionable error UI', () => {
        const fixture = createDOMFixture();
        fixture.video.load = () => {
            throw new Error('Immediate load error');
        };
        ScreenPlayer.init(fixture.doc);

        // Trigger error then retry
        fixture.video.dispatchEvent({ type: 'error' });
        assert.doesNotThrow(() => {
            fixture.retryBtn.dispatchEvent({
                type: 'click',
                preventDefault: () => {}
            });
        });

        assert.strictEqual(fixture.errorContainer.hidden, false);
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể tải lại video. Vui lòng bấm Thử lại.'
        );
    });

    test('16. Multiple retry attempts do not create a stale-error race', async () => {
        const fixture = createDOMFixture();
        let firstPlayReject;
        const firstPlayPromise = new Promise((resolve, reject) => {
            firstPlayReject = reject;
        });

        let callCount = 0;
        fixture.video.play = () => {
            callCount++;
            if (callCount === 1) {
                return firstPlayPromise;
            }
            return Promise.resolve();
        };

        ScreenPlayer.init(fixture.doc);

        // Trigger initial error
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // Click Retry #1 (pending on firstPlayPromise)
        fixture.retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.errorContainer.hidden, true);

        // User clicks Retry #2 before #1 completes (callCount = 2, resolves immediately)
        fixture.retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await Promise.resolve();

        // Now firstPlayPromise rejects late
        firstPlayReject(new Error('Stale rejection from Retry #1'));
        await Promise.resolve();
        await Promise.resolve();

        // The error container MUST remain hidden because Retry #2 succeeded and stale rejection #1 is ignored
        assert.strictEqual(fixture.errorContainer.hidden, true, 'Stale rejection from older retry must NOT unhide the error container');
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), '');
    });

    test('17. Stale rejection does not override successful recovery event', async () => {
        const fixture = createDOMFixture();
        let playReject;
        fixture.video.play = () => new Promise((resolve, reject) => {
            playReject = reject;
        });

        ScreenPlayer.init(fixture.doc);

        // Initial error
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // Click retry
        fixture.retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.errorContainer.hidden, true);

        // Recovery occurs (e.g. video begins playing)
        fixture.video.dispatchEvent({ type: 'playing' });
        assert.strictEqual(fixture.errorContainer.hidden, true);

        // play() rejects after recovery
        playReject(new Error('Delayed rejection'));
        await Promise.resolve();
        await Promise.resolve();

        // Must remain hidden
        assert.strictEqual(fixture.errorContainer.hidden, true, 'Recovery must not be overridden by delayed play rejection');
    });

    test('18. G2-C03 Test A — loadeddata before play Promise rejection does not conceal failure', async () => {
        const fixture = createDOMFixture();
        let playReject;
        const pendingPlayPromise = new Promise((resolve, reject) => {
            playReject = reject;
        });

        fixture.video.play = () => {
            fixture.video._playCalled = (fixture.video._playCalled || 0) + 1;
            return pendingPlayPromise;
        };

        ScreenPlayer.init(fixture.doc);

        // 1. Dispatch a media error
        fixture.video.error = { code: 2 };
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // 2. Click Retry
        fixture.retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.errorContainer.hidden, true);

        // 3. Dispatch loadeddata (readiness event, NOT playback recovery)
        fixture.video.dispatchEvent({ type: 'loadeddata' });

        // 4. Reject the pending play Promise
        playReject(new Error('Playback failed after loadeddata'));
        await Promise.resolve();
        await Promise.resolve();

        // 5. Error notice must be restored and actionable
        assert.strictEqual(fixture.errorContainer.hidden, false, 'Error notice must be restored after play rejection despite loadeddata event');
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể phát lại video. Vui lòng bấm Thử lại hoặc tương tác trực tiếp với trình phát.'
        );
        assert.ok(fixture.retryBtn, 'Retry action must remain available');
    });

    test('19. G2-C03 Test B — canplay before play Promise rejection does not conceal failure', async () => {
        const fixture = createDOMFixture();
        let playReject;
        const pendingPlayPromise = new Promise((resolve, reject) => {
            playReject = reject;
        });

        fixture.video.play = () => {
            fixture.video._playCalled = (fixture.video._playCalled || 0) + 1;
            return pendingPlayPromise;
        };

        ScreenPlayer.init(fixture.doc);

        // 1. Dispatch a media error
        fixture.video.error = { code: 4 };
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // 2. Click Retry
        fixture.retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.errorContainer.hidden, true);

        // 3. Dispatch canplay (readiness event, NOT playback recovery)
        fixture.video.dispatchEvent({ type: 'canplay' });

        // 4. Reject the pending play Promise
        playReject(new Error('Playback failed after canplay'));
        await Promise.resolve();
        await Promise.resolve();

        // 5. Error notice must be restored and actionable
        assert.strictEqual(fixture.errorContainer.hidden, false, 'Error notice must be restored after play rejection despite canplay event');
        assert.strictEqual(fixture.errorContainer.getAttribute('hidden'), null);
        assert.strictEqual(
            fixture.errorMessage.textContent,
            'Không thể phát lại video. Vui lòng bấm Thử lại hoặc tương tác trực tiếp với trình phát.'
        );
        assert.ok(fixture.retryBtn, 'Retry action must remain available');
    });

});

describe('ScreenPlayer — Modern Player UX, Resume Watching & Next Episode (MS-08G2-UX01)', () => {

    test('UX01-1. Progressive enhancement initializes custom controls and hides native browser controls', () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();
        const controller = ScreenPlayer.init(fixture.doc, { storage });

        assert.ok(controller);
        assert.strictEqual(fixture.wrap.classList.contains('is-enhanced'), true);
        assert.strictEqual(fixture.wrap.classList.contains('is-paused'), true);
        assert.strictEqual(fixture.video.controls, false);
        assert.strictEqual(fixture.video.getAttribute('controls'), null);
        assert.strictEqual(fixture.durationEl.textContent, '26:16');

        controller.destroy();
        assert.strictEqual(fixture.wrap.classList.contains('is-enhanced'), false);
        assert.strictEqual(fixture.video.controls, true);
    });

    test('UX01-2. Play / Pause button and big play button toggle playback and reflect accurate state', async () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage });

        // Click play/pause button -> starts playing
        fixture.playPauseBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await Promise.resolve();

        assert.strictEqual(fixture.video._playCalled, 1);
        assert.strictEqual(fixture.wrap.classList.contains('is-playing'), true);
        assert.strictEqual(fixture.playPauseBtn.getAttribute('aria-label'), 'Tạm dừng');
        assert.strictEqual(fixture.playIcon.hidden, true);
        assert.strictEqual(fixture.pauseIcon.hidden, false);
        assert.strictEqual(fixture.bigPlayBtn.hidden, true);

        // Click again -> pauses
        fixture.playPauseBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video._pauseCalled, 1);
        assert.strictEqual(fixture.wrap.classList.contains('is-paused'), true);
        assert.strictEqual(fixture.playPauseBtn.getAttribute('aria-label'), 'Phát');
        assert.strictEqual(fixture.playIcon.hidden, false);
        assert.strictEqual(fixture.pauseIcon.hidden, true);
        assert.strictEqual(fixture.bigPlayBtn.hidden, false);
    });

    test('UX01-3. Rejected play() Promise on play button does not leave UI in false playing state', async () => {
        const fixture = createUX01Fixture();
        fixture.video._playShouldReject = true;
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage });

        fixture.bigPlayBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await Promise.resolve();
        await Promise.resolve();

        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.wrap.classList.contains('is-paused'), true);
        assert.strictEqual(fixture.wrap.classList.contains('is-playing'), false);
        assert.strictEqual(fixture.playPauseBtn.getAttribute('aria-label'), 'Phát');
    });

    test('UX01-4. Rewind 10s clamps to 0 near start and Forward 10s clamps to duration near end', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage });

        // Near start: 4s -> Rewind 10s -> 0s
        fixture.video.currentTime = 4;
        fixture.rewindBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video.currentTime, 0);
        assert.strictEqual(fixture.seekFeedback.hidden, false);
        assert.strictEqual(fixture.seekFeedbackText.textContent, '-10 giây');

        // Normal forward: 50s -> Forward 10s -> 60s
        fixture.video.currentTime = 50;
        fixture.forwardBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video.currentTime, 60);
        assert.strictEqual(fixture.seekFeedbackText.textContent, '+10 giây');

        // Near end: 1570s -> Forward 10s -> clamped to 1576s
        fixture.video.currentTime = 1570;
        fixture.forwardBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video.currentTime, 1576);
    });

    test('UX01-5. Forward / Rewind when duration is NaN or unknown never produces NaN', () => {
        const fixture = createUX01Fixture({ videoDuration: NaN });
        const storage = createMemoryStorage();
        const controller = ScreenPlayer.init(fixture.doc, { storage });

        fixture.video.currentTime = 5;
        fixture.forwardBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(Number.isFinite(fixture.video.currentTime), true);
        assert.strictEqual(fixture.video.currentTime, 5);

        fixture.rewindBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video.currentTime, 0);
        assert.strictEqual(fixture.durationEl.textContent, '--:--');
        controller.destroy();
    });

    test('UX01-6. Playback speed selector supports 0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x and persists across seeks', () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage });

        // Open speed menu
        fixture.speedBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.speedMenu.hidden, false);
        assert.strictEqual(fixture.speedBtn.getAttribute('aria-expanded'), 'true');

        for (const rate of [0.5, 0.75, 1, 1.25, 1.5, 2]) {
            const btn = fixture.speedOptionButtons[rate];
            btn.dispatchEvent({ type: 'click', currentTarget: btn, preventDefault: () => {} });
            assert.strictEqual(fixture.video.playbackRate, rate);
            assert.strictEqual(fixture.speedLabel.textContent, rate + 'x');
            assert.strictEqual(btn.getAttribute('aria-checked'), 'true');
            assert.strictEqual(btn.classList.contains('is-active'), true);
            assert.strictEqual(fixture.speedMenu.hidden, true);
        }

        // Seeking preserves playbackRate
        fixture.forwardBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video.playbackRate, 2);
        assert.strictEqual(storage.getItem(ScreenPlayer.SPEED_STORAGE_KEY), '2');
    });

    test('UX01-7. Keyboard shortcuts operate when player is focused and are strictly ignored in text fields / comments', async () => {
        const fixture = createUX01Fixture({ videoDuration: 1000 });
        const storage = createMemoryStorage();
        const controller = ScreenPlayer.init(fixture.doc, { storage });

        fixture.video.currentTime = 100;

        // ArrowRight -> +10s
        fixture.wrap.dispatchEvent({ type: 'keydown', key: 'ArrowRight', target: fixture.wrap, preventDefault: () => {} });
        assert.strictEqual(fixture.video.currentTime, 110);

        // ArrowLeft -> -10s
        fixture.wrap.dispatchEvent({ type: 'keydown', key: 'ArrowLeft', target: fixture.wrap, preventDefault: () => {} });
        assert.strictEqual(fixture.video.currentTime, 100);

        // M -> Mute toggle
        fixture.wrap.dispatchEvent({ type: 'keydown', key: 'm', target: fixture.wrap, preventDefault: () => {} });
        assert.strictEqual(fixture.video.muted, true);
        assert.strictEqual(fixture.muteBtn.getAttribute('aria-label'), 'Bật tiếng');

        // Space -> Play toggle
        fixture.wrap.dispatchEvent({ type: 'keydown', key: ' ', target: fixture.wrap, preventDefault: () => {} });
        await Promise.resolve();
        assert.strictEqual(fixture.video._playCalled, 1);

        // Escape closes open speed menu
        fixture.speedBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.speedMenu.hidden, false);
        fixture.wrap.dispatchEvent({ type: 'keydown', key: 'Escape', target: fixture.wrap });
        assert.strictEqual(fixture.speedMenu.hidden, true);

        // Now test strict non-interference with textarea, text input, and contenteditable
        const commentTextarea = createMockElement('textarea', { id: 'screenRootComposerInput' });
        const searchInput = createMockElement('input', { type: 'text' });
        const editableDiv = createMockElement('div', { contenteditable: 'true' });

        const prevTime = fixture.video.currentTime;
        const prevMuted = fixture.video.muted;
        const prevPlayCalls = fixture.video._playCalled;

        assert.strictEqual(controller.handleKeyDown({ key: 'ArrowRight', target: commentTextarea }), false);
        assert.strictEqual(controller.handleKeyDown({ key: ' ', target: commentTextarea }), false);
        assert.strictEqual(controller.handleKeyDown({ key: 'm', target: searchInput }), false);
        assert.strictEqual(controller.handleKeyDown({ key: 'f', target: editableDiv }), false);

        assert.strictEqual(fixture.video.currentTime, prevTime);
        assert.strictEqual(fixture.video.muted, prevMuted);
        assert.strictEqual(fixture.video._playCalled, prevPlayCalls);
    });

    test('UX01-8. Fullscreen action uses standard API when supported and falls back gracefully on mobile', () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();
        let reqFsCalled = 0;
        let exitFsCalled = 0;

        fixture.wrap.requestFullscreen = () => {
            reqFsCalled++;
            fixture.doc.fullscreenElement = fixture.wrap;
            return Promise.resolve();
        };
        fixture.doc.exitFullscreen = () => {
            exitFsCalled++;
            fixture.doc.fullscreenElement = null;
            return Promise.resolve();
        };

        ScreenPlayer.init(fixture.doc, { storage });

        // Enter fullscreen
        fixture.fullscreenBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(reqFsCalled, 1);
        assert.strictEqual(fixture.wrap.classList.contains('is-fullscreen'), true);
        assert.strictEqual(fixture.fullscreenBtn.getAttribute('aria-label'), 'Thoát toàn màn hình');

        // Exit fullscreen
        fixture.fullscreenBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(exitFsCalled, 1);
        assert.strictEqual(fixture.wrap.classList.contains('is-fullscreen'), false);
        assert.strictEqual(fixture.fullscreenBtn.getAttribute('aria-label'), 'Toàn màn hình');

        // Mobile fallback (webkitEnterFullscreen on video when container requestFullscreen is absent)
        delete fixture.wrap.requestFullscreen;
        let webkitVideoFsCalled = 0;
        fixture.video.webkitEnterFullscreen = () => { webkitVideoFsCalled++; };
        fixture.fullscreenBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(webkitVideoFsCalled, 1);
    });

    test('UX01-9. Timeline seeking, buffered bar, and formatTime accuracy', () => {
        assert.strictEqual(ScreenPlayer.formatTime(0), '0:00');
        assert.strictEqual(ScreenPlayer.formatTime(755), '12:35');
        assert.strictEqual(ScreenPlayer.formatTime(1576), '26:16');
        assert.strictEqual(ScreenPlayer.formatTime(3723), '1:02:03');
        assert.strictEqual(ScreenPlayer.formatTime(NaN), '--:--');
        assert.strictEqual(ScreenPlayer.formatTime(-5), '--:--');

        const fixture = createUX01Fixture({ videoDuration: 1000 });
        fixture.video.buffered = {
            length: 1,
            end: () => 400
        };
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage });

        // Seek via range slider to 50%
        fixture.seekSlider.value = '50';
        fixture.seekSlider.dispatchEvent({ type: 'input' });

        assert.strictEqual(fixture.video.currentTime, 500);
        assert.strictEqual(fixture.currentTimeEl.textContent, '8:20');
        assert.strictEqual(fixture.playedBar.style.width, '50%');
        assert.strictEqual(fixture.bufferedBar.style.width, '40%');
    });

    test('UX01-10. Resume progress storage writes and 5-second throttling during playback', () => {
        let currentNow = 10000;
        const fixture = createUX01Fixture({ entryId: 'ep-101', videoSrc: '/media/assets/a1/content', videoDuration: 1576 });
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage, now: () => currentNow });

        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'ep-101';

        // Before playback starts, timeupdate at 0s does not write
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(storage.getItem(key), null);

        // Start playing and advance to 45s
        fixture.video.dispatchEvent({ type: 'playing' });
        fixture.video.currentTime = 45;
        fixture.video.dispatchEvent({ type: 'timeupdate' });

        const firstSaved = JSON.parse(storage.getItem(key));
        assert.strictEqual(firstSaved.entryId, 'ep-101');
        assert.strictEqual(firstSaved.videoSrc, '/media/assets/a1/content');
        assert.strictEqual(firstSaved.positionSeconds, 45);
        assert.strictEqual(firstSaved.durationSeconds, 1576);

        // 2 seconds later (within 5s throttle window) -> does not overwrite storage yet
        currentNow = 12000;
        fixture.video.currentTime = 47;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(JSON.parse(storage.getItem(key)).positionSeconds, 45);

        // 5 seconds elapsed -> writes updated position
        currentNow = 15001;
        fixture.video.currentTime = 50;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(JSON.parse(storage.getItem(key)).positionSeconds, 50);

        // Immediate save on pause even within throttle window
        currentNow = 16000;
        fixture.video.currentTime = 55;
        fixture.video.dispatchEvent({ type: 'pause' });
        assert.strictEqual(JSON.parse(storage.getItem(key)).positionSeconds, 55);
    });

    test('UX01-11. Resume popup is shown when saved position >= 30s and valid', () => {
        const storage = createMemoryStorage({
            [ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1']: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/123/content',
                positionSeconds: 755,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', entryLabel: 'Tập 1', videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc, { storage });

        assert.strictEqual(fixture.resumeModal.hidden, false);
        assert.strictEqual(fixture.resumeTitle.textContent, 'Tiếp tục xem Tập 1?');
        assert.strictEqual(fixture.resumeMessage.textContent, 'Lần trước bạn đã xem đến 12:35.');
    });

    test('UX01-12. Resume popup is NOT shown at 0s or < 30s (e.g. 29s)', () => {
        const storage = createMemoryStorage({
            [ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1']: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/123/content',
                positionSeconds: 29,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc, { storage });

        assert.strictEqual(fixture.resumeModal.hidden, true);
    });

    test('UX01-13. Resume popup is NOT shown near end (>= duration - 30s) or after episode ended', () => {
        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1';
        const storage = createMemoryStorage({
            [key]: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/123/content',
                positionSeconds: 1550,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc, { storage });

        // 1550 >= 1576 - 30 (1546), so popup must remain hidden
        assert.strictEqual(fixture.resumeModal.hidden, true);

        // When episode finishes ('ended'), saved progress is cleared
        fixture.video.dispatchEvent({ type: 'ended' });
        assert.strictEqual(storage.getItem(key), null);
    });

    test('UX01-14. Resume popup is NOT shown when video source identity changes (replaced video asset)', () => {
        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1';
        const storage = createMemoryStorage({
            [key]: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/old-asset-id/content',
                positionSeconds: 600,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({
            entryId: 'entry-ep-1',
            videoSrc: '/media/assets/new-asset-id/content',
            videoDuration: 1576
        });
        ScreenPlayer.init(fixture.doc, { storage });

        assert.strictEqual(fixture.resumeModal.hidden, true);
        assert.strictEqual(storage.getItem(key), null, 'Stale progress for old video asset should be cleared');
    });

    test('UX01-15. Corrupt JSON or blocked localStorage never breaks playback initialization or controls', () => {
        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1';
        const corruptStorage = createMemoryStorage({ [key]: '{invalid-json' });
        const fixture1 = createUX01Fixture();
        assert.doesNotThrow(() => {
            ScreenPlayer.init(fixture1.doc, { storage: corruptStorage });
        });
        assert.strictEqual(fixture1.resumeModal.hidden, true);

        const throwingStorage = {
            getItem() { throw new Error('SecurityError'); },
            setItem() { throw new Error('QuotaExceededError'); },
            removeItem() { throw new Error('SecurityError'); }
        };
        const fixture2 = createUX01Fixture();
        assert.doesNotThrow(() => {
            const ctrl = ScreenPlayer.init(fixture2.doc, { storage: throwingStorage });
            ctrl.seekRelative(10);
            ctrl.setPlaybackSpeed(1.5);
        });
        assert.strictEqual(fixture2.video.currentTime, 10);
        assert.strictEqual(fixture2.video.playbackRate, 1.5);
    });

    test('UX01-16. Selecting "Xem tiếp" seeks to saved position, dismisses popup, and plays video', async () => {
        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1';
        const storage = createMemoryStorage({
            [key]: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/123/content',
                positionSeconds: 755,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc, { storage });

        assert.strictEqual(fixture.resumeModal.hidden, false);

        fixture.resumeConfirmBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await Promise.resolve();

        assert.strictEqual(fixture.resumeModal.hidden, true);
        assert.strictEqual(fixture.video.currentTime, 755);
        assert.strictEqual(fixture.video._playCalled, 1);
    });

    test('UX01-17. Selecting "Xem từ đầu" clears saved progress, resets to 0:00, dismisses popup, and plays video', async () => {
        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1';
        const storage = createMemoryStorage({
            [key]: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/123/content',
                positionSeconds: 755,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', videoDuration: 1576 });
        fixture.video.currentTime = 755;
        ScreenPlayer.init(fixture.doc, { storage });

        assert.strictEqual(fixture.resumeModal.hidden, false);

        fixture.resumeResetBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await Promise.resolve();

        assert.strictEqual(fixture.resumeModal.hidden, true);
        assert.strictEqual(fixture.video.currentTime, 0);
        assert.strictEqual(storage.getItem(key), null);
        assert.strictEqual(fixture.video._playCalled, 1);
    });

    test('UX01-18. Network interruption preserves latest known position and restores it on retry', () => {
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', videoDuration: 1576 });
        const storage = createMemoryStorage();
        // Simulate video.load() resetting currentTime to 0 like browsers do on reload
        fixture.video.load = () => {
            fixture.video._loadCalled = (fixture.video._loadCalled || 0) + 1;
            fixture.video.currentTime = 0;
        };

        ScreenPlayer.init(fixture.doc, { storage });

        // User plays to 420s
        fixture.video.dispatchEvent({ type: 'playing' });
        fixture.video.currentTime = 420;
        fixture.video.dispatchEvent({ type: 'timeupdate' });

        // Network error occurs
        fixture.video.error = { code: 2 };
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false);

        // User clicks Retry
        fixture.retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        fixture.video.dispatchEvent({ type: 'loadedmetadata' });

        assert.strictEqual(fixture.video._loadCalled, 1);
        assert.strictEqual(fixture.video.currentTime, 420, 'Saved position should be restored after retry reload');
    });

    test('UX01-19. Next Episode control and near-end prompt appear within last 45s and can be dismissed', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576, hasNext: true });
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage });

        assert.ok(fixture.nextEpBtn);
        assert.strictEqual(fixture.nextUpPrompt.hidden, true);

        // At 1000s (> 45s from end) -> hidden
        fixture.video.currentTime = 1000;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.nextUpPrompt.hidden, true);

        // At 1540s (within last 45s of 1576s) -> visible
        fixture.video.currentTime = 1540;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.nextUpPrompt.hidden, false);

        // Dismiss prompt -> stays hidden on further timeupdate
        fixture.nextUpDismissBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.nextUpPrompt.hidden, true);

        fixture.video.currentTime = 1550;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.nextUpPrompt.hidden, true);

        // When hasNext is false (e.g. FEATURE or final episode), next controls are absent
        const noNextFixture = createUX01Fixture({ hasNext: false });
        const ctrl2 = ScreenPlayer.init(noNextFixture.doc, { storage });
        assert.strictEqual(ctrl2.nextEpBtn, null);
        assert.strictEqual(ctrl2.nextUpPrompt, null);
    });

    test('UX01-20. Touch double-tap left/right on video surface seeks -10s / +10s', () => {
        let now = 5000;
        const fixture = createUX01Fixture({ videoDuration: 1000 });
        fixture.video.getBoundingClientRect = () => ({ left: 0, width: 400 });
        fixture.video.currentTime = 100;

        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage, now: () => now });

        // Double tap right side (clientX = 350 out of 400) within 200ms
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 350 });
        now += 180;
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 350 });
        assert.strictEqual(fixture.video.currentTime, 110);
        assert.strictEqual(fixture.seekFeedbackText.textContent, '+10 giây');

        // Double tap left side (clientX = 50 out of 400) within 200ms
        now += 1000;
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 50 });
        now += 150;
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 50 });
        assert.strictEqual(fixture.video.currentTime, 100);
        assert.strictEqual(fixture.seekFeedbackText.textContent, '-10 giây');
    });

    test('UX01-21. Repeated init() does not attach duplicate event listeners', () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();

        ScreenPlayer.init(fixture.doc, { storage });
        ScreenPlayer.init(fixture.doc, { storage });

        assert.strictEqual(fixture.video.getListeners('error').length, 1);
        assert.strictEqual(fixture.video.getListeners('playing').length, 1);
        assert.strictEqual(fixture.video.getListeners('timeupdate').length, 1);
        assert.strictEqual(fixture.playPauseBtn.getListeners('click').length, 1);
        assert.strictEqual(fixture.rewindBtn.getListeners('click').length, 1);
        assert.strictEqual(fixture.forwardBtn.getListeners('click').length, 1);
    });

    test('UX01-22. Native read-only HTMLMediaElement.paused throws if assigned; player manages state exclusively through play() and pause()', async () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();

        // Verify fixture enforces native read-only paused property
        assert.throws(() => {
            fixture.video.paused = false;
        }, TypeError);

        const controller = ScreenPlayer.init(fixture.doc, { storage });
        assert.ok(controller);
        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.wrap.classList.contains('is-paused'), true);

        // Toggle 1: Play
        controller.togglePlay();
        await Promise.resolve();
        assert.strictEqual(fixture.video._playCalled, 1);
        assert.strictEqual(fixture.video.paused, false);
        assert.strictEqual(fixture.wrap.classList.contains('is-playing'), true);

        // Toggle 2: Pause
        controller.togglePlay();
        assert.strictEqual(fixture.video._pauseCalled, 1);
        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.wrap.classList.contains('is-paused'), true);

        // Toggle 3: Play again
        controller.togglePlay();
        await Promise.resolve();
        assert.strictEqual(fixture.video._playCalled, 2);
        assert.strictEqual(fixture.video.paused, false);
        assert.strictEqual(fixture.wrap.classList.contains('is-playing'), true);

        // Test Promise rejection handling without writing to paused
        fixture.video._playShouldReject = true;
        controller.togglePlay(); // pauses
        controller.togglePlay(); // tries to play and rejects
        await Promise.resolve();
        await Promise.resolve();
        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.wrap.classList.contains('is-paused'), true);
        assert.strictEqual(fixture.playPauseBtn.getAttribute('aria-label'), 'Phát');
    });

    test('UX01-23. Touch single-tap waits for gesture window and executes Play/Pause exactly once', async () => {
        const fixture = createUX01Fixture({ videoDuration: 1000 });
        fixture.video.getBoundingClientRect = () => ({ left: 0, width: 400 });
        const storage = createMemoryStorage();

        // Use short gesture window for test speed
        ScreenPlayer.init(fixture.doc, { storage, touchDoubleTapWindowMs: 40 });

        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.video._playCalled, 0);

        // Single tap on right zone (clientX = 350)
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 350 });

        // Immediately after tap, play has NOT been called yet (waiting for potential double-tap)
        assert.strictEqual(fixture.video._playCalled, 0);

        // Wait for gesture window to expire
        await new Promise(resolve => setTimeout(resolve, 60));

        // Now single-tap action has fired exactly once
        assert.strictEqual(fixture.video._playCalled, 1);
        assert.strictEqual(fixture.video.paused, false);
    });

    test('UX01-24. Touch double-tap while playing seeks +10s and does NOT pause or interrupt playback', async () => {
        let now = 1000;
        const fixture = createUX01Fixture({ videoDuration: 1000, paused: false });
        fixture.video.getBoundingClientRect = () => ({ left: 0, width: 400 });
        fixture.video.currentTime = 60;
        const storage = createMemoryStorage();

        ScreenPlayer.init(fixture.doc, { storage, now: () => now, touchDoubleTapWindowMs: 150 });

        // Video starts playing
        assert.strictEqual(fixture.video.paused, false);
        assert.strictEqual(fixture.video._pauseCalled, 0);

        // Tap 1 on right zone (clientX = 350)
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 350 });

        // Video must NOT pause on tap 1!
        assert.strictEqual(fixture.video._pauseCalled, 0);
        assert.strictEqual(fixture.video.paused, false);

        // Tap 2 on right zone (clientX = 350) 80ms later (within gesture window)
        now += 80;
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 350 });

        // Seek performed: 60s -> 70s
        assert.strictEqual(fixture.video.currentTime, 70);
        assert.strictEqual(fixture.seekFeedbackText.textContent, '+10 giây');

        // Playback was never paused!
        assert.strictEqual(fixture.video._pauseCalled, 0);
        assert.strictEqual(fixture.video.paused, false);

        // Wait to verify no delayed timer pauses playback
        await new Promise(resolve => setTimeout(resolve, 200));
        assert.strictEqual(fixture.video._pauseCalled, 0);
        assert.strictEqual(fixture.video.paused, false);
    });

    test('UX01-25. Touch double-tap while paused seeks -10s and does NOT start playback', async () => {
        let now = 2000;
        const fixture = createUX01Fixture({ videoDuration: 1000, paused: true });
        fixture.video.getBoundingClientRect = () => ({ left: 0, width: 400 });
        fixture.video.currentTime = 50;
        const storage = createMemoryStorage();

        ScreenPlayer.init(fixture.doc, { storage, now: () => now, touchDoubleTapWindowMs: 150 });

        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.video._playCalled, 0);

        // Tap 1 on left zone (clientX = 50)
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 50 });

        // Video must NOT start playing on tap 1!
        assert.strictEqual(fixture.video._playCalled, 0);
        assert.strictEqual(fixture.video.paused, true);

        // Tap 2 on left zone (clientX = 50) 80ms later
        now += 80;
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 50 });

        // Seek performed: 50s -> 40s
        assert.strictEqual(fixture.video.currentTime, 40);
        assert.strictEqual(fixture.seekFeedbackText.textContent, '-10 giây');

        // Playback was never started!
        assert.strictEqual(fixture.video._playCalled, 0);
        assert.strictEqual(fixture.video.paused, true);

        // Wait to verify no delayed timer starts playback
        await new Promise(resolve => setTimeout(resolve, 200));
        assert.strictEqual(fixture.video._playCalled, 0);
        assert.strictEqual(fixture.video.paused, true);
    });

    test('UX01-26. Mouse click on video triggers Play/Pause immediately with no gesture delay', () => {
        const fixture = createUX01Fixture();
        const storage = createMemoryStorage();
        ScreenPlayer.init(fixture.doc, { storage, touchDoubleTapWindowMs: 300 });

        assert.strictEqual(fixture.video._playCalled, 0);

        // Standard mouse click
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'mouse', clientX: 350 });

        // Synchronously invokes play without any delay
        assert.strictEqual(fixture.video._playCalled, 1);
    });

    test('UX01-27. Gesture timer cleanup on controller destroy()', async () => {
        const fixture = createUX01Fixture();
        fixture.video.getBoundingClientRect = () => ({ left: 0, width: 400 });
        const storage = createMemoryStorage();
        const controller = ScreenPlayer.init(fixture.doc, { storage, touchDoubleTapWindowMs: 100 });

        // Tap 1 to start gesture timer
        fixture.video.dispatchEvent({ type: 'click', pointerType: 'touch', clientX: 350 });
        assert.strictEqual(fixture.video._playCalled, 0);

        // Destroy controller while timer is pending
        controller.destroy();

        // Wait past timer duration
        await new Promise(resolve => setTimeout(resolve, 150));

        // Timer was cancelled on destroy, so play was never called
        assert.strictEqual(fixture.video._playCalled, 0);
    });

    test('UX01-28. Resume modal moves focus to "Xem tiếp", allows keyboard reachability to "Xem từ đầu", and Escape dismisses without deleting saved progress', () => {
        const key = ScreenPlayer.STORAGE_KEY_PREFIX + 'entry-ep-1';
        const storage = createMemoryStorage({
            [key]: JSON.stringify({
                entryId: 'entry-ep-1',
                videoSrc: '/media/assets/123/content',
                positionSeconds: 755,
                durationSeconds: 1576,
                updatedAt: 1700000000000
            })
        });
        const fixture = createUX01Fixture({ entryId: 'entry-ep-1', videoDuration: 1576 });

        ScreenPlayer.init(fixture.doc, { storage });

        // 1. Popup is shown and focus automatically moves to "Xem tiếp" (resumeConfirmBtn)
        assert.strictEqual(fixture.resumeModal.hidden, false);
        assert.strictEqual(fixture.doc.activeElement, fixture.resumeConfirmBtn, 'Keyboard focus should move to Xem tiếp on showing modal');

        // 2. Both action buttons are reachable by keyboard
        assert.strictEqual(fixture.resumeConfirmBtn.getAttribute('type'), 'button');
        assert.strictEqual(fixture.resumeResetBtn.getAttribute('type'), 'button');
        fixture.resumeResetBtn.focus();
        assert.strictEqual(fixture.doc.activeElement, fixture.resumeResetBtn, 'Keyboard focus can move to Xem từ đầu');

        // 3. Dialog has non-modal ARIA semantics
        assert.strictEqual(fixture.resumeModal.getAttribute('role'), 'dialog');
        assert.strictEqual(fixture.resumeModal.getAttribute('aria-modal'), 'false');

        // 4. Escape key dismisses the dialog
        fixture.resumeModal.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(fixture.resumeModal.hidden, true, 'Resume dialog should be hidden after Escape');

        // 5. Focus returns to sensible player control (playPauseBtn)
        assert.strictEqual(fixture.doc.activeElement, fixture.playPauseBtn, 'Focus should return to playPauseBtn after Escape');

        // 6. Saved progress is preserved in storage (NOT deleted on dismissal)
        assert.ok(storage.getItem(key), 'Saved progress must remain intact after Escape dismissal');
        const record = JSON.parse(storage.getItem(key));
        assert.strictEqual(record.positionSeconds, 755);
    });

    test('UX01-P1-1. Initial paused player keeps poster visible and attaches fallback listeners', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, false, 'Poster should be visible before playback starts');
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/variants/w600');
        assert.strictEqual(fixture.posterImg.getListeners('error').length, 1);
        assert.strictEqual(fixture.posterImg.getListeners('load').length, 1);
        assert.strictEqual(controller.getPosterElement(), fixture.poster);
    });

    test('UX01-P1-2. Primary variant error falls back to canonical content URL and updates video.poster', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc);

        // Simulate variant 404/failure triggering error on <img>
        fixture.posterImg.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-fallback'), 'true');
        assert.strictEqual(fixture.video.poster, '/media/assets/thumb-asset-1/content');
    });

    test('UX01-P1-3. Content fallback error falls back to secondary fallback URL (title backdrop/poster)', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc);

        // First error: variant -> content
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');

        // Second error: content -> secondary fallback
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.src, '/media/assets/backdrop-asset-1/content');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-secondary'), 'true');
        assert.strictEqual(fixture.video.poster, '/media/assets/backdrop-asset-1/content');
    });

    test('UX01-P1-4. All fallbacks failing gracefully hides img without throwing or breaking controls', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc);

        // 1st error -> fallback
        fixture.posterImg.dispatchEvent({ type: 'error' });
        // 2nd error -> secondary
        fixture.posterImg.dispatchEvent({ type: 'error' });
        // 3rd error -> hide image gracefully
        fixture.posterImg.dispatchEvent({ type: 'error' });

        assert.strictEqual(fixture.posterImg.style.display, 'none');

        // Controls remain fully operational
        fixture.playPauseBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.video._playCalled, 1);
    });

    test('UX01-P1-5. First-frame visibility: play preserves poster, playing hides poster, subsequent pause, buffer, and seek keep it hidden', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, false, 'Poster is initially visible');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false, 'First frame has not rendered yet');

        // Playback starts (play event: buffering initial frames)
        fixture.video.dispatchEvent({ type: 'play' });
        assert.strictEqual(fixture.poster.hidden, false, 'Poster must REMAIN VISIBLE on play while initial frame buffers');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);

        // Playing event fires (decodable frames are now presenting on canvas)
        fixture.video.dispatchEvent({ type: 'playing' });
        assert.strictEqual(fixture.poster.hidden, true, 'Poster must be hidden on playing event');
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);

        // Video pauses -> Poster MUST REMAIN HIDDEN so user sees paused frame
        fixture.video.dispatchEvent({ type: 'pause' });
        assert.strictEqual(fixture.poster.hidden, true, 'Poster must remain hidden when paused');

        // Video buffers -> Poster MUST REMAIN HIDDEN
        fixture.video.dispatchEvent({ type: 'waiting' });
        assert.strictEqual(fixture.poster.hidden, true, 'Poster must remain hidden while buffering');

        // Video seeks -> Poster MUST REMAIN HIDDEN
        fixture.rewindBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(fixture.poster.hidden, true, 'Poster must remain hidden during seek');
    });

    test('UX01-P1-6. Initializing when video is already playing or past 0s immediately hides poster', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576, videoCurrentTime: 42 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, true, 'Poster should be hidden if video position > 0 on init');
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);
    });

    test('UX01-P1-7. No stage image (hasStageImage: false) initializes cleanly with null poster', () => {
        const fixture = createUX01Fixture({ hasStageImage: false, videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(controller.getPosterElement(), null);
        assert.strictEqual(fixture.wrap.classList.contains('is-enhanced'), true);
    });

    test('UX01-P1-8. Controller destroy cleanly removes posterImg and video loadeddata event listeners', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.posterImg.getListeners('error').length, 1);
        assert.strictEqual(fixture.posterImg.getListeners('load').length, 1);
        assert.strictEqual(fixture.video.getListeners('loadeddata').length, 1);
        assert.strictEqual(fixture.video.getListeners('seeked').length, 1);

        controller.destroy();

        assert.strictEqual(fixture.posterImg.getListeners('error').length, 0);
        assert.strictEqual(fixture.posterImg.getListeners('load').length, 0);
        assert.strictEqual(fixture.video.getListeners('loadeddata').length, 0);
        assert.strictEqual(fixture.video.getListeners('seeked').length, 0);
    });

    test('UX01-P1-C1-1 (5.A Dual-handler regression test). Neutralizes inline onerror and does not skip canonical fallback', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        let legacyInlineHandlerCalled = 0;
        fixture.posterImg.setAttribute('onerror', "this.onerror=null;this.src='/media/assets/thumb-asset-1/content'");
        fixture.posterImg.onerror = function () {
            legacyInlineHandlerCalled++;
            fixture.posterImg.setAttribute('data-tried-fallback', 'true');
            fixture.posterImg.src = '/media/assets/thumb-asset-1/content';
        };

        const controller = ScreenPlayer.init(fixture.doc);

        // init() must neutralize any inline onerror
        assert.strictEqual(fixture.posterImg.onerror, null, 'posterImg.onerror must be neutralized on init');
        assert.strictEqual(fixture.posterImg.getAttribute('onerror'), null, 'onerror attribute must be removed');

        // Dispatch primary variant error
        fixture.posterImg.dispatchEvent({ type: 'error' });

        // Must transition to canonical content URL without skipping to secondary or hiding
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-fallback'), 'true');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-secondary'), null, 'Must NOT skip to secondary fallback');
        assert.notStrictEqual(fixture.posterImg.style.display, 'none', 'Must NOT be hidden prematurely');
    });

    test('UX01-P1-C1-2 (5.B Primary variant failure -> canonical content succeeds). Load event syncs video.poster', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc);

        // 1. Variant fails
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');

        // 2. Canonical content loads successfully
        fixture.posterImg.dispatchEvent({ type: 'load' });
        assert.strictEqual(fixture.video.poster, '/media/assets/thumb-asset-1/content');
        assert.notStrictEqual(fixture.posterImg.style.display, 'none');
    });

    test('UX01-P1-C1-3 (5.C No secondary image). Canonical content remains visible while loading, not hidden prematurely', () => {
        const fixture = createUX01Fixture({
            videoDuration: 1576,
            posterSecondarySrc: '' // No secondary image available
        });
        ScreenPlayer.init(fixture.doc);

        // Primary variant fails
        fixture.posterImg.dispatchEvent({ type: 'error' });

        // Canonical content is assigned and STILL VISIBLE while loading
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-fallback'), 'true');
        assert.notStrictEqual(fixture.posterImg.style.display, 'none', 'Must remain visible while canonical content is loading');

        // Only when canonical content ALSO fails does it hide gracefully
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.style.display, 'none', 'Gracefully hidden after canonical failure with no secondary');
    });

    test('UX01-P1-C1-4 (5.D Full fallback progression). Primary -> Canonical -> Secondary -> Graceful hide', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        ScreenPlayer.init(fixture.doc);

        // 1. Primary fails -> canonical
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');
        assert.notStrictEqual(fixture.posterImg.style.display, 'none');

        // 2. Canonical fails -> secondary
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.src, '/media/assets/backdrop-asset-1/content');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-secondary'), 'true');
        assert.notStrictEqual(fixture.posterImg.style.display, 'none');

        // 3. Secondary fails -> graceful hide
        fixture.posterImg.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.posterImg.style.display, 'none');
    });

    test('UX01-P1-C1-5 (5.E Early-load timing). Image fails before controller init() runs', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        // Simulate image completed with failure before DOMContentLoaded / ScreenPlayer.init
        fixture.posterImg.complete = true;
        fixture.posterImg.naturalWidth = 0;

        ScreenPlayer.init(fixture.doc);

        // init() should detect completed failure and trigger onPosterImgError immediately
        assert.strictEqual(fixture.posterImg.src, '/media/assets/thumb-asset-1/content');
        assert.strictEqual(fixture.posterImg.getAttribute('data-tried-fallback'), 'true');
        assert.strictEqual(fixture.video.poster, '/media/assets/thumb-asset-1/content');
    });

    test('UX01-P1-C1-6 (5.F First-frame visibility with loadeddata). loadeddata while playing hides poster; loadeddata while paused does not', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, false);
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);

        // 1. loadeddata while paused (e.g. metadata/initial preload before user clicks play)
        assert.strictEqual(fixture.video.paused, true);
        fixture.video.dispatchEvent({ type: 'loadeddata' });
        assert.strictEqual(fixture.poster.hidden, false, 'Poster must remain visible if loadeddata fires while paused');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);

        // 2. User plays
        fixture.video.dispatchEvent({ type: 'play' });
        assert.strictEqual(fixture.poster.hidden, false, 'Poster must remain visible on play');

        // 3. loadeddata fires now that paused is false
        // (In our mock, video.play() sets _isPaused to false)
        fixture.video.play();
        assert.strictEqual(fixture.video.paused, false);
        fixture.video.dispatchEvent({ type: 'loadeddata' });
        assert.strictEqual(fixture.poster.hidden, true, 'Poster must be hidden when loadeddata fires while playing');
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);
    });

    test('UX01-P1-C1-7 (5.F Rejected play() keeps poster covering video surface and shows error overlay)', async () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        fixture.video._playShouldReject = true; // Simulate autoplay rejection or network playback failure
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, false);
        assert.strictEqual(fixture.errorContainer.hidden, true);
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);

        // Trigger play button which encounters rejected play() promise
        fixture.playPauseBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Wait for rejected promise microtasks to settle
        await new Promise(resolve => setTimeout(resolve, 50));

        // Poster must REMAIN VISIBLE covering blank video canvas
        assert.strictEqual(fixture.poster.hidden, false, 'Poster must remain visible on play rejection');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);
        assert.strictEqual(fixture.video.paused, true);
    });

    test('UX01-P1-C1-8. normalizePath handles various absolute, relative, and formatted URLs', () => {
        assert.strictEqual(ScreenPlayer.normalizePath('http://localhost:8080/media/assets/123/variants/w600'), '/media/assets/123/variants/w600');
        assert.strictEqual(ScreenPlayer.normalizePath('https://universe.kiemlai.vn/media/assets/123/content?token=abc#bar'), '/media/assets/123/content');
        assert.strictEqual(ScreenPlayer.normalizePath('/media/assets/123/variants/w600/'), '/media/assets/123/variants/w600');
        assert.strictEqual(ScreenPlayer.normalizePath('/media/assets/123/variants/w600'), '/media/assets/123/variants/w600');
        assert.strictEqual(ScreenPlayer.normalizePath(''), '');
        assert.strictEqual(ScreenPlayer.normalizePath(null), '');
        assert.strictEqual(ScreenPlayer.normalizePath(undefined), '');
    });

    // =========================================================================
    // MS-08G2-UX01-P1-C2: Prevent Premature Hiding During Paused Seeking
    // =========================================================================

    test('UX01-P1-C2-1 (5.1 Initial paused state). Stage poster visible before any playback', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.video.currentTime, 0);
        assert.strictEqual(fixture.poster.hidden, false, 'Poster must be visible on initial paused load');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);
    });

    test('UX01-P1-C2-2 (5.2 & 5.3 Paused seek reproduction). Paused seek from 0 to 60s does not hide poster on timeupdate', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.video.paused, true);
        assert.strictEqual(fixture.poster.hidden, false);
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);

        // User or script seeks while paused before any frame has played
        fixture.video.currentTime = 60;
        fixture.video.dispatchEvent({ type: 'timeupdate' });

        assert.strictEqual(
            fixture.poster.hidden,
            false,
            'Paused seeking before the first frame must not hide the poster'
        );
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);
        assert.strictEqual(fixture.video.paused, true, 'Video must remain paused');
        assert.strictEqual(fixture.video.currentTime, 60, 'Seek position must not be reset');
    });

    test('UX01-P1-C2-3 (5.4 Video readiness after paused seek). seeked event with readyState >= 2 dismisses poster', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        // Seek to 60s while paused
        fixture.video.currentTime = 60;
        fixture.video.seeking = true;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.poster.hidden, false, 'Poster remains visible while seeking');

        // Video readiness occurs: seek completes and decodable frame is ready
        fixture.video.seeking = false;
        fixture.video.readyState = 2; // HAVE_CURRENT_DATA
        fixture.video.dispatchEvent({ type: 'seeked' });

        assert.strictEqual(fixture.poster.hidden, true, 'Poster must be dismissed once frame is ready after seek');
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);
    });

    test('UX01-P1-C2-4 (5.4 Playing event after paused seek). playing event dismisses poster', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        fixture.video.currentTime = 60;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.poster.hidden, false);

        // User starts playback
        fixture.video.dispatchEvent({ type: 'play' });
        assert.strictEqual(fixture.poster.hidden, false, 'Play alone does not hide poster');

        // Playback commences with presentation
        fixture.video.dispatchEvent({ type: 'playing' });
        assert.strictEqual(fixture.poster.hidden, true, 'Playing event dismisses poster');
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);
    });

    test('UX01-P1-C2-5 (5.5 Post-playback stability). After real playback, Pause, Seek, and Buffering do not restore poster', () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        // Play and render first frame
        fixture.video.dispatchEvent({ type: 'playing' });
        assert.strictEqual(fixture.poster.hidden, true);
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);

        // Ordinary Pause
        fixture.video.dispatchEvent({ type: 'pause' });
        assert.strictEqual(fixture.poster.hidden, true, 'Pause must not restore poster');

        // Ordinary Buffering
        fixture.video.dispatchEvent({ type: 'waiting' });
        assert.strictEqual(fixture.poster.hidden, true, 'Buffering must not restore poster');

        // Subsequent seek while paused
        fixture.video.currentTime = 120;
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.poster.hidden, true, 'Subsequent seek must not restore poster');
    });

    test('UX01-P1-C2-6 (5.6 Initialization at nonzero time without ready frame). Keeps poster visible and unrendered', () => {
        const fixture = createUX01Fixture({
            videoDuration: 1576,
            videoCurrentTime: 60,
            videoReadyState: 0 // HAVE_NOTHING: frame not yet decoded
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, false, 'Poster must remain visible if initialized at nonzero time without ready frame');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);

        // timeupdate while frame still not ready
        fixture.video.dispatchEvent({ type: 'timeupdate' });
        assert.strictEqual(fixture.poster.hidden, false, 'timeupdate without ready frame must keep poster visible');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);
    });

    test('UX01-P1-C2-7 (5.7 Reinitialization with an actually available frame). Correctly hides poster on init', () => {
        const fixture = createUX01Fixture({
            videoDuration: 1576,
            videoCurrentTime: 60,
            videoReadyState: 2 // HAVE_CURRENT_DATA: frame already decoded and ready
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.poster.hidden, true, 'Poster should be hidden if initialized with an available frame');
        assert.strictEqual(controller.hasRenderedFirstFrame(), true);
    });

    test('UX01-P1-C2-8 (5.8 G2-C03 recovery & rejected playback). Non-playing events do not dismiss errors; rejected play preserves poster', async () => {
        const fixture = createUX01Fixture({ videoDuration: 1576 });
        const controller = ScreenPlayer.init(fixture.doc);

        // Trigger runtime error
        fixture.video.error = { code: 2 }; // MEDIA_ERR_NETWORK
        fixture.video.dispatchEvent({ type: 'error' });
        assert.strictEqual(fixture.errorContainer.hidden, false, 'Error container shown');

        // canplay / loadeddata during seek must NOT conceal the error (G2-C03 invariant)
        fixture.video.dispatchEvent({ type: 'canplay' });
        assert.strictEqual(fixture.errorContainer.hidden, false, 'canplay must not hide error');
        fixture.video.dispatchEvent({ type: 'loadeddata' });
        assert.strictEqual(fixture.errorContainer.hidden, false, 'loadeddata must not hide error');

        // Play rejection keeps poster visible and maintains error notice
        fixture.video._playShouldReject = true;
        fixture.playPauseBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(fixture.poster.hidden, false, 'Poster remains visible on play rejection');
        assert.strictEqual(controller.hasRenderedFirstFrame(), false);
        assert.strictEqual(fixture.errorContainer.hidden, false);
    });

    test('UX01-P1-C2-9. isVideoFrameReady correctly evaluates readyState and seeking flags', () => {
        assert.strictEqual(ScreenPlayer.isVideoFrameReady(null), false);
        assert.strictEqual(ScreenPlayer.isVideoFrameReady(undefined), false);

        // Actively seeking is not ready
        assert.strictEqual(ScreenPlayer.isVideoFrameReady({ seeking: true, readyState: 4 }), false);

        // readyState < 2 is not ready
        assert.strictEqual(ScreenPlayer.isVideoFrameReady({ seeking: false, readyState: 0 }), false);
        assert.strictEqual(ScreenPlayer.isVideoFrameReady({ seeking: false, readyState: 1 }), false);

        // readyState >= 2 and not seeking is ready
        assert.strictEqual(ScreenPlayer.isVideoFrameReady({ seeking: false, readyState: 2 }), true);
        assert.strictEqual(ScreenPlayer.isVideoFrameReady({ seeking: false, readyState: 3 }), true);
        assert.strictEqual(ScreenPlayer.isVideoFrameReady({ seeking: false, readyState: 4 }), true);
    });

});

describe('ScreenPlayer — Responsive Video Player Controls, Standalone PiP and Extensible Settings (MS-08G2-UX01-M1)', () => {

    test('M1-1. Settings gear button opens and closes settings menu and toggles aria-expanded', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Settings menu initially hidden');
        assert.strictEqual(fixture.settingsBtn.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(controller.isSettingsMenuOpen(), false);

        // Click settings button to open
        fixture.settingsBtn.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(fixture.settingsMenu.hidden, false, 'Settings menu opened on click');
        assert.strictEqual(fixture.settingsBtn.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(controller.isSettingsMenuOpen(), true);

        // Click settings button again to close
        fixture.settingsBtn.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Settings menu closed on second click');
        assert.strictEqual(fixture.settingsBtn.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(controller.isSettingsMenuOpen(), false);
    });

    test('M1-2. Opening settings menu closes desktop speed menu if open; opening speed menu closes settings menu', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        // Open speed menu
        fixture.speedBtn.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(fixture.speedMenu.hidden, false, 'Speed menu open');
        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Settings menu closed');

        // Open settings menu -> speed menu must close
        fixture.settingsBtn.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(fixture.settingsMenu.hidden, false, 'Settings menu now open');
        assert.strictEqual(fixture.speedMenu.hidden, true, 'Speed menu automatically closed');

        // Open speed menu again -> settings menu must close
        fixture.speedBtn.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(fixture.speedMenu.hidden, false, 'Speed menu re-opened');
        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Settings menu automatically closed');
    });

    test('M1-3. Playback speed selection inside settings menu updates video, badges, active states, storage, and closes menu', () => {
        const memoryStorage = createMemoryStorage();
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc, { storage: memoryStorage });

        // Open settings menu
        controller.setSettingsMenuOpen(true);
        assert.strictEqual(fixture.settingsMenu.hidden, false);

        // Select 1.5x in settings menu
        const opt15 = fixture.settingsSpeedOptionButtons[1.5];
        assert.ok(opt15, '1.5x option button exists in settings menu');
        opt15.dispatchEvent({
            type: 'click',
            target: opt15,
            currentTarget: opt15,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        // Verify video playbackRate
        assert.strictEqual(fixture.video.playbackRate, 1.5, 'video.playbackRate updated to 1.5');

        // Verify speed badges in both desktop label and settings badge
        assert.strictEqual(fixture.speedLabel.textContent, '1.5x');
        assert.strictEqual(fixture.settingsSpeedBadge.textContent, '1.5x');

        // Verify storage persistence
        assert.strictEqual(memoryStorage.getItem(ScreenPlayer.SPEED_STORAGE_KEY), '1.5');

        // Verify active classes and aria-checked in both speed menus
        assert.strictEqual(opt15.classList.contains('is-active'), true);
        assert.strictEqual(opt15.getAttribute('aria-checked'), 'true');
        const desktopOpt15 = fixture.speedOptionButtons[1.5];
        assert.strictEqual(desktopOpt15.classList.contains('is-active'), true);
        assert.strictEqual(desktopOpt15.getAttribute('aria-checked'), 'true');

        // Settings menu should be closed after selection
        assert.strictEqual(fixture.settingsMenu.hidden, true);
        assert.strictEqual(fixture.settingsBtn.getAttribute('aria-expanded'), 'false');
    });

    test('M1-4. Initializing with saved speed in storage restores video rate, label, and settings badge', () => {
        const memoryStorage = createMemoryStorage({ [ScreenPlayer.SPEED_STORAGE_KEY]: '2' });
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc, { storage: memoryStorage });

        assert.strictEqual(fixture.video.playbackRate, 2);
        assert.strictEqual(fixture.speedLabel.textContent, '2x');
        assert.strictEqual(fixture.settingsSpeedBadge.textContent, '2x');
        assert.strictEqual(fixture.settingsSpeedOptionButtons[2].classList.contains('is-active'), true);
        assert.strictEqual(fixture.settingsSpeedOptionButtons[2].getAttribute('aria-checked'), 'true');
    });

    test('M1-5. Standalone PiP: PiP button is independent outside settings; hidden when unsupported, visible when supported', () => {
        const fixture = createUX01Fixture();

        // Assert PiP button is NOT inside settingsWrap or settingsMenu
        assert.strictEqual(fixture.settingsWrap.contains(fixture.pipBtn), false, 'PiP button must not be inside settingsWrap');
        assert.strictEqual(fixture.settingsMenu.contains(fixture.pipBtn), false, 'PiP button must not be inside settingsMenu');
        assert.strictEqual(fixture.pipBtn.parentNode, fixture.controls, 'PiP button is direct child of controls container');

        // When unsupported (no doc.pictureInPictureEnabled):
        fixture.doc.pictureInPictureEnabled = false;
        fixture.video.requestPictureInPicture = undefined;
        let controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.pipBtn.hidden, true, 'PiP button hidden when unsupported');

        controller.destroy();

        // When supported:
        fixture.doc.pictureInPictureEnabled = true;
        let pipCalled = 0;
        fixture.video.requestPictureInPicture = () => {
            pipCalled++;
            return Promise.resolve();
        };
        controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.pipBtn.hidden, false, 'PiP button visible when supported');

        // Clicking PiP calls video.requestPictureInPicture
        fixture.pipBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(pipCalled, 1, 'Clicking PiP triggers requestPictureInPicture');
    });

    test('M1-6. Fullscreen transitions update .is-fullscreen, aria-label, and close open settings and speed menus', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        // Open settings menu
        controller.setSettingsMenuOpen(true);
        assert.strictEqual(fixture.settingsMenu.hidden, false);

        // Simulate entering fullscreen
        fixture.doc.fullscreenElement = fixture.wrap;
        fixture.doc.dispatchEvent({ type: 'fullscreenchange' });

        assert.strictEqual(fixture.wrap.classList.contains('is-fullscreen'), true, 'wrap has .is-fullscreen');
        assert.strictEqual(fixture.fullscreenBtn.getAttribute('aria-label'), 'Thoát toàn màn hình');
        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Fullscreen transition closes settings menu');
        assert.strictEqual(fixture.speedMenu.hidden, true, 'Fullscreen transition closes speed menu');

        // Open speed menu in fullscreen
        controller.setSpeedMenuOpen(true);
        assert.strictEqual(fixture.speedMenu.hidden, false);

        // Simulate exiting fullscreen
        fixture.doc.fullscreenElement = null;
        fixture.doc.dispatchEvent({ type: 'fullscreenchange' });

        assert.strictEqual(fixture.wrap.classList.contains('is-fullscreen'), false, 'wrap no longer .is-fullscreen');
        assert.strictEqual(fixture.fullscreenBtn.getAttribute('aria-label'), 'Toàn màn hình');
        assert.strictEqual(fixture.speedMenu.hidden, true, 'Exiting fullscreen closes speed menu');
    });

    test('M1-7. Escape key closes open settings menu and returns focus to settingsBtn', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        controller.setSettingsMenuOpen(true);
        assert.strictEqual(fixture.settingsMenu.hidden, false);

        let focused = false;
        fixture.settingsBtn.focus = () => { focused = true; };

        // Press Escape inside playerWrap
        const evt = { type: 'keydown', key: 'Escape', preventDefault: () => {} };
        fixture.wrap.dispatchEvent(evt);

        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Escape closed settings menu');
        assert.strictEqual(fixture.settingsBtn.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(focused, true, 'Focus returned to settingsBtn');
    });

    test('M1-8. Outside click on ownerDoc closes settings menu', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        controller.setSettingsMenuOpen(true);
        assert.strictEqual(fixture.settingsMenu.hidden, false);

        // Outside click on arbitrary target
        const outsideElement = createMockElement('div');
        fixture.doc.dispatchEvent({ type: 'click', target: outsideElement });

        assert.strictEqual(fixture.settingsMenu.hidden, true, 'Outside click closed settings menu');
        assert.strictEqual(fixture.settingsBtn.getAttribute('aria-expanded'), 'false');
    });

    test('M1-9. Clicking inside settings menu or settingsBtn does not toggle video play/pause', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.video.paused, true);

        // Click settings button
        fixture.settingsBtn.dispatchEvent({
            type: 'click',
            target: fixture.settingsBtn,
            currentTarget: fixture.settingsBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video._playCalled, 0, 'Play not called on settingsBtn click');
        assert.strictEqual(fixture.video.paused, true, 'Video remains paused');

        // Click speed option
        const opt = fixture.settingsSpeedOptionButtons[0.75];
        opt.dispatchEvent({
            type: 'click',
            target: opt,
            currentTarget: opt,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video._playCalled, 0, 'Play not called on speed option click');
        assert.strictEqual(fixture.video.playbackRate, 0.75);
    });

    test('M1-10. Controller destroy cleanly removes settingsBtn and ownerDoc click listeners', () => {
        const fixture = createUX01Fixture();
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.settingsBtn.getListeners('click').length, 1);
        assert.strictEqual(fixture.doc.getListeners('click').length, 1);

        controller.destroy();

        assert.strictEqual(fixture.settingsBtn.getListeners('click').length, 0);
        assert.strictEqual(fixture.doc.getListeners('click').length, 0);
    });

});

describe('ScreenPlayer — Outro End Boundary & Manual Skip Outro (MS-08G3-UX02-C2.1)', () => {

    test('C2.1-1. Bounded Outro [24:12 -> 25:30] on 26:16: button is hidden before 24:12 (1452s)', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452, // 24:12
            outroEndSeconds: 1530,   // 25:30
            videoDuration: 1576,     // 26:16
            videoCurrentTime: 1000
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Skip Outro button hidden before Outro starts');
        assert.strictEqual(controller.isOutroActive(), false);

        // Advance to 1451s
        fixture.video.currentTime = 1451;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Still hidden at 1451s');
    });

    test('C2.1-2. Bounded Outro [24:12 -> 25:30]: button is visible during Outro interval [1452s, 1530s)', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1452
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Skip Outro button visible at Outro start (1452s)');
        assert.strictEqual(controller.isOutroActive(), true);

        // Advance within Outro to 1500s
        fixture.video.currentTime = 1500;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Skip Outro button remains visible at 1500s');
    });

    test('C2.1-3. Bounded Outro [24:12 -> 25:30]: button hides at and after 25:30 (1530s) allowing after-credit playback', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1452
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.skipOutroBtn.hidden, false);

        // Advance to exactly Outro end (1530s)
        fixture.video.currentTime = 1530;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Skip Outro button hides at outroEnd boundary');
        assert.strictEqual(controller.isOutroActive(), false);

        // Advance into after-credit scene (1550s)
        fixture.video.currentTime = 1550;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Skip Outro button remains hidden during after-credits');
    });

    test('C2.1-4. Bounded Outro: clicking Skip Outro seeks to 25:30 (1530s), hides button, does NOT end video, and does NOT trigger next episode', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            paused: false
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.skipOutroBtn.hidden, false);

        let clickPrevented = false;
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => { clickPrevented = true; },
            stopPropagation: () => {}
        });

        assert.strictEqual(clickPrevented, true, 'Default prevented on click');
        assert.strictEqual(fixture.video.currentTime, 1530, 'Video seeked to bounded outroEnd (1530s)');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Skip Outro button immediately hidden after seeking to end');
        assert.strictEqual(fixture.video.paused, false, 'Playback continues without pausing');
        assert.strictEqual(fixture.video.ended, undefined, 'Video is not ended; after-credit scene remains watchable');
    });

    test('C2.1-5. Bounded Outro: seeking back into Outro interval re-shows the Skip Outro button', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1550 // in after-credit scene
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.skipOutroBtn.hidden, true);

        // Seek backwards into Outro (1480s)
        controller.seekTo(1480);
        fixture.video.dispatchEvent('seeked');

        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Button reappears when seeking back into Outro interval');
    });

    test('C2.1-6. Unbounded Outro without next episode (hasNext: false): button appears at 1452s and stays visible until video end', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            // outroEndSeconds omitted -> null
            videoDuration: 1576,
            videoCurrentTime: 1000,
            hasNext: false
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.skipOutroBtn.hidden, true);

        // Advance to 1452s
        fixture.video.currentTime = 1452;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Button visible at 1452s');
        assert.strictEqual(controller.isOutroActive(), true);
        assert.strictEqual(controller.getContextualOutroMode(), 'SEEK_VIDEO_END');

        // Advance to 1570s
        fixture.video.currentTime = 1570;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Button still visible near end for unbounded Outro');
    });

    test('C2.1-7. Unbounded Outro without next episode (hasNext: false): clicking Skip Outro seeks safely to video.duration (1576s)', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: false
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.skipOutroBtn.hidden, false);
        assert.strictEqual(controller.getContextualOutroMode(), 'SEEK_VIDEO_END');

        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1576, 'Seeked safely to video duration');
    });

    test('C2.1-8. Native ended event hides Outro action elements', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1500
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(controller.isOutroActive(), true, 'Outro action is active during Outro');

        fixture.video.ended = true;
        fixture.video.dispatchEvent('ended');

        assert.strictEqual(controller.isOutroActive(), false, 'Ended event deactivates Outro action');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Ended event hides Skip Outro button');
        if (fixture.nextOutroLink) {
            assert.strictEqual(fixture.nextOutroLink.hidden, true, 'Ended event hides Next Outro link');
        }
    });

    test('C2.1-9. Outro disabled (hasOutro: false) or absent: Skip Outro button is never shown and click is no-op', () => {
        const fixture = createUX01Fixture({
            hasOutro: false,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Button hidden when hasOutro is false');

        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1480, 'Position unchanged by click when disabled');
    });

    test('C2.1-10. Invalid Outro metadata: start >= duration or end > duration suppresses button and prevents unsafe seeking', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1600, // exceeds duration 1576
            videoDuration: 1576,
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Button suppressed when outroEnd exceeds video duration');

        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1480, 'Unsafe seek past duration is suppressed');
    });

    test('C2.1-11. No next episode published (hasNext: false): Skip Outro still operates cleanly', () => {
        const fixture = createUX01Fixture({
            hasNext: false,
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.nextUpPrompt, null, 'No next episode prompt in standalone / final episode');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Skip Outro button operates normally');

        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1530, 'Seeked cleanly to outroEnd');
    });

    test('C2.1-12. Controller destroy removes Skip Outro click event listener cleanly', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.getListeners('click').length, 1);

        controller.destroy();

        assert.strictEqual(fixture.skipOutroBtn.getListeners('click').length, 0);
    });

});

describe('ScreenPlayer — After-Credit Protection & Near-End Next Up Suppression (MS-08G3-UX02-C2.1-SR01)', () => {

    test('SR01-A1. Bounded Outro [1452, 1530) on 1576s with hasNext=true: Next Up prompt is suppressed during after-credits [1530..1576), appears only on ended', () => {
        const fixture = createUX01Fixture({
            hasNext: true,
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            paused: false
        });
        const controller = ScreenPlayer.init(fixture.doc);

        // 1 & 2. Skip Outro is visible at 1460, clicking seeks to 1530
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Skip Outro visible in Outro interval');
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });
        assert.strictEqual(fixture.video.currentTime, 1530, 'Seeked to Outro end');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Skip Outro hidden at Outro end');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt hidden at 1530');

        // 3. At 1531s (within standard last 45s window), Next Up prompt remains hidden to protect after-credits
        fixture.video.currentTime = 1531;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt remains hidden at 1531s');

        // 4. At 1550s and 1575s, Next Up prompt remains hidden during after-credit content
        fixture.video.currentTime = 1550;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt remains hidden at 1550s');

        fixture.video.currentTime = 1575;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt remains hidden at 1575s');

        // 6. Manual Next Episode button in control bar remains available
        assert.strictEqual(fixture.nextEpBtn !== null, true, 'Permanent Next Episode control exists');
        assert.strictEqual(fixture.nextEpBtn.getAttribute('href'), '/screen/kiem-lai-phan-1/watch/tap-2');

        // 5. At native ended, Next Up appears according to existing behavior
        fixture.video.ended = true;
        fixture.video.dispatchEvent('ended');
        assert.strictEqual(fixture.nextUpPrompt.hidden, false, 'Next Up prompt appears on native video ended');
    });

    test('SR01-A2. Unbounded Outro with next episode: suppresses floating Next Up prompt during Outro to avoid double prompt; appears on ended', () => {
        const fixture = createUX01Fixture({
            hasNext: true,
            hasOutro: true,
            outroStartSeconds: 1452,
            // unbounded: outroEndSeconds is null
            videoDuration: 1576,
            videoCurrentTime: 1500
        });
        const controller = ScreenPlayer.init(fixture.doc);

        // Before last 45s window (1500 < 1531): hidden
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Hidden before 45s window');

        // In last 45s window (1531s): primary contextual action is active, floating prompt is suppressed (Section 4.5)
        fixture.video.currentTime = 1531;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(controller.isOutroActive(), true, 'Primary contextual action is active');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Floating Next Up suppressed during Outro to prevent double prompt');

        // At native ended: Next Up prompt appears
        fixture.video.ended = true;
        fixture.video.dispatchEvent('ended');
        assert.strictEqual(fixture.nextUpPrompt.hidden, false, 'Appears on native video ended');
    });

    test('SR01-A3. No Outro configured preserves legacy near-end suggestion behavior', () => {
        const fixture = createUX01Fixture({
            hasNext: true,
            hasOutro: false,
            videoDuration: 1576,
            videoCurrentTime: 1535
        });
        const controller = ScreenPlayer.init(fixture.doc);

        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.nextUpPrompt.hidden, false, 'Appears in 45s window when no outro');
    });

    test('SR01-A4. Standalone / final episode with hasNext=false: no Next Up prompt exists or appears', () => {
        const fixture = createUX01Fixture({
            hasNext: false,
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1550
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.nextUpPrompt, null, 'nextUpPrompt is null when hasNext is false');
        fixture.video.currentTime = 1550;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(fixture.nextUpPrompt, null);
    });
});

describe('ScreenPlayer — Contextual Next Episode Navigation & Vietnamese Labels (MS-08G3-UX02-C3)', () => {

    test('C3-1. Strict Vietnamese Labels: Mode 1/3 use "Bỏ qua phần kết", Mode 2 uses "Tập tiếp theo"', () => {
        // Mode 1: Bounded Outro
        const f1 = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            hasNext: true
        });
        const c1 = ScreenPlayer.init(f1.doc);
        assert.strictEqual(c1.getContextualOutroMode(), 'SEEK_AFTER_OUTRO');
        assert.strictEqual(f1.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');
        assert.strictEqual(f1.skipOutroBtn.textContent.includes('Bỏ qua phần kết'), true);

        // Mode 2: Unbounded Outro with next episode
        const f2 = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            hasNext: true
        });
        const c2 = ScreenPlayer.init(f2.doc);
        assert.strictEqual(c2.getContextualOutroMode(), 'NAVIGATE_NEXT');
        assert.strictEqual(f2.nextOutroLink.getAttribute('aria-label'), 'Tập tiếp theo');
        assert.strictEqual(f2.nextOutroLink.textContent.includes('Tập tiếp theo'), true);

        // Mode 3: Unbounded Outro without next episode
        const f3 = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            hasNext: false
        });
        const c3 = ScreenPlayer.init(f3.doc);
        assert.strictEqual(c3.getContextualOutroMode(), 'SEEK_VIDEO_END');
        assert.strictEqual(f3.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');
        assert.strictEqual(f3.skipOutroBtn.textContent.includes('Bỏ qua phần kết'), true);

        // Prohibit all forbidden phrases across all configurations
        const forbiddenPhrases = [
            'Bỏ qua Intro', 'Bỏ qua Outro', 'Bỏ qua danh đề',
            'Bỏ qua phần giới thiệu', 'Xem tiếp sau danh đề', 'Skip Intro', 'Skip Outro'
        ];
        forbiddenPhrases.forEach(phrase => {
            assert.strictEqual(f1.skipOutroBtn.textContent.includes(phrase), false, `Must not contain forbidden phrase: ${phrase}`);
            assert.strictEqual(f2.nextOutroLink.textContent.includes(phrase), false, `Must not contain forbidden phrase: ${phrase}`);
            assert.strictEqual(f3.skipOutroBtn.textContent.includes(phrase), false, `Must not contain forbidden phrase: ${phrase}`);
        });
    });

    test('C3-2. Mode 1 (SEEK_AFTER_OUTRO): Bounded Outro [1452, 1530) on 1576s with hasNext=true shows "Bỏ qua phần kết", seeks to 1530s without navigation, preserves after-credit content', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            hasNext: true,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2'
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(controller.getContextualOutroMode(), 'SEEK_AFTER_OUTRO');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Skip Outro button visible');
        assert.strictEqual(fixture.nextOutroLink.hidden, true, 'Next Outro link hidden in Mode 1');
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');

        // Click action
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1530, 'Seeks to explicit outroEnd');
        assert.strictEqual(navigatedUrl, null, 'Never navigates to next episode in Mode 1 (after-credit preserved)');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Hides button immediately at outroEnd');
        assert.strictEqual(fixture.video.ended, undefined, 'Playback continues into after-credit content');
    });

    test('C3-3. Mode 2 (NAVIGATE_NEXT) with coordinated link: Unbounded Outro [1452, null) on 1576s with hasNext=true displays "Tập tiếp theo" link, clicking triggers navigation', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2'
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(controller.getContextualOutroMode(), 'NAVIGATE_NEXT');
        assert.strictEqual(fixture.nextOutroLink.hidden, false, 'Next Outro link visible');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Skip Outro button hidden in Mode 2');
        assert.strictEqual(fixture.nextOutroLink.getAttribute('aria-label'), 'Tập tiếp theo');

        // Click action
        let clickPrevented = false;
        fixture.nextOutroLink.dispatchEvent({
            type: 'click',
            target: fixture.nextOutroLink,
            preventDefault: () => { clickPrevented = true; },
            stopPropagation: () => {}
        });

        assert.strictEqual(clickPrevented, true, 'Click default prevented on intercepted navigate');
        assert.strictEqual(navigatedUrl, '/screen/kiem-lai-1/watch/tap-2', 'Navigates to nextUrl');
    });

    test('C3-4. Mode 2 (NAVIGATE_NEXT) keyboard activation: Space and Enter trigger navigation on nextOutroLink', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2'
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(fixture.nextOutroLink.hidden, false);

        // Keyboard Space activation
        let spacePrevented = false;
        fixture.nextOutroLink.dispatchEvent({
            type: 'keydown',
            key: ' ',
            target: fixture.nextOutroLink,
            preventDefault: () => { spacePrevented = true; }
        });
        assert.strictEqual(spacePrevented, true);
        assert.strictEqual(navigatedUrl, '/screen/kiem-lai-1/watch/tap-2');

        // Reset and test click simulation via Enter
        navigatedUrl = null;
        fixture.nextOutroLink.dispatchEvent({
            type: 'click',
            target: fixture.nextOutroLink,
            preventDefault: () => {},
            stopPropagation: () => {}
        });
        assert.strictEqual(navigatedUrl, '/screen/kiem-lai-1/watch/tap-2');
    });

    test('C3-5. Mode 2 (NAVIGATE_NEXT) standalone fallback: When nextOutroLink is absent from DOM, skipOutroBtn updates dynamically to "Tập tiếp theo" and navigates', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true,
            includeNextOutroLink: false,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2'
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(fixture.nextOutroLink, null, 'nextOutroLink element is absent');
        assert.strictEqual(controller.getContextualOutroMode(), 'NAVIGATE_NEXT');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Fallback skipOutroBtn visible');
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('aria-label'), 'Tập tiếp theo');
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('data-action'), 'navigate-next');

        // Click fallback button
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(navigatedUrl, '/screen/kiem-lai-1/watch/tap-2', 'Clicking fallback button navigates to next episode');
    });

    test('C3-6. Mode 3 (SEEK_VIDEO_END): Unbounded Outro [1452, null) on 1576s with hasNext=false displays "Bỏ qua phần kết" and seeks safely to duration', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: false
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(controller.getContextualOutroMode(), 'SEEK_VIDEO_END');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false);
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');

        // Click action
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1576, 'Seeks safely to duration');
        assert.strictEqual(navigatedUrl, null, 'Never navigates');
    });

    test('C3-7. Outro boundary tolerance (outroEnd within 0.5s of duration): [1452, 1576) on 1576s resolves to end-of-video', () => {
        // With hasNext: true -> Mode 2 (NAVIGATE_NEXT)
        const f1 = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1576, // equals duration
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true
        });
        const c1 = ScreenPlayer.init(f1.doc);
        assert.strictEqual(c1.getContextualOutroMode(), 'NAVIGATE_NEXT');
        assert.strictEqual(f1.nextOutroLink.hidden, false);
        assert.strictEqual(f1.nextOutroLink.getAttribute('aria-label'), 'Tập tiếp theo');

        // With hasNext: false -> Mode 3 (SEEK_VIDEO_END)
        const f2 = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1575.8, // within 0.5s of 1576
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: false
        });
        const c2 = ScreenPlayer.init(f2.doc);
        assert.strictEqual(c2.getContextualOutroMode(), 'SEEK_VIDEO_END');
        assert.strictEqual(f2.skipOutroBtn.hidden, false);
        assert.strictEqual(f2.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');
    });

    test('C3-8. Visual Overlap & Floating Suppression (Section 4.5): No double prompt during active Outro; restored on native ended', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true
        });
        const controller = ScreenPlayer.init(fixture.doc);

        // In Outro interval: contextual CTA is active, Next Up floating prompt is strictly suppressed
        assert.strictEqual(controller.isOutroActive(), true, 'Outro contextual action is active');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt suppressed to prevent visual overlap');

        // Advance to near end (1550s): still in Outro -> Next Up remains suppressed
        fixture.video.currentTime = 1550;
        fixture.video.dispatchEvent('timeupdate');
        assert.strictEqual(controller.isOutroActive(), true);
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'No double prompt near end');

        // Native video.ended: Outro action hides, Next Up prompt appears
        fixture.video.ended = true;
        fixture.video.dispatchEvent('ended');
        assert.strictEqual(controller.isOutroActive(), false, 'Outro action hidden on ended');
        assert.strictEqual(fixture.nextUpPrompt.hidden, false, 'Next Up prompt appears on ended');
    });

    test('C3-9. Idle viewer protection: Contextual Outro action does NOT auto-navigate', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2',
            paused: false
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        // Simulate playback progression while viewer remains idle
        for (let t = 1480; t < 1570; t += 10) {
            fixture.video.currentTime = t;
            fixture.video.dispatchEvent('timeupdate');
        }

        assert.strictEqual(navigatedUrl, null, 'Idle viewer never triggers automatic navigation');
        assert.strictEqual(controller.isOutroActive(), true, 'Contextual navigation remains available for manual click');
    });

    test('C3-10. Scenario E: Outro disabled (hasOutro: false) or absent displays no contextual Outro action', () => {
        const fixture = createUX01Fixture({
            hasOutro: false,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(controller.getContextualOutroMode(), null);
        assert.strictEqual(controller.isOutroActive(), false);
        assert.strictEqual(fixture.skipOutroBtn.hidden, true);
        if (fixture.nextOutroLink) {
            assert.strictEqual(fixture.nextOutroLink.hidden, true);
        }
    });

    test('C3-11. Lifecycle & Teardown: controller.destroy() cleanly removes event listeners on both button and link', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            videoDuration: 1576,
            videoCurrentTime: 1480,
            hasNext: true
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.getListeners('click').length, 1);
        assert.strictEqual(fixture.nextOutroLink.getListeners('click').length, 1);
        assert.strictEqual(fixture.nextOutroLink.getListeners('keydown').length, 1);

        controller.destroy();

        assert.strictEqual(fixture.skipOutroBtn.getListeners('click').length, 0);
        assert.strictEqual(fixture.nextOutroLink.getListeners('click').length, 0);
        assert.strictEqual(fixture.nextOutroLink.getListeners('keydown').length, 0);
    });

});

describe('ScreenPlayer — Prevent Inert Outro Skip Action When Video Duration Is Unknown (MS-08G3-UX02-C3-SR01)', () => {

    test('SR01-1. Unknown duration, no next episode: unbounded Outro, video.duration = NaN, no fallback -> Bỏ qua phần kết is hidden', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: false,
            videoDuration: NaN,
            durationSeconds: '',
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Bỏ qua phần kết must be hidden when duration is unknown/NaN');
        assert.strictEqual(controller.isOutroActive(), false, 'isOutroActive must be false when duration is unknown');
    });

    test('SR01-2. Infinite duration: video.duration = Infinity with no finite fallback -> Bỏ qua phần kết is hidden', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: false,
            videoDuration: Infinity,
            durationSeconds: '',
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Bỏ qua phần kết must be hidden when duration is Infinity');
        assert.strictEqual(controller.isOutroActive(), false, 'isOutroActive must be false');
    });

    test('SR01-3 & 4. Duration becomes valid via durationchange: Bỏ qua phần kết becomes visible and clicking seeks safely to 1576', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: false,
            videoDuration: NaN,
            durationSeconds: '',
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        // Initially hidden
        assert.strictEqual(fixture.skipOutroBtn.hidden, true, 'Initially hidden while duration is NaN');
        assert.strictEqual(controller.isOutroActive(), false);

        // Duration becomes valid
        fixture.video.duration = 1576;
        fixture.video.dispatchEvent('durationchange');

        // Now visible
        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Becomes visible once finite duration is available');
        assert.strictEqual(controller.isOutroActive(), true);
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');

        // Click seeks safely to 1576
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });
        assert.strictEqual(fixture.video.currentTime, 1576, 'Seeks safely to 1576');
    });

    test('SR01-3b. Duration becomes valid via loadedmetadata: Bỏ qua phần kết becomes visible', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: false,
            videoDuration: NaN,
            durationSeconds: '',
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);
        assert.strictEqual(fixture.skipOutroBtn.hidden, true);

        // Duration becomes valid on loadedmetadata
        fixture.video.duration = 1576;
        fixture.video.dispatchEvent('loadedmetadata');

        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Becomes visible after loadedmetadata provides duration');
        assert.strictEqual(controller.isOutroActive(), true);
    });

    test('SR01-5. Finite metadata fallback: data-duration-seconds is present when video.duration = NaN -> safe seek behavior preserved', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: false,
            videoDuration: NaN,
            durationSeconds: 1576, // finite metadata fallback
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, false, 'Visible via data-duration-seconds fallback');
        assert.strictEqual(controller.isOutroActive(), true);
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');

        // Clicking seeks to metadata duration
        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });
        assert.strictEqual(fixture.video.currentTime, 1576, 'Seeks safely to metadata duration');
    });

    test('SR01-6. Next episode exists: unbounded Outro with next episode exposes "Tập tiếp theo" even when duration is unknown/NaN', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: true,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2',
            videoDuration: NaN,
            durationSeconds: '',
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(controller.getContextualOutroMode(), 'NAVIGATE_NEXT');
        assert.strictEqual(fixture.nextOutroLink.hidden, false, 'Tập tiếp theo link is visible despite NaN duration');
        assert.strictEqual(fixture.skipOutroBtn.hidden, true);
        assert.strictEqual(fixture.nextOutroLink.getAttribute('aria-label'), 'Tập tiếp theo');

        // Click navigates to next episode
        fixture.nextOutroLink.dispatchEvent({
            type: 'click',
            target: fixture.nextOutroLink,
            preventDefault: () => {},
            stopPropagation: () => {}
        });
        assert.strictEqual(navigatedUrl, '/screen/kiem-lai-1/watch/tap-2', 'Successfully navigates to nextUrl');
    });

    test('SR01-7. Bounded after-credit: [1452, 1530) on duration 1576 still seeks to 1530 without navigation', () => {
        let navigatedUrl = null;
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: 1530,
            videoDuration: 1576,
            videoCurrentTime: 1460,
            hasNext: true,
            nextUrl: '/screen/kiem-lai-1/watch/tap-2'
        });
        const controller = ScreenPlayer.init(fixture.doc, {
            navigate: url => { navigatedUrl = url; }
        });

        assert.strictEqual(controller.getContextualOutroMode(), 'SEEK_AFTER_OUTRO');
        assert.strictEqual(fixture.skipOutroBtn.hidden, false);
        assert.strictEqual(fixture.nextOutroLink.hidden, true);
        assert.strictEqual(fixture.skipOutroBtn.getAttribute('aria-label'), 'Bỏ qua phần kết');

        fixture.skipOutroBtn.dispatchEvent({
            type: 'click',
            target: fixture.skipOutroBtn,
            preventDefault: () => {},
            stopPropagation: () => {}
        });

        assert.strictEqual(fixture.video.currentTime, 1530, 'Seeks to explicit outroEnd (1530)');
        assert.strictEqual(navigatedUrl, null, 'Never navigates');
    });

    test('SR01-8. Next Up suppression: no duplicate floating CTA is introduced during duration recovery in Outro interval', () => {
        const fixture = createUX01Fixture({
            hasOutro: true,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: true,
            videoDuration: NaN,
            durationSeconds: '',
            videoCurrentTime: 1540
        });
        const controller = ScreenPlayer.init(fixture.doc);

        // Before duration update: nextUpPrompt is suppressed by active Outro navigation link
        assert.strictEqual(fixture.nextOutroLink.hidden, false, 'Next Outro link visible');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt suppressed');

        // Duration update occurs
        fixture.video.duration = 1576;
        fixture.video.dispatchEvent('durationchange');

        // Still in Outro: Next Up prompt remains suppressed, only 1 primary CTA is visible
        assert.strictEqual(fixture.nextOutroLink.hidden, false, 'Next Outro link remains visible');
        assert.strictEqual(fixture.nextUpPrompt.hidden, true, 'Next Up prompt remains suppressed (no double prompt)');

        // When ended fires: Next Up prompt appears and Outro action hides
        fixture.video.ended = true;
        fixture.video.dispatchEvent('ended');
        assert.strictEqual(fixture.nextOutroLink.hidden, true, 'Outro action hidden on ended');
        assert.strictEqual(fixture.nextUpPrompt.hidden, false, 'Next Up prompt appears on ended');
    });

    test('SR01-9. Disabled/absent Outro: hasOutro=false never displays contextual action regardless of duration updates', () => {
        const fixture = createUX01Fixture({
            hasOutro: false,
            outroStartSeconds: 1452,
            outroEndSeconds: null,
            hasNext: false,
            videoDuration: NaN,
            durationSeconds: '',
            videoCurrentTime: 1480
        });
        const controller = ScreenPlayer.init(fixture.doc);

        assert.strictEqual(fixture.skipOutroBtn.hidden, true);
        assert.strictEqual(controller.isOutroActive(), false);

        // Even after duration becomes valid
        fixture.video.duration = 1576;
        fixture.video.dispatchEvent('durationchange');

        assert.strictEqual(fixture.skipOutroBtn.hidden, true);
        assert.strictEqual(controller.isOutroActive(), false);
    });

});
