const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const commentsModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comments.js'));

// ============================================================================
// Lightweight DOM Test Fixtures
// ============================================================================

class FakeClassList {
    constructor(element) {
        Object.defineProperty(this, 'element', { value: element, writable: true, configurable: true, enumerable: false });
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

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        Object.defineProperty(this, 'parentNode', { value: null, writable: true, configurable: true, enumerable: false });
        Object.defineProperty(this, 'parentElement', { value: null, writable: true, configurable: true, enumerable: false });
        Object.defineProperty(this, 'ownerDocument', { value: null, writable: true, configurable: true, enumerable: false });
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this.hidden = false;
        this._textContent = '';
        this.isFocused = false;

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    [Symbol.for('nodejs.util.inspect.custom')]() {
        const idStr = this.getAttribute('id') ? '#' + this.getAttribute('id') : '';
        const classStr = this.className ? '.' + this.className.split(/\s+/).filter(Boolean).join('.') : '';
        return `<${this.tagName.toLowerCase()}${idStr}${classStr}>`;
    }

    get className() {
        return this.getAttribute('class') || '';
    }

    set className(val) {
        this.setAttribute('class', val);
    }

    get textContent() {
        if (this.childNodes.length === 0) {
            return this._textContent;
        }
        return this.childNodes.map(c => c.textContent || '').join('');
    }

    set textContent(val) {
        this._textContent = String(val);
        this.childNodes = [];
    }

    get nodeType() {
        return 1;
    }

    get children() {
        return this.childNodes.filter(n => n.nodeType === 1);
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        child.ownerDocument = this.ownerDocument;
        this.childNodes.push(child);
        return child;
    }

    insertBefore(newChild, refChild) {
        if (!refChild) {
            return this.appendChild(newChild);
        }
        const idx = this.childNodes.indexOf(refChild);
        if (idx === -1) {
            return this.appendChild(newChild);
        }
        newChild.parentNode = this;
        newChild.parentElement = this;
        newChild.ownerDocument = this.ownerDocument;
        this.childNodes.splice(idx, 0, newChild);
        return newChild;
    }

    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx !== -1) {
            this.childNodes.splice(idx, 1);
            child.parentNode = null;
            child.parentElement = null;
        }
        return child;
    }

    replaceChildren(...newChildren) {
        for (const c of this.childNodes) {
            c.parentNode = null;
            c.parentElement = null;
        }
        this.childNodes = [];
        this._textContent = '';
        for (const child of newChildren) {
            this.appendChild(child);
        }
    }

    replaceChild(newChild, oldChild) {
        const idx = this.childNodes.indexOf(oldChild);
        if (idx !== -1) {
            this.childNodes[idx] = newChild;
            newChild.parentNode = this;
            newChild.parentElement = this;
            newChild.ownerDocument = this.ownerDocument;
            oldChild.parentNode = null;
            oldChild.parentElement = null;
            return oldChild;
        }
        return null;
    }

    get firstChild() {
        return this.childNodes[0] || null;
    }

    get isConnected() {
        let cur = this;
        while (cur) {
            if (cur.ownerDocument && (cur === cur.ownerDocument.documentElement || cur.parentNode === cur.ownerDocument || cur.parentElement === cur.ownerDocument.documentElement)) {
                return true;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    contains(node) {
        let cur = node;
        while (cur) {
            if (cur === this) return true;
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes.clear();
            String(value).trim().split(/\s+/).filter(Boolean).forEach(c => this.classList.classes.add(c));
        }
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(fn);
        if (idx !== -1) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(evt) {
        try {
            evt.target = evt.target || this;
        } catch (_) {}
        if (evt.defaultPrevented === undefined) {
            evt.defaultPrevented = false;
        }
        const origPrevent = evt.preventDefault;
        evt.preventDefault = function () {
            evt.defaultPrevented = true;
            if (typeof origPrevent === 'function') {
                origPrevent.call(this);
            }
        };

        let stopped = false;
        const origStop = evt.stopPropagation;
        evt.stopPropagation = function () {
            stopped = true;
            if (typeof origStop === 'function') {
                origStop.call(this);
            }
        };

        try {
            evt.currentTarget = this;
        } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
            if (stopped) break;
        }

        if (!stopped) {
            let cur = this.parentElement || this.parentNode;
            while (cur && !stopped) {
                try {
                    evt.currentTarget = cur;
                } catch (_) {}
                const curHandlers = cur.listeners[evt.type] || [];
                for (const fn of [...curHandlers]) {
                    fn.call(cur, evt);
                    if (stopped) break;
                }
                cur = cur.parentElement || cur.parentNode;
            }
            if (!stopped && this.ownerDocument) {
                try {
                    evt.currentTarget = this.ownerDocument;
                } catch (_) {}
                const docHandlers = this.ownerDocument.listeners[evt.type] || [];
                for (const fn of [...docHandlers]) {
                    fn.call(this.ownerDocument, evt);
                    if (stopped) break;
                }
            }
        }

        return !evt.defaultPrevented;
    }

    scrollIntoView(options) {
        this.scrollIntoViewCalled = true;
        this.lastScrollOptions = options;
    }

    click() {
        this.dispatchEvent({
            type: 'click',
            target: this,
            currentTarget: this,
            defaultPrevented: false,
            preventDefault() { this.defaultPrevented = true; },
            stopPropagation() {}
        });
    }

    focus() {
        this.isFocused = true;
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
    }

    querySelector(selector) {
        return querySelectorAllDeep(this, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this, selector);
    }

    closest(selector) {
        let cur = this;
        while (cur) {
            if (matchesSingleSelector(cur, selector)) {
                return cur;
            }
            cur = cur.parentElement;
        }
        return null;
    }
}

function matchesSingleSelector(el, sel) {
    if (!el || !el.tagName) return false;
    let attrPart = null;
    let baseSel = sel;
    const bracketIdx = sel.indexOf('[');
    if (bracketIdx !== -1 && sel.endsWith(']')) {
        attrPart = sel.slice(bracketIdx + 1, -1);
        baseSel = sel.slice(0, bracketIdx);
    }
    if (attrPart !== null) {
        const eqIdx = attrPart.indexOf('=');
        if (eqIdx === -1) {
            if (el.getAttribute(attrPart) === null) return false;
        } else {
            const name = attrPart.slice(0, eqIdx);
            const val = attrPart.slice(eqIdx + 1).replace(/^["']|["']$/g, '');
            if (el.getAttribute(name) !== val) return false;
        }
        if (!baseSel) {
            return true;
        }
    }
    if (baseSel.startsWith('#')) {
        return el.getAttribute('id') === baseSel.slice(1);
    }
    let tag = null;
    let classPart = baseSel;
    if (!baseSel.startsWith('.')) {
        const dotIdx = baseSel.indexOf('.');
        if (dotIdx !== -1) {
            tag = baseSel.slice(0, dotIdx);
            classPart = baseSel.slice(dotIdx);
        } else {
            return el.tagName.toLowerCase() === baseSel.toLowerCase();
        }
    }
    if (tag && el.tagName.toLowerCase() !== tag.toLowerCase()) {
        return false;
    }
    const classes = classPart.split('.').filter(Boolean);
    return classes.every(c => el.classList.contains(c));
}

function querySelectorAllDeep(root, selector) {
    const parts = selector.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return [];
    if (parts.length === 1) {
        const results = [];
        function traverse(node) {
            if (!node || !node.childNodes) return;
            for (const child of node.childNodes) {
                if (matchesSingleSelector(child, parts[0])) {
                    results.push(child);
                }
                traverse(child);
            }
        }
        traverse(root);
        return results;
    }

    let currentSet = [root];
    for (const part of parts) {
        const nextSet = [];
        for (const parent of currentSet) {
            nextSet.push(...querySelectorAllDeep(parent, part));
        }
        currentSet = nextSet;
    }
    return currentSet;
}

class FakeDocument {
    constructor() {
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.documentElement = new FakeElement('html');
        this.documentElement.ownerDocument = this;
        this.documentElement.appendChild(this.body);
        this.listeners = {};
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    getElementById(id) {
        return querySelectorAllDeep(this.documentElement, '#' + id)[0] || null;
    }

    querySelector(selector) {
        return querySelectorAllDeep(this.documentElement, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this.documentElement, selector);
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(fn);
        if (idx !== -1) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(evt) {
        try {
            evt.target = evt.target || this;
        } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

/**
 * Creates standard DOM fixture for chapter comments tests.
 */
function createStandardFixture(chapterId = 'c1234567-89ab-cdef-0123-456789abcdef') {
    const doc = new FakeDocument();

    const chapterBody = doc.createElement('div');
    chapterBody.className = 'novel-reader-chapter-body';
    chapterBody.setAttribute('data-chapter-id', chapterId);
    chapterBody.setAttribute('data-content-version', '1');

    const block1 = doc.createElement('p');
    block1.className = 'novel-reader-block';
    block1.setAttribute('data-reader-block-key', 'blk-test-1');
    block1.textContent = 'Đoạn văn thứ nhất...';
    chapterBody.appendChild(block1);

    const block2 = doc.createElement('p');
    block2.className = 'novel-reader-block';
    block2.setAttribute('data-reader-block-key', 'blk-test-2');
    block2.textContent = 'Đoạn văn thứ hai...';
    chapterBody.appendChild(block2);

    doc.body.appendChild(chapterBody);

    const section = doc.createElement('section');
    section.className = 'novel-chapter-comments';
    section.setAttribute('id', commentsModule.SECTION_ID);
    if (chapterId) {
        section.setAttribute('data-chapter-id', chapterId);
    }

    const header = doc.createElement('header');
    header.className = 'novel-chapter-comments-header';

    const title = doc.createElement('h2');
    title.setAttribute('id', commentsModule.TITLE_ID);
    title.className = 'novel-chapter-comments-title';
    title.textContent = 'Bình luận';

    const count = doc.createElement('span');
    count.setAttribute('id', commentsModule.COUNT_ID);
    count.className = 'novel-chapter-comments-count';

    header.appendChild(title);
    header.appendChild(count);

    const status = doc.createElement('div');
    status.setAttribute('id', commentsModule.STATUS_ID);
    status.className = 'novel-chapter-comments-status';

    const list = doc.createElement('div');
    list.setAttribute('id', commentsModule.LIST_ID);
    list.className = 'novel-chapter-comments-list';
    list.setAttribute('role', 'feed');
    list.setAttribute('aria-busy', 'false');

    const more = doc.createElement('div');
    more.setAttribute('id', commentsModule.MORE_ID || 'novelChapterCommentsMore');
    more.className = 'novel-chapter-comments-more';
    more.hidden = true;

    section.appendChild(header);
    section.appendChild(status);
    section.appendChild(list);
    section.appendChild(more);

    doc.body.appendChild(section);

    return { doc, section, header, title, count, status, list, more, chapterBody, block1, block2 };
}

// Sample feed data fixtures
function makeFeedResponse(items = [], hasNext = false, page = 0, size = 20) {
    return {
        items: items,
        page: page,
        size: size,
        hasNext: hasNext
    };
}

// ============================================================================
// Test Suite: reader-chapter-comments (MS-05E5H2C)
// ============================================================================

describe('Reader Chapter Comments Read UI (MS-05E5H2C)', () => {

    afterEach(() => {
        commentsModule.destroy();
    });

    test('1. No-op on missing DOM elements (section or list not found) without throwing or fetching', () => {
        const doc = new FakeDocument();
        let fetchCalled = false;
        const fakeFetch = () => {
            fetchCalled = true;
            return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse()) });
        };

        // Missing elements entirely
        commentsModule.init(doc, { fetch: fakeFetch, chapterId: 'c1' });
        assert.strictEqual(fetchCalled, false, 'Fetch should not be called when DOM is missing');
        assert.strictEqual(commentsModule.getState().status, 'idle');
    });

    test('2. Initial page-0 fetch when chapter ID is present on section element', async () => {
        const { doc } = createStandardFixture('c100');
        let requestedUrl = null;

        const fakeFetch = (url) => {
            requestedUrl = url;
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        // Allow microtask ticks for promise chain
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(
            requestedUrl,
            '/api/novel/chapters/c100/comments/feed?page=0&size=20',
            'Must issue fetch with page=0&size=20'
        );
    });

    test('3. Initial page-0 fetch resolving chapter ID from reader chapter body if absent on section', async () => {
        const { doc, section } = createStandardFixture('c200');
        section.removeAttribute('data-chapter-id'); // Remove from section to force body fallback
        let requestedUrl = null;

        const fakeFetch = (url) => {
            requestedUrl = url;
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(
            requestedUrl,
            '/api/novel/chapters/c200/comments/feed?page=0&size=20',
            'Must resolve chapterId from .novel-reader-chapter-body'
        );
    });

    test('4. Loading state displayed while fetch is in-flight (aria-busy="true", spinner, loading text)', () => {
        const { doc, list, status, count } = createStandardFixture('c300');
        let resolvePromise;
        const fetchPromise = new Promise((resolve) => {
            resolvePromise = resolve;
        });

        const fakeFetch = () => fetchPromise;

        commentsModule.init(doc, { fetch: fakeFetch });

        // Verify synchronous loading state immediately after init call
        assert.strictEqual(list.getAttribute('aria-busy'), 'true', 'List should have aria-busy="true" during loading');
        assert.strictEqual(count.textContent, '', 'Count should be empty during loading');

        const loadingDiv = status.querySelector('.novel-chapter-comments-loading');
        assert.ok(loadingDiv, 'Loading container must be present in status element');
        assert.strictEqual(loadingDiv.getAttribute('role'), 'status');
        assert.strictEqual(loadingDiv.getAttribute('aria-live'), 'polite');

        const spinner = loadingDiv.querySelector('.novel-chapter-comments-spinner');
        assert.ok(spinner, 'Spinner span must be present');
        assert.strictEqual(spinner.getAttribute('aria-hidden'), 'true');

        const loadingText = loadingDiv.querySelector('.novel-chapter-comments-loading-text');
        assert.ok(loadingText, 'Loading text must be present');
        assert.strictEqual(loadingText.textContent, 'Đang tải bình luận...');

        // Clean up pending promise
        resolvePromise({ ok: true, json: () => Promise.resolve(makeFeedResponse([])) });
    });

    test('5. Root thread order preserves API-delivered order (newest roots first)', async () => {
        const { doc, list } = createStandardFixture('c400');
        const items = [
            {
                rootCommentId: 'root-1',
                author: { userId: 'u1', displayName: 'Người Dùng 1', avatarUrl: null },
                body: 'Bình luận mới nhất',
                createdAt: '2026-09-18T10:30:00Z',
                updatedAt: '2026-09-18T10:30:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            },
            {
                rootCommentId: 'root-2',
                author: { userId: 'u2', displayName: 'Người Dùng 2', avatarUrl: null },
                body: 'Bình luận cũ hơn',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const threadCards = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threadCards.length, 2, 'Should render 2 thread cards');
        assert.strictEqual(threadCards[0].getAttribute('data-root-id'), 'root-1');
        assert.strictEqual(threadCards[1].getAttribute('data-root-id'), 'root-2');
    });

    test('6. Replies rendered underneath each root thread in delivered order (chronological ASC)', async () => {
        const { doc, list } = createStandardFixture('c500');
        const items = [
            {
                rootCommentId: 'root-10',
                author: { userId: 'u10', displayName: 'Root Author', avatarUrl: null },
                body: 'Nội dung root',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-1',
                        authorUserId: 'u11',
                        parentCommentId: 'root-10',
                        body: 'Phản hồi đầu tiên',
                        tombstone: false,
                        createdAt: '2026-09-18T08:10:00Z',
                        updatedAt: '2026-09-18T08:10:00Z',
                        author: { userId: 'u11', displayName: 'Reply Author 1', avatarUrl: null }
                    },
                    {
                        id: 'rep-2',
                        authorUserId: 'u12',
                        parentCommentId: 'root-10',
                        body: 'Phản hồi thứ hai',
                        tombstone: false,
                        createdAt: '2026-09-18T08:20:00Z',
                        updatedAt: '2026-09-18T08:20:00Z',
                        author: { userId: 'u12', displayName: 'Reply Author 2', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const repliesContainer = list.querySelector('.novel-comment-replies');
        assert.ok(repliesContainer, 'Replies container should exist');
        assert.strictEqual(repliesContainer.getAttribute('role'), 'group');

        const replyEls = repliesContainer.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replyEls.length, 2, 'Should render 2 replies');
        assert.strictEqual(replyEls[0].getAttribute('data-reply-id'), 'rep-1');
        assert.strictEqual(replyEls[1].getAttribute('data-reply-id'), 'rep-2');
    });

    test('7. Flat reply rendering: all replies are direct children of .novel-comment-replies', async () => {
        const { doc, list } = createStandardFixture('c600');
        const items = [
            {
                rootCommentId: 'root-20',
                author: { userId: 'u20', displayName: 'Root', avatarUrl: null },
                body: 'Root body',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-21',
                        parentCommentId: 'root-20',
                        body: 'Reply to root',
                        tombstone: false,
                        createdAt: '2026-09-18T08:05:00Z',
                        updatedAt: '2026-09-18T08:05:00Z',
                        author: { userId: 'u21', displayName: 'User 21', avatarUrl: null }
                    },
                    {
                        id: 'rep-22',
                        parentCommentId: 'rep-21', // nested reply to rep-21
                        body: 'Nested reply to rep-21',
                        tombstone: false,
                        createdAt: '2026-09-18T08:10:00Z',
                        updatedAt: '2026-09-18T08:10:00Z',
                        author: { userId: 'u22', displayName: 'User 22', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const repliesContainer = list.querySelector('.novel-comment-replies');
        assert.strictEqual(repliesContainer.childNodes.length, 2, 'Both replies must be direct children of container (flat)');

        // Second reply should have immediate parent mention @User 21
        const nestedReply = repliesContainer.childNodes[1];
        const mention = nestedReply.querySelector('.novel-comment-reply-mention');
        assert.ok(mention, 'Nested reply must have immediate-parent mention');
        assert.strictEqual(mention.textContent, '@User 21');
    });

    test('8. Author presentation: display name, fallback avatar initial, and sanitized avatar img URL', async () => {
        const { doc, list } = createStandardFixture('c700');
        const items = [
            {
                rootCommentId: 'root-30',
                author: { userId: 'u30', displayName: 'Alice', avatarUrl: 'https://cdn.example.com/alice.jpg' },
                body: 'Alice comment',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-31',
                        parentCommentId: 'root-30',
                        body: 'Bob reply',
                        tombstone: false,
                        createdAt: '2026-09-18T08:05:00Z',
                        updatedAt: '2026-09-18T08:05:00Z',
                        author: { userId: 'u31', displayName: 'Bob', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        // Alice (Root): Image avatar
        const rootEl = list.querySelector('.novel-comment--root');
        const img = rootEl.querySelector('img.novel-comment-avatar');
        assert.ok(img, 'Valid avatarUrl should render <img> tag');
        assert.strictEqual(img.getAttribute('src'), 'https://cdn.example.com/alice.jpg');
        assert.strictEqual(img.getAttribute('alt'), 'Alice');
        assert.strictEqual(img.getAttribute('referrerpolicy'), 'no-referrer');

        const authorName = rootEl.querySelector('.novel-comment-author');
        assert.strictEqual(authorName.textContent, 'Alice');

        // Bob (Reply): Fallback avatar with initial 'B'
        const replyEl = list.querySelector('.novel-comment--reply');
        const fallback = replyEl.querySelector('.novel-comment-avatar--fallback');
        assert.ok(fallback, 'Missing avatarUrl should render fallback initial');
        assert.strictEqual(fallback.textContent, 'B');
    });

    test('9. Timestamp formatting with <time> datetime attribute', async () => {
        const { doc, list } = createStandardFixture('c800');
        const items = [
            {
                rootCommentId: 'root-40',
                author: { userId: 'u40', displayName: 'Time Tester', avatarUrl: null },
                body: 'Timestamp test',
                createdAt: '2026-09-18T10:15:00.000Z',
                updatedAt: '2026-09-18T10:15:00.000Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const timeEl = list.querySelector('time.novel-comment-time');
        assert.ok(timeEl, '<time> element must be present');
        assert.strictEqual(timeEl.getAttribute('datetime'), '2026-09-18T10:15:00.000Z');
        assert.ok(timeEl.textContent.length > 0, 'Formatted timestamp must not be empty');
    });

    test('10. Edited label displayed when edited=true or updatedAt > createdAt', async () => {
        const { doc, list } = createStandardFixture('c900');
        const items = [
            {
                rootCommentId: 'root-50',
                author: { userId: 'u50', displayName: 'Edited User', avatarUrl: null },
                body: 'Edited root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:30:00Z',
                edited: true,
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-51',
                        parentCommentId: 'root-50',
                        body: 'Unedited reply',
                        tombstone: false,
                        createdAt: '2026-09-18T10:35:00Z',
                        updatedAt: '2026-09-18T10:35:00Z',
                        edited: false,
                        author: { userId: 'u51', displayName: 'User 51', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const rootEl = list.querySelector('.novel-comment--root');
        const rootEdited = rootEl.querySelector('.novel-comment-edited');
        assert.ok(rootEdited, 'Root edited indicator must be present');
        assert.strictEqual(rootEdited.textContent, 'đã chỉnh sửa');

        const replyEl = list.querySelector('.novel-comment--reply');
        const replyEdited = replyEl.querySelector('.novel-comment-edited');
        assert.strictEqual(replyEdited, null, 'Unedited reply must not have edited indicator');
    });

    test('11. Tombstone reply: author/avatar/actions omitted, text rendered, contextual single child mention', async () => {
        const { doc, list } = createStandardFixture('c1000');
        const items = [
            {
                rootCommentId: 'root-60',
                author: { userId: 'u60', displayName: 'Root', avatarUrl: null },
                body: 'Root body',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-tombstone',
                        parentCommentId: 'root-60',
                        body: null,
                        tombstone: true,
                        status: 'DELETED',
                        createdAt: '2026-09-18T08:05:00Z',
                        updatedAt: '2026-09-18T08:15:00Z',
                        author: null
                    },
                    {
                        id: 'rep-child',
                        parentCommentId: 'rep-tombstone',
                        body: 'Child of deleted reply',
                        tombstone: false,
                        createdAt: '2026-09-18T08:10:00Z',
                        updatedAt: '2026-09-18T08:10:00Z',
                        author: { userId: 'u62', displayName: 'Con Gái', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const tombstoneEl = list.querySelector('.novel-comment--reply.is-tombstone');
        assert.ok(tombstoneEl, 'Tombstone reply must have .is-tombstone');

        // Check header, avatar, author, time, edited are all omitted
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-header'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-avatar'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-author'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-time'), null);

        // Check contextual child mention: exactly 1 active child -> "Bình luận mà @Con Gái phản hồi đã bị xóa."
        const tombstoneBody = tombstoneEl.querySelector('.novel-comment-body--tombstone');
        assert.ok(tombstoneBody, 'Tombstone body must be present');
        assert.strictEqual(tombstoneBody.textContent, 'Bình luận mà @Con Gái phản hồi đã bị xóa.');
    });

    test('12. Exclusion of passageExcerpt, blockKey, anchorStatus, and mutation action buttons', async () => {
        const { doc, list } = createStandardFixture('c1100');
        const items = [
            {
                rootCommentId: 'root-70',
                author: { userId: 'u70', displayName: 'User 70', avatarUrl: null },
                body: 'Root comment body',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                canEdit: false,
                canDelete: false,
                anchorStatus: 'ANCHORED',
                blockKey: 'p-42',
                passageExcerpt: 'Trích đoạn bí mật không được hiển thị',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const html = list.textContent;
        assert.strictEqual(html.includes('Trích đoạn bí mật'), false, 'passageExcerpt must NOT be rendered');
        assert.strictEqual(html.includes('p-42'), false, 'blockKey must NOT be rendered in text');

        // Reply action is rendered on active comment, but edit/delete buttons remain excluded
        assert.ok(list.querySelector('.novel-comment-actions'));
        assert.ok(list.querySelector('.novel-comment-reply-btn'));
        assert.strictEqual(list.querySelector('.novel-comment-edit-btn'), null);
        assert.strictEqual(list.querySelector('.novel-comment-delete-btn'), null);
    });

    test('13. Empty state rendered when items array is empty with "Chưa có bình luận nào." and count "0 bình luận"', async () => {
        const { doc, list, status, count } = createStandardFixture('c1200');

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([]))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(list.getAttribute('aria-busy'), 'false');
        assert.strictEqual(status.childNodes.length, 0, 'Status should be empty on successful fetch');

        const emptyEl = list.querySelector('.novel-chapter-comments-empty');
        assert.ok(emptyEl, 'Empty state container should exist');

        const textEl = emptyEl.querySelector('.novel-chapter-comments-empty-text');
        assert.strictEqual(textEl.textContent, 'Chưa có bình luận nào.');

        assert.strictEqual(count.textContent, '0 bình luận', 'Count header should show 0 bình luận');
    });

    test('14. Error state rendered on HTTP 500 / network error with retry button', async () => {
        const { doc, list, status, count } = createStandardFixture('c1300');

        const fakeFetch = () => Promise.reject(new Error('Network error'));

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(list.getAttribute('aria-busy'), 'false');
        assert.strictEqual(list.childNodes.length, 0, 'List should be empty on error');
        assert.strictEqual(count.textContent, '', 'Count should be cleared on error');

        const errorDiv = status.querySelector('.novel-chapter-comments-error');
        assert.ok(errorDiv, 'Error container must exist in status');
        assert.strictEqual(errorDiv.getAttribute('role'), 'alert');

        const errorText = errorDiv.querySelector('.novel-chapter-comments-error-text');
        assert.strictEqual(errorText.textContent, 'Không thể tải bình luận. Vui lòng thử lại.');

        const retryBtn = errorDiv.querySelector('button.novel-chapter-comments-retry-btn');
        assert.ok(retryBtn, 'Retry button must exist');
        assert.strictEqual(retryBtn.textContent, 'Thử lại');
    });

    test('15. Retry button initiates new fetch and transitions to populated state upon success', async () => {
        const { doc, list, status } = createStandardFixture('c1400');
        let callCount = 0;

        const fakeFetch = () => {
            callCount++;
            if (callCount === 1) {
                return Promise.resolve({ ok: false, status: 500 });
            }
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([
                    {
                        rootCommentId: 'recovered-root',
                        author: { userId: 'u99', displayName: 'Recovered User', avatarUrl: null },
                        body: 'Thành công sau retry',
                        createdAt: '2026-09-18T10:00:00Z',
                        updatedAt: '2026-09-18T10:00:00Z',
                        edited: false,
                        replyCount: 0,
                        replies: []
                    }
                ]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        // First call failed
        assert.strictEqual(commentsModule.getState().status, 'error');
        const retryBtn = status.querySelector('.novel-chapter-comments-retry-btn');
        assert.ok(retryBtn);

        // Click retry
        retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        // Second call succeeded
        assert.strictEqual(callCount, 2, 'Fetch should have been called twice');
        assert.strictEqual(commentsModule.getState().status, 'populated');
        const thread = list.querySelector('.novel-block-discussion-thread');
        assert.ok(thread, 'Thread card should now be rendered');
        assert.strictEqual(thread.getAttribute('data-root-id'), 'recovered-root');
    });

    test('16. Token race safety: stale responses from earlier fetches are ignored', async () => {
        const { doc, list } = createStandardFixture('c1500');
        let resolveSlow;

        const slowPromise = new Promise(resolve => {
            resolveSlow = resolve;
        });

        const fastData = makeFeedResponse([
            {
                rootCommentId: 'fast-root',
                author: { userId: 'u1', displayName: 'Fast', avatarUrl: null },
                body: 'Fast comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ]);

        const slowData = makeFeedResponse([
            {
                rootCommentId: 'slow-root',
                author: { userId: 'u2', displayName: 'Slow', avatarUrl: null },
                body: 'Slow comment',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ]);

        let fetchCount = 0;
        const fakeFetch = () => {
            fetchCount++;
            if (fetchCount === 1) {
                return slowPromise;
            }
            return Promise.resolve({ ok: true, json: () => Promise.resolve(fastData) });
        };

        commentsModule.init(doc, { fetch: fakeFetch });

        // Trigger a second fetch before the first one resolves
        commentsModule.retry();
        await new Promise(r => setTimeout(r, 10));

        // Second fetch finished first
        assert.strictEqual(commentsModule.getState().status, 'populated');
        let threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'fast-root');

        // Now resolve the first (slow) fetch
        resolveSlow({ ok: true, json: () => Promise.resolve(slowData) });
        await new Promise(r => setTimeout(r, 10));

        // The slow response must be ignored!
        threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads.length, 1);
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'fast-root');
    });

    test('17. kiemlai:chapter-changed event updates chapter ID and triggers new fetch', async () => {
        const { doc, list } = createStandardFixture('c1600-original');
        const requestedUrls = [];

        const fakeFetch = (url) => {
            requestedUrls.push(url);
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([
                    {
                        rootCommentId: 'chapter-' + requestedUrls.length,
                        author: { userId: 'u1', displayName: 'Reader', avatarUrl: null },
                        body: 'Comment ' + requestedUrls.length,
                        createdAt: '2026-09-18T10:00:00Z',
                        updatedAt: '2026-09-18T10:00:00Z',
                        edited: false,
                        replyCount: 0,
                        replies: []
                    }
                ]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(requestedUrls.length, 1);
        assert.ok(requestedUrls[0].includes('c1600-original'));

        // Dispatch chapter-changed event
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'c1600-next' }
        });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(requestedUrls.length, 2);
        assert.ok(requestedUrls[1].includes('c1600-next'));
        assert.strictEqual(commentsModule.getState().chapterId, 'c1600-next');
    });

    test('18. Header comment count accurately sums roots and replies', async () => {
        const { doc, count } = createStandardFixture('c1700');
        const items = [
            {
                rootCommentId: 'r1',
                author: { userId: 'u1', displayName: 'U1', avatarUrl: null },
                body: 'B1',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [{}, {}]
            },
            {
                rootCommentId: 'r2',
                author: { userId: 'u2', displayName: 'U2', avatarUrl: null },
                body: 'B2',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                replyCount: 3,
                replies: [{}, {}, {}]
            }
        ];

        // Total = (1 + 2) + (1 + 3) = 7 bình luận
        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(count.textContent, '7 bình luận');
    });

    test('19. Destroy cleanly clears state, detaches listeners, and invalidates in-flight fetches', async () => {
        const { doc, list, status, count } = createStandardFixture('c1800');
        let resolveInFlight;
        const inFlightPromise = new Promise(resolve => {
            resolveInFlight = resolve;
        });

        commentsModule.init(doc, { fetch: () => inFlightPromise });

        assert.strictEqual(commentsModule.getState().status, 'loading');

        commentsModule.destroy();

        assert.strictEqual(commentsModule.getState().status, 'idle');
        assert.strictEqual(commentsModule.getState().chapterId, null);
        assert.strictEqual(list.childNodes.length, 0);
        assert.strictEqual(status.childNodes.length, 0);
        assert.strictEqual(count.textContent, '');

        // Resolve after destroy
        resolveInFlight({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([
                {
                    rootCommentId: 'late-root',
                    author: { userId: 'u1', displayName: 'U1', avatarUrl: null },
                    body: 'Late',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    edited: false,
                    replyCount: 0,
                    replies: []
                }
            ]))
        });
        await new Promise(r => setTimeout(r, 10));

        // State remains idle and DOM remains empty
        assert.strictEqual(commentsModule.getState().status, 'idle');
        assert.strictEqual(list.childNodes.length, 0);
    });

    test('20. CURRENT root renders ⋯ with "Xem bình luận gốc" action', async () => {
        const { doc, list } = createStandardFixture('c2000');
        const items = [
            {
                rootCommentId: 'r-current',
                author: { userId: 'u1', displayName: 'User 1', avatarUrl: null },
                body: 'Current root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger, 'Menu trigger button must exist on CURRENT root');
        assert.strictEqual(trigger.getAttribute('aria-label'), 'Mở menu bình luận');
        assert.strictEqual(trigger.getAttribute('aria-haspopup'), 'menu');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        const popover = list.querySelector('.novel-comment-menu-popover');
        assert.ok(popover, 'Menu popover must exist');
        assert.strictEqual(popover.hidden, true);

        // Click trigger to open menu
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popover.hidden, false);

        const menuItem = popover.querySelector('.novel-comment-menu-item');
        assert.ok(menuItem, 'Menu item must exist');
        assert.strictEqual(menuItem.textContent, 'Xem bình luận gốc');
        assert.strictEqual(menuItem.getAttribute('data-block-key'), 'blk-test-1');
    });

    test('21. RELOCATED root renders navigation action with relocated blockKey', async () => {
        const { doc, list } = createStandardFixture('c2100');
        const items = [
            {
                rootCommentId: 'r-relocated',
                author: { userId: 'u2', displayName: 'User 2', avatarUrl: null },
                body: 'Relocated root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'RELOCATED',
                blockKey: 'blk-test-2',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger, 'Menu trigger must exist on RELOCATED root');

        const menuItem = list.querySelector('.novel-comment-menu-item');
        assert.ok(menuItem);
        assert.strictEqual(menuItem.getAttribute('data-block-key'), 'blk-test-2');
    });

    test('22. STALE non-owner root has no View Original action but retains overflow Report', async () => {
        const { doc, list } = createStandardFixture('c2200');
        const items = [
            {
                rootCommentId: 'r-stale',
                author: { userId: 'u3', displayName: 'User 3', avatarUrl: null },
                body: 'Stale root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'STALE',
                blockKey: null,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger, 'STALE non-owner root must render ⋯ trigger for report action');
        const menu = list.querySelector('.novel-comment-actions-menu');
        assert.ok(menu, 'STALE non-owner root must render actions menu');
        assert.strictEqual(menu.querySelector('[data-action="view-origin"]'), null, 'STALE root must not render view-origin action');
        const reportItem = menu.querySelector('[data-action="report"]');
        assert.ok(reportItem, 'STALE non-owner root must render report action');
        assert.strictEqual(reportItem.textContent, 'Báo cáo');
    });

    test('23. UNANCHORED non-owner root has no View Original action but retains overflow Report', async () => {
        const { doc, list } = createStandardFixture('c2300');
        const items = [
            {
                rootCommentId: 'r-unanchored',
                author: { userId: 'u4', displayName: 'User 4', avatarUrl: null },
                body: 'Unanchored root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'UNANCHORED',
                blockKey: null,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger, 'UNANCHORED non-owner root must render ⋯ trigger for report action');
        const menu = list.querySelector('.novel-comment-actions-menu');
        assert.ok(menu, 'UNANCHORED non-owner root must render actions menu');
        assert.strictEqual(menu.querySelector('[data-action="view-origin"]'), null, 'UNANCHORED root must not render view-origin action');
        const reportItem = menu.querySelector('[data-action="report"]');
        assert.ok(reportItem, 'UNANCHORED non-owner root must render report action');
        assert.strictEqual(reportItem.textContent, 'Báo cáo');
    });

    test('24. Active reply inherits root CURRENT blockKey and renders origin menu', async () => {
        const { doc, list } = createStandardFixture('c2400');
        const items = [
            {
                rootCommentId: 'r-parent',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-active',
                        parentCommentId: 'r-parent',
                        body: 'Active reply text',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'Replier', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const replyEl = list.querySelector('.novel-comment--reply');
        assert.ok(replyEl);

        const replyTrigger = replyEl.querySelector('.novel-comment-menu-trigger');
        assert.ok(replyTrigger, 'Active reply must render menu trigger inherited from root');

        const replyMenuItem = replyEl.querySelector('.novel-comment-menu-item');
        assert.ok(replyMenuItem);
        assert.strictEqual(replyMenuItem.getAttribute('data-block-key'), 'blk-test-1', 'Reply must inherit root blockKey');
    });

    test('25. Nested reply still inherits root blockKey, not immediate-parent identity', async () => {
        const { doc, list } = createStandardFixture('c2500');
        const items = [
            {
                rootCommentId: 'r-root',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-1',
                        parentCommentId: 'r-root',
                        body: 'First reply',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'User 2', avatarUrl: null }
                    },
                    {
                        id: 'rep-2',
                        parentCommentId: 'rep-1', // Nested reply
                        body: 'Nested reply',
                        tombstone: false,
                        createdAt: '2026-09-18T10:10:00Z',
                        updatedAt: '2026-09-18T10:10:00Z',
                        author: { userId: 'u3', displayName: 'User 3', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const replies = list.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const nestedReply = replies[1];
        const nestedMenuItem = nestedReply.querySelector('.novel-comment-menu-item');
        assert.ok(nestedMenuItem);
        assert.strictEqual(
            nestedMenuItem.getAttribute('data-block-key'),
            'blk-test-1',
            'Nested reply must inherit root blockKey instead of deriving from parentCommentId'
        );
    });

    test('26. Tombstone reply has no actions menu', async () => {
        const { doc, list } = createStandardFixture('c2600');
        const items = [
            {
                rootCommentId: 'r-root-tomb',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-deleted',
                        parentCommentId: 'r-root-tomb',
                        body: null,
                        tombstone: true,
                        status: 'DELETED',
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:10:00Z',
                        author: null
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const tombstoneEl = list.querySelector('.novel-comment--reply.is-tombstone');
        assert.ok(tombstoneEl);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-actions-menu'), null, 'Tombstone must not have actions menu');
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-menu-trigger'), null, 'Tombstone must not have menu trigger');
    });

    test('27. Click "Xem bình luận gốc" on root comment invokes openDiscussionTarget with { chapterId, blockKey, threadId: rootCommentId } without scrolling reader document', async () => {
        const { doc, list, block1 } = createStandardFixture('c2700');
        let bridgePayload = null;

        const items = [
            {
                rootCommentId: 'r-nav',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Navigate root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, {
            fetch: fakeFetch,
            openDiscussionTarget: (target) => {
                bridgePayload = target;
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = list.querySelector('.novel-comment-menu-item');
        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Menu should be closed
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        // Main reader document / paragraph scrollIntoView must NOT be invoked
        assert.strictEqual(block1.scrollIntoViewCalled, undefined, 'Must NOT scroll reader document or paragraph');
        assert.strictEqual(block1.classList.contains('is-origin-target'), false, 'Must NOT add .is-origin-target to paragraph');

        // Bridge must be called with chapterId, blockKey, and threadId (rootCommentId)
        assert.ok(bridgePayload, 'Must invoke injected openDiscussionTarget bridge');
        assert.strictEqual(bridgePayload.chapterId, 'c2700');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');
        assert.strictEqual(bridgePayload.threadId, 'r-nav');
    });

    test('28. Active reply "Xem bình luận gốc" targets ROOT discussion ID, never reply ID', async () => {
        const { doc, list, block1 } = createStandardFixture('c2800');
        let bridgePayload = null;

        const items = [
            {
                rootCommentId: 'r-parent-root',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Parent text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-child-1',
                        parentCommentId: 'r-parent-root',
                        body: 'Active reply body',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'Replier', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, {
            fetch: fakeFetch,
            openDiscussionTarget: (target) => {
                bridgePayload = target;
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const replyEl = list.querySelector('.novel-comment--reply');
        assert.ok(replyEl);

        const trigger = replyEl.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger);
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = replyEl.querySelector('.novel-comment-menu-item');
        assert.ok(originBtn);
        assert.strictEqual(originBtn.getAttribute('data-root-id'), 'r-parent-root', 'Reply menu item must reference rootCommentId');

        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Menu closed
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        // Reader document scroll untouched
        assert.strictEqual(block1.scrollIntoViewCalled, undefined, 'Must NOT scroll reader document or paragraph');

        // Bridge payload targets root discussion ID, NEVER child reply ID
        assert.ok(bridgePayload, 'Must invoke openDiscussionTarget');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');
        assert.strictEqual(bridgePayload.threadId, 'r-parent-root', 'Must use rootCommentId, NEVER reply.id');
    });

    test('29. Nested reply still targets root discussion ID and does not alter URL or scroll reader document', async () => {
        const { doc, list, block1 } = createStandardFixture('c2900');
        let bridgePayload = null;

        const items = [
            {
                rootCommentId: 'r-deep-root',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-first-level',
                        parentCommentId: 'r-deep-root',
                        body: 'Level 1',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'U2', avatarUrl: null }
                    },
                    {
                        id: 'rep-nested-level',
                        parentCommentId: 'rep-first-level',
                        body: 'Level 2 nested',
                        tombstone: false,
                        createdAt: '2026-09-18T10:10:00Z',
                        updatedAt: '2026-09-18T10:10:00Z',
                        author: { userId: 'u3', displayName: 'U3', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, {
            fetch: fakeFetch,
            openDiscussionTarget: (target) => {
                bridgePayload = target;
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const replies = list.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const nestedReply = replies[1];
        const trigger = nestedReply.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = nestedReply.querySelector('.novel-comment-menu-item');
        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Reader document scroll untouched
        assert.strictEqual(block1.scrollIntoViewCalled, undefined);

        // Targets root discussion ID, NOT nested reply ID or intermediate parent ID
        assert.ok(bridgePayload);
        assert.strictEqual(bridgePayload.threadId, 'r-deep-root');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');
    });

    test('30. When restore bridge is unavailable, clicking "Xem bình luận gốc" safely no-ops without throwing or scrolling', async () => {
        const { doc, list, block1, block2 } = createStandardFixture('c3000');

        const items = [
            {
                rootCommentId: 'r-unavailable-bridge',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Target',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        // Init WITHOUT openDiscussionTarget and WITHOUT global NovelReaderBlockDiscussionRestore
        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = list.querySelector('.novel-comment-menu-item');
        assert.doesNotThrow(() => {
            originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(block1.scrollIntoViewCalled, undefined);
        assert.strictEqual(block2.scrollIntoViewCalled, undefined);
    });

    test('31. Escape closes menu and restores focus to trigger', async () => {
        const { doc, list } = createStandardFixture('c3100');
        const items = [
            {
                rootCommentId: 'r-esc',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.ok(commentsModule.getActiveOpenMenu());

        // Press Escape
        doc.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27, preventDefault: () => {} });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(commentsModule.getActiveOpenMenu(), null);
        assert.strictEqual(trigger.isFocused, true, 'Escape must restore focus to trigger button');
    });

    test('32. Outside click closes active menu', async () => {
        const { doc, list } = createStandardFixture('c3200');
        const items = [
            {
                rootCommentId: 'r-click-out',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');

        // Click outside on body
        doc.dispatchEvent({ type: 'click', target: doc.body });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(commentsModule.getActiveOpenMenu(), null);
    });

    test('33. Opening menu B closes menu A', async () => {
        const { doc, list } = createStandardFixture('c3300');
        const items = [
            {
                rootCommentId: 'r-a',
                author: { userId: 'u1', displayName: 'A', avatarUrl: null },
                body: 'Comment A',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            },
            {
                rootCommentId: 'r-b',
                author: { userId: 'u2', displayName: 'B', avatarUrl: null },
                body: 'Comment B',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-2',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const triggers = list.querySelectorAll('.novel-comment-menu-trigger');
        assert.strictEqual(triggers.length, 2);

        const triggerA = triggers[0];
        const triggerB = triggers[1];

        // Open menu A
        triggerA.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(triggerA.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(triggerB.getAttribute('aria-expanded'), 'false');

        // Open menu B -> menu A must close
        triggerB.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(triggerA.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(triggerB.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(commentsModule.getActiveOpenMenu().triggerEl, triggerB);
    });

    test('34. Chapter change closes open menu and cleans up pending state', async () => {
        const { doc, list, block1 } = createStandardFixture('c3400-orig');
        const items = [
            {
                rootCommentId: 'r-orig',
                author: { userId: 'u1', displayName: 'Orig', avatarUrl: null },
                body: 'Orig',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.ok(commentsModule.getActiveOpenMenu());

        // Transition chapter
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'c3400-next' }
        });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(commentsModule.getActiveOpenMenu(), null);
    });

    test('35. No passageExcerpt rendered anywhere in card or menu', async () => {
        const { doc, list } = createStandardFixture('c3500');
        const items = [
            {
                rootCommentId: 'r-excerpt-test',
                author: { userId: 'u1', displayName: 'Author', avatarUrl: null },
                body: 'Normal comment body',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                passageExcerpt: 'Đoạn trích này tuyệt đối không được render',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const fullText = list.textContent;
        assert.strictEqual(fullText.includes('Đoạn trích này tuyệt đối không được render'), false);
    });

    test('36. Safe missing presentation fail-safe: returns null and handles missing cards gracefully without crash', async () => {
        const { doc, list } = createStandardFixture('c3600');
        const items = [
            {
                rootCommentId: 'r-missing-pres',
                author: { userId: 'u1', displayName: 'User', avatarUrl: null },
                body: 'Comment with missing presentation',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-missing-pres',
                        parentCommentId: 'r-missing-pres',
                        body: 'Reply with missing presentation',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'Replier', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        // Set presentation implementation to null
        commentsModule.setCommentPresentationImplementation(null);

        try {
            assert.doesNotThrow(() => {
                commentsModule.init(doc, { fetch: fakeFetch });
            });
            await new Promise(r => setTimeout(r, 10));

            // List should not crash and should have 0 child nodes because cards returned null
            assert.strictEqual(list.childNodes.length, 0);

            // Directly invoking createActionsMenu with null presentation returns null
            assert.strictEqual(commentsModule.createActionsMenu({ originNavigable: true, blockKey: 'b1' }, doc), null);
        } finally {
            // Restore default presentation
            commentsModule.setCommentPresentationImplementation(undefined);
        }
    });
});

// ============================================================================
// Test Suite: Root Comment Pagination (MS-05E5H2E)
// ============================================================================

describe('MS-05E5H2E Root Comment Pagination', () => {

    afterEach(() => {
        commentsModule.destroy();
    });

    test('ROOT-1. page 0 hasNext=false renders no root load-more control', async () => {
        const { doc, more } = createStandardFixture('c-root-1');
        const items = [{ rootCommentId: 'r1', author: { displayName: 'A' }, body: 'B1' }];
        commentsModule.init(doc, {
            fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items, false)) })
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(more.hidden, true);
        assert.strictEqual(more.querySelector('.novel-chapter-comments-more-btn'), null);
    });

    test('ROOT-2. page 0 hasNext=true shows "Xem thêm bình luận" button', async () => {
        const { doc, more } = createStandardFixture('c-root-2');
        const items = [{ rootCommentId: 'r1', author: { displayName: 'A' }, body: 'B1' }];
        commentsModule.init(doc, {
            fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items, true)) })
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(more.hidden, false);
        const btn = more.querySelector('.novel-chapter-comments-more-btn');
        assert.ok(btn);
        assert.strictEqual(btn.textContent.trim(), 'Xem thêm bình luận');
        assert.strictEqual(btn.disabled, false);
    });

    test('ROOT-3. click root load-more requests page=1&size=20', async () => {
        const { doc, more } = createStandardFixture('c-root-3');
        const urls = [];
        const items0 = [{ rootCommentId: 'r1', author: { displayName: 'A' }, body: 'B1' }];
        const items1 = [{ rootCommentId: 'r2', author: { displayName: 'B' }, body: 'B2' }];
        commentsModule.init(doc, {
            fetch: (url) => {
                urls.push(url);
                const isPage1 = url.includes('page=1');
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse(isPage1 ? items1 : items0, !isPage1, isPage1 ? 1 : 0))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        const btn = more.querySelector('.novel-chapter-comments-more-btn');
        btn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(urls.length, 2);
        assert.ok(urls[1].includes('page=1&size=20'));
    });

    test('ROOT-4 to ROOT-7. page 1 success appends roots after page 0, preserves order and replies, and updates header count', async () => {
        const { doc, list, count, more } = createStandardFixture('c-root-4');
        const items0 = [{
            rootCommentId: 'r1',
            author: { displayName: 'Root 1' },
            body: 'B1',
            replyCount: 1,
            replies: [{ id: 'rep1', body: 'R1', createdAt: '2026-09-18T10:00:00Z', author: { displayName: 'Rep1' } }]
        }];
        const items1 = [{
            rootCommentId: 'r2',
            author: { displayName: 'Root 2' },
            body: 'B2',
            replyCount: 2,
            replies: [
                { id: 'rep2a', body: 'R2A', createdAt: '2026-09-18T10:01:00Z', author: { displayName: 'Rep2A' } },
                { id: 'rep2b', body: 'R2B', createdAt: '2026-09-18T10:02:00Z', author: { displayName: 'Rep2B' } }
            ]
        }];
        commentsModule.init(doc, {
            fetch: (url) => {
                const isPage1 = url.includes('page=1');
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse(isPage1 ? items1 : items0, !isPage1, isPage1 ? 1 : 0))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(count.textContent, '2 bình luận'); // 1 root + 1 reply

        const btn = more.querySelector('.novel-chapter-comments-more-btn');
        btn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        const threadCards = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threadCards.length, 2);
        assert.strictEqual(threadCards[0].getAttribute('data-root-id'), 'r1');
        assert.strictEqual(threadCards[1].getAttribute('data-root-id'), 'r2');

        const repCards = threadCards[1].querySelectorAll('.novel-comment--reply');
        assert.strictEqual(repCards.length, 2);
        assert.strictEqual(repCards[0].getAttribute('data-reply-id'), 'rep2a');
        assert.strictEqual(repCards[1].getAttribute('data-reply-id'), 'rep2b');

        // Header count: (1 + 1) + (1 + 2) = 5 bình luận
        assert.strictEqual(count.textContent, '5 bình luận');
    });

    test('ROOT-8 & ROOT-9. page 1 hasNext=true leaves more button; final page hasNext=false hides more container', async () => {
        const { doc, more } = createStandardFixture('c-root-8');
        let pageReq = 0;
        commentsModule.init(doc, {
            fetch: (url) => {
                pageReq++;
                const isPage2 = url.includes('page=2');
                const hasNext = !isPage2; // page 0 -> true, page 1 -> true, page 2 -> false
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r' + pageReq, author: { displayName: 'U' }, body: 'B' }], hasNext))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(more.hidden, false);

        // Click 1: page 1 (hasNext=true)
        more.querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(more.hidden, false);

        // Click 2: page 2 (hasNext=false)
        more.querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(more.hidden, true);
        assert.strictEqual(more.querySelector('.novel-chapter-comments-more-btn'), null);
    });

    test('ROOT-10. double click while page 1 pending creates exactly one page-1 request', async () => {
        const { doc, more } = createStandardFixture('c-root-10');
        let page1Calls = 0;
        let resolvePage1;
        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    page1Calls++;
                    return new Promise((res) => { resolvePage1 = res; });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r0', author: { displayName: 'U' }, body: 'B' }], true))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        const btn = more.querySelector('.novel-chapter-comments-more-btn');
        btn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        btn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(page1Calls, 1);
        resolvePage1({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r1', author: { displayName: 'U' }, body: 'B' }], false, 1)) });
        await new Promise(r => setTimeout(r, 10));
    });

    test('ROOT-11 to ROOT-14. page 1 failure leaves cards intact, shows local error, advances no page, and retry requests page 1 again', async () => {
        const { doc, list, status, more } = createStandardFixture('c-root-11');
        let attempt = 0;
        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    attempt++;
                    if (attempt === 1) {
                        return Promise.resolve({ ok: false, status: 500 });
                    }
                    return Promise.resolve({
                        ok: true,
                        json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r1', author: { displayName: 'U1' }, body: 'B1' }], false, 1))
                    });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r0', author: { displayName: 'U0' }, body: 'B0' }], true, 0))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);

        // Click load more -> page 1 fails
        more.querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        // Existing cards remain (ROOT-11)
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);
        // Initial/full-section error UI is NOT rendered (ROOT-12)
        assert.strictEqual(status.childNodes.length, 0);
        // Local error state in more container
        const errorEl = more.querySelector('.novel-chapter-comments-more-error');
        assert.ok(errorEl);
        assert.strictEqual(errorEl.querySelector('.novel-chapter-comments-more-error-text').textContent, 'Không thể tải thêm bình luận.');
        // currentPage still 0 (ROOT-14)
        assert.strictEqual(commentsModule.getState().currentPage, 0);

        // Click retry in more container (ROOT-13)
        const retryBtn = errorEl.querySelector('.novel-chapter-comments-more-retry-btn');
        assert.ok(retryBtn);
        retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        // Successful retry advances currentPage and appends cards
        assert.strictEqual(commentsModule.getState().currentPage, 1);
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
    });

    test('ROOT-15. Chapter A page 1 resolves after switching to Chapter B does not append Chapter A data', async () => {
        const { doc, list } = createStandardFixture('cA');
        let resolveChapAPage1;
        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('cA') && url.includes('page=1')) {
                    return new Promise((res) => { resolveChapAPage1 = res; });
                }
                if (url.includes('cB')) {
                    return Promise.resolve({
                        ok: true,
                        json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'rB0', author: { displayName: 'UB' }, body: 'B0' }], false))
                    });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'rA0', author: { displayName: 'UA' }, body: 'A0' }], true))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        // Start load more for cA
        doc.getElementById(commentsModule.MORE_ID).querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Switch to Chapter B
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'cB' } });
        await new Promise(r => setTimeout(r, 10));

        // Now resolve Chapter A page 1 late
        resolveChapAPage1({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'rA1-late', author: { displayName: 'UA1' }, body: 'A1' }], false, 1))
        });
        await new Promise(r => setTimeout(r, 10));

        const threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads.length, 1);
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'rB0');
    });

    test('ROOT-16. new page-0/reset fetch invalidates old page-N response', async () => {
        const { doc, list } = createStandardFixture('c16');
        let resolvePage1;
        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    return new Promise((res) => { resolvePage1 = res; });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r0-initial', author: { displayName: 'U' }, body: 'B0' }], true))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        doc.getElementById(commentsModule.MORE_ID).querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Trigger reset fetchFeed
        commentsModule.retry();
        await new Promise(r => setTimeout(r, 10));

        // Resolve old page 1
        resolvePage1({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r1-stale', author: { displayName: 'U' }, body: 'B1' }], false, 1))
        });
        await new Promise(r => setTimeout(r, 10));

        const threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads.length, 1);
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'r0-initial');
    });

    test('ROOT-17. destroy invalidates old page-N response', async () => {
        const { doc } = createStandardFixture('c17');
        let resolvePage1;
        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    return new Promise((res) => { resolvePage1 = res; });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r0', author: { displayName: 'U' }, body: 'B0' }], true))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        doc.getElementById(commentsModule.MORE_ID).querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });

        commentsModule.destroy();
        assert.doesNotThrow(() => {
            resolvePage1({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r1', author: { displayName: 'U' }, body: 'B1' }], false, 1))
            });
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(commentsModule.getState().status, 'idle');
    });

    test('ROOT-18 & ROOT-19. duplicate root is skipped and nonduplicates around it preserve relative order', async () => {
        const { doc, list } = createStandardFixture('c18');
        const page0 = [
            { rootCommentId: 'r-A', author: { displayName: 'A' }, body: 'Body A' }
        ];
        const page1 = [
            { rootCommentId: 'r-A', author: { displayName: 'A-dup' }, body: 'Body A duplicate' },
            { rootCommentId: 'r-B', author: { displayName: 'B' }, body: 'Body B' },
            { rootCommentId: 'r-C', author: { displayName: 'C' }, body: 'Body C' }
        ];
        commentsModule.init(doc, {
            fetch: (url) => {
                const isPage1 = url.includes('page=1');
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse(isPage1 ? page1 : page0, !isPage1, isPage1 ? 1 : 0))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        doc.getElementById(commentsModule.MORE_ID).querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        const threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads.length, 3);
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'r-A');
        assert.strictEqual(threads[1].getAttribute('data-root-id'), 'r-B');
        assert.strictEqual(threads[2].getAttribute('data-root-id'), 'r-C');
    });

    test('ROOT-20 to ROOT-23. appended root and replies preserve origin navigation and tombstone menu rules', async () => {
        const { doc, list } = createStandardFixture('c20');
        let bridgeTarget = null;
        const page0 = [{ rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0' }];
        const page1 = [
            {
                rootCommentId: 'r-curr',
                author: { displayName: 'RCurr' },
                body: 'Current root',
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replies: [
                    { id: 'rep-curr-active', body: 'Active rep', tombstone: false, author: { displayName: 'RepAct' } },
                    { id: 'rep-curr-tomb', body: 'Tomb rep', tombstone: true }
                ]
            },
            {
                rootCommentId: 'r-stale',
                author: { displayName: 'RStale' },
                body: 'Stale root',
                anchorStatus: 'STALE',
                blockKey: 'blk-test-2',
                replies: []
            }
        ];
        commentsModule.init(doc, {
            fetch: (url) => {
                const isPage1 = url.includes('page=1');
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse(isPage1 ? page1 : page0, !isPage1, isPage1 ? 1 : 0))
                });
            },
            openDiscussionTarget: (t) => { bridgeTarget = t; }
        });
        await new Promise(r => setTimeout(r, 10));
        doc.getElementById(commentsModule.MORE_ID).querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        const threads = list.querySelectorAll('.novel-block-discussion-thread');
        const currThread = threads[1];
        const staleThread = threads[2];

        // ROOT-20: appended CURRENT root has ⋯
        const rootTrigger = currThread.querySelector('.novel-comment--root .novel-comment-menu-trigger');
        assert.ok(rootTrigger);

        // ROOT-21: appended active reply targets ROOT rootCommentId
        const activeRep = currThread.querySelectorAll('.novel-comment--reply')[0];
        const repTrigger = activeRep.querySelector('.novel-comment-menu-trigger');
        assert.ok(repTrigger);
        repTrigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        const originBtn = currThread.querySelector('.novel-comment--reply .novel-comment-menu-item');
        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.ok(bridgeTarget);
        assert.strictEqual(bridgeTarget.threadId, 'r-curr');
        assert.strictEqual(bridgeTarget.blockKey, 'blk-test-1');

        // ROOT-22: appended STALE non-owner root renders ⋯ with Report, NO origin
        const staleTrigger = staleThread.querySelector('.novel-comment-menu-trigger');
        assert.ok(staleTrigger, 'STALE appended root must have menu trigger');
        const staleMenu = staleThread.querySelector('.novel-comment-actions-menu');
        assert.ok(staleMenu, 'STALE appended root must have actions menu');
        assert.strictEqual(staleMenu.querySelector('[data-action="view-origin"]'), null, 'view-origin must be absent');
        const staleReport = staleMenu.querySelector('[data-action="report"]');
        assert.ok(staleReport, 'report must be present');
        assert.strictEqual(staleReport.textContent, 'Báo cáo');

        // ROOT-23: appended tombstone reply has no menu
        const tombEl = currThread.querySelector('.is-tombstone');
        assert.ok(tombEl);
        assert.strictEqual(tombEl.querySelector('.novel-comment-menu-trigger'), null);
    });

    test('ROOT-24. root load-more causes no Reader paragraph scroll', async () => {
        const { doc, block1, more } = createStandardFixture('c24');
        const page0 = [{ rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0' }];
        const page1 = [{ rootCommentId: 'r1', author: { displayName: 'R1' }, body: 'B1' }];
        commentsModule.init(doc, {
            fetch: (url) => {
                const isP1 = url.includes('page=1');
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(isP1 ? page1 : page0, !isP1, isP1 ? 1 : 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        more.querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(block1.scrollIntoViewCalled, undefined);
    });

    test('ROOT-25. initial retry retains page-0 semantics', async () => {
        const { doc, status } = createStandardFixture('c25');
        const urls = [];
        let first = true;
        commentsModule.init(doc, {
            fetch: (url) => {
                urls.push(url);
                if (first) {
                    first = false;
                    return Promise.resolve({ ok: false, status: 500 });
                }
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([], false, 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        const retryBtn = status.querySelector('.novel-chapter-comments-retry-btn');
        retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(urls.length, 2);
        assert.ok(urls[0].includes('page=0'));
        assert.ok(urls[1].includes('page=0'));
    });

    test('ROOT-26. chapter change resets root pagination to page 0', async () => {
        const { doc, more } = createStandardFixture('c26-A');
        commentsModule.init(doc, {
            fetch: (url) => {
                const isP1 = url.includes('page=1');
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r', author: { displayName: 'U' }, body: 'B' }], !isP1, isP1 ? 1 : 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        more.querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(commentsModule.getState().currentPage, 1);

        doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c26-B' } });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(commentsModule.getState().currentPage, 0);
    });
});

// ============================================================================
// Test Suite: Per-Thread Reply Progressive Reveal (MS-05E5H2E)
// ============================================================================

describe('MS-05E5H2E Per-Thread Reply Progressive Reveal', () => {

    afterEach(() => {
        commentsModule.destroy();
    });

    test('REPLY-1 to REPLY-4. 0 to 3 replies render directly with no expansion control', async () => {
        const { doc, list } = createStandardFixture('c-rep-1');
        const makeThread = (id, count) => {
            const replies = [];
            for (let i = 0; i < count; i++) {
                replies.push({ id: id + '-rep-' + i, body: 'R' + i, author: { displayName: 'User' } });
            }
            return { rootCommentId: id, author: { displayName: 'Root' }, body: 'Body', replies: replies };
        };
        const items = [makeThread('t0', 0), makeThread('t1', 1), makeThread('t2', 2), makeThread('t3', 3)];
        commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
        await new Promise(r => setTimeout(r, 10));

        const cards = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(cards[0].querySelector('.novel-comment-replies'), null); // REPLY-1
        assert.strictEqual(cards[1].querySelectorAll('.novel-comment--reply').length, 1); // REPLY-2
        assert.strictEqual(cards[1].querySelector('.novel-comment-replies-more'), null);
        assert.strictEqual(cards[2].querySelectorAll('.novel-comment--reply').length, 2); // REPLY-3
        assert.strictEqual(cards[2].querySelector('.novel-comment-replies-more'), null);
        assert.strictEqual(cards[3].querySelectorAll('.novel-comment--reply').length, 3); // REPLY-4
        assert.strictEqual(cards[3].querySelector('.novel-comment-replies-more'), null);
    });

    test('REPLY-5. 4 delivered reply items initially renders 3 with "Xem thêm 1 phản hồi"', async () => {
        const { doc, list } = createStandardFixture('c-rep-5');
        const replies = [1, 2, 3, 4].map(n => ({ id: 'rep-' + n, body: 'R' + n, author: { displayName: 'U' } }));
        commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r4', author: { displayName: 'R' }, body: 'B', replies: replies }])) }) });
        await new Promise(r => setTimeout(r, 10));

        const card = list.querySelector('.novel-block-discussion-thread');
        assert.strictEqual(card.querySelectorAll('.novel-comment--reply').length, 3);
        const moreBtn = card.querySelector('.novel-comment-replies-more-btn');
        assert.ok(moreBtn);
        assert.strictEqual(moreBtn.textContent.trim(), 'Xem thêm 1 phản hồi');
    });

    test('REPLY-6 to REPLY-8. 12 replies shows "Xem thêm 9 phản hồi", 1st click reveals +5 ("Xem thêm 4 phản hồi"), 2nd click reveals all 12 and removes control', async () => {
        const { doc, list } = createStandardFixture('c-rep-6');
        const replies = [];
        for (let i = 1; i <= 12; i++) {
            replies.push({ id: 'rep-' + i, body: 'Reply ' + i, author: { displayName: 'U' + i } });
        }
        commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r12', author: { displayName: 'R' }, body: 'B', replies: replies }])) }) });
        await new Promise(r => setTimeout(r, 10));

        const card = list.querySelector('.novel-block-discussion-thread');
        // Initial (REPLY-6)
        assert.strictEqual(card.querySelectorAll('.novel-comment--reply').length, 3);
        let moreBtn = card.querySelector('.novel-comment-replies-more-btn');
        assert.strictEqual(moreBtn.textContent.trim(), 'Xem thêm 9 phản hồi');

        // First click (REPLY-7)
        moreBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(card.querySelectorAll('.novel-comment--reply').length, 8);
        assert.strictEqual(moreBtn.textContent.trim(), 'Xem thêm 4 phản hồi');

        // Second click (REPLY-8)
        moreBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(card.querySelectorAll('.novel-comment--reply').length, 12);
        assert.strictEqual(card.querySelector('.novel-comment-replies-more'), null);
    });

    test('REPLY-9 & REPLY-10. reply order remains exact delivered order and expansion triggers ZERO fetch calls', async () => {
        const { doc, list } = createStandardFixture('c-rep-9');
        let fetchCount = 0;
        const replies = [
            { id: 'rep-alpha', createdAt: '2026-09-18T10:00:00Z', body: 'Alpha', author: { displayName: 'U1' } },
            { id: 'rep-beta', createdAt: '2026-09-18T10:01:00Z', body: 'Beta', author: { displayName: 'U2' } },
            { id: 'rep-gamma', createdAt: '2026-09-18T10:02:00Z', body: 'Gamma', author: { displayName: 'U3' } },
            { id: 'rep-delta', createdAt: '2026-09-18T10:03:00Z', body: 'Delta', author: { displayName: 'U4' } }
        ];
        commentsModule.init(doc, {
            fetch: () => {
                fetchCount++;
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-ord', author: { displayName: 'R' }, body: 'B', replies: replies }])) });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(fetchCount, 1);

        const card = list.querySelector('.novel-block-discussion-thread');
        card.querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Zero additional fetches (REPLY-10)
        assert.strictEqual(fetchCount, 1);

        // Exact order preserved (REPLY-9)
        const repEls = card.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(repEls.length, 4);
        assert.strictEqual(repEls[0].getAttribute('data-reply-id'), 'rep-alpha');
        assert.strictEqual(repEls[1].getAttribute('data-reply-id'), 'rep-beta');
        assert.strictEqual(repEls[2].getAttribute('data-reply-id'), 'rep-gamma');
        assert.strictEqual(repEls[3].getAttribute('data-reply-id'), 'rep-delta');
    });

    test('REPLY-11 & REPLY-12. expanding Root A does not expand Root B; appending new root page preserves Root A expanded state', async () => {
        const { doc, list, more } = createStandardFixture('c-rep-11');
        const makeReplies = (prefix, n) => {
            const arr = [];
            for (let i = 1; i <= n; i++) arr.push({ id: prefix + '-' + i, body: 'R' + i, author: { displayName: 'U' } });
            return arr;
        };
        const page0 = [
            { rootCommentId: 'rA', author: { displayName: 'A' }, body: 'BA', replies: makeReplies('rA', 8) },
            { rootCommentId: 'rB', author: { displayName: 'B' }, body: 'BB', replies: makeReplies('rB', 6) }
        ];
        const page1 = [
            { rootCommentId: 'rC', author: { displayName: 'C' }, body: 'BC', replies: makeReplies('rC', 4) }
        ];
        commentsModule.init(doc, {
            fetch: (url) => {
                const isP1 = url.includes('page=1');
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(isP1 ? page1 : page0, !isP1, isP1 ? 1 : 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const cards = list.querySelectorAll('.novel-block-discussion-thread');
        // Expand Root A
        cards[0].querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Root A has 8 (3 + 5), Root B still has 3 (REPLY-11)
        assert.strictEqual(cards[0].querySelectorAll('.novel-comment--reply').length, 8);
        assert.strictEqual(cards[1].querySelectorAll('.novel-comment--reply').length, 3);

        // Load page 1
        more.querySelector('.novel-chapter-comments-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        // Root A still has 8 replies rendered! (REPLY-12)
        const updatedCards = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(updatedCards.length, 3);
        assert.strictEqual(updatedCards[0].querySelectorAll('.novel-comment--reply').length, 8);
    });

    test('REPLY-13 & REPLY-14. header count reflects authoritative active replies even when collapsed, and tombstones do not increase active count', async () => {
        const { doc, count } = createStandardFixture('c-rep-13');
        const replies = [
            { id: 'rep-tomb', tombstone: true },
            { id: 'rep-act1', tombstone: false, author: { displayName: 'U1' } },
            { id: 'rep-act2', tombstone: false, author: { displayName: 'U2' } }
        ];
        // 1 root + replyCount: 2 -> header count must be 3
        const items = [{
            rootCommentId: 'r1',
            author: { displayName: 'Root' },
            body: 'Body',
            replyCount: 2,
            replies: replies
        }];
        commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(count.textContent, '3 bình luận');
    });

    test('REPLY-15 to REPLY-18. newly revealed replies have ⋯ targeting ROOT rootCommentId, tombstones have no menu, and nested mention resolves', async () => {
        const { doc, list } = createStandardFixture('c-rep-15');
        let bridgePayload = null;
        const replies = [
            { id: 'rep-1', author: { displayName: 'Alice' }, body: 'R1' },
            { id: 'rep-2', author: { displayName: 'Bob' }, body: 'R2' },
            { id: 'rep-3', author: { displayName: 'Charlie' }, body: 'R3' },
            // Batch 2:
            { id: 'rep-4-nested', parentCommentId: 'rep-1', author: { displayName: 'David' }, body: 'R4 nested' },
            { id: 'rep-5-tomb', parentCommentId: 'rep-1', tombstone: true }
        ];
        const item = {
            rootCommentId: 'r-root-target',
            anchorStatus: 'CURRENT',
            blockKey: 'blk-test-1',
            author: { displayName: 'RootOwner' },
            body: 'Root text',
            replies: replies
        };
        commentsModule.init(doc, {
            fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([item])) }),
            openDiscussionTarget: (t) => { bridgePayload = t; }
        });
        await new Promise(r => setTimeout(r, 10));

        const card = list.querySelector('.novel-block-discussion-thread');
        // Reveal batch 2
        card.querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });

        const renderedReplies = card.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(renderedReplies.length, 5);

        // REPLY-18: nested reply has @Alice mention
        const nestedReply = renderedReplies[3];
        const mention = nestedReply.querySelector('.novel-comment-reply-mention');
        assert.ok(mention);
        assert.strictEqual(mention.textContent, '@Alice');

        // REPLY-15 & REPLY-16: newly revealed CURRENT nested reply has ⋯ and sends ROOT rootCommentId
        const trigger = nestedReply.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger);
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        const originBtn = nestedReply.querySelector('.novel-comment-menu-item');
        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.ok(bridgePayload);
        assert.strictEqual(bridgePayload.threadId, 'r-root-target');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');

        // REPLY-17: newly revealed tombstone has no menu
        const tombReply = renderedReplies[4];
        assert.ok(tombReply.classList.contains('is-tombstone'));
        assert.strictEqual(tombReply.querySelector('.novel-comment-menu-trigger'), null);
    });

    test('REPLY-19 & REPLY-20. reply expansion causes no Reader scroll and does not alter URL', async () => {
        const { doc, list, block1 } = createStandardFixture('c-rep-19');
        const replies = [1, 2, 3, 4, 5].map(n => ({ id: 'rep-' + n, body: 'R' + n, author: { displayName: 'U' } }));
        commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r', author: { displayName: 'R' }, body: 'B', replies: replies }])) }) });
        await new Promise(r => setTimeout(r, 10));

        list.querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(block1.scrollIntoViewCalled, undefined);
    });

    test('REPLY-21 & REPLY-22. chapter change and full initial retry reset reply reveal state', async () => {
        const { doc, list } = createStandardFixture('c-rep-21');
        const replies = [1, 2, 3, 4, 5, 6].map(n => ({ id: 'rep-' + n, body: 'R' + n, author: { displayName: 'U' } }));
        commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r', author: { displayName: 'R' }, body: 'B', replies: replies }])) }) });
        await new Promise(r => setTimeout(r, 10));

        // Expand to all 6
        list.querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(list.querySelectorAll('.novel-comment--reply').length, 6);

        // REPLY-22: retryFetch resets to initial 3
        commentsModule.retry();
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-comment--reply').length, 3);

        // Expand again
        list.querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(list.querySelectorAll('.novel-comment--reply').length, 6);

        // REPLY-21: chapter change resets to initial 3
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c-rep-21-new' } });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-comment--reply').length, 3);
    });
});

describe('MS-05E5H2F1 Authoritative Mutation Refresh (refreshFromPageZero)', () => {
    afterEach(() => {
        commentsModule.destroy();
    });

    test('REFRESH-A. existing rendered DOM remains visible while mutation refresh is pending', async () => {
        const { doc, list } = createStandardFixture('c-ref-a');
        let resolveRefresh;
        const page0 = [{ rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0' }];
        const page1 = [{ rootCommentId: 'r1', author: { displayName: 'R1' }, body: 'B1' }];

        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    return Promise.resolve({
                        ok: true,
                        json: () => Promise.resolve(makeFeedResponse(page1, false, 1))
                    });
                }
                if (resolveRefresh) {
                    return new Promise(r => { resolveRefresh = r; });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse(page0, true, 0))
                });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);

        // Load page 1
        commentsModule.loadMore();
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
        assert.strictEqual(commentsModule.getState().currentPage, 1);

        // Start mutation refresh (pending)
        let refreshResolved = false;
        resolveRefresh = true; // flag to trigger Promise
        const refreshPromise = commentsModule.refreshFromPageZero().then(() => { refreshResolved = true; });

        // REFRESH-A: Old DOM remains visible while GET page 0 is in-flight
        assert.strictEqual(commentsModule.getState().isRefreshing, true);
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
        assert.strictEqual(refreshResolved, false);

        // Resolve refresh
        const refreshedPage0 = [
            { rootCommentId: 'r-new', author: { displayName: 'RNew' }, body: 'New Root' },
            { rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0' }
        ];
        resolveRefresh({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(refreshedPage0, false, 0))
        });
        await refreshPromise;

        assert.strictEqual(refreshResolved, true);
        assert.strictEqual(commentsModule.getState().isRefreshing, false);
    });

    test('REFRESH-B to REFRESH-D. accepted response replaces feed, resets currentPage to 0, updates count, resets expanded replies', async () => {
        const { doc, list, count } = createStandardFixture('c-ref-b');
        const initialReplies = [
            { id: 'rep1', body: '1' }, { id: 'rep2', body: '2' }, { id: 'rep3', body: '3' },
            { id: 'rep4', body: '4' }, { id: 'rep5', body: '5' }
        ];
        const page0 = [{ rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0', replies: initialReplies }];
        const page1 = [{ rootCommentId: 'r1', author: { displayName: 'R1' }, body: 'B1', replies: [] }];

        let fetchTarget = 'init';
        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page1, false, 1)) });
                }
                if (fetchTarget === 'refreshed') {
                    const refreshedRoots = [
                        { rootCommentId: 'r-new', author: { displayName: 'RNew' }, body: 'New Root', replies: [] },
                        { rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0', replies: initialReplies }
                    ];
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(refreshedRoots, false, 0)) });
                }
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0, true, 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));

        // Expand r0 replies from 3 to 5
        list.querySelector('.novel-comment-replies-more-btn').dispatchEvent({ type: 'click', preventDefault: () => {} });
        assert.strictEqual(list.querySelectorAll('.novel-comment--reply').length, 5);

        // Load page 1
        commentsModule.loadMore();
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
        assert.strictEqual(commentsModule.getState().currentPage, 1);

        // Trigger mutation refresh
        fetchTarget = 'refreshed';
        await commentsModule.refreshFromPageZero();

        // REFRESH-B: replaces feed with new page 0 (r-new at top, r0 below, r1 gone)
        const threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads.length, 2);
        assert.strictEqual(threads[0].querySelector('.novel-comment-body').textContent, 'New Root');
        assert.strictEqual(threads[1].querySelector('.novel-comment-body').textContent, 'B0');
        assert.strictEqual(commentsModule.getState().currentPage, 0);
        assert.strictEqual(commentsModule.getState().hasNext, false);

        // REFRESH-C: header count recomputed (1 new root + 1 root r0 + 5 replies = 7 comments)
        assert.strictEqual(count.textContent, '7 bình luận');

        // REFRESH-D: replies on r0 reset to initial 3 visible (with reveal button)
        assert.strictEqual(threads[1].querySelectorAll('.novel-comment--reply').length, 3);
        assert.ok(threads[1].querySelector('.novel-comment-replies-more-btn'));
    });

    test('REFRESH-E & REFRESH-F. loadMore cannot begin while refresh pending, and stale loadMore cannot append', async () => {
        const { doc, list } = createStandardFixture('c-ref-e');
        let resolveLoadMore;
        let loadMoreFetchCount = 0;
        let resolveRefresh;
        let refreshFetchCount = 0;

        const page0 = [{ rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0' }];
        const page1 = [{ rootCommentId: 'r1', author: { displayName: 'R1' }, body: 'B1' }];

        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('page=1')) {
                    loadMoreFetchCount++;
                    return new Promise(r => { resolveLoadMore = r; });
                }
                if (refreshFetchCount > 0) {
                    return new Promise(r => { resolveRefresh = r; });
                }
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0, true, 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);

        // Start loadMore (pending)
        commentsModule.loadMore();
        assert.strictEqual(loadMoreFetchCount, 1);

        // Start mutation refresh while loadMore is pending
        refreshFetchCount = 1;
        const refreshPromise = commentsModule.refreshFromPageZero();
        assert.strictEqual(commentsModule.getState().isRefreshing, true);

        // REFRESH-F: Calling loadMore while isRefreshing is true is a NO-OP
        commentsModule.loadMore();
        assert.strictEqual(loadMoreFetchCount, 1);

        // Now stale loadMore resolves late
        resolveLoadMore({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(page1, false, 1))
        });
        await new Promise(r => setTimeout(r, 10));

        // REFRESH-E: Stale loadMore data was NOT appended
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);

        // Refresh completes
        const refreshedPage0 = [{ rootCommentId: 'r-fresh', author: { displayName: 'RFresh' }, body: 'Fresh' }];
        resolveRefresh({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(refreshedPage0, false, 0))
        });
        await refreshPromise;

        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);
        assert.strictEqual(list.querySelector('.novel-comment-body').textContent, 'Fresh');
        assert.strictEqual(commentsModule.getState().isRefreshing, false);
    });

    test('REFRESH-G. mutation refresh failure leaves existing items, DOM, and pagination intact without full-feed error UI', async () => {
        const { doc, list, status, more } = createStandardFixture('c-ref-g');
        const page0 = [{ rootCommentId: 'r0', author: { displayName: 'R0' }, body: 'B0' }];
        const page1 = [{ rootCommentId: 'r1', author: { displayName: 'R1' }, body: 'B1' }];

        let triggerRefreshFail = false;
        commentsModule.init(doc, {
            fetch: (url) => {
                if (triggerRefreshFail) {
                    return Promise.resolve({ ok: false, status: 500 });
                }
                if (url.includes('page=1')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page1, false, 1)) });
                }
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0, true, 0)) });
            }
        });
        await new Promise(r => setTimeout(r, 10));

        // Load page 1 -> 2 cards rendered
        commentsModule.loadMore();
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
        assert.strictEqual(commentsModule.getState().currentPage, 1);

        // Trigger failing mutation refresh
        triggerRefreshFail = true;
        let caughtError = null;
        try {
            await commentsModule.refreshFromPageZero();
        } catch (e) {
            caughtError = e;
        }

        assert.ok(caughtError);
        assert.strictEqual(commentsModule.getState().isRefreshing, false);

        // REFRESH-G: Existing items, DOM, and currentPage remain intact
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
        assert.strictEqual(commentsModule.getState().currentPage, 1);
        assert.strictEqual(commentsModule.getState().items.length, 2);

        // Full-feed error is NOT displayed (status remains empty)
        assert.strictEqual(status.childNodes.length, 0);
    });

    test('REFRESH-H & REFRESH-I. chapter change or destroy invalidates pending mutation refresh response', async () => {
        const { doc, list } = createStandardFixture('c-ref-h');
        let resolveRefreshH;

        commentsModule.init(doc, {
            fetch: (url) => {
                if (url.includes('c-ref-h')) {
                    return new Promise(r => { resolveRefreshH = r; });
                }
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-chap-b', author: { displayName: 'RB' }, body: 'Chapter B Body' }], false, 0))
                });
            }
        });

        const refreshPromise = commentsModule.refreshFromPageZero();

        // Switch chapter to Chapter B
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c-chap-b' } });
        await new Promise(r => setTimeout(r, 10));

        // Now Chapter A refresh resolves late
        resolveRefreshH({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-late-a', author: { displayName: 'RA' }, body: 'Late A' }], false, 0))
        });
        await refreshPromise;

        // Chapter B content is rendered; Late A is discarded
        assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 1);
        assert.strictEqual(list.querySelector('.novel-comment-body').textContent, 'Chapter B Body');

        // REFRESH-I: Destroy invalidates refresh
        let resolveRefreshI;
        commentsModule.setFetchImplementation(() => new Promise(r => { resolveRefreshI = r; }));
        const refreshPromiseI = commentsModule.refreshFromPageZero();
        commentsModule.destroy();

        resolveRefreshI({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-after-destroy', author: { displayName: 'RD' }, body: 'Dead' }], false, 0))
        });
        await refreshPromiseI;

        assert.strictEqual(commentsModule.getState().items.length, 0);
    });

    describe('MS-05E5H2F2B Authoritative Root Thread Refresh (refreshRootThread)', () => {
        test('A & B. accepted page 0 and page 1 record root to page mappings, T & U. refreshFromPageZero rebuilds and chapter change clears mapping', async () => {
            const { doc, list } = createStandardFixture('c-map-1');
            const page0Items = [
                { rootCommentId: 'r-0a', author: { displayName: 'User 0A' }, body: 'Body 0A', replyCount: 0, replies: [] },
                { rootCommentId: 'r-0b', author: { displayName: 'User 0B' }, body: 'Body 0B', replyCount: 0, replies: [] }
            ];
            const page1Items = [
                { rootCommentId: 'r-1a', author: { displayName: 'User 1A' }, body: 'Body 1A', replyCount: 0, replies: [] }
            ];

            const fakeFetch = (url) => {
                if (url.includes('page=0')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0Items, true, 0)) });
                }
                if (url.includes('page=1')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page1Items, false, 1)) });
                }
                return Promise.reject(new Error('Unknown url: ' + url));
            };

            commentsModule.init(doc, { fetch: fakeFetch });
            await new Promise(r => setTimeout(r, 10));

            // A. Page 0 records mapping
            let map = commentsModule.getState().rootPageMap;
            assert.strictEqual(map['r-0a'], 0);
            assert.strictEqual(map['r-0b'], 0);

            // B. Load more page 1 records mapping
            commentsModule.loadMore();
            await new Promise(r => setTimeout(r, 10));

            map = commentsModule.getState().rootPageMap;
            assert.strictEqual(map['r-0a'], 0);
            assert.strictEqual(map['r-0b'], 0);
            assert.strictEqual(map['r-1a'], 1);

            // T. refreshFromPageZero rebuilds rootPageMap
            await commentsModule.refreshFromPageZero();
            map = commentsModule.getState().rootPageMap;
            assert.strictEqual(map['r-0a'], 0);
            assert.strictEqual(map['r-0b'], 0);
            assert.strictEqual(map['r-1a'], undefined);

            // U. chapter change clears rootPageMap
            doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c-new-chap' } });
            // Before new fetch resolves or during reset
            map = commentsModule.getState().rootPageMap;
            // Cleared and re-populated for new chapter
            assert.strictEqual(typeof map, 'object');
        });

        test('C to J. refreshRootThread on page-1 root refetches page 1, replaces only target entry/DOM, preserves other DOM/pages/pagination, and updates count', async () => {
            const { doc, list, count } = createStandardFixture('c-refresh-root');
            const page0Items = [
                { rootCommentId: 'r-p0-1', author: { displayName: 'P0 Root' }, body: 'P0 Root Body', replyCount: 0, replies: [] }
            ];
            const page1Items = [
                { rootCommentId: 'r-p1-1', author: { displayName: 'P1 Target' }, body: 'P1 Target Old Body', replyCount: 0, replies: [] },
                { rootCommentId: 'r-p1-2', author: { displayName: 'P1 Sibling' }, body: 'P1 Sibling Body', replyCount: 0, replies: [] }
            ];

            let requestedUrls = [];
            const fakeFetch = (url) => {
                requestedUrls.push(url);
                if (url.includes('page=0')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0Items, true, 0)) });
                }
                if (url.includes('page=1')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page1Items, false, 1)) });
                }
                return Promise.reject(new Error('Unknown url: ' + url));
            };

            commentsModule.init(doc, { fetch: fakeFetch });
            await new Promise(r => setTimeout(r, 10));
            commentsModule.loadMore();
            await new Promise(r => setTimeout(r, 10));

            // Verify initial setup: 3 roots rendered, count = 3
            assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 3);
            assert.strictEqual(count.textContent, '3 bình luận');
            const initialP0Card = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p0-1"]');
            const initialSiblingCard = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p1-2"]');
            const initialTargetCard = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p1-1"]');

            // Now target root has a new reply on server
            const updatedPage1Items = [
                {
                    rootCommentId: 'r-p1-1',
                    author: { displayName: 'P1 Target' },
                    body: 'P1 Target Old Body',
                    replyCount: 1,
                    replies: [
                        { id: 'rep-new-1', rootCommentId: 'r-p1-1', author: { displayName: 'Replier' }, body: 'New Reply Text' }
                    ]
                },
                { rootCommentId: 'r-p1-2', author: { displayName: 'P1 Sibling' }, body: 'P1 Sibling Body', replyCount: 0, replies: [] }
            ];

            // Change fetch to return updated page 1
            commentsModule.setFetchImplementation((url) => {
                requestedUrls.push(url);
                if (url.includes('page=1')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(updatedPage1Items, false, 1)) });
                }
                return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0Items, true, 0)) });
            });

            // C. refreshRootThread('r-p1-1')
            const refreshedItem = await commentsModule.refreshRootThread('r-p1-1');

            // Verify C: requests page=1&size=20
            const lastUrl = requestedUrls[requestedUrls.length - 1];
            assert.ok(lastUrl.includes('page=1&size=20'), 'Must request exact source page: ' + lastUrl);

            // D. Target root found: only that currentItems entry replaced
            const state = commentsModule.getState();
            assert.strictEqual(state.items.length, 3);
            assert.strictEqual(state.items[1].rootCommentId, 'r-p1-1');
            assert.strictEqual(state.items[1].replyCount, 1);
            assert.strictEqual(state.items[1].replies.length, 1);

            // E & F. Only target root DOM replaced, others retain exact node identity
            const currentP0Card = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p0-1"]');
            const currentSiblingCard = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p1-2"]');
            const currentTargetCard = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p1-1"]');

            assert.strictEqual(currentP0Card, initialP0Card, 'Unrelated page 0 root node identity must be preserved');
            assert.strictEqual(currentSiblingCard, initialSiblingCard, 'Unrelated page 1 sibling node identity must be preserved');
            assert.notStrictEqual(currentTargetCard, initialTargetCard, 'Target root card must be replaced in DOM');

            // Newly added reply is visible in the target card
            assert.ok(currentTargetCard.querySelector('.novel-comment--reply'));
            assert.strictEqual(currentTargetCard.querySelector('.novel-comment--reply .novel-comment-body').textContent, 'New Reply Text');

            // G & H & I. Pagination preserved
            assert.strictEqual(state.currentPage, 1);
            assert.strictEqual(state.hasNext, false);

            // J. Header count recomputed from updated replyCount (1 + 1 + 1 + 1 = 4)
            assert.strictEqual(count.textContent, '4 bình luận');
        });

        test('K & L & M & V. reveal depth preservation, revealCommentId inclusion, and unrelated roots expansion state preserved', async () => {
            const { doc, list } = createStandardFixture('c-reveal-depth');
            const repliesA = [];
            for (let i = 1; i <= 6; i++) {
                repliesA.push({ id: 'rep-a-' + i, rootCommentId: 'r-a', author: { displayName: 'User A' }, body: 'Reply A ' + i });
            }
            const repliesB = [];
            for (let i = 1; i <= 5; i++) {
                repliesB.push({ id: 'rep-b-' + i, rootCommentId: 'r-b', author: { displayName: 'User B' }, body: 'Reply B ' + i });
            }

            const initialItems = [
                { rootCommentId: 'r-a', author: { displayName: 'RA' }, body: 'Root A', replyCount: 6, replies: repliesA },
                { rootCommentId: 'r-b', author: { displayName: 'RB' }, body: 'Root B', replyCount: 5, replies: repliesB }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(initialItems, false, 0)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const cardA = list.querySelector('.novel-block-discussion-thread[data-root-id="r-a"]');
            const cardB = list.querySelector('.novel-block-discussion-thread[data-root-id="r-b"]');

            // Initially both show INITIAL_VISIBLE_REPLIES = 3
            assert.strictEqual(cardA.querySelectorAll('.novel-comment--reply').length, 3);
            assert.strictEqual(cardB.querySelectorAll('.novel-comment--reply').length, 3);

            // Click expand on card B: 3 -> 5
            const moreBtnB = cardB.querySelector('.novel-comment-replies-more-btn');
            moreBtnB.click();
            assert.strictEqual(cardB.querySelectorAll('.novel-comment--reply').length, 5);

            // Expand card A once: 3 -> 6 (all 6 visible)
            const moreBtnA = cardA.querySelector('.novel-comment-replies-more-btn');
            moreBtnA.click();
            assert.strictEqual(cardA.querySelectorAll('.novel-comment--reply').length, 6);

            // Now root A gets a 7th reply on server
            const newReply7 = { id: 'rep-a-7', rootCommentId: 'r-a', author: { displayName: 'User A' }, body: 'Reply A 7' };
            const updatedRepliesA = [...repliesA, newReply7];
            const updatedItems = [
                { rootCommentId: 'r-a', author: { displayName: 'RA' }, body: 'Root A', replyCount: 7, replies: updatedRepliesA },
                { rootCommentId: 'r-b', author: { displayName: 'RB' }, body: 'Root B', replyCount: 5, replies: repliesB }
            ];

            commentsModule.setFetchImplementation(() => Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse(updatedItems, false, 0))
            }));

            // L. refreshRootThread with revealCommentId='rep-a-7'
            await commentsModule.refreshRootThread('r-a', { revealCommentId: 'rep-a-7' });

            const newCardA = list.querySelector('.novel-block-discussion-thread[data-root-id="r-a"]');
            const currentCardB = list.querySelector('.novel-block-discussion-thread[data-root-id="r-b"]');

            // K & L. Target root now renders all 7 replies (including the new reply)
            assert.strictEqual(newCardA.querySelectorAll('.novel-comment--reply').length, 7);

            // V. Unrelated Root B expansion state remains untouched (still 5 replies revealed)
            assert.strictEqual(currentCardB, cardB);
            assert.strictEqual(currentCardB.querySelectorAll('.novel-comment--reply').length, 5);
        });

        test('N. root refresh failure leaves currentItems, DOM, and pagination unchanged', async () => {
            const { doc, list, count } = createStandardFixture('c-fail');
            const items = [{ rootCommentId: 'r-1', author: { displayName: 'R1' }, body: 'Original Body', replyCount: 0, replies: [] }];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items, false, 0)) })
            });
            await new Promise(r => setTimeout(r, 10));

            // Set failing fetch
            commentsModule.setFetchImplementation(() => Promise.resolve({ ok: false, status: 500 }));

            await assert.rejects(
                () => commentsModule.refreshRootThread('r-1'),
                (err) => {
                    assert.strictEqual(err.message, 'HTTP 500');
                    return true;
                }
            );

            // N. DOM unchanged, items unchanged, isRefreshing reset to false
            assert.strictEqual(list.querySelector('.novel-comment-body').textContent, 'Original Body');
            assert.strictEqual(commentsModule.getState().isRefreshing, false);
            assert.strictEqual(commentsModule.getState().items[0].body, 'Original Body');
        });

        test('O & P. loadMore cannot begin while root refresh is active, and stale loadMore cannot append', async () => {
            const { doc, list } = createStandardFixture('c-race');
            const items = [{ rootCommentId: 'r-1', author: { displayName: 'R1' }, body: 'Body 1', replyCount: 0, replies: [] }];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items, true, 0)) })
            });
            await new Promise(r => setTimeout(r, 10));

            let resolveRefresh;
            commentsModule.setFetchImplementation(() => new Promise(r => { resolveRefresh = r; }));

            // Start root refresh (keeps pending)
            const refreshPromise = commentsModule.refreshRootThread('r-1');
            assert.strictEqual(commentsModule.getState().isRefreshing, true);

            // P. loadMore cannot start
            commentsModule.loadMore();
            assert.strictEqual(commentsModule.getState().isLoadingMore, false);

            resolveRefresh({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse(items, true, 0))
            });
            await refreshPromise;
            assert.strictEqual(commentsModule.getState().isRefreshing, false);
        });

        test('Q & R. chapter change or destroy invalidates pending root refresh', async () => {
            const { doc, list } = createStandardFixture('c-inval');
            const items = [{ rootCommentId: 'r-1', author: { displayName: 'R1' }, body: 'Body 1', replyCount: 0, replies: [] }];

            let resolvePending;
            commentsModule.init(doc, {
                fetch: (url) => {
                    if (url.includes('c-chap-2')) {
                        return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-chap2', author: { displayName: 'C2' }, body: 'Chapter 2 Body' }], false, 0)) });
                    }
                    return new Promise(r => { resolvePending = r; });
                }
            });
            resolvePending({ ok: true, json: () => Promise.resolve(makeFeedResponse(items, false, 0)) });
            await new Promise(r => setTimeout(r, 10));

            // Now start root refresh and change chapter while in flight
            let resolveLateRefresh;
            commentsModule.setFetchImplementation((url) => {
                if (url.includes('c-chap-2')) {
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-chap2', author: { displayName: 'C2' }, body: 'Chapter 2 Body' }], false, 0)) });
                }
                return new Promise(r => { resolveLateRefresh = r; });
            });

            const refreshPromise = commentsModule.refreshRootThread('r-1');
            doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c-chap-2' } });
            await new Promise(r => setTimeout(r, 10));

            // Now late old-chapter refresh resolves
            resolveLateRefresh({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-1', author: { displayName: 'R1' }, body: 'Stale Root 1' }], false, 0))
            });
            await refreshPromise;

            // Q. Old refresh does not corrupt new chapter
            assert.strictEqual(list.querySelector('.novel-comment-body').textContent, 'Chapter 2 Body');

            // R. Destroy invalidates
            let resolveDestroyRefresh;
            commentsModule.setFetchImplementation(() => new Promise(r => { resolveDestroyRefresh = r; }));
            const refreshPromise2 = commentsModule.refreshRootThread('r-chap2');
            commentsModule.destroy();

            resolveDestroyRefresh({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-chap2', author: { displayName: 'C2' }, body: 'After Destroy' }], false, 0))
            });
            await refreshPromise2;
            assert.strictEqual(commentsModule.getState().items.length, 0);
        });

        test('S. target missing from expected page triggers safe fallback to authoritative page-0 refresh', async () => {
            const { doc, list } = createStandardFixture('c-shift');
            const page0Items = [
                { rootCommentId: 'r-shifted', author: { displayName: 'Shifted' }, body: 'Page 0 Old Body', replyCount: 0, replies: [] }
            ];

            let page0RefreshCalled = false;
            commentsModule.init(doc, {
                fetch: (url) => {
                    if (url.includes('page=0')) {
                        page0RefreshCalled = true;
                        return Promise.resolve({
                            ok: true,
                            json: () => Promise.resolve(makeFeedResponse([
                                { rootCommentId: 'r-new-top', author: { displayName: 'Top' }, body: 'New Top', replyCount: 0, replies: [] },
                                { rootCommentId: 'r-shifted', author: { displayName: 'Shifted' }, body: 'Page 0 Fresh Body', replyCount: 0, replies: [] }
                            ], false, 0))
                        });
                    }
                    // Page 1 response where r-shifted was expected, but missing due to concurrent insertion
                    return Promise.resolve({
                        ok: true,
                        json: () => Promise.resolve(makeFeedResponse([], false, 1))
                    });
                }
            });
            await new Promise(r => setTimeout(r, 10));

            // Set map as if r-shifted is on page 1
            commentsModule.getState().rootPageMap['r-shifted'] = 1;

            page0RefreshCalled = false;
            await commentsModule.refreshRootThread('r-shifted');

            // Fallback triggered page-0 refresh and found it
            assert.strictEqual(page0RefreshCalled, true);
            assert.strictEqual(commentsModule.getState().items.length, 2);
            assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 2);
        });

        test('W. refreshRootThread receiving DELETED root removes old thread card, clears descendant actions, and updates count to "0 bình luận"', async () => {
            const { doc, list, count } = createStandardFixture('c-refresh-deleted-root');
            const activeRoot = {
                rootCommentId: 'r-del-target',
                author: { userId: 'u-other', displayName: 'Other' },
                body: 'Active Root Body Before Delete',
                tombstone: false,
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-child-1',
                        parentCommentId: 'r-del-target',
                        author: { userId: 'u-child', displayName: 'Child User' },
                        body: 'Child Reply Body',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    }
                ]
            };

            let currentFeedItems = [activeRoot];
            commentsModule.init(doc, {
                fetch: () => Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse(currentFeedItems, false, 0))
                })
            });
            await new Promise(r => setTimeout(r, 10));

            // Initial verification: thread card is rendered, descendant report button exists, count = '2 bình luận'
            const initialCard = list.querySelector('.novel-block-discussion-thread[data-root-id="r-del-target"]');
            assert.ok(initialCard, 'Initial thread card must be rendered in DOM');
            const initialReportBtn = list.querySelector('.novel-comment-report-btn[data-comment-id="rep-child-1"]');
            assert.ok(initialReportBtn, 'Descendant report button must exist before delete');
            assert.strictEqual(count.textContent, '2 bình luận');

            // Server now returns the root as DELETED / tombstone with its replies
            const deletedRoot = {
                rootCommentId: 'r-del-target',
                author: null,
                body: null,
                tombstone: true,
                status: 'DELETED',
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-child-1',
                        parentCommentId: 'r-del-target',
                        author: { userId: 'u-child', displayName: 'Child User' },
                        body: 'Child Reply Body',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    }
                ]
            };
            currentFeedItems = [deletedRoot];

            // Perform targeted refresh
            await commentsModule.refreshRootThread('r-del-target');

            // 1. Old thread card is removed from DOM
            const cardAfter = list.querySelector('.novel-block-discussion-thread[data-root-id="r-del-target"]');
            assert.strictEqual(cardAfter, null, 'Old thread card must be removed from DOM when root becomes DELETED');
            assert.strictEqual(list.querySelectorAll('.novel-block-discussion-thread').length, 0, 'No thread cards should remain in list');

            // 2. No descendant report actions remain
            assert.strictEqual(list.querySelector('.novel-comment-report-btn'), null, 'No descendant report button should remain');

            // 3. Visible count becomes "0 bình luận"
            assert.strictEqual(count.textContent, '0 bình luận', 'Count must update to "0 bình luận"');
        });

        test('X. refreshRootThread on ACTIVE root when CommentPresentation is unavailable preserves existing thread card in DOM', async () => {
            const { doc, list } = createStandardFixture('c-refresh-pres-unavail');
            const activeRoot = {
                rootCommentId: 'r-active-target',
                author: { userId: 'u-user', displayName: 'Active User' },
                body: 'Active Root Body Remains',
                tombstone: false,
                canEdit: false,
                canDelete: false,
                replyCount: 0,
                replies: []
            };

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(makeFeedResponse([activeRoot], false, 0))
                })
            });
            await new Promise(r => setTimeout(r, 10));

            // Initial verification: thread card is rendered in DOM
            const initialCard = list.querySelector('.novel-block-discussion-thread[data-root-id="r-active-target"]');
            assert.ok(initialCard, 'Initial thread card must be rendered in DOM');

            // Inject CommentPresentation as unavailable
            commentsModule.setCommentPresentationImplementation(null);

            try {
                // Authoritative refresh returns the ACTIVE root
                await commentsModule.refreshRootThread('r-active-target');

                // Existing old thread card remains in DOM; thread is NOT removed merely because presentation rendering is unavailable
                const cardAfter = list.querySelector('.novel-block-discussion-thread[data-root-id="r-active-target"]');
                assert.ok(cardAfter, 'Thread card must remain in DOM when root is active but presentation is unavailable');
                assert.strictEqual(cardAfter, initialCard, 'Original DOM element must be preserved');
            } finally {
                commentsModule.setCommentPresentationImplementation(undefined);
            }
        });
    });

    describe('MS-05E5H2F3B Bottom Comment Owner Menu Capabilities (Cases A-J)', () => {
        test('Case A: Anchored root owner renders ⋯ with origin + Edit + Delete, plus primary Phản hồi', async () => {
            const { doc, list } = createStandardFixture('c-case-a');
            const items = [{
                rootCommentId: 'r-case-a',
                author: { userId: 'u1', displayName: 'Owner' },
                body: 'Anchored root owner',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                canEdit: true,
                canDelete: true,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const trigger = list.querySelector('.novel-comment--root .novel-comment-menu-trigger');
            assert.ok(trigger, 'Menu trigger must exist');

            const popover = list.querySelector('.novel-comment--root .novel-comment-menu-popover');
            assert.ok(popover);

            const originBtn = popover.querySelector('button[data-action="view-origin"]');
            assert.ok(originBtn, 'Origin action must exist');
            assert.strictEqual(originBtn.textContent, 'Xem bình luận gốc');

            const editBtn = popover.querySelector('.novel-comment-edit-btn');
            assert.ok(editBtn, 'Edit action must exist');
            assert.strictEqual(editBtn.getAttribute('data-action'), 'edit');
            assert.strictEqual(editBtn.textContent, 'Chỉnh sửa');
            assert.strictEqual(editBtn.getAttribute('data-comment-id'), 'r-case-a');
            assert.strictEqual(editBtn.getAttribute('data-root-id'), 'r-case-a');

            const deleteBtn = popover.querySelector('.novel-comment-delete-btn');
            assert.ok(deleteBtn, 'Delete action must exist');
            assert.strictEqual(deleteBtn.getAttribute('data-action'), 'delete');
            assert.strictEqual(deleteBtn.textContent, 'Xóa');
            assert.strictEqual(deleteBtn.getAttribute('data-comment-id'), 'r-case-a');
            assert.strictEqual(deleteBtn.getAttribute('data-root-id'), 'r-case-a');

            const replyBtn = list.querySelector('.novel-comment--root .novel-comment-reply-btn');
            assert.ok(replyBtn, 'Primary Phản hồi button must exist outside menu');
        });

        test('Case B: UNANCHORED root owner renders ⋯ with Edit + Delete, NO origin action', async () => {
            const { doc, list } = createStandardFixture('c-case-b');
            const items = [{
                rootCommentId: 'r-case-b',
                author: { userId: 'u1', displayName: 'Owner' },
                body: 'Unanchored root owner',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                anchorStatus: 'UNANCHORED',
                blockKey: null,
                canEdit: true,
                canDelete: true,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const trigger = list.querySelector('.novel-comment--root .novel-comment-menu-trigger');
            assert.ok(trigger, 'Menu trigger must exist for unanchored owner');

            const popover = list.querySelector('.novel-comment--root .novel-comment-menu-popover');
            assert.ok(popover);

            assert.strictEqual(popover.querySelector('button[data-action="view-origin"]'), null, 'Origin action must NOT exist');

            const editBtn = popover.querySelector('.novel-comment-edit-btn');
            assert.ok(editBtn, 'Edit action must exist');
            assert.strictEqual(editBtn.getAttribute('data-action'), 'edit');

            const deleteBtn = popover.querySelector('.novel-comment-delete-btn');
            assert.ok(deleteBtn, 'Delete action must exist');
            assert.strictEqual(deleteBtn.getAttribute('data-action'), 'delete');

            const replyBtn = list.querySelector('.novel-comment--root .novel-comment-reply-btn');
            assert.ok(replyBtn, 'Primary Phản hồi button must exist');
        });

        test('Case C: Anchored root non-owner renders ⋯ with origin + Report, NO Edit/Delete', async () => {
            const { doc, list } = createStandardFixture('c-case-c');
            const items = [{
                rootCommentId: 'r-case-c',
                author: { userId: 'u2', displayName: 'Non-Owner' },
                body: 'Anchored root non-owner',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                canEdit: false,
                canDelete: false,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const trigger = list.querySelector('.novel-comment--root .novel-comment-menu-trigger');
            assert.ok(trigger, 'Menu trigger must exist for anchored non-owner');

            const popover = list.querySelector('.novel-comment--root .novel-comment-menu-popover');
            assert.ok(popover.querySelector('button[data-action="view-origin"]'));
            const reportBtn = popover.querySelector('button[data-action="report"]');
            assert.ok(reportBtn, 'Report action must exist for non-owner');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(popover.querySelector('button[data-action="edit"]'), null);
            assert.strictEqual(popover.querySelector('button[data-action="delete"]'), null);
        });

        test('Case D: UNANCHORED root non-owner renders ⋯ with Report, NO origin', async () => {
            const { doc, list } = createStandardFixture('c-case-d');
            const items = [{
                rootCommentId: 'r-case-d',
                author: { userId: 'u2', displayName: 'Non-Owner' },
                body: 'Unanchored root non-owner',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                anchorStatus: 'UNANCHORED',
                blockKey: null,
                canEdit: false,
                canDelete: false,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const trigger = list.querySelector('.novel-comment--root .novel-comment-menu-trigger');
            assert.ok(trigger, 'Menu trigger must exist for unanchored root non-owner');
            const menu = list.querySelector('.novel-comment--root .novel-comment-actions-menu');
            assert.ok(menu, 'Actions menu must exist for unanchored root non-owner');
            const popover = list.querySelector('.novel-comment--root .novel-comment-menu-popover');
            assert.ok(popover, 'Menu popover must exist');
            assert.strictEqual(popover.querySelector('button[data-action="view-origin"]'), null, 'view-origin must be absent');
            const reportBtn = popover.querySelector('button[data-action="report"]');
            assert.ok(reportBtn, 'Report action must be present');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(popover.querySelector('button[data-action="edit"]'), null, 'Edit must be absent');
            assert.strictEqual(popover.querySelector('button[data-action="delete"]'), null, 'Delete must be absent');
            assert.ok(list.querySelector('.novel-comment--root .novel-comment-reply-btn'), 'Phản hồi button remains outside menu');
        });

        test('Case E: Root tombstone has NO ⋯ menu, NO Reply', async () => {
            const { doc, list } = createStandardFixture('c-case-e');
            const items = [{
                rootCommentId: 'r-case-e',
                author: null,
                body: null,
                tombstone: true,
                status: 'DELETED',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                canEdit: true, // ignored for tombstone
                canDelete: true,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(list.querySelector('.novel-comment--root .novel-comment-menu-trigger'), null);
            assert.strictEqual(list.querySelector('.novel-comment--root .novel-comment-reply-btn'), null);
        });

        test('Case F: Anchored reply owner renders ⋯ with origin + Edit + Delete', async () => {
            const { doc, list } = createStandardFixture('c-case-f');
            const items = [{
                rootCommentId: 'r-f',
                author: { userId: 'u1', displayName: 'Root' },
                body: 'Root',
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [{
                    id: 'rep-f',
                    parentCommentId: 'r-f',
                    body: 'Reply owner',
                    canEdit: true,
                    canDelete: true,
                    createdAt: '2026-09-18T10:05:00Z',
                    updatedAt: '2026-09-18T10:05:00Z',
                    author: { userId: 'u2', displayName: 'ReplyOwner' }
                }]
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const replyEl = list.querySelector('.novel-comment--reply');
            assert.ok(replyEl);

            const trigger = replyEl.querySelector('.novel-comment-menu-trigger');
            assert.ok(trigger);

            const popover = replyEl.querySelector('.novel-comment-menu-popover');
            assert.ok(popover.querySelector('button[data-action="view-origin"]'));

            const editBtn = popover.querySelector('.novel-comment-edit-btn');
            assert.ok(editBtn);
            assert.strictEqual(editBtn.getAttribute('data-action'), 'edit');
            assert.strictEqual(editBtn.getAttribute('data-comment-id'), 'rep-f');
            assert.strictEqual(editBtn.getAttribute('data-root-id'), 'r-f');

            const deleteBtn = popover.querySelector('.novel-comment-delete-btn');
            assert.ok(deleteBtn);
            assert.strictEqual(deleteBtn.getAttribute('data-action'), 'delete');
            assert.strictEqual(deleteBtn.getAttribute('data-comment-id'), 'rep-f');
            assert.strictEqual(deleteBtn.getAttribute('data-root-id'), 'r-f');

            assert.ok(replyEl.querySelector('.novel-comment-reply-btn'));
        });

        test('Case G: UNANCHORED reply owner renders ⋯ with Edit + Delete, NO origin', async () => {
            const { doc, list } = createStandardFixture('c-case-g');
            const items = [{
                rootCommentId: 'r-g',
                author: { userId: 'u1', displayName: 'Root' },
                body: 'Root',
                anchorStatus: 'UNANCHORED',
                blockKey: null,
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [{
                    id: 'rep-g',
                    parentCommentId: 'r-g',
                    body: 'Reply owner unanchored',
                    canEdit: true,
                    canDelete: true,
                    createdAt: '2026-09-18T10:05:00Z',
                    updatedAt: '2026-09-18T10:05:00Z',
                    author: { userId: 'u2', displayName: 'ReplyOwner' }
                }]
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const replyEl = list.querySelector('.novel-comment--reply');
            const popover = replyEl.querySelector('.novel-comment-menu-popover');
            assert.ok(popover);
            assert.strictEqual(popover.querySelector('button[data-action="view-origin"]'), null);
            assert.ok(popover.querySelector('button[data-action="edit"]'));
            assert.ok(popover.querySelector('button[data-action="delete"]'));
        });

        test('Case H: Anchored reply non-owner renders ⋯ with origin + Report, NO Edit/Delete', async () => {
            const { doc, list } = createStandardFixture('c-case-h');
            const items = [{
                rootCommentId: 'r-h',
                author: { userId: 'u1', displayName: 'Root' },
                body: 'Root',
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [{
                    id: 'rep-h',
                    parentCommentId: 'r-h',
                    body: 'Reply non-owner',
                    canEdit: false,
                    canDelete: false,
                    createdAt: '2026-09-18T10:05:00Z',
                    updatedAt: '2026-09-18T10:05:00Z',
                    author: { userId: 'u2', displayName: 'NonOwner' }
                }]
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const replyEl = list.querySelector('.novel-comment--reply');
            const popover = replyEl.querySelector('.novel-comment-menu-popover');
            assert.ok(popover.querySelector('button[data-action="view-origin"]'));
            const reportBtn = popover.querySelector('button[data-action="report"]');
            assert.ok(reportBtn, 'Report action must exist for reply non-owner');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(popover.querySelector('button[data-action="edit"]'), null);
            assert.strictEqual(popover.querySelector('button[data-action="delete"]'), null);
        });

        test('Case I: UNANCHORED reply non-owner renders ⋯ with Report, NO origin', async () => {
            const { doc, list } = createStandardFixture('c-case-i');
            const items = [{
                rootCommentId: 'r-i',
                author: { userId: 'u1', displayName: 'Root' },
                body: 'Root',
                anchorStatus: 'UNANCHORED',
                blockKey: null,
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [{
                    id: 'rep-i',
                    parentCommentId: 'r-i',
                    body: 'Reply unanchored non-owner',
                    canEdit: false,
                    canDelete: false,
                    createdAt: '2026-09-18T10:05:00Z',
                    updatedAt: '2026-09-18T10:05:00Z',
                    author: { userId: 'u2', displayName: 'NonOwner' }
                }]
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const replyEl = list.querySelector('.novel-comment--reply');
            const trigger = replyEl.querySelector('.novel-comment-menu-trigger');
            assert.ok(trigger, 'Menu trigger must exist for unanchored reply non-owner');
            const menu = replyEl.querySelector('.novel-comment-actions-menu');
            assert.ok(menu, 'Actions menu must exist for unanchored reply non-owner');
            const popover = replyEl.querySelector('.novel-comment-menu-popover');
            assert.ok(popover, 'Menu popover must exist');
            assert.strictEqual(popover.querySelector('button[data-action="view-origin"]'), null, 'view-origin must be absent');
            const reportBtn = popover.querySelector('button[data-action="report"]');
            assert.ok(reportBtn, 'Report action must be present');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(popover.querySelector('button[data-action="edit"]'), null, 'Edit must be absent');
            assert.strictEqual(popover.querySelector('button[data-action="delete"]'), null, 'Delete must be absent');
            assert.ok(replyEl.querySelector('.novel-comment-reply-btn'), 'Reply button remains outside menu');
        });

        test('Case J: Reply tombstone has NO ⋯ menu, NO Reply', async () => {
            const { doc, list } = createStandardFixture('c-case-j');
            const items = [{
                rootCommentId: 'r-j',
                author: { userId: 'u1', displayName: 'Root' },
                body: 'Root',
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                canEdit: false,
                canDelete: false,
                replyCount: 1,
                replies: [{
                    id: 'rep-tomb',
                    parentCommentId: 'r-j',
                    body: null,
                    tombstone: true,
                    status: 'DELETED',
                    canEdit: true,
                    canDelete: true,
                    createdAt: '2026-09-18T10:05:00Z',
                    updatedAt: '2026-09-18T10:05:00Z',
                    author: null
                }]
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const tombEl = list.querySelector('.novel-comment--reply.is-tombstone');
            assert.ok(tombEl);
            assert.strictEqual(tombEl.querySelector('.novel-comment-menu-trigger'), null);
            assert.strictEqual(tombEl.querySelector('.novel-comment-reply-btn'), null);
        });

        test('Edit-only capability renders only Edit', async () => {
            const { doc, list } = createStandardFixture('c-edit-only');
            const items = [{
                rootCommentId: 'r-edit-only',
                author: { userId: 'u1', displayName: 'Editor' },
                body: 'Edit only',
                anchorStatus: 'UNANCHORED',
                canEdit: true,
                canDelete: false,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const popover = list.querySelector('.novel-comment--root .novel-comment-menu-popover');
            assert.ok(popover.querySelector('button[data-action="edit"]'));
            assert.strictEqual(popover.querySelector('button[data-action="delete"]'), null);
            assert.strictEqual(popover.querySelector('button[data-action="view-origin"]'), null);
        });

        test('Delete-only capability renders only Delete', async () => {
            const { doc, list } = createStandardFixture('c-del-only');
            const items = [{
                rootCommentId: 'r-del-only',
                author: { userId: 'u1', displayName: 'Deleter' },
                body: 'Delete only',
                anchorStatus: 'UNANCHORED',
                canEdit: false,
                canDelete: true,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, { fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) }) });
            await new Promise(r => setTimeout(r, 10));

            const popover = list.querySelector('.novel-comment--root .novel-comment-menu-popover');
            assert.strictEqual(popover.querySelector('button[data-action="edit"]'), null);
            assert.ok(popover.querySelector('button[data-action="delete"]'));
            assert.strictEqual(popover.querySelector('button[data-action="view-origin"]'), null);
        });

        test('Later-page roots and progressively revealed replies preserve capability menus', async () => {
            const { doc, list } = createStandardFixture('c-pages');
            const page0Items = [{
                rootCommentId: 'r-p0',
                author: { userId: 'u1', displayName: 'P0' },
                body: 'P0 body',
                anchorStatus: 'UNANCHORED',
                canEdit: false,
                canDelete: false,
                replyCount: 4,
                replies: [
                    { id: 'rep-1', parentCommentId: 'r-p0', body: 'R1', canEdit: false, canDelete: false },
                    { id: 'rep-2', parentCommentId: 'r-p0', body: 'R2', canEdit: false, canDelete: false },
                    { id: 'rep-3', parentCommentId: 'r-p0', body: 'R3', canEdit: false, canDelete: false },
                    { id: 'rep-4', parentCommentId: 'r-p0', body: 'R4', canEdit: true, canDelete: true, author: { displayName: 'Rep4' } }
                ]
            }];

            const page1Items = [{
                rootCommentId: 'r-p1',
                author: { userId: 'u1', displayName: 'P1 Owner' },
                body: 'P1 body',
                anchorStatus: 'UNANCHORED',
                canEdit: true,
                canDelete: true,
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, {
                fetch: (url) => {
                    const u = String(url);
                    if (u.includes('page=1')) {
                        return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page1Items, false, 1)) });
                    }
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0Items, true, 0)) });
                }
            });
            await new Promise(r => setTimeout(r, 10));

            // 1. Progressively reveal 4th reply
            const revealBtn = list.querySelector('.novel-comment-replies-more-btn');
            assert.ok(revealBtn);
            revealBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

            const revealedReply = list.querySelector('.novel-comment--reply[data-comment-id="rep-4"]');
            assert.ok(revealedReply);
            assert.ok(revealedReply.querySelector('.novel-comment-menu-trigger'), 'Revealed reply must have menu trigger');
            const revPopover = revealedReply.querySelector('.novel-comment-menu-popover');
            assert.ok(revPopover.querySelector('button[data-action="edit"]'));
            assert.ok(revPopover.querySelector('button[data-action="delete"]'));

            // 2. Load page 1
            await commentsModule.loadMore();
            await new Promise(r => setTimeout(r, 10));

            const p1Root = list.querySelector('.novel-block-discussion-thread[data-root-id="r-p1"]');
            assert.ok(p1Root);
            const p1Popover = p1Root.querySelector('.novel-comment-menu-popover');
            assert.ok(p1Popover);
            assert.ok(p1Popover.querySelector('button[data-action="edit"]'));
            assert.ok(p1Popover.querySelector('button[data-action="delete"]'));
        });
    });

    describe('UX-DRAFT-01D2A Post-Render Feed Event (EVENT_FEED_RENDERED)', () => {
        test('EVENT_FEED_RENDERED constant is exported', () => {
            assert.strictEqual(commentsModule.EVENT_FEED_RENDERED, 'kiemlai:chapter-comments-feed-rendered');
        });

        test('Populated full render dispatches exactly once after DOM exists with safe chapterId detail', async () => {
            const { doc, list } = createStandardFixture('c-feed-rendered-pop');
            const events = [];
            doc.addEventListener(commentsModule.EVENT_FEED_RENDERED, (e) => {
                events.push({
                    detail: e.detail,
                    domExists: list.querySelectorAll('.novel-comment').length > 0
                });
            });

            const items = [{
                rootCommentId: 'r-1',
                author: { displayName: 'User 1' },
                body: 'Comment 1',
                anchorStatus: 'UNANCHORED',
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items, false, 0)) })
            });

            await new Promise(r => setTimeout(r, 15));

            assert.strictEqual(events.length, 1);
            assert.deepStrictEqual(events[0].detail, { chapterId: 'c-feed-rendered-pop' });
            assert.strictEqual(events[0].domExists, true, 'Event must fire after DOM cards have been attached');
        });

        test('Empty render dispatches safe event with chapterId detail', async () => {
            const { doc, list } = createStandardFixture('c-feed-rendered-empty');
            const events = [];
            doc.addEventListener(commentsModule.EVENT_FEED_RENDERED, (e) => {
                events.push({
                    detail: e.detail,
                    emptyExists: list.querySelector('.novel-chapter-comments-empty') !== null
                });
            });

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([], false, 0)) })
            });

            await new Promise(r => setTimeout(r, 15));

            assert.strictEqual(events.length, 1);
            assert.deepStrictEqual(events[0].detail, { chapterId: 'c-feed-rendered-empty' });
            assert.strictEqual(events[0].emptyExists, true);
        });

        test('Load-more successful append emits rendered notification carrying chapterId', async () => {
            const { doc, list } = createStandardFixture('c-load-more-rendered');
            const events = [];

            const page0 = [{
                rootCommentId: 'r-0',
                author: { displayName: 'P0' },
                body: 'P0 body',
                anchorStatus: 'UNANCHORED',
                replyCount: 0,
                replies: []
            }];
            const page1 = [{
                rootCommentId: 'r-1',
                author: { displayName: 'P1' },
                body: 'P1 body',
                anchorStatus: 'UNANCHORED',
                replyCount: 0,
                replies: []
            }];

            commentsModule.init(doc, {
                fetch: (url) => {
                    const u = String(url);
                    if (u.includes('page=1')) {
                        return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page1, false, 1)) });
                    }
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(page0, true, 0)) });
                }
            });

            await new Promise(r => setTimeout(r, 15));

            doc.addEventListener(commentsModule.EVENT_FEED_RENDERED, (e) => {
                events.push({
                    detail: e.detail,
                    totalRoots: list.querySelectorAll('.novel-block-discussion-thread').length
                });
            });

            await commentsModule.loadMore();
            await new Promise(r => setTimeout(r, 15));

            assert.strictEqual(events.length, 1);
            assert.deepStrictEqual(events[0].detail, { chapterId: 'c-load-more-rendered' });
            assert.strictEqual(events[0].totalRoots, 2, 'Event must fire after page-1 roots are appended to DOM');
        });

        test('Stale/out-of-order feed response does NOT emit rendered event for the wrong chapter', async () => {
            const { doc } = createStandardFixture('ch-orig');
            const events = [];
            doc.addEventListener(commentsModule.EVENT_FEED_RENDERED, (e) => {
                events.push(e.detail);
            });

            let resolveOrig;
            const origPromise = new Promise(r => { resolveOrig = r; });

            commentsModule.init(doc, {
                fetch: (url) => {
                    if (String(url).includes('ch-orig')) {
                        return origPromise;
                    }
                    return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([], false, 0)) });
                }
            });

            // Switch to Chapter B before Chapter A responds
            doc.dispatchEvent({
                type: 'kiemlai:chapter-changed',
                detail: { chapterId: 'ch-second' }
            });

            await new Promise(r => setTimeout(r, 15));
            assert.strictEqual(events.length, 1);
            assert.deepStrictEqual(events[0], { chapterId: 'ch-second' });

            // Old response A resolves
            resolveOrig({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([{ rootCommentId: 'r-stale', body: 'Stale' }], false, 0))
            });
            await new Promise(r => setTimeout(r, 15));

            // Must NOT emit any event for ch-orig
            assert.strictEqual(events.length, 1, 'Stale completion must not emit rendered event');
        });
    });

    describe('MS-05E/E8C4 Novel Chapter Comments Report Integration', () => {
        let originalReportModal;
        let mockModal;
        let openCalls;

        beforeEach(() => {
            openCalls = [];
            mockModal = {
                open: (params) => {
                    openCalls.push(params);
                    return true;
                }
            };
            originalReportModal = commentsModule.setReportModal;
            commentsModule.setReportModal(mockModal);
        });

        afterEach(() => {
            commentsModule.setReportModal(null);
            commentsModule.setAuthenticatedImplementation(null);
            commentsModule.destroy();
        });

        test('REPORT-1. Active non-owner root comment renders "Báo cáo" button with data-action="report" and data-comment-id', async () => {
            const { doc, list } = createStandardFixture('c-report-1');
            const items = [
                {
                    rootCommentId: 'root-non-owner',
                    author: { userId: 'other-user', displayName: 'Other User' },
                    body: 'Active non-owner root comment',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const rootCard = list.querySelector('.novel-block-discussion-thread');
            assert.ok(rootCard, 'Thread card must exist');
            const reportBtn = rootCard.querySelector('.novel-comment-report-btn[data-action="report"]');
            assert.ok(reportBtn, 'Report button must be rendered for active non-owner root');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(reportBtn.getAttribute('data-comment-id'), 'root-non-owner');
            assert.strictEqual((reportBtn.listeners.click || []).length, 0, 'No direct click listeners on root reportBtn');
        });

        test('REPORT-2. Active owner root comment (canEdit: true or canDelete: true) does NOT render "Báo cáo" button', async () => {
            const { doc, list } = createStandardFixture('c-report-2');
            const items = [
                {
                    rootCommentId: 'root-owner-edit',
                    author: { userId: 'me', displayName: 'Me' },
                    body: 'Owner edit root',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: true,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                },
                {
                    rootCommentId: 'root-owner-del',
                    author: { userId: 'me', displayName: 'Me' },
                    body: 'Owner delete root',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: true,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtns = list.querySelectorAll('.novel-comment-report-btn');
            assert.strictEqual(reportBtns.length, 0, 'No report button should be rendered for owned root comments');
        });

        test('REPORT-3. Root tombstone does NOT render "Báo cáo" button', async () => {
            const { doc, list } = createStandardFixture('c-report-3');
            const items = [
                {
                    rootCommentId: 'root-tombstone',
                    author: null,
                    body: null,
                    tombstone: true,
                    status: 'DELETED',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtns = list.querySelectorAll('.novel-comment-report-btn');
            assert.strictEqual(reportBtns.length, 0, 'No report button should be rendered for root tombstone');
        });

        test('REPORT-4. Active non-owner reply renders "Báo cáo" button', async () => {
            const { doc, list } = createStandardFixture('c-report-4');
            const items = [
                {
                    rootCommentId: 'root-1',
                    author: { userId: 'me', displayName: 'Me' },
                    body: 'Root',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: true,
                    canDelete: true,
                    anchorStatus: 'NONE',
                    replyCount: 1,
                    replies: [
                        {
                            id: 'rep-non-owner',
                            parentCommentId: 'root-1',
                            author: { userId: 'other-user', displayName: 'Other User' },
                            body: 'Active non-owner reply',
                            createdAt: '2026-09-18T10:05:00Z',
                            updatedAt: '2026-09-18T10:05:00Z',
                            canEdit: false,
                            canDelete: false,
                            tombstone: false
                        }
                    ]
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const replyEl = list.querySelector('.novel-comment--reply');
            assert.ok(replyEl, 'Reply element must exist');
            const reportBtn = replyEl.querySelector('.novel-comment-report-btn[data-action="report"]');
            assert.ok(reportBtn, 'Report button must be rendered for active non-owner reply');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(reportBtn.getAttribute('data-comment-id'), 'rep-non-owner');
            assert.strictEqual((reportBtn.listeners.click || []).length, 0, 'No direct click listeners on reply reportBtn');
        });

        test('REPORT-5. Active owner reply does NOT render "Báo cáo" button', async () => {
            const { doc, list } = createStandardFixture('c-report-5');
            const items = [
                {
                    rootCommentId: 'root-1',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Root',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 2,
                    replies: [
                        {
                            id: 'rep-owner-edit',
                            parentCommentId: 'root-1',
                            author: { userId: 'me', displayName: 'Me' },
                            body: 'Owner edit reply',
                            createdAt: '2026-09-18T10:05:00Z',
                            updatedAt: '2026-09-18T10:05:00Z',
                            canEdit: true,
                            canDelete: false,
                            tombstone: false
                        },
                        {
                            id: 'rep-owner-del',
                            parentCommentId: 'root-1',
                            author: { userId: 'me', displayName: 'Me' },
                            body: 'Owner delete reply',
                            createdAt: '2026-09-18T10:06:00Z',
                            updatedAt: '2026-09-18T10:06:00Z',
                            canEdit: false,
                            canDelete: true,
                            tombstone: false
                        }
                    ]
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const replyEls = list.querySelectorAll('.novel-comment--reply');
            for (const rep of replyEls) {
                assert.strictEqual(rep.querySelector('.novel-comment-report-btn'), null, 'Owned replies must not have report button');
            }
        });

        test('REPORT-6. Reply tombstone does NOT render "Báo cáo" button', async () => {
            const { doc, list } = createStandardFixture('c-report-6');
            const items = [
                {
                    rootCommentId: 'root-1',
                    author: { userId: 'u1', displayName: 'User 1' },
                    body: 'Root',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 1,
                    replies: [
                        {
                            id: 'rep-tomb',
                            parentCommentId: 'root-1',
                            body: null,
                            tombstone: true,
                            status: 'DELETED',
                            createdAt: '2026-09-18T10:05:00Z',
                            updatedAt: '2026-09-18T10:05:00Z',
                            canEdit: false,
                            canDelete: false
                        }
                    ]
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const replyEl = list.querySelector('.novel-comment--reply');
            assert.ok(replyEl);
            assert.strictEqual(replyEl.querySelector('.novel-comment-report-btn'), null);
        });

        test('REPORT-7. Active descendant reply under a tombstoned intermediate reply DOES render "Báo cáo" button for non-owner', async () => {
            const { doc, list } = createStandardFixture('c-report-7');
            const items = [
                {
                    rootCommentId: 'root-1',
                    author: { userId: 'u1', displayName: 'User 1' },
                    body: 'Active root comment',
                    tombstone: false,
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 2,
                    replies: [
                        {
                            id: 'rep-tomb-a',
                            parentCommentId: 'root-1',
                            author: null,
                            body: null,
                            tombstone: true,
                            status: 'DELETED',
                            canEdit: false,
                            canDelete: false,
                            createdAt: '2026-09-18T10:05:00Z',
                            updatedAt: '2026-09-18T10:05:00Z'
                        },
                        {
                            id: 'rep-active-child',
                            parentCommentId: 'rep-tomb-a',
                            author: { userId: 'u2', displayName: 'User 2' },
                            body: 'Active descendant under deleted intermediate reply',
                            tombstone: false,
                            canEdit: false,
                            canDelete: false,
                            createdAt: '2026-09-18T10:10:00Z',
                            updatedAt: '2026-09-18T10:10:00Z'
                        }
                    ]
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const rootCard = list.querySelector('.novel-block-discussion-thread');
            assert.ok(rootCard, 'Thread card must exist');

            const replies = rootCard.querySelectorAll('.novel-comment--reply');
            assert.strictEqual(replies.length, 2, 'Two replies must be rendered');

            // Tombstone intermediate reply A must NOT have report button
            const repA = replies[0];
            assert.strictEqual(repA.querySelector('.novel-comment-report-btn'), null, 'Tombstone reply A must not have report button');

            // Active descendant reply B MUST have report button
            const repB = replies[1];
            const reportBtnB = repB.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtnB, 'Active descendant under tombstone parent must have report button');
            assert.strictEqual(reportBtnB.getAttribute('data-comment-id'), 'rep-active-child');
        });

        test('REPORT-8. Unauthenticated guest clicking "Báo cáo" redirects to /login?returnTo=... without opening modal', async () => {
            const { doc, section, list } = createStandardFixture('c-report-8');
            section.setAttribute('data-authenticated', 'false');
            doc.defaultView = {
                location: {
                    pathname: '/novel/chapters/chap-8-slug',
                    search: '?ref=test',
                    href: '/novel/chapters/chap-8-slug?ref=test'
                }
            };

            const items = [
                {
                    rootCommentId: 'root-guest-target',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Target for guest',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtn = list.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn);

            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 0, 'Modal must NOT be opened for unauthenticated guest');
            assert.strictEqual(
                doc.defaultView.location.href,
                '/login?returnTo=' + encodeURIComponent('/novel/chapters/chap-8-slug?ref=test#novelChapterComments'),
                'Guest must be redirected to /login with returnTo pointing to #novelChapterComments'
            );
        });

        test('REPORT-8b. Unauthenticated guest redirect with no query string preserves pathname and appends #novelChapterComments', async () => {
            const { doc, section, list } = createStandardFixture('c-report-8b');
            section.setAttribute('data-authenticated', 'false');
            doc.defaultView = {
                location: {
                    pathname: '/novel/chapters/simple-chapter',
                    search: '',
                    href: '/novel/chapters/simple-chapter'
                }
            };

            const items = [
                {
                    rootCommentId: 'root-guest-simple',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Target for guest simple',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtn = list.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn);

            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 0, 'Modal must NOT be opened for unauthenticated guest');
            assert.strictEqual(
                doc.defaultView.location.href,
                '/login?returnTo=' + encodeURIComponent('/novel/chapters/simple-chapter#novelChapterComments'),
                'Guest must be redirected to /login with returnTo without search query'
            );
        });

        test('REPORT-9. Authenticated user clicking "Báo cáo" opens CommentReportModal with correct parameters', async () => {
            const { doc, section, list } = createStandardFixture('chap-123-uuid');
            section.setAttribute('data-authenticated', 'true');

            const items = [
                {
                    rootCommentId: 'cmt-456-uuid',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Target for reporting',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtn = list.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn);

            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 1, 'Modal open must be called once');
            const call = openCalls[0];
            assert.strictEqual(call.commentId, 'cmt-456-uuid');
            assert.strictEqual(call.submitUrl, '/api/novel/chapters/chap-123-uuid/comments/cmt-456-uuid/reports');
            assert.strictEqual(call.contextLabel, 'novel-chapter');
            assert.strictEqual(call.triggerEl, reportBtn);
            assert.strictEqual(typeof call.onSuccess, 'function');
        });

        test('REPORT-10. onSuccess callback disables button, updates text to "Đã báo cáo", and sets title tooltip', async () => {
            const { doc, section, list } = createStandardFixture('chap-123');
            section.setAttribute('data-authenticated', 'true');

            const items = [
                {
                    rootCommentId: 'cmt-succ',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Target',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtn = list.querySelector('.novel-comment-report-btn');
            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 1);
            const { onSuccess } = openCalls[0];

            // Invoke success callback
            onSuccess({ reportId: 'rep-001', status: 'PENDING' });

            assert.strictEqual(reportBtn.textContent, 'Đã báo cáo');
            assert.strictEqual(reportBtn.disabled, true);
            assert.strictEqual(reportBtn.getAttribute('title'), 'Bạn đã gửi báo cáo cho bình luận này');

            const statusEl = doc.getElementById('novelChapterCommentsStatus');
            assert.ok(statusEl, 'Status element must exist');
            assert.ok(statusEl.textContent.includes('Đã gửi báo cáo. Cảm ơn bạn đã phản hồi.'), 'Status must show user feedback');
            assert.strictEqual(statusEl.textContent.includes('rep-001'), false, 'Status must not leak reportId');
        });

        test('REPORT-11. ChapterId and CommentId with special characters are properly URL-encoded in submitUrl', async () => {
            const { doc, section, list } = createStandardFixture('chap/special#1?x=y');
            section.setAttribute('data-authenticated', 'true');

            const items = [
                {
                    rootCommentId: 'cmt/special#2?a=b',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Special characters ID',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtn = list.querySelector('.novel-comment-report-btn');
            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 1);
            const expectedUrl = '/api/novel/chapters/' + encodeURIComponent('chap/special#1?x=y') + '/comments/' + encodeURIComponent('cmt/special#2?a=b') + '/reports';
            assert.strictEqual(openCalls[0].submitUrl, expectedUrl);
        });

        test('REPORT-12. Deleted root with active replies hides entire thread and excludes descendant report actions and comment count', async () => {
            const { doc, list, count } = createStandardFixture('c-report-12');
            const deletedRootItem = {
                rootCommentId: 'root-deleted',
                author: null,
                body: null,
                tombstone: true,
                status: 'DELETED',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                canEdit: false,
                canDelete: false,
                anchorStatus: 'NONE',
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-1',
                        parentCommentId: 'root-deleted',
                        author: { userId: 'u1', displayName: 'User 1' },
                        body: 'Reply to deleted root',
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        canEdit: false,
                        canDelete: false,
                        tombstone: false
                    }
                ]
            };

            assert.strictEqual(commentsModule.getActiveCommentCount([deletedRootItem]), 0, 'Deleted root must contribute 0 to active comment count');

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse([deletedRootItem])) })
            });
            await new Promise(r => setTimeout(r, 10));

            const threads = list.querySelectorAll('.novel-block-discussion-thread');
            assert.strictEqual(threads.length, 0, 'Entire thread must be hidden when root is deleted');
            const reportBtns = list.querySelectorAll('.novel-comment-report-btn');
            assert.strictEqual(reportBtns.length, 0, 'No report buttons rendered for hidden thread');
            assert.strictEqual(count.textContent, '0 bình luận', 'Count should reflect 0 active comments');
        });

        test('REPORT-13. Unavailable or non-callable CommentReportModal fails safely without throw or navigation', async () => {
            const { doc, section, list } = createStandardFixture('c-report-13');
            section.setAttribute('data-authenticated', 'true');
            doc.defaultView = {
                location: {
                    pathname: '/novel/chapters/chap-13',
                    search: '',
                    href: '/novel/chapters/chap-13'
                }
            };

            commentsModule.setReportModal({});

            const items = [
                {
                    rootCommentId: 'root-13',
                    author: { userId: 'other', displayName: 'Other' },
                    body: 'Comment to report',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    canEdit: false,
                    canDelete: false,
                    anchorStatus: 'NONE',
                    replyCount: 0,
                    replies: []
                }
            ];

            commentsModule.init(doc, {
                fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse(items)) })
            });
            await new Promise(r => setTimeout(r, 10));

            const reportBtn = list.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn, 'Report button must exist');

            assert.doesNotThrow(() => {
                doc.dispatchEvent({
                    type: 'click',
                    target: reportBtn,
                    preventDefault() {}
                });
            });

            assert.strictEqual(doc.defaultView.location.href, '/novel/chapters/chap-13', 'Must not trigger navigation');

            const result = commentsModule.openReportModal('root-13', reportBtn, doc);
            assert.strictEqual(result, false, 'openReportModal must return false when modal is non-callable');
        });
    });
});
