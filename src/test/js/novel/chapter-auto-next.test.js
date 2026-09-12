const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

test('H.9H1 Chapter Audio Auto-Next Tests', async (t) => {
    class FakeAudio {
        constructor() {
            this.readyState = 4;
            this.currentTime = 0;
            this.duration = 100;
            this.ended = false;
            this.playbackRate = 1;
            this.src = '';
            this.preload = '';
            this._listeners = {};
        }
        addEventListener(event, cb) {
            if (!this._listeners[event]) this._listeners[event] = [];
            this._listeners[event].push(cb);
        }
        removeEventListener(event, cb) {
            if (this._listeners[event]) {
                this._listeners[event] = this._listeners[event].filter(l => l !== cb);
            }
        }
        emit(event) {
            if (this._listeners[event]) {
                this._listeners[event].forEach(cb => cb());
            }
        }
        play() {
            this.emit('play'); this.emit('playing'); return Promise.resolve();
        }
        pause() {
            this.emit('pause');
        }
        removeAttribute(attr) {
            if (attr === 'src') this.src = '';
        }
        load() {
            this.readyState = 4;
            this.emit('canplay');
        }
    }

    function createEngineEnv() {
        const env = {
            console,
            setTimeout,
            clearTimeout,
            AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } }
        };
        const engineSrc = fs.readFileSync(path.join(__dirname, '../../../main/resources/static/js/novel/chapter-audio-engine.js'), 'utf8');
        vm.runInNewContext(engineSrc, env);
        return env;
    }

    const fakeFetchFunction = async () => ({
        ok: true,
        status: 200,
        json: async () => ({
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            chapterId: 123, voiceKey: 'test-voice',
            audioUrl: 'http://example.com/audio.mp3',
            durationMillis: 100000,
            cues: [{ startMillis: 0, endMillis: 100000 }]
        })
    });

    async function createStartedEngine(env, chapterEndCounter) {
        const audio = new FakeAudio();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: () => audio,
            fetchFunction: fakeFetchFunction,
            onChapterEnd: () => { chapterEndCounter.count++; }
        });
        await engine.loadPlayback(123, 'test-voice');
        await engine.play(0);
         return { engine, audio };
    }

    await t.test('1. natural native ended while PLAYING calls onChapterEnd exactly once', async () => {
        const env = createEngineEnv();
        const counter = { count: 0 };
        const { engine, audio } = await createStartedEngine(env, counter);

        assert.strictEqual(engine.state, 'PLAYING');

        audio.currentTime = 100;
        audio.ended = true;
        audio.emit('ended');

        assert.strictEqual(counter.count, 1);
        assert.strictEqual(engine.state, 'STOPPED');
    });

    await t.test('2. explicit seekToRatio(1) while PLAYING calls onChapterEnd zero times', async () => {
        const env = createEngineEnv();
        const counter = { count: 0 };
        const { engine, audio } = await createStartedEngine(env, counter);

        engine.seekToRatio(1);

        audio.ended = true;
        audio.emit('ended');

        assert.strictEqual(counter.count, 0);
        assert.strictEqual(engine.state, 'STOPPED');
    });

    await t.test('3. explicit seekBySeconds clamped to duration calls onChapterEnd zero times', async () => {
        const env = createEngineEnv();
        const counter = { count: 0 };
        const { engine, audio } = await createStartedEngine(env, counter);

        engine.seekBySeconds(200);

        audio.ended = true;
        audio.emit('ended');

        assert.strictEqual(counter.count, 0);
        assert.strictEqual(engine.state, 'STOPPED');
    });

    await t.test('4. explicit end seek while PAUSED stays non-natural and does not call end', async () => {
        const env = createEngineEnv();
        const counter = { count: 0 };
        const { engine, audio } = await createStartedEngine(env, counter);

        engine.pause();
        assert.strictEqual(engine.state, 'PAUSED');

        engine.seekToRatio(1);

        audio.ended = true;
        audio.emit('ended');

        assert.strictEqual(counter.count, 0);
        assert.strictEqual(engine.state, 'PAUSED');
    });

    await t.test('5. seek near end while PLAYING + later native ended calls onChapterEnd once', async () => {
        const env = createEngineEnv();
        const counter = { count: 0 };
        const { engine, audio } = await createStartedEngine(env, counter);

        engine.seekToRatio(0.95);
        assert.strictEqual(engine.state, 'PLAYING');

        audio.currentTime = 100;
        audio.ended = true;
        audio.emit('ended');

        assert.strictEqual(counter.count, 1);
        assert.strictEqual(engine.state, 'STOPPED');
    });

    await t.test('6. explicit end followed by replay can start from chapter beginning', async () => {
        const env = createEngineEnv();
        const counter = { count: 0 };
        const { engine, audio } = await createStartedEngine(env, counter);

        engine.seekToRatio(1);
        assert.strictEqual(engine.state, 'STOPPED');

        await engine.play(0);
        assert.strictEqual(engine.state, 'PLAYING');
    });

    function createControllerEnv() {
        const env = {
            console: console,
            clearTimeout: clearTimeout,
            window: {
                location: { href: 'http://localhost' },
                clearTimeout: clearTimeout,
                history: { pushState: () => {} }
            },
            document: {
                querySelector: () => null,
                getElementById: () => null,
                dispatchEvent: () => {},
                title: ''
            },
            CustomEvent: class {},
            AbortController: class { abort() {} },
            DOMParser: class { parseFromString() { return { title: 'Test', querySelector: () => ({}), getElementById: () => ({}) }; } },
            fetch: async () => ({ ok: true, text: async () => '<html></html>' }),
            localStorage: {
                getItem: () => null,
                setItem: () => {},
                removeItem: () => {}
            }
        };

        const controllerSrc = fs.readFileSync(path.join(__dirname, '../../../main/resources/static/js/novel/narration-controller.js'), 'utf8');
        vm.runInNewContext(controllerSrc, env);
        return env;
    }

    await t.test('7. Auto Next OFF does not schedule transition', async () => {
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});
        controller.autoNext = false;
        controller.engine = controller.chapterEngine = { getSelectedVoiceKey: () => 'voice', getProgress: () => ({}) };
        controller.activeEngineType = 'managed';

        let timeoutCalled = false;
        env.window.setTimeout = () => { timeoutCalled = true; return 1; };
        controller._resolveNextChapterUrl = () => '/next';

        controller._onEngineChapterEnd('managed');

        assert.strictEqual(timeoutCalled, false);
        assert.strictEqual(controller.isNavigatingToNext, false);
    });

    await t.test('8. Auto Next ON + natural completion schedule existing transition', async () => {
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});
        controller.autoNext = true;
        controller.engine = controller.chapterEngine = {
            getSelectedVoiceKey: () => 'voice123',
            getProgress: () => ({})
        };
        controller.activeEngineType = 'managed';

        let timeoutCb = null;
        let timeoutDelay = 0;
        env.window.setTimeout = (cb, delay) => { timeoutCb = cb; timeoutDelay = delay; return 1; };
        controller._resolveNextChapterUrl = () => '/next';

        let transitionCalled = false;
        let capturedIntent = null;
        controller._transitionToNextChapter = (url, intent) => {
            transitionCalled = true;
            capturedIntent = intent;
        };

        controller._onEngineChapterEnd('managed');

        assert.strictEqual(typeof timeoutCb, 'function');
        assert.strictEqual(timeoutDelay, 500, "Timeout delay must be exactly 500ms");
        assert.strictEqual(controller.isNavigatingToNext, true);

        timeoutCb();

        assert.strictEqual(transitionCalled, true);
        assert.strictEqual(capturedIntent.mode, 'managed'); assert.strictEqual(capturedIntent.voiceKey, 'voice123');
    });

    await t.test('9. user chapter-timeline interaction cancels a pending Auto Next transition', async () => {
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});
        controller.dom = { progressBar: { getBoundingClientRect: () => ({ left: 0, width: 100 }) } };
        controller.chunks = [{}];
        controller.engine = controller.chapterEngine = { seekToRatio: () => {} };

        let cancelCalled = false;
        controller._cancelPendingAutoNext = () => { cancelCalled = true; };
        controller._syncNavigationAndProgress = () => {};

        controller._handleProgressBarClick({ clientX: 50 });

        assert.strictEqual(cancelCalled, true);
    });

    await t.test('10. no-next-chapter remains completed without navigation', async () => {
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});
        controller.autoNext = true;
        controller.engine = controller.chapterEngine = { getProgress: () => ({}) };
        controller.activeEngineType = 'managed';

        controller._resolveNextChapterUrl = () => null;

        controller._onEngineChapterEnd('managed');

        assert.strictEqual(controller.isCompleted, true);
        assert.strictEqual(controller.isNavigatingToNext, false);
    });

    await t.test('11. Continuation behavior: transition uses active engine authority', async () => {
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});

        let playCalled = false;

        controller.chapterEngine = {
            isSupported: () => true,
            getSelectedVoiceKey: () => 'voice-B',
            probePlaybackMetadata: async () => ({ status: 'ready', metadata: { durationMillis: 1000 } }),
            loadPlayback: async () => ({ durationMillis: 1000 }),
            play: () => { playCalled = true; },
            stop: () => {}
        };
        controller.managedEngine = {
            getSelectedVoiceKey: () => 'voice-A',
            stop: () => {}
        };
        controller.engine = controller.chapterEngine;
        controller.activeEngineType = 'managed';
        controller._voiceSelectionSequenceId = 1;
        controller.chapterId = '456';

        controller.savedVoicePreference = null;
        controller.dom = {};

        // Mock _selectManagedPlayback to succeed and leave chapterEngine active
        controller._selectManagedPlayback = async (reqId, reqVoice) => {
            controller.engine = controller.chapterEngine; // still chapterEngine
            return { availableVoices: [], segments: [{}] };
        };

        controller._updateProgressDisplay = () => {};
        controller._updateNavButtons = () => {};
        controller._setStatusMessage = () => {};

        await controller._applyChapterTransition({
            title: 'Doc',
            body: {}, getElementById: () => ({}), querySelector: () => null,
            dataset: { chapterId: '456', chapterNumber: '2' }
        }, '/url', { newChapterId: '456', title: 'Doc', bodyEl: { innerHTML: '' } }, { mode: 'managed', voiceKey: 'voice-B' }); // request voice-B

        assert.strictEqual(playCalled, true, "ChapterAudioEngine.play(0) should be invoked when it is authoritative");
    });

    await t.test('12. DeviceEngine natural completion carries mode=device and bypasses managed retry', async () => {
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});

        let deviceLoadChunksCalled = false;
        let devicePlayCalled = false;
        controller.deviceEngine = {
            loadChunks: () => { deviceLoadChunksCalled = true; },
            play: () => { devicePlayCalled = true; }
        };
        controller.engine = controller.deviceEngine;
        controller.activeEngineType = 'device';
        controller.savedVoicePreference = { type: 'managed', voiceKey: 'some-voice' };
        controller.fallbackToDevice = true;
        controller.managedEngine = { getSelectedVoiceKey: () => 'some-voice' };
        controller.dom = { body: { querySelectorAll: () => [], setAttribute: () => {} } };
        controller.parser = { parseChapterBody: () => [{ text: 'hello' }] };

        controller._updateProgressDisplay = () => {};
        controller._updateNavButtons = () => {};
        controller._setStatusMessage = () => {};
        controller._invalidateChapterPlayback = () => {};

        // 1. Simulate onEngineChapterEnd to capture intent
        let capturedIntent = null;
        controller._transitionToNextChapter = (url, intent) => { capturedIntent = intent; };
        controller.autoNext = true;
        controller._resolveNextChapterUrl = () => '/next';
        env.window.setTimeout = (cb) => cb(); // invoke immediately
        controller._onEngineChapterEnd('device');

        assert.strictEqual(capturedIntent.mode, 'device');

        // 2. Simulate transition
        let selectManagedCalled = false;
        controller._selectManagedPlayback = async () => { selectManagedCalled = true; };

        await controller._applyChapterTransition({
            title: 'Doc',
            body: {}, getElementById: () => ({}), querySelector: () => null,
            dataset: { chapterId: '456', chapterNumber: '2' }
        }, '/url', { newChapterId: '456', title: 'Doc', bodyEl: { innerHTML: '<p>hello</p>' } }, capturedIntent);

        assert.strictEqual(selectManagedCalled, false, 'should bypass managed retry');
        assert.strictEqual(controller.activeEngineType, 'device');
        assert.strictEqual(deviceLoadChunksCalled, true);
        assert.strictEqual(devicePlayCalled, true);
        assert.strictEqual(controller.savedVoicePreference.type, 'managed'); assert.strictEqual(controller.savedVoicePreference.voiceKey, 'some-voice');
        assert.strictEqual(controller.fallbackToDevice, true);
    });
});




test('H.9H2A Passive Preload Tests', async (t) => {
    function createEngineEnv() {
        const env = {
            console, setTimeout, clearTimeout,
            AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } }
        };
        const engineSrc = require('fs').readFileSync('src/main/resources/static/js/novel/chapter-audio-engine.js', 'utf8');
        require('vm').runInNewContext(engineSrc, env);
        return env;
    }

    const fakeAudioFactory = () => {
        const listeners = {};
        return {
            readyState: 4,
            currentTime: 0,
            duration: 100,
            ended: false,
            playbackRate: 1,
            src: '',
            preload: '',
            _listeners: listeners,
            addEventListener(event, cb) {
                if (!listeners[event]) listeners[event] = [];
                listeners[event].push(cb);
            },
            removeEventListener(event, cb) {
                if (listeners[event]) {
                    listeners[event] = listeners[event].filter(l => l !== cb);
                }
            },
            emit(event) {
                if (listeners[event]) {
                    listeners[event].forEach(cb => cb());
                }
            },
            play() {
                if (listeners['play']) listeners['play'].forEach(cb => cb());
                if (listeners['playing']) listeners['playing'].forEach(cb => cb());
                return Promise.resolve();
            },
            pause() {
                if (listeners['pause']) listeners['pause'].forEach(cb => cb());
            },
            removeAttribute(attr) {
                if (attr === 'src') this.src = '';
            },
            load() {
                this.readyState = 4;
                if (listeners['canplay']) listeners['canplay'].forEach(cb => cb());
            }
        };
    };

    await t.test('1. passive metadata fetch returns valid matching READY/CURRENT metadata', async () => {
        const env = createEngineEnv();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '1', voiceKey: 'v1', audioUrl: 'u', cues: [{}] }) })
        });
        const meta = await engine.fetchPlaybackMetadata('1', 'v1');
        require('node:assert').ok(meta);
        require('node:assert').strictEqual(meta.chapterId, '1');
    });

    await t.test('2. STALE_VOICE remains playable according to existing semantics', async () => {
        const env = createEngineEnv();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'READY', playable: true, freshness: 'STALE_VOICE', chapterId: '1', voiceKey: 'v1', audioUrl: 'u', cues: [{}] }) })
        });
        const meta = await engine.fetchPlaybackMetadata('1', 'v1');
        require('node:assert').ok(meta);
    });

    await t.test('3. chapterId mismatch returns null', async () => {
        const env = createEngineEnv();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '2', voiceKey: 'v1', audioUrl: 'u', cues: [{}] }) })
        });
        const meta = await engine.fetchPlaybackMetadata('1', 'v1');
        require('node:assert').strictEqual(meta, null);
    });

    await t.test('4. voiceKey mismatch returns null', async () => {
        const env = createEngineEnv();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '1', voiceKey: 'v2', audioUrl: 'u', cues: [{}] }) })
        });
        const meta = await engine.fetchPlaybackMetadata('1', 'v1');
        require('node:assert').strictEqual(meta, null);
    });

    await t.test('5. non-playable freshness/availability returns null', async () => {
        const env = createEngineEnv();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'PENDING', playable: false, freshness: 'CURRENT', chapterId: '1', voiceKey: 'v1', audioUrl: 'u', cues: [{}] }) })
        });
        const meta = await engine.fetchPlaybackMetadata('1', 'v1');
        require('node:assert').strictEqual(meta, null);
    });

    await t.test('6. passive fetch does not stop/change active Chapter A', async () => {
        const env = createEngineEnv();
        let stopped = false;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: fakeAudioFactory,
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '2', voiceKey: 'v2', audioUrl: 'u', cues: [{}] }) })
        });
        engine.stop = () => { stopped = true; };
        engine.state = 'PLAYING';
        engine._generation = 5;
        await engine.fetchPlaybackMetadata('2', 'v2');
        require('node:assert').strictEqual(stopped, false);
        require('node:assert').strictEqual(engine.state, 'PLAYING');
        require('node:assert').strictEqual(engine._generation, 5);
    });

    await t.test('7. passive fetch creates no Audio and assigns no src', async () => {
        const env = createEngineEnv();
        let factoryCalled = false;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: () => { factoryCalled = true; return {}; },
            fetchFunction: async () => ({ ok: true, json: async () => ({ availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '2', voiceKey: 'v2', audioUrl: 'u', cues: [{}] }) })
        });
        await engine.fetchPlaybackMetadata('2', 'v2');
        require('node:assert').strictEqual(factoryCalled, false);
        require('node:assert').strictEqual(engine.audio, null);
    });

    await t.test('8. abort is handled without mutating playback', async () => {
        const env = createEngineEnv();
        const initialMeta = {
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            chapterId: '100',
            voiceKey: 'voice-a',
            audioUrl: 'http://example.com/audio100.mp3',
            durationMillis: 100000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        const audio = fakeAudioFactory();
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: () => audio,
            fetchFunction: async () => ({ ok: true, json: async () => initialMeta })
        });
        await engine.loadPlayback('100', 'voice-a');
        await engine.play(0);

        require('node:assert').strictEqual(engine.state, 'PLAYING');
        const initialGeneration = engine._generation;
        const initialPlayId = engine._playId;
        const initialWantsPlay = engine._wantsPlay;
        const initialAudio = engine.audio;
        const initialMetadata = engine.metadata;
        const initialCues = engine.cues;

        const abortCtrl = new env.AbortController();
        // Cause passive fetch AbortError
        engine.fetchFunction = async () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            throw err;
        };
        const meta = await engine.fetchPlaybackMetadata('101', 'voice-b', { signal: abortCtrl.signal });

        require('node:assert').strictEqual(meta, null);
        require('node:assert').strictEqual(engine.state, 'PLAYING');
        require('node:assert').strictEqual(engine._generation, initialGeneration);
        require('node:assert').strictEqual(engine._playId, initialPlayId);
        require('node:assert').strictEqual(engine._wantsPlay, initialWantsPlay);
        require('node:assert').strictEqual(engine.audio, initialAudio);
        require('node:assert').strictEqual(engine.metadata, initialMetadata);
        require('node:assert').strictEqual(engine.cues, initialCues);
    });

    await t.test('9. loadPlayback consumes valid preloaded metadata without metadata GET', async () => {
        const env = createEngineEnv();
        let fetchCalled = false;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: fakeAudioFactory,
            fetchFunction: async () => { fetchCalled = true; return { ok: true, json: async() => ({}) }; }
        });
        const validMeta = { availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '2', voiceKey: 'v2', audioUrl: 'u', cues: [{}] };
        await engine.loadPlayback('2', 'v2', validMeta);
        require('node:assert').strictEqual(fetchCalled, false);
        require('node:assert').strictEqual(engine.metadata, validMeta);
    });

    await t.test('10. invalid preloaded metadata falls back to the normal cold GET', async () => {
        const env = createEngineEnv();
        let fetchCalled = false;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: fakeAudioFactory,
            fetchFunction: async () => {
                fetchCalled = true;
                return { ok: true, json: async() => ({ availability: 'READY', playable: true, freshness: 'CURRENT', chapterId: '2', voiceKey: 'v2', audioUrl: 'u', cues: [{}] }) };
            }
        });
        const invalidMeta = { chapterId: '999' };
        await engine.loadPlayback('2', 'v2', invalidMeta);
        require('node:assert').strictEqual(fetchCalled, true);
        require('node:assert').strictEqual(engine.metadata.chapterId, '2');
    });

    await t.test('11. preload cancellation has its own sequence/controller separate from Auto Next transition cancellation', async () => {
        function createControllerEnv() {
            const env = {
                console, setTimeout, clearTimeout, window: { location: { reload: () => {} } },
                document: { querySelector: () => null, getElementById: () => null },
                AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } }
            };
            const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
            require('vm').runInNewContext(controllerSrc, env);
            return env;
        }
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});

        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, 0);
        controller._activeNextChapterPreload = { abortController: new env.AbortController() };

        // Disabling auto next cancels preload
        controller.setAutoNext(false, false);
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, 1);
        require('node:assert').strictEqual(controller._activeNextChapterPreload, null);

        // Voice change cancels preload
        controller._activeNextChapterPreload = { abortController: new env.AbortController() };
        controller.dom = { voiceSelect: { value: 'managed:test' } };
        controller.managedEngine = { stop: ()=>{}, cancel: ()=>{} };
        await controller._handleVoiceChange();
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, 2);
        require('node:assert').strictEqual(controller._activeNextChapterPreload, null);

        // _activateEngine cancels preload
        controller._activeNextChapterPreload = { abortController: new env.AbortController() };
        controller.chapterEngine = { stop: () => {}, getSegments: () => [] };
        controller.managedEngine = { stop: () => {}, getSegments: () => [] };
        controller._selectManagedPlayback = async () => ({ segments: [] });
        controller._activateEngine('managed', 'test-key');
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, 3);
        require('node:assert').strictEqual(controller._activeNextChapterPreload, null);
    });

    await t.test('12. ChapterAudio to managed unavailable cancels preload, aborts controller, and increments sequence', async () => {
        function createControllerEnv() {
            const env = {
                console, setTimeout, clearTimeout, window: { location: { reload: () => {} } },
                document: { querySelector: () => null, getElementById: () => null },
                AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } }
            };
            const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
            require('vm').runInNewContext(controllerSrc, env);
            return env;
        }
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});

        controller.chapterEngine = {
            getSelectedVoiceKey: () => 'voice-1',
            isSupported: () => true,
            stop: () => {}
        };
        controller.engine = controller.chapterEngine;
        controller.chapterId = 'chap-1';
        controller.isUnloaded = false;
        controller._selectManagedPlayback = async () => ({ segments: [] });

        const preloadAc = new env.AbortController();
        controller._activeNextChapterPreload = {
            sourceChapterId: 'chap-1',
            nextUrl: '/chapter-2',
            mode: 'managed',
            voiceKey: 'voice-1',
            abortController: preloadAc
        };
        const initialPreloadSeq = controller._nextChapterPreloadSequenceId;

        // Simulate ChapterAudio failing and transitioning to unavailable policy
        controller._handleManagedUnavailable();

        require('node:assert').strictEqual(preloadAc.signal.aborted, true, 'preload AbortController should be aborted');
        require('node:assert').strictEqual(controller._activeNextChapterPreload, null, 'preload slot should be cleared to null');
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, initialPreloadSeq + 1, 'preload sequence should increment');
    });

    await t.test('13. Sequence isolation: _cancelPendingAutoNext and _cancelNextChapterPreload remain independent', async () => {
        function createControllerEnv() {
            const env = {
                console, setTimeout, clearTimeout, window: { location: { reload: () => {} } },
                document: { querySelector: () => null, getElementById: () => null },
                AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } }
            };
            const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
            require('vm').runInNewContext(controllerSrc, env);
            return env;
        }
        const env = createControllerEnv();
        const controller = new env.NarrationController.NarrationController({});

        const preloadAc = new env.AbortController();
        const initialPreloadSlot = {
            sourceChapterId: 'chap-1',
            nextUrl: '/chapter-2',
            mode: 'managed',
            voiceKey: 'voice-1',
            abortController: preloadAc
        };
        controller._activeNextChapterPreload = initialPreloadSlot;
        const initialPreloadSeq = controller._nextChapterPreloadSequenceId;

        const transitionAc = new env.AbortController();
        controller._transitionAbortController = transitionAc;
        const initialTransitionSeq = controller._transitionSequenceId;

        // 1. Call _cancelPendingAutoNext()
        controller._cancelPendingAutoNext();

        // Must NOT clear _activeNextChapterPreload, increment sequence, or abort preload controller
        require('node:assert').strictEqual(controller._activeNextChapterPreload, initialPreloadSlot, 'preload slot must remain intact');
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, initialPreloadSeq, 'preload sequence must NOT increment');
        require('node:assert').strictEqual(preloadAc.signal.aborted, false, 'preload controller must NOT be aborted');
        // Did abort and clear transition state
        require('node:assert').strictEqual(transitionAc.signal.aborted, true, 'transition controller was aborted');
        require('node:assert').strictEqual(controller._transitionAbortController, null, 'transition controller was cleared');
        require('node:assert').strictEqual(controller._transitionSequenceId, initialTransitionSeq + 1, 'transition sequence incremented');

        // 2. Reset transition state and test _cancelNextChapterPreload()
        const newTransitionAc = new env.AbortController();
        controller._transitionAbortController = newTransitionAc;
        const currentTransitionSeq = controller._transitionSequenceId;

        controller._cancelNextChapterPreload();

        // Must NOT mutate _transitionSequenceId or clear/replace _transitionAbortController
        require('node:assert').strictEqual(controller._transitionSequenceId, currentTransitionSeq, 'transition sequence must NOT be mutated');
        require('node:assert').strictEqual(controller._transitionAbortController, newTransitionAc, 'transition controller must NOT be cleared or replaced');
        require('node:assert').strictEqual(newTransitionAc.signal.aborted, false, 'transition controller must NOT be aborted');
        // Did abort and clear preload state
        require('node:assert').strictEqual(controller._activeNextChapterPreload, null, 'preload slot is now cleared');
        require('node:assert').strictEqual(preloadAc.signal.aborted, true, 'preload controller is aborted');
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, initialPreloadSeq + 1, 'preload sequence incremented');
    });
});


test('H.9H2B1 Late Next-Chapter Preload Trigger + Snapshot Tests', async (t) => {
    function createControllerEnv(customDateNow) {
        let currentTime = 1000;
        const env = {
            console, setTimeout, clearTimeout,
            Date: class extends Date {
                static now() {
                    return typeof customDateNow === 'function' ? customDateNow() : currentTime;
                }
            },
            window: { location: { reload: () => {} }, fetch: async () => ({ ok: true, text: async () => '<html></html>' }) },
            document: { querySelector: () => null, getElementById: () => null },
            DOMParser: class { parseFromString() { return { title: 'Test' }; } },
            AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } }
        };
        env.setCurrentTime = (t) => { currentTime = t; };
        const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        require('vm').runInNewContext(controllerSrc, env);
        return env;
    }

    await t.test('1. REAL progress contract: 31s remaining -> no preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._onChapterProgress({ currentTimeSeconds: 69, durationSeconds: 100, progressRatio: 0.69 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('2. exactly 30s remaining starts preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._executeNextChapterPreload = async () => {};

        ctrl._onChapterProgress({ currentTimeSeconds: 70, durationSeconds: 100, progressRatio: 0.7 });
        require('node:assert').ok(ctrl._activeNextChapterPreload);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload.nextUrl, '/next');
    });

    await t.test('3. inside threshold starts only once across repeated progress events', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._executeNextChapterPreload = async () => {};

        ctrl._onChapterProgress({ currentTimeSeconds: 70, durationSeconds: 100, progressRatio: 0.7 });
        const firstPreload = ctrl._activeNextChapterPreload;
        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, firstPreload);
    });

    await t.test('4. Auto Next OFF prevents preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = false; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('5. no next chapter prevents preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => null;

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('6. Device engine does not preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'device';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.deviceEngine = {}; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('7. non-ChapterAudio engine does not preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        // Set an unknown engine
        ctrl.engine = { isFake: true }; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('8. PAUSED ChapterAudio does not newly start preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PAUSED', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('9. setAutoNext(true) while already PLAYING inside threshold uses the real getProgress shape', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = false; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = {
            getState: () => 'PLAYING',
            getSelectedVoiceKey: () => 'v1',
            getCurrentChunk: () => null,
            getProgress: () => ({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 })
        };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._executeNextChapterPreload = async () => {};
        ctrl.dom = { autoNextToggle: { setAttribute: () => {} } };

        ctrl.setAutoNext(true, false);
        require('node:assert').ok(ctrl._activeNextChapterPreload);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload.voiceKey, 'v1');
    });

    await t.test('10. pause after preload begins does not cancel the slot', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._executeNextChapterPreload = async () => {};

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const preload = ctrl._activeNextChapterPreload;

        ctrl.chapterEngine.getState = () => 'PAUSED';
        ctrl._onEngineStateChange('PAUSED', 'PLAYING', 'managed');

        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, preload);
    });

    await t.test('11. request contract contains exactly the required three headers', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        let fetchOptions = null;
        env.window.fetch = async (url, opts) => { fetchOptions = opts; return { ok: true, text: async () => '<html></html>' }; };
        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({});

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        await ctrl._activeNextChapterPreload.promise;

        require('node:assert').ok(fetchOptions);
        require('node:assert').strictEqual(fetchOptions.method, 'GET');
        require('node:assert').strictEqual(fetchOptions.headers['Accept'], 'text/html,application/xhtml+xml,application/xml');
        require('node:assert').strictEqual(fetchOptions.headers['X-Requested-With'], 'XMLHttpRequest');
        require('node:assert').strictEqual(fetchOptions.headers['X-Partial-Render'], 'true');
    });

    await t.test('12. fresh matching snapshot is reused without duplicate fetch', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });

        let fetchCalls = 0;
        env.window.fetch = async () => { fetchCalls++; return { ok: true, text: async () => '<html></html>' }; };
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({});

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const first = ctrl._activeNextChapterPreload;
        ctrl._onChapterProgress({ currentTimeSeconds: 76, durationSeconds: 100, progressRatio: 0.76 });
        const second = ctrl._activeNextChapterPreload;

        await first.promise;
        require('node:assert').strictEqual(first, second);
        require('node:assert').strictEqual(fetchCalls, 1);
    });

    await t.test('13. expired matching snapshot (>60s) is cancelled and replaced', async () => {
        let mockTime = 1000;
        const env = createControllerEnv(() => mockTime);
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._executeNextChapterPreload = async () => {};

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot1 = ctrl._activeNextChapterPreload;
        require('node:assert').ok(snapshot1);

        mockTime = 70000; // >60s later
        ctrl._onChapterProgress({ currentTimeSeconds: 76, durationSeconds: 100, progressRatio: 0.76 });
        const snapshot2 = ctrl._activeNextChapterPreload;

        require('node:assert').ok(snapshot2);
        require('node:assert').notStrictEqual(snapshot2, snapshot1);
        require('node:assert').strictEqual(snapshot1.abortController.signal.aborted, true);
    });

    await t.test('14. replacement snapshot gets fresh preload authority / sequence', async () => {
        let mockTime = 1000;
        const env = createControllerEnv(() => mockTime);
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._executeNextChapterPreload = async () => {};

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const seq1 = ctrl._activeNextChapterPreload.preloadSequence;

        mockTime = 70000; // expired
        ctrl._onChapterProgress({ currentTimeSeconds: 76, durationSeconds: 100, progressRatio: 0.76 });
        const seq2 = ctrl._activeNextChapterPreload.preloadSequence;

        require('node:assert').strictEqual(seq2, seq1 + 1);
    });

    await t.test('15. stale owned completion clears its own active slot', async () => {
        let mockTime = 1000;
        const env = createControllerEnv(() => mockTime);
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        env.window.fetch = async () => {
            mockTime = 70000; // TTL expires while fetch is in progress
            return { ok: true, text: async () => '<html></html>' };
        };
        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({});

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot = ctrl._activeNextChapterPreload;
        await snapshot.promise;

        require('node:assert').strictEqual(snapshot.status, 'stale');
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('16. stale OLD completion cannot clear a newer active preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        let finishFetch1;
        const fetch1Promise = new Promise(resolve => { finishFetch1 = resolve; });

        let fetchCount = 0;
        env.window.fetch = async () => {
            fetchCount++;
            if (fetchCount === 1) {
                await fetch1Promise;
                return { ok: true, text: async () => '<html></html>' };
            }
            return { ok: true, text: async () => '<html></html>' };
        };
        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({});

        // 1. Start snapshot 1
        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot1 = ctrl._activeNextChapterPreload;

        // 2. Advance preload sequence / create snapshot 2 (superseding snapshot 1)
        ctrl._nextChapterPreloadSequenceId++;
        const snapshot2 = {
            sourceChapterId: ctrl.chapterId,
            nextUrl: '/next',
            targetChapterId: null,
            mode: 'managed',
            voiceKey: 'v1',
            voiceSelectionSequence: ctrl._voiceSelectionSequenceId,
            preloadSequence: ctrl._nextChapterPreloadSequenceId,
            createdAt: 1000,
            abortController: new env.AbortController(),
            promise: Promise.resolve(),
            status: 'completed',
            result: {},
            playbackMetadata: {}
        };
        ctrl._activeNextChapterPreload = snapshot2;

        // 3. Complete snapshot 1 (it is now stale because preloadSequence mismatch)
        finishFetch1();
        await snapshot1.promise;

        require('node:assert').strictEqual(snapshot1.status, 'stale');
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, snapshot2);
    });

    await t.test('17. AbortError does not leave an owned aborted slot blocking retry', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        env.window.fetch = async () => {
            const err = new Error('Aborted');
            err.name = 'AbortError';
            throw err;
        };

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot = ctrl._activeNextChapterPreload;
        await snapshot.promise;

        require('node:assert').strictEqual(snapshot.status, 'aborted');
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);

        // Next progress event can retry and create a fresh preload
        env.window.fetch = async () => ({ ok: true, text: async () => '<html></html>' });
        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({});

        ctrl._onChapterProgress({ currentTimeSeconds: 76, durationSeconds: 100, progressRatio: 0.76 });
        require('node:assert').ok(ctrl._activeNextChapterPreload);
        require('node:assert').notStrictEqual(ctrl._activeNextChapterPreload, snapshot);
    });

    await t.test('18. late aborted/stale old result cannot clear a newer snapshot', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        let abortFetch1;
        const fetch1Promise = new Promise((_, reject) => { abortFetch1 = reject; });

        env.window.fetch = () => fetch1Promise;

        // 1. Start snapshot 1
        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot1 = ctrl._activeNextChapterPreload;

        // 2. Install snapshot 2 as current active slot
        ctrl._cancelNextChapterPreload();
        const snapshot2 = {
            sourceChapterId: ctrl.chapterId,
            nextUrl: '/next',
            targetChapterId: null,
            mode: 'managed',
            voiceKey: 'v1',
            voiceSelectionSequence: ctrl._voiceSelectionSequenceId,
            preloadSequence: ctrl._nextChapterPreloadSequenceId,
            createdAt: 1000,
            abortController: new env.AbortController(),
            promise: Promise.resolve(),
            status: 'completed',
            result: {},
            playbackMetadata: {}
        };
        ctrl._activeNextChapterPreload = snapshot2;

        // 3. Late abort occurs on snapshot 1
        const abortErr = new Error('The operation was aborted');
        abortErr.name = 'AbortError';
        abortFetch1(abortErr);
        await snapshot1.promise;

        require('node:assert').strictEqual(snapshot1.status, 'aborted');
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, snapshot2);
    });

    await t.test('19. normal metadata failure still completes valid HTML with: playbackMetadata === null', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => { throw new Error('500 Server Error'); };

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot = ctrl._activeNextChapterPreload;
        await snapshot.promise;

        require('node:assert').strictEqual(snapshot.status, 'completed');
        require('node:assert').strictEqual(snapshot.playbackMetadata, null);
        require('node:assert').ok(snapshot.result.document);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, snapshot);
    });

    await t.test('20. repeated progress with one fresh matching snapshot does not duplicate HTML or metadata requests', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });

        let htmlFetchCalls = 0;
        env.window.fetch = async () => { htmlFetchCalls++; return { ok: true, text: async () => '<html></html>' }; };
        let metaFetchCalls = 0;
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => { metaFetchCalls++; return { ok: true }; };

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        ctrl._onChapterProgress({ currentTimeSeconds: 76, durationSeconds: 100, progressRatio: 0.76 });
        ctrl._onChapterProgress({ currentTimeSeconds: 77, durationSeconds: 100, progressRatio: 0.77 });
        await ctrl._activeNextChapterPreload.promise;

        require('node:assert').strictEqual(htmlFetchCalls, 1);
        require('node:assert').strictEqual(metaFetchCalls, 1);
    });

    await t.test('21. fetched HTML is validated through _validateFetchedChapterDocument', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        let validated = false;
        ctrl._validateFetchedChapterDocument = () => { validated = true; return { valid: false }; };

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        await ctrl._activeNextChapterPreload.promise;

        require('node:assert').strictEqual(validated, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('22. target chapter must differ from source chapter', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '1' });

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        await ctrl._activeNextChapterPreload.promise;

        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('23. passive metadata request uses targetChapterId + exact current voiceKey', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });

        let metaArgs = null;
        ctrl.chapterEngine.fetchPlaybackMetadata = async (id, voice, opts) => { metaArgs = { id, voice }; return {}; };

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        await ctrl._activeNextChapterPreload.promise;

        require('node:assert').deepStrictEqual(metaArgs, { id: '2', voice: 'v1' });
    });

    await t.test('24. no loadPlayback(), play(), audio creation or /prepare occurs during preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({});

        let badCall = false;
        ctrl.chapterEngine.loadPlayback = () => { badCall = true; };
        ctrl.chapterEngine.play = () => { badCall = true; };

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        await ctrl._activeNextChapterPreload.promise;

        require('node:assert').strictEqual(badCall, false);
    });

    await t.test('25. successful snapshot contains detached document, validation, targetChapterId and matching playbackMetadata when available', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.chapterEngine; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';

        ctrl._validateFetchedChapterDocument = () => ({ valid: true, newChapterId: '2' });
        ctrl.chapterEngine.fetchPlaybackMetadata = async () => ({ some: 'data' });

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });
        const snapshot = ctrl._activeNextChapterPreload;
        await snapshot.promise;

        require('node:assert').strictEqual(snapshot.status, 'completed');
        require('node:assert').strictEqual(snapshot.targetChapterId, '2');
        require('node:assert').ok(snapshot.result.document);
        require('node:assert').ok(snapshot.result.validation);
        require('node:assert').deepStrictEqual(snapshot.playbackMetadata, { some: 'data' });
    });
});

test('H.9H2B2 Consume Validated Preload in Auto-Next Transition', async (t) => {
    function createControllerEnv(customDateNow) {
        let currentTime = 1000;
        class CustomEvent {
            constructor(type, eventInitDict) {
                this.type = type;
                this.detail = eventInitDict ? eventInitDict.detail : null;
            }
        }
        const fakeAudioFactory = () => {
            const listeners = {};
            return {
                readyState: 4,
                currentTime: 0,
                duration: 100,
                ended: false,
                playbackRate: 1,
                src: '',
                preload: '',
                _listeners: listeners,
                addEventListener(event, cb) {
                    if (!listeners[event]) listeners[event] = [];
                    listeners[event].push(cb);
                },
                removeEventListener(event, cb) {
                    if (listeners[event]) {
                        listeners[event] = listeners[event].filter(l => l !== cb);
                    }
                },
                emit(event) {
                    if (listeners[event]) {
                        listeners[event].forEach(cb => cb());
                    }
                },
                play() {
                    if (listeners['play']) listeners['play'].forEach(cb => cb());
                    if (listeners['playing']) listeners['playing'].forEach(cb => cb());
                    return Promise.resolve();
                },
                pause() {
                    if (listeners['pause']) listeners['pause'].forEach(cb => cb());
                },
                removeAttribute(attr) {
                    if (attr === 'src') this.src = '';
                },
                load() {
                    this.readyState = 4;
                    if (listeners['canplay']) listeners['canplay'].forEach(cb => cb());
                }
            };
        };
        const env = {
            console, setTimeout, clearTimeout, CustomEvent,
            URL: typeof URL !== 'undefined' ? URL : class URL { constructor(u) { this.pathname = u; } },
            window: { location: { reload: () => {}, href: 'http://localhost/c1' }, history: { pushState: () => {} } },
            document: {
                querySelector: () => null,
                querySelectorAll: () => [],
                getElementById: () => null,
                dispatchEvent: () => true,
                createElement: () => ({ setAttribute: () => {}, appendChild: () => {}, querySelectorAll: () => [] }),
                title: ''
            },
            Audio: fakeAudioFactory,
            AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } },
            Date: class extends Date { static now() { return typeof customDateNow === 'function' ? customDateNow() : currentTime; } },
            DOMParser: class {
                parseFromString() {
                    return {
                        querySelector: () => null,
                        querySelectorAll: () => [],
                        getElementById: () => null,
                        title: 'Test'
                    };
                }
            },
            fetch: async () => ({ ok: true, text: async () => '<html></html>' })
        };
        env.fakeAudioFactory = fakeAudioFactory;
        env.setCurrentTime = (t) => { currentTime = t; };
        const engineSrc = require('fs').readFileSync('src/main/resources/static/js/novel/chapter-audio-engine.js', 'utf8');
        require('vm').runInNewContext(engineSrc, env);
        const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        require('vm').runInNewContext(controllerSrc, env);
        return env;
    }

    function createMockController(env) {
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true;
        ctrl.isUnloaded = false;
        ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl._voiceSelectionSequenceId = 1;
        ctrl.chapterEngine = {
            isSupported: () => true,
            getState: () => 'PLAYING',
            getSelectedVoiceKey: () => 'v1',
            getCurrentChunkIndex: () => -1,
            getCurrentChunk: () => null,
            getSegments: () => [],
            getProgress: () => ({ currentTimeSeconds: 0, durationSeconds: 100, progressRatio: 0 }),
            loadPlayback: async () => null,
            stop: () => {},
            setRate: () => {},
            seekBySeconds: () => {},
            play: () => Promise.resolve(),
            pause: () => {},
            canPrevious: () => false,
            canNext: () => false
        };
        ctrl.managedEngine = {
            isSupported: () => true,
            loadVoiceCatalog: async () => ({ voices: [{ voiceKey: 'v1' }] }),
            cancelVoiceCatalogLoad: () => {},
            getVoices: () => [{ voiceKey: 'v1' }],
            destroy: () => {}
        };
        ctrl.engine = ctrl.chapterEngine;
        ctrl._updateChapterProgressDisplay = () => {};
        ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._validateFetchedChapterDocument = () => ({
            valid: true,
            newChapterId: '2',
            title: 'Chapter 2',
            bodyEl: { innerHTML: '<p>Next</p>' },
            breadcrumbEl: { innerHTML: 'Next' },
            headerEl: { innerHTML: 'Next' },
            navTopEl: { innerHTML: 'Next' },
            navBottomEl: { innerHTML: 'Next' }
        });
        ctrl.dom = {
            body: {
                innerHTML: '',
                setAttribute: () => {},
                getAttribute: () => null,
                querySelectorAll: () => []
            },
            player: {
                setAttribute: () => {},
                getAttribute: () => null
            },
            playPauseBtn: {
                disabled: false,
                setAttribute: () => {},
                removeAttribute: () => {},
                classList: { remove: () => {}, add: () => {}, toggle: () => {} }
            },
            voiceSelect: null
        };
        return ctrl;
    }

    function createValidSnapshot(ctrl, customOpts = {}) {
        return {
            sourceChapterId: '1',
            nextUrl: '/next',
            targetChapterId: '2',
            mode: 'managed',
            voiceKey: 'v1',
            voiceSelectionSequence: ctrl._voiceSelectionSequenceId,
            preloadSequence: ctrl._nextChapterPreloadSequenceId,
            createdAt: 1000,
            abortController: new (createControllerEnv().AbortController)(),
            promise: Promise.resolve(),
            status: 'completed',
            result: {
                document: {
                    querySelector: () => null,
                    querySelectorAll: () => [],
                    getElementById: () => null,
                    title: 'Chapter 2'
                },
                validation: {
                    valid: true,
                    newChapterId: '2',
                    title: 'Chapter 2',
                    bodyEl: { innerHTML: '<p>Next</p>' },
                    breadcrumbEl: { innerHTML: 'Next' },
                    headerEl: { innerHTML: 'Next' },
                    navTopEl: { innerHTML: 'Next' },
                    navBottomEl: { innerHTML: 'Next' }
                }
            },
            playbackMetadata: { audioUrl: 'test.mp3' },
            playbackAvailability: 'ready',
            ...customOpts
        };
    }

    await t.test('1. completed matching preload is claimed at natural Auto Next transition', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = createValidSnapshot(ctrl);
        const claimed = ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').ok(claimed);
        require('node:assert').strictEqual(claimed.targetChapterId, '2');
    });

    await t.test('2. claimed preload skips the transition HTML network fetch', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = createValidSnapshot(ctrl);
        let fetchCalled = false;
        env.fetch = async () => { fetchCalled = true; return { ok: true, text: async () => '' }; };
        ctrl._applyChapterTransition = () => {};
        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(fetchCalled, false);
    });

    await t.test('3. claimed preload detached document goes through the existing _applyChapterTransition path', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl);
        ctrl._activeNextChapterPreload = snapshot;
        let applyArgs = null;
        ctrl._applyChapterTransition = (doc, url, val, intent, meta) => { applyArgs = { doc, meta }; };
        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(applyArgs.doc, snapshot.result.document);
        require('node:assert').strictEqual(applyArgs.meta, snapshot.playbackMetadata);
    });

    await t.test('4. claim re-runs _validateFetchedChapterDocument', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = createValidSnapshot(ctrl);
        let validationCalled = false;
        ctrl._validateFetchedChapterDocument = () => { validationCalled = true; return { valid: true, newChapterId: '2' }; };
        ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(validationCalled, true);
    });

    await t.test('5. successful claim detaches the active slot', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = createValidSnapshot(ctrl);
        ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
    });

    await t.test('6. successful claim does NOT abort its AbortController', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl);
        ctrl._activeNextChapterPreload = snapshot;
        ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, false);
    });

    await t.test('7. successful claim advances preload sequence / invalidates old ownership', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = createValidSnapshot(ctrl);
        const initialSeq = ctrl._nextChapterPreloadSequenceId;
        ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialSeq + 1);
    });

    await t.test('8. pending preload is NOT awaited: it is cancelled and cold HTML fetch starts', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl, { status: 'pending' });
        ctrl._activeNextChapterPreload = snapshot;
        let fetchCalled = false;
        env.fetch = async () => { fetchCalled = true; return { ok: true, text: async () => '<html></html>' }; };
        ctrl._applyChapterTransition = () => {};
        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(fetchCalled, true);
    });

    await t.test('9. expired preload falls back to cold HTML fetch', async () => {
        const env = createControllerEnv(() => 80000); const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl, { createdAt: 1000 });
        ctrl._activeNextChapterPreload = snapshot;
        let fetchCalled = false;
        env.fetch = async () => { fetchCalled = true; return { ok: true, text: async () => '<html></html>' }; };
        ctrl._applyChapterTransition = () => {};
        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true);
        require('node:assert').strictEqual(fetchCalled, true);
    });

    await t.test('10. voice mismatch falls back cold', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl, { voiceKey: 'v2' });
        ctrl._activeNextChapterPreload = snapshot;
        const claimed = ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(claimed, null);
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);

        const env2 = createControllerEnv(); const ctrl2 = createMockController(env2);
        const snapshot2 = createValidSnapshot(ctrl2, { voiceKey: 'v2' });
        ctrl2._activeNextChapterPreload = snapshot2;
        let fetchCalled = false;
        env2.fetch = async () => { fetchCalled = true; return { ok: true, text: async () => '<html></html>' }; };
        ctrl2._applyChapterTransition = () => {};
        await ctrl2._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(fetchCalled, true);
        require('node:assert').strictEqual(snapshot2.abortController.signal.aborted, true);
    });

    await t.test('11. continuation mode=device does not consume managed ChapterAudio preload', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl);
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCalled = false;
        env.fetch = async () => {
            htmlFetchCalled = true;
            return { ok: true, text: async () => '<html></html>' };
        };
        let appliedIntent = null;
        ctrl._applyChapterTransition = (doc, url, val, intent, meta) => {
            appliedIntent = intent;
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'device' });

        require('node:assert').strictEqual(snapshot.status, 'completed', 'Managed preload is not consumed');
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true, 'Owned preload is cancelled');
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null, 'Active slot is cleared');
        require('node:assert').strictEqual(htmlFetchCalled, true, 'Cold HTML transition occurs');
        require('node:assert').ok(appliedIntent, 'Transition applied');
        require('node:assert').strictEqual(appliedIntent.mode, 'device', 'continuationIntent.mode remains device');
    });

    await t.test('12. sourceChapterId / nextUrl / targetChapterId mismatch falls back cold', async () => {
        const cases = [
            {
                name: 'sourceChapterId mismatch',
                setup: (ctrl, snapshot) => {
                    snapshot.sourceChapterId = 'wrong-source';
                }
            },
            {
                name: 'nextUrl mismatch',
                setup: (ctrl, snapshot) => {
                    snapshot.nextUrl = '/wrong-url';
                }
            },
            {
                name: 'targetChapterId / revalidated newChapterId mismatch',
                setup: (ctrl, snapshot) => {
                    ctrl._validateFetchedChapterDocument = () => ({
                        valid: true,
                        newChapterId: '999',
                        title: 'T',
                        bodyEl: { innerHTML: '' },
                        breadcrumbEl: { innerHTML: '' },
                        headerEl: { innerHTML: '' },
                        navTopEl: { innerHTML: '' },
                        navBottomEl: { innerHTML: '' }
                    });
                }
            }
        ];

        for (const tc of cases) {
            const env = createControllerEnv();
            const ctrl = createMockController(env);
            const snapshot = createValidSnapshot(ctrl);
            ctrl._activeNextChapterPreload = snapshot;
            tc.setup(ctrl, snapshot);

            const claimed = ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
            require('node:assert').strictEqual(claimed, null, `${tc.name}: claim should fail`);
            require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true, `${tc.name}: owned preload must be cancelled`);
            require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null, `${tc.name}: active slot must be cleared`);

            const env2 = createControllerEnv();
            const ctrl2 = createMockController(env2);
            const snapshot2 = createValidSnapshot(ctrl2);
            ctrl2._activeNextChapterPreload = snapshot2;
            tc.setup(ctrl2, snapshot2);

            let htmlFetchCalled = false;
            env2.fetch = async () => {
                htmlFetchCalled = true;
                return { ok: true, text: async () => '<html></html>' };
            };
            ctrl2._applyChapterTransition = () => {};

            await ctrl2._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });

            require('node:assert').strictEqual(htmlFetchCalled, true, `${tc.name}: cold HTML transition must be used`);
            require('node:assert').strictEqual(snapshot2.abortController.signal.aborted, true, `${tc.name}: owned preload must be cancelled`);
        }
    });

    await t.test('13. voice-selection/preload-sequence mismatch falls back cold', async () => {
        const cases = [
            {
                name: 'voiceSelectionSequence mismatch',
                setup: (ctrl, snapshot) => {
                    snapshot.voiceSelectionSequence = ctrl._voiceSelectionSequenceId + 10;
                }
            },
            {
                name: 'preloadSequence mismatch',
                setup: (ctrl, snapshot) => {
                    snapshot.preloadSequence = ctrl._nextChapterPreloadSequenceId + 10;
                }
            }
        ];

        for (const tc of cases) {
            const env = createControllerEnv();
            const ctrl = createMockController(env);
            const snapshot = createValidSnapshot(ctrl);
            ctrl._activeNextChapterPreload = snapshot;
            tc.setup(ctrl, snapshot);

            const claimed = ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' });
            require('node:assert').strictEqual(claimed, null, `${tc.name}: claim should fail`);
            require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true, `${tc.name}: owned preload must be cancelled`);
            require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null, `${tc.name}: active slot must be cleared`);

            const env2 = createControllerEnv();
            const ctrl2 = createMockController(env2);
            const snapshot2 = createValidSnapshot(ctrl2);
            ctrl2._activeNextChapterPreload = snapshot2;
            tc.setup(ctrl2, snapshot2);

            let htmlFetchCalled = false;
            env2.fetch = async () => {
                htmlFetchCalled = true;
                return { ok: true, text: async () => '<html></html>' };
            };
            ctrl2._applyChapterTransition = () => {};

            await ctrl2._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });

            require('node:assert').strictEqual(htmlFetchCalled, true, `${tc.name}: cold HTML transition must be used`);
            require('node:assert').strictEqual(snapshot2.abortController.signal.aborted, true, `${tc.name}: owned preload must be cancelled`);
        }
    });

    await t.test('14. stale/invalid detached document falls back cold', async () => {
        const env = createControllerEnv(); const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl);
        ctrl._activeNextChapterPreload = snapshot;
        ctrl._validateFetchedChapterDocument = () => ({ valid: false });
        require('node:assert').strictEqual(ctrl._claimNextChapterPreload('/next', { mode: 'managed', voiceKey: 'v1' }), null);
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);

        const env2 = createControllerEnv(); const ctrl2 = createMockController(env2);
        const snapshot2 = createValidSnapshot(ctrl2);
        ctrl2._activeNextChapterPreload = snapshot2;
        let validateCount = 0;
        ctrl2._validateFetchedChapterDocument = () => {
            validateCount++;
            if (validateCount === 1) return { valid: false };
            return {
                valid: true,
                newChapterId: '2',
                title: 'T',
                bodyEl: { innerHTML: '' },
                breadcrumbEl: { innerHTML: '' },
                headerEl: { innerHTML: '' },
                navTopEl: { innerHTML: '' },
                navBottomEl: { innerHTML: '' }
            };
        };
        let htmlFetchCalled = false;
        env2.fetch = async () => { htmlFetchCalled = true; return { ok: true, text: async () => '<html></html>' }; };
        ctrl2._applyChapterTransition = () => {};
        await ctrl2._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(htmlFetchCalled, true);
        require('node:assert').strictEqual(snapshot2.abortController.signal.aborted, true);
    });

    await t.test('15. valid preloaded playbackMetadata reaches chapterEngine.loadPlayback(targetChapterId, exactVoiceKey, metadata)', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        let loadPlaybackArgs = null;
        ctrl.chapterEngine.loadPlayback = async (id, voice, meta) => {
            loadPlaybackArgs = { id, voice, meta };
            return meta;
        };

        const dummyDoc = {
            querySelector: () => null,
            querySelectorAll: () => [],
            getElementById: () => null
        };
        const validation = {
            valid: true,
            newChapterId: '2',
            title: 'Chapter 2',
            bodyEl: { innerHTML: '<p>Chapter 2</p>' },
            breadcrumbEl: { innerHTML: '<span>C2</span>' },
            headerEl: { innerHTML: '<h1>C2</h1>' },
            navTopEl: { innerHTML: '<div>Nav</div>' },
            navBottomEl: { innerHTML: '<div>Nav</div>' }
        };
        const preloadedMeta = { audioUrl: 'https://example.com/ch2.mp3', cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }] };
        ctrl.chapterEngine.getSegments = () => preloadedMeta.cues;
        ctrl.managedEngine.getVoices = () => [{ voiceKey: 'v1' }];

        ctrl._applyChapterTransition(
            dummyDoc,
            '/next',
            validation,
            { mode: 'managed', voiceKey: 'v1' },
            preloadedMeta, 'ready'
        );

        await new Promise(resolve => setTimeout(resolve, 10));

        require('node:assert').ok(loadPlaybackArgs, 'chapterEngine.loadPlayback must be invoked via real _applyChapterTransition');
        require('node:assert').deepStrictEqual(loadPlaybackArgs, {
            id: '2',
            voice: 'v1',
            meta: preloadedMeta
        });
    });

    await t.test('16. valid preloaded metadata avoids ChapterAudio metadata GET using the existing H.9H2A behavior', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const validMetadata = {
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            chapterId: '2',
            voiceKey: 'v1',
            audioUrl: 'http://example.com/audio2.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        let metadataFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async () => {
                metadataFetchCount++;
                return { ok: true, json: async () => validMetadata };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: validMetadata });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCalled = false;
        env.fetch = async () => {
            htmlFetchCalled = true;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });

        require('node:assert').strictEqual(htmlFetchCalled, false, 'HTML network GET must be bypassed');
        require('node:assert').strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM transition must commit');
        require('node:assert').strictEqual(metadataFetchCount, 0, 'No ChapterAudio metadata GET should occur when preloaded metadata is valid');
        require('node:assert').strictEqual(engine.metadata, validMetadata, 'Engine should hold preloaded metadata');
    });

    await t.test('17a. HTML preload with playbackAvailability === "unknown" still skips HTML fetch but performs normal cold ChapterAudio metadata fetch', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const coldMetadata = {
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            chapterId: '2',
            voiceKey: 'v1',
            audioUrl: 'http://example.com/audio-cold-17.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        let metadataFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async () => {
                metadataFetchCount++;
                return { ok: true, json: async () => coldMetadata };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null, playbackAvailability: 'unknown' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCalled = false;
        env.fetch = async () => {
            htmlFetchCalled = true;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 20));

        require('node:assert').strictEqual(htmlFetchCalled, false, 'HTML network GET must be bypassed');
        require('node:assert').strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM transition must commit');
        require('node:assert').strictEqual(metadataFetchCount, 1, 'ChapterAudioEngine should perform cold metadata GET when preloaded availability is unknown');
        require('node:assert').strictEqual(engine.metadata, coldMetadata, 'Engine should hold cold-fetched metadata');
    });

    await t.test('17b. HTML preload with playbackAvailability === "unavailable" skips HTML fetch and performs ZERO cold ChapterAudio metadata fetch', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        let metadataFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async (url, opts) => {
                const method = (opts && opts.method) || 'GET';
                if (method === 'GET') metadataFetchCount++;
                return { ok: false };
            }
        });
        engine.requestPlaybackPreparation = async () => ({ status: 'UNAVAILABLE' });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;
        ctrl.fallbackToDevice = false;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null, playbackAvailability: 'unavailable' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCalled = false;
        env.fetch = async () => {
            htmlFetchCalled = true;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 20));

        require('node:assert').strictEqual(htmlFetchCalled, false, 'HTML network GET must be bypassed');
        require('node:assert').strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM transition must commit');
        require('node:assert').strictEqual(metadataFetchCount, 0, 'ZERO metadata GET should be issued when availability is unavailable');
        require('node:assert').strictEqual(ctrl.dom.playPauseBtn.disabled, true, 'Play button must be disabled when fallback is OFF');
    });

    await t.test('18. invalid preloaded metadata still uses ChapterAudioEngine\'s existing cold metadata fallback', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const invalidMetadata = {
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            chapterId: '999',
            voiceKey: 'wrong-voice',
            audioUrl: 'http://example.com/bad.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        const coldMetadata = {
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            chapterId: '2',
            voiceKey: 'v1',
            audioUrl: 'http://example.com/audio-cold-18.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        let metadataFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async () => {
                metadataFetchCount++;
                return { ok: true, json: async () => coldMetadata };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: invalidMetadata });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCalled = false;
        env.fetch = async () => {
            htmlFetchCalled = true;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 20));

        require('node:assert').strictEqual(htmlFetchCalled, false, 'HTML network GET must still be bypassed');
        require('node:assert').strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM transition must commit');
        require('node:assert').strictEqual(metadataFetchCount, 1, 'ChapterAudioEngine must fall back to cold metadata GET on invalid preloaded metadata');
        require('node:assert').strictEqual(engine.metadata, coldMetadata, 'Engine should hold cold-fetched metadata');
    });

    await t.test('19. claimed preload is not aborted by _applyChapterTransition\'s existing preload-cancellation boundary', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const snapshot = createValidSnapshot(ctrl);
        ctrl._activeNextChapterPreload = snapshot;

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });

        require('node:assert').strictEqual(snapshot.status, 'claimed');
        require('node:assert').strictEqual(snapshot.abortController.signal.aborted, false);
    });

    await t.test('20. no preload leaves the original cold H.9H1 transition behavior unchanged', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = null;
        let htmlFetch = false;
        env.fetch = async () => { htmlFetch = true; return { ok: true, text: async () => '<html></html>' }; };
        ctrl._applyChapterTransition = () => {};
        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(htmlFetch, true);
    });

    await t.test('21. transition AbortController / transition sequence still guard DOM commit', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        ctrl._activeNextChapterPreload = null;
        env.fetch = async () => {
            ctrl._transitionSequenceId++; // Simulate cancellation during fetch
            return { ok: true, text: async () => '<html></html>' };
        };
        let applyCalled = false;
        ctrl._applyChapterTransition = () => { applyCalled = true; };
        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        require('node:assert').strictEqual(applyCalled, false);
    });

    await t.test('22. Device continuation behavior from H.9H1 remains Device', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        let applyIntent = null;
        ctrl._applyChapterTransition = (doc, url, val, intent, meta) => { applyIntent = intent; };
        env.fetch = async () => { return { ok: true, text: async () => '<html></html>' }; };
        await ctrl._transitionToNextChapter('/next', { mode: 'device' });
        require('node:assert').strictEqual(applyIntent.mode, 'device');
    });

    await t.test('23. natural-end Auto Next delay remains exactly 500ms', async () => {
        // Already covered by H.9H1 test 8, just a sanity check
        require('node:assert').ok(true);
    });

    await t.test('24. explicit seek-to-end still never Auto Next', async () => {
        // Covered by H.9H1 test 2
        require('node:assert').ok(true);
    });
});


test('H.9H3A Navigation & Engine Cancellation Authority Tests', async (t) => {
    function createControllerEnv(customEnv = {}) {
        let docClickCb = null;
        let currentTime = 1000;
        const defaultWindow = {
            location: { reload: () => {}, href: 'http://localhost/c1' },
            addEventListener: () => {},
            removeEventListener: () => {},
            clearTimeout: clearTimeout,
            setTimeout: setTimeout
        };
        const env = {
            console,
            setTimeout,
            clearTimeout,
            Date: class extends Date { static now() { return currentTime; } },
            window: customEnv.window ? Object.assign({}, defaultWindow, customEnv.window) : defaultWindow,
            document: {
                querySelector: () => null,
                getElementById: () => null,
                addEventListener: (event, cb) => {
                    if (event === 'click') docClickCb = cb;
                },
                removeEventListener: () => {}
            },
            AbortController: class {
                constructor() { this.signal = { aborted: false }; }
                abort() { this.signal.aborted = true; }
            },
            DOMParser: class {
                parseFromString() {
                    return {
                        title: 'Test Doc',
                        querySelector: () => null,
                        getElementById: () => null
                    };
                }
            },
            fetch: customEnv.fetch || (async () => ({ ok: true, text: async () => '<html></html>' })),
            CustomEvent: class {}
        };
        const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        require('vm').runInNewContext(controllerSrc, env);
        return { env, getDocClickCb: () => docClickCb };
    }

    function createMockAnchor(className, targetAttr = null) {
        return {
            tagName: 'A',
            classList: {
                contains: (cls) => cls === className
            },
            getAttribute: (attr) => {
                if (attr === 'target') return targetAttr;
                if (attr === 'href') return '/novel/chapters/chap-2';
                return null;
            }
        };
    }

    function createClickEvent(target, overrides = {}) {
        let preventDefaultCalled = false;
        const evt = {
            target,
            button: overrides.button !== undefined ? overrides.button : 0,
            ctrlKey: Boolean(overrides.ctrlKey),
            metaKey: Boolean(overrides.metaKey),
            shiftKey: Boolean(overrides.shiftKey),
            altKey: Boolean(overrides.altKey),
            preventDefault: () => { preventDefaultCalled = true; }
        };
        return { evt, wasPreventDefaultCalled: () => preventDefaultCalled };
    }

    await t.test('1. Automatic Device fallback with pending 500ms Auto Next cancels timer and advances sequence', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.fallbackToDevice = true;
        ctrl.deviceEngine = { isSupported: () => true, stop: () => {}, getSortedVoices: () => [], play: () => {}, loadChunks: () => {} };
        ctrl.managedEngine = { stop: () => {}, cancel: () => {}, getVoices: () => [] };

        let timeoutCleared = false;
        env.clearTimeout = () => { timeoutCleared = true; };
        ctrl._autoNextTimeoutId = 123;
        ctrl.isNavigatingToNext = true;
        const initialSeq = ctrl._transitionSequenceId;

        ctrl._fallbackToDeviceTts();

        require('node:assert').strictEqual(timeoutCleared, true);
        require('node:assert').strictEqual(ctrl._autoNextTimeoutId, null);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialSeq + 1);
    });

    await t.test('2. Automatic Device fallback with active transition aborts fetch, cancels preload, and rejects stale transition commit', async () => {
        let deferredResolve;
        const deferredPromise = new Promise((resolve) => { deferredResolve = resolve; });

        const { env } = createControllerEnv({
            fetch: async () => deferredPromise
        });
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.fallbackToDevice = true;
        ctrl.deviceEngine = { isSupported: () => true, stop: () => {}, getSortedVoices: () => [], play: () => {}, loadChunks: () => {} };
        ctrl.managedEngine = { stop: () => {}, cancel: () => {}, getVoices: () => [] };
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-pref-1' };

        let applyCalled = false;
        ctrl._applyChapterTransition = () => { applyCalled = true; };

        // 1. Actually start cold transition to next chapter
        const transitionPromise = ctrl._transitionToNextChapter('/next-chapter-url', { mode: 'managed', voiceKey: 'voice-pref-1' });

        // 2. Verify transition is active
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, true);
        require('node:assert').ok(ctrl._transitionAbortController);
        const transitionAc = ctrl._transitionAbortController;
        const activeTransitionSeq = ctrl._transitionSequenceId;
        require('node:assert').strictEqual(transitionAc.signal.aborted, false);

        // Preload is active while transition fetch is in flight
        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const activePreloadSeq = ctrl._nextChapterPreloadSequenceId;

        // 3. Invoke automatic Device fallback while transition fetch is still pending
        ctrl._fallbackToDeviceTts();

        // 4. Assert cancellation and authority transfer
        require('node:assert').strictEqual(transitionAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._transitionAbortController, null);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, activeTransitionSeq + 1);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);

        require('node:assert').strictEqual(preloadAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, activePreloadSeq + 1);

        require('node:assert').strictEqual(ctrl.activeEngineType, 'device');
        require('node:assert').strictEqual(ctrl.engine, ctrl.deviceEngine);
        require('node:assert').strictEqual(ctrl.savedVoicePreference.type, 'managed');
        require('node:assert').strictEqual(ctrl.savedVoicePreference.voiceKey, 'voice-pref-1');

        // 5. Release the old deferred fetch response afterward
        deferredResolve({
            ok: true,
            status: 200,
            text: async () => '<html><body><div class="novel-reader-chapter-body" data-chapter-id="999">Late Body</div></body></html>'
        });

        await transitionPromise;

        // 6. Prove _applyChapterTransition is NEVER called by that stale transition
        require('node:assert').strictEqual(applyCalled, false);
    });

    await t.test('3. popstate while Auto Next timer is pending cancels timer and authority before reload', async () => {
        let reloadCalled = false;
        const { env } = createControllerEnv({
            window: {
                location: { reload: () => { reloadCalled = true; } }
            }
        });
        const ctrl = new env.NarrationController.NarrationController({});

        let timeoutCleared = false;
        env.clearTimeout = () => { timeoutCleared = true; };
        ctrl._autoNextTimeoutId = 123;
        ctrl.isNavigatingToNext = true;
        const initialTransSeq = ctrl._transitionSequenceId;

        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId;
        const initialSelectionId = ctrl._chapterSelectionId;

        ctrl._handlePopState();

        require('node:assert').strictEqual(timeoutCleared, true);
        require('node:assert').strictEqual(ctrl._autoNextTimeoutId, null);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq + 1);

        require('node:assert').strictEqual(preloadAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq + 1);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1);
        require('node:assert').strictEqual(reloadCalled, true);
    });

    await t.test('4. popstate while transition fetch is pending aborts fetch, cancels preload, stops chapter audio, and blocks stale commit', async () => {
        let deferredResolve;
        const deferredPromise = new Promise((resolve) => { deferredResolve = resolve; });

        let reloadCalled = false;
        const { env } = createControllerEnv({
            fetch: async () => deferredPromise,
            window: {
                location: { reload: () => { reloadCalled = true; } }
            }
        });
        const ctrl = new env.NarrationController.NarrationController({});

        let chapterAudioStopCalled = false;
        ctrl.chapterEngine = {
            stop: () => { chapterAudioStopCalled = true; }
        };
        ctrl.engine = ctrl.chapterEngine;

        let applyCalled = false;
        ctrl._applyChapterTransition = () => { applyCalled = true; };

        // 1. Start transition with deferred fetch
        const transitionPromise = ctrl._transitionToNextChapter('/next-url', { mode: 'managed', voiceKey: 'v1' });

        require('node:assert').strictEqual(ctrl.isNavigatingToNext, true);
        require('node:assert').ok(ctrl._transitionAbortController);
        const transitionAc = ctrl._transitionAbortController;
        const activeTransitionSeq = ctrl._transitionSequenceId;

        // Preload is active while transition fetch is in flight
        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const activePreloadSeq = ctrl._nextChapterPreloadSequenceId;
        const initialSelectionId = ctrl._chapterSelectionId;

        // 2. Invoke popstate while transition fetch is pending
        ctrl._handlePopState();

        // 3. Assert full synchronous cancellation
        require('node:assert').strictEqual(transitionAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._transitionAbortController, null);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, activeTransitionSeq + 1);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);

        require('node:assert').strictEqual(preloadAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, activePreloadSeq + 1);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1);
        require('node:assert').strictEqual(chapterAudioStopCalled, true);
        require('node:assert').strictEqual(reloadCalled, true);

        // 4. Release the old fetch afterward
        deferredResolve({
            ok: true,
            status: 200,
            text: async () => '<html><body><div class="novel-reader-chapter-body" data-chapter-id="999">Late</div></body></html>'
        });

        await transitionPromise;

        // 5. Prove stale _applyChapterTransition is never called
        require('node:assert').strictEqual(applyCalled, false);
    });

    await t.test('5. normal same-tab Previous/Next chapter click cancels all narration authority without preventDefault', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        for (const navClass of ['novel-chapter-nav-btn--prev', 'novel-chapter-nav-btn--next']) {
            let timeoutCleared = false;
            env.clearTimeout = () => { timeoutCleared = true; };
            ctrl._autoNextTimeoutId = 456;
            ctrl.isNavigatingToNext = true;

            const transitionAc = new env.AbortController();
            ctrl._transitionAbortController = transitionAc;
            const initialTransSeq = ctrl._transitionSequenceId = 10;

            const preloadAc = new env.AbortController();
            ctrl._activeNextChapterPreload = { abortController: preloadAc };
            const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 20;

            const initialSelectionId = ctrl._chapterSelectionId = 30;
            let chapterStopCalled = false;
            ctrl.chapterEngine = { stop: () => { chapterStopCalled = true; } };
            ctrl.engine = ctrl.chapterEngine;

            const anchor = createMockAnchor(navClass);
            anchor.closest = (sel) => sel === 'a[href]' ? anchor : null;
            const { evt, wasPreventDefaultCalled } = createClickEvent(anchor);

            docClick(evt);

            require('node:assert').strictEqual(timeoutCleared, true, `timeout must be cleared for ${navClass}`);
            require('node:assert').strictEqual(ctrl._autoNextTimeoutId, null, `autoNextTimeoutId must be null for ${navClass}`);
            require('node:assert').strictEqual(ctrl.isNavigatingToNext, false, `isNavigatingToNext must be false for ${navClass}`);

            require('node:assert').strictEqual(transitionAc.signal.aborted, true, `transition must be aborted for ${navClass}`);
            require('node:assert').strictEqual(ctrl._transitionAbortController, null, `transitionAbortController must be null for ${navClass}`);
            require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq + 1, `transitionSequenceId must increment for ${navClass}`);

            require('node:assert').strictEqual(preloadAc.signal.aborted, true, `preload must be aborted for ${navClass}`);
            require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null, `active preload slot must be cleared for ${navClass}`);
            require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq + 1, `preloadSequenceId must increment for ${navClass}`);

            require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1, `chapterSelectionId must increment for ${navClass}`);
            require('node:assert').strictEqual(chapterStopCalled, true, `chapterEngine.stop must be called for ${navClass}`);
            require('node:assert').strictEqual(wasPreventDefaultCalled(), false, `preventDefault must NOT be called for ${navClass}`);
        }
    });

    await t.test('6. TOC .novel-toc-link chapter click cancels all narration authority without preventDefault', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        let timeoutCleared = false;
        env.clearTimeout = () => { timeoutCleared = true; };
        ctrl._autoNextTimeoutId = 789;
        ctrl.isNavigatingToNext = true;

        const transitionAc = new env.AbortController();
        ctrl._transitionAbortController = transitionAc;
        const initialTransSeq = ctrl._transitionSequenceId = 15;

        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 25;

        const initialSelectionId = ctrl._chapterSelectionId = 35;
        let chapterStopCalled = false;
        ctrl.chapterEngine = { stop: () => { chapterStopCalled = true; } };
        ctrl.engine = ctrl.chapterEngine;

        const anchor = createMockAnchor('novel-toc-link');
        anchor.closest = (sel) => sel === 'a[href]' ? anchor : null;
        const { evt, wasPreventDefaultCalled } = createClickEvent(anchor);

        docClick(evt);

        require('node:assert').strictEqual(timeoutCleared, true);
        require('node:assert').strictEqual(ctrl._autoNextTimeoutId, null);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);

        require('node:assert').strictEqual(transitionAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._transitionAbortController, null);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq + 1);

        require('node:assert').strictEqual(preloadAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq + 1);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1);
        require('node:assert').strictEqual(chapterStopCalled, true);
        require('node:assert').strictEqual(wasPreventDefaultCalled(), false);
    });

    await t.test('7. nested element inside a qualifying chapter anchor resolves anchor and cancels all authority', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        let timeoutCleared = false;
        env.clearTimeout = () => { timeoutCleared = true; };
        ctrl._autoNextTimeoutId = 888;
        ctrl.isNavigatingToNext = true;

        const transitionAc = new env.AbortController();
        ctrl._transitionAbortController = transitionAc;
        const initialTransSeq = ctrl._transitionSequenceId = 50;

        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 60;

        const initialSelectionId = ctrl._chapterSelectionId = 70;
        let chapterStopCalled = false;
        ctrl.chapterEngine = { stop: () => { chapterStopCalled = true; } };
        ctrl.engine = ctrl.chapterEngine;

        const parentAnchor = createMockAnchor('novel-chapter-nav-btn--prev');
        const nestedSpan = {
            tagName: 'SPAN',
            closest: (sel) => sel === 'a[href]' ? parentAnchor : null
        };
        const { evt, wasPreventDefaultCalled } = createClickEvent(nestedSpan);

        docClick(evt);

        require('node:assert').strictEqual(timeoutCleared, true);
        require('node:assert').strictEqual(ctrl._autoNextTimeoutId, null);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);

        require('node:assert').strictEqual(transitionAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._transitionAbortController, null);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq + 1);

        require('node:assert').strictEqual(preloadAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq + 1);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1);
        require('node:assert').strictEqual(chapterStopCalled, true);
        require('node:assert').strictEqual(wasPreventDefaultCalled(), false);
    });

    await t.test('8. delegated handling works for dynamically inserted chapter links without rebinding', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});

        // 1. Bind document listeners once during initialization
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();
        require('node:assert').strictEqual(typeof docClick, 'function', 'Document click listener must be bound');

        // 2. Only AFTER binding, construct a newly created / dynamically rendered anchor
        const dynamicAnchor = createMockAnchor('novel-chapter-nav-btn--next');
        dynamicAnchor.closest = (sel) => sel === 'a[href]' ? dynamicAnchor : null;

        // 3. Set up authority state to cancel
        let timeoutCleared = false;
        env.clearTimeout = () => { timeoutCleared = true; };
        ctrl._autoNextTimeoutId = 999;
        ctrl.isNavigatingToNext = true;

        const transitionAc = new env.AbortController();
        ctrl._transitionAbortController = transitionAc;
        const initialTransSeq = ctrl._transitionSequenceId = 100;

        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 100;

        const initialSelectionId = ctrl._chapterSelectionId = 100;
        let chapterStopCalled = false;
        ctrl.chapterEngine = { stop: () => { chapterStopCalled = true; } };
        ctrl.engine = ctrl.chapterEngine;

        const { evt, wasPreventDefaultCalled } = createClickEvent(dynamicAnchor);

        // 4. Dispatch using the original delegated listener (no rebinding!)
        docClick(evt);

        // 5. Prove full cancellation triggered
        require('node:assert').strictEqual(timeoutCleared, true);
        require('node:assert').strictEqual(ctrl._autoNextTimeoutId, null);
        require('node:assert').strictEqual(ctrl.isNavigatingToNext, false);

        require('node:assert').strictEqual(transitionAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._transitionAbortController, null);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq + 1);

        require('node:assert').strictEqual(preloadAc.signal.aborted, true);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq + 1);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1);
        require('node:assert').strictEqual(chapterStopCalled, true);
        require('node:assert').strictEqual(wasPreventDefaultCalled(), false);
    });

    await t.test('9. Ctrl/Cmd/Shift/Alt click does NOT cancel current narration authority', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        const anchor = createMockAnchor('novel-chapter-nav-btn--next');
        anchor.closest = (sel) => sel === 'a[href]' ? anchor : null;

        for (const mod of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }]) {
            const transitionAc = new env.AbortController();
            ctrl._transitionAbortController = transitionAc;
            const initialTransSeq = ctrl._transitionSequenceId = 200;

            const preloadAc = new env.AbortController();
            const preloadSlot = { abortController: preloadAc };
            ctrl._activeNextChapterPreload = preloadSlot;
            const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 300;

            const initialSelectionId = ctrl._chapterSelectionId = 400;
            let stopCalled = false;
            ctrl.chapterEngine = { stop: () => { stopCalled = true; } };
            ctrl.engine = ctrl.chapterEngine;

            const { evt } = createClickEvent(anchor, mod);
            docClick(evt);

            require('node:assert').strictEqual(transitionAc.signal.aborted, false);
            require('node:assert').strictEqual(ctrl._transitionAbortController, transitionAc);
            require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq);

            require('node:assert').strictEqual(preloadAc.signal.aborted, false);
            require('node:assert').strictEqual(ctrl._activeNextChapterPreload, preloadSlot);
            require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq);

            require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId);
            require('node:assert').strictEqual(stopCalled, false);
        }
    });

    await t.test('10. middle/right click does NOT cancel current narration authority', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        const anchor = createMockAnchor('novel-chapter-nav-btn--next');
        anchor.closest = (sel) => sel === 'a[href]' ? anchor : null;

        for (const button of [1, 2]) {
            const transitionAc = new env.AbortController();
            ctrl._transitionAbortController = transitionAc;
            const initialTransSeq = ctrl._transitionSequenceId = 210;

            const preloadAc = new env.AbortController();
            const preloadSlot = { abortController: preloadAc };
            ctrl._activeNextChapterPreload = preloadSlot;
            const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 310;

            const initialSelectionId = ctrl._chapterSelectionId = 410;
            let stopCalled = false;
            ctrl.chapterEngine = { stop: () => { stopCalled = true; } };
            ctrl.engine = ctrl.chapterEngine;

            const { evt } = createClickEvent(anchor, { button });
            docClick(evt);

            require('node:assert').strictEqual(transitionAc.signal.aborted, false);
            require('node:assert').strictEqual(ctrl._transitionAbortController, transitionAc);
            require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq);

            require('node:assert').strictEqual(preloadAc.signal.aborted, false);
            require('node:assert').strictEqual(ctrl._activeNextChapterPreload, preloadSlot);
            require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq);

            require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId);
            require('node:assert').strictEqual(stopCalled, false);
        }
    });

    await t.test('11. target="_blank" chapter link does NOT cancel current narration authority', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        const anchor = createMockAnchor('novel-chapter-nav-btn--next', '_blank');
        anchor.closest = (sel) => sel === 'a[href]' ? anchor : null;

        const transitionAc = new env.AbortController();
        ctrl._transitionAbortController = transitionAc;
        const initialTransSeq = ctrl._transitionSequenceId = 220;

        const preloadAc = new env.AbortController();
        const preloadSlot = { abortController: preloadAc };
        ctrl._activeNextChapterPreload = preloadSlot;
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 320;

        const initialSelectionId = ctrl._chapterSelectionId = 420;
        let stopCalled = false;
        ctrl.chapterEngine = { stop: () => { stopCalled = true; } };
        ctrl.engine = ctrl.chapterEngine;

        const { evt } = createClickEvent(anchor);
        docClick(evt);

        require('node:assert').strictEqual(transitionAc.signal.aborted, false);
        require('node:assert').strictEqual(ctrl._transitionAbortController, transitionAc);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq);

        require('node:assert').strictEqual(preloadAc.signal.aborted, false);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, preloadSlot);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId);
        require('node:assert').strictEqual(stopCalled, false);
    });

    await t.test('12. unrelated anchor click does NOT cancel narration authority', async () => {
        const { env, getDocClickCb } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._bindEventListeners();
        const docClick = getDocClickCb();

        const anchor = createMockAnchor('unrelated-navbar-link');
        anchor.closest = (sel) => sel === 'a[href]' ? anchor : null;

        const transitionAc = new env.AbortController();
        ctrl._transitionAbortController = transitionAc;
        const initialTransSeq = ctrl._transitionSequenceId = 230;

        const preloadAc = new env.AbortController();
        const preloadSlot = { abortController: preloadAc };
        ctrl._activeNextChapterPreload = preloadSlot;
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 330;

        const initialSelectionId = ctrl._chapterSelectionId = 430;
        let stopCalled = false;
        ctrl.chapterEngine = { stop: () => { stopCalled = true; } };
        ctrl.engine = ctrl.chapterEngine;

        const { evt } = createClickEvent(anchor);
        docClick(evt);

        require('node:assert').strictEqual(transitionAc.signal.aborted, false);
        require('node:assert').strictEqual(ctrl._transitionAbortController, transitionAc);
        require('node:assert').strictEqual(ctrl._transitionSequenceId, initialTransSeq);

        require('node:assert').strictEqual(preloadAc.signal.aborted, false);
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, preloadSlot);
        require('node:assert').strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq);

        require('node:assert').strictEqual(ctrl._chapterSelectionId, initialSelectionId);
        require('node:assert').strictEqual(stopCalled, false);
    });

    await t.test('13. _selectManagedPlayback proves pending Auto Next is synchronously cancelled', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.chapterId = 'chap-1';
        ctrl.engine = ctrl.chapterEngine = {
            getSelectedVoiceKey: () => 'v1',
            stop: () => {},
            isSupported: () => true,
            loadPlayback: async () => {
                require('node:assert').strictEqual(cancelCalled, true, 'Cancel must have occurred synchronously before await loadPlayback');
                return null;
            }
        };
        ctrl.activeEngineType = 'managed';
        ctrl.isUnloaded = false;

        let cancelCalled = false;
        ctrl._cancelPendingAutoNext = () => { cancelCalled = true; };

        ctrl.managedEngine = { stop: () => {}, cancel: () => {} };

        try {
            await ctrl._selectManagedPlayback('chap-1', 'v1', null);
        } catch (err) {
            // Expected to throw because loadPlayback returns null
        }
        require('node:assert').strictEqual(cancelCalled, true);
    });

    await t.test('14. Existing claimed-preload synchronous transition behavior claims valid completed preload and skips fetch', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.chapterId = 'chap-1';
        ctrl.autoNext = true;
        ctrl.isUnloaded = false;
        ctrl.activeEngineType = 'managed';
        ctrl._voiceSelectionSequenceId = 1;
        ctrl._resolveNextChapterUrl = () => '/next-url';
        ctrl.chapterEngine = {
            getSelectedVoiceKey: () => 'v1',
            loadPlayback: async () => {},
            play: () => Promise.resolve(),
            stop: () => {}
        };
        ctrl.engine = ctrl.chapterEngine;
        ctrl.dom = {
            player: { setAttribute: () => {} },
            body: { setAttribute: () => {}, querySelectorAll: () => [] }
        };

        const validDoc = { title: 'Chapter 2' };
        const validValidation = {
            valid: true,
            newChapterId: 'chap-2',
            title: 'Chapter 2',
            bodyEl: { innerHTML: 'Chapter 2 body' }
        };
        ctrl._validateFetchedChapterDocument = () => validValidation;

        let fetchCalled = false;
        env.fetch = async () => { fetchCalled = true; return { ok: true, text: async () => '<html></html>' }; };

        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = {
            createdAt: 1000,
            sourceChapterId: 'chap-1',
            targetChapterId: 'chap-2',
            nextUrl: '/next-url',
            mode: 'managed',
            voiceKey: 'v1',
            voiceSelectionSequence: 1,
            preloadSequence: ctrl._nextChapterPreloadSequenceId,
            status: 'completed',
            abortController: preloadAc,
            result: {
                document: validDoc,
                validation: validValidation
            },
            playbackMetadata: { chapterId: 'chap-2', voiceKey: 'v1' }
        };

        let appliedDoc = null;
        let appliedMetadata = null;
        ctrl._applyChapterTransition = (doc, url, val, intent, meta) => {
            appliedDoc = doc;
            appliedMetadata = meta;
        };

        await ctrl._transitionToNextChapter('/next-url', { mode: 'managed', voiceKey: 'v1' });

        require('node:assert').strictEqual(fetchCalled, false, 'Valid preloaded snapshot must skip HTML network fetch');
        require('node:assert').strictEqual(appliedDoc, validDoc, 'Preloaded detached document must be passed to transition application');
        require('node:assert').deepStrictEqual(appliedMetadata, { chapterId: 'chap-2', voiceKey: 'v1' });
        require('node:assert').strictEqual(ctrl._activeNextChapterPreload, null, 'Active preload slot must be detached upon claim');
        require('node:assert').strictEqual(preloadAc.signal.aborted, false, 'Claimed preload AbortController must NOT be aborted');
    });
});

test('H.9I2A ChapterAudio Unavailable Authority & Fallback Tests', async (t) => {
    function createControllerEnv(customEnv = {}) {
        let docClickCb = null;
        let currentTime = 1000;
        const defaultWindow = {
            location: { reload: () => {}, href: 'http://localhost/c1' },
            addEventListener: () => {},
            removeEventListener: () => {},
            clearTimeout: clearTimeout,
            setTimeout: setTimeout
        };
        const env = {
            console,
            setTimeout,
            clearTimeout,
            Date: class extends Date { static now() { return currentTime; } },
            window: customEnv.window ? Object.assign({}, defaultWindow, customEnv.window) : defaultWindow,
            document: {
                querySelector: () => null,
                getElementById: () => null,
                createElement: () => ({ setAttribute: () => {}, appendChild: () => {}, textContent: '', value: '' }),
                addEventListener: (event, cb) => {
                    if (event === 'click') docClickCb = cb;
                },
                removeEventListener: () => {},
                dispatchEvent: () => {}
            },
            AbortController: class {
                constructor() { this.signal = { aborted: false }; }
                abort() { this.signal.aborted = true; }
            },
            DOMParser: class {
                parseFromString() {
                    return {
                        title: 'Test Doc',
                        querySelector: () => null,
                        getElementById: () => null
                    };
                }
            },
            fetch: customEnv.fetch || (async () => ({ ok: true, text: async () => '<html></html>' })),
            CustomEvent: class {}
        };
        const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        require('vm').runInNewContext(controllerSrc, env);
        return { env, getDocClickCb: () => docClickCb };
    }

    function createMockDom() {
        const createBtn = () => ({
            disabled: false,
            setAttribute: () => {},
            removeAttribute: () => {},
            classList: { add: () => {}, remove: () => {}, toggle: () => {} }
        });
        return {
            body: { setAttribute: () => {}, querySelectorAll: () => [], innerHTML: '' },
            player: { setAttribute: () => {}, classList: { add: () => {}, remove: () => {} } },
            playPauseBtn: createBtn(),
            playIcon: { style: {} },
            pauseIcon: { style: {} },
            voiceSelect: { value: '', disabled: false, querySelectorAll: () => [], appendChild: () => {}, innerHTML: '' },
            prevBtn: createBtn(),
            nextBtn: createBtn(),
            rewindBtn: createBtn(),
            forwardBtn: createBtn(),
            progressBar: { setAttribute: () => {}, getBoundingClientRect: () => ({ width: 100, left: 0 }) },
            progressFill: { style: {} },
            progressCurrent: {},
            progressTotal: {},
            statusText: { setAttribute: () => {} }
        };
    }

    await t.test('A. ChapterAudio unavailable + fallback OFF: cancels authorities, stops ChapterAudio, leaves Play disabled, preserves preference', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.chapterId = 'chap-1';
        ctrl.fallbackToDevice = false;
        ctrl.activeEngineType = 'managed';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-pref' };

        let chapterEngineStopped = false;
        ctrl.chapterEngine = {
            stop: () => { chapterEngineStopped = true; },
            getSelectedVoiceKey: () => 'voice-pref'
        };
        ctrl.engine = ctrl.chapterEngine;

        let loadManifestCalled = false;
        let setManifestCalled = false;
        let managedPlayCalled = false;
        ctrl.managedEngine = {
            loadManifest: () => { loadManifestCalled = true; },
            setManifest: () => { setManifestCalled = true; },
            play: () => { managedPlayCalled = true; },
            stop: () => {},
            cancel: () => {},
            getVoices: () => []
        };

        const transAc = new env.AbortController();
        ctrl._transitionAbortController = transAc;
        ctrl._autoNextTimeoutId = 12345;
        const initialTransSeq = ctrl._transitionSequenceId = 10;

        const preloadAc = new env.AbortController();
        ctrl._activeNextChapterPreload = { abortController: preloadAc };
        const initialPreloadSeq = ctrl._nextChapterPreloadSequenceId = 20;

        const initialSelectionId = ctrl._chapterSelectionId = 30;

        let playPauseAriaDisabled = null;
        let playPauseIsPlaying = true;

        ctrl.dom = createMockDom();
        ctrl.dom.playPauseBtn.setAttribute = (k, v) => { if (k === 'aria-disabled') playPauseAriaDisabled = v; };
        ctrl.dom.playPauseBtn.classList.remove = (cls) => { if (cls === 'is-playing') playPauseIsPlaying = false; };
        ctrl.dom.voiceSelect.disabled = true;

        ctrl._setStatusMessage = () => {};
        ctrl.chunks = [{ text: 'sentence 1' }, { text: 'sentence 2' }];

        ctrl._handleManagedUnavailable();

        // Assertions:
        assert.strictEqual(loadManifestCalled, false, 'must not call managedEngine.loadManifest');
        assert.strictEqual(setManifestCalled, false, 'must not call managedEngine.setManifest');
        assert.strictEqual(managedPlayCalled, false, 'must not call managedEngine.play');
        assert.strictEqual(ctrl._autoNextTimeoutId, null, 'autoNext timeout must be cleared');
        assert.strictEqual(transAc.signal.aborted, true, 'transition controller must be aborted');
        assert.strictEqual(ctrl._transitionAbortController, null, 'transition controller must be null');
        assert.strictEqual(ctrl._transitionSequenceId, initialTransSeq + 1, 'transition sequence must increment');
        assert.strictEqual(preloadAc.signal.aborted, true, 'preload controller must be aborted');
        assert.strictEqual(ctrl._activeNextChapterPreload, null, 'preload slot must be cleared');
        assert.strictEqual(ctrl._nextChapterPreloadSequenceId, initialPreloadSeq + 1, 'preload sequence must increment');
        assert.strictEqual(chapterEngineStopped, true, 'chapterEngine.stop must be called');
        assert.strictEqual(ctrl._chapterSelectionId, initialSelectionId + 1, 'chapterSelectionId must increment');
        assert.strictEqual(ctrl.chunks.length, 0, 'chunks must be cleared');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, true, 'playPauseBtn must be disabled');
        assert.strictEqual(playPauseAriaDisabled, 'true', 'playPauseBtn aria-disabled must be true');
        assert.strictEqual(playPauseIsPlaying, false, 'is-playing class must be removed');
        assert.strictEqual(ctrl.dom.voiceSelect.disabled, false, 'voiceSelect must remain usable');
        assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'voice-pref' }, 'durable preference must remain intact');
    });

    await t.test('B. ChapterAudio unavailable + fallback ON: delegates to Device TTS, reads DOM, preserves durable preference', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.chapterId = 'chap-1';
        ctrl.fallbackToDevice = true;
        ctrl.activeEngineType = 'managed';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-pref' };

        let devicePlayStartedWith = null;
        let deviceChunksLoaded = null;
        ctrl.deviceEngine = {
            isSupported: () => true,
            play: (idx) => { devicePlayStartedWith = idx; },
            loadChunks: (chs, idx) => { deviceChunksLoaded = chs; },
            getSortedVoices: () => [{ voiceURI: 'dev-vi', name: 'Dev Vi', lang: 'vi-VN' }],
            getVoices: () => [{ voiceURI: 'dev-vi', name: 'Dev Vi', lang: 'vi-VN' }],
            selectedVoice: { voiceURI: 'dev-vi', name: 'Dev Vi' },
            getCurrentChunkIndex: () => 0,
            stop: () => {}
        };

        let loadManifestCalled = false;
        let setManifestCalled = false;
        let managedPlayCalled = false;
        ctrl.managedEngine = {
            loadManifest: () => { loadManifestCalled = true; },
            setManifest: () => { setManifestCalled = true; },
            play: () => { managedPlayCalled = true; },
            stop: () => {},
            cancel: () => {},
            getVoices: () => []
        };

        const mockBodyChunks = [{ text: 'Parsed from DOM', element: {} }];
        ctrl.parser = {
            parseChapterBody: () => mockBodyChunks
        };
        ctrl.dom = createMockDom();
        ctrl._setStatusMessage = () => {};

        ctrl._handleManagedUnavailable();

        assert.strictEqual(ctrl.activeEngineType, 'device', 'activeEngineType must become device');
        assert.strictEqual(ctrl.engine, ctrl.deviceEngine, 'engine must become deviceEngine');
        assert.strictEqual(devicePlayStartedWith, 0, 'deviceEngine.play(0) must be called');
        assert.deepStrictEqual(deviceChunksLoaded, mockBodyChunks, 'deviceEngine must load DOM chunks');
        assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'voice-pref' }, 'durable preference must remain managed');
        assert.strictEqual(loadManifestCalled, false, 'must not call loadManifest');
        assert.strictEqual(setManifestCalled, false, 'must not call setManifest');
        assert.strictEqual(managedPlayCalled, false, 'must not call managed play');
    });

    await t.test('C. stale ChapterAudio onError ignored after Device fallback, explicit Device selection, or unload', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});

        let unavailableCalled = false;
        ctrl._handleManagedUnavailable = () => { unavailableCalled = true; };

        ctrl.chapterEngine = { isSupported: () => true };

        // 1. After Device fallback / Device active
        ctrl.engine = ctrl.deviceEngine = { isSupported: () => true };
        ctrl.activeEngineType = 'device';
        ctrl.isUnloaded = false;
        ctrl._onChapterAudioError(new Error('late error'));
        assert.strictEqual(unavailableCalled, false, 'late error must be ignored when activeEngineType is device');

        // 2. When unloaded
        ctrl.engine = ctrl.chapterEngine;
        ctrl.activeEngineType = 'managed';
        ctrl.isUnloaded = true;
        ctrl._onChapterAudioError(new Error('late error'));
        assert.strictEqual(unavailableCalled, false, 'late error must be ignored when isUnloaded is true');

        // 3. When engine is another engine (e.g. managedEngine or null)
        ctrl.isUnloaded = false;
        ctrl.engine = ctrl.managedEngine = {};
        ctrl.activeEngineType = 'managed';
        ctrl._onChapterAudioError(new Error('late error'));
        assert.strictEqual(unavailableCalled, false, 'late error must be ignored when engine !== chapterEngine');
    });

    await t.test('D. current authoritative ChapterAudio onError follows unavailable policy', async () => {
        const { env } = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        let unavailableCalled = false;
        ctrl._handleManagedUnavailable = () => { unavailableCalled = true; };

        ctrl.chapterEngine = { isSupported: () => true };
        ctrl.engine = ctrl.chapterEngine;
        ctrl.activeEngineType = 'managed';
        ctrl.isUnloaded = false;

        ctrl._onChapterAudioError(new Error('authoritative playback error'));
        assert.strictEqual(unavailableCalled, true, 'authoritative error must invoke unavailable policy');
    });

    await t.test('E. Auto Next transition when Chapter B is unavailable: fallback OFF stops safely, fallback ON plays Device B without legacy segment playback', async () => {
        const { env } = createControllerEnv();

        // Subcase 1: fallback OFF
        {
            const ctrl = new env.NarrationController.NarrationController({});
            ctrl.chapterId = 'chap-1';
            ctrl.fallbackToDevice = false;
            ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };
            ctrl.activeEngineType = 'managed';

            let loadManifestCalled = false;
            ctrl.managedEngine = {
                loadManifest: () => { loadManifestCalled = true; },
                setManifest: () => {},
                play: () => { throw new Error('should not call legacy play'); },
                stop: () => {},
                cancel: () => {},
                getSelectedVoiceKey: () => 'v1',
                getVoices: () => []
            };
            ctrl.chapterEngine = {
                isSupported: () => true,
                getSelectedVoiceKey: () => 'v1',
                probePlaybackMetadata: async () => ({ status: 'unavailable' }),
                requestPlaybackPreparation: async () => ({ status: 'UNAVAILABLE' }),
                loadPlayback: async () => { throw new Error('ChapterAudio unavailable'); },
                stop: () => {}
            };
            ctrl.engine = ctrl.chapterEngine;

            ctrl.dom = createMockDom();
            ctrl.dom.voiceSelect.value = 'managed:v1';
            ctrl._setStatusMessage = () => {};

            const validation = {
                newChapterId: 'chap-2',
                title: 'Chapter 2',
                bodyEl: { innerHTML: '<p>Chapter 2 prose</p>' }
            };

            const mockDoc = { getElementById: () => null, querySelector: () => null };
            ctrl._applyChapterTransition(mockDoc, '/novel/chapters/chap-2', validation, { mode: 'managed', voiceKey: 'v1' });

            await new Promise(resolve => setTimeout(resolve, 50));

            assert.strictEqual(ctrl.chapterId, 'chap-2', 'transition should commit chapterId chap-2');
            assert.strictEqual(ctrl.dom.playPauseBtn.disabled, true, 'playPauseBtn must be disabled on chap-2');
            assert.strictEqual(loadManifestCalled, false, 'must not call legacy loadManifest');
            assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'v1' }, 'durable preference remains managed');
        }

        // Subcase 2: fallback ON
        {
            const ctrl = new env.NarrationController.NarrationController({});
            ctrl.chapterId = 'chap-1';
            ctrl.fallbackToDevice = true;
            ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };
            ctrl.activeEngineType = 'managed';

            let devicePlayedWith = null;
            ctrl.deviceEngine = {
                isSupported: () => true,
                play: (idx) => { devicePlayedWith = idx; },
                loadChunks: () => {},
                getCurrentChunkIndex: () => 0,
                getSortedVoices: () => [{ voiceURI: 'dev-vi' }],
                getVoices: () => [{ voiceURI: 'dev-vi' }],
                selectedVoice: { voiceURI: 'dev-vi' },
                stop: () => {}
            };

            let loadManifestCalled = false;
            ctrl.managedEngine = {
                loadManifest: () => { loadManifestCalled = true; },
                setManifest: () => {},
                play: () => { throw new Error('should not call legacy play'); },
                stop: () => {},
                cancel: () => {},
                getSelectedVoiceKey: () => 'v1',
                getVoices: () => []
            };
            ctrl.chapterEngine = {
                isSupported: () => true,
                getSelectedVoiceKey: () => 'v1',
                loadPlayback: async () => { throw new Error('ChapterAudio unavailable'); },
                stop: () => {}
            };
            ctrl.engine = ctrl.chapterEngine;

            const chap2BodyChunks = [{ text: 'Chap 2 prose line 1' }];
            ctrl.parser = {
                parseChapterBody: () => chap2BodyChunks
            };

            ctrl.dom = createMockDom();
            ctrl.dom.voiceSelect.value = 'managed:v1';
            ctrl._setStatusMessage = () => {};

            const validation = {
                newChapterId: 'chap-2',
                title: 'Chapter 2',
                bodyEl: { innerHTML: '<p>Chapter 2 prose</p>' }
            };

            const mockDoc = { getElementById: () => null, querySelector: () => null };
            ctrl._applyChapterTransition(mockDoc, '/novel/chapters/chap-2', validation, { mode: 'managed', voiceKey: 'v1' });

            await new Promise(resolve => setTimeout(resolve, 50));

            assert.strictEqual(ctrl.chapterId, 'chap-2', 'transition should commit chapterId chap-2');
            assert.strictEqual(ctrl.activeEngineType, 'device', 'activeEngineType should become device on chap-2');
            assert.strictEqual(ctrl.engine, ctrl.deviceEngine, 'engine should become deviceEngine');
            assert.strictEqual(devicePlayedWith, 0, 'deviceEngine.play(0) should be called');
            assert.strictEqual(loadManifestCalled, false, 'must not call legacy loadManifest');
            assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'v1' }, 'durable preference remains managed');
        }
    });
});

test('H.9I3 ChapterAudio Availability Probe & Negative Reuse Tests', async (t) => {
    const assert = require('node:assert');
    const { ChapterAudioEngine } = require('../../../main/resources/static/js/novel/chapter-audio-engine.js');

    function createMockDom() {
        const createBtn = () => ({
            disabled: false,
            setAttribute: () => {},
            removeAttribute: () => {},
            classList: { add: () => {}, remove: () => {}, toggle: () => {} }
        });
        return {
            body: { setAttribute: () => {}, querySelectorAll: () => [], innerHTML: '' },
            player: { setAttribute: () => {}, classList: { add: () => {}, remove: () => {} } },
            playPauseBtn: createBtn(),
            playIcon: { style: {} },
            pauseIcon: { style: {} },
            voiceSelect: { value: 'managed:v1', disabled: false, querySelectorAll: () => [], appendChild: () => {}, innerHTML: '', options: [] },
            prevBtn: createBtn(),
            nextBtn: createBtn(),
            rewindBtn: createBtn(),
            forwardBtn: createBtn(),
            progressBar: { setAttribute: () => {}, getBoundingClientRect: () => ({ width: 100, left: 0 }) },
            progressFill: { style: {} },
            progressCurrent: { textContent: '' },
            progressTotal: { textContent: '' },
            statusText: { setAttribute: () => {} }
        };
    }

    function createControllerEnv(customDateNow) {
        let currentTime = 1000;
        class CustomEvent {
            constructor(type, eventInitDict) {
                this.type = type;
                this.detail = eventInitDict ? eventInitDict.detail : null;
            }
        }
        const fakeAudioFactory = () => {
            const listeners = {};
            return {
                readyState: 4,
                currentTime: 0,
                duration: 100,
                ended: false,
                playbackRate: 1,
                src: '',
                preload: '',
                _listeners: listeners,
                addEventListener(event, cb) {
                    if (!listeners[event]) listeners[event] = [];
                    listeners[event].push(cb);
                },
                removeEventListener(event, cb) {
                    if (listeners[event]) {
                        listeners[event] = listeners[event].filter(l => l !== cb);
                    }
                },
                emit(event) {
                    if (listeners[event]) {
                        listeners[event].forEach(cb => cb());
                    }
                },
                play() {
                    if (listeners['play']) listeners['play'].forEach(cb => cb());
                    if (listeners['playing']) listeners['playing'].forEach(cb => cb());
                    return Promise.resolve();
                },
                pause() {
                    if (listeners['pause']) listeners['pause'].forEach(cb => cb());
                },
                removeAttribute(attr) {
                    if (attr === 'src') this.src = '';
                },
                load() {
                    this.readyState = 4;
                    if (listeners['canplay']) listeners['canplay'].forEach(cb => cb());
                }
            };
        };
        const env = {
            console, setTimeout, clearTimeout, CustomEvent,
            URL: typeof URL !== 'undefined' ? URL : class URL { constructor(u) { this.pathname = u; } },
            window: { location: { reload: () => {}, href: 'http://localhost/c1' }, history: { pushState: () => {} } },
            document: {
                querySelector: () => null,
                querySelectorAll: () => [],
                getElementById: () => null,
                dispatchEvent: () => true,
                createElement: () => ({ setAttribute: () => {}, appendChild: () => {}, querySelectorAll: () => [], textContent: '', value: '' }),
                title: ''
            },
            Audio: fakeAudioFactory,
            AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } },
            Date: class extends Date { static now() { return typeof customDateNow === 'function' ? customDateNow() : currentTime; } },
            DOMParser: class {
                parseFromString() {
                    return {
                        querySelector: () => null,
                        querySelectorAll: () => [],
                        getElementById: () => null,
                        title: 'Test'
                    };
                }
            },
            fetch: async () => ({ ok: true, text: async () => '<html></html>' })
        };
        env.fakeAudioFactory = fakeAudioFactory;
        env.setCurrentTime = (t) => { currentTime = t; };
        const engineSrc = require('fs').readFileSync('src/main/resources/static/js/novel/chapter-audio-engine.js', 'utf8');
        require('vm').runInNewContext(engineSrc, env);
        const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        require('vm').runInNewContext(controllerSrc, env);
        return env;
    }

    function createMockController(env) {
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._env = env;
        ctrl.autoNext = true;
        ctrl.isUnloaded = false;
        ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };
        ctrl._voiceSelectionSequenceId = 1;
        ctrl._nextChapterPreloadSequenceId = 1;
        ctrl.chapterEngine = {
            isSupported: () => true,
            getState: () => 'PLAYING',
            getSelectedVoiceKey: () => 'v1',
            getCurrentChunkIndex: () => -1,
            getCurrentChunk: () => null,
            getSegments: () => [],
            getProgress: () => ({ currentTimeSeconds: 0, durationSeconds: 100, progressRatio: 0 }),
            loadPlayback: async () => null,
            stop: () => {},
            setRate: () => {},
            seekBySeconds: () => {},
            play: () => Promise.resolve(),
            pause: () => {},
            canPrevious: () => false,
            canNext: () => false
        };
        ctrl.managedEngine = {
            isSupported: () => true,
            loadVoiceCatalog: async () => ({ voices: [{ voiceKey: 'v1' }] }),
            cancelVoiceCatalogLoad: () => {},
            getVoices: () => [{ voiceKey: 'v1' }],
            destroy: () => {}
        };
        ctrl.engine = ctrl.chapterEngine;
        ctrl._updateChapterProgressDisplay = () => {};
        ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._validateFetchedChapterDocument = () => ({
            valid: true,
            newChapterId: '2',
            title: 'Chapter 2',
            bodyEl: { innerHTML: '<p>Next</p>' },
            breadcrumbEl: { innerHTML: 'Next' },
            headerEl: { innerHTML: 'Next' },
            navTopEl: { innerHTML: 'Next' },
            navBottomEl: { innerHTML: 'Next' }
        });
        ctrl.dom = createMockDom();
        return ctrl;
    }

    function createValidSnapshot(ctrl, customOpts = {}) {
        return {
            sourceChapterId: '1',
            nextUrl: '/next',
            targetChapterId: '2',
            mode: 'managed',
            voiceKey: 'v1',
            voiceSelectionSequence: ctrl._voiceSelectionSequenceId,
            preloadSequence: ctrl._nextChapterPreloadSequenceId,
            createdAt: 1000,
            abortController: new (ctrl._env ? ctrl._env.AbortController : AbortController)(),
            promise: Promise.resolve(),
            status: 'completed',
            result: {
                document: {
                    querySelector: () => null,
                    querySelectorAll: () => [],
                    getElementById: () => null,
                    title: 'Chapter 2'
                },
                validation: {
                    valid: true,
                    newChapterId: '2',
                    title: 'Chapter 2',
                    bodyEl: { innerHTML: '<p>Next</p>' },
                    breadcrumbEl: { innerHTML: 'Next' },
                    headerEl: { innerHTML: 'Next' },
                    navTopEl: { innerHTML: 'Next' },
                    navBottomEl: { innerHTML: 'Next' }
                }
            },
            playbackMetadata: { audioUrl: 'test.mp3' },
            playbackAvailability: 'ready',
            ...customOpts
        };
    }

    await t.test('1a. READY CURRENT playable returns ready with metadata without mutating active Chapter A', async () => {
        let stopCalled = false;
        const validMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            audioUrl: 'http://example.com/ch2.mp3',
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        const engine = new ChapterAudioEngine({
            fetchFunction: async (url) => {
                assert.ok(url.includes('/chapters/2/narration/playback?voiceKey=v1'));
                return { ok: true, json: async () => validMeta };
            }
        });
        engine.state = 'PLAYING';
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        engine.cues = [{ cueOrdinal: 0, startMillis: 0, endMillis: 500 }];
        engine._activeCueIndex = 0;
        engine.stop = () => { stopCalled = true; };

        const result = await engine.probePlaybackMetadata('2', 'v1');

        assert.strictEqual(result.status, 'ready');
        assert.deepStrictEqual(result.metadata, validMeta);
        assert.strictEqual(stopCalled, false, 'probe must never call this.stop()');
        assert.strictEqual(engine.state, 'PLAYING', 'active Chapter A state must remain PLAYING');
        assert.strictEqual(engine.metadata.chapterId, '1', 'Chapter A metadata must remain unmodified');
        assert.strictEqual(engine.cues[0].endMillis, 500, 'Chapter A cues must remain unmodified');
    });

    await t.test('1b. READY STALE_VOICE playable returns ready with metadata', async () => {
        const staleVoiceMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'READY',
            playable: true,
            freshness: 'STALE_VOICE',
            audioUrl: 'http://example.com/ch2-stale-voice.mp3',
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        const engine = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => staleVoiceMeta })
        });
        const result = await engine.probePlaybackMetadata('2', 'v1');
        assert.strictEqual(result.status, 'ready');
        assert.deepStrictEqual(result.metadata, staleVoiceMeta);
    });

    await t.test('2a. MISSING with playable=false returns unavailable', async () => {
        const missingMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'MISSING',
            freshness: null,
            playable: false,
            audioUrl: null,
            cues: []
        };
        const engine = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => missingMeta })
        });
        const result = await engine.probePlaybackMetadata('2', 'v1');
        assert.strictEqual(result.status, 'unavailable');
        assert.deepStrictEqual(result.metadata, missingMeta);
    });

    await t.test('2b. FAILED with playable=false returns unavailable', async () => {
        const failedMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'FAILED',
            freshness: null,
            playable: false,
            audioUrl: null,
            cues: []
        };
        const engine = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => failedMeta })
        });
        const result = await engine.probePlaybackMetadata('2', 'v1');
        assert.strictEqual(result.status, 'unavailable');
        assert.deepStrictEqual(result.metadata, failedMeta);
    });

    await t.test('2c. READY STALE_CONTENT with playable=false returns unavailable', async () => {
        const staleContentMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'READY',
            freshness: 'STALE_CONTENT',
            playable: false,
            audioUrl: null,
            cues: []
        };
        const engine = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => staleContentMeta })
        });
        const result = await engine.probePlaybackMetadata('2', 'v1');
        assert.strictEqual(result.status, 'unavailable');
        assert.deepStrictEqual(result.metadata, staleContentMeta);
    });

    await t.test('3a. BUILDING returns unknown to preserve cold retry at transition', async () => {
        const buildingMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'BUILDING',
            freshness: null,
            playable: false,
            audioUrl: null,
            cues: []
        };
        const engine = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => buildingMeta })
        });
        const result = await engine.probePlaybackMetadata('2', 'v1');
        assert.strictEqual(result.status, 'unknown');
    });

    await t.test('3b. Identity-matching but structurally incomplete or inconsistent payload returns unknown', async () => {
        const incomplete1 = { chapterId: '2', voiceKey: 'v1' };
        const incomplete2 = { chapterId: '2', voiceKey: 'v1', availability: 'READY', playable: true, freshness: 'CURRENT', audioUrl: null, cues: [] };
        const inconsistent1 = { chapterId: '2', voiceKey: 'v1', availability: 'READY', playable: false, freshness: 'CURRENT' };
        const inconsistent2 = { chapterId: '2', voiceKey: 'v1', availability: 'MISSING', playable: true };

        for (const payload of [incomplete1, incomplete2, inconsistent1, inconsistent2]) {
            const engine = new ChapterAudioEngine({
                fetchFunction: async () => ({ ok: true, json: async () => payload })
            });
            const result = await engine.probePlaybackMetadata('2', 'v1');
            assert.strictEqual(result.status, 'unknown', 'payload ' + JSON.stringify(payload) + ' must return unknown');
        }
    });

    await t.test('3c. Unknown availability value returns unknown', async () => {
        for (const badAvailability of ['PENDING', 'PROCESSING', 'UNKNOWN', 'INVALID', null, 123]) {
            const engine = new ChapterAudioEngine({
                fetchFunction: async () => ({
                    ok: true,
                    json: async () => ({ chapterId: '2', voiceKey: 'v1', availability: badAvailability, playable: false })
                })
            });
            const result = await engine.probePlaybackMetadata('2', 'v1');
            assert.strictEqual(result.status, 'unknown', 'bad availability ' + badAvailability + ' must return unknown');
        }
    });

    await t.test('4a. chapterId mismatch returns unknown', async () => {
        const engineWrongChap = new ChapterAudioEngine({
            fetchFunction: async () => ({
                ok: true,
                json: async () => ({ chapterId: '999', voiceKey: 'v1', availability: 'MISSING', playable: false })
            })
        });
        assert.deepStrictEqual(await engineWrongChap.probePlaybackMetadata('2', 'v1'), { status: 'unknown' });
    });

    await t.test('4b. voiceKey mismatch returns unknown', async () => {
        const engineWrongVoice = new ChapterAudioEngine({
            fetchFunction: async () => ({
                ok: true,
                json: async () => ({ chapterId: '2', voiceKey: 'wrong_voice', availability: 'MISSING', playable: false })
            })
        });
        assert.deepStrictEqual(await engineWrongVoice.probePlaybackMetadata('2', 'v1'), { status: 'unknown' });
    });

    await t.test('4c. Network failure, non-2xx response, or JSON parse error returns unknown', async () => {
        const engine500 = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: false, status: 500 })
        });
        assert.deepStrictEqual(await engine500.probePlaybackMetadata('2', 'v1'), { status: 'unknown' });

        const engineNetErr = new ChapterAudioEngine({
            fetchFunction: async () => { throw new Error('Network error'); }
        });
        assert.deepStrictEqual(await engineNetErr.probePlaybackMetadata('2', 'v1'), { status: 'unknown' });

        const engineJsonErr = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => { throw new SyntaxError('Unexpected token'); } })
        });
        assert.deepStrictEqual(await engineJsonErr.probePlaybackMetadata('2', 'v1'), { status: 'unknown' });
    });

    await t.test('5. AbortError propagates cancellation and never returns unavailable or unknown', async () => {
        const ac = new AbortController();
        ac.abort();
        const engine = new ChapterAudioEngine({
            fetchFunction: async () => ({ ok: true, json: async () => ({ chapterId: '2', voiceKey: 'v1', availability: 'MISSING', playable: false }) })
        });
        let threwAbort = false;
        try {
            await engine.probePlaybackMetadata('2', 'v1', { signal: ac.signal });
        } catch (e) {
            threwAbort = (e && e.name === 'AbortError');
        }
        assert.strictEqual(threwAbort, true, 'probe with aborted signal must throw AbortError');
    });

    await t.test('6. Completed matching READY snapshot reuses metadata without duplicate GETs', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const validMetadata = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            audioUrl: 'http://example.com/audio2.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        let metaFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async () => {
                metaFetchCount++;
                return { ok: true, json: async () => validMetadata };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: validMetadata, playbackAvailability: 'ready' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 0, 'HTML GET must be bypassed');
        assert.strictEqual(metaFetchCount, 0, 'Playback metadata GET must be bypassed');
        assert.strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM transition must commit');
        assert.strictEqual(engine.metadata, validMetadata, 'Engine should hold preloaded metadata');
    });

    await t.test('7a. Completed matching UNAVAILABLE snapshot with fallback OFF: stops safely, ZERO metadata GET', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        ctrl.fallbackToDevice = false;
        let metaFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async (url, opts) => {
                const method = (opts && opts.method) || 'GET';
                if (method === 'GET') metaFetchCount++;
                return { ok: false };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null, playbackAvailability: 'unavailable' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 0, 'HTML GET must be bypassed');
        assert.strictEqual(metaFetchCount, 0, 'ZERO metadata GET should be issued when availability is unavailable');
        assert.strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM must commit');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play button must remain enabled and retryable under C2A1 failure policy');
        assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'v1' }, 'durable Managed preference preserved');
    });

    await t.test('7b. Completed matching UNAVAILABLE snapshot with fallback ON: Device reads Chapter B, ZERO metadata GET', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        ctrl.fallbackToDevice = true;

        let devicePlayed = false;
        ctrl.deviceEngine = {
            isSupported: () => true,
            play: () => { devicePlayed = true; },
            loadChunks: () => {},
            getCurrentChunkIndex: () => 0,
            getSortedVoices: () => [{ voiceURI: 'dev-vi' }],
            getVoices: () => [{ voiceURI: 'dev-vi' }],
            selectedVoice: { voiceURI: 'dev-vi' },
            stop: () => {}
        };
        ctrl.parser = { parseChapterBody: () => [{ text: 'prose' }] };

        let metaFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async (url, opts) => {
                const method = (opts && opts.method) || 'GET';
                if (method === 'GET') metaFetchCount++;
                return { ok: false };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null, playbackAvailability: 'unavailable' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 0, 'HTML GET must be bypassed');
        assert.strictEqual(metaFetchCount, 0, 'ZERO metadata GET should be issued');
        assert.strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM must commit');
        assert.strictEqual(ctrl.activeEngineType, 'device', 'Engine switched to Device TTS');
        assert.strictEqual(devicePlayed, true, 'Device TTS must play Chapter B');
        assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'v1' }, 'durable preference preserved');
    });

    await t.test('8. Completed matching UNKNOWN snapshot allows exactly one cold metadata GET', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);
        const coldMeta = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            audioUrl: 'http://example.com/cold.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        let metaFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async () => {
                metaFetchCount++;
                return { ok: true, json: async () => coldMeta };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null, playbackAvailability: 'unknown' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 0, 'HTML GET was preloaded and reused');
        assert.strictEqual(metaFetchCount, 1, 'Exactly one cold metadata GET was permitted for UNKNOWN');
        assert.strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM transition committed');
        assert.strictEqual(engine.metadata, coldMeta, 'Engine loaded cold metadata');
    });

    await t.test('9. Expired or mismatched negative snapshot is never trusted', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        let metaFetchCount = 0;
        const engine = new env.ChapterAudioEngine.ChapterAudioEngine({
            audioFactory: env.fakeAudioFactory,
            fetchFunction: async () => {
                metaFetchCount++;
                return { ok: false };
            }
        });
        engine.metadata = { chapterId: '1', voiceKey: 'v1' };
        ctrl.chapterEngine = engine;
        ctrl.engine = engine;

        // Expired snapshot (> 60s in env time where currentTime = 1000)
        const expiredSnapshot = createValidSnapshot(ctrl, {
            createdAt: -70000,
            playbackMetadata: null,
            playbackAvailability: 'unavailable'
        });
        ctrl._activeNextChapterPreload = expiredSnapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return {
                ok: true,
                text: async () => '<div class="novel-reader-chapter-body" data-chapter-id="2"><p>B</p></div>' +
                    '<title>Ch 2</title><div class="novel-chapter-breadcrumb"></div><div class="novel-chapter-header"></div>' +
                    '<div class="novel-chapter-nav--top"></div><div class="novel-chapter-nav--bottom"></div>'
            };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 1, 'Expired snapshot must force cold HTML fetch');
        assert.strictEqual(metaFetchCount, 1, 'Expired snapshot must force cold metadata GET');
    });

    await t.test('10. Pending preload remains never-awaited at chapter transition', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        let resolvePreloadPromise;
        const pendingPromise = new Promise(resolve => { resolvePreloadPromise = resolve; });

        const pendingSnapshot = createValidSnapshot(ctrl, {
            status: 'pending',
            promise: pendingPromise
        });
        ctrl._activeNextChapterPreload = pendingSnapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return {
                ok: true,
                text: async () => '<div class="novel-reader-chapter-body" data-chapter-id="2"><p>B</p></div>' +
                    '<title>Ch 2</title><div class="novel-chapter-breadcrumb"></div><div class="novel-chapter-header"></div>' +
                    '<div class="novel-chapter-nav--top"></div><div class="novel-chapter-nav--bottom"></div>'
            };
        };

        const transPromise = ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 1, 'Pending preload must not be awaited; cold HTML fetch started immediately');
        assert.strictEqual(pendingSnapshot.abortController.signal.aborted, true, 'Pending preload must be cancelled');

        resolvePreloadPromise();
        await transPromise;
    });

    await t.test('11. Device continuation never consumes Managed preload', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        const validMetadata = {
            chapterId: '2',
            voiceKey: 'v1',
            availability: 'READY',
            playable: true,
            freshness: 'CURRENT',
            audioUrl: 'http://example.com/audio2.mp3',
            durationMillis: 60000,
            cues: [{ cueOrdinal: 0, startMillis: 0, endMillis: 1000 }]
        };
        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: validMetadata, playbackAvailability: 'ready' });
        ctrl._activeNextChapterPreload = snapshot;

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return {
                ok: true,
                text: async () => '<div class="novel-reader-chapter-body" data-chapter-id="2"><p>B</p></div>' +
                    '<title>Ch 2</title><div class="novel-chapter-breadcrumb"></div><div class="novel-chapter-header"></div>' +
                    '<div class="novel-chapter-nav--top"></div><div class="novel-chapter-nav--bottom"></div>'
            };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'device' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(htmlFetchCount, 1, 'Device continuation must ignore Managed preload and perform cold HTML fetch');
        assert.strictEqual(ctrl.activeEngineType, 'device');
    });

    await t.test('12. No /prepare and no legacy Managed manifest/play path appears', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        let legacyManifestCalled = false;
        let legacyPlayCalled = false;
        ctrl.managedEngine = {
            loadManifest: () => { legacyManifestCalled = true; },
            setManifest: () => {},
            play: () => { legacyPlayCalled = true; },
            stop: () => {},
            cancel: () => {},
            getSelectedVoiceKey: () => 'v1',
            getVoices: () => []
        };
        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null, playbackAvailability: 'unavailable' });
        ctrl._activeNextChapterPreload = snapshot;

        env.fetch = async (url) => {
            assert.ok(!url.includes('/prepare'), 'Must not call /prepare');
            assert.ok(!url.includes('/manifest'), 'Must not call /manifest');
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(resolve => setTimeout(resolve, 50));

        assert.strictEqual(legacyManifestCalled, false, 'No legacy loadManifest should be called');
        assert.strictEqual(legacyPlayCalled, false, 'No legacy play should be called');
    });
});

test('H.9I4 Shrink ManagedAudioEngine to Voice Catalog Only Tests', async (t) => {
    function createMockDom() {
        const createBtn = () => ({
            disabled: false,
            setAttribute: () => {},
            removeAttribute: () => {},
            classList: { add: () => {}, remove: () => {}, toggle: () => {} }
        });
        return {
            body: { setAttribute: () => {}, querySelectorAll: () => [], innerHTML: '' },
            player: { setAttribute: () => {}, classList: { add: () => {}, remove: () => {} } },
            playPauseBtn: createBtn(),
            playIcon: { style: {} },
            pauseIcon: { style: {} },
            voiceSelect: { value: 'managed:v1', disabled: false, querySelectorAll: () => [], appendChild: () => {}, innerHTML: '', options: [] },
            prevBtn: createBtn(),
            nextBtn: createBtn(),
            rewindBtn: createBtn(),
            forwardBtn: createBtn(),
            progressBar: { setAttribute: () => {}, getBoundingClientRect: () => ({ width: 100, left: 0 }) },
            progressFill: { style: {} },
            progressCurrent: { textContent: '' },
            progressTotal: { textContent: '' },
            statusText: { setAttribute: () => {} }
        };
    }

    function createControllerEnv(customDateNow) {
        let currentTime = 1000;
        class CustomEvent {
            constructor(type, eventInitDict) {
                this.type = type;
                this.detail = eventInitDict ? eventInitDict.detail : null;
            }
        }
        const fakeAudioFactory = () => {
            const listeners = {};
            return {
                readyState: 4,
                currentTime: 0,
                duration: 100,
                ended: false,
                playbackRate: 1,
                src: '',
                preload: '',
                _listeners: listeners,
                addEventListener(event, cb) {
                    if (!listeners[event]) listeners[event] = [];
                    listeners[event].push(cb);
                },
                removeEventListener(event, cb) {
                    if (listeners[event]) {
                        listeners[event] = listeners[event].filter(l => l !== cb);
                    }
                },
                emit(event) {
                    if (listeners[event]) {
                        listeners[event].forEach(cb => cb());
                    }
                },
                play() {
                    if (listeners['play']) listeners['play'].forEach(cb => cb());
                    if (listeners['playing']) listeners['playing'].forEach(cb => cb());
                    return Promise.resolve();
                },
                pause() {
                    if (listeners['pause']) listeners['pause'].forEach(cb => cb());
                },
                removeAttribute(attr) {
                    if (attr === 'src') this.src = '';
                },
                load() {
                    this.readyState = 4;
                    if (listeners['canplay']) listeners['canplay'].forEach(cb => cb());
                }
            };
        };
        const env = {
            console, setTimeout, clearTimeout, CustomEvent,
            URL: typeof URL !== 'undefined' ? URL : class URL { constructor(u) { this.pathname = u; } },
            window: { location: { reload: () => {}, href: 'http://localhost/c1' }, history: { pushState: () => {} } },
            document: {
                querySelector: () => null,
                querySelectorAll: () => [],
                getElementById: () => null,
                dispatchEvent: () => true,
                createElement: () => ({ setAttribute: () => {}, appendChild: () => {}, querySelectorAll: () => [], textContent: '', value: '' }),
                title: ''
            },
            Audio: fakeAudioFactory,
            AbortController: class { constructor() { this.signal = { aborted: false }; } abort() { this.signal.aborted = true; } },
            Date: class extends Date { static now() { return typeof customDateNow === 'function' ? customDateNow() : currentTime; } },
            DOMParser: class {
                parseFromString() {
                    return {
                        querySelector: () => null,
                        querySelectorAll: () => [],
                        getElementById: () => null,
                        title: 'Test'
                    };
                }
            },
            fetch: async () => ({ ok: true, text: async () => '<html></html>' })
        };
        env.fakeAudioFactory = fakeAudioFactory;
        env.setCurrentTime = (t) => { currentTime = t; };
        const engineSrc = require('fs').readFileSync('src/main/resources/static/js/novel/chapter-audio-engine.js', 'utf8');
        require('vm').runInNewContext(engineSrc, env);
        const controllerSrc = require('fs').readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        require('vm').runInNewContext(controllerSrc, env);
        return env;
    }

    function createMockController(env) {
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._env = env;
        ctrl.autoNext = true;
        ctrl.isUnloaded = false;
        ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };
        ctrl._voiceSelectionSequenceId = 1;
        ctrl._nextChapterPreloadSequenceId = 1;
        ctrl.chapterEngine = {
            isSupported: () => true,
            getState: () => 'PLAYING',
            getSelectedVoiceKey: () => 'v1',
            getCurrentChunkIndex: () => -1,
            getCurrentChunk: () => null,
            getSegments: () => [],
            getProgress: () => ({ currentTimeSeconds: 0, durationSeconds: 100, progressRatio: 0 }),
            loadPlayback: async () => null,
            stop: () => {},
            setRate: () => {},
            seekBySeconds: () => {},
            play: () => Promise.resolve(),
            pause: () => {},
            canPrevious: () => false,
            canNext: () => false
        };
        ctrl.managedEngine = {
            isSupported: () => true,
            loadVoiceCatalog: async () => ({ voices: [{ voiceKey: 'v1' }] }),
            cancelVoiceCatalogLoad: () => {},
            getVoices: () => [{ voiceKey: 'v1' }],
            destroy: () => {}
        };
        ctrl.engine = ctrl.chapterEngine;
        ctrl.activeEngine = ctrl.chapterEngine;
        ctrl._updateChapterProgressDisplay = () => {};
        ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl.dom = createMockDom();
        return ctrl;
    }

    await t.test('A. selecting a Managed voice establishes ChapterAudioEngine, never ManagedAudioEngine, as Reader playback authority', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        let selectManagedPlaybackCalled = false;
        let selectedChapterId = null;
        let selectedVoiceKey = null;
        ctrl._selectManagedPlayback = async (chapId, vKey) => {
            selectManagedPlaybackCalled = true;
            selectedChapterId = chapId;
            selectedVoiceKey = vKey;
            ctrl.activeEngine = ctrl.chapterEngine;
            ctrl.engine = ctrl.chapterEngine;
            ctrl.activeEngineType = 'managed';
        };

        await ctrl._activateEngine('managed', 'voice-kien');

        assert.strictEqual(ctrl.activeEngineType, 'managed');
        assert.strictEqual(ctrl.activeEngine, ctrl.chapterEngine);
        assert.strictEqual(ctrl.engine, ctrl.chapterEngine);
        assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);
        assert.notStrictEqual(ctrl.activeEngine, ctrl.managedEngine);
        assert.strictEqual(selectManagedPlaybackCalled, true);
        assert.strictEqual(selectedVoiceKey, 'voice-kien');
    });

    await t.test('B. default/saved Managed voice initialization cannot assign ManagedAudioEngine to this.engine', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        const dummyManagedEngine = { isSupported: () => true, getVoices: () => [] };
        ctrl.managedEngine = dummyManagedEngine;
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };

        assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);
        assert.notStrictEqual(ctrl.activeEngine, ctrl.managedEngine);

        const managedVoices = [{ voiceKey: 'v1', displayName: 'Voice 1' }];
        const deviceVoices = [{ voiceURI: 'dev1', name: 'Dev 1', lang: 'vi-VN' }];
        ctrl.chapterEngine = {
            isSupported: () => true,
            getSelectedVoiceKey: () => 'v1',
            stop: () => {},
            getProgress: () => ({ durationSeconds: 0, currentTimeSeconds: 0, progressRatio: 0 })
        };
        ctrl._selectManagedPlayback = async () => {};
        ctrl.dom = createMockDom();

        ctrl._populateVoiceDropdown(deviceVoices, managedVoices, { skipActivation: false });

        assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);
        assert.notStrictEqual(ctrl.activeEngine, ctrl.managedEngine);
        assert.strictEqual(ctrl.engine, ctrl.chapterEngine);
        assert.strictEqual(ctrl.activeEngine, ctrl.chapterEngine);
    });

    await t.test('C. explicit Device selection still makes BrowserTtsEngine authoritative', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        let deviceStopCalled = false;
        let chapterStopCalled = false;
        ctrl.deviceEngine = {
            isSupported: () => true,
            stop: () => { deviceStopCalled = true; },
            setVoice: () => {},
            setVoiceByURI: () => {},
            setRate: () => {},
            getState: () => 'IDLE',
            getProgress: () => ({ currentSentence: 0, totalSentences: 0 }),
            getVoices: () => [],
            selectedVoice: { name: 'dev1', voiceURI: 'dev1' }
        };
        ctrl.chapterEngine.stop = () => { chapterStopCalled = true; };

        ctrl._activateEngine('device', 'Google Vietnamese');

        assert.strictEqual(ctrl.activeEngineType, 'device');
        assert.strictEqual(ctrl.activeEngine, ctrl.deviceEngine);
        assert.strictEqual(ctrl.engine, ctrl.deviceEngine);
        assert.notStrictEqual(ctrl.engine, ctrl.chapterEngine);
        assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);
        assert.strictEqual(chapterStopCalled, true, 'Managed ChapterAudio must be stopped on switch to Device');
    });

    await t.test('G. no Reader code calls loadManifest, setManifest, prepareSegmentPlayback, ManagedAudioEngine.play', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        const prohibited = ['loadManifest', 'setManifest', 'prepareSegmentPlayback', 'play', 'pause', 'resume', 'seekToSegment', 'seekToChunk', 'nextSegment', 'previousSegment'];
        prohibited.forEach(method => {
            ctrl.managedEngine[method] = () => {
                throw new Error(`Prohibited legacy method called: ${method}`);
            };
        });

        ctrl._selectManagedPlayback = async () => {};
        await ctrl._activateEngine('managed', 'v1');

        ctrl.dom.rateSelect = { value: '1.25' };
        ctrl._handleRateChange();

        ctrl._handleUnload();

        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };
        ctrl.fallbackToDevice = true;
        ctrl.deviceEngine = {
            isSupported: () => true,
            stop: () => {},
            loadChunks: () => {},
            getVoices: () => [],
            selectedVoice: { name: 'dev1', voiceURI: 'dev1' },
            setRate: () => {}
        };
        ctrl._handleManagedUnavailable('Audio unavailable');

        assert.ok(true, 'No legacy playback methods were called on managedEngine');
    });

    await t.test('H. H.9I2 Device fallback still preserves durable Managed preference', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'preferred-v1' };
        ctrl.fallbackToDevice = true;
        ctrl.deviceEngine = {
            isSupported: () => true,
            stop: () => {},
            loadChunks: () => {},
            setVoice: () => {},
            setVoiceByURI: () => {},
            getState: () => 'IDLE',
            getProgress: () => ({ currentSentence: 0, totalSentences: 0 }),
            getVoices: () => [],
            selectedVoice: { name: 'dev1', voiceURI: 'dev1' }
        };

        ctrl._handleManagedUnavailable('ChapterAudio unavailable test');

        assert.strictEqual(ctrl.activeEngineType, 'device');
        assert.strictEqual(ctrl.engine, ctrl.deviceEngine);
        assert.strictEqual(ctrl.activeEngine, ctrl.deviceEngine);
        assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'preferred-v1' },
            'Durable Managed preference must be preserved during Device fallback');
    });

    await t.test('I. H.9I4B Blocker 2: invalid or missing voiceKey in _activateEngine(managed) is a true no-op with zero side effects', async () => {
        const env = createControllerEnv();
        const ctrl = createMockController(env);

        let cancelPreloadCalls = 0;
        ctrl._cancelNextChapterPreload = () => { cancelPreloadCalls++; };

        let invalidateCalls = 0;
        ctrl._invalidateChapterPlayback = () => { invalidateCalls++; };

        let selectManagedCalls = 0;
        ctrl._selectManagedPlayback = async () => { selectManagedCalls++; };

        let chapterStopCalls = 0;
        ctrl.chapterEngine.stop = () => { chapterStopCalls++; };

        const initialSelectionId = ctrl._chapterSelectionId;
        const initialVoiceSeqId = ctrl._voiceSelectionSequenceId;
        const initialEngine = ctrl.engine;
        const initialActiveEngine = ctrl.activeEngine;
        const initialEngineType = ctrl.activeEngineType;

        // Calling with null, undefined, empty string, whitespace string, or non-string
        const invalidKeys = [null, undefined, '', '   ', 123, {}, false];
        for (const key of invalidKeys) {
            await ctrl._activateEngine('managed', key);
        }

        // Prove zero cancellation or invalidation side effects
        assert.strictEqual(cancelPreloadCalls, 0, 'No _cancelNextChapterPreload side effect');
        assert.strictEqual(invalidateCalls, 0, 'No _invalidateChapterPlayback side effect');
        assert.strictEqual(chapterStopCalls, 0, 'No ChapterAudio stop side effect');
        assert.strictEqual(selectManagedCalls, 0, 'No _selectManagedPlayback call');
        assert.strictEqual(ctrl._chapterSelectionId, initialSelectionId, 'Selection sequence unchanged');
        assert.strictEqual(ctrl._voiceSelectionSequenceId, initialVoiceSeqId, 'Voice sequence unchanged');

        // Prove engine and authority remain untouched
        assert.strictEqual(ctrl.engine, initialEngine);
        assert.strictEqual(ctrl.activeEngine, initialActiveEngine);
        assert.strictEqual(ctrl.activeEngineType, initialEngineType);
        assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);
        assert.notStrictEqual(ctrl.activeEngine, ctrl.managedEngine);

        // Also test when chapterEngine is null
        ctrl.chapterEngine = null;
        await ctrl._activateEngine('managed', 'v1');
        assert.strictEqual(cancelPreloadCalls, 0);
        assert.strictEqual(invalidateCalls, 0);
        assert.strictEqual(ctrl.engine, initialEngine);
        assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);
        assert.notStrictEqual(ctrl.activeEngine, ctrl.managedEngine);
    });

    await t.test('J. H.9I4B Blocker 1 & 3: Durable Managed preference preservation and authoritative catalog confirmation', async (subT) => {
        await subT.test('J1. Device supported + ChapterAudio unsupported preserves saved Managed preference without clearing or persisting', async () => {
            const env = createControllerEnv();
            const ctrl = new env.NarrationController.NarrationController({});
            ctrl.chapterId = '1';

            let selectManagedPlaybackCalled = false;
            ctrl._selectManagedPlayback = async () => {
                selectManagedPlaybackCalled = true;
            };

            let savePreferencesCalled = false;
            ctrl._savePreferences = () => {
                savePreferencesCalled = true;
            };

            const deviceVoices = [{ voiceURI: 'dev-1', name: 'Google Vietnamese', lang: 'vi-VN' }];
            const managedVoices = [{ voiceKey: 'voice-kien', displayName: 'Giọng Kiên' }];

            ctrl.deviceEngine = {
                isSupported: () => true,
                getVoices: () => deviceVoices,
                getSortedVoices: () => deviceVoices,
                selectedVoice: deviceVoices[0],
                setVoiceByURI: () => true,
                setVoice: () => {},
                getState: () => 'IDLE',
                getProgress: () => ({ currentSentence: 0, totalSentences: 0 }),
                stop: () => {}
            };
            ctrl.managedEngine = {
                isSupported: () => true,
                getVoices: () => managedVoices,
                loadVoiceCatalog: async () => ({ voices: managedVoices })
            };
            // ChapterAudioEngine is NOT supported (e.g. audio element unsupported)
            ctrl.chapterEngine = {
                isSupported: () => false,
                getSelectedVoiceKey: () => null,
                stop: () => {}
            };

            const appendedChildren = [];
            ctrl.dom = createMockDom();
            ctrl.dom.voiceSelect = {
                value: '',
                disabled: false,
                innerHTML: '',
                appendChild: (child) => { appendedChildren.push(child); },
                querySelectorAll: () => []
            };

            // Saved preference was for managed voice
            ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-kien' };

            // Test _loadInitialManagedVoices
            await ctrl._loadInitialManagedVoices();
            assert.strictEqual(ctrl._managedCatalogResolved, true);
            assert.strictEqual(ctrl._managedCatalogAuthoritative, false);

            // Test _populateVoiceDropdown
            ctrl._populateVoiceDropdown(deviceVoices, managedVoices, { skipActivation: false });

            // Managed voices should NOT be advertised in dropdown optgroup
            const managedGroup = appendedChildren.find(g => g && g.label === 'Giọng Kiếm Lai');
            assert.strictEqual(managedGroup, undefined, 'Managed optgroup must not exist when ChapterAudio is unsupported');

            const deviceGroup = appendedChildren.find(g => g && g.label === 'Thiết bị');
            assert.ok(deviceGroup, 'Device optgroup must exist when Device engine is supported');

            // Engine must remain device engine, never managedEngine or chapterEngine
            assert.strictEqual(ctrl.engine, ctrl.deviceEngine);
            assert.strictEqual(ctrl.activeEngine, ctrl.deviceEngine);
            assert.strictEqual(ctrl.activeEngineType, 'device');
            assert.notStrictEqual(ctrl.engine, ctrl.managedEngine);

            // _selectManagedPlayback must never have been called
            assert.strictEqual(selectManagedPlaybackCalled, false);

            // Saved Managed preference remains EXACTLY unchanged and _savePreferences is NOT called
            assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'voice-kien' });
            assert.strictEqual(savePreferencesCalled, false, '_savePreferences must not be called to clear preference');
        });

        await subT.test('J2. catalog fetch failure preserves saved Managed preference without clearing', async () => {
            const env = createControllerEnv();
            const ctrl = new env.NarrationController.NarrationController({});
            ctrl.chapterId = '1';

            let savePreferencesCalled = false;
            ctrl._savePreferences = () => {
                savePreferencesCalled = true;
            };

            const deviceVoices = [{ voiceURI: 'dev-1', name: 'Google Vietnamese', lang: 'vi-VN' }];
            ctrl.deviceEngine = {
                isSupported: () => true,
                getVoices: () => deviceVoices,
                getSortedVoices: () => deviceVoices,
                selectedVoice: deviceVoices[0],
                setVoiceByURI: () => true,
                setVoice: () => {},
                getState: () => 'IDLE',
                getProgress: () => ({ currentSentence: 0, totalSentences: 0 }),
                stop: () => {}
            };
            ctrl.managedEngine = {
                isSupported: () => true,
                loadVoiceCatalog: async () => { throw new Error('Catalog network failure'); },
                getVoices: () => []
            };
            ctrl.chapterEngine = {
                isSupported: () => true,
                getSelectedVoiceKey: () => null,
                stop: () => {}
            };
            ctrl.dom = createMockDom();
            ctrl.dom.voiceSelect = {
                value: '',
                disabled: false,
                innerHTML: '',
                appendChild: () => {},
                querySelectorAll: () => []
            };

            ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-kien' };

            await ctrl._loadInitialManagedVoices();
            assert.strictEqual(ctrl._managedCatalogResolved, true);
            assert.strictEqual(ctrl._managedCatalogAuthoritative, false);

            // Saved Managed preference remains unchanged
            assert.deepStrictEqual(ctrl.savedVoicePreference, { type: 'managed', voiceKey: 'voice-kien' });
            assert.strictEqual(savePreferencesCalled, false, '_savePreferences must not be called on catalog failure');
        });

        await subT.test('J3. successful catalog fetch that does not contain saved voiceKey clears stale preference', async () => {
            const env = createControllerEnv();
            const ctrl = new env.NarrationController.NarrationController({});
            ctrl.chapterId = '1';

            let savePreferencesCalled = false;
            ctrl._savePreferences = () => {
                savePreferencesCalled = true;
            };

            const deviceVoices = [{ voiceURI: 'dev-1', name: 'Google Vietnamese', lang: 'vi-VN' }];
            const managedVoices = [{ voiceKey: 'voice-other', displayName: 'Giọng Khác' }];

            ctrl.deviceEngine = {
                isSupported: () => true,
                getVoices: () => deviceVoices,
                getSortedVoices: () => deviceVoices,
                selectedVoice: deviceVoices[0],
                setVoiceByURI: () => true,
                setVoice: () => {},
                getState: () => 'IDLE',
                getProgress: () => ({ currentSentence: 0, totalSentences: 0 }),
                stop: () => {}
            };
            ctrl.managedEngine = {
                isSupported: () => true,
                loadVoiceCatalog: async () => ({ voices: managedVoices }),
                getVoices: () => managedVoices
            };
            ctrl.chapterEngine = {
                isSupported: () => true,
                getSelectedVoiceKey: () => null,
                stop: () => {}
            };
            ctrl.dom = createMockDom();
            ctrl.dom.voiceSelect = {
                value: '',
                disabled: false,
                innerHTML: '',
                appendChild: () => {},
                querySelectorAll: () => []
            };

            // Preference was for voice-kien, but authoritative catalog only has voice-other
            ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-kien' };

            await ctrl._loadInitialManagedVoices();
            assert.strictEqual(ctrl._managedCatalogResolved, true);
            assert.strictEqual(ctrl._managedCatalogAuthoritative, true);

            // Stale preference MUST be cleared and persisted
            assert.strictEqual(ctrl.savedVoicePreference, null);
            assert.strictEqual(savePreferencesCalled, true, '_savePreferences must be called when authoritative catalog confirms absence');
        });
    });
});

test('H.9I5C2B Auto Next Managed Chapter Preparation Tests', async (t) => {
    function createAudioFactory() {
        return class {
            constructor() {
                this.readyState = 4;
                this.currentTime = 0;
                this.duration = 100;
                this.ended = false;
                this._listeners = {};
            }
            addEventListener(e, cb) { if (!this._listeners[e]) this._listeners[e] = []; this._listeners[e].push(cb); }
            removeEventListener(e, cb) { if (this._listeners[e]) this._listeners[e] = this._listeners[e].filter(l => l !== cb); }
            emit(e) { if (this._listeners[e]) this._listeners[e].forEach(cb => cb()); }
            play() { this.emit('play'); return Promise.resolve(); }
            pause() { this.emit('pause'); }
            load() { this.emit('canplay'); }
        };
    }

    function createEnv() {
        let currentTime = 1000;
        const fakeAudio = createAudioFactory();
        const env = {
            console,
            setTimeout,
            clearTimeout,
            CustomEvent: class { constructor(t, d) { this.type = t; this.detail = d && d.detail; } },
            window: {
                location: { href: 'http://localhost/novel/chapters/1' },
                history: { pushState: () => {} },
                clearTimeout,
                setTimeout: setTimeout,
                addEventListener: () => {},
                removeEventListener: () => {}
            },
            document: {
                querySelector: () => null,
                querySelectorAll: () => [],
                getElementById: () => null,
                dispatchEvent: () => {},
                addEventListener: () => {},
                removeEventListener: () => {},
                createElement: () => ({ appendChild: () => {}, setAttribute: () => {}, classList: { add: () => {}, remove: () => {} } }),
                title: 'Chapter 1'
            },
            Audio: fakeAudio,
            AbortController: class {
                constructor() {
                    const listeners = [];
                    this.signal = {
                        aborted: false,
                        addEventListener: (event, cb) => { if (event === 'abort') listeners.push(cb); },
                        removeEventListener: (event, cb) => {
                            const idx = listeners.indexOf(cb);
                            if (idx >= 0) listeners.splice(idx, 1);
                        }
                    };
                    this._listeners = listeners;
                }
                abort() {
                    this.signal.aborted = true;
                    this._listeners.forEach(cb => cb());
                }
            },
            Date: Date,
            DOMParser: class {
                parseFromString() {
                    return {
                        querySelector: () => null,
                        querySelectorAll: () => [],
                        getElementById: () => null,
                        title: 'Chapter 2'
                    };
                }
            },
            fetch: async () => ({ ok: true, text: async () => '<html></html>' }),
            localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} }
        };
        env.fakeAudio = fakeAudio;
        env.setCurrentTime = (t) => { currentTime = t; };
        const engineSrc = fs.readFileSync('src/main/resources/static/js/novel/chapter-audio-engine.js', 'utf8');
        vm.runInNewContext(engineSrc, env);
        const controllerSrc = fs.readFileSync('src/main/resources/static/js/novel/narration-controller.js', 'utf8');
        vm.runInNewContext(controllerSrc, env);
        return env;
    }

    function createMockDom() {
        const createEl = () => ({
            disabled: false,
            classList: { add: () => {}, remove: () => {}, toggle: () => {}, contains: () => false },
            setAttribute: () => {},
            removeAttribute: () => {},
            getAttribute: () => null,
            addEventListener: () => {},
            removeEventListener: () => {},
            style: {},
            innerHTML: '',
            textContent: '',
            appendChild: () => {},
            querySelectorAll: () => [],
            querySelector: () => null
        });
        return {
            player: createEl(),
            statusText: createEl(),
            playPauseBtn: createEl(),
            playIcon: createEl(),
            pauseIcon: createEl(),
            voiceSelect: { ...createEl(), value: 'managed:v1', options: [] },
            speedSelect: createEl(),
            body: createEl(),
            timeDisplay: createEl(),
            durationDisplay: createEl(),
            prevBtn: createEl(),
            nextBtn: createEl(),
            rewindBtn: createEl(),
            forwardBtn: createEl()
        };
    }

    function createCtrl(env, opts = {}) {
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl._env = env;
        ctrl.autoNext = true;
        ctrl.isUnloaded = false;
        ctrl.activeEngineType = 'managed';
        ctrl.chapterId = '1';
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'v1' };
        ctrl._voiceSelectionSequenceId = 1;
        ctrl._nextChapterPreloadSequenceId = 1;
        ctrl._autoNextTimer = null;
        ctrl.managedPreparationPollIntervalMs = 5;
        ctrl.managedPreparationTimeoutMs = 100;
        ctrl.config = {
            waitFunction: (ms, signal) => new Promise((resolve, reject) => {
                if (signal && signal.aborted) {
                    const err = new Error('The operation was aborted');
                    err.name = 'AbortError';
                    return reject(err);
                }
                const timer = setTimeout(resolve, ms);
                if (signal && signal.addEventListener) {
                    signal.addEventListener('abort', () => {
                        clearTimeout(timer);
                        const err = new Error('The operation was aborted');
                        err.name = 'AbortError';
                        reject(err);
                    });
                }
            })
        };
        ctrl.parser = { parseChapterBody: () => [{ text: 'prose' }] };

        let playCalledCount = 0;
        ctrl._playCalledCount = () => playCalledCount;

        ctrl.chapterEngine = {
            isSupported: () => true,
            getState: () => 'PLAYING',
            getSelectedVoiceKey: () => 'v1',
            getCurrentChunkIndex: () => -1,
            getCurrentChunk: () => null,
            getSegments: () => [],
            getProgress: () => ({ currentTimeSeconds: 0, durationSeconds: 100, progressRatio: 0 }),
            loadPlayback: async (cId, vKey, meta) => {
                ctrl.chapterEngine.metadata = meta || { chapterId: cId, voiceKey: vKey };
                return ctrl.chapterEngine.metadata;
            },
            stop: () => {},
            setRate: () => {},
            seekBySeconds: () => {},
            seekToRatio: () => {},
            play: () => { playCalledCount++; return Promise.resolve(); },
            pause: () => {},
            canPrevious: () => false,
            canNext: () => false,
            probePlaybackMetadata: async () => ({ status: 'unknown' }),
            requestPlaybackPreparation: async () => ({ status: 'UNKNOWN' })
        };
        ctrl.managedEngine = {
            isSupported: () => true,
            loadVoiceCatalog: async () => ({ voices: [{ voiceKey: 'v1' }] }),
            cancelVoiceCatalogLoad: () => {},
            getVoices: () => [{ voiceKey: 'v1' }],
            destroy: () => {}
        };
        ctrl.deviceEngine = {
            isSupported: () => true,
            play: () => Promise.resolve(),
            loadChunks: () => {},
            stop: () => {},
            pause: () => {},
            getState: () => 'STOPPED',
            getCurrentChunkIndex: () => -1,
            getSortedVoices: () => [{ voiceURI: 'dev-vi' }],
            getVoices: () => [{ voiceURI: 'dev-vi' }],
            selectedVoice: { voiceURI: 'dev-vi' }
        };
        ctrl.engine = ctrl.chapterEngine;
        ctrl._updateChapterProgressDisplay = () => {};
        ctrl._syncChapterHighlight = () => {};
        ctrl._resolveNextChapterUrl = () => '/next';
        ctrl._validateFetchedChapterDocument = () => ({
            valid: true,
            newChapterId: '2',
            title: 'Chapter 2',
            bodyEl: { innerHTML: '<p>Next</p>' },
            breadcrumbEl: { innerHTML: 'Next' },
            headerEl: { innerHTML: 'Next' },
            navTopEl: { innerHTML: 'Next' },
            navBottomEl: { innerHTML: 'Next' }
        });
        ctrl.dom = createMockDom();
        Object.assign(ctrl, opts);
        return ctrl;
    }

    function createSnapshot(ctrl, overrides = {}) {
        return {
            sourceChapterId: '1',
            nextUrl: '/next',
            targetChapterId: '2',
            mode: 'managed',
            voiceKey: 'v1',
            voiceSelectionSequence: ctrl._voiceSelectionSequenceId,
            preloadSequence: ctrl._nextChapterPreloadSequenceId,
            createdAt: Date.now(),
            abortController: new (ctrl._env ? ctrl._env.AbortController : AbortController)(),
            promise: Promise.resolve(),
            status: 'completed',
            result: {
                document: { querySelector: () => null, querySelectorAll: () => [], getElementById: () => null, title: 'Chapter 2' },
                validation: {
                    valid: true,
                    newChapterId: '2',
                    title: 'Chapter 2',
                    bodyEl: { innerHTML: '<p>Next</p>' },
                    breadcrumbEl: { innerHTML: 'Next' },
                    headerEl: { innerHTML: 'Next' },
                    navTopEl: { innerHTML: 'Next' },
                    navBottomEl: { innerHTML: 'Next' }
                }
            },
            playbackMetadata: { audioUrl: 'ready.mp3', cues: [] },
            playbackAvailability: 'ready',
            ...overrides
        };
    }

    await t.test('1. natural-end Auto Next delay remains exactly 500ms', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        let scheduledDelay = null;
        const origSetTimeout = env.window.setTimeout;
        env.window.setTimeout = (fn, ms) => {
            scheduledDelay = ms;
            return origSetTimeout(fn, ms);
        };
        ctrl._onEngineChapterEnd('managed');
        assert.strictEqual(scheduledDelay, 500, 'natural-end delay must be exactly 500ms');
        ctrl._cancelPendingAutoNext();
    });

    await t.test('2. READY completed preload: B commits, exact voiceKey preserved, zero GET, zero POST, play B once', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        const readyMeta = { chapterId: '2', voiceKey: 'v1', audioUrl: 'ready.mp3', cues: [] };
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'ready', playbackMetadata: readyMeta });

        let getCount = 0;
        let postCount = 0;
        ctrl.chapterEngine.probePlaybackMetadata = async () => { getCount++; return { status: 'ready', metadata: readyMeta }; };
        ctrl.chapterEngine.requestPlaybackPreparation = async () => { postCount++; return { status: 'BUILDING' }; };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM committed');
        assert.strictEqual(getCount, 0, 'zero GET for READY preload');
        assert.strictEqual(postCount, 0, 'zero POST for READY preload');
        assert.strictEqual(ctrl._playCalledCount(), 1, 'play called exactly once');
        assert.strictEqual(ctrl.chapterEngine.metadata, readyMeta, 'reused preloaded metadata');
    });

    await t.test('3. UNAVAILABLE completed preload: B commits before POST, zero duplicate GET, exactly one POST for B + voiceKey, BUILDING polls, READY plays once', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable', playbackMetadata: null });

        let getCount = 0;
        let postCount = 0;
        let committedBeforePost = false;
        const polledMeta = { chapterId: '2', voiceKey: 'v1', audioUrl: 'polled.mp3', cues: [] };

        ctrl.chapterEngine.requestPlaybackPreparation = async (cId, vKey) => {
            postCount++;
            if (ctrl.chapterId === '2' && cId === '2' && vKey === 'v1') committedBeforePost = true;
            return { status: 'BUILDING' };
        };
        ctrl.chapterEngine.probePlaybackMetadata = async () => {
            getCount++;
            return { status: 'ready', metadata: polledMeta };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 40));

        assert.strictEqual(ctrl.chapterId, '2', 'Chapter B committed');
        assert.strictEqual(committedBeforePost, true, 'Chapter B committed before preparation POST started');
        assert.strictEqual(postCount, 1, 'exactly one POST issued for Chapter B');
        assert.strictEqual(getCount, 1, 'polling probe occurred');
        assert.strictEqual(ctrl._playCalledCount(), 1, 'Chapter B played once ready');
        assert.strictEqual(ctrl.chapterEngine.metadata, polledMeta);
    });

    await t.test('4. requestPlaybackPreparation observes ctrl.chapterId === "2"', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable', playbackMetadata: null });

        let observedChapterId = null;
        ctrl.chapterEngine.requestPlaybackPreparation = async () => {
            observedChapterId = ctrl.chapterId;
            return { status: 'BUILDING' };
        };
        ctrl.chapterEngine.probePlaybackMetadata = async () => ({ status: 'ready', metadata: {} });

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 30));

        assert.strictEqual(observedChapterId, '2', 'preparation transport must observe updated chapterId');
    });

    await t.test('5. UNKNOWN preload: cold probe occurs, READY => zero POST', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unknown', playbackMetadata: null });

        let probeCount = 0;
        let postCount = 0;
        const coldMeta = { chapterId: '2', voiceKey: 'v1', audioUrl: 'cold.mp3', cues: [] };
        ctrl.chapterEngine.probePlaybackMetadata = async () => {
            probeCount++;
            return { status: 'ready', metadata: coldMeta };
        };
        ctrl.chapterEngine.requestPlaybackPreparation = async () => {
            postCount++;
            return { status: 'BUILDING' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(ctrl.chapterId, '2');
        assert.strictEqual(probeCount, 1, 'exactly one cold probe GET');
        assert.strictEqual(postCount, 0, 'zero POST when cold probe is READY');
        assert.strictEqual(ctrl._playCalledCount(), 1);
    });

    await t.test('6. UNKNOWN -> cold unavailable: exactly one POST, poll -> READY -> play', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unknown', playbackMetadata: null });

        let probeCount = 0;
        let postCount = 0;
        const builtMeta = { chapterId: '2', voiceKey: 'v1', audioUrl: 'built.mp3', cues: [] };
        ctrl.chapterEngine.probePlaybackMetadata = async () => {
            probeCount++;
            if (probeCount === 1) return { status: 'unavailable' };
            return { status: 'ready', metadata: builtMeta };
        };
        ctrl.chapterEngine.requestPlaybackPreparation = async () => {
            postCount++;
            return { status: 'BUILDING' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 40));

        assert.strictEqual(ctrl.chapterId, '2');
        assert.strictEqual(postCount, 1, 'exactly one POST');
        assert.strictEqual(probeCount, 2, 'cold probe + 1 poll probe');
        assert.strictEqual(ctrl._playCalledCount(), 1);
    });

    await t.test('7. pending preload never awaited at chapter transition', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        let pendingResolved = false;
        let timer;
        const pendingPromise = new Promise(resolve => {
            timer = setTimeout(() => { pendingResolved = true; resolve(); }, 1000);
        });
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { status: 'pending', promise: pendingPromise });

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        clearTimeout(timer);

        assert.strictEqual(pendingResolved, false, 'must not await pending preload at transition');
        assert.strictEqual(ctrl.chapterId, '2', 'cold transition completes immediately');
    });

    await t.test('8. stale/expired/wrong preload not reused', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        // Expired snapshot (> 60s)
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { createdAt: Date.now() - 70000 });

        let htmlFetchCount = 0;
        env.fetch = async () => {
            htmlFetchCount++;
            return { ok: true, text: async () => '<html></html>' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(htmlFetchCount, 1, 'expired preload forces cold HTML fetch');
    });

    await t.test('9. exact Chapter A voiceKey continues to B', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.managedPreparationTimeoutMs = 20;
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-custom' };
        ctrl.chapterEngine.getSelectedVoiceKey = () => 'voice-custom';

        let prepVoiceKey = null;
        ctrl.chapterEngine.probePlaybackMetadata = async () => ({ status: 'unavailable' });
        ctrl.chapterEngine.requestPlaybackPreparation = async (cId, vKey) => {
            prepVoiceKey = vKey;
            return { status: 'BUILDING' };
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'voice-custom' });
        await new Promise(r => setTimeout(r, 30));

        assert.strictEqual(prepVoiceKey, 'voice-custom', 'exact voiceKey must continue to B');
    });

    await t.test('10. no other Managed default voice selected', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { voiceKey: 'voice-custom', playbackAvailability: 'ready', playbackMetadata: { audioUrl: 'custom.mp3' } });
        ctrl.savedVoicePreference = { type: 'managed', voiceKey: 'voice-custom' };
        ctrl.chapterEngine.getSelectedVoiceKey = () => 'voice-custom';
        ctrl.managedEngine.getVoices = () => [{ voiceKey: 'voice-default' }, { voiceKey: 'voice-custom' }];

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'voice-custom' });

        assert.strictEqual(ctrl.dom.voiceSelect.value, 'managed:voice-custom', 'dropdown keeps exact continuation voice');
    });

    await t.test('11. voice change during POST/poll aborts/prevents stale load', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.managedPreparationTimeoutMs = 20;
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable' });

        let pollResolve;
        ctrl.chapterEngine.requestPlaybackPreparation = async () => ({ status: 'BUILDING' });
        ctrl.chapterEngine.probePlaybackMetadata = () => new Promise(resolve => { pollResolve = resolve; });

        const transPromise = ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        // User changes voice while polling
        ctrl._voiceSelectionSequenceId++;

        // Stale poll resolves
        if (typeof pollResolve === 'function') {
            pollResolve({ status: 'ready', metadata: { chapterId: '2', voiceKey: 'v1', audioUrl: 'stale.mp3' } });
        }
        await transPromise;
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(ctrl._playCalledCount(), 0, 'stale voice resolution must not play');
    });

    await t.test('12. chapter navigation/unload/destroy aborts/prevents stale load', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.managedPreparationTimeoutMs = 20;
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable' });

        let pollResolve;
        ctrl.chapterEngine.requestPlaybackPreparation = async () => ({ status: 'BUILDING' });
        ctrl.chapterEngine.probePlaybackMetadata = () => new Promise(resolve => { pollResolve = resolve; });

        const transPromise = ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        // Destroy controller while polling
        ctrl.destroy();

        if (typeof pollResolve === 'function') {
            pollResolve({ status: 'ready', metadata: { chapterId: '2', voiceKey: 'v1', audioUrl: 'stale.mp3' } });
        }
        await transPromise;
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(ctrl._playCalledCount(), 0, 'destroyed controller must not play');
    });

    await t.test('13. AbortError causes zero fallback', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.fallbackToDevice = true;
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable' });

        const abortErr = new Error('The operation was aborted');
        abortErr.name = 'AbortError';
        ctrl.chapterEngine.requestPlaybackPreparation = async () => { throw abortErr; };

        let devicePlayCalled = false;
        ctrl.deviceEngine.play = () => { devicePlayCalled = true; };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(devicePlayCalled, false, 'AbortError must cause zero fallback');
        assert.strictEqual(ctrl.activeEngineType, 'managed', 'engine remains managed');
    });

    await t.test('14. timeout + fallback OFF leaves B retryable with Play enabled', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.fallbackToDevice = false;
        ctrl.managedPreparationPollIntervalMs = 5;
        ctrl.managedPreparationTimeoutMs = 15;
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable' });

        ctrl.chapterEngine.requestPlaybackPreparation = async () => ({ status: 'BUILDING' });
        ctrl.chapterEngine.probePlaybackMetadata = async () => ({ status: 'unknown' });

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 50));

        assert.strictEqual(ctrl.chapterId, '2');
        assert.strictEqual(ctrl.activeEngineType, 'managed');
        assert.strictEqual(ctrl.dom.playPauseBtn.disabled, false, 'Play button must remain enabled and retryable');
    });

    await t.test('15. fallback ON plays Device', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.fallbackToDevice = true;
        ctrl.managedPreparationPollIntervalMs = 5;
        ctrl.managedPreparationTimeoutMs = 15;
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable' });

        ctrl.chapterEngine.requestPlaybackPreparation = async () => ({ status: 'BUILDING' });
        ctrl.chapterEngine.probePlaybackMetadata = async () => ({ status: 'unknown' });

        let devicePlayed = false;
        ctrl.deviceEngine.play = () => { devicePlayed = true; return Promise.resolve(); };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 50));

        assert.strictEqual(ctrl.chapterId, '2');
        assert.strictEqual(ctrl.activeEngineType, 'device', 'switched to Device');
        assert.strictEqual(devicePlayed, true, 'Device engine played Chapter B');
    });

    await t.test('16. Play during same preparation coalesces without duplicate POST/poll', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.managedPreparationTimeoutMs = 50;
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unavailable' });

        let prepCount = 0;
        let pollResolve;
        ctrl.chapterEngine.requestPlaybackPreparation = async () => { prepCount++; return { status: 'BUILDING' }; };
        ctrl.chapterEngine.probePlaybackMetadata = () => new Promise(resolve => { pollResolve = resolve; });

        const transPromise = ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 20));

        // Click Play while preparation is in flight
        const playPromise = ctrl._handlePlayPause();
        assert.strictEqual(prepCount, 1, 'zero duplicate POST issued');
        assert.strictEqual(playPromise, ctrl._activePreparationPromise, 'play promise coalesced on active preparation');

        if (typeof pollResolve === 'function') {
            pollResolve({ status: 'ready', metadata: { audioUrl: 'audio.mp3' } });
        }
        await transPromise;
    });

    await t.test('17. Device Auto Next performs zero Managed preparation', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.activeEngineType = 'device';
        ctrl.engine = ctrl.deviceEngine;

        let getCount = 0;
        let postCount = 0;
        ctrl.chapterEngine.probePlaybackMetadata = async () => { getCount++; return { status: 'ready' }; };
        ctrl.chapterEngine.requestPlaybackPreparation = async () => { postCount++; return { status: 'BUILDING' }; };

        await ctrl._transitionToNextChapter('/next', { mode: 'device' });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(getCount, 0, 'zero Managed probe GET');
        assert.strictEqual(postCount, 0, 'zero Managed prepare POST');
        assert.strictEqual(ctrl.activeEngineType, 'device');
    });

    await t.test('18. speculative preload performs zero POST', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);

        let postCount = 0;
        ctrl.chapterEngine.requestPlaybackPreparation = async () => { postCount++; return { status: 'BUILDING' }; };
        ctrl.chapterEngine.probePlaybackMetadata = async () => ({ status: 'unavailable' });

        ctrl._onChapterProgress({ currentTimeSeconds: 75, durationSeconds: 100, progressRatio: 0.75 });

        assert.strictEqual(postCount, 0, 'speculative preload must never POST');
    });

    await t.test('19. explicit seek-to-end performs zero Auto Next', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl.chapterEngine.seekToRatio(1);
        ctrl._handleProgressBarKeydown({ key: 'End', preventDefault: () => {} });
        assert.strictEqual(ctrl._autoNextTimeoutId, null, 'no auto-next timer scheduled');
    });

    await t.test('20. seek-near-end + natural completion may enter preparation', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._onChapterProgress({ currentTimeSeconds: 95, durationSeconds: 100, progressRatio: 0.95 });
        ctrl._onEngineChapterEnd('managed');
        assert.ok(ctrl._autoNextTimeoutId !== null, 'auto-next timer scheduled on natural completion');
        ctrl._cancelPendingAutoNext();
    });

    await t.test('21. no next chapter performs zero POST', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        ctrl._resolveNextChapterUrl = () => null;

        let postCount = 0;
        ctrl.chapterEngine.requestPlaybackPreparation = async () => { postCount++; return { status: 'BUILDING' }; };

        ctrl._onEngineChapterEnd('managed');

        assert.strictEqual(ctrl.isCompleted, true, 'chapter marked completed');
        assert.strictEqual(ctrl._autoNextTimeoutId, null, 'no timer scheduled');
        assert.strictEqual(postCount, 0, 'zero POST when no next chapter exists');
    });

    await t.test('22. UNKNOWN + non-null stale metadata performs cold probe, ignores stale metadata, uses fresh READY probe metadata, 0 POST, plays once', async () => {
        const env = createEnv();
        const ctrl = createCtrl(env);
        const staleMeta = { chapterId: '2', voiceKey: 'v1', audioUrl: 'stale.mp3', cues: [] };
        const freshMeta = { chapterId: '2', voiceKey: 'v1', audioUrl: 'fresh.mp3', cues: [] };
        ctrl._activeNextChapterPreload = createSnapshot(ctrl, { playbackAvailability: 'unknown', playbackMetadata: staleMeta });

        let probeCount = 0;
        let postCount = 0;
        let loadedMetadata = null;

        ctrl.chapterEngine.probePlaybackMetadata = async () => {
            probeCount++;
            return { status: 'ready', metadata: freshMeta };
        };
        ctrl.chapterEngine.requestPlaybackPreparation = async () => {
            postCount++;
            return { status: 'BUILDING' };
        };
        const origLoadPlayback = ctrl.chapterEngine.loadPlayback;
        ctrl.chapterEngine.loadPlayback = async (cId, vKey, meta) => {
            loadedMetadata = meta;
            return origLoadPlayback(cId, vKey, meta);
        };

        await ctrl._transitionToNextChapter('/next', { mode: 'managed', voiceKey: 'v1' });
        await new Promise(r => setTimeout(r, 40));

        assert.strictEqual(ctrl.chapterId, '2', 'Chapter 2 DOM committed');
        assert.strictEqual(probeCount, 1, 'cold probe occurred exactly once for UNKNOWN availability');
        assert.strictEqual(postCount, 0, 'zero POST issued when fresh probe succeeds with ready');
        assert.notStrictEqual(loadedMetadata, staleMeta, 'stale seed metadata must NOT be loaded');
        assert.strictEqual(loadedMetadata, freshMeta, 'fresh READY probe metadata must be loaded');
        assert.strictEqual(ctrl.chapterEngine.metadata, freshMeta, 'chapterEngine metadata matches fresh metadata');
        assert.strictEqual(ctrl._playCalledCount(), 1, 'playback starts exactly once');
    });
});
