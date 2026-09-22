const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const drawerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-drawer.js'));

// ============================================================================
// Lightweight DOM Test Fixtures
// ============================================================================

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

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.parentElement = null;
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

    get nodeType() {
        return 1;
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

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        this.childNodes.push(child);
        return child;
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

    insertBefore(newChild, referenceChild) {
        if (!referenceChild) {
            return this.appendChild(newChild);
        }
        const idx = this.childNodes.indexOf(referenceChild);
        if (idx !== -1) {
            newChild.parentNode = this;
            newChild.parentElement = this;
            this.childNodes.splice(idx, 0, newChild);
            return newChild;
        }
        return this.appendChild(newChild);
    }

    replaceChild(newChild, oldChild) {
        const idx = this.childNodes.indexOf(oldChild);
        if (idx !== -1) {
            oldChild.parentNode = null;
            oldChild.parentElement = null;
            newChild.parentNode = this;
            newChild.parentElement = this;
            this.childNodes[idx] = newChild;
            return oldChild;
        }
        return null;
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
        try {
            evt.currentTarget = this;
        } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }

    focus() {
        this.isFocused = true;
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
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

    querySelector(selector) {
        return querySelectorAllDeep(this, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this, selector);
    }
}

function matchesSingleSelector(el, sel) {
    if (!el || !el.tagName) return false;
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
    if (sel.startsWith('[') && sel.endsWith(']')) {
        const raw = sel.slice(1, -1);
        const eqIdx = raw.indexOf('=');
        if (eqIdx === -1) {
            return el.getAttribute(raw) !== null;
        }
        const name = raw.slice(0, eqIdx);
        const val = raw.slice(eqIdx + 1).replace(/^["']|["']$/g, '');
        return el.getAttribute(name) === val;
    }
    let tag = null;
    let classPart = sel;
    if (!sel.startsWith('.')) {
        const dotIdx = sel.indexOf('.');
        if (dotIdx !== -1) {
            tag = sel.slice(0, dotIdx);
            classPart = sel.slice(dotIdx);
        } else {
            return el.tagName.toLowerCase() === sel.toLowerCase();
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
        this.activeElement = this.body;
        this.defaultView = {
            innerWidth: 1024,
            innerHeight: 768
        };
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    createTextNode(text) {
        return {
            nodeType: 3,
            textContent: String(text != null ? text : ''),
            parentNode: null,
            parentElement: null
        };
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

    contains(node) {
        let cur = node;
        while (cur) {
            if (cur === this || cur === this.documentElement || cur === this.body) return true;
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
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
        try {
            evt.currentTarget = this;
        } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

function setupChapterDOM() {
    const doc = new FakeDocument();

    // 1. Chapter body (canonical prose)
    const chapterBody = doc.createElement('article');
    chapterBody.setAttribute('class', 'novel-reader-chapter-body');
    chapterBody.setAttribute('data-chapter-id', '11111111-1111-1111-1111-111111111111');
    chapterBody.setAttribute('data-content-version', '1');

    const blockA = doc.createElement('p');
    blockA.setAttribute('data-reader-block-key', 'blk-0123456789abcdef-1');
    blockA.textContent = 'Đoạn văn A nội dung chính.';
    chapterBody.appendChild(blockA);

    const blockB = doc.createElement('p');
    blockB.setAttribute('data-reader-block-key', 'blk-fedcba9876543210-1');
    blockB.textContent = 'Đoạn văn B nội dung chính.';
    chapterBody.appendChild(blockB);

    doc.body.appendChild(chapterBody);

    // 2. Backdrop
    const backdrop = doc.createElement('div');
    backdrop.setAttribute('id', drawerModule.BACKDROP_ID);
    backdrop.setAttribute('class', 'novel-block-discussion-backdrop');
    backdrop.setAttribute('aria-hidden', 'true');
    backdrop.hidden = true;
    doc.body.appendChild(backdrop);

    // 3. Discussion Drawer Aside
    const drawer = doc.createElement('aside');
    drawer.setAttribute('id', drawerModule.DRAWER_ID);
    drawer.setAttribute('class', 'novel-block-discussion-drawer');
    drawer.setAttribute('role', 'dialog');
    drawer.setAttribute('aria-modal', 'true');
    drawer.setAttribute('aria-labelledby', drawerModule.TITLE_ID);
    drawer.setAttribute('aria-hidden', 'true');
    drawer.hidden = true;

    // Header
    const header = doc.createElement('header');
    header.setAttribute('class', 'novel-block-discussion-header');

    const headerTitle = doc.createElement('div');
    headerTitle.setAttribute('class', 'novel-block-discussion-header-title');

    const title = doc.createElement('h2');
    title.setAttribute('id', drawerModule.TITLE_ID);
    title.setAttribute('class', 'novel-block-discussion-title');
    title.textContent = 'Thảo luận';
    headerTitle.appendChild(title);

    const count = doc.createElement('span');
    count.setAttribute('id', drawerModule.COUNT_ID);
    count.setAttribute('class', 'novel-block-discussion-count');
    count.setAttribute('aria-live', 'polite');
    headerTitle.appendChild(count);

    header.appendChild(headerTitle);

    const closeBtn = doc.createElement('button');
    closeBtn.setAttribute('type', 'button');
    closeBtn.setAttribute('id', drawerModule.CLOSE_BTN_ID);
    closeBtn.setAttribute('class', 'novel-block-discussion-close');
    closeBtn.setAttribute('aria-label', 'Đóng thảo luận');
    header.appendChild(closeBtn);

    drawer.appendChild(header);

    // Passage section
    const passageSection = doc.createElement('section');
    passageSection.setAttribute('class', 'novel-block-discussion-passage-section');
    passageSection.setAttribute('aria-label', 'Đoạn văn đang thảo luận');

    const passageLabel = doc.createElement('div');
    passageLabel.setAttribute('class', 'novel-block-discussion-passage-label');
    passageLabel.textContent = 'Đoạn văn';
    passageSection.appendChild(passageLabel);

    const passage = doc.createElement('div');
    passage.setAttribute('id', drawerModule.PASSAGE_ID);
    passage.setAttribute('class', 'novel-block-discussion-passage');
    passageSection.appendChild(passage);

    drawer.appendChild(passageSection);

    // Content section
    const content = doc.createElement('section');
    content.setAttribute('id', drawerModule.CONTENT_ID);
    content.setAttribute('class', 'novel-block-discussion-content');
    content.setAttribute('aria-label', 'Danh sách thảo luận');
    drawer.appendChild(content);

    doc.body.appendChild(drawer);

    return {
        doc,
        chapterBody,
        blockA,
        blockB,
        backdrop,
        drawer,
        closeBtn,
        count,
        passage,
        content
    };
}

describe('MS-05E5G2 Wattpad-Style Novel Block Discussion Drawer Tests', () => {

    beforeEach(() => {
        drawerModule.resetDrawerState();
    });

    test('1. init is idempotent: multiple init calls bind listeners only once', () => {
        const { doc } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);
        const listenersCountBefore = doc.listeners['kiemlai:block-discussion-requested'] ? doc.listeners['kiemlai:block-discussion-requested'].length : 0;

        drawerModule.initReaderBlockDiscussionDrawer(doc);
        const listenersCountAfter = doc.listeners['kiemlai:block-discussion-requested'] ? doc.listeners['kiemlai:block-discussion-requested'].length : 0;

        assert.strictEqual(listenersCountBefore, 1);
        assert.strictEqual(listenersCountAfter, 1);
    });

    test('2. valid discussion event opens exactly one drawer', () => {
        const { doc, drawer, backdrop } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(drawer.classList.contains('is-open'), false);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Đoạn văn A nội dung chính.',
                threadCount: 2
            }
        });

        assert.strictEqual(drawer.hidden, false);
        assert.strictEqual(drawer.getAttribute('aria-hidden'), 'false');
        assert.strictEqual(drawer.classList.contains('is-open'), true);
        assert.strictEqual(backdrop.hidden, false);
        assert.strictEqual(backdrop.classList.contains('is-open'), true);
    });

    test('3. invalid event detail is ignored safely without opening drawer or throwing', () => {
        const { doc, drawer } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        // Missing blockKey
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: '   ',
                canonicalText: 'Text',
                threadCount: 0
            }
        });
        assert.strictEqual(drawer.hidden, true);

        // Negative contentVersion
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: -1,
                blockKey: 'blk-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });
        assert.strictEqual(drawer.hidden, true);

        // Non-string canonicalText
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-1',
                canonicalText: null,
                threadCount: 0
            }
        });
        assert.strictEqual(drawer.hidden, true);

        // Negative threadCount
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-1',
                canonicalText: 'Text',
                threadCount: -1
            }
        });
        assert.strictEqual(drawer.hidden, true);
    });

    test('4. drawer is outside .novel-reader-chapter-body and template contract verified', () => {
        const { chapterBody, drawer, backdrop } = setupChapterDOM();
        assert.strictEqual(drawer.closest('.novel-reader-chapter-body'), null);
        assert.strictEqual(chapterBody.querySelector('#' + drawerModule.DRAWER_ID), null);
        assert.strictEqual(backdrop.hasAttribute('tabindex'), false);
        assert.strictEqual(drawer.getAttribute('role'), 'dialog');
        assert.strictEqual(drawer.getAttribute('aria-modal'), 'true');
        assert.strictEqual(drawer.getAttribute('aria-labelledby'), drawerModule.TITLE_ID);
    });

    test('5. provisional canonicalText appears immediately before fetch resolves', async () => {
        const { doc, passage } = setupChapterDOM();
        let resolveFetch;
        let fetchResolved = false;

        const fetchGate = new Promise(resolve => {
            resolveFetch = resolve;
        });

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => {
                const response = await fetchGate;
                fetchResolved = true;
                return response;
            }
        });

        const loadedPromise = new Promise(resolve => {
            doc.addEventListener(drawerModule.EVENT_DISCUSSION_LOADED, function (event) {
                resolve(event);
            });
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional client text.',
                threadCount: 0
            }
        });

        // 1. Immediately assert provisional state before fetch resolution
        assert.strictEqual(fetchResolved, false);
        assert.strictEqual(passage.textContent, 'Provisional client text.');

        // 2. Resolve the controlled fetch
        resolveFetch({
            ok: true,
            status: 200,
            json: async () => ({
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Authoritative server text.',
                threadCount: 0,
                threads: []
            })
        });

        // 3. Await authoritative completion
        await loadedPromise;

        // 4. Assert authoritative resolved state
        assert.strictEqual(fetchResolved, true);
        assert.strictEqual(passage.textContent, 'Authoritative server text.');
    });

    test('6. canonical passage uses textContent, not HTML interpretation', () => {
        const { doc, passage } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: '<strong>Bold HTML probe</strong>',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: '<strong>Provisional</strong>',
                threadCount: 0
            }
        });

        assert.strictEqual(passage.textContent, '<strong>Provisional</strong>');
        assert.strictEqual(passage.querySelector('strong'), null);
    });

    test('7 & 8 & 9. correct encoded GET URL, Accept: application/json, and exactly one fetch per open', async () => {
        const { doc } = setupChapterDOM();
        const calls = [];

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async (url, opts) => {
                calls.push({ url, opts });
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: 'ch/1',
                        contentVersion: 1,
                        blockKey: 'blk/1',
                        canonicalText: 'Canonical',
                        threadCount: 0,
                        threads: []
                    })
                };
            }
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: 'ch/1',
                contentVersion: 1,
                blockKey: 'blk/1',
                canonicalText: 'Canonical',
                threadCount: 0
            }
        });

        assert.strictEqual(calls.length, 1);
        assert.strictEqual(calls[0].url, '/api/novel/chapters/ch%2F1/comments/blocks/blk%2F1');
        assert.strictEqual(calls[0].opts.method, 'GET');
        assert.strictEqual(calls[0].opts.headers['Accept'], 'application/json');
    });

    test('10 & 11. successful response replaces provisional passage and uses server contentVersion', async () => {
        const { doc, passage } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 5,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Authoritative server passage text.',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional client text.',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(passage.textContent, 'Authoritative server passage text.');
        assert.strictEqual(drawerModule.getActiveContext().contentVersion, 5);
    });

    test('12. threadCount reflects root threads only and count display reflects commentCount', async () => {
        const { doc, count } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 2,
                    commentCount: 4,
                    threads: [
                        { root: { id: 'r1', body: 'Root 1' }, replies: [{ id: 'rep1', body: 'Reply 1' }, { id: 'rep2', body: 'Reply 2' }] },
                        { root: { id: 'r2', body: 'Root 2' }, replies: [] }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(drawerModule.getActiveContext().threadCount, 2);
        assert.strictEqual(drawerModule.getActiveContext().commentCount, 4);
        assert.strictEqual(count.textContent, '4 bình luận');
    });

    test('12b. 1 root + 2 active replies + 1 tombstone displays 3 bình luận and preserves threadCount=1', async () => {
        const { doc, count } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    commentCount: 3,
                    threads: [
                        {
                            root: { id: 'r1', body: 'Root 1' },
                            replies: [
                                { id: 'rep1', body: 'Active Reply 1' },
                                { id: 'rep2', body: 'Active Reply 2' },
                                { id: 'rep3', body: null, tombstone: true }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1,
                commentCount: 3
            }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(drawerModule.getActiveContext().threadCount, 1);
        assert.strictEqual(drawerModule.getActiveContext().commentCount, 3);
        assert.strictEqual(count.textContent, '3 bình luận');
    });

    test('13 & 14 & 34. root and reply bodies rendered safely as text (no script execution)', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'r1', body: '<script>alert("root")</script>' },
                            replies: [
                                { id: 'rep1', body: '<img src=x onerror=alert("reply")>' }
                            ]
                        }
                    ]
                })
            })
        });

        const loadedPromise = new Promise(r => doc.addEventListener('kiemlai:block-discussion-loaded', r));

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await loadedPromise;

        const scriptEl = content.querySelector('script');
        const imgEl = content.querySelector('img');
        assert.strictEqual(scriptEl, null);
        assert.strictEqual(imgEl, null);

        const rootBodyEl = content.querySelector('.novel-comment--root .novel-comment-body');
        const replyBodyEl = content.querySelector('.novel-comment--reply .novel-comment-body');
        assert.strictEqual(rootBodyEl.textContent, '<script>alert("root")</script>');
        assert.strictEqual(replyBodyEl.textContent, '<img src=x onerror=alert("reply")>');
    });

    test('15 & 16. replies preserve response order and use flat visual level', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'r1', body: 'Root 1' },
                            replies: [
                                { id: 'rep-1', body: 'Reply 1' },
                                { id: 'rep-2', body: 'Reply 2' },
                                { id: 'rep-3', body: 'Reply 3' }
                            ]
                        }
                    ]
                })
            })
        });

        const loadedPromise = new Promise(r => doc.addEventListener('kiemlai:block-discussion-loaded', r));

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await loadedPromise;

        const replyEls = content.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replyEls.length, 3);
        assert.strictEqual(replyEls[0].getAttribute('data-reply-id'), 'rep-1');
        assert.strictEqual(replyEls[1].getAttribute('data-reply-id'), 'rep-2');
        assert.strictEqual(replyEls[2].getAttribute('data-reply-id'), 'rep-3');
    });

    test('17. tombstone reply has deleted presentation and no visible author', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'r1', body: 'Root 1' },
                            replies: [
                                { id: 'tomb-1', body: 'SECRET DELETED COMMENT BODY', tombstone: true }
                            ]
                        }
                    ]
                })
            })
        });

        const loadedPromise = new Promise(r => doc.addEventListener('kiemlai:block-discussion-loaded', r));

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await loadedPromise;

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.strictEqual(replyEl.classList.contains('is-tombstone'), true);
        assert.strictEqual(replyEl.querySelector('.novel-comment-author'), null);
        const renderedText = replyEl.querySelector('.novel-comment-body--tombstone').textContent;
        assert.strictEqual(renderedText, 'Bình luận đã bị xóa.');
        assert.strictEqual(renderedText.includes('SECRET DELETED COMMENT BODY'), false);
    });

    test('18. zero threads shows empty state', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const emptyEl = content.querySelector('.novel-block-discussion-empty');
        assert.notStrictEqual(emptyEl, null);
        assert.strictEqual(emptyEl.textContent.includes('Chưa có thảo luận nào cho đoạn này.'), true);
    });

    test('19. request A then request B then late A response: A cannot overwrite B', async () => {
        const { doc, passage } = setupChapterDOM();
        let resolveA;

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async (url) => {
                if (url.includes('blk-A')) {
                    return new Promise((resolve) => {
                        resolveA = () => resolve({
                            ok: true,
                            status: 200,
                            json: async () => ({
                                chapterId: '11111111-1111-1111-1111-111111111111',
                                contentVersion: 1,
                                blockKey: 'blk-A',
                                canonicalText: 'Text from Block A',
                                threadCount: 0,
                                threads: []
                            })
                        });
                    });
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        contentVersion: 1,
                        blockKey: 'blk-B',
                        canonicalText: 'Text from Block B',
                        threadCount: 0,
                        threads: []
                    })
                };
            }
        });

        // Click Block A
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-A',
                canonicalText: 'Provisional A',
                threadCount: 0
            }
        });

        // Click Block B
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-B',
                canonicalText: 'Provisional B',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(passage.textContent, 'Text from Block B');

        // Late response from A resolves
        resolveA();
        await new Promise(r => setTimeout(r, 10));

        // Block B remains intact!
        assert.strictEqual(passage.textContent, 'Text from Block B');
        assert.strictEqual(drawerModule.getActiveContext().blockKey, 'blk-B');
    });

    test('20 & 21. chapter-changed closes drawer, invalidates request, and late response from Chapter A cannot render', async () => {
        const { doc, drawer, passage } = setupChapterDOM();
        let resolveA;

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => new Promise(resolve => {
                resolveA = () => resolve({
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        contentVersion: 1,
                        blockKey: 'blk-0123456789abcdef-1',
                        canonicalText: 'Chapter A authoritative',
                        threadCount: 0,
                        threads: []
                    })
                });
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional A',
                threadCount: 0
            }
        });

        assert.strictEqual(drawer.hidden, false);

        // Chapter transitions to Chapter B
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });

        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(drawerModule.getActiveContext(), null);

        // Late Chapter A response arrives
        resolveA();
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(passage.textContent, '');
    });

    test('22. 404 renders unavailable-block state', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: false,
                status: 404,
                json: async () => ({})
            })
        });

        const failedPromise = new Promise(resolve => {
            doc.addEventListener(
                drawerModule.EVENT_DISCUSSION_LOAD_FAILED,
                function (event) {
                    if (event && event.detail && event.detail.reason === 'unavailable') {
                        resolve(event);
                    }
                }
            );
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await failedPromise;

        const errorEl = content.querySelector('.novel-block-discussion-status--unavailable');
        assert.notStrictEqual(errorEl, null);
        assert.strictEqual(errorEl.textContent.includes('Đoạn này không còn khả dụng trong phiên bản hiện tại.'), true);
    });

    test('23 & 24. 500/network error renders generic load error and Retry issues exactly one new request', async () => {
        const { doc, content } = setupChapterDOM();
        let callCount = 0;

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => {
                callCount++;
                if (callCount === 1) {
                    return {
                        ok: false,
                        status: 500,
                        json: async () => ({})
                    };
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        contentVersion: 1,
                        blockKey: 'blk-0123456789abcdef-1',
                        canonicalText: 'Text recovered',
                        threadCount: 0,
                        threads: []
                    })
                };
            }
        });

        const failedPromise = new Promise(resolve => {
            doc.addEventListener(
                drawerModule.EVENT_DISCUSSION_LOAD_FAILED,
                function (event) {
                    if (event && event.detail && event.detail.reason === 'error') {
                        resolve(event);
                    }
                }
            );
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await failedPromise;

        const errorEl = content.querySelector('.novel-block-discussion-status--error');
        assert.notStrictEqual(errorEl, null);
        assert.strictEqual(errorEl.textContent.includes('Không thể tải thảo luận. Vui lòng thử lại.'), true);

        const retryBtn = content.querySelector('.novel-block-discussion-retry-btn');
        assert.notStrictEqual(retryBtn, null);
        assert.strictEqual(callCount, 1);

        const loadedPromise = new Promise(resolve => {
            doc.addEventListener(
                drawerModule.EVENT_DISCUSSION_LOADED,
                function (event) {
                    resolve(event);
                }
            );
        });

        // Click retry
        retryBtn.dispatchEvent({ type: 'click' });
        await loadedPromise;

        assert.strictEqual(callCount, 2);
        assert.notStrictEqual(content.querySelector('.novel-block-discussion-empty'), null);
    });

    test('25. close button closes drawer and clears state', () => {
        const { doc, drawer, closeBtn, backdrop } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(drawer.hidden, false);
        assert.strictEqual(backdrop.hidden, false);

        doc.dispatchEvent({ type: 'click', target: closeBtn });

        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(backdrop.hidden, true);
        assert.strictEqual(drawerModule.getActiveContext(), null);
    });

    test('26. backdrop click closes drawer', () => {
        const { doc, drawer, backdrop } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(drawer.hidden, false);

        doc.dispatchEvent({ type: 'click', target: backdrop });

        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(backdrop.hidden, true);
    });

    test('27. Escape key closes drawer', () => {
        const { doc, drawer } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(drawer.hidden, false);

        doc.dispatchEvent({ type: 'keydown', key: 'Escape' });

        assert.strictEqual(drawer.hidden, true);
    });

    test('28. click inside drawer does NOT close drawer', () => {
        const { doc, drawer, passage } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(drawer.hidden, false);

        // Click passage inside drawer
        doc.dispatchEvent({ type: 'click', target: passage });

        assert.strictEqual(drawer.hidden, false);
    });

    test('29. body open-state class added on open and removed on close', () => {
        const { doc } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        assert.strictEqual(doc.body.classList.contains('has-block-discussion-open'), false);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(doc.body.classList.contains('has-block-discussion-open'), true);

        drawerModule.closeDrawer();

        assert.strictEqual(doc.body.classList.contains('has-block-discussion-open'), false);
    });

    test('30 & 31. focus moves to close button on open and returns to prior element on close', () => {
        const { doc, closeBtn } = setupChapterDOM();
        const triggerBtn = doc.createElement('button');
        triggerBtn.setAttribute('id', 'affordance-btn-test');
        doc.body.appendChild(triggerBtn);

        triggerBtn.focus();
        assert.strictEqual(doc.activeElement, triggerBtn);

        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(doc.activeElement, closeBtn);

        drawerModule.closeDrawer();

        assert.strictEqual(doc.activeElement, triggerBtn);
        assert.strictEqual(drawerModule.getPriorFocusedElement(), null);
    });

    test('30b. focus restoration skips detached previous element', () => {
        const { doc, closeBtn } = setupChapterDOM();
        const triggerBtn = doc.createElement('button');
        doc.body.appendChild(triggerBtn);

        triggerBtn.focus();
        assert.strictEqual(doc.activeElement, triggerBtn);

        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(doc.activeElement, closeBtn);

        // Detach trigger from document
        doc.body.removeChild(triggerBtn);
        triggerBtn.isFocused = false;

        drawerModule.closeDrawer();

        // Must not call focus on detached element
        assert.strictEqual(triggerBtn.isFocused, false);
        assert.notStrictEqual(doc.activeElement, triggerBtn);
        assert.strictEqual(drawerModule.getPriorFocusedElement(), null);
    });

    test('30c. focus restoration skips hidden or display:none previous element', () => {
        const { doc, closeBtn } = setupChapterDOM();
        const triggerBtn = doc.createElement('button');
        doc.body.appendChild(triggerBtn);

        triggerBtn.focus();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(doc.activeElement, closeBtn);

        // Make trigger hidden
        triggerBtn.hidden = true;
        triggerBtn.isFocused = false;

        drawerModule.closeDrawer();

        assert.strictEqual(triggerBtn.isFocused, false);
        assert.notStrictEqual(doc.activeElement, triggerBtn);
        assert.strictEqual(drawerModule.getPriorFocusedElement(), null);

        // Test display:none as well
        triggerBtn.hidden = false;
        triggerBtn.style.display = 'none';
        triggerBtn.focus();

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(doc.activeElement, closeBtn);
        triggerBtn.isFocused = false;

        drawerModule.closeDrawer();

        assert.strictEqual(triggerBtn.isFocused, false);
        assert.notStrictEqual(doc.activeElement, triggerBtn);
        assert.strictEqual(drawerModule.getPriorFocusedElement(), null);
    });

    test('30d. focus restoration skips disabled previous button', () => {
        const { doc, closeBtn } = setupChapterDOM();
        const triggerBtn = doc.createElement('button');
        doc.body.appendChild(triggerBtn);

        triggerBtn.focus();
        drawerModule.initReaderBlockDiscussionDrawer(doc);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        assert.strictEqual(doc.activeElement, closeBtn);

        // Disable button
        triggerBtn.disabled = true;
        triggerBtn.isFocused = false;

        drawerModule.closeDrawer();

        assert.strictEqual(triggerBtn.isFocused, false);
        assert.notStrictEqual(doc.activeElement, triggerBtn);
        assert.strictEqual(drawerModule.getPriorFocusedElement(), null);
    });

    test('32. malformed or mismatched server identity is not rendered', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'WRONG-BLOCK-KEY', // Mismatch!
                    canonicalText: 'Text',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        const failedPromise = new Promise(resolve => {
            doc.addEventListener(
                drawerModule.EVENT_DISCUSSION_LOAD_FAILED,
                function (event) {
                    if (event && event.detail && event.detail.reason === 'invalid_response') {
                        resolve(event);
                    }
                }
            );
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await failedPromise;

        const errorEl = content.querySelector('.novel-block-discussion-status--error');
        assert.notStrictEqual(errorEl, null);
    });

    test('32b. server response with missing or non-string canonicalText is rejected', async () => {
        const { doc, passage, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 5,
                    blockKey: 'blk-0123456789abcdef-1',
                    // canonicalText is null/non-string
                    canonicalText: null,
                    threadCount: 0,
                    threads: []
                })
            })
        });

        const failedPromise = new Promise(resolve => {
            doc.addEventListener(
                drawerModule.EVENT_DISCUSSION_LOAD_FAILED,
                function (event) {
                    if (event && event.detail && event.detail.reason === 'invalid_response') {
                        resolve(event);
                    }
                }
            );
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional text',
                threadCount: 0
            }
        });

        // 1. Immediately after open, provisional passage is rendered
        assert.strictEqual(passage.textContent, 'Provisional text');

        await failedPromise;

        // 2. Malformed authoritative content is NOT rendered
        // 3. Active server contentVersion (5) is NOT accepted as successful state
        const activeCtx = drawerModule.getActiveContext();
        assert.strictEqual(activeCtx.contentVersion, 1);

        // 4. Generic invalid-response error state appears
        const errorEl = content.querySelector('.novel-block-discussion-status--error');
        assert.notStrictEqual(errorEl, null);
        assert.ok(errorEl.textContent.includes('Dữ liệu phản hồi không hợp lệ'));
    });

    test('32c. server response with non-array threads is rejected', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 5,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Valid text',
                    threadCount: 0,
                    threads: 'invalid-non-array'
                })
            })
        });

        const failedPromise = new Promise(resolve => {
            doc.addEventListener(
                drawerModule.EVENT_DISCUSSION_LOAD_FAILED,
                function (event) {
                    if (event && event.detail && event.detail.reason === 'invalid_response') {
                        resolve(event);
                    }
                }
            );
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional text',
                threadCount: 0
            }
        });

        await failedPromise;

        const activeCtx = drawerModule.getActiveContext();
        assert.strictEqual(activeCtx.contentVersion, 1);

        const errorEl = content.querySelector('.novel-block-discussion-status--error');
        assert.notStrictEqual(errorEl, null);
        assert.ok(errorEl.textContent.includes('Dữ liệu phản hồi không hợp lệ'));
    });

    test('33. server contentVersion different from event version is ACCEPTED (server is authoritative)', async () => {
        const { doc, passage } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 99, // Server advanced version
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Advanced version canonical text',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1, // Event had version 1
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(passage.textContent, 'Advanced version canonical text');
        assert.strictEqual(drawerModule.getActiveContext().contentVersion, 99);
    });

    test('35. canonical Reader block DOM/textContent remains completely unchanged', async () => {
        const { doc, blockA, blockB } = setupChapterDOM();
        const initialTextA = blockA.textContent;
        const initialTextB = blockB.textContent;

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Server text',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: initialTextA,
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(blockA.textContent, initialTextA);
        assert.strictEqual(blockB.textContent, initialTextB);
        assert.strictEqual(blockA.childNodes.length, 0); // No child nodes injected
    });

    test('36. Continuous Reader regression: Chapter A open -> chapter-changed -> Chapter B open', async () => {
        const { doc, drawer, passage } = setupChapterDOM();
        const fetchUrls = [];

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async (url) => {
                fetchUrls.push(url);
                const isChapterB = url.includes('22222222-2222-2222-2222-222222222222');
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: isChapterB ? '22222222-2222-2222-2222-222222222222' : '11111111-1111-1111-1111-111111111111',
                        contentVersion: 1,
                        blockKey: isChapterB ? 'blk-B-1' : 'blk-A-1',
                        canonicalText: isChapterB ? 'Chapter B Canonical Text' : 'Chapter A Canonical Text',
                        threadCount: 0,
                        threads: []
                    })
                };
            }
        });

        // 1. Chapter A open
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-A-1',
                canonicalText: 'Provisional A',
                threadCount: 0
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(drawer.hidden, false);
        assert.strictEqual(passage.textContent, 'Chapter A Canonical Text');

        // 2. Chapter changed
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });
        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(drawerModule.getActiveContext(), null);

        // 3. Chapter B open
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '22222222-2222-2222-2222-222222222222',
                contentVersion: 1,
                blockKey: 'blk-B-1',
                canonicalText: 'Provisional B',
                threadCount: 0
            }
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(drawer.hidden, false);
        assert.strictEqual(passage.textContent, 'Chapter B Canonical Text');
        assert.strictEqual(drawerModule.getActiveContext().chapterId, '22222222-2222-2222-2222-222222222222');
        assert.strictEqual(fetchUrls.length, 2);
        assert.strictEqual(fetchUrls[1].includes('22222222-2222-2222-2222-222222222222'), true);
    });

    test('37. successful authoritative GET dispatches kiemlai:block-discussion-loaded and failed GET does not', async () => {
        const { doc } = setupChapterDOM();
        const loadedEvents = [];

        doc.addEventListener(drawerModule.EVENT_DISCUSSION_LOADED, (evt) => {
            loadedEvents.push(evt.detail);
        });

        // 1. Successful GET
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 3,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Loaded canonical text',
                    threadCount: 2,
                    threads: [
                        { id: 'th-1', authorUserId: 'u-1', body: 'Thread 1', replies: [] },
                        { id: 'th-2', authorUserId: 'u-2', body: 'Thread 2', replies: [] }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(loadedEvents.length, 1);
        assert.deepStrictEqual(loadedEvents[0], {
            chapterId: '11111111-1111-1111-1111-111111111111',
            contentVersion: 3,
            blockKey: 'blk-0123456789abcdef-1',
            threadCount: 2,
            commentCount: 0
        });

        // 2. Failed GET
        drawerModule.resetDrawerState();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: false,
                status: 500,
                json: async () => ({ error: 'Server error' })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        // Event count still 1, no new loaded event emitted for 500 error
        assert.strictEqual(loadedEvents.length, 1);
    });

    test('38. refreshActiveDiscussion requires authoritative context, issues GET when authoritative, and does not reopen closed drawer', async () => {
        const { doc, drawer } = setupChapterDOM();
        let fetchCount = 0;
        let resolveFirstFetch;
        const firstFetchPromise = new Promise(r => { resolveFirstFetch = r; });

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => {
                fetchCount++;
                if (fetchCount === 1) {
                    await firstFetchPromise;
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        contentVersion: 1,
                        blockKey: 'blk-0123456789abcdef-1',
                        canonicalText: 'Refreshed Text',
                        threadCount: 1,
                        threads: [{ id: 'th-1', authorUserId: 'u-1', body: 'Comment 1', replies: [] }]
                    })
                };
            }
        });

        // 1. When drawer is closed, refresh is a no-op
        await drawerModule.refreshActiveDiscussion();
        assert.strictEqual(fetchCount, 0);

        // 2. Open drawer with provisional context (GET #1 in-flight)
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Provisional',
                threadCount: 0
            }
        });
        assert.strictEqual(fetchCount, 1);

        // 3. While initial GET is still pending (context not authoritative), call refresh
        await drawerModule.refreshActiveDiscussion();
        // Assert: NO second GET occurs because context is provisional (authoritative: false)
        assert.strictEqual(fetchCount, 1);

        // 4. Resolve the initial authoritative GET
        resolveFirstFetch();
        await new Promise(r => setTimeout(r, 15));
        assert.strictEqual(fetchCount, 1);

        // 5. Now that context is authoritative, trigger refreshActiveDiscussion()
        await drawerModule.refreshActiveDiscussion();
        // Assert: Exactly one additional GET occurs
        assert.strictEqual(fetchCount, 2);

        // 6. Close drawer, then call refresh
        drawerModule.closeDrawer();
        assert.strictEqual(drawer.hidden, true);
        await drawerModule.refreshActiveDiscussion();
        assert.strictEqual(fetchCount, 2); // Unchanged! Does not fetch or reopen
        assert.strictEqual(drawer.hidden, true);
    });

    test('39. actual close emits kiemlai:block-discussion-closed exactly once; no-op/reset close does not emit duplicate event', async () => {
        const { doc } = setupChapterDOM();
        const closedEvents = [];

        doc.addEventListener(drawerModule.EVENT_DISCUSSION_CLOSED, (e) => {
            closedEvents.push(e);
        });

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Canonical',
                    threadCount: 0,
                    threads: []
                })
            })
        });

        // 1. Initial close when drawer is not open: no event emitted
        drawerModule.closeDrawer();
        assert.strictEqual(closedEvents.length, 0);

        // 2. Open drawer
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Canonical',
                threadCount: 0
            }
        });
        await new Promise(r => setTimeout(r, 10));

        // 3. Actually close drawer -> emits closed event once with detail
        drawerModule.closeDrawer();
        assert.strictEqual(closedEvents.length, 1);
        assert.strictEqual(closedEvents[0].type, drawerModule.EVENT_DISCUSSION_CLOSED);
        assert.deepStrictEqual(closedEvents[0].detail, {
            chapterId: '11111111-1111-1111-1111-111111111111',
            blockKey: 'blk-0123456789abcdef-1'
        });

        // 4. Repeated close when already closed -> no duplicate event
        drawerModule.closeDrawer();
        assert.strictEqual(closedEvents.length, 1);

        // 5. resetDrawerState when closed -> no duplicate event
        drawerModule.resetDrawerState();
        assert.strictEqual(closedEvents.length, 1);
    });

    test('40. 404 response emits kiemlai:block-discussion-load-failed with reason unavailable', async () => {
        const { doc } = setupChapterDOM();
        const failedEvents = [];
        doc.addEventListener(drawerModule.EVENT_DISCUSSION_LOAD_FAILED, (e) => {
            failedEvents.push(e);
        });

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: false,
                status: 404,
                json: async () => ({})
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(failedEvents.length, 1);
        assert.deepStrictEqual(failedEvents[0].detail, {
            chapterId: '11111111-1111-1111-1111-111111111111',
            blockKey: 'blk-0123456789abcdef-1',
            reason: 'unavailable'
        });
    });

    test('41. 500 or network failure emits kiemlai:block-discussion-load-failed with reason error', async () => {
        const { doc } = setupChapterDOM();
        const failedEvents = [];
        doc.addEventListener(drawerModule.EVENT_DISCUSSION_LOAD_FAILED, (e) => {
            failedEvents.push(e);
        });

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => {
                throw new Error('Network error');
            }
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(failedEvents.length, 1);
        assert.deepStrictEqual(failedEvents[0].detail, {
            chapterId: '11111111-1111-1111-1111-111111111111',
            blockKey: 'blk-0123456789abcdef-1',
            reason: 'error'
        });
    });

    test('42. invalid server response emits kiemlai:block-discussion-load-failed with reason invalid_response', async () => {
        const { doc } = setupChapterDOM();
        const failedEvents = [];
        doc.addEventListener(drawerModule.EVENT_DISCUSSION_LOAD_FAILED, (e) => {
            failedEvents.push(e);
        });

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 12345,
                    threads: []
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(failedEvents.length, 1);
        assert.deepStrictEqual(failedEvents[0].detail, {
            chapterId: '11111111-1111-1111-1111-111111111111',
            blockKey: 'blk-0123456789abcdef-1',
            reason: 'invalid_response'
        });
    });

    test('43. aborted or stale fetch does NOT emit kiemlai:block-discussion-load-failed', async () => {
        const { doc } = setupChapterDOM();
        const failedEvents = [];
        doc.addEventListener(drawerModule.EVENT_DISCUSSION_LOAD_FAILED, (e) => {
            failedEvents.push(e);
        });

        let resolveFetchA;
        const fetchAPromise = new Promise(r => { resolveFetchA = r; });

        let fetchCount = 0;
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => {
                fetchCount++;
                if (fetchCount === 1) {
                    await fetchAPromise;
                    return {
                        ok: false,
                        status: 500,
                        json: async () => ({})
                    };
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        contentVersion: 1,
                        blockKey: 'blk-0123456789abcdef-2',
                        canonicalText: 'Block B text',
                        threadCount: 0,
                        threads: []
                    })
                };
            }
        });

        // Request Block A
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Block A',
                threadCount: 0
            }
        });

        // Immediately request Block B (making Block A stale)
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-2',
                canonicalText: 'Block B',
                threadCount: 0
            }
        });

        // Now resolve stale fetch A with 500 failure
        resolveFetchA();
        await new Promise(r => setTimeout(r, 20));

        // Assert: Stale fetch A failure was discarded, no load-failed event emitted for Block A
        assert.strictEqual(failedEvents.length, 0);
    });

    test('44. Comment author presentation renders valid avatar image and display name for root and replies', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: 'Hàn Lập',
                                    avatarUrl: 'https://cdn.example.com/avatars/hanlap.png'
                                }
                            },
                            replies: [
                                {
                                    id: 'reply-1',
                                    authorUserId: 'user-reply',
                                    body: 'Reply comment',
                                    tombstone: false,
                                    createdAt: '2026-09-17T12:05:00Z',
                                    author: {
                                        userId: 'user-reply',
                                        displayName: 'Nam Cung Uyển',
                                        avatarUrl: 'https://cdn.example.com/avatars/namcunguyen.jpg'
                                    }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        // Root comment author verification
        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);

        const rootAuthor = rootEl.querySelector('.novel-comment-author');
        assert.notStrictEqual(rootAuthor, null);
        assert.strictEqual(rootAuthor.textContent, 'Hàn Lập');

        const rootAvatar = rootEl.querySelector('img.novel-comment-avatar');
        assert.notStrictEqual(rootAvatar, null);
        assert.strictEqual(rootAvatar.getAttribute('src'), 'https://cdn.example.com/avatars/hanlap.png');
        assert.strictEqual(rootAvatar.getAttribute('alt'), 'Hàn Lập');
        assert.strictEqual(rootAvatar.getAttribute('referrerpolicy'), 'no-referrer');

        // Reply author verification
        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        const replyAuthor = replyEl.querySelector('.novel-comment-author');
        assert.notStrictEqual(replyAuthor, null);
        assert.strictEqual(replyAuthor.textContent, 'Nam Cung Uyển');

        const replyAvatar = replyEl.querySelector('img.novel-comment-avatar');
        assert.notStrictEqual(replyAvatar, null);
        assert.strictEqual(replyAvatar.getAttribute('src'), 'https://cdn.example.com/avatars/namcunguyen.jpg');
        assert.strictEqual(replyAvatar.getAttribute('alt'), 'Nam Cung Uyển');
        assert.strictEqual(replyAvatar.getAttribute('referrerpolicy'), 'no-referrer');
    });

    test('45. Comment author presentation renders initial letter fallback when avatar is null or empty', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: 'Bạch Tiểu Thuần',
                                    avatarUrl: null
                                }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);

        const imgAvatar = rootEl.querySelector('img.novel-comment-avatar');
        assert.strictEqual(imgAvatar, null);

        const fallback = rootEl.querySelector('.novel-comment-avatar--fallback');
        assert.notStrictEqual(fallback, null);
        assert.strictEqual(fallback.textContent, 'B');
        assert.strictEqual(fallback.getAttribute('aria-hidden'), 'true');
    });

    test('46. Comment author presentation sanitizes unsafe avatar URLs and falls back safely', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: 'Attacker',
                                    avatarUrl: 'javascript:alert(1)'
                                }
                            },
                            replies: [
                                {
                                    id: 'reply-1',
                                    authorUserId: 'user-reply',
                                    body: 'Reply with protocol-relative URL',
                                    tombstone: false,
                                    createdAt: '2026-09-17T12:05:00Z',
                                    author: {
                                        userId: 'user-reply',
                                        displayName: 'Attacker2',
                                        avatarUrl: '//evil.com/avatar.jpg'
                                    }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        // Root javascript: URL was rejected and rendered fallback
        const rootEl = content.querySelector('.novel-comment--root');
        assert.strictEqual(rootEl.querySelector('img.novel-comment-avatar'), null);
        const rootFallback = rootEl.querySelector('.novel-comment-avatar--fallback');
        assert.notStrictEqual(rootFallback, null);
        assert.strictEqual(rootFallback.textContent, 'A');

        // Reply //evil.com URL was rejected and rendered fallback
        const replyEl = content.querySelector('.novel-comment--reply');
        assert.strictEqual(replyEl.querySelector('img.novel-comment-avatar'), null);
        const replyFallback = replyEl.querySelector('.novel-comment-avatar--fallback');
        assert.notStrictEqual(replyFallback, null);
        assert.strictEqual(replyFallback.textContent, 'A');
    });

    test('47. Comment author display name containing HTML/scripts is safely escaped via textContent', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: '<script>alert("xss")</script>',
                                    avatarUrl: null
                                }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const rootEl = content.querySelector('.novel-comment--root');
        const authorEl = rootEl.querySelector('.novel-comment-author');
        assert.strictEqual(authorEl.textContent, '<script>alert("xss")</script>');
        assert.strictEqual(rootEl.querySelector('script'), null);
    });

    test('48. Missing or blank author/displayName falls back to "Người dùng"', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: null
                            },
                            replies: [
                                {
                                    id: 'reply-1',
                                    authorUserId: 'user-reply',
                                    body: 'Reply with blank displayName',
                                    tombstone: false,
                                    createdAt: '2026-09-17T12:05:00Z',
                                    author: {
                                        userId: 'user-reply',
                                        displayName: '   ',
                                        avatarUrl: null
                                    }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const rootAuthor = content.querySelector('.novel-comment--root .novel-comment-author');
        assert.strictEqual(rootAuthor.textContent, 'Người dùng');

        const replyAuthor = content.querySelector('.novel-comment--reply .novel-comment-author');
        assert.strictEqual(replyAuthor.textContent, 'Người dùng');
    });

    test('49. Image onerror replaces img with fallback placeholder', async () => {
        const { doc, content } = setupChapterDOM();

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: 'Trần Bình An',
                                    avatarUrl: 'https://cdn.example.com/broken.jpg'
                                }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const rootEl = content.querySelector('.novel-comment--root');
        const img = rootEl.querySelector('img.novel-comment-avatar');
        assert.notStrictEqual(img, null);

        // Simulate image load error
        img.onerror();

        // Image should be replaced with fallback placeholder
        assert.strictEqual(rootEl.querySelector('img.novel-comment-avatar'), null);
        const fallback = rootEl.querySelector('.novel-comment-avatar--fallback');
        assert.notStrictEqual(fallback, null);
        assert.strictEqual(fallback.textContent, 'T');
    });

    test('50. regression: threadCard exposes data-root-id and MUST NOT expose data-comment-id; root and reply expose exact comment identities', async () => {
        const { doc, content } = setupChapterDOM();

        const ROOT_ID = 'root-uuid-1111';
        const REPLY_ID = 'reply-uuid-2222';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Root comment',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: 'Tiêu Viêm',
                                    avatarUrl: 'https://cdn.example.com/tieuviem.png'
                                }
                            },
                            replies: [
                                {
                                    id: REPLY_ID,
                                    authorUserId: 'user-reply',
                                    body: 'Reply comment',
                                    tombstone: false,
                                    createdAt: '2026-09-17T12:05:00Z',
                                    author: {
                                        userId: 'user-reply',
                                        displayName: 'Dược Lão',
                                        avatarUrl: null
                                    }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        // Thread container contract
        const threadCard = content.querySelector('.novel-block-discussion-thread');
        assert.notStrictEqual(threadCard, null);
        assert.strictEqual(threadCard.getAttribute('data-root-id'), ROOT_ID);
        assert.strictEqual(threadCard.getAttribute('data-comment-id'), null);
        assert.strictEqual(threadCard.getAttribute('data-thread-id'), null);

        // Root comment contract
        const rootEl = threadCard.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);
        assert.strictEqual(rootEl.getAttribute('data-comment-id'), ROOT_ID);
        assert.strictEqual(rootEl.querySelector('.novel-comment-author').textContent, 'Tiêu Viêm');
        assert.strictEqual(rootEl.querySelector('img.novel-comment-avatar').getAttribute('src'), 'https://cdn.example.com/tieuviem.png');

        // Reply comment contract
        const replyEl = threadCard.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);
        assert.strictEqual(replyEl.getAttribute('data-reply-id'), REPLY_ID);
        assert.strictEqual(replyEl.getAttribute('data-comment-id'), REPLY_ID);
        assert.strictEqual(replyEl.querySelector('.novel-comment-author').textContent, 'Dược Lão');
        assert.strictEqual(replyEl.querySelector('.novel-comment-avatar--fallback').textContent, 'D');
    });

    test('51. active root comment renders Reply button carrying semantic IDs', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-reply-btn-test';
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Root body',
                                createdAt: '2026-09-17T12:00:00Z',
                                author: {
                                    userId: 'user-root',
                                    displayName: 'Hàn Lập',
                                    avatarUrl: null
                                }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);
        const replyBtn = rootEl.querySelector('.novel-comment-reply-btn');
        assert.notStrictEqual(replyBtn, null);
        assert.strictEqual(replyBtn.getAttribute('data-action'), 'reply');
        assert.strictEqual(replyBtn.getAttribute('data-comment-id'), ROOT_ID);
        assert.strictEqual(replyBtn.getAttribute('data-root-id'), ROOT_ID);
        assert.strictEqual(replyBtn.getAttribute('data-author-name'), 'Hàn Lập');
        assert.strictEqual(replyBtn.textContent, 'Phản hồi');
    });

    test('52. active reply comment renders Reply button carrying semantic IDs', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-parent';
        const REPLY_ID = 'reply-child';
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Root',
                                createdAt: '2026-09-17T12:00:00Z'
                            },
                            replies: [
                                {
                                    id: REPLY_ID,
                                    authorUserId: 'user-reply',
                                    body: 'Reply',
                                    tombstone: false,
                                    createdAt: '2026-09-17T12:05:00Z',
                                    author: {
                                        userId: 'user-reply',
                                        displayName: 'Nam Cung Uyển',
                                        avatarUrl: null
                                    }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);
        const replyBtn = replyEl.querySelector('.novel-comment-reply-btn');
        assert.notStrictEqual(replyBtn, null);
        assert.strictEqual(replyBtn.getAttribute('data-action'), 'reply');
        assert.strictEqual(replyBtn.getAttribute('data-comment-id'), REPLY_ID);
        assert.strictEqual(replyBtn.getAttribute('data-reply-id'), REPLY_ID);
        assert.strictEqual(replyBtn.getAttribute('data-root-id'), ROOT_ID);
        assert.strictEqual(replyBtn.getAttribute('data-author-name'), 'Nam Cung Uyển');
        assert.strictEqual(replyBtn.textContent, 'Phản hồi');
    });

    test('53. tombstone reply does NOT render Reply button', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-parent-2';
        const TOMBSTONE_ID = 'tombstone-reply-id';
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Root',
                                createdAt: '2026-09-17T12:00:00Z'
                            },
                            replies: [
                                {
                                    id: TOMBSTONE_ID,
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true,
                                    status: 'DELETED',
                                    createdAt: '2026-09-17T12:05:00Z'
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);
        assert.strictEqual(replyEl.classList.contains('is-tombstone'), true);
        const replyBtn = replyEl.querySelector('.novel-comment-reply-btn');
        assert.strictEqual(replyBtn, null);
    });

    test('54. direct reply to root (parentCommentId == root.id) renders body normally with no mention element', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-54';
        const REPLY_ID = 'reply-54';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Bình luận gốc',
                                author: { displayName: 'Tiêu Viêm' }
                            },
                            replies: [
                                {
                                    id: REPLY_ID,
                                    parentCommentId: ROOT_ID,
                                    authorUserId: 'user-reply',
                                    body: 'Phản hồi trực tiếp cho gốc',
                                    author: { displayName: 'Dược Lão' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        // A. Direct reply to root MUST NOT render mention element
        const mentionEl = replyEl.querySelector('.novel-comment-reply-mention');
        assert.strictEqual(mentionEl, null);

        // Body renders normally
        const bodyEl = replyEl.querySelector('.novel-comment-body');
        assert.strictEqual(bodyEl.textContent, 'Phản hồi trực tiếp cho gốc');
        assert.strictEqual(bodyEl.textContent.includes('@Tiêu Viêm'), false);
    });

    test('55. nested reply (parentCommentId == another reply.id) renders @immediateParentDisplayName inline without mutating body or adding parent preview', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-55';
        const PARENT_REPLY_ID = 'reply-55-parent';
        const CHILD_REPLY_ID = 'reply-55-child';

        const childReplyData = {
            id: CHILD_REPLY_ID,
            parentCommentId: PARENT_REPLY_ID,
            authorUserId: 'user-c',
            body: 'Nội dung phản hồi con',
            author: { displayName: 'Hải Ba Đông' }
        };

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Bình luận gốc',
                                author: { displayName: 'Tiêu Viêm' }
                            },
                            replies: [
                                {
                                    id: PARENT_REPLY_ID,
                                    parentCommentId: ROOT_ID,
                                    authorUserId: 'user-b',
                                    body: 'Đoạn văn phản hồi cha rất dài cần được bảo toàn',
                                    author: { displayName: 'Dược Lão' }
                                },
                                childReplyData
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const replies = content.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const childReplyEl = replies[1];
        assert.strictEqual(childReplyEl.getAttribute('data-reply-id'), CHILD_REPLY_ID);

        // B. Nested reply renders @immediateParentDisplayName
        const mentionEl = childReplyEl.querySelector('.novel-comment-reply-mention');
        assert.notStrictEqual(mentionEl, null);
        assert.strictEqual(mentionEl.textContent, '@Dược Lão');

        // Body text element renders child reply body
        const bodyTextEl = childReplyEl.querySelector('.novel-comment-reply-body-text');
        assert.notStrictEqual(bodyTextEl, null);
        assert.strictEqual(bodyTextEl.textContent, 'Nội dung phản hồi con');

        // D. No parent body preview or quote rendered
        assert.strictEqual(childReplyEl.textContent.includes('Đoạn văn phản hồi cha'), false);
        assert.strictEqual(childReplyEl.querySelector('.novel-comment-body-preview'), null);
        assert.strictEqual(childReplyEl.querySelector('blockquote'), null);

        // E. Persisted/read data object body itself was NOT mutated
        assert.strictEqual(childReplyData.body, 'Nội dung phản hồi con');
    });

    test('56. deeply nested reply references IMMEDIATE parent author, not thread root author or grandparent author', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-56';
        const R1_ID = 'rep-1';
        const R2_ID = 'rep-2';
        const R3_ID = 'rep-3';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-root',
                                body: 'Gốc',
                                author: { displayName: 'Tổ Thụ' }
                            },
                            replies: [
                                {
                                    id: R1_ID,
                                    parentCommentId: ROOT_ID,
                                    authorUserId: 'user-1',
                                    body: 'Phản hồi 1',
                                    author: { displayName: 'Cha' }
                                },
                                {
                                    id: R2_ID,
                                    parentCommentId: R1_ID,
                                    authorUserId: 'user-2',
                                    body: 'Phản hồi 2',
                                    author: { displayName: 'Con' }
                                },
                                {
                                    id: R3_ID,
                                    parentCommentId: R2_ID,
                                    authorUserId: 'user-3',
                                    body: 'Phản hồi 3',
                                    author: { displayName: 'Cháu' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const replies = content.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 3);

        const r1El = replies[0];
        const r2El = replies[1];
        const r3El = replies[2];

        // R1 is direct reply to root -> no mention
        assert.strictEqual(r1El.querySelector('.novel-comment-reply-mention'), null);

        // R2 replies to R1 -> mentions @Cha
        assert.strictEqual(r2El.querySelector('.novel-comment-reply-mention').textContent, '@Cha');
        assert.strictEqual(r2El.textContent.includes('@Tổ Thụ'), false);

        // C. R3 replies to R2 -> mentions @Con (immediate parent), NEVER @Tổ Thụ (root) or @Cha (grandparent)
        const r3Mention = r3El.querySelector('.novel-comment-reply-mention');
        assert.notStrictEqual(r3Mention, null);
        assert.strictEqual(r3Mention.textContent, '@Con');
        assert.strictEqual(r3El.textContent.includes('@Tổ Thụ'), false);
        assert.strictEqual(r3El.textContent.includes('@Cha'), false);
    });

    test('57. HTML-like parent displayName is safely escaped via textContent and rendered as text', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-57';
        const P_ID = 'rep-p';
        const C_ID = 'rep-c';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: ROOT_ID, author: { displayName: 'Root' } },
                            replies: [
                                {
                                    id: P_ID,
                                    parentCommentId: ROOT_ID,
                                    body: 'Parent body',
                                    author: { displayName: '<script>alert("xss")</script>' }
                                },
                                {
                                    id: C_ID,
                                    parentCommentId: P_ID,
                                    body: 'Child body',
                                    author: { displayName: 'NormalChild' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        // F. No script elements injected into DOM
        assert.strictEqual(content.querySelector('script'), null);

        const childReplyEl = content.querySelectorAll('.novel-comment--reply')[1];
        const mentionEl = childReplyEl.querySelector('.novel-comment-reply-mention');
        assert.notStrictEqual(mentionEl, null);
        assert.strictEqual(mentionEl.textContent, '@<script>alert("xss")</script>');
    });

    test('58. missing parent renders body normally with no mention element', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-58';
        const ORPHAN_ID = 'rep-orphan';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: ROOT_ID, author: { displayName: 'Root' } },
                            replies: [
                                {
                                    id: ORPHAN_ID,
                                    parentCommentId: 'missing-parent-uuid-999',
                                    body: 'Phản hồi có cha bị mất',
                                    author: { displayName: 'Mồ Côi' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const orphanEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(orphanEl, null);

        // G. Missing parent -> no mention element
        assert.strictEqual(orphanEl.querySelector('.novel-comment-reply-mention'), null);
        assert.strictEqual(orphanEl.querySelector('.novel-comment-body').textContent, 'Phản hồi có cha bị mất');
    });

    test('59. tombstoned / deleted immediate parent does NOT leak deleted author identity', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-59';
        const TOMB_PARENT_ID = 'tomb-59';
        const CHILD_ID = 'child-59';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: ROOT_ID, author: { displayName: 'Root' } },
                            replies: [
                                {
                                    id: TOMB_PARENT_ID,
                                    parentCommentId: ROOT_ID,
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true,
                                    status: 'DELETED',
                                    author: { displayName: 'NguoiBiMat' }
                                },
                                {
                                    id: CHILD_ID,
                                    parentCommentId: TOMB_PARENT_ID,
                                    body: 'Phản hồi con của bình luận đã xóa',
                                    author: { displayName: 'NguoiDungB' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const replies = content.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const childEl = replies[1];

        // H. Tombstoned parent -> NO mention element, NO author leak
        assert.strictEqual(childEl.querySelector('.novel-comment-reply-mention'), null);
        assert.strictEqual(childEl.textContent.includes('NguoiBiMat'), false);
        assert.strictEqual(childEl.textContent.includes('@Người dùng'), false);
        assert.strictEqual(childEl.querySelector('.novel-comment-body').textContent, 'Phản hồi con của bình luận đã xóa');
    });

    test('60. all replies remain in the same flat replies container with intact A3 DOM contracts', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-60';
        const R1_ID = 'r1-60';
        const R2_ID = 'r2-60';
        const R3_ID = 'r3-60';

        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: () => Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: ROOT_ID, author: { displayName: 'Author Root' } },
                            replies: [
                                { id: R1_ID, parentCommentId: ROOT_ID, body: 'Reply 1', author: { displayName: 'Author 1' } },
                                { id: R2_ID, parentCommentId: R1_ID, body: 'Reply 2', author: { displayName: 'Author 2' } },
                                { id: R3_ID, parentCommentId: R2_ID, body: 'Reply 3', author: { displayName: 'Author 3' } }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 0
            }
        });

        await new Promise(r => setTimeout(r, 10));

        const threadCard = content.querySelector('.novel-block-discussion-thread');
        assert.notStrictEqual(threadCard, null);

        // J. Thread container contract
        assert.strictEqual(threadCard.getAttribute('data-root-id'), ROOT_ID);
        assert.strictEqual(threadCard.getAttribute('data-comment-id'), null);
        assert.strictEqual(threadCard.getAttribute('data-thread-id'), null);

        // Root comment contract
        const rootEl = threadCard.querySelector('.novel-comment--root');
        assert.strictEqual(rootEl.getAttribute('data-comment-id'), ROOT_ID);

        // I. Flat replies container contract
        const repliesContainer = threadCard.querySelector('.novel-comment-replies');
        assert.notStrictEqual(repliesContainer, null);

        const replyArticles = repliesContainer.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replyArticles.length, 3);

        // All replies are direct children of repliesContainer (no nested reply DOM trees)
        assert.strictEqual(repliesContainer.childNodes.length, 3);
        assert.strictEqual(repliesContainer.childNodes[0], replyArticles[0]);
        assert.strictEqual(repliesContainer.childNodes[1], replyArticles[1]);
        assert.strictEqual(repliesContainer.childNodes[2], replyArticles[2]);

        // J. Reply comment and button contracts
        for (let i = 0; i < replyArticles.length; i++) {
            const el = replyArticles[i];
            const expectedId = [R1_ID, R2_ID, R3_ID][i];
            assert.strictEqual(el.getAttribute('data-reply-id'), expectedId);
            assert.strictEqual(el.getAttribute('data-comment-id'), expectedId);

            const btn = el.querySelector('.novel-comment-reply-btn');
            assert.notStrictEqual(btn, null);
            assert.strictEqual(btn.getAttribute('data-action'), 'reply');
            assert.strictEqual(btn.getAttribute('data-comment-id'), expectedId);
            assert.strictEqual(btn.getAttribute('data-reply-id'), expectedId);
            assert.strictEqual(btn.getAttribute('data-root-id'), ROOT_ID);
        }
    });

    test('61. active root comment with canEdit: true renders edit button', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-edit-test-1',
                                authorUserId: 'user-owner',
                                body: 'My own comment',
                                tombstone: false,
                                canEdit: true,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z',
                                author: { displayName: 'Author Me' }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);

        const editBtn = rootEl.querySelector('.novel-comment-edit-btn');
        assert.notStrictEqual(editBtn, null);
        assert.strictEqual(editBtn.getAttribute('data-action'), 'edit');
        assert.strictEqual(editBtn.getAttribute('data-comment-id'), 'root-edit-test-1');
        assert.strictEqual(editBtn.getAttribute('data-root-id'), 'root-edit-test-1');
        assert.strictEqual(editBtn.textContent, 'Chỉnh sửa');
    });

    test('62. active root comment with canEdit: false does NOT render edit button', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-noedit-test-1',
                                authorUserId: 'user-other',
                                body: 'Someone else comment',
                                tombstone: false,
                                canEdit: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z',
                                author: { displayName: 'Other Person' }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);

        const editBtn = rootEl.querySelector('.novel-comment-edit-btn');
        assert.strictEqual(editBtn, null);
    });

    test('63. active reply comment with canEdit: true renders edit button carrying semantic IDs', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-reply-edit-test-1';
        const REPLY_ID = 'reply-edit-test-1';
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                authorUserId: 'user-other',
                                body: 'Root comment',
                                tombstone: false,
                                canEdit: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z',
                                author: { displayName: 'Other Person' }
                            },
                            replies: [
                                {
                                id: REPLY_ID,
                                authorUserId: 'user-owner',
                                parentCommentId: ROOT_ID,
                                body: 'My reply',
                                tombstone: false,
                                canEdit: true,
                                createdAt: '2026-09-17T10:05:00Z',
                                updatedAt: '2026-09-17T10:05:00Z',
                                author: { displayName: 'Author Me' }
                            }
                        ]
                    }
                ]
            })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        const editBtn = replyEl.querySelector('.novel-comment-edit-btn');
        assert.notStrictEqual(editBtn, null);
        assert.strictEqual(editBtn.getAttribute('data-action'), 'edit');
        assert.strictEqual(editBtn.getAttribute('data-comment-id'), REPLY_ID);
        assert.strictEqual(editBtn.getAttribute('data-reply-id'), REPLY_ID);
        assert.strictEqual(editBtn.getAttribute('data-root-id'), ROOT_ID);
        assert.strictEqual(editBtn.textContent, 'Chỉnh sửa');
    });

    test('64. tombstone reply never renders edit button even if canEdit is mistakenly true', async () => {
        const { doc, content } = setupChapterDOM();
        const ROOT_ID = 'root-tomb-test-1';
        const REPLY_ID = 'reply-tomb-test-1';
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: ROOT_ID,
                                body: 'Root',
                                tombstone: false,
                                canEdit: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z'
                            },
                            replies: [
                                {
                                    id: REPLY_ID,
                                    parentCommentId: ROOT_ID,
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true,
                                    canEdit: true,
                                    createdAt: '2026-09-17T10:05:00Z',
                                    updatedAt: '2026-09-17T10:06:00Z'
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        const editBtn = replyEl.querySelector('.novel-comment-edit-btn');
        assert.strictEqual(editBtn, null);
    });

    test('65. active comment with updatedAt > createdAt renders đã chỉnh sửa label', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-edited-1',
                                body: 'Root edited body',
                                tombstone: false,
                                canEdit: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:15:00Z',
                                author: { displayName: 'User 1' }
                            },
                            replies: [
                                {
                                    id: 'reply-edited-1',
                                    parentCommentId: 'root-edited-1',
                                    body: 'Reply edited body',
                                    tombstone: false,
                                    canEdit: false,
                                    createdAt: '2026-09-17T10:05:00Z',
                                    updatedAt: '2026-09-17T10:20:00Z',
                                    author: { displayName: 'User 2' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);
        const rootEditedLabel = rootEl.querySelector('.novel-comment-edited');
        assert.notStrictEqual(rootEditedLabel, null);
        assert.strictEqual(rootEditedLabel.textContent, 'đã chỉnh sửa');

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);
        const replyEditedLabel = replyEl.querySelector('.novel-comment-edited');
        assert.notStrictEqual(replyEditedLabel, null);
        assert.strictEqual(replyEditedLabel.textContent, 'đã chỉnh sửa');
    });

    test('66. active comment with updatedAt == createdAt does NOT render đã chỉnh sửa label', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-not-edited-1',
                                body: 'Root pristine body',
                                tombstone: false,
                                canEdit: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z',
                                author: { displayName: 'User 1' }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);
        const rootEditedLabel = rootEl.querySelector('.novel-comment-edited');
        assert.strictEqual(rootEditedLabel, null);
    });

    test('67. tombstone comment does NOT render đã chỉnh sửa label even if updatedAt > createdAt', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-1',
                                body: 'Root',
                                tombstone: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z'
                            },
                            replies: [
                                {
                                    id: 'reply-tomb-1',
                                    parentCommentId: 'root-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true,
                                    createdAt: '2026-09-17T10:00:00Z',
                                    updatedAt: '2026-09-17T10:30:00Z'
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);
        const replyEditedLabel = replyEl.querySelector('.novel-comment-edited');
        assert.strictEqual(replyEditedLabel, null);
    });

    test('68. isCommentEdited unit tests for null, malformed, identical, and strictly greater timestamps', () => {
        assert.strictEqual(drawerModule.isCommentEdited(null), false);
        assert.strictEqual(drawerModule.isCommentEdited({}), false);
        assert.strictEqual(drawerModule.isCommentEdited({ createdAt: '2026-09-17T10:00:00Z' }), false);
        assert.strictEqual(drawerModule.isCommentEdited({ updatedAt: '2026-09-17T10:00:00Z' }), false);
        assert.strictEqual(drawerModule.isCommentEdited({ createdAt: 'bad', updatedAt: 'bad' }), false);
        assert.strictEqual(drawerModule.isCommentEdited({
            createdAt: '2026-09-17T10:00:00Z',
            updatedAt: '2026-09-17T10:00:00Z'
        }), false);
        assert.strictEqual(drawerModule.isCommentEdited({
            createdAt: '2026-09-17T10:05:00Z',
            updatedAt: '2026-09-17T10:00:00Z'
        }), false);
        assert.strictEqual(drawerModule.isCommentEdited({
            createdAt: '2026-09-17T10:00:00Z',
            updatedAt: '2026-09-17T10:00:01Z'
        }), true);
    });

    test('69. active root comment with canDelete: true renders delete button', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-del-test-1',
                                authorUserId: 'user-owner',
                                body: 'My own comment to delete',
                                tombstone: false,
                                canEdit: true,
                                canDelete: true,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z',
                                author: { displayName: 'Author Me' }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);

        const delBtn = rootEl.querySelector('.novel-comment-delete-btn');
        assert.notStrictEqual(delBtn, null);
        assert.strictEqual(delBtn.getAttribute('data-action'), 'delete');
        assert.strictEqual(delBtn.getAttribute('data-comment-id'), 'root-del-test-1');
        assert.strictEqual(delBtn.getAttribute('data-root-id'), 'root-del-test-1');
        assert.strictEqual(delBtn.textContent, 'Xóa');
    });

    test('70. active root comment with canDelete: false does NOT render delete button', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-nodel-test-1',
                                authorUserId: 'user-other',
                                body: 'Other user comment',
                                tombstone: false,
                                canEdit: false,
                                canDelete: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z',
                                author: { displayName: 'Other Person' }
                            },
                            replies: []
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        assert.notStrictEqual(rootEl, null);

        const delBtn = rootEl.querySelector('.novel-comment-delete-btn');
        assert.strictEqual(delBtn, null);
    });

    test('71. active reply comment with canDelete: true renders delete button carrying semantic IDs', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-reply-del-1',
                                body: 'Root',
                                tombstone: false,
                                canDelete: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z'
                            },
                            replies: [
                                {
                                    id: 'reply-del-test-1',
                                    parentCommentId: 'root-reply-del-1',
                                    authorUserId: 'user-owner',
                                    body: 'My active reply to delete',
                                    tombstone: false,
                                    canEdit: true,
                                    canDelete: true,
                                    createdAt: '2026-09-17T10:05:00Z',
                                    updatedAt: '2026-09-17T10:05:00Z',
                                    author: { displayName: 'Me Replying' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        const delBtn = replyEl.querySelector('.novel-comment-delete-btn');
        assert.notStrictEqual(delBtn, null);
        assert.strictEqual(delBtn.getAttribute('data-action'), 'delete');
        assert.strictEqual(delBtn.getAttribute('data-comment-id'), 'reply-del-test-1');
        assert.strictEqual(delBtn.getAttribute('data-reply-id'), 'reply-del-test-1');
        assert.strictEqual(delBtn.getAttribute('data-root-id'), 'root-reply-del-1');
        assert.strictEqual(delBtn.textContent, 'Xóa');
    });

    test('72. active reply comment with canDelete: false does NOT render delete button', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-reply-nodel-1',
                                body: 'Root',
                                tombstone: false,
                                canDelete: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z'
                            },
                            replies: [
                                {
                                    id: 'reply-nodel-test-1',
                                    parentCommentId: 'root-reply-nodel-1',
                                    body: 'Not my reply',
                                    tombstone: false,
                                    canDelete: false,
                                    createdAt: '2026-09-17T10:05:00Z',
                                    updatedAt: '2026-09-17T10:05:00Z'
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        const delBtn = replyEl.querySelector('.novel-comment-delete-btn');
        assert.strictEqual(delBtn, null);
    });

    test('73. tombstone reply never renders delete button even if canDelete is mistakenly true', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-tomb-del-1',
                                body: 'Root',
                                tombstone: false,
                                canDelete: false,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z'
                            },
                            replies: [
                                {
                                    id: 'reply-tomb-1',
                                    parentCommentId: 'root-tomb-del-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true,
                                    canDelete: true,
                                    createdAt: '2026-09-17T10:05:00Z',
                                    updatedAt: '2026-09-17T10:05:00Z'
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replyEl = content.querySelector('.novel-comment--reply');
        assert.notStrictEqual(replyEl, null);

        const delBtn = replyEl.querySelector('.novel-comment-delete-btn');
        assert.strictEqual(delBtn, null);
    });

    test('74. action buttons order is Trả lời, Chỉnh sửa, Xóa when all are available', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: {
                                id: 'root-all-actions-1',
                                body: 'Root with all actions',
                                tombstone: false,
                                canEdit: true,
                                canDelete: true,
                                createdAt: '2026-09-17T10:00:00Z',
                                updatedAt: '2026-09-17T10:00:00Z'
                            },
                            replies: [
                                {
                                    id: 'reply-all-actions-1',
                                    parentCommentId: 'root-all-actions-1',
                                    body: 'Reply with all actions',
                                    tombstone: false,
                                    canEdit: true,
                                    canDelete: true,
                                    createdAt: '2026-09-17T10:05:00Z',
                                    updatedAt: '2026-09-17T10:05:00Z'
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const rootEl = content.querySelector('.novel-comment--root');
        const rootReplyBtn = rootEl.querySelector('.novel-comment-reply-btn');
        assert.notStrictEqual(rootReplyBtn, null);
        assert.strictEqual(rootReplyBtn.textContent, 'Phản hồi');

        const rootMenu = rootEl.querySelector('.kl-comment__menu') || rootEl.querySelector('.novel-comment-menu-popover');
        assert.notStrictEqual(rootMenu, null);
        const rootButtons = rootMenu.childNodes.filter(c => c.tagName === 'BUTTON');
        assert.strictEqual(rootButtons.length, 2);
        assert.strictEqual(rootButtons[0].textContent, 'Chỉnh sửa');
        assert.strictEqual(rootButtons[1].textContent, 'Xóa');

        const replyEl = content.querySelector('.novel-comment--reply');
        const replyReplyBtn = replyEl.querySelector('.novel-comment-reply-btn');
        assert.notStrictEqual(replyReplyBtn, null);
        assert.strictEqual(replyReplyBtn.textContent, 'Phản hồi');

        const replyMenu = replyEl.querySelector('.kl-comment__menu') || replyEl.querySelector('.novel-comment-menu-popover');
        assert.notStrictEqual(replyMenu, null);
        const replyButtons = replyMenu.childNodes.filter(c => c.tagName === 'BUTTON');
        assert.strictEqual(replyButtons.length, 2);
        assert.strictEqual(replyButtons[0].textContent, 'Chỉnh sửa');
        assert.strictEqual(replyButtons[1].textContent, 'Xóa');
    });

    test('75. tombstone with exactly one active direct child renders contextual message with child displayName', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'root-1', body: 'Root 1', tombstone: false },
                            replies: [
                                {
                                    id: 'tomb-1',
                                    parentCommentId: 'root-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true
                                },
                                {
                                    id: 'child-1',
                                    parentCommentId: 'tomb-1',
                                    body: '+2',
                                    tombstone: false,
                                    author: { displayName: 'QQQQ' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replies = content.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const tombstoneEl = replies[0];
        assert.strictEqual(tombstoneEl.classList.contains('is-tombstone'), true);

        const tombstoneBody = tombstoneEl.querySelector('.novel-comment-body--tombstone');
        assert.notStrictEqual(tombstoneBody, null);
        assert.strictEqual(tombstoneBody.textContent, 'Bình luận mà @QQQQ phản hồi đã bị xóa.');

        const mentionSpan = tombstoneBody.querySelector('.novel-comment-reply-mention');
        assert.notStrictEqual(mentionSpan, null);
        assert.strictEqual(mentionSpan.textContent, '@QQQQ');
    });

    test('76. tombstone with two active direct children falls back to standard message', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'root-1', body: 'Root 1', tombstone: false },
                            replies: [
                                {
                                    id: 'tomb-1',
                                    parentCommentId: 'root-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true
                                },
                                {
                                    id: 'child-1',
                                    parentCommentId: 'tomb-1',
                                    body: 'First child',
                                    tombstone: false,
                                    author: { displayName: 'UserA' }
                                },
                                {
                                    id: 'child-2',
                                    parentCommentId: 'tomb-1',
                                    body: 'Second child',
                                    tombstone: false,
                                    author: { displayName: 'UserB' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const tombstoneEl = content.querySelectorAll('.novel-comment--reply')[0];
        const tombstoneBody = tombstoneEl.querySelector('.novel-comment-body--tombstone');
        assert.strictEqual(tombstoneBody.textContent, 'Bình luận đã bị xóa.');
        assert.strictEqual(tombstoneBody.querySelector('.novel-comment-reply-mention'), null);
    });

    test('77. tombstone with one active direct child but missing/blank author displayName falls back to standard message', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'root-1', body: 'Root 1', tombstone: false },
                            replies: [
                                {
                                    id: 'tomb-1',
                                    parentCommentId: 'root-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true
                                },
                                {
                                    id: 'child-1',
                                    parentCommentId: 'tomb-1',
                                    body: 'Child with blank name',
                                    tombstone: false,
                                    author: { displayName: '   ' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const tombstoneEl = content.querySelectorAll('.novel-comment--reply')[0];
        const tombstoneBody = tombstoneEl.querySelector('.novel-comment-body--tombstone');
        assert.strictEqual(tombstoneBody.textContent, 'Bình luận đã bị xóa.');
        assert.strictEqual(tombstoneBody.querySelector('.novel-comment-reply-mention'), null);
    });

    test('78. tombstone with only a transitive active descendant does NOT use transitive child name and falls back', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'root-1', body: 'Root 1', tombstone: false },
                            replies: [
                                {
                                    id: 'tomb-t',
                                    parentCommentId: 'root-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true
                                },
                                {
                                    id: 'tomb-c',
                                    parentCommentId: 'tomb-t',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true
                                },
                                {
                                    id: 'active-d',
                                    parentCommentId: 'tomb-c',
                                    body: 'Deep active child',
                                    tombstone: false,
                                    author: { displayName: 'DeepUserD' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const replies = content.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 3);

        const tombstoneT = replies[0];
        const tombstoneTBody = tombstoneT.querySelector('.novel-comment-body--tombstone');
        // T must NOT use @DeepUserD because D is a child of C, not T
        assert.strictEqual(tombstoneTBody.textContent, 'Bình luận đã bị xóa.');
        assert.strictEqual(tombstoneTBody.querySelector('.novel-comment-reply-mention'), null);

        // C, however, has exactly one direct active child (D)
        const tombstoneC = replies[1];
        const tombstoneCBody = tombstoneC.querySelector('.novel-comment-body--tombstone');
        assert.strictEqual(tombstoneCBody.textContent, 'Bình luận mà @DeepUserD phản hồi đã bị xóa.');
    });

    test('79. contextual tombstone preserves existing privacy and action restrictions', async () => {
        const { doc, content } = setupChapterDOM();
        drawerModule.initReaderBlockDiscussionDrawer(doc, {
            fetchFn: async () => ({
                ok: true,
                status: 200,
                json: async () => ({
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    blockKey: 'blk-0123456789abcdef-1',
                    contentVersion: 1,
                    canonicalText: 'Text',
                    threadCount: 1,
                    threads: [
                        {
                            root: { id: 'root-1', body: 'Root 1', tombstone: false },
                            replies: [
                                {
                                    id: 'tomb-priv-1',
                                    parentCommentId: 'root-1',
                                    body: '[Bình luận đã bị xóa]',
                                    tombstone: true,
                                    canEdit: true,
                                    canDelete: true
                                },
                                {
                                    id: 'child-priv-1',
                                    parentCommentId: 'tomb-priv-1',
                                    body: 'Direct active reply',
                                    tombstone: false,
                                    canEdit: true,
                                    canDelete: true,
                                    author: { displayName: 'ActiveUser' }
                                }
                            ]
                        }
                    ]
                })
            })
        });

        doc.dispatchEvent({
            type: drawerModule.EVENT_DISCUSSION_REQUESTED,
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                contentVersion: 1,
                blockKey: 'blk-0123456789abcdef-1',
                canonicalText: 'Text',
                threadCount: 1
            }
        });

        await new Promise(r => setTimeout(r, 25));

        const tombstoneEl = content.querySelectorAll('.novel-comment--reply')[0];
        // No author presentation / avatar
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-author'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-avatar'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-avatar--fallback'), null);

        // No action buttons
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-reply-btn'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-edit-btn'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-delete-btn'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-actions'), null);
    });

    describe('MS-05E/E8C4 Novel Block Discussion Drawer Report Integration', () => {
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
            originalReportModal = drawerModule.setReportModal;
            drawerModule.setReportModal(mockModal);
        });

        afterEach(() => {
            drawerModule.setReportModal(null);
            drawerModule.setAuthenticatedImplementation(null);
            drawerModule.resetDrawerState();
        });

        test('REPORT-DRAWER-1. Active non-owner root comment renders "Báo cáo" button with data-action="report" and data-comment-id', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Discussion passage text',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'drawer-root-1',
                                    authorUserId: 'other-user',
                                    author: { displayName: 'Other User' },
                                    body: 'Active non-owner root in drawer',
                                    canEdit: false,
                                    canDelete: false,
                                    createdAt: '2026-09-18T10:00:00Z',
                                    updatedAt: '2026-09-18T10:00:00Z',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Discussion passage text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const rootEl = content.querySelector('.novel-comment--root');
            assert.ok(rootEl, 'Root comment element must exist');
            const reportBtn = rootEl.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn, 'Report button must be rendered for active non-owner root');
            assert.strictEqual(reportBtn.getAttribute('data-action'), 'report');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(reportBtn.getAttribute('data-comment-id'), 'drawer-root-1');
            assert.strictEqual((reportBtn.listeners.click || []).length, 0, 'No direct click listeners on root reportBtn');
        });

        test('REPORT-DRAWER-2. Active owner root comment (canEdit: true or canDelete: true) does NOT render "Báo cáo" button', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 2,
                        threads: [
                            {
                                root: {
                                    id: 'root-edit',
                                    canEdit: true,
                                    canDelete: false,
                                    body: 'Owner edit',
                                    tombstone: false
                                },
                                replies: []
                            },
                            {
                                root: {
                                    id: 'root-del',
                                    canEdit: false,
                                    canDelete: true,
                                    body: 'Owner del',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 2
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const reportBtns = content.querySelectorAll('.novel-comment-report-btn');
            assert.strictEqual(reportBtns.length, 0, 'Owned root comments must not have report button');
        });

        test('REPORT-DRAWER-3. Deleted root thread is hidden and does NOT render thread or "Báo cáo" button', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-tomb',
                                    tombstone: true,
                                    status: 'DELETED',
                                    canEdit: false,
                                    canDelete: false,
                                    body: null
                                },
                                replies: [
                                    {
                                        id: 'rep-tomb-child',
                                        parentCommentId: 'root-tomb',
                                        body: 'Child of deleted root',
                                        canEdit: false,
                                        canDelete: false,
                                        tombstone: false
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            assert.strictEqual(content.querySelector('.novel-block-discussion-thread'), null, 'Deleted root thread must be completely hidden');
            const reportBtns = content.querySelectorAll('.novel-comment-report-btn');
            assert.strictEqual(reportBtns.length, 0, 'Hidden deleted root thread must not have report button');
        });

        test('REPORT-DRAWER-4. Active non-owner reply renders "Báo cáo" button', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-1',
                                    canEdit: true,
                                    canDelete: true,
                                    body: 'Root'
                                },
                                replies: [
                                    {
                                        id: 'rep-non-owner',
                                        parentCommentId: 'root-1',
                                        author: { displayName: 'Other User' },
                                        body: 'Active non-owner reply',
                                        canEdit: false,
                                        canDelete: false,
                                        tombstone: false
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const replyEl = content.querySelector('.novel-comment--reply');
            assert.ok(replyEl);
            const reportBtn = replyEl.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn, 'Report button must be rendered for active non-owner reply');
            assert.strictEqual(reportBtn.getAttribute('data-action'), 'report');
            assert.strictEqual(reportBtn.textContent, 'Báo cáo');
            assert.strictEqual(reportBtn.getAttribute('data-comment-id'), 'rep-non-owner');
            assert.strictEqual((reportBtn.listeners.click || []).length, 0, 'No direct click listeners on reply reportBtn');
        });

        test('REPORT-DRAWER-5. Active owner reply does NOT render "Báo cáo" button', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: { id: 'root-1', canEdit: false, canDelete: false, body: 'Root' },
                                replies: [
                                    {
                                        id: 'rep-owner-edit',
                                        canEdit: true,
                                        canDelete: false,
                                        body: 'Edit reply',
                                        tombstone: false
                                    },
                                    {
                                        id: 'rep-owner-del',
                                        canEdit: false,
                                        canDelete: true,
                                        body: 'Del reply',
                                        tombstone: false
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const replyEls = content.querySelectorAll('.novel-comment--reply');
            for (const rep of replyEls) {
                assert.strictEqual(rep.querySelector('.novel-comment-report-btn'), null);
            }
        });

        test('REPORT-DRAWER-6. Reply tombstone does NOT render "Báo cáo" button', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: { id: 'root-1', canEdit: false, canDelete: false, body: 'Root' },
                                replies: [
                                    {
                                        id: 'rep-tomb',
                                        tombstone: true,
                                        status: 'DELETED',
                                        canEdit: false,
                                        canDelete: false,
                                        body: null
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const replyEl = content.querySelector('.novel-comment--reply');
            assert.ok(replyEl);
            assert.strictEqual(replyEl.querySelector('.novel-comment-report-btn'), null);
        });

        test('REPORT-DRAWER-7. Active descendant reply under a tombstoned intermediate reply DOES render "Báo cáo" button for non-owner', async () => {
            const { doc, content } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-1',
                                    tombstone: false,
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'Active root'
                                },
                                replies: [
                                    {
                                        id: 'rep-tomb-a',
                                        parentCommentId: 'root-1',
                                        tombstone: true,
                                        status: 'DELETED',
                                        canEdit: false,
                                        canDelete: false,
                                        body: null
                                    },
                                    {
                                        id: 'rep-active-descendant',
                                        parentCommentId: 'rep-tomb-a',
                                        author: { displayName: 'Active User' },
                                        body: 'Active child under deleted intermediate reply',
                                        tombstone: false,
                                        canEdit: false,
                                        canDelete: false
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const replyEls = content.querySelectorAll('.novel-comment--reply');
            assert.strictEqual(replyEls.length, 2, 'Two replies must be rendered');

            const repA = replyEls[0];
            assert.strictEqual(repA.querySelector('.novel-comment-report-btn'), null, 'Tombstone reply A must not have report button');

            const repB = replyEls[1];
            const reportBtn = repB.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn, 'Active descendant under tombstone parent must have report button');
            assert.strictEqual(reportBtn.getAttribute('data-comment-id'), 'rep-active-descendant');
        });

        test('REPORT-DRAWER-8. Unauthenticated guest clicking "Báo cáo" in drawer redirects to /login?returnTo=... without opening modal', async () => {
            const { doc, drawer, content } = setupChapterDOM();
            drawer.setAttribute('data-authenticated', 'false');
            doc.defaultView.location = {
                pathname: '/novel/chapters/chap-8-slug',
                search: '?ref=block',
                href: '/novel/chapters/chap-8-slug?ref=block'
            };

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: '11111111-1111-1111-1111-111111111111',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-guest-target',
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'Target',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: '11111111-1111-1111-1111-111111111111',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const reportBtn = content.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn);

            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 0, 'Modal must NOT be opened for unauthenticated guest');
            assert.strictEqual(
                doc.defaultView.location.href,
                '/login?returnTo=' + encodeURIComponent('/novel/chapters/chap-8-slug?ref=block'),
                'Guest must be redirected to /login with returnTo'
            );
        });

        test('REPORT-DRAWER-9. Authenticated user clicking "Báo cáo" opens CommentReportModal with correct parameters', async () => {
            const { doc, drawer, content } = setupChapterDOM();
            drawer.setAttribute('data-authenticated', 'true');

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: 'chap-drawer-123',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'cmt-drawer-456',
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'Drawer comment to report',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-drawer-123',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const reportBtn = content.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn);

            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 1, 'Modal open must be called once');
            const call = openCalls[0];
            assert.strictEqual(call.commentId, 'cmt-drawer-456');
            assert.strictEqual(call.submitUrl, '/api/novel/chapters/chap-drawer-123/comments/cmt-drawer-456/reports');
            assert.strictEqual(call.contextLabel, 'novel-block-discussion');
            assert.strictEqual(call.triggerEl, reportBtn);
            assert.strictEqual(typeof call.onSuccess, 'function');
        });

        test('REPORT-DRAWER-10. onSuccess callback disables button, updates text to "Đã báo cáo", and sets title tooltip', async () => {
            const { doc, drawer, content } = setupChapterDOM();
            drawer.setAttribute('data-authenticated', 'true');

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: 'chap-drawer-123',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'cmt-succ-drawer',
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'Drawer comment',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-drawer-123',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const reportBtn = content.querySelector('.novel-comment-report-btn');
            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 1);
            const { onSuccess } = openCalls[0];

            onSuccess({ reportId: 'rep-drawer-001', status: 'PENDING' });

            assert.strictEqual(reportBtn.textContent, 'Đã báo cáo');
            assert.strictEqual(reportBtn.disabled, true);
            assert.strictEqual(reportBtn.getAttribute('title'), 'Bạn đã gửi báo cáo cho bình luận này');
            assert.strictEqual(drawerModule.isDrawerOpen(), true, 'Drawer must remain open after reporting');
            assert.ok(content.querySelector('.novel-block-discussion-thread'), 'Discussion thread content must remain intact');
        });

        test('REPORT-DRAWER-11. ChapterId and CommentId with special characters are properly URL-encoded in submitUrl', async () => {
            const { doc, drawer, content } = setupChapterDOM();
            drawer.setAttribute('data-authenticated', 'true');

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: 'chap/drawer#1?x=y',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'cmt/drawer#2?a=b',
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'Special drawer comment',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap/drawer#1?x=y',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const reportBtn = content.querySelector('.novel-comment-report-btn');
            doc.dispatchEvent({
                type: 'click',
                target: reportBtn,
                preventDefault() {}
            });

            assert.strictEqual(openCalls.length, 1);
            const expectedUrl = '/api/novel/chapters/' + encodeURIComponent('chap/drawer#1?x=y') + '/comments/' + encodeURIComponent('cmt/drawer#2?a=b') + '/reports';
            assert.strictEqual(openCalls[0].submitUrl, expectedUrl);
        });

        test('REPORT-DRAWER-12. Unavailable or non-callable CommentReportModal fails safely without throw', async () => {
            const { doc, drawer, content } = setupChapterDOM();
            drawer.setAttribute('data-authenticated', 'true');

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                reportModal: {},
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: 'chap-drawer-f17',
                        blockKey: 'blk-0123456789abcdef-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'cmt-drawer-f17',
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'F17 comment',
                                    tombstone: false
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-drawer-f17',
                    contentVersion: 1,
                    blockKey: 'blk-0123456789abcdef-1',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const reportBtn = content.querySelector('.novel-comment-report-btn');
            assert.ok(reportBtn);

            assert.doesNotThrow(() => {
                doc.dispatchEvent({
                    type: 'click',
                    target: reportBtn,
                    preventDefault() {}
                });
            });

            assert.strictEqual(drawerModule.openReportModal('cmt-drawer-f17', reportBtn, doc), false, 'openReportModal returns false when modal is non-callable');
        });
    });

    describe('MS-05E / E8C4-UX2: Drawer Shared CommentPresentation Migration', () => {
        const sharedPresentation = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-presentation.js'));

        beforeEach(() => {
            drawerModule.resetDrawerState();
        });

        afterEach(() => {
            drawerModule.resetDrawerState();
        });

        test('A. Shared presentation classes: root and reply elements contain .kl-comment and BEM child classes', async () => {
            let capturedRootDescriptor = null;
            let capturedReplyDescriptor = null;
            const spyPresentation = {
                ...sharedPresentation,
                renderComment(desc, docRef, opts) {
                    if (desc && desc.id === 'root-101') capturedRootDescriptor = desc;
                    if (desc && desc.id === 'rep-101') capturedReplyDescriptor = desc;
                    return sharedPresentation.renderComment(desc, docRef, opts);
                }
            };
            drawerModule.setCommentPresentation(spyPresentation);

            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Đoạn văn mẫu',
                        contentVersion: 1,
                        chapterId: 'chap-101',
                        blockKey: 'blk-abc-1',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-101',
                                    authorUserId: 'u-1',
                                    body: 'Bình luận gốc',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { userId: 'u-1', displayName: 'Hàn Lập', avatarUrl: 'https://cdn.example.com/avatar.jpg' }
                                },
                                replies: [
                                    {
                                        id: 'rep-101',
                                        parentCommentId: 'root-101',
                                        authorUserId: 'u-2',
                                        body: 'Phản hồi cấp 1',
                                        tombstone: false,
                                        createdAt: '2026-09-20T10:05:00Z',
                                        author: { userId: 'u-2', displayName: 'Nam Cung Uyển', avatarUrl: null }
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-101',
                    contentVersion: 1,
                    blockKey: 'blk-abc-1',
                    canonicalText: 'Đoạn văn mẫu',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const rootEl = content.querySelector('.novel-comment--root');
            assert.ok(rootEl, 'Root comment element must exist');
            assert.ok(rootEl.classList.contains('kl-comment'), 'Root must have kl-comment base class');
            assert.ok(rootEl.querySelector('.kl-comment__header'), 'Root must have .kl-comment__header');
            assert.ok(rootEl.querySelector('.kl-comment__avatar'), 'Root must have .kl-comment__avatar');
            const rootTime = rootEl.querySelector('.kl-comment__time');
            assert.ok(rootTime, 'Root must have .kl-comment__time');
            assert.strictEqual(rootTime.hasAttribute('data-relative-time'), true, 'Root time must have data-relative-time');
            assert.strictEqual(rootTime.getAttribute('datetime'), '2026-09-20T10:00:00Z', 'Root time must preserve datetime');
            assert.ok(rootTime.getAttribute('title'), 'Root time must have title');
            assert.ok(rootTime.getAttribute('aria-label'), 'Root time must have aria-label');
            assert.ok(rootEl.querySelector('.kl-comment__body'), 'Root must have .kl-comment__body');
            assert.ok(rootEl.querySelector('.kl-comment__primary-actions'), 'Root must have .kl-comment__primary-actions');
            assert.ok(rootEl.querySelector('.kl-comment__overflow'), 'Root must have .kl-comment__overflow');

            // Architecture assertions: canonical createdAt and no formattedTime
            assert.ok(capturedRootDescriptor, 'Root descriptor must be passed to renderComment');
            assert.strictEqual(capturedRootDescriptor.createdAt, '2026-09-20T10:00:00Z', 'Root createdAt must match server createdAt');
            assert.strictEqual(capturedRootDescriptor.formattedTime, undefined, 'Root formattedTime must be undefined');

            const replyEl = content.querySelector('.novel-comment--reply');
            assert.ok(replyEl, 'Reply comment element must exist');
            assert.ok(replyEl.classList.contains('kl-comment'), 'Reply must have kl-comment base class');
            assert.ok(replyEl.querySelector('.kl-comment__header'), 'Reply must have .kl-comment__header');
            assert.ok(replyEl.querySelector('.kl-comment__avatar'), 'Reply must have .kl-comment__avatar');
            assert.ok(replyEl.querySelector('.kl-comment__author'), 'Reply must have .kl-comment__author');
            assert.ok(replyEl.querySelector('.kl-comment__time'), 'Reply must have .kl-comment__time');
            assert.ok(replyEl.querySelector('.kl-comment__body'), 'Reply must have .kl-comment__body');
            assert.ok(replyEl.querySelector('.kl-comment__primary-actions'), 'Reply must have .kl-comment__primary-actions');
            assert.ok(replyEl.querySelector('.kl-comment__overflow'), 'Reply must have .kl-comment__overflow');

            assert.ok(capturedReplyDescriptor, 'Reply descriptor must be passed to renderComment');
            assert.strictEqual(capturedReplyDescriptor.createdAt, '2026-09-20T10:05:00Z', 'Reply createdAt must match server createdAt');
            assert.strictEqual(capturedReplyDescriptor.formattedTime, undefined, 'Reply formattedTime must be undefined');
        });

        test('B. Legacy prefix compatibility: elements carry both kl-comment and novel-comment classes', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-102',
                        blockKey: 'blk-abc-2',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-102',
                                    body: 'Legacy test root',
                                    canEdit: true,
                                    canDelete: true,
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Trần Bình An' }
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-102',
                    contentVersion: 1,
                    blockKey: 'blk-abc-2',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const rootEl = content.querySelector('.novel-comment--root');
            assert.ok(rootEl.classList.contains('kl-comment'), 'Root has kl-comment');
            assert.ok(rootEl.classList.contains('novel-comment'), 'Root has novel-comment');

            const header = rootEl.querySelector('.kl-comment__header');
            assert.ok(header.classList.contains('kl-comment__header'), 'Header has kl-comment__header');
            assert.ok(header.classList.contains('novel-comment-header'), 'Header has novel-comment-header');

            const author = rootEl.querySelector('.kl-comment__author');
            assert.ok(author.classList.contains('kl-comment__author'), 'Author has kl-comment__author');
            assert.ok(author.classList.contains('novel-comment-author'), 'Author has novel-comment-author');

            const body = rootEl.querySelector('.kl-comment__body');
            assert.ok(body.classList.contains('kl-comment__body'), 'Body has kl-comment__body');
            assert.ok(body.classList.contains('novel-comment-body'), 'Body has novel-comment-body');

            const replyBtn = rootEl.querySelector('.novel-comment-reply-btn');
            assert.ok(replyBtn.classList.contains('kl-comment__primary-action'), 'Reply btn has kl-comment__primary-action');
            assert.ok(replyBtn.classList.contains('novel-comment-reply-btn'), 'Reply btn has novel-comment-reply-btn');

            const menu = rootEl.querySelector('.kl-comment__overflow');
            assert.ok(menu.classList.contains('kl-comment__overflow'), 'Overflow has kl-comment__overflow');
            assert.ok(menu.classList.contains('novel-comment-actions-menu'), 'Overflow has novel-comment-actions-menu');
        });

        test('C. Primary action: "Phản hồi" button outside overflow menu carrying semantic IDs', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: 'chap-103',
                        blockKey: 'blk-abc-3',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-103',
                                    body: 'Active root',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Lý Thất Dạ' }
                                },
                                replies: [
                                    {
                                        id: 'rep-103',
                                        parentCommentId: 'root-103',
                                        body: 'Active child',
                                        tombstone: false,
                                        createdAt: '2026-09-20T10:05:00Z',
                                        author: { displayName: 'Nam Hoài Nhân' }
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-103',
                    contentVersion: 1,
                    blockKey: 'blk-abc-3',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const rootBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
            assert.ok(rootBtn, 'Root reply button must exist');
            assert.strictEqual(rootBtn.textContent, 'Phản hồi');
            assert.strictEqual(rootBtn.getAttribute('data-action'), 'reply');
            assert.strictEqual(rootBtn.getAttribute('data-comment-id'), 'root-103');
            assert.strictEqual(rootBtn.getAttribute('data-root-id'), 'root-103');
            assert.strictEqual(rootBtn.getAttribute('data-author-name'), 'Lý Thất Dạ');

            const repBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
            assert.ok(repBtn, 'Reply button must exist');
            assert.strictEqual(repBtn.textContent, 'Phản hồi');
            assert.strictEqual(repBtn.getAttribute('data-action'), 'reply');
            assert.strictEqual(repBtn.getAttribute('data-comment-id'), 'rep-103');
            assert.strictEqual(repBtn.getAttribute('data-root-id'), 'root-103');
            assert.strictEqual(repBtn.getAttribute('data-author-name'), 'Nam Hoài Nhân');
        });

        test('D. Overflow menu rendering: 3-dots trigger with aria attributes and hidden popover', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Passage',
                        contentVersion: 1,
                        chapterId: 'chap-104',
                        blockKey: 'blk-abc-4',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-104',
                                    canEdit: true,
                                    canDelete: true,
                                    body: 'Comment',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Bạch Tiểu Thuần' }
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-104',
                    contentVersion: 1,
                    blockKey: 'blk-abc-4',
                    canonicalText: 'Passage',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const trigger = content.querySelector('.kl-comment__menu-trigger');
            assert.ok(trigger, 'Menu trigger must exist');
            assert.strictEqual(trigger.getAttribute('aria-label'), 'Mở menu bình luận');
            assert.strictEqual(trigger.getAttribute('aria-haspopup'), 'menu');
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

            const dots = trigger.querySelector('.kl-comment__menu-dots');
            assert.ok(dots, 'Dots element must exist');
            assert.strictEqual(dots.textContent, '⋯');

            const menu = content.querySelector('.kl-comment__menu');
            assert.ok(menu, 'Menu popover must exist');
            assert.strictEqual(menu.getAttribute('role'), 'menu');
            assert.strictEqual(menu.hidden, true);
        });

        test('E. Overflow descriptors ordering and strict exclusion of view-origin in drawer', () => {
            const descriptors = drawerModule.buildOverflowActionDescriptors({
                commentId: 'cmt-ord-1',
                rootCommentId: 'cmt-ord-1',
                isEdited: true,
                canEdit: true,
                canDelete: true,
                canReport: false,
                originNavigable: true,
                blockKey: 'blk-1'
            });

            const keys = descriptors.map(d => d.key);
            assert.deepStrictEqual(keys, ['view-revisions', 'edit', 'delete']);
            assert.strictEqual(descriptors.some(d => d.key === 'view-origin'), false, 'Drawer must NEVER render view-origin');
        });

        test('F. Separator before report when preceded by revisions, edit, or delete', () => {
            const descriptorsWithEdit = drawerModule.buildOverflowActionDescriptors({
                commentId: 'cmt-sep-1',
                rootCommentId: 'cmt-sep-1',
                isEdited: false,
                canEdit: true,
                canDelete: false,
                canReport: true
            });
            const reportDesc = descriptorsWithEdit.find(d => d.key === 'report');
            assert.ok(reportDesc, 'Report descriptor must be present');
            assert.strictEqual(reportDesc.separatorBefore, true, 'Report preceded by edit must have separatorBefore');

            const descriptorsReportOnly = drawerModule.buildOverflowActionDescriptors({
                commentId: 'cmt-sep-2',
                rootCommentId: 'cmt-sep-2',
                isEdited: false,
                canEdit: false,
                canDelete: false,
                canReport: true
            });
            assert.strictEqual(descriptorsReportOnly.length, 1);
            assert.strictEqual(descriptorsReportOnly[0].separatorBefore, false, 'Report-only descriptor must NOT have separatorBefore');
        });

        test('G. Danger styling on delete and report items', () => {
            const descriptors = drawerModule.buildOverflowActionDescriptors({
                commentId: 'cmt-danger-1',
                rootCommentId: 'cmt-danger-1',
                isEdited: false,
                canEdit: false,
                canDelete: true,
                canReport: true
            });

            const del = descriptors.find(d => d.key === 'delete');
            const rep = descriptors.find(d => d.key === 'report');
            assert.ok(del && del.danger === true, 'Delete must have danger: true');
            assert.ok(rep && rep.danger === true, 'Report must have danger: true');

            const doc = new FakeDocument();
            const menuEl = drawerModule.createActionsMenu(descriptors, doc);
            assert.ok(menuEl, 'Rendered menu element must exist');
            const delBtn = menuEl.querySelector('[data-action="delete"]');
            const repBtn = menuEl.querySelector('[data-action="report"]');
            assert.ok(delBtn && delBtn.classList.contains('kl-comment__menu-item--danger'), 'Delete button has danger class');
            assert.ok(repBtn && repBtn.classList.contains('kl-comment__menu-item--danger'), 'Report button has danger class');
        });

        test('H. Capability ownership - Owner: canEdit: true, canDelete: true -> canReport: false', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-105',
                        blockKey: 'blk-abc-5',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-owner-1',
                                    canEdit: true,
                                    canDelete: true,
                                    body: 'Owner comment',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Owner' }
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-105',
                    contentVersion: 1,
                    blockKey: 'blk-abc-5',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const rootEl = content.querySelector('.novel-comment--root');
            const editBtn = rootEl.querySelector('[data-action="edit"]');
            const delBtn = rootEl.querySelector('[data-action="delete"]');
            const repBtn = rootEl.querySelector('[data-action="report"]');
            assert.ok(editBtn, 'Owner has edit action');
            assert.ok(delBtn, 'Owner has delete action');
            assert.strictEqual(repBtn, null, 'Owner must NOT have report action');
        });

        test('I. Capability ownership - Non-owner: canEdit: false, canDelete: false -> canReport: true', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-106',
                        blockKey: 'blk-abc-6',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-nonowner-1',
                                    canEdit: false,
                                    canDelete: false,
                                    body: 'Non-owner comment',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Non-Owner' }
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-106',
                    contentVersion: 1,
                    blockKey: 'blk-abc-6',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const rootEl = content.querySelector('.novel-comment--root');
            const editBtn = rootEl.querySelector('[data-action="edit"]');
            const delBtn = rootEl.querySelector('[data-action="delete"]');
            const repBtn = rootEl.querySelector('[data-action="report"]');
            assert.strictEqual(editBtn, null, 'Non-owner does not have edit');
            assert.strictEqual(delBtn, null, 'Non-owner does not have delete');
            assert.ok(repBtn, 'Non-owner has report action');
        });

        test('J. Deleted root thread is completely hidden: no root card, no descendant replies, no actions', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-107',
                        blockKey: 'blk-abc-7',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-tomb-1',
                                    tombstone: true,
                                    status: 'DELETED',
                                    body: 'Old text',
                                    createdAt: '2026-09-20T10:00:00Z'
                                },
                                replies: [
                                    {
                                        id: 'rep-under-deleted-root',
                                        parentCommentId: 'root-tomb-1',
                                        body: 'Descendant reply',
                                        tombstone: false,
                                        canEdit: true,
                                        canDelete: true,
                                        createdAt: '2026-09-20T10:05:00Z'
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-107',
                    contentVersion: 1,
                    blockKey: 'blk-abc-7',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            // Entire thread must be absent
            assert.strictEqual(content.querySelector('.novel-block-discussion-thread'), null, 'No thread card should be rendered for deleted root');
            assert.strictEqual(content.querySelector('.novel-comment--root'), null, 'No root comment card should be rendered');
            assert.strictEqual(content.querySelector('.novel-comment--reply'), null, 'No replies should be rendered from deleted root thread');
            assert.strictEqual(content.querySelector('.novel-comment-reply-btn'), null, 'No reply button from deleted root thread');
            assert.strictEqual(content.querySelector('[data-action="report"]'), null, 'No report button from deleted root thread');
            assert.strictEqual(content.querySelector('[data-action="edit"]'), null, 'No edit button from deleted root thread');
            assert.strictEqual(content.querySelector('[data-action="delete"]'), null, 'No delete button from deleted root thread');
        });

        test('K. Active root with intermediate tombstone reply: intermediate tombstone has no actions, active descendant remains actionable and reportable', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-108',
                        blockKey: 'blk-abc-8',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-active-k',
                                    tombstone: false,
                                    canEdit: true,
                                    canDelete: true,
                                    body: 'Active parent root',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Active Root Author' }
                                },
                                replies: [
                                    {
                                        id: 'rep-tombstone-inter',
                                        parentCommentId: 'root-active-k',
                                        tombstone: true,
                                        status: 'DELETED',
                                        body: '[Bình luận đã bị xóa]',
                                        createdAt: '2026-09-20T10:05:00Z'
                                    },
                                    {
                                        id: 'rep-active-descendant',
                                        parentCommentId: 'rep-tombstone-inter',
                                        authorUserId: 'u-descendant',
                                        body: 'Active non-owner descendant reply',
                                        tombstone: false,
                                        canEdit: false,
                                        canDelete: false,
                                        createdAt: '2026-09-20T10:10:00Z',
                                        author: { displayName: 'Descendant User' }
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-108',
                    contentVersion: 1,
                    blockKey: 'blk-abc-8',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            // Root is rendered
            const rootEl = content.querySelector('.novel-comment--root');
            assert.ok(rootEl, 'Active root must be rendered');

            // Replies
            const replyEls = content.querySelectorAll('.novel-comment--reply');
            assert.strictEqual(replyEls.length, 2, 'Both intermediate tombstone and descendant reply must be rendered');

            // Intermediate tombstone has no actions
            const tombEl = replyEls[0];
            assert.strictEqual(
                tombEl.classList.contains('is-tombstone'),
                true,
                'Intermediate deleted reply must render as tombstone'
            );
            assert.strictEqual(tombEl.querySelector('.kl-comment__overflow'), null, 'Intermediate tombstone has no overflow menu');
            assert.strictEqual(tombEl.querySelector('.novel-comment-reply-btn'), null, 'Intermediate tombstone has no reply button');
            assert.strictEqual(tombEl.querySelector('[data-action="report"]'), null, 'Intermediate tombstone has no report button');

            // Active descendant has Phản hồi and Report
            const descEl = replyEls[1];
            assert.ok(descEl, 'Active descendant is rendered');
            const descReplyBtn = descEl.querySelector('.novel-comment-reply-btn');
            assert.ok(descReplyBtn, 'Active descendant has Phản hồi button');
            assert.strictEqual(descReplyBtn.getAttribute('data-action'), 'reply');
            assert.strictEqual(descReplyBtn.getAttribute('data-comment-id'), 'rep-active-descendant');
            assert.strictEqual(descReplyBtn.getAttribute('data-reply-id'), 'rep-active-descendant');
            assert.strictEqual(descReplyBtn.getAttribute('data-root-id'), 'root-active-k');
            assert.strictEqual(descReplyBtn.getAttribute('data-author-name'), 'Descendant User');

            const descReportBtn = descEl.querySelector('[data-action="report"]');
            assert.ok(descReportBtn, 'Non-owner descendant has Report action');
            assert.strictEqual(descReportBtn.getAttribute('data-comment-id'), 'rep-active-descendant');
            assert.strictEqual(descReportBtn.getAttribute('data-reply-id'), 'rep-active-descendant');
            assert.strictEqual(descReportBtn.getAttribute('data-root-id'), 'root-active-k');
        });

        test('L. Contextual tombstone message: single active direct child renders contextual deletion notice', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-109',
                        blockKey: 'blk-abc-9',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-109',
                                    body: 'Root',
                                    createdAt: '2026-09-20T10:00:00Z'
                                },
                                replies: [
                                    {
                                        id: 'rep-tomb-109',
                                        parentCommentId: 'root-109',
                                        tombstone: true,
                                        createdAt: '2026-09-20T10:05:00Z'
                                    },
                                    {
                                        id: 'rep-child-109',
                                        parentCommentId: 'rep-tomb-109',
                                        tombstone: false,
                                        body: 'Active child replying to deleted comment',
                                        createdAt: '2026-09-20T10:10:00Z',
                                        author: { displayName: 'Vương Lâm' }
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-109',
                    contentVersion: 1,
                    blockKey: 'blk-abc-9',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const replies = content.querySelectorAll('.novel-comment--reply');
            assert.strictEqual(replies.length, 2);
            const tombReply = replies[0];
            assert.ok(tombReply.textContent.includes('Bình luận mà @Vương Lâm phản hồi đã bị xóa.'));
            const mentionSpan = tombReply.querySelector('.novel-comment-reply-mention');
            assert.ok(mentionSpan, 'Contextual tombstone mention span must exist');
            assert.strictEqual(mentionSpan.textContent, '@Vương Lâm');
        });

        test('M. Wattpad @mention parent attribution preserved for active reply with non-root parent', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-110',
                        blockKey: 'blk-abc-10',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-110',
                                    body: 'Root',
                                    createdAt: '2026-09-20T10:00:00Z'
                                },
                                replies: [
                                    {
                                        id: 'rep-parent-110',
                                        parentCommentId: 'root-110',
                                        body: 'Parent reply',
                                        tombstone: false,
                                        createdAt: '2026-09-20T10:05:00Z',
                                        author: { displayName: 'Mạnh Hạo' }
                                    },
                                    {
                                        id: 'rep-child-110',
                                        parentCommentId: 'rep-parent-110',
                                        body: 'Child reply text',
                                        tombstone: false,
                                        createdAt: '2026-09-20T10:10:00Z',
                                        author: { displayName: 'Hứa Thanh' }
                                    }
                                ]
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-110',
                    contentVersion: 1,
                    blockKey: 'blk-abc-10',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const replies = content.querySelectorAll('.novel-comment--reply');
            const childReply = replies[1];
            assert.ok(childReply, 'Child reply must exist');
            const mention = childReply.querySelector('.novel-comment-reply-mention');
            assert.ok(mention, 'Mention span must exist in child reply');
            assert.strictEqual(mention.textContent, '@Mạnh Hạo');
            const bodyText = childReply.querySelector('.novel-comment-reply-body-text');
            assert.ok(bodyText, 'Body text span must exist');
            assert.strictEqual(bodyText.textContent, 'Child reply text');
        });

        test('N. Menu open/close toggle and outside click delegation', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-111',
                        blockKey: 'blk-abc-11',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-111',
                                    canEdit: true,
                                    canDelete: true,
                                    body: 'Comment for menu test',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Tô Minh' }
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-111',
                    contentVersion: 1,
                    blockKey: 'blk-abc-11',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            const trigger = content.querySelector('.kl-comment__menu-trigger');
            const menu = content.querySelector('.kl-comment__menu');
            const container = content.querySelector('.kl-comment__overflow');
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(menu.hidden, true);

            // 1. Click trigger -> open menu
            trigger.dispatchEvent({ type: 'click', target: trigger });
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
            assert.strictEqual(menu.hidden, false);
            assert.ok(container.classList.contains('is-open'));
            assert.ok(drawerModule.getActiveOpenMenu() !== null, 'Active open menu descriptor is present');

            // 2. Click trigger again -> closes menu
            trigger.dispatchEvent({ type: 'click', target: trigger });
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
            assert.strictEqual(menu.hidden, true);
            assert.strictEqual(container.classList.contains('is-open'), false);
            assert.strictEqual(drawerModule.getActiveOpenMenu(), null);

            // 3. Open menu again and click outside -> closes menu
            trigger.dispatchEvent({ type: 'click', target: trigger });
            assert.strictEqual(menu.hidden, false);

            const outsideEl = doc.body;
            doc.dispatchEvent({ type: 'click', target: outsideEl });
            assert.strictEqual(menu.hidden, true);
            assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        });

        test('O. Escape key precedence: first Escape dismisses menu, second Escape dismisses drawer', async () => {
            const { doc, content } = setupChapterDOM();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-112',
                        blockKey: 'blk-abc-12',
                        threadCount: 1,
                        threads: [
                            {
                                root: {
                                    id: 'root-112',
                                    canEdit: true,
                                    canDelete: true,
                                    body: 'Comment',
                                    createdAt: '2026-09-20T10:00:00Z',
                                    author: { displayName: 'Diệp Phàm' }
                                },
                                replies: []
                            }
                        ]
                    })
                })
            });

            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-112',
                    contentVersion: 1,
                    blockKey: 'blk-abc-12',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));

            assert.strictEqual(drawerModule.isDrawerOpen(), true, 'Drawer is open');

            // Open overflow menu
            const trigger = content.querySelector('.kl-comment__menu-trigger');
            trigger.dispatchEvent({ type: 'click', target: trigger });
            assert.ok(drawerModule.getActiveOpenMenu() !== null, 'Menu is open');

            // First Escape: closes menu, drawer remains open
            const escEvent1 = {
                type: 'keydown',
                key: 'Escape',
                defaultPrevented: false,
                preventDefault() { this.defaultPrevented = true; }
            };
            doc.dispatchEvent(escEvent1);

            assert.strictEqual(drawerModule.getActiveOpenMenu(), null, 'Menu must be closed after 1st Escape');
            assert.strictEqual(drawerModule.isDrawerOpen(), true, 'Drawer MUST REMAIN OPEN after 1st Escape');

            // Second Escape: closes drawer
            const escEvent2 = {
                type: 'keydown',
                key: 'Escape',
                defaultPrevented: false,
                preventDefault() { this.defaultPrevented = true; }
            };
            doc.dispatchEvent(escEvent2);

            assert.strictEqual(drawerModule.isDrawerOpen(), false, 'Drawer must be closed after 2nd Escape');
        });

        test('P. Fail-safe contract: when CommentPresentation is null / missing, render returns null without crash', async () => {
            const { doc } = setupChapterDOM();

            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                commentPresentation: null,
                fetchFn: () => Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({
                        canonicalText: 'Text',
                        contentVersion: 1,
                        chapterId: 'chap-113',
                        blockKey: 'blk-abc-13',
                        threadCount: 1,
                        threads: [
                            {
                                root: { id: 'root-113', body: 'Comment', createdAt: '2026-09-20T10:00:00Z' },
                                replies: []
                            }
                        ]
                    })
                })
            });

            assert.strictEqual(drawerModule.renderRoot({ id: 'r-1' }, doc), null);
            assert.strictEqual(drawerModule.renderReply({ id: 'rep-1' }, { id: 'r-1' }, [], {}, doc), null);

            // Dispatching discussion request does not crash
            doc.dispatchEvent({
                type: drawerModule.EVENT_DISCUSSION_REQUESTED,
                detail: {
                    chapterId: 'chap-113',
                    contentVersion: 1,
                    blockKey: 'blk-abc-13',
                    canonicalText: 'Text',
                    threadCount: 1
                }
            });

            await new Promise(r => setTimeout(r, 20));
            assert.strictEqual(drawerModule.isDrawerOpen(), true, 'Drawer opens safely even when presentation is null');
        });

        test('Q. Explicit dependency injection via options.commentPresentation and setCommentPresentation', () => {
            const customPresentation = {
                renderComment: () => null,
                renderActionsMenu: () => null,
                closeActiveMenu: () => {},
                getActiveOpenMenu: () => null
            };

            const doc = new FakeDocument();
            drawerModule.initReaderBlockDiscussionDrawer(doc, {
                commentPresentation: customPresentation
            });

            assert.strictEqual(drawerModule.resolveCommentPresentation(), customPresentation);

            const anotherPresentation = { ...customPresentation };
            drawerModule.setCommentPresentation(anotherPresentation);
            assert.strictEqual(drawerModule.resolveCommentPresentation(), anotherPresentation);
        });

        test('R. Teardown / cleanup: resetDrawerState clears injected presentation and resets menu', () => {
            const customPresentation = {
                renderComment: () => null,
                unbindDocument: () => {}
            };
            drawerModule.setCommentPresentation(customPresentation);
            assert.strictEqual(drawerModule.resolveCommentPresentation(), customPresentation);

            drawerModule.resetDrawerState();
            assert.strictEqual(drawerModule.getActiveOpenMenu(), null);
            assert.strictEqual(drawerModule.isDrawerOpen(), false);
            // After reset, resolution falls back to default shared module
            assert.strictEqual(drawerModule.resolveCommentPresentation(), sharedPresentation);
        });
    });
});
