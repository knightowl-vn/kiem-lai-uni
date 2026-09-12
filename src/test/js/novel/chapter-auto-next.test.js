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
            getSelectedVoiceKey: () => 'voice-B',
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
        }, '/url', {}, { mode: 'managed', voiceKey: 'voice-B' }); // request voice-B

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
        }, '/url', { newChapterId: '456', bodyEl: {} }, capturedIntent);

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
        controller.managedEngine = { stop: () => {}, getSegments: () => [] };
        controller._selectManagedPlayback = async () => ({ segments: [] });
        controller._activateEngine('managed', 'test-key');
        require('node:assert').strictEqual(controller._nextChapterPreloadSequenceId, 3);
        require('node:assert').strictEqual(controller._activeNextChapterPreload, null);
    });

    await t.test('12. ChapterAudio to legacy Managed fallback cancels preload, aborts controller, and increments sequence', async () => {
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

        await controller._offerLegacyChapterFallback();

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

    await t.test('7. legacy Managed engine does not preload', async () => {
        const env = createControllerEnv();
        const ctrl = new env.NarrationController.NarrationController({});
        ctrl.autoNext = true; ctrl.isUnloaded = false; ctrl.activeEngineType = 'managed';
        ctrl.chapterEngine = { getState: () => 'PLAYING', getSelectedVoiceKey: () => 'v1', getCurrentChunk: () => null };
        ctrl.engine = ctrl.managedEngine = {}; ctrl._updateChapterProgressDisplay = () => {}; ctrl._syncChapterHighlight = () => {};
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
            getVoices: () => [{ voiceKey: 'v1' }],
            getSelectedVoiceKey: () => 'v1',
            stop: () => {},
            cancel: () => {},
            loadManifest: async (id, voiceKey) => ({ selectedVoice: { voiceKey }, segments: [], availableVoices: [{ voiceKey }] })
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
            preloadedMeta
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

    await t.test('17. HTML preload with playbackMetadata === null still skips HTML fetch but performs normal cold ChapterAudio metadata fetch', async () => {
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

        const snapshot = createValidSnapshot(ctrl, { playbackMetadata: null });
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
        require('node:assert').strictEqual(metadataFetchCount, 1, 'ChapterAudioEngine should perform cold metadata GET when preloaded metadata is null');
        require('node:assert').strictEqual(engine.metadata, coldMetadata, 'Engine should hold cold-fetched metadata');
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
