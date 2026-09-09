'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ChapterAudioEngine } = require('../../../main/resources/static/js/novel/chapter-audio-engine.js');
const { NarrationController, HIGHLIGHT_CLASS } = require('../../../main/resources/static/js/novel/narration-controller.js');

class FakeAudio {
    constructor() {
        this.currentTime = 0;
        this.duration = 600;
        this.readyState = 1;
        this.ended = false;
        this.paused = true;
        this.playbackRate = 1;
        this.src = '';
        this.listeners = new Map();
    }

    addEventListener(name, callback) { this.listeners.set(name, callback); }
    removeEventListener(name) { this.listeners.delete(name); }
    removeAttribute(name) { if (name === 'src') this.src = ''; }
    load() {}
    pause() { this.paused = true; }
    async play() { this.paused = false; this.emit('playing'); }
    emit(name) { const callback = this.listeners.get(name); if (callback) callback(); }
}

function playbackMetadata() {
    return {
        chapterId: 'chapter-1', voiceKey: 'vi-fe-no', availability: 'READY', playable: true,
        freshness: 'CURRENT', audioUrl: '/media/chapter-audio', durationMillis: 600000,
        cues: [
            { cueOrdinal: 0, segmentId: 's1', startMillis: 0, endMillis: 120000 },
            { cueOrdinal: 1, segmentId: 's2', startMillis: 120000, endMillis: 300000 },
            { cueOrdinal: 2, segmentId: 's3', startMillis: 300000, endMillis: 600000 }
        ]
    };
}

async function loadedEngine(options = {}) {
    const audio = new FakeAudio();
    let audioCount = 0;
    const engine = new ChapterAudioEngine({
        fetchFunction: async () => ({ ok: true, json: async () => playbackMetadata() }),
        audioFactory: () => { audioCount += 1; return audio; },
        ...options
    });
    await engine.loadPlayback('chapter-1', 'vi-fe-no');
    return { engine, audio, audioCount: () => audioCount };
}

function fakeElement(text, segmentIds) {
    const classes = new Set();
    return {
        innerText: text,
        classList: {
            add: value => classes.add(value),
            remove: value => classes.delete(value),
            contains: value => classes.has(value)
        },
        getAttribute: name => name === 'data-narration-segment-ids' ? segmentIds : null
    };
}

function highlightController(elements, cue, state = 'PLAYING') {
    let timeSeconds = 0;
    let scrollCount = 0;
    const settingsPanel = {
        hidden: true,
        setAttribute: name => { if (name === 'hidden') settingsPanel.hidden = true; },
        removeAttribute: name => { if (name === 'hidden') settingsPanel.hidden = false; }
    };
    const settingsTrigger = fakeElement('', '');
    settingsTrigger.setAttribute = () => {};
    const chapterEngine = {
        getState: () => state,
        getCurrentChunk: () => cue,
        getProgress: () => ({ currentTimeSeconds: timeSeconds, durationSeconds: 10, progressRatio: timeSeconds / 10 })
    };
    const controller = Object.create(NarrationController.prototype);
    Object.assign(controller, {
        chapterEngine,
        engine: chapterEngine,
        followMode: true,
        isCompleted: false,
        isUnloaded: false,
        activeHighlightedElement: null,
        activeNarrationSegmentId: null,
        activeChapterHighlightedElements: new Set(),
        dom: {
            settingsPanel,
            settingsTrigger,
            followToggle: null,
            body: {
                querySelectorAll: selector => selector === '[data-narration-segment-ids]' ? elements : []
            }
        },
        _savePreferences: () => {},
        _isElementComfortablyVisible: () => false,
        _scrollElementIntoView: () => { scrollCount += 1; }
    });
    return {
        controller,
        setTime: value => { timeSeconds = value; },
        scrollCount: () => scrollCount,
        settingsPanel
    };
}

async function managedVoiceChangeFixture(useChapterPlayback) {
    const segmentProgressCalls = [];
    const chapterProgressCalls = [];
    const statuses = [];
    let selectionCalls = 0;
    const segments = [{ segmentId: 's1' }, { segmentId: 's2' }];
    const chapterProgress = { currentTimeSeconds: 60, durationSeconds: 600, progressRatio: 0.1 };
    const managedEngine = { stop: () => {}, cancel: () => {}, getSegments: () => segments };
    const chapterEngine = { getProgress: () => chapterProgress };
    const buttonClasses = new Set();
    const controller = Object.create(NarrationController.prototype);
    Object.assign(controller, {
        chapterId: 'chapter-1', managedEngine, chapterEngine, deviceEngine: null,
        engine: managedEngine, activeEngine: managedEngine, activeEngineType: 'managed',
        chunks: [], _voiceSelectionSequenceId: 0,
        dom: {
            voiceSelect: { value: 'managed:vi-fe-no' }, player: null, statusText: null,
            playIcon: null, pauseIcon: null,
            playPauseBtn: {
                disabled: false, setAttribute: () => {}, removeAttribute: () => {},
                classList: { add: value => buttonClasses.add(value), remove: value => buttonClasses.delete(value) }
            }
        },
        _invalidateChapterPlayback: () => {}, _cancelPendingAutoNext: () => {},
        _clearSavedResume: () => {}, _clearHighlight: () => {}, _savePreferences: () => {},
        _updateNavButtons: () => {},
        _updateProgressDisplay: (current, total) => segmentProgressCalls.push([current, total]),
        _updateChapterProgressDisplay: progress => chapterProgressCalls.push(progress),
        _setStatusMessage: status => statuses.push(status)
    });
    controller._selectManagedPlayback = async () => {
        selectionCalls += 1;
        controller.engine = controller.activeEngine = useChapterPlayback ? chapterEngine : managedEngine;
        return { segments };
    };

    await controller._handleVoiceChange();
    return { controller, segmentProgressCalls, chapterProgressCalls, statuses, selectionCalls, chapterProgress };
}

test('chapter progress uses absolute audio time and does not reset at cue boundaries', async () => {
    const progressEvents = [];
    const cueEvents = [];
    const { engine, audio, audioCount } = await loadedEngine({
        onProgress: progress => progressEvents.push(progress),
        onCueChange: (index, cue) => cueEvents.push([index, cue && cue.segmentId])
    });
    const source = audio.src;

    audio.currentTime = 60;
    audio.emit('timeupdate');
    assert.equal(progressEvents.at(-1).progressRatio, 0.1);

    audio.currentTime = 125;
    audio.emit('timeupdate');
    assert.ok(Math.abs(progressEvents.at(-1).progressRatio - (125 / 600)) < 0.000001);

    audio.currentTime = 250;
    audio.emit('timeupdate');
    assert.ok(Math.abs(progressEvents.at(-1).progressRatio - (250 / 600)) < 0.000001);
    assert.deepEqual(cueEvents.map(event => event[1]), ['s1', 's2']);
    assert.equal(audioCount(), 1);
    assert.equal(audio.src, source);
});

test('ratio, cue-boundary, and five-second seeks are absolute and preserve paused or playing state', async () => {
    const { engine, audio, audioCount } = await loadedEngine();
    const source = audio.src;
    engine.seekToChunk(1);
    assert.equal(audio.currentTime, 120);
    engine.nextChunk();
    assert.equal(audio.currentTime, 300);
    engine.previousChunk();
    assert.equal(audio.currentTime, 120);

    engine.pause();
    engine.seekToRatio(0.75);
    assert.equal(audio.currentTime, 450);
    assert.equal(engine.getState(), 'PAUSED');

    engine.seekBySeconds(5);
    assert.equal(audio.currentTime, 455);
    engine.seekBySeconds(-5);
    assert.equal(audio.currentTime, 450);
    assert.equal(engine.getState(), 'PAUSED');

    await engine.play();
    engine.seekToRatio(0.5);
    assert.equal(audio.currentTime, 300);
    assert.equal(engine.getCurrentChunk().segmentId, 's3');
    assert.equal(engine.getState(), 'PLAYING');
    assert.equal(audioCount(), 1);
    assert.equal(audio.src, source);
});

test('chapter end reports 100 percent and clears the active cue without replacing audio', async () => {
    const progressEvents = [];
    let ended = 0;
    const { engine, audio, audioCount } = await loadedEngine({
        onProgress: progress => progressEvents.push(progress),
        onChapterEnd: () => { ended += 1; }
    });
    await engine.play();
    const source = audio.src;
    audio.currentTime = 600;
    audio.ended = true;
    audio.emit('ended');

    assert.equal(progressEvents.at(-1).progressRatio, 1);
    assert.equal(engine.getCurrentChunk(), null);
    assert.equal(ended, 1);
    assert.equal(audioCount(), 1);
    assert.equal(audio.src, source);
});

test('one cue mapped to three blocks advances one highlight by text weight', () => {
    const blocks = [
        fakeElement('a'.repeat(20), 's1'),
        fakeElement('b'.repeat(50), 's1'),
        fakeElement('c'.repeat(30), 's1')
    ];
    const cue = { segmentId: 's1', startMillis: 0, endMillis: 10000 };
    const fixture = highlightController(blocks, cue);

    fixture.setTime(1);
    fixture.controller._syncChapterHighlight();
    assert.deepEqual(blocks.map(block => block.classList.contains(HIGHLIGHT_CLASS)), [true, false, false]);

    fixture.setTime(4);
    fixture.controller._syncChapterHighlight();
    assert.deepEqual(blocks.map(block => block.classList.contains(HIGHLIGHT_CLASS)), [false, true, false]);

    fixture.setTime(8);
    fixture.controller._syncChapterHighlight();
    assert.deepEqual(blocks.map(block => block.classList.contains(HIGHLIGHT_CLASS)), [false, false, true]);
    assert.equal(fixture.controller.activeChapterHighlightedElements.size, 1);
});

test('one mapped block stays highlighted across multiple segment cues without repeat scrolling', () => {
    const block = fakeElement('shared paragraph', 's1 s2 s3');
    const cue = { segmentId: 's1', startMillis: 0, endMillis: 10000 };
    const fixture = highlightController([block], cue);
    fixture.setTime(1);
    fixture.controller._syncChapterHighlight();
    fixture.controller._syncChapterHighlight();
    cue.segmentId = 's2';
    cue.startMillis = 10000;
    cue.endMillis = 20000;
    fixture.setTime(12);
    fixture.controller._syncChapterHighlight();

    assert.equal(block.classList.contains(HIGHLIGHT_CLASS), true);
    assert.equal(fixture.scrollCount(), 1);
});

test('settings suppress transition scrolling and close restores the unchanged block once', () => {
    const blocks = [fakeElement('a'.repeat(50), 's1'), fakeElement('b'.repeat(50), 's1')];
    const fixture = highlightController(blocks, { segmentId: 's1', startMillis: 0, endMillis: 10000 });
    fixture.setTime(1);
    fixture.controller._syncChapterHighlight();
    assert.equal(fixture.scrollCount(), 1);

    fixture.controller.openSettings();
    fixture.setTime(8);
    fixture.controller._syncChapterHighlight();
    assert.deepEqual(blocks.map(block => block.classList.contains(HIGHLIGHT_CLASS)), [false, true]);
    assert.equal(fixture.scrollCount(), 1);

    fixture.controller.closeSettings();
    assert.equal(fixture.settingsPanel.hidden, true);
    assert.equal(fixture.scrollCount(), 2);
    fixture.controller._syncChapterHighlight();
    assert.equal(fixture.scrollCount(), 2);
    assert.equal(fixture.controller.chapterEngine.getState(), 'PLAYING');
});

test('idle never highlights or scrolls, while paused preserves the current block', () => {
    const block = fakeElement('paragraph', 's1');
    const cue = { segmentId: 's1', startMillis: 0, endMillis: 10000 };
    const idle = highlightController([block], cue, 'IDLE');
    idle.controller._syncChapterHighlight();
    assert.equal(block.classList.contains(HIGHLIGHT_CLASS), false);
    assert.equal(idle.scrollCount(), 0);

    const paused = highlightController([block], cue, 'PAUSED');
    paused.setTime(5);
    paused.controller._syncChapterHighlight();
    paused.controller._syncChapterHighlight();
    assert.equal(block.classList.contains(HIGHLIGHT_CLASS), true);
    assert.equal(paused.scrollCount(), 1);
});

test('five-second seek while paused recomputes the weighted block and stays paused', async () => {
    let controller = null;
    const { engine } = await loadedEngine({ onProgress: progress => {
        if (controller) controller._onChapterProgress(progress);
    } });
    const blocks = [fakeElement('a'.repeat(50), 's1'), fakeElement('b'.repeat(50), 's1')];
    let scrollCount = 0;
    controller = Object.create(NarrationController.prototype);
    Object.assign(controller, {
        chapterEngine: engine, engine, followMode: true, isCompleted: false, isUnloaded: false,
        activeHighlightedElement: null, activeNarrationSegmentId: null, activeChapterHighlightedElements: new Set(),
        dom: { body: { querySelectorAll: () => blocks }, progressBar: null, progressFill: null,
            progressCurrent: null, progressTotal: null },
        isSettingsOpen: () => false, _isElementComfortablyVisible: () => false,
        _scrollElementIntoView: () => { scrollCount += 1; }
    });

    engine.pause();
    engine.seekBySeconds(58);
    assert.deepEqual(blocks.map(block => block.classList.contains(HIGHLIGHT_CLASS)), [true, false]);
    engine.seekBySeconds(5);
    assert.deepEqual(blocks.map(block => block.classList.contains(HIGHLIGHT_CLASS)), [false, true]);
    assert.equal(engine.getState(), 'PAUSED');
    assert.equal(scrollCount, 2);
});

test('follow off clears and follow on restores the current block', () => {
    const block = fakeElement('paragraph', 's1');
    const fixture = highlightController([block], { segmentId: 's1', startMillis: 0, endMillis: 10000 }, 'PAUSED');
    fixture.setTime(5);
    fixture.controller._syncChapterHighlight();
    fixture.controller.setFollowMode(false);
    assert.equal(block.classList.contains(HIGHLIGHT_CLASS), false);

    fixture.controller.setFollowMode(true);
    assert.equal(block.classList.contains(HIGHLIGHT_CLASS), true);
    assert.equal(fixture.scrollCount(), 2);
    assert.equal(fixture.controller.chapterEngine.getState(), 'PAUSED');
});

test('controller renders chapter-global percentage and seeks the existing engine by ratio', () => {
    const attributes = new Map();
    let soughtRatio = null;
    const chapterEngine = { seekToRatio: ratio => { soughtRatio = ratio; }, getCurrentChunkIndex: () => 0,
        getCurrentChunk: () => ({ segmentId: 's1', startMillis: 0, endMillis: 600000 }),
        getProgress: () => ({ currentTimeSeconds: 250, durationSeconds: 600, progressRatio: 250 / 600 }),
        getState: () => 'PAUSED', canPrevious: () => false, canNext: () => true };
    const controller = Object.create(NarrationController.prototype);
    Object.assign(controller, {
        chapterEngine, engine: chapterEngine, chunks: [{}], isCompleted: false, isAutoplayContinuation: false,
        followMode: false, activeChapterHighlightedElements: new Set(), activeHighlightedElement: null,
        dom: {
            progressBar: { setAttribute: (name, value) => attributes.set(name, value), getBoundingClientRect: () => ({ left: 0, width: 200 }) },
            progressFill: { style: {} }, progressCurrent: { textContent: '' }, progressTotal: { textContent: '' },
            prevBtn: null, nextBtn: null, body: null
        },
        _cancelPendingAutoNext: () => {}, _saveResumePosition: () => {}, _setStatusMessage: () => {}
    });

    controller._updateChapterProgressDisplay(chapterEngine.getProgress());
    assert.ok(Math.abs(parseFloat(controller.dom.progressFill.style.width) - 41.6666667) < 0.0001);
    assert.equal(controller.dom.progressCurrent.textContent, '4:10');
    assert.equal(controller.dom.progressTotal.textContent, '10:00');

    controller._handleProgressBarClick({ clientX: 150 });
    assert.equal(soughtRatio, 0.75);
    assert.equal(chapterEngine.getState(), 'PAUSED');
});

test('visible chapter times advance continuously and legacy progress clears chapter duration', () => {
    const attributes = new Map();
    const controller = Object.create(NarrationController.prototype);
    Object.assign(controller, {
        dom: {
            progressBar: { setAttribute: (name, value) => attributes.set(name, value) },
            progressFill: { style: {} }, progressCurrent: { textContent: 'stale' },
            progressTotal: { textContent: 'stale' }
        }
    });

    const visibleCurrentLabels = [];
    for (const currentTimeSeconds of [27, 28, 29]) {
        controller._updateChapterProgressDisplay({
            currentTimeSeconds,
            durationSeconds: 109.9,
            progressRatio: currentTimeSeconds / 109.9
        });
        visibleCurrentLabels.push(controller.dom.progressCurrent.textContent);
    }

    assert.deepEqual(visibleCurrentLabels, ['0:27', '0:28', '0:29']);
    assert.equal(controller.dom.progressTotal.textContent, '1:49');
    assert.ok(Math.abs(parseFloat(controller.dom.progressFill.style.width) - (29 / 109.9 * 100)) < 0.0001);
    assert.equal(attributes.get('aria-valuetext'), '0:29 / 1:49');

    controller._updateProgressDisplay(2, 3);
    assert.equal(controller.dom.progressCurrent.textContent, '2');
    assert.equal(controller.dom.progressTotal.textContent, '3');
    assert.equal(attributes.get('aria-valuetext'), '2 trên 3 câu');
});

test('Managed voice change keeps chapter playback progress and ready wording', async () => {
    const result = await managedVoiceChangeFixture(true);
    assert.equal(result.selectionCalls, 1);
    assert.deepEqual(result.segmentProgressCalls, [[0, 0]]);
    assert.deepEqual(result.chapterProgressCalls, [result.chapterProgress]);
    assert.equal(result.statuses.at(-1), 'Sẵn sàng phát âm thanh cả chương.');
    assert.equal(result.controller.engine, result.controller.chapterEngine);
});

test('Managed voice change keeps legacy segment-count finalization after fallback', async () => {
    const result = await managedVoiceChangeFixture(false);
    assert.equal(result.selectionCalls, 1);
    assert.deepEqual(result.segmentProgressCalls, [[0, 0], [0, 2]]);
    assert.deepEqual(result.chapterProgressCalls, []);
    assert.equal(result.statuses.at(-1), 'Sẵn sàng phát giọng đọc Kiếm Lai (2 đoạn).');
    assert.equal(result.controller.engine, result.controller.managedEngine);
});
