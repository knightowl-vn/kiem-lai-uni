const { describe, test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');
const fs = require('fs');
const vm = require('vm');

const RelativeTime = require(path.join(__dirname, '../../../main/resources/static/js/shared/relative-time.js'));

// ============================================================================
// Lightweight DOM Fixtures for Node Testing
// ============================================================================

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = { ...attributes };
        this.childNodes = [];
        this._textContent = '';
        this.dateTime = attributes.datetime || null;
    }

    get textContent() {
        if (this.childNodes.length === 0) {
            return this._textContent;
        }
        return this.childNodes.map(c => c.textContent || '').join('');
    }

    set textContent(val) {
        this.childNodes = [];
        this._textContent = String(val == null ? '' : val);
    }

    setAttribute(k, v) {
        this.attributes[k] = String(v);
        if (k === 'datetime') {
            this.dateTime = String(v);
        }
    }

    getAttribute(k) {
        return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null;
    }

    hasAttribute(k) {
        return Object.prototype.hasOwnProperty.call(this.attributes, k);
    }

    removeAttribute(k) {
        delete this.attributes[k];
    }

    querySelectorAll(selector) {
        const results = [];
        function traverse(node) {
            for (const child of node.childNodes) {
                if (selector === '[data-relative-time]' && child.hasAttribute && child.hasAttribute('data-relative-time')) {
                    results.push(child);
                }
                if (child.childNodes && child.childNodes.length > 0) {
                    traverse(child);
                }
            }
        }
        traverse(this);
        return results;
    }

    appendChild(child) {
        this.childNodes.push(child);
        return child;
    }
}

class FakeDocument extends FakeElement {
    constructor() {
        super('#document');
        this.readyState = 'complete';
        this.listeners = {};
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }

    dispatchEvent(event) {
        const type = typeof event === 'string' ? event : event.type;
        const list = this.listeners[type] || [];
        for (const fn of list) {
            fn.call(this, event);
        }
    }
}

class MockTimerApi {
    constructor() {
        this.timers = new Map();
        this.nextId = 1;
    }

    setInterval(fn, ms) {
        const id = this.nextId++;
        this.timers.set(id, { fn, ms });
        return id;
    }

    clearInterval(id) {
        this.timers.delete(id);
    }

    triggerAll() {
        for (const timer of this.timers.values()) {
            timer.fn();
        }
    }

    count() {
        return this.timers.size;
    }
}

// ============================================================================
// Test Suites
// ============================================================================

describe('UX-TIME-01B1 RelativeTime Pure Formatter Boundaries', () => {
    // Anchor reference time: 2026-09-22T10:00:00.000Z
    const BASE_NOW_MS = Date.UTC(2026, 8, 22, 10, 0, 0, 0);

    test('1. 0 ms age -> Vừa xong', () => {
        const ts = new Date(BASE_NOW_MS).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), 'Vừa xong');
    });

    test('2. 59,999 ms age -> Vừa xong', () => {
        const ts = new Date(BASE_NOW_MS - 59999).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), 'Vừa xong');
    });

    test('3. 60,000 ms age -> 1 phút trước', () => {
        const ts = new Date(BASE_NOW_MS - 60000).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '1 phút trước');
    });

    test('4. 3,599,999 ms age (59m59s) -> 59 phút trước', () => {
        const ts = new Date(BASE_NOW_MS - 3599999).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '59 phút trước');
    });

    test('5. 3,600,000 ms age (60m) -> 1 giờ trước', () => {
        const ts = new Date(BASE_NOW_MS - 3600000).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '1 giờ trước');
    });

    test('6. 23h59m age -> 23 giờ trước', () => {
        const diffMs = (23 * 60 + 59) * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '23 giờ trước');
    });

    test('7. 24h age -> 1 ngày trước', () => {
        const diffMs = 24 * 60 * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '1 ngày trước');
    });

    test('8. 6d23h age -> 6 ngày trước', () => {
        const diffMs = (6 * 24 + 23) * 60 * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '6 ngày trước');
    });

    test('9. 7d age -> 1 tuần trước', () => {
        const diffMs = 7 * 24 * 60 * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '1 tuần trước');
    });

    test('10. 13d age -> 1 tuần trước', () => {
        const diffMs = 13 * 24 * 60 * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '1 tuần trước');
    });

    test('11. 14d age -> 2 tuần trước', () => {
        const diffMs = 14 * 24 * 60 * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '2 tuần trước');
    });

    test('12. 29d age -> 4 tuần trước', () => {
        const diffMs = 29 * 24 * 60 * 60 * 1000;
        const ts = new Date(BASE_NOW_MS - diffMs).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), '4 tuần trước');
    });

    test('13. 30d age -> absolute datetime', () => {
        const diffMs = 30 * 24 * 60 * 60 * 1000;
        const date = new Date(BASE_NOW_MS - diffMs);
        const ts = date.toISOString();
        const expectedAbsolute = RelativeTime.formatAbsolute(date);
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), expectedAbsolute);
    });

    test('14. old timestamp (> 1 year) -> absolute datetime', () => {
        const date = new Date(Date.UTC(2024, 0, 15, 8, 30, 0));
        const ts = date.toISOString();
        const expectedAbsolute = RelativeTime.formatAbsolute(date);
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), expectedAbsolute);
    });

    test('15. timestamp 30 seconds in future -> Vừa xong (clock skew tolerance)', () => {
        const ts = new Date(BASE_NOW_MS + 30000).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), 'Vừa xong');
    });

    test('16. timestamp exactly 60 seconds in future -> Vừa xong (edge tolerance)', () => {
        const ts = new Date(BASE_NOW_MS + 60000).toISOString();
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), 'Vừa xong');
    });

    test('17. timestamp >60 seconds in future -> absolute datetime', () => {
        const date = new Date(BASE_NOW_MS + 60001);
        const ts = date.toISOString();
        const expectedAbsolute = RelativeTime.formatAbsolute(date);
        assert.strictEqual(RelativeTime.format(ts, BASE_NOW_MS), expectedAbsolute);
    });

    test('18. invalid string -> empty string', () => {
        assert.strictEqual(RelativeTime.format('invalid-iso-date', BASE_NOW_MS), '');
    });

    test('19. null -> empty string', () => {
        assert.strictEqual(RelativeTime.format(null, BASE_NOW_MS), '');
    });

    test('20. empty string -> empty string', () => {
        assert.strictEqual(RelativeTime.format('', BASE_NOW_MS), '');
        assert.strictEqual(RelativeTime.format('   ', BASE_NOW_MS), '');
    });

    test('finite-but-invalid numeric Date range returns empty string without crashing', () => {
        const fixedNow = Date.UTC(2026, 8, 22, 10, 0, 0);
        assert.strictEqual(RelativeTime.formatAbsolute(1e20), '');
        assert.strictEqual(RelativeTime.format(1e20, fixedNow), '');
        assert.strictEqual(RelativeTime.format(-1e20, fixedNow), '');
        assert.strictEqual(RelativeTime.formatAbsolute(-1e20), '');
    });

    test('formatAbsolute: formats local Date components to zero-padded dd/MM/yyyy HH:mm', () => {
        const date = new Date(2026, 0, 5, 9, 7, 0);
        assert.strictEqual(RelativeTime.formatAbsolute(date), '05/01/2026 09:07');
    });

    test('public export API surface: exposes only authoritative methods and no private internals', () => {
        assert.strictEqual(typeof RelativeTime.format, 'function');
        assert.strictEqual(typeof RelativeTime.formatAbsolute, 'function');
        assert.strictEqual(typeof RelativeTime.formatElement, 'function');
        assert.strictEqual(typeof RelativeTime.formatTree, 'function');
        assert.strictEqual(typeof RelativeTime.init, 'function');
        assert.strictEqual(typeof RelativeTime.destroy, 'function');
        assert.strictEqual(RelativeTime._parseTimestamp, undefined);
        assert.strictEqual(RelativeTime.parseTimestamp, undefined);
    });
});

describe('UX-TIME-01B1 RelativeTime DOM Contract', () => {
    const BASE_NOW_MS = Date.UTC(2026, 8, 22, 10, 0, 0, 0);

    test('formatElement: updates visible text, preserves datetime, adds exact title & aria-label', () => {
        const iso = new Date(BASE_NOW_MS - 3 * 3600000).toISOString(); // 3 hours ago
        const el = new FakeElement('time', { datetime: iso, class: 'kl-comment__time' });

        const success = RelativeTime.formatElement(el, BASE_NOW_MS);
        assert.strictEqual(success, true);
        assert.strictEqual(el.textContent, '3 giờ trước');
        assert.strictEqual(el.getAttribute('datetime'), iso);
        assert.strictEqual(el.hasAttribute('data-relative-time'), true);

        const exactTime = RelativeTime.formatAbsolute(new Date(iso));
        assert.strictEqual(el.getAttribute('title'), exactTime);
        assert.strictEqual(el.getAttribute('aria-label'), '3 giờ trước, thời gian chính xác ' + exactTime);
        assert.strictEqual(el.getAttribute('class'), 'kl-comment__time');
    });

    test('formatElement: timestamps >= 30d render concise exact-time aria-label', () => {
        const date = new Date(BASE_NOW_MS - 45 * 24 * 3600000); // 45 days ago
        const iso = date.toISOString();
        const el = new FakeElement('time', { datetime: iso });

        const success = RelativeTime.formatElement(el, BASE_NOW_MS);
        assert.strictEqual(success, true);
        const exactTime = RelativeTime.formatAbsolute(date);
        assert.strictEqual(el.textContent, exactTime);
        assert.strictEqual(el.getAttribute('title'), exactTime);
        assert.strictEqual(el.getAttribute('aria-label'), 'Thời gian chính xác ' + exactTime);
    });

    test('formatElement: fails safely on invalid or missing datetime without crashing', () => {
        const elMissing = new FakeElement('time');
        assert.strictEqual(RelativeTime.formatElement(elMissing, BASE_NOW_MS), false);

        const elInvalid = new FakeElement('time', { datetime: 'not-a-date' });
        assert.strictEqual(RelativeTime.formatElement(elInvalid, BASE_NOW_MS), false);

        assert.strictEqual(RelativeTime.formatElement(null, BASE_NOW_MS), false);
    });

    test('formatTree: formats all [data-relative-time] elements within scoped root', () => {
        const doc = new FakeDocument();
        const container = doc.createElement('div');
        doc.appendChild(container);

        const t1 = doc.createElement('time');
        t1.setAttribute('datetime', new Date(BASE_NOW_MS - 120000).toISOString()); // 2 mins ago
        t1.setAttribute('data-relative-time', '');
        container.appendChild(t1);

        const t2 = doc.createElement('time');
        t2.setAttribute('datetime', new Date(BASE_NOW_MS - 86400000).toISOString()); // 1 day ago
        t2.setAttribute('data-relative-time', '');
        container.appendChild(t2);

        const tStatic = doc.createElement('time');
        tStatic.setAttribute('datetime', new Date(BASE_NOW_MS).toISOString());
        // no data-relative-time
        container.appendChild(tStatic);

        const count = RelativeTime.formatTree(container, BASE_NOW_MS);
        assert.strictEqual(count, 2);
        assert.strictEqual(t1.textContent, '2 phút trước');
        assert.strictEqual(t2.textContent, '1 ngày trước');
        assert.strictEqual(tStatic.textContent, '');
    });

    test('formatTree: handles zero matching elements safely', () => {
        const doc = new FakeDocument();
        assert.strictEqual(RelativeTime.formatTree(doc, BASE_NOW_MS), 0);
        assert.strictEqual(RelativeTime.formatTree(null, BASE_NOW_MS), 0);
    });
});

describe('UX-TIME-01B1 RelativeTime Timer & Lifecycle', () => {
    let mockTimerApi;
    let fakeDoc;
    let mockClock;
    let currentNow;

    beforeEach(() => {
        mockTimerApi = new MockTimerApi();
        fakeDoc = new FakeDocument();
        currentNow = Date.UTC(2026, 8, 22, 10, 0, 0, 0);
        mockClock = () => currentNow;
    });

    afterEach(() => {
        RelativeTime.destroy({ timerApi: mockTimerApi });
    });

    test('init creates exactly one timer; second init does not create duplicate timer', () => {
        RelativeTime.init(fakeDoc, {
            timerApi: mockTimerApi,
            clock: mockClock,
            intervalMs: 60000
        });
        assert.strictEqual(mockTimerApi.count(), 1);

        // Call init again with same or different doc
        RelativeTime.init(fakeDoc, {
            timerApi: mockTimerApi,
            clock: mockClock,
            intervalMs: 60000
        });
        assert.strictEqual(mockTimerApi.count(), 1, 'Multiple init calls must NOT create duplicate timers');
    });

    test('refresh tick transitions timestamp across threshold boundary', () => {
        const timeEl = fakeDoc.createElement('time');
        // Created 50s before base now -> initial age is 50s ("Vừa xong")
        timeEl.setAttribute('datetime', new Date(currentNow - 50000).toISOString());
        timeEl.setAttribute('data-relative-time', '');
        fakeDoc.appendChild(timeEl);

        RelativeTime.init(fakeDoc, {
            timerApi: mockTimerApi,
            clock: mockClock,
            intervalMs: 60000
        });

        assert.strictEqual(timeEl.textContent, 'Vừa xong');

        // Advance simulated time by 20s (total age now 70s -> "1 phút trước")
        currentNow += 20000;
        mockTimerApi.triggerAll();

        assert.strictEqual(timeEl.textContent, '1 phút trước');
    });

    test('destroy clears active timer and resets state cleanly', () => {
        RelativeTime.init(fakeDoc, {
            timerApi: mockTimerApi,
            clock: mockClock
        });
        assert.strictEqual(mockTimerApi.count(), 1);

        RelativeTime.destroy({ timerApi: mockTimerApi });
        assert.strictEqual(mockTimerApi.count(), 0);
    });

    test('init: repeated init with different timerApi does not corrupt active timer ownership', () => {
        const timerApiA = new MockTimerApi();
        const timerApiB = new MockTimerApi();
        const docA = new FakeDocument();
        const docB = new FakeDocument();

        RelativeTime.init(docA, { timerApi: timerApiA });
        assert.strictEqual(timerApiA.count(), 1);

        RelativeTime.init(docB, { timerApi: timerApiB });
        assert.strictEqual(timerApiA.count(), 1);
        assert.strictEqual(timerApiB.count(), 0);

        RelativeTime.destroy();
        assert.strictEqual(timerApiA.count(), 0);
    });

    test('dynamic post-init insertion: newly appended [data-relative-time] element is formatted on tick', () => {
        RelativeTime.init(fakeDoc, {
            timerApi: mockTimerApi,
            clock: mockClock,
            intervalMs: 60000
        });

        // Initially no element. Post-init, dynamically insert an element into fakeDoc
        const dynamicEl = fakeDoc.createElement('time');
        dynamicEl.setAttribute('datetime', new Date(currentNow - 120000).toISOString()); // 2 mins ago
        dynamicEl.setAttribute('data-relative-time', '');
        fakeDoc.appendChild(dynamicEl);

        assert.strictEqual(dynamicEl.textContent, '');

        // Trigger tick
        mockTimerApi.triggerAll();

        assert.strictEqual(dynamicEl.textContent, '2 phút trước');
    });
});

describe('UX-TIME-01B1 RelativeTime Browser Auto-Bootstrap (node:vm)', () => {
    const relativeTimeSource = fs.readFileSync(
        path.join(__dirname, '../../../main/resources/static/js/shared/relative-time.js'),
        'utf8'
    );

    const FIXED_NOW_MS = Date.UTC(2026, 8, 22, 10, 0, 0);

    function createDeterministicDate(fixedNowMs) {
        return class FixedDate extends Date {
            constructor(...args) {
                if (args.length === 0) {
                    super(fixedNowMs);
                } else {
                    super(...args);
                }
            }

            static now() {
                return fixedNowMs;
            }
        };
    }

    test('auto-init runs immediately when document.readyState is "complete"', () => {
        let intervalCallCount = 0;

        const fakeWindow = {
            setInterval(fn, ms) {
                intervalCallCount++;
                return 101;
            },
            clearInterval(id) {
                intervalCallCount--;
            }
        };

        const eventListeners = {};
        const elementsInDoc = [];
        const fakeDoc = {
            readyState: 'complete',
            addEventListener(ev, fn) {
                if (!eventListeners[ev]) eventListeners[ev] = [];
                eventListeners[ev].push(fn);
            },
            querySelectorAll(selector) {
                if (selector === '[data-relative-time]') {
                    return elementsInDoc;
                }
                return [];
            }
        };

        const DeterministicDate = createDeterministicDate(FIXED_NOW_MS);
        // 2 minutes before fixed reference now
        const twoMinutesAgoIso = new DeterministicDate(FIXED_NOW_MS - 2 * 60 * 1000).toISOString();
        const timeEl = new FakeElement('time', {
            datetime: twoMinutesAgoIso,
            'data-relative-time': ''
        });
        elementsInDoc.push(timeEl);

        const sandbox = {
            window: fakeWindow,
            document: fakeDoc,
            globalThis: fakeWindow,
            Date: DeterministicDate,
            Math: Math,
            String: String,
            Number: Number,
            isNaN: isNaN,
            isFinite: isFinite
        };
        fakeWindow.document = fakeDoc;

        vm.createContext(sandbox);
        vm.runInContext(relativeTimeSource, sandbox);

        const RelativeTimeInstance = fakeWindow.RelativeTime || sandbox.RelativeTime;

        // Verify RelativeTime was placed on root/window
        assert.ok(RelativeTimeInstance);
        assert.strictEqual(typeof RelativeTimeInstance.init, 'function');

        // Verify auto-init immediately formatted the DOM element with deterministic string and started exactly 1 timer
        assert.strictEqual(intervalCallCount, 1);
        assert.strictEqual(timeEl.textContent, '2 phút trước');

        // Cleanup
        RelativeTimeInstance.destroy();
        assert.strictEqual(intervalCallCount, 0);
    });

    test('auto-init registers DOMContentLoaded listener when document.readyState is "loading"', () => {
        let intervalCallCount = 0;

        const fakeWindow = {
            setInterval(fn, ms) {
                intervalCallCount++;
                return 202;
            },
            clearInterval(id) {
                intervalCallCount--;
            }
        };

        const eventListeners = {};
        const elementsInDoc = [];
        const fakeDoc = {
            readyState: 'loading',
            addEventListener(ev, fn) {
                if (!eventListeners[ev]) eventListeners[ev] = [];
                eventListeners[ev].push(fn);
            },
            querySelectorAll(selector) {
                if (selector === '[data-relative-time]') {
                    return elementsInDoc;
                }
                return [];
            }
        };

        const DeterministicDate = createDeterministicDate(FIXED_NOW_MS);
        // 2 minutes before fixed reference now
        const twoMinutesAgoIso = new DeterministicDate(FIXED_NOW_MS - 2 * 60 * 1000).toISOString();
        const timeEl = new FakeElement('time', {
            datetime: twoMinutesAgoIso,
            'data-relative-time': ''
        });
        elementsInDoc.push(timeEl);

        const sandbox = {
            window: fakeWindow,
            document: fakeDoc,
            globalThis: fakeWindow,
            Date: DeterministicDate,
            Math: Math,
            String: String,
            Number: Number,
            isNaN: isNaN,
            isFinite: isFinite
        };
        fakeWindow.document = fakeDoc;

        vm.createContext(sandbox);
        vm.runInContext(relativeTimeSource, sandbox);

        const RelativeTimeInstance = fakeWindow.RelativeTime || sandbox.RelativeTime;
        assert.ok(RelativeTimeInstance);

        // Before event: Timer should NOT have started yet while readyState is loading, zero timers, unformatted
        assert.strictEqual(intervalCallCount, 0);
        assert.strictEqual(timeEl.textContent, '');
        assert.ok(eventListeners['DOMContentLoaded']);
        assert.strictEqual(eventListeners['DOMContentLoaded'].length, 1);

        // Trigger DOMContentLoaded
        fakeDoc.readyState = 'interactive';
        for (const listener of eventListeners['DOMContentLoaded']) {
            listener();
        }

        // After event: Exactly 1 timer started and DOM formatted with deterministic text
        assert.strictEqual(intervalCallCount, 1);
        assert.strictEqual(timeEl.textContent, '2 phút trước');

        // Cleanup
        RelativeTimeInstance.destroy();
        assert.strictEqual(intervalCallCount, 0);
    });

    test('CommonJS module environment does NOT auto-initialize or start timers', () => {
        let intervalCallCount = 0;
        const fakeWindow = {
            setInterval(fn, ms) {
                intervalCallCount++;
                return 303;
            },
            clearInterval(id) {
                intervalCallCount--;
            }
        };

        const eventListeners = {};
        const elementsInDoc = [];
        const fakeDoc = {
            readyState: 'complete',
            addEventListener(ev, fn) {
                if (!eventListeners[ev]) eventListeners[ev] = [];
                eventListeners[ev].push(fn);
            },
            querySelectorAll(selector) {
                if (selector === '[data-relative-time]') {
                    return elementsInDoc;
                }
                return [];
            }
        };

        const timeEl = new FakeElement('time', {
            datetime: '2026-09-22T09:58:00Z',
            'data-relative-time': ''
        });
        elementsInDoc.push(timeEl);

        const mockModule = { exports: {} };

        const sandbox = {
            module: mockModule,
            exports: mockModule.exports,
            window: fakeWindow,
            document: fakeDoc,
            globalThis: fakeWindow,
            Date: Date,
            Math: Math,
            String: String,
            Number: Number,
            isNaN: isNaN,
            isFinite: isFinite
        };
        fakeWindow.document = fakeDoc;

        vm.createContext(sandbox);
        vm.runInContext(relativeTimeSource, sandbox);

        // 1. module.exports receives RelativeTime public API
        assert.ok(mockModule.exports);
        assert.strictEqual(typeof mockModule.exports.init, 'function');
        assert.strictEqual(typeof mockModule.exports.format, 'function');
        assert.strictEqual(typeof mockModule.exports.formatAbsolute, 'function');
        assert.strictEqual(mockModule.exports._parseTimestamp, undefined);

        // 2. Window/root did NOT receive RelativeTime attached directly as global
        assert.strictEqual(fakeWindow.RelativeTime, undefined);

        // 3. Script load did NOT start any interval timer
        assert.strictEqual(intervalCallCount, 0);

        // 4. Script load did NOT register any DOMContentLoaded listener
        assert.strictEqual(eventListeners['DOMContentLoaded'], undefined);

        // 5. Elements in document were NOT formatted
        assert.strictEqual(timeEl.textContent, '');
    });
});
