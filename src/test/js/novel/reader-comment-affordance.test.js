const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const affordanceModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-affordance.js'));

// ============================================================================
// Lightweight DOM Test Fixtures
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
        this.listeners = {};
        this.style = {};
        this._innerHTML = '';
        this.offsetWidth = 40;
        this.offsetHeight = 32;

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

    get className() {
        return this.getAttribute('class') || '';
    }

    set className(val) {
        this.setAttribute('class', val);
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

    get innerHTML() {
        return this._innerHTML;
    }

    set innerHTML(val) {
        this._innerHTML = String(val);
    }

    matches(selector) {
        if (!selector) return false;
        if (selector === '.novel-reader-chapter-body') {
            return this.classList.contains('novel-reader-chapter-body');
        }
        if (selector.startsWith('.')) {
            return this.classList.contains(selector.slice(1));
        }
        if (selector === '[data-reader-block-key]') {
            return this.hasAttribute('data-reader-block-key');
        }
        if (selector === '[data-comment-thread-count]') {
            return this.hasAttribute('data-comment-thread-count');
        }
        if (selector.includes(',')) {
            return selector.split(',').some(part => this.matches(part.trim()));
        }
        if (this.tagName.toLowerCase() === selector.toLowerCase()) {
            return true;
        }
        return false;
    }

    closest(selector) {
        let el = this;
        while (el && el.nodeType === 1) {
            if (el.matches(selector)) {
                return el;
            }
            el = el.parentElement;
        }
        return null;
    }

    contains(other) {
        if (!other) return false;
        if (this === other) return true;
        let curr = other.parentElement;
        while (curr) {
            if (curr === this) return true;
            curr = curr.parentElement;
        }
        return false;
    }

    getBoundingClientRect() {
        return {
            top: 120,
            bottom: 160,
            left: 50,
            right: 600,
            width: 550,
            height: 40
        };
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
        function walk(node) {
            if (!node || !node.childNodes) return;
            for (const child of node.childNodes) {
                if (child.nodeType === 1) {
                    if (child.matches(selector)) {
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
    constructor() {
        this.body = new FakeElement('body');
        this.documentElement = new FakeElement('html');
        this.documentElement.appendChild(this.body);
        this.readyState = 'complete';
        this.listeners = {};
        this._activeSelection = null;

        this.defaultView = {
            innerWidth: 1024,
            innerHeight: 768,
            getSelection: () => {
                return this._activeSelection || { isCollapsed: true, toString: () => '' };
            },
            addEventListener: (type, fn) => {
                this.addEventListener(type, fn);
            }
        };
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
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

    querySelector(selector) {
        if (this.body.matches(selector)) return this.body;
        return this.body.querySelector(selector);
    }

    querySelectorAll(selector) {
        return this.body.querySelectorAll(selector);
    }
}

function createBlock(tagName, blockKey, text, count = null) {
    const attrs = { 'data-reader-block-key': blockKey };
    if (count !== null) {
        attrs['data-comment-thread-count'] = String(count);
    }
    const el = new FakeElement(tagName, attrs);
    el.appendChild(new FakeTextNode(text));
    return el;
}

// ============================================================================
// Test Suite: MS-05E5F2 Wattpad-Style Novel Reader Block Comment Affordance
// ============================================================================

describe('MS-05E5F2 Wattpad-Style Novel Reader Block Comment Affordance', () => {

    let doc;
    let chapterBody;
    let p1, p2;

    beforeEach(() => {
        affordanceModule.resetAffordanceState();
        doc = new FakeDocument();

        chapterBody = new FakeElement('article', {
            'class': 'novel-reader-chapter-body',
            'data-chapter-id': 'ch-uuid-1',
            'data-content-version': '1'
        });
        chapterBody.ownerDocument = doc;

        p1 = createBlock('p', 'blk-p1', 'Đạo khả đạo, phi thường đạo.');
        p2 = createBlock('p', 'blk-p2', 'Danh khả danh, phi thường danh.', 3);

        chapterBody.appendChild(p1);
        chapterBody.appendChild(p2);
        doc.body.appendChild(chapterBody);
    });

    test('1. exactly one floating button is created in document', () => {
        const btn1 = affordanceModule.initReaderCommentAffordance(doc);
        const btn2 = affordanceModule.initReaderCommentAffordance(doc);

        assert.ok(btn1);
        assert.strictEqual(btn1, btn2);
        const allButtons = doc.querySelectorAll('.reader-comment-affordance-btn');
        assert.strictEqual(allButtons.length, 1);
    });

    test('2. button is outside .novel-reader-chapter-body', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);
        assert.strictEqual(btn.parentElement, doc.body);
        assert.strictEqual(chapterBody.contains(btn), false);
    });

    test('3. keyed block textContent never changes before, during, or after affordance', () => {
        const initialText = p1.textContent;
        const initialChildCount = p1.childNodes.length;

        affordanceModule.initReaderCommentAffordance(doc);

        // Hover p1
        affordanceModule.showAffordance(p1);
        assert.strictEqual(p1.textContent, initialText, 'textContent changed while affordance visible!');
        assert.strictEqual(p1.childNodes.length, initialChildCount, 'childNodes count changed while visible!');

        // Hide affordance
        affordanceModule.hideAffordance();
        assert.strictEqual(p1.textContent, initialText, 'textContent changed after affordance hidden!');
        assert.strictEqual(p1.childNodes.length, initialChildCount, 'childNodes count changed after hidden!');
    });

    test('4. hover/focus target with zero threads shows new-discussion affordance', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        affordanceModule.showAffordance(p1); // p1 has no comments

        assert.strictEqual(btn.getAttribute('aria-label'), 'Bình luận đoạn này');
        assert.ok(btn.innerHTML.includes('reader-comment-badge--plus'));
        assert.ok(btn.innerHTML.includes('+'));
        assert.strictEqual(btn.classList.contains('has-comments'), false);
    });

    test('5. block with threadCount=3 shows count 3', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        affordanceModule.showAffordance(p2); // p2 has count 3

        assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 3 bình luận của đoạn này');
        assert.ok(btn.innerHTML.includes('reader-comment-badge--count'));
        assert.ok(btn.innerHTML.includes('3'));
        assert.strictEqual(btn.classList.contains('has-comments'), true);
    });

    test('6. malformed/non-positive count treated as zero with strict parsing', () => {
        const pZero = createBlock('p', 'blk-zero', 'Text', 0);
        const pNeg = createBlock('p', 'blk-neg', 'Text', -5);
        const pInvalid = createBlock('p', 'blk-inv', 'Text', 'abc');
        const pTrailing = createBlock('p', 'blk-trailing', 'Text', '3abc');
        const pFloat = createBlock('p', 'blk-float', 'Text', '3.7');
        chapterBody.appendChild(pZero);
        chapterBody.appendChild(pNeg);
        chapterBody.appendChild(pInvalid);
        chapterBody.appendChild(pTrailing);
        chapterBody.appendChild(pFloat);

        const btn = affordanceModule.initReaderCommentAffordance(doc);

        for (const blk of [pZero, pNeg, pInvalid, pTrailing, pFloat]) {
            affordanceModule.showAffordance(blk);
            assert.strictEqual(btn.getAttribute('aria-label'), 'Bình luận đoạn này');
            assert.ok(btn.innerHTML.includes('+'));
            assert.strictEqual(btn.classList.contains('has-comments'), false);
        }

        // Direct unit checks for parseThreadCount
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => '3' }), 3);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => ' 3 ' }), 3);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => '3abc' }), 0);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => '3.7' }), 0);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => '-1' }), 0);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => '0' }), 0);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => '   ' }), 0);
        assert.strictEqual(affordanceModule.parseThreadCount({ getAttribute: () => null }), 0);
        assert.strictEqual(affordanceModule.parseThreadCount(null), 0);

        // Direct unit checks for parseCommentCount with fallback
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? '3' : '1' }), 3);
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? ' 3 ' : '1' }), 3);
        // Missing commentCount falls back to threadCount
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-thread-count' ? '2' : null }), 2);
        // Malformed commentCount falls back to threadCount
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? 'abc' : '2' }), 2);
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? '-1' : '2' }), 2);
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? '0' : '2' }), 2);
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? '1.5' : '2' }), 2);
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: (attr) => attr === 'data-comment-count' ? '3abc' : '2' }), 2);
        assert.strictEqual(affordanceModule.parseCommentCount({ getAttribute: () => null }), 0);
        assert.strictEqual(affordanceModule.parseCommentCount(null), 0);
    });

    test('7. switching active block updates count/context', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        // Show p1 (0 count)
        affordanceModule.showAffordance(p1);
        assert.strictEqual(affordanceModule.getActiveBlock(), p1);
        assert.strictEqual(btn.getAttribute('aria-label'), 'Bình luận đoạn này');

        // Switch to p2 (3 count)
        affordanceModule.showAffordance(p2);
        assert.strictEqual(affordanceModule.getActiveBlock(), p2);
        assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 3 bình luận của đoạn này');
    });

    test('8. button click dispatches exactly one kiemlai:block-discussion-requested event', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        let eventCount = 0;
        let receivedDetail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            eventCount++;
            receivedDetail = e.detail;
        });

        affordanceModule.showAffordance(p2);
        btn.dispatchEvent('click');

        assert.strictEqual(eventCount, 1);
        assert.ok(receivedDetail);
    });

    test('9. event detail has current chapterId/contentVersion/blockKey/threadCount', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        let detail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            detail = e.detail;
        });

        affordanceModule.showAffordance(p2);
        btn.dispatchEvent('click');

        assert.strictEqual(detail.chapterId, 'ch-uuid-1');
        assert.strictEqual(detail.contentVersion, 1);
        assert.strictEqual(detail.blockKey, 'blk-p2');
        assert.strictEqual(detail.threadCount, 3);
        assert.strictEqual(detail.canonicalText, 'Danh khả danh, phi thường danh.');
    });

    test('10. no offset/range fields exist in event detail', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        let detail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            detail = e.detail;
        });

        affordanceModule.showAffordance(p2);
        btn.dispatchEvent('click');

        assert.strictEqual(detail.startOffset, undefined);
        assert.strictEqual(detail.endOffset, undefined);
        assert.strictEqual(detail.selectedText, undefined);
        assert.strictEqual(detail.range, undefined);
    });

    test('11. same block interaction does not duplicate buttons/listeners', () => {
        affordanceModule.initReaderCommentAffordance(doc);

        let clickCount = 0;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, () => {
            clickCount++;
        });

        const btn = affordanceModule.getAffordanceButton();

        // Repeated hover
        affordanceModule.showAffordance(p1);
        affordanceModule.showAffordance(p1);
        affordanceModule.showAffordance(p1);

        assert.strictEqual(doc.querySelectorAll('.reader-comment-affordance-btn').length, 1);

        btn.dispatchEvent('click');
        assert.strictEqual(clickCount, 1);
    });

    test('12. pointer movement from block to button keeps control usable', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        // Hover block
        doc.dispatchEvent({ type: 'mouseover', target: p1 });
        assert.strictEqual(affordanceModule.getActiveBlock(), p1);

        // Move pointer from block onto button: relatedTarget is button
        doc.dispatchEvent({ type: 'mouseout', target: p1, relatedTarget: btn });

        // Pointer enters button
        doc.dispatchEvent({ type: 'mouseover', target: btn });

        // Control remains active and usable
        assert.strictEqual(affordanceModule.getActiveBlock(), p1);
        assert.strictEqual(btn.classList.contains('is-visible'), true);
    });

    test('13. touch/tap reveals affordance but does NOT itself dispatch open event', () => {
        affordanceModule.initReaderCommentAffordance(doc);

        let openDispatched = false;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, () => {
            openDispatched = true;
        });

        // Tap block
        doc.dispatchEvent({ type: 'click', target: p1 });

        // Affordance is revealed for p1
        assert.strictEqual(affordanceModule.getActiveBlock(), p1);
        // But discussion open event was NOT dispatched on first tap!
        assert.strictEqual(openDispatched, false);
    });

    test('14. non-collapsed text selection prevents touch block activation', () => {
        affordanceModule.initReaderCommentAffordance(doc);

        // Mock non-collapsed text selection (e.g. user selecting text for Wiki lookup)
        doc._activeSelection = {
            isCollapsed: false,
            toString: () => 'Đạo khả đạo'
        };

        // User taps block during text selection
        doc.dispatchEvent({ type: 'click', target: p1 });

        // Affordance must NOT be activated
        assert.strictEqual(affordanceModule.getActiveBlock(), null);
    });

    test('15. link/control click inside Reader does not incorrectly activate block affordance', () => {
        affordanceModule.initReaderCommentAffordance(doc);

        const link = new FakeElement('a', { href: '/wiki/term' });
        link.appendChild(new FakeTextNode('Wiki Term'));
        p1.appendChild(link);

        // Tap link
        doc.dispatchEvent({ type: 'click', target: link });

        // Affordance was not activated
        assert.strictEqual(affordanceModule.getActiveBlock(), null);
    });

    test('16. chapter-changed hides affordance and clears old block', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        affordanceModule.showAffordance(p1);
        assert.strictEqual(affordanceModule.getActiveBlock(), p1);

        // Dispatch kiemlai:chapter-changed
        doc.dispatchEvent({ type: affordanceModule.EVENT_CHAPTER_CHANGED, detail: { chapterId: 'ch-uuid-2' } });

        assert.strictEqual(affordanceModule.getActiveBlock(), null);
        assert.strictEqual(btn.classList.contains('is-visible'), false);
    });

    test('17. Chapter A block cannot dispatch after transition to Chapter B', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        let eventCount = 0;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, () => {
            eventCount++;
        });

        // Hover Chapter A block
        affordanceModule.showAffordance(p1);

        // Transition to Chapter B
        doc.dispatchEvent({ type: affordanceModule.EVENT_CHAPTER_CHANGED, detail: { chapterId: 'ch-uuid-2' } });

        // Attempt clicking button
        btn.dispatchEvent('click');

        assert.strictEqual(eventCount, 0, 'Old block dispatched event after chapter transition!');
    });

    test('18. new Chapter B block produces B metadata', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        let receivedDetail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            receivedDetail = e.detail;
        });

        // Swap chapterBody to Chapter B
        chapterBody.setAttribute('data-chapter-id', 'ch-uuid-B');
        chapterBody.setAttribute('data-content-version', '2');
        chapterBody.childNodes = [];

        const pB = createBlock('p', 'blk-chB-1', 'Khởi đầu chương 2.', 7);
        chapterBody.appendChild(pB);

        // Notify chapter changed
        doc.dispatchEvent({ type: affordanceModule.EVENT_CHAPTER_CHANGED, detail: { chapterId: 'ch-uuid-B' } });

        // Hover Chapter B block and click
        affordanceModule.showAffordance(pB);
        btn.dispatchEvent('click');

        assert.ok(receivedDetail);
        assert.strictEqual(receivedDetail.chapterId, 'ch-uuid-B');
        assert.strictEqual(receivedDetail.contentVersion, 2);
        assert.strictEqual(receivedDetail.blockKey, 'blk-chB-1');
        assert.strictEqual(receivedDetail.threadCount, 7);
    });

    test('19. count update can refresh currently active affordance', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        // Show p1 which initially has 0 count
        affordanceModule.showAffordance(p1);
        assert.ok(btn.innerHTML.includes('+'));

        // Background indicator fetch finishes and updates p1 to 4
        p1.setAttribute('data-comment-thread-count', '4');
        doc.dispatchEvent({ type: affordanceModule.EVENT_INDICATORS_UPDATED, detail: { chapterId: 'ch-uuid-1' } });

        // Affordance reflects updated count 4 without closing
        assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 4 bình luận của đoạn này');
        assert.ok(btn.innerHTML.includes('4'));
        assert.strictEqual(btn.classList.contains('has-comments'), true);
    });

    test('20. table-row fixture remains structurally untouched', () => {
        const tr = new FakeElement('tr', {
            'data-reader-block-key': 'blk-tr1',
            'data-comment-thread-count': '2'
        });
        const td1 = new FakeElement('td');
        td1.appendChild(new FakeTextNode('Võ Tôn'));
        const td2 = new FakeElement('td');
        td2.appendChild(new FakeTextNode('Cấp 9'));
        tr.appendChild(td1);
        tr.appendChild(td2);
        chapterBody.appendChild(tr);

        const initialChildCount = tr.childNodes.length;
        const initialText = tr.textContent;

        const btn = affordanceModule.initReaderCommentAffordance(doc);

        // Hover table row
        affordanceModule.showAffordance(tr);

        // Verify button placed outside table row
        assert.strictEqual(tr.contains(btn), false);
        assert.strictEqual(tr.childNodes.length, initialChildCount);
        assert.strictEqual(tr.textContent, initialText);

        // Click button
        let detail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            detail = e.detail;
        });
        btn.dispatchEvent('click');

        assert.strictEqual(detail.blockKey, 'blk-tr1');
        assert.strictEqual(detail.threadCount, 2);

        // Hide affordance
        affordanceModule.hideAffordance();

        assert.strictEqual(tr.childNodes.length, initialChildCount);
        assert.strictEqual(tr.textContent, initialText);
    });

    test('21. strict contentVersion parsing and validation on button click', () => {
        // Direct unit checks for parseContentVersion
        assert.strictEqual(affordanceModule.parseContentVersion('1'), 1);
        assert.strictEqual(affordanceModule.parseContentVersion('2'), 2);
        assert.strictEqual(affordanceModule.parseContentVersion(' 2 '), 2);
        assert.strictEqual(affordanceModule.parseContentVersion('2junk'), null);
        assert.strictEqual(affordanceModule.parseContentVersion('2.5'), null);
        assert.strictEqual(affordanceModule.parseContentVersion('-1'), null);
        assert.strictEqual(affordanceModule.parseContentVersion('0'), null);
        assert.strictEqual(affordanceModule.parseContentVersion(''), null);
        assert.strictEqual(affordanceModule.parseContentVersion('   '), null);
        assert.strictEqual(affordanceModule.parseContentVersion(null), null);
        assert.strictEqual(affordanceModule.parseContentVersion(undefined), null);

        const btn = affordanceModule.initReaderCommentAffordance(doc);

        let eventCount = 0;
        let receivedDetail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            eventCount++;
            receivedDetail = e.detail;
        });

        // 1. Valid integer contentVersion "2"
        chapterBody.setAttribute('data-content-version', '2');
        affordanceModule.showAffordance(p1);
        btn.dispatchEvent('click');

        assert.strictEqual(eventCount, 1);
        assert.strictEqual(receivedDetail.contentVersion, 2);

        // 2. Trailing garbage "2junk" -> click fails safely, no event dispatched, affordance hidden
        chapterBody.setAttribute('data-content-version', '2junk');
        affordanceModule.showAffordance(p1);
        assert.strictEqual(btn.classList.contains('is-visible'), true);
        btn.dispatchEvent('click');

        assert.strictEqual(eventCount, 1, 'Event should not be dispatched for "2junk"');
        assert.strictEqual(affordanceModule.getActiveBlock(), null);
        assert.strictEqual(btn.classList.contains('is-visible'), false);

        // 3. Float "2.5" -> click fails safely, no event dispatched, affordance hidden
        chapterBody.setAttribute('data-content-version', '2.5');
        affordanceModule.showAffordance(p1);
        assert.strictEqual(btn.classList.contains('is-visible'), true);
        btn.dispatchEvent('click');

        assert.strictEqual(eventCount, 1, 'Event should not be dispatched for "2.5"');
        assert.strictEqual(affordanceModule.getActiveBlock(), null);
        assert.strictEqual(btn.classList.contains('is-visible'), false);

        // 4. Zero "0" -> click fails safely, no event dispatched, affordance hidden
        chapterBody.setAttribute('data-content-version', '0');
        affordanceModule.showAffordance(p1);
        assert.strictEqual(btn.classList.contains('is-visible'), true);
        btn.dispatchEvent('click');

        assert.strictEqual(eventCount, 1, 'Event should not be dispatched for "0"');
        assert.strictEqual(affordanceModule.getActiveBlock(), null);
        assert.strictEqual(btn.classList.contains('is-visible'), false);
    });

    test('22. cross-chapter indicator event guard: only refreshes if chapterId matches activeBlock', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        // Show p1 in chapter 'ch-uuid-1'
        affordanceModule.showAffordance(p1);
        assert.ok(btn.innerHTML.includes('+'));

        // Mutate block's attribute in DOM to 7
        p1.setAttribute('data-comment-thread-count', '7');

        // 1. Dispatch event for an UNRELATED chapter 'other-chapter'
        doc.dispatchEvent({
            type: affordanceModule.EVENT_INDICATORS_UPDATED,
            detail: { chapterId: 'other-chapter' }
        });

        // Affordance must NOT have refreshed: still shows '+'
        assert.ok(btn.innerHTML.includes('+'), 'Affordance should not refresh for mismatched chapterId');
        assert.strictEqual(btn.getAttribute('aria-label'), 'Bình luận đoạn này');

        // 2. Dispatch event with blank / missing chapterId
        doc.dispatchEvent({
            type: affordanceModule.EVENT_INDICATORS_UPDATED,
            detail: { chapterId: '' }
        });
        assert.ok(btn.innerHTML.includes('+'), 'Affordance should not refresh for empty chapterId');

        doc.dispatchEvent({
            type: affordanceModule.EVENT_INDICATORS_UPDATED,
            detail: null
        });
        assert.ok(btn.innerHTML.includes('+'), 'Affordance should not refresh for null detail');

        // 3. Dispatch event for MATCHING chapter 'ch-uuid-1'
        doc.dispatchEvent({
            type: affordanceModule.EVENT_INDICATORS_UPDATED,
            detail: { chapterId: 'ch-uuid-1' }
        });

        // Affordance is now updated to 7
        assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 7 bình luận của đoạn này');
        assert.ok(btn.innerHTML.includes('7'));
        assert.strictEqual(btn.classList.contains('has-comments'), true);
    });

    test('23. threadCount=1 and commentCount=3 shows badge 3, aria-label 3 bình luận, and dispatches {threadCount: 1, commentCount: 3}', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        const pDistinct = createBlock('p', 'blk-distinct', 'Nội dung khối');
        pDistinct.setAttribute('data-comment-thread-count', '1');
        pDistinct.setAttribute('data-comment-count', '3');
        chapterBody.appendChild(pDistinct);

        affordanceModule.showAffordance(pDistinct);

        // Badge displays commentCount (3)
        assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 3 bình luận của đoạn này');
        assert.ok(btn.innerHTML.includes('reader-comment-badge--count'));
        assert.ok(btn.innerHTML.includes('3'));
        assert.strictEqual(btn.classList.contains('has-comments'), true);

        // Click dispatches threadCount=1, commentCount=3
        let receivedDetail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            receivedDetail = e.detail;
        });
        btn.dispatchEvent('click');

        assert.ok(receivedDetail);
        assert.strictEqual(receivedDetail.threadCount, 1);
        assert.strictEqual(receivedDetail.commentCount, 3);
        assert.strictEqual(receivedDetail.blockKey, 'blk-distinct');
    });

    test('24. legacy block with only data-comment-thread-count="2" falls back to badge 2 and event {threadCount: 2, commentCount: 2}', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        const pLegacy = createBlock('p', 'blk-legacy-2', 'Khối legacy');
        pLegacy.setAttribute('data-comment-thread-count', '2');
        chapterBody.appendChild(pLegacy);

        affordanceModule.showAffordance(pLegacy);

        // Badge displays 2
        assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 2 bình luận của đoạn này');
        assert.ok(btn.innerHTML.includes('2'));

        let receivedDetail = null;
        doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, (e) => {
            receivedDetail = e.detail;
        });
        btn.dispatchEvent('click');

        assert.ok(receivedDetail);
        assert.strictEqual(receivedDetail.threadCount, 2);
        assert.strictEqual(receivedDetail.commentCount, 2);
    });

    test('25. malformed data-comment-count falls back to valid threadCount for badge and dispatch', () => {
        const btn = affordanceModule.initReaderCommentAffordance(doc);

        const malformedValues = ['abc', '-1', '0', '1.5', '3junk', ''];

        for (const malformed of malformedValues) {
            const pMalformed = createBlock('p', 'blk-malformed-' + malformed, 'Khối malformed');
            pMalformed.setAttribute('data-comment-thread-count', '4');
            pMalformed.setAttribute('data-comment-count', malformed);
            chapterBody.appendChild(pMalformed);

            affordanceModule.showAffordance(pMalformed);

            // Badge displays 4
            assert.strictEqual(btn.getAttribute('aria-label'), 'Mở 4 bình luận của đoạn này');
            assert.ok(btn.innerHTML.includes('4'));

            let receivedDetail = null;
            const onRequested = (e) => {
                receivedDetail = e.detail;
            };
            doc.addEventListener(affordanceModule.EVENT_DISCUSSION_REQUESTED, onRequested);
            btn.dispatchEvent('click');

            assert.ok(receivedDetail, 'Detail should be dispatched for malformed commentCount');
            assert.strictEqual(receivedDetail.threadCount, 4);
            assert.strictEqual(receivedDetail.commentCount, 4);

            // Cleanup listener for next iteration
            doc.listeners[affordanceModule.EVENT_DISCUSSION_REQUESTED] = [];
        }
    });

});
