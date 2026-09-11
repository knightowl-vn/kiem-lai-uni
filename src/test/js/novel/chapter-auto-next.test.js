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




