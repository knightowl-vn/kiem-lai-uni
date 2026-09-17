const { test, describe } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const {
    READER_BODY_SELECTOR,
    BLOCK_KEY_ATTR,
    THREAD_COUNT_ATTR,
    INDICATOR_CLASS,
    buildIndicatorsUrl,
    clearIndicators,
    applyIndicators,
    loadChapterIndicators,
    initChapterCommentIndicators,
    bindChapterEvents
} = require(path.join(__dirname, '../../../main/resources/static/js/novel/chapter-comment-indicators.js'));

// ============================================================================
// Lightweight DOM Test Fixture Helpers
// ============================================================================

class FakeNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
        this.parentElement = null;
    }
}

class FakeTextNode extends FakeNode {
    constructor(text) {
        super(3);
        this.nodeValue = text;
        this.data = text;
    }

    get textContent() {
        return this.nodeValue;
    }

    set textContent(val) {
        this.nodeValue = String(val);
        this.data = this.nodeValue;
    }
}

class FakeClassList {
    constructor(element) {
        this.element = element;
        this.classes = new Set();
    }

    add(...names) {
        for (const name of names) {
            this.classes.add(name);
        }
        this._sync();
    }

    remove(...names) {
        for (const name of names) {
            this.classes.delete(name);
        }
        this._sync();
    }

    contains(name) {
        return this.classes.has(name);
    }

    _sync() {
        if (this.classes.size > 0) {
            this.element.attributes['class'] = Array.from(this.classes).join(' ');
        } else {
            delete this.element.attributes['class'];
        }
    }
}

class FakeElement extends FakeNode {
    constructor(tagName, attributes = {}) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.classList = new FakeClassList(this);

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        this.childNodes.push(child);
        return child;
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes = new Set(String(value).split(/\s+/).filter(Boolean));
        }
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
    }

    get dataset() {
        const handler = {
            get: (target, prop) => {
                const attrName = 'data-' + String(prop).replace(/([A-Z])/g, '-$1').toLowerCase();
                return this.getAttribute(attrName);
            },
            set: (target, prop, val) => {
                const attrName = 'data-' + String(prop).replace(/([A-Z])/g, '-$1').toLowerCase();
                this.setAttribute(attrName, val);
                return true;
            }
        };
        return new Proxy({}, handler);
    }

    get textContent() {
        let text = '';
        function collect(node) {
            if (node.nodeType === 3) {
                text += node.nodeValue;
            } else if (node.childNodes) {
                for (const c of node.childNodes) {
                    collect(c);
                }
            }
        }
        collect(this);
        return text;
    }

    matches(selector) {
        if (selector === READER_BODY_SELECTOR) {
            return this.classList.contains('novel-reader-chapter-body');
        }
        if (selector.startsWith('.')) {
            return this.classList.contains(selector.slice(1));
        }
        if (selector === '[' + BLOCK_KEY_ATTR + ']') {
            return this.hasAttribute(BLOCK_KEY_ATTR);
        }
        if (selector === '[' + THREAD_COUNT_ATTR + ']') {
            return this.hasAttribute(THREAD_COUNT_ATTR);
        }
        return false;
    }

    querySelectorAll(selector) {
        const results = [];
        function walk(node) {
            if (!node || !node.childNodes) return;
            for (const child of node.childNodes) {
                if (child.nodeType === 1) {
                    if (selector.includes(',')) {
                        const parts = selector.split(',').map(s => s.trim());
                        if (parts.some(p => child.matches(p))) {
                            results.push(child);
                        }
                    } else if (child.matches(selector)) {
                        results.push(child);
                    }
                    walk(child);
                }
            }
        }
        walk(this);
        return results;
    }

    querySelector(selector) {
        const all = this.querySelectorAll(selector);
        return all.length > 0 ? all[0] : null;
    }
}

class FakeDocument {
    constructor(body) {
        this.body = body;
        this.readyState = 'complete';
        this.listeners = {};
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }

    dispatchEvent(event) {
        const type = typeof event === 'string' ? event : event.type;
        const handlers = this.listeners[type] || [];
        for (const fn of handlers) {
            fn(event);
        }
    }

    querySelectorAll(selector) {
        const results = [];
        if (this.body.matches(selector)) {
            results.push(this.body);
        }
        return results.concat(this.body.querySelectorAll(selector));
    }

    querySelector(selector) {
        const list = this.querySelectorAll(selector);
        return list.length > 0 ? list[0] : null;
    }
}

function createBlock(tagName, blockKey, text) {
    const el = new FakeElement(tagName, { [BLOCK_KEY_ATTR]: blockKey });
    el.appendChild(new FakeTextNode(text));
    return el;
}

// ============================================================================
// Test Suite: MS-05E5E2 Novel Reader Block Comment Indicator UI
// ============================================================================

describe('MS-05E5E2 Novel Reader Block Comment Indicator UI', () => {

    test('1. Canonical text invariant: textContent strictly unchanged across all block types', () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-uuid-1',
            'data-content-version': '1'
        });

        const p = createBlock('p', 'blk-p1', 'Đạo khả đạo, phi thường đạo.');
        const h2 = createBlock('h2', 'blk-h2', 'Chương 1: Hỗn Độn Khởi Đầu');
        const bq = createBlock('blockquote', 'blk-bq', 'Nhất hoa nhất thế giới, nhất diệp nhất bồ đề.');
        const li = createBlock('li', 'blk-li', 'Đệ nhất kiếm pháp: Lạc Hoa Thập Tam Kiếm.');
        const pre = createBlock('pre', 'blk-pre', 'const sword = new Sword("Thần Kiếm");');
        const tr = createBlock('tr', 'blk-tr', 'Hàng 1: Kiếm Chủ | Tu Vi: Hóa Thần');

        chapterBody.appendChild(p);
        chapterBody.appendChild(h2);
        chapterBody.appendChild(bq);
        chapterBody.appendChild(li);
        chapterBody.appendChild(pre);
        chapterBody.appendChild(tr);

        // Record initial textContent and childNodes structure
        const blocks = [p, h2, bq, li, pre, tr];
        const textsBefore = blocks.map(b => b.textContent);
        const childCountsBefore = blocks.map(b => b.childNodes.length);

        // Apply indicators
        const indicators = [
            { blockKey: 'blk-p1', threadCount: 5 },
            { blockKey: 'blk-h2', threadCount: 12 },
            { blockKey: 'blk-bq', threadCount: 1 },
            { blockKey: 'blk-li', threadCount: 3 },
            { blockKey: 'blk-pre', threadCount: 8 },
            { blockKey: 'blk-tr', threadCount: 2 }
        ];

        applyIndicators(chapterBody, indicators);

        // Verify indicators were applied via attributes/classes
        for (const block of blocks) {
            assert.strictEqual(block.classList.contains(INDICATOR_CLASS), true);
            assert.ok(block.hasAttribute(THREAD_COUNT_ATTR));
        }

        // Verify CANONICAL TEXT INVARIANT: textContent and childNodes are identical!
        for (let i = 0; i < blocks.length; i++) {
            assert.strictEqual(blocks[i].textContent, textsBefore[i],
                `Block ${blocks[i].tagName} textContent was altered!`);
            assert.strictEqual(blocks[i].childNodes.length, childCountsBefore[i],
                `Block ${blocks[i].tagName} childNodes count was altered!`);
        }

        // Clear indicators and verify textContent remains identical
        clearIndicators(chapterBody);

        for (let i = 0; i < blocks.length; i++) {
            assert.strictEqual(blocks[i].textContent, textsBefore[i]);
            assert.strictEqual(blocks[i].classList.contains(INDICATOR_CLASS), false);
            assert.strictEqual(blocks[i].hasAttribute(THREAD_COUNT_ATTR), false);
        }
    });

    test('2. buildIndicatorsUrl correctly encodes chapter ID', () => {
        const normalUrl = buildIndicatorsUrl('123e4567-e89b-12d3-a456-426614174000');
        assert.strictEqual(normalUrl, '/api/novel/chapters/123e4567-e89b-12d3-a456-426614174000/comments/indicators');

        const specialUrl = buildIndicatorsUrl('abc/def');
        assert.strictEqual(specialUrl, '/api/novel/chapters/abc%2Fdef/comments/indicators');
    });

    test('3. loadChapterIndicators fetches from correct endpoint with Accept: application/json', async () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-uuid-123'
        });
        const p1 = createBlock('p', 'blk-1', 'Thế gian vạn vật');
        chapterBody.appendChild(p1);

        const fetchCalls = [];
        const mockFetch = async (url, opts) => {
            fetchCalls.push({ url, opts });
            return {
                ok: true,
                status: 200,
                json: async () => [{ blockKey: 'blk-1', threadCount: 4 }]
            };
        };

        await loadChapterIndicators(chapterBody, { fetchFn: mockFetch });

        assert.strictEqual(fetchCalls.length, 1);
        assert.strictEqual(fetchCalls[0].url, '/api/novel/chapters/ch-uuid-123/comments/indicators');
        assert.strictEqual(fetchCalls[0].opts.method, 'GET');
        assert.strictEqual(fetchCalls[0].opts.headers['Accept'], 'application/json');

        assert.strictEqual(p1.getAttribute(THREAD_COUNT_ATTR), '4');
        assert.strictEqual(p1.classList.contains(INDICATOR_CLASS), true);
    });

    test('4. Indicators decoration: strict row validation rejects malformed rows and accepts positive integers', () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-uuid-1'
        });
        const pValid = createBlock('p', 'blk-valid', 'Nội dung hợp lệ');
        const pZero = createBlock('p', 'blk-zero', 'Count 0');
        const pNeg = createBlock('p', 'blk-negative', 'Count -1');
        const pStr = createBlock('p', 'blk-string', 'Count "3"');
        const pBool = createBlock('p', 'blk-bool', 'Count true');
        const pFloat = createBlock('p', 'blk-float', 'Count 1.5');
        const pNan = createBlock('p', 'blk-nan', 'Count NaN');
        const pInf = createBlock('p', 'blk-inf', 'Count Infinity');
        const pEmpty = createBlock('p', '', 'Empty key');

        chapterBody.appendChild(pValid);
        chapterBody.appendChild(pZero);
        chapterBody.appendChild(pNeg);
        chapterBody.appendChild(pStr);
        chapterBody.appendChild(pBool);
        chapterBody.appendChild(pFloat);
        chapterBody.appendChild(pNan);
        chapterBody.appendChild(pInf);
        chapterBody.appendChild(pEmpty);

        const indicators = [
            // Control case: valid positive integer
            { blockKey: 'blk-valid', threadCount: 4 },
            // Rejected cases:
            { blockKey: 'blk-string', threadCount: "3" },
            { blockKey: 'blk-bool', threadCount: true },
            { blockKey: 'blk-float', threadCount: 1.5 },
            { blockKey: 'blk-nan', threadCount: NaN },
            { blockKey: 'blk-inf', threadCount: Infinity },
            { blockKey: 'blk-zero', threadCount: 0 },
            { blockKey: 'blk-negative', threadCount: -1 },
            { blockKey: '', threadCount: 5 },
            { blockKey: '   ', threadCount: 6 },
            { blockKey: 123, threadCount: 7 },
            { blockKey: null, threadCount: 8 },
            { blockKey: undefined, threadCount: 9 },
            { blockKey: 'blk-non-existent-in-dom', threadCount: 15 }
        ];

        applyIndicators(chapterBody, indicators);

        // Control case: accepted
        assert.strictEqual(pValid.getAttribute(THREAD_COUNT_ATTR), '4');
        assert.strictEqual(pValid.classList.contains(INDICATOR_CLASS), true);

        // All malformed cases rejected: no attribute, no class
        const rejectedBlocks = [pZero, pNeg, pStr, pBool, pFloat, pNan, pInf, pEmpty];
        for (const b of rejectedBlocks) {
            assert.strictEqual(b.hasAttribute(THREAD_COUNT_ATTR), false,
                `Block ${b.getAttribute(BLOCK_KEY_ATTR)} should not have attribute`);
            assert.strictEqual(b.classList.contains(INDICATOR_CLASS), false,
                `Block ${b.getAttribute(BLOCK_KEY_ATTR)} should not have indicator class`);
        }
    });

    test('5. Chapter scoping: independent indicators across multiple chapter containers', async () => {
        const chapterBodyA = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'chapter-A'
        });
        const pA = createBlock('p', 'blk-shared-key', 'Đoạn văn chương A');
        chapterBodyA.appendChild(pA);

        const chapterBodyB = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'chapter-B'
        });
        const pB = createBlock('p', 'blk-shared-key', 'Đoạn văn chương B');
        chapterBodyB.appendChild(pB);

        const container = new FakeElement('div');
        container.appendChild(chapterBodyA);
        container.appendChild(chapterBodyB);

        const mockFetch = async (url) => {
            if (url.includes('chapter-A')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => [{ blockKey: 'blk-shared-key', threadCount: 3 }]
                };
            }
            if (url.includes('chapter-B')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => [{ blockKey: 'blk-shared-key', threadCount: 9 }]
                };
            }
            return { ok: false, status: 404 };
        };

        await initChapterCommentIndicators(container, { fetchFn: mockFetch });

        // Verify chapter A block gets count 3, chapter B block gets count 9
        assert.strictEqual(pA.getAttribute(THREAD_COUNT_ATTR), '3');
        assert.strictEqual(pB.getAttribute(THREAD_COUNT_ATTR), '9');
    });

    test('6. Idempotency: repeated runs cleanly update counts without leaking stale badges', () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-1'
        });
        const p1 = createBlock('p', 'blk-1', 'Đoạn 1');
        const p2 = createBlock('p', 'blk-2', 'Đoạn 2');
        chapterBody.appendChild(p1);
        chapterBody.appendChild(p2);

        // Run 1: blk-1 has 2, blk-2 has 4
        applyIndicators(chapterBody, [
            { blockKey: 'blk-1', threadCount: 2 },
            { blockKey: 'blk-2', threadCount: 4 }
        ]);
        assert.strictEqual(p1.getAttribute(THREAD_COUNT_ATTR), '2');
        assert.strictEqual(p2.getAttribute(THREAD_COUNT_ATTR), '4');

        // Run 2: blk-1 count increases to 5, blk-2 has no comments (0 or omitted)
        applyIndicators(chapterBody, [
            { blockKey: 'blk-1', threadCount: 5 }
        ]);
        assert.strictEqual(p1.getAttribute(THREAD_COUNT_ATTR), '5');
        assert.strictEqual(p2.hasAttribute(THREAD_COUNT_ATTR), false);
        assert.strictEqual(p2.classList.contains(INDICATOR_CLASS), false);

        // Run 3: empty list clears all
        applyIndicators(chapterBody, []);
        assert.strictEqual(p1.hasAttribute(THREAD_COUNT_ATTR), false);
        assert.strictEqual(p1.classList.contains(INDICATOR_CLASS), false);
        assert.strictEqual(p2.hasAttribute(THREAD_COUNT_ATTR), false);
        assert.strictEqual(p2.classList.contains(INDICATOR_CLASS), false);
    });

    test('7. Graceful handling of HTTP 404, 500, and network failure', async () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-error'
        });
        const p1 = createBlock('p', 'blk-1', 'Kiểm tra lỗi');
        chapterBody.appendChild(p1);

        // 404 Not Found
        const fetch404 = async () => ({ ok: false, status: 404 });
        await assert.doesNotReject(async () => {
            await loadChapterIndicators(chapterBody, { fetchFn: fetch404 });
        });
        assert.strictEqual(p1.hasAttribute(THREAD_COUNT_ATTR), false);

        // 500 Server Error
        const fetch500 = async () => ({ ok: false, status: 500 });
        await assert.doesNotReject(async () => {
            await loadChapterIndicators(chapterBody, { fetchFn: fetch500 });
        });
        assert.strictEqual(p1.hasAttribute(THREAD_COUNT_ATTR), false);

        // Network reject
        const fetchNetworkError = async () => { throw new Error('Network timeout'); };
        await assert.doesNotReject(async () => {
            await loadChapterIndicators(chapterBody, { fetchFn: fetchNetworkError });
        });
        assert.strictEqual(p1.hasAttribute(THREAD_COUNT_ATTR), false);
    });

    test('8. Missing data-chapter-id skips quietly without fetching', async () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body'
            // no data-chapter-id
        });
        let fetched = false;
        const mockFetch = async () => {
            fetched = true;
            return { ok: true, json: async () => [] };
        };

        await loadChapterIndicators(chapterBody, { fetchFn: mockFetch });
        assert.strictEqual(fetched, false);
    });

    test('9. In-flight race protection: discards response if chapter changed while fetching', async () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'chapter-initial'
        });
        const p1 = createBlock('p', 'blk-1', 'Đoạn văn');
        chapterBody.appendChild(p1);

        let resolveFetch;
        const fetchPromise = new Promise(res => { resolveFetch = res; });

        const slowFetch = () => fetchPromise;

        const loadPromise = loadChapterIndicators(chapterBody, { fetchFn: slowFetch });

        // Simulate user navigating to a new chapter before fetch finishes
        chapterBody.setAttribute('data-chapter-id', 'chapter-swapped');

        // Now let fetch resolve with old chapter's data
        resolveFetch({
            ok: true,
            status: 200,
            json: async () => [{ blockKey: 'blk-1', threadCount: 99 }]
        });

        await loadPromise;

        // Verify indicators were NOT applied because chapterId was swapped
        assert.strictEqual(p1.hasAttribute(THREAD_COUNT_ATTR), false);
        assert.strictEqual(p1.classList.contains(INDICATOR_CLASS), false);
    });

    test('10. Lifecycle event binding: same chapter initializes/fetches at most once', async () => {
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-A'
        });
        const p1 = createBlock('p', 'blk-1', 'Chương thử sự kiện');
        chapterBody.appendChild(p1);

        const fakeDoc = new FakeDocument(chapterBody);
        fakeDoc.readyState = 'loading';

        let fetchCount = 0;
        let resolveInFlightFetch;
        const fetchPromise = new Promise(r => { resolveInFlightFetch = r; });

        const mockFetch = async () => {
            fetchCount++;
            await fetchPromise;
            return {
                ok: true,
                status: 200,
                json: async () => [{ blockKey: 'blk-1', threadCount: 2 }]
            };
        };

        bindChapterEvents(fakeDoc, { fetchFn: mockFetch });

        // ReadyState was loading: not called yet
        assert.strictEqual(fetchCount, 0);

        // Trigger DOMContentLoaded for chapter A -> request starts (in flight)
        fakeDoc.dispatchEvent('DOMContentLoaded');
        assert.strictEqual(fetchCount, 1);

        // Prove: duplicate event / initialization WHILE the first A request is still pending does NOT start a second request
        fakeDoc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'ch-A' } });
        initChapterCommentIndicators(fakeDoc, { fetchFn: mockFetch });
        assert.strictEqual(fetchCount, 1);

        // Allow in-flight request to resolve
        resolveInFlightFetch();
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(fetchCount, 1);
        assert.strictEqual(p1.getAttribute(THREAD_COUNT_ATTR), '2');

        // Prove: duplicate event AFTER completed request does NOT start another request
        fakeDoc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'ch-A' } });
        initChapterCommentIndicators(fakeDoc, { fetchFn: mockFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(fetchCount, 1);
        assert.strictEqual(p1.getAttribute(THREAD_COUNT_ATTR), '2');
    });

    test('11. Actual continuous reader contract: stable chapterBody in-place transition A -> B', async () => {
        // ONE stable chapterBody element
        const chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'chapter-A'
        });

        // Initial state: Chapter A contains blockKey = blk-shared
        const pA = createBlock('p', 'blk-shared', 'Nội dung chương A');
        chapterBody.appendChild(pA);

        const fakeDoc = new FakeDocument(chapterBody);

        const fetchedUrls = [];
        const mockFetch = async (url) => {
            fetchedUrls.push(url);
            if (url.includes('chapter-A')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => [{ blockKey: 'blk-shared', threadCount: 3 }]
                };
            }
            if (url.includes('chapter-B')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => [{ blockKey: 'blk-shared', threadCount: 8 }]
                };
            }
            return { ok: false, status: 404 };
        };

        bindChapterEvents(fakeDoc, { fetchFn: mockFetch });

        // Initialize Chapter A
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(fetchedUrls.length, 1);
        assert.ok(fetchedUrls[0].includes('chapter-A'));
        assert.strictEqual(pA.getAttribute(THREAD_COUNT_ATTR), '3');
        assert.strictEqual(pA.classList.contains(INDICATOR_CLASS), true);

        // Simulate narration-controller's actual in-place transition:
        // 1. Replace chapterBody children with Chapter B's keyed block
        chapterBody.childNodes = [];
        const pB = createBlock('p', 'blk-shared', 'Nội dung chương B hoàn toàn mới');
        chapterBody.appendChild(pB);

        // 2. Change data-chapter-id to chapter-B
        chapterBody.setAttribute('data-chapter-id', 'chapter-B');

        // 3. Dispatch kiemlai:chapter-changed
        fakeDoc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'chapter-B' }
        });

        await new Promise(r => setTimeout(r, 10));

        // Assert: B endpoint fetched exactly once
        assert.strictEqual(fetchedUrls.length, 2);
        assert.ok(fetchedUrls[1].includes('chapter-B'));

        // Assert: B block receives B count (8, not A's 3!)
        assert.strictEqual(pB.getAttribute(THREAD_COUNT_ATTR), '8');
        assert.strictEqual(pB.classList.contains(INDICATOR_CLASS), true);

        // Dispatch another chapter-changed for chapter-B:
        fakeDoc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'chapter-B' }
        });

        await new Promise(r => setTimeout(r, 10));

        // Assert: NO additional B fetch!
        assert.strictEqual(fetchedUrls.length, 2);
    });

});
