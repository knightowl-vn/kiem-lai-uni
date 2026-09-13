const { test, describe } = require('node:test');
const assert = require('node:assert');
const path = require('path');
const fs = require('fs');
const vm = require('vm');

function createControllerEnv(customEnv = {}) {
    const env = {
        console: console,
        setTimeout: setTimeout,
        clearTimeout: clearTimeout,
        Date: Date,
        window: {
            location: { href: 'http://localhost' },
            clearTimeout: clearTimeout,
            history: { pushState: () => {} },
            addEventListener: () => {},
            removeEventListener: () => {}
        },
        document: {
            querySelector: () => null,
            getElementById: () => null,
            dispatchEvent: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            title: ''
        },
        CustomEvent: class {},
        AbortController: class {
            constructor() {
                this.signal = {
                    aborted: false,
                    _listeners: [],
                    addEventListener(type, fn, opts) {
                        this._listeners.push(fn);
                    },
                    removeEventListener(type, fn) {
                        this._listeners = this._listeners.filter(l => l !== fn);
                    }
                };
            }
            abort() {
                this.signal.aborted = true;
                for (const fn of this.signal._listeners) {
                    try { fn(); } catch (ignored) {}
                }
            }
        },
        DOMParser: class {
            parseFromString() {
                return { title: 'Test', querySelector: () => ({}), getElementById: () => ({}) };
            }
        },
        fetch: async () => ({ ok: true, text: async () => '<html></html>' }),
        localStorage: {
            getItem: () => null,
            setItem: () => {},
            removeItem: () => {}
        },
        ...customEnv
    };

    const controllerSrc = fs.readFileSync(path.join(__dirname, '../../../main/resources/static/js/novel/narration-controller.js'), 'utf8');
    vm.runInNewContext(controllerSrc, env);
    return env;
}

function createMockDom() {
    const createEl = () => {
        const attrs = {};
        return {
            setAttribute: (k, v) => { attrs[k] = String(v); },
            getAttribute: (k) => Object.prototype.hasOwnProperty.call(attrs, k) ? attrs[k] : null,
            hasAttribute: (k) => Object.prototype.hasOwnProperty.call(attrs, k),
            removeAttribute: (k) => { delete attrs[k]; },
            classList: { add: () => {}, remove: () => {}, toggle: () => {} }
        };
    };
    const createBtn = () => {
        const el = createEl();
        return {
            ...el,
            disabled: false,
            addEventListener: () => {},
            removeEventListener: () => {}
        };
    };
    return {
        body: { setAttribute: () => {}, querySelectorAll: () => [], innerHTML: '' },
        player: createEl(),
        playPauseBtn: createBtn(),
        playIcon: { style: {} },
        pauseIcon: { style: {} },
        voiceSelect: {
            value: '',
            disabled: false,
            querySelectorAll: () => [],
            appendChild: () => {},
            innerHTML: '',
            addEventListener: () => {},
            removeEventListener: () => {}
        },
        prevBtn: createBtn(),
        nextBtn: createBtn(),
        rewindBtn: createBtn(),
        forwardBtn: createBtn(),
        progressBar: { setAttribute: () => {}, getBoundingClientRect: () => ({ width: 100, left: 0 }), addEventListener: () => {}, removeEventListener: () => {} },
        progressFill: { style: {} },
        progressCurrent: {},
        progressTotal: {},
        statusText: { ...createEl(), textContent: '' }
    };
}

describe('MS-04.9H.9 — H.9I5C2A Manual Managed Play On-Demand Preparation Tests', () => {

    test('1. page init performs zero preparation POST', () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.init();

        assert.strictEqual(prepareCalled, 0, 'init() must perform zero preparation POST');
    });

    test('2. initial/default/saved Managed voice activation performs zero preparation POST', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                loadPlayback: async () => { throw new Error('ChapterAudio unavailable'); },
                stop: () => {},
                getSegments: () => []
            }
        });
        ctrl.dom = createMockDom();
        ctrl.dom.voiceSelect.value = 'managed:voice-alpha';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-alpha' };

        ctrl._activateEngine('managed', 'voice-alpha');

        assert.strictEqual(prepareCalled, 0, 'voice activation must perform zero preparation POST');
    });

    test('3. explicit Managed voice selection with missing playback performs zero preparation POST and remains eligible for later Play', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let lastStatus = '';
        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                loadPlayback: async () => { throw new Error('ChapterAudio unavailable'); },
                stop: () => {},
                getSegments: () => []
            }
        });
        ctrl.dom = createMockDom();
        ctrl.dom.voiceSelect.value = 'managed:voice-beta';
        ctrl._setStatusMessage = (msg) => { lastStatus = msg; };

        await ctrl._handleVoiceChange();

        assert.strictEqual(prepareCalled, 0, 'voice selection must perform zero preparation POST');
        assert.strictEqual(ctrl.activeEngineType, 'managed', 'Managed engine must remain active authority');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play button must remain enabled for later user intent');
        assert.ok(lastStatus.includes('Nhấn Phát để chuẩn bị giọng đọc'), 'Status must guide user to press Play');
        assert.ok(ctrl.savedVoicePreference, 'Durable preference must exist');
        assert.strictEqual(ctrl.savedVoicePreference.type, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-beta');
    });

    test('4. user Play with already loaded ChapterAudio plays immediately and performs zero POST/poll', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let probeCalled = 0;
        let playCalled = 0;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async () => { probeCalled++; return { status: 'ready' }; },
                play: () => { playCalled++; },
                stop: () => {},
                getState: () => 'IDLE',
                metadata: { chapterId: 'ch-1', voiceKey: 'voice-1', availability: 'READY', playable: true },
                getSelectedVoiceKey: () => 'voice-1',
                getSegments: () => [{ id: 'seg-1' }],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [{ id: 'seg-1' }];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        ctrl._handlePlayPause();

        assert.strictEqual(playCalled, 1, 'Loaded ChapterAudio must play immediately');
        assert.strictEqual(prepareCalled, 0, 'Zero prepare POST');
        assert.strictEqual(probeCalled, 0, 'Zero probe/poll');
    });

    test('5. user Play -> passive probe READY: no POST, loadPlayback receives ready metadata, play exactly once', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let probeCalled = 0;
        let loadCalledWith = null;
        let playCalled = 0;

        const readyMetadata = { chapterId: 'ch-1', voiceKey: 'voice-1', audioUrl: '/audio.mp3', availability: 'READY' };

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async () => { probeCalled++; return { status: 'ready', metadata: readyMetadata }; },
                loadPlayback: async (chId, vKey, meta) => {
                    loadCalledWith = meta;
                    return meta;
                },
                play: () => { playCalled++; },
                stop: () => {},
                setRate: () => {},
                seekBySeconds: () => {},
                getProgress: () => ({ durationSeconds: 10, currentTimeSeconds: 0 }),
                getSegments: () => [{ id: 's1' }],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = []; // not loaded yet
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        await ctrl._handlePlayPause();

        assert.strictEqual(probeCalled, 1, 'Passive probe called once');
        assert.strictEqual(prepareCalled, 0, 'No prepare POST on ready probe');
        assert.strictEqual(loadCalledWith, readyMetadata, 'loadPlayback received probe metadata');
        assert.strictEqual(playCalled, 1, 'ChapterAudio played exactly once');
        assert.strictEqual(ctrl.chunks.length, 1);
    });

    test('6. user Play -> probe unavailable -> POST BUILDING -> poll unavailable -> poll READY -> load -> play exactly once', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let probeCallCount = 0;
        let loadCalled = 0;
        let playCalled = 0;

        const readyMetadata = { chapterId: 'ch-1', voiceKey: 'voice-1', audioUrl: '/audio.mp3', availability: 'READY' };

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 5,
            managedPreparationTimeoutMs: 500,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => {
                    probeCallCount++;
                    if (probeCallCount === 1) return { status: 'unavailable' }; // initial probe
                    if (probeCallCount === 2) return { status: 'unavailable' }; // 1st poll
                    return { status: 'ready', metadata: readyMetadata };        // 2nd poll
                },
                requestPlaybackPreparation: async () => {
                    prepareCalled++;
                    return { status: 'BUILDING' };
                },
                loadPlayback: async () => {
                    loadCalled++;
                    return readyMetadata;
                },
                play: () => { playCalled++; },
                stop: () => {},
                setRate: () => {},
                seekBySeconds: () => {},
                getProgress: () => ({ durationSeconds: 10, currentTimeSeconds: 0 }),
                getSegments: () => [{ id: 's1' }],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        await ctrl._handlePlayPause();

        assert.strictEqual(prepareCalled, 1, 'POST /prepare called exactly once');
        assert.strictEqual(probeCallCount, 3, 'Initial probe + 2 poll probes');
        assert.strictEqual(loadCalled, 1, 'loadPlayback called once on poll success');
        assert.strictEqual(playCalled, 1, 'play called exactly once on poll success');
    });

    test('7. after accepted BUILDING, unavailable polling result does NOT fail early', async () => {
        const env = createControllerEnv();
        let probeCount = 0;
        let completed = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 2,
            managedPreparationTimeoutMs: 500,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => {
                    probeCount++;
                    if (probeCount <= 3) return { status: 'unavailable' };
                    return { status: 'ready', metadata: { chapterId: 'ch-1' } };
                },
                requestPlaybackPreparation: async () => ({ status: 'BUILDING' }),
                loadPlayback: async () => ({ chapterId: 'ch-1' }),
                play: () => { completed = true; },
                stop: () => {},
                setRate: () => {},
                seekBySeconds: () => {},
                getProgress: () => ({ durationSeconds: 10 }),
                getSegments: () => [{}],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        await ctrl._handlePlayPause();

        assert.ok(probeCount >= 4, 'Must poll across multiple unavailable results');
        assert.strictEqual(completed, true, 'Must complete when poll returns ready');
    });

    test('8. after accepted BUILDING, unknown polling result does NOT fail early', async () => {
        const env = createControllerEnv();
        let probeCount = 0;
        let completed = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 2,
            managedPreparationTimeoutMs: 500,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => {
                    probeCount++;
                    if (probeCount <= 3) return { status: 'unknown' };
                    return { status: 'ready', metadata: { chapterId: 'ch-1' } };
                },
                requestPlaybackPreparation: async () => ({ status: 'BUILDING' }),
                loadPlayback: async () => ({ chapterId: 'ch-1' }),
                play: () => { completed = true; },
                stop: () => {},
                setRate: () => {},
                seekBySeconds: () => {},
                getProgress: () => ({ durationSeconds: 10 }),
                getSegments: () => [{}],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        await ctrl._handlePlayPause();

        assert.ok(probeCount >= 4, 'Must poll across multiple unknown results');
        assert.strictEqual(completed, true, 'Must complete when poll returns ready');
    });

    test('9. exact same in-flight user Play intent does not issue duplicate POST or duplicate poll loop', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let resolvePrepare;
        const preparePromise = new Promise(r => { resolvePrepare = r; });

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 10,
            managedPreparationTimeoutMs: 500,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => {
                    return { status: 'unavailable' };
                },
                requestPlaybackPreparation: async () => {
                    prepareCalled++;
                    await preparePromise;
                    return { status: 'BUILDING' };
                },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        // Trigger first Play call
        const p1 = ctrl._handlePlayPause();
        // Give microtask tick so probe finishes and enters requestPlaybackPreparation
        await new Promise(r => setTimeout(r, 5));

        // Trigger second Play call while requestPlaybackPreparation is in flight
        const p2 = ctrl._handlePlayPause();

        assert.strictEqual(p1, p2, 'Duplicate intent must return the exact same promise');

        resolvePrepare();
        ctrl._cancelManagedPreparation();
        await p1;

        assert.strictEqual(prepareCalled, 1, 'Only one prepare POST issued for duplicate intents');
    });

    test('10. voice change during POST aborts and stale result cannot load/play/fallback', async () => {
        const env = createControllerEnv();
        const loadedVoiceKeys = [];
        let playCalled = false;
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => {
                    await new Promise(r => setTimeout(r, 30));
                    return { status: 'BUILDING' };
                },
                loadPlayback: async (chId, vKey) => { loadedVoiceKeys.push(vKey); },
                play: () => { playCalled = true; },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; };

        const playPromise = ctrl._handlePlayPause();

        // Stale intent interrupted by user switching voice
        ctrl.dom.voiceSelect.value = 'managed:voice-2';
        await ctrl._handleVoiceChange();

        await playPromise;

        assert.strictEqual(loadedVoiceKeys.includes('voice-1'), false, 'Stale voice-1 intent cannot load');
        assert.strictEqual(playCalled, false, 'Stale intent cannot play');
        assert.strictEqual(fallbackCalled, false, 'Cancellation cannot invoke fallback');
    });

    test('11. voice change during polling aborts and stale READY cannot load/play', async () => {
        const env = createControllerEnv();
        const loadedVoiceKeys = [];
        let playCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 20,
            managedPreparationTimeoutMs: 500,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => {
                    return { status: 'unavailable' };
                },
                requestPlaybackPreparation: async () => ({ status: 'BUILDING' }),
                loadPlayback: async (chId, vKey) => { loadedVoiceKeys.push(vKey); },
                play: () => { playCalled = true; },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        const playPromise = ctrl._handlePlayPause();

        // During polling, voice changes
        setTimeout(() => {
            ctrl.dom.voiceSelect.value = 'managed:voice-2';
            ctrl._handleVoiceChange();
        }, 5);

        await playPromise;

        assert.strictEqual(loadedVoiceKeys.includes('voice-1'), false, 'Stale poll cannot load voice-1');
        assert.strictEqual(playCalled, false, 'Stale poll cannot play');
    });

    test('12. chapter/navigation supersession cancels pending preparation', async () => {
        const env = createControllerEnv();
        let playCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 20,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => {
                    await new Promise(r => setTimeout(r, 20));
                    return { status: 'BUILDING' };
                },
                play: () => { playCalled = true; },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        const playPromise = ctrl._handlePlayPause();

        // Chapter navigation supersession
        ctrl._invalidateChapterPlayback();

        await playPromise;

        assert.strictEqual(playCalled, false, 'Superseded intent must not play');
    });

    test('13. unload/destroy cancels pending preparation', async () => {
        const env = createControllerEnv();
        let playCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => {
                    await new Promise(r => setTimeout(r, 20));
                    return { status: 'BUILDING' };
                },
                play: () => { playCalled = true; },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        const playPromise = ctrl._handlePlayPause();

        ctrl.destroy();

        await playPromise;

        assert.strictEqual(playCalled, false, 'Destroyed controller must not play');
        assert.strictEqual(ctrl.isUnloaded, true);
    });

    test('14. AbortError never invokes fallback and never displays failure', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;
        let lastStatus = '';

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => {
                    const err = new Error('The operation was aborted');
                    err.name = 'AbortError';
                    throw err;
                },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; };
        ctrl._setStatusMessage = (msg) => { lastStatus = msg; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, false, 'AbortError must never invoke fallback');
        assert.ok(!lastStatus.includes('Không thể tải'), 'AbortError must never show failure notice');
    });

    test('15. REJECTED terminal command outcome follows safe playback-failure policy', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: true,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'REJECTED' }),
                stop: () => {}
            },
            deviceEngine: {
                isSupported: () => true,
                loadChunks: () => {},
                play: () => {},
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, true, 'REJECTED must invoke fallback policy when enabled');
        assert.ok(ctrl.savedVoicePreference, 'Durable preference preserved');
        assert.strictEqual(ctrl.savedVoicePreference.type, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
    });

    test('16. UNAVAILABLE terminal command outcome follows safe playback-failure policy', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: true,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'UNAVAILABLE' }),
                stop: () => {}
            },
            deviceEngine: {
                isSupported: () => true,
                loadChunks: () => {},
                play: () => {},
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, true, 'UNAVAILABLE must invoke fallback policy when enabled');
        assert.ok(ctrl.savedVoicePreference, 'Durable preference preserved');
        assert.strictEqual(ctrl.savedVoicePreference.type, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
    });

    test('17. UNKNOWN terminal command outcome follows safe playback-failure policy', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: true,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'UNKNOWN' }),
                stop: () => {}
            },
            deviceEngine: {
                isSupported: () => true,
                loadChunks: () => {},
                play: () => {},
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, true, 'UNKNOWN must invoke fallback policy when enabled');
        assert.ok(ctrl.savedVoicePreference, 'Durable preference preserved');
        assert.strictEqual(ctrl.savedVoicePreference.type, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
    });

    test('18. timeout stops polling and follows safe failure/fallback policy', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: true,
            managedPreparationPollIntervalMs: 5,
            managedPreparationTimeoutMs: 15, // short timeout
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'BUILDING' }),
                stop: () => {}
            },
            deviceEngine: {
                isSupported: () => true,
                loadChunks: () => {},
                play: () => {},
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, true, 'Timeout must invoke fallback policy when enabled');
        assert.ok(ctrl.savedVoicePreference, 'Durable preference preserved');
        assert.strictEqual(ctrl.savedVoicePreference.type, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
    });

    test('19. Device Play path remains unchanged and issues zero Managed preparation calls', () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let probeCalled = 0;
        let devicePlayCalled = 0;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async () => { probeCalled++; return { status: 'ready' }; },
                stop: () => {}
            },
            deviceEngine: {
                isSupported: () => true,
                play: () => { devicePlayCalled++; },
                stop: () => {},
                getState: () => 'IDLE',
                getCurrentChunkIndex: () => 0
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'device';
        ctrl.engine = ctrl.deviceEngine;
        ctrl.chunks = [{ text: 'Sentence 1' }];

        ctrl._handlePlayPause();

        assert.strictEqual(devicePlayCalled, 1, 'Device engine played');
        assert.strictEqual(prepareCalled, 0, 'Zero Managed preparation POST');
        assert.strictEqual(probeCalled, 0, 'Zero Managed probe');
    });

    test('20. speculative preload remains GET-only and issues zero preparation POST', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let probeCalled = 0;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            autoNext: true,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => { probeCalled++; return { status: 'unknown' }; },
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                stop: () => {},
                getState: () => 'PLAYING'
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [{ startMillis: 0, endMillis: 10000 }];

        // Trigger next chapter preload check
        ctrl._checkAndTriggerNextChapterPreload({ currentTimeSeconds: 8, durationSeconds: 10 });

        assert.strictEqual(prepareCalled, 0, 'Preload must never call requestPlaybackPreparation');
    });

    test('21. H.9I5C2B: Committed Auto Next continuation invokes requestPlaybackPreparation when playback is unavailable', async () => {
        const env = createControllerEnv();
        let prepareCalled = 0;
        let prepareChapterId = null;
        let prepareVoiceKey = null;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 5,
            managedPreparationTimeoutMs: 15,
            chapterEngine: {
                isSupported: () => true,
                loadPlayback: async () => { throw new Error('ChapterAudio unavailable'); },
                requestPlaybackPreparation: async (ch, vk) => {
                    prepareCalled++;
                    prepareChapterId = ch;
                    prepareVoiceKey = vk;
                    return { status: 'BUILDING' };
                },
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                stop: () => {},
                getSelectedVoiceKey: () => 'voice-1'
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;

        const validation = {
            newChapterId: 'ch-2',
            title: 'Chapter 2',
            bodyEl: { innerHTML: '<p>Chapter 2</p>' }
        };
        const mockDoc = { getElementById: () => null, querySelector: () => null };

        ctrl._applyChapterTransition(mockDoc, '/ch-2', validation, { mode: 'managed', voiceKey: 'voice-1' });

        await new Promise(r => setTimeout(r, 50));

        assert.strictEqual(prepareCalled, 1, 'Committed Auto Next continuation must invoke requestPlaybackPreparation');
        assert.strictEqual(prepareChapterId, 'ch-2', 'Preparation must target committed Chapter B');
        assert.strictEqual(prepareVoiceKey, 'voice-1', 'Preparation must preserve exact Chapter A voiceKey');
    });

    test('22. H.9I5C2A1: timeout + fallback OFF leaves Play enabled, busy cleared, keeps Managed pref, and retry succeeds when ready', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;
        let probeCount = 0;
        let playCount = 0;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: false,
            managedPreparationPollIntervalMs: 5,
            managedPreparationTimeoutMs: 15,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => {
                    probeCount++;
                    if (probeCount <= 10) {
                        return { status: 'unavailable' };
                    }
                    return {
                        status: 'ready',
                        metadata: { chapterId: 'ch-1', voiceKey: 'voice-1', audioUrl: '/ch1.mp3', availability: 'READY' }
                    };
                },
                requestPlaybackPreparation: async () => ({ status: 'BUILDING' }),
                loadPlayback: async (ch, vk, meta) => meta,
                play: () => { playCount++; },
                stop: () => {},
                getSegments: () => [{ id: 'seg-1' }],
                seekBySeconds: () => {},
                getProgress: () => ({ currentTimeSeconds: 0, durationSeconds: 60, progressRatio: 0 }),
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        // Attempt 1 -> times out
        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, false, 'Fallback must not be invoked when fallbackToDevice is false');
        assert.strictEqual(ctrl.dom.player.getAttribute('aria-busy'), 'false', 'aria-busy on player must be cleared');
        assert.strictEqual(ctrl.dom.statusText.getAttribute('aria-busy'), 'false', 'aria-busy on statusText must be cleared');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play button must remain enabled for user retry');
        assert.strictEqual(ctrl.dom.playPauseBtn.hasAttribute('aria-disabled'), false, 'aria-disabled must be removed');
        assert.strictEqual(ctrl.activeEngineType, 'managed', 'Managed engine must remain active authority');
        assert.strictEqual(ctrl.savedVoicePreference.type, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
        assert.strictEqual(ctrl._activePreparationPromise, null, 'Active preparation promise reference cleared');
        assert.strictEqual(ctrl._preparationAbortController, null, 'Active preparation abort controller reference cleared');

        // Attempt 2 -> backend finished building, probe discovers ready -> succeeds and plays
        probeCount = 100; // force ready branch
        await ctrl._handlePlayPause();

        assert.strictEqual(playCount, 1, 'Retry Play must load and play successfully without being trapped');
        assert.strictEqual(ctrl.chunks.length, 1);
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false);
    });

    test('23. H.9I5C2A1: REJECTED + fallback OFF leaves Play enabled and retryable', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: false,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'REJECTED' }),
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, false);
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play must remain enabled for later retry');
        assert.strictEqual(ctrl.dom.playPauseBtn.hasAttribute('aria-disabled'), false);
        assert.strictEqual(ctrl.dom.player.getAttribute('aria-busy'), 'false');
        assert.strictEqual(ctrl.activeEngineType, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
        assert.strictEqual(ctrl._activePreparationPromise, null);
        assert.strictEqual(ctrl._preparationAbortController, null);
    });

    test('24. H.9I5C2A1: UNKNOWN + fallback OFF leaves Play enabled and retryable', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: false,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'UNKNOWN' }),
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-1' };
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };

        await ctrl._handlePlayPause();

        assert.strictEqual(fallbackCalled, false);
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play must remain enabled for later retry');
        assert.strictEqual(ctrl.dom.playPauseBtn.hasAttribute('aria-disabled'), false);
        assert.strictEqual(ctrl.dom.player.getAttribute('aria-busy'), 'false');
        assert.strictEqual(ctrl.activeEngineType, 'managed');
        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-1');
        assert.strictEqual(ctrl._activePreparationPromise, null);
        assert.strictEqual(ctrl._preparationAbortController, null);
    });

    test('25. H.9I5C2A1: AbortError while operation still owns UI clears busy, restores Play, and does not show failure message', async () => {
        const env = createControllerEnv();
        let fallbackCalled = false;
        let lastStatus = '';

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            fallbackToDevice: false,
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async (ch, vk, opts) => {
                    return new Promise((resolve, reject) => {
                        if (opts && opts.signal) {
                            opts.signal.addEventListener('abort', () => {
                                const err = new Error('The operation was aborted');
                                err.name = 'AbortError';
                                reject(err);
                            });
                        }
                    });
                },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';
        ctrl._fallbackToDeviceTts = () => { fallbackCalled = true; return true; };
        ctrl._setStatusMessage = (msg) => { lastStatus = msg; };

        const prepPromise = ctrl._handlePlayPause();

        assert.strictEqual(ctrl.dom.player.getAttribute('aria-busy'), 'true');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, true);
        assert.ok(ctrl._preparationAbortController, 'Abort controller must be active during preparation');

        // Abort the active controller directly
        ctrl._preparationAbortController.abort();
        await prepPromise;

        assert.strictEqual(fallbackCalled, false, 'Abort must never trigger fallback');
        assert.strictEqual(ctrl.dom.player.getAttribute('aria-busy'), 'false', 'aria-busy must clear on abort');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play button must be restored');
        assert.strictEqual(ctrl.dom.playPauseBtn.hasAttribute('aria-disabled'), false);
        assert.strictEqual(ctrl._activePreparationPromise, null, 'Active promise ref cleared');
        assert.strictEqual(ctrl._preparationAbortController, null, 'Abort controller ref cleared');
        assert.ok(!lastStatus.includes('Không thể tải'), 'No failure message shown');
        assert.ok(!lastStatus.includes('Chưa thể phát'), 'No failure message shown');
    });

    test('26. H.9I5C2A1: voice change during in-flight preparation does not allow stale promise to overwrite new voice UI', async () => {
        const env = createControllerEnv();

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async (ch, vk, opts) => {
                    return new Promise((resolve, reject) => {
                        if (opts && opts.signal) {
                            opts.signal.addEventListener('abort', () => {
                                const err = new Error('The operation was aborted');
                                err.name = 'AbortError';
                                reject(err);
                            });
                        }
                    });
                },
                loadPlayback: async () => { throw new Error('unavailable'); },
                stop: () => {},
                getSegments: () => []
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-A';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-A' };

        const prepA = ctrl._handlePlayPause();

        // User changes voice to voice-B
        ctrl.dom.voiceSelect.value = 'managed:voice-B';
        await ctrl._handleVoiceChange();

        await prepA;

        assert.strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-B', 'New voice preference must remain intact');
        assert.strictEqual(ctrl.dom.voiceSelect.value, 'managed:voice-B', 'Voice select must remain on new voice');
        assert.strictEqual(ctrl.dom.player.getAttribute('aria-busy'), 'false');
    });

    test('27. H.9I5C2A1: chapter change / unload cancellation isolates stale cleanup from new state', async () => {
        const env = createControllerEnv();

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                probePlaybackMetadata: async (ch, vk, opts) => {
                    return new Promise((resolve, reject) => {
                        if (opts && opts.signal) {
                            opts.signal.addEventListener('abort', () => {
                                const err = new Error('The operation was aborted');
                                err.name = 'AbortError';
                                reject(err);
                            });
                        }
                    });
                },
                stop: () => {}
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [];
        ctrl.dom.voiceSelect.value = 'managed:voice-1';

        const p = ctrl._handlePlayPause();
        ctrl._handleUnload();
        await p;

        assert.strictEqual(ctrl.isUnloaded, true);
        assert.strictEqual(ctrl._activePreparationPromise, null);
        assert.strictEqual(ctrl._preparationAbortController, null);
    });

    test('28. H.9I5C2A1: already-loaded immediate Play requires matching metadata.voiceKey', async () => {
        const env = createControllerEnv();
        let playCalled = 0;
        let prepareCalled = 0;
        let probeCalled = 0;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async () => { prepareCalled++; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async () => { probeCalled++; return { status: 'ready' }; },
                play: () => { playCalled++; },
                stop: () => {},
                getState: () => 'IDLE',
                metadata: { chapterId: 'ch-1', voiceKey: 'voice-matching', availability: 'READY', playable: true },
                getSelectedVoiceKey: () => 'voice-matching',
                getSegments: () => [{ id: 'seg-1' }],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [{ id: 'seg-1' }];
        ctrl.dom.voiceSelect.value = 'managed:voice-matching';

        ctrl._handlePlayPause();

        assert.strictEqual(playCalled, 1, 'Matching voiceKey plays immediately');
        assert.strictEqual(prepareCalled, 0, 'Zero prepare POST');
        assert.strictEqual(probeCalled, 0, 'Zero probe');
    });

    test('29. H.9I5C2A1: same chapter but wrong metadata.voiceKey enters on-demand preparation and does not immediately play', async () => {
        const env = createControllerEnv();
        let playCalled = 0;
        let probeVoiceKey = null;
        let prepareVoiceKey = null;

        const ctrl = new env.NarrationController.NarrationController({
            chapterId: 'ch-1',
            managedPreparationPollIntervalMs: 5,
            managedPreparationTimeoutMs: 15,
            chapterEngine: {
                isSupported: () => true,
                requestPlaybackPreparation: async (ch, vk) => { prepareVoiceKey = vk; return { status: 'BUILDING' }; },
                probePlaybackMetadata: async (ch, vk) => { probeVoiceKey = vk; return { status: 'unavailable' }; },
                play: () => { playCalled++; },
                stop: () => {},
                getState: () => 'IDLE',
                metadata: { chapterId: 'ch-1', voiceKey: 'voice-old', availability: 'READY', playable: true },
                getSelectedVoiceKey: () => 'voice-new',
                getSegments: () => [{ id: 'seg-old' }],
                getCurrentChunkIndex: () => 0,
                canPrevious: () => false,
                canNext: () => false
            }
        });
        ctrl.dom = createMockDom();
        ctrl.activeEngineType = 'managed';
        ctrl.engine = ctrl.chapterEngine;
        ctrl.chunks = [{ id: 'seg-old' }];
        ctrl.dom.voiceSelect.value = 'managed:voice-new'; // newly selected voice!

        await ctrl._handlePlayPause();

        assert.strictEqual(playCalled, 0, 'Must NOT immediately play when loaded metadata voiceKey does not match selected voiceKey');
        assert.strictEqual(probeVoiceKey, 'voice-new', 'Must probe for the newly selected voice');
        assert.strictEqual(prepareVoiceKey, 'voice-new', 'Must prepare for the newly selected voice');
    });

});

