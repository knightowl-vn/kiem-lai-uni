const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const replyComposerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-reply-composer.js'));
const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));
const draftsAdapter = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-drafts.js'));

function createMockDraftStore() {
    const storageMap = new Map();
    const mockStorage = {
        getItem: (k) => storageMap.has(k) ? storageMap.get(k) : null,
        setItem: (k, v) => { storageMap.set(k, String(v)); },
        removeItem: (k) => { storageMap.delete(k); },
        clear: () => { storageMap.clear(); }
    };
    return {
        store: EphemeralDraftStore.createStore({ storage: mockStorage }),
        storageMap
    };
}

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
        this.disabled = false;
        this.value = '';
        this._textContent = '';
        this.isFocused = false;
        this.ownerDocument = null;

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
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
        child.ownerDocument = this.ownerDocument;
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
        try { evt.target = evt.target || this; } catch (_) {}
        let cur = this;
        while (cur) {
            try { evt.currentTarget = cur; } catch (_) {}
            const handlers = cur.listeners ? (cur.listeners[evt.type] || []) : [];
            for (const fn of [...handlers]) {
                fn.call(cur, evt);
            }
            if (evt.propagationStopped) break;
            cur = cur.parentElement || cur.parentNode;
        }
        if (!evt.propagationStopped && this.ownerDocument && this.ownerDocument.listeners) {
            try { evt.currentTarget = this.ownerDocument; } catch (_) {}
            const docHandlers = this.ownerDocument.listeners[evt.type] || [];
            for (const fn of [...docHandlers]) {
                fn.call(this.ownerDocument, evt);
            }
        }
        return !evt.defaultPrevented;
    }

    click() {
        const evt = {
            type: 'click',
            target: this,
            currentTarget: this,
            defaultPrevented: false,
            propagationStopped: false,
            preventDefault() { this.defaultPrevented = true; },
            stopPropagation() { this.propagationStopped = true; }
        };
        this.dispatchEvent(evt);
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
    if (selector.includes(',')) {
        const subSelectors = selector.split(',').map(s => s.trim()).filter(Boolean);
        const set = new Set();
        for (const sub of subSelectors) {
            for (const el of querySelectorAllDeep(root, sub)) {
                set.add(el);
            }
        }
        return Array.from(set);
    }
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
        this.activeElement = null;
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
        try { evt.target = evt.target || this; } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

/**
 * Creates standard DOM fixture for reply composer tests.
 */
function createReplyFixture(options = {}) {
    const doc = new FakeDocument();
    const chapterId = options.chapterId || 'chap-reply-1';
    const chapterSlug = options.chapterSlug || 'chuong-1';
    const authenticated = options.authenticated !== undefined ? options.authenticated : true;

    if (options.location) {
        doc.defaultView = { location: options.location };
    }

    const section = doc.createElement('section');
    section.setAttribute('id', 'novelChapterComments');
    section.className = 'novel-chapter-comments';
    section.setAttribute('data-chapter-id', chapterId);
    section.setAttribute('data-chapter-slug', chapterSlug);
    section.setAttribute('data-authenticated', authenticated ? 'true' : 'false');

    const statusEl = doc.createElement('div');
    statusEl.setAttribute('id', 'novelChapterCommentsStatus');
    statusEl.className = 'novel-chapter-comments-status';
    section.appendChild(statusEl);

    const listEl = doc.createElement('div');
    listEl.setAttribute('id', 'novelChapterCommentsList');
    listEl.className = 'novel-chapter-comments-list';

    // Thread 1 Card
    const thread1 = doc.createElement('article');
    thread1.className = 'novel-block-discussion-thread';
    thread1.setAttribute('data-root-id', 'root-1');

    // Root Comment in Thread 1
    const root1 = doc.createElement('div');
    root1.className = 'novel-comment novel-comment--root';
    root1.setAttribute('data-comment-id', 'root-1');

    const root1Actions = doc.createElement('div');
    root1Actions.className = 'novel-comment-actions';
    const root1ReplyBtn = doc.createElement('button');
    root1ReplyBtn.type = 'button';
    root1ReplyBtn.className = 'novel-comment-reply-btn';
    root1ReplyBtn.setAttribute('data-action', 'reply');
    root1ReplyBtn.setAttribute('data-comment-id', 'root-1');
    root1ReplyBtn.setAttribute('data-root-id', 'root-1');
    root1ReplyBtn.setAttribute('data-author-name', 'Tác Giả Gốc');
    root1ReplyBtn.textContent = 'Phản hồi';
    root1Actions.appendChild(root1ReplyBtn);
    root1.appendChild(root1Actions);
    thread1.appendChild(root1);

    // Reply 1 in Thread 1
    const repliesContainer = doc.createElement('div');
    repliesContainer.className = 'novel-comment-replies';

    const reply1 = doc.createElement('article');
    reply1.className = 'novel-comment novel-comment--reply';
    reply1.setAttribute('data-reply-id', 'rep-1');
    reply1.setAttribute('data-comment-id', 'rep-1');

    const reply1Actions = doc.createElement('div');
    reply1Actions.className = 'novel-comment-actions';
    const reply1ReplyBtn = doc.createElement('button');
    reply1ReplyBtn.type = 'button';
    reply1ReplyBtn.className = 'novel-comment-reply-btn';
    reply1ReplyBtn.setAttribute('data-action', 'reply');
    reply1ReplyBtn.setAttribute('data-comment-id', 'rep-1');
    reply1ReplyBtn.setAttribute('data-root-id', 'root-1');
    reply1ReplyBtn.setAttribute('data-author-name', 'Người Phản Hồi 1');
    reply1ReplyBtn.textContent = 'Phản hồi';
    reply1Actions.appendChild(reply1ReplyBtn);
    reply1.appendChild(reply1Actions);
    repliesContainer.appendChild(reply1);
    thread1.appendChild(repliesContainer);

    listEl.appendChild(thread1);

    // Thread 2 Card (for multi-thread tests)
    const thread2 = doc.createElement('article');
    thread2.className = 'novel-block-discussion-thread';
    thread2.setAttribute('data-root-id', 'root-2');

    const root2 = doc.createElement('div');
    root2.className = 'novel-comment novel-comment--root';
    root2.setAttribute('data-comment-id', 'root-2');

    const root2Actions = doc.createElement('div');
    root2Actions.className = 'novel-comment-actions';
    const root2ReplyBtn = doc.createElement('button');
    root2ReplyBtn.type = 'button';
    root2ReplyBtn.className = 'novel-comment-reply-btn';
    root2ReplyBtn.setAttribute('data-action', 'reply');
    root2ReplyBtn.setAttribute('data-comment-id', 'root-2');
    root2ReplyBtn.setAttribute('data-root-id', 'root-2');
    root2ReplyBtn.setAttribute('data-author-name', 'Tác Giả Gốc 2');
    root2ReplyBtn.textContent = 'Phản hồi';
    root2Actions.appendChild(root2ReplyBtn);
    root2.appendChild(root2Actions);
    thread2.appendChild(root2);

    listEl.appendChild(thread2);

    section.appendChild(listEl);
    doc.body.appendChild(section);

    return {
        doc,
        section,
        statusEl,
        listEl,
        thread1,
        root1,
        root1ReplyBtn,
        reply1,
        reply1ReplyBtn,
        thread2,
        root2,
        root2ReplyBtn
    };
}

// ============================================================================
// Test Suite: reader-chapter-comment-reply-composer (MS-05E5H2F2B)
// ============================================================================

describe('Reader Chapter Comment Reply Composer UI (MS-05E5H2F2B)', () => {

    afterEach(() => {
        replyComposerModule.destroy();
    });

    test('1. init on missing DOM elements exits gracefully without throwing', () => {
        const doc = new FakeDocument();
        assert.doesNotThrow(() => {
            replyComposerModule.init(doc);
        });
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    test('2. Unauthenticated user clicking "Phản hồi" on root renders guest prompt with login link targeting #novelChapterComments', () => {
        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const prompt = fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt, 'Guest prompt must be rendered under root comment');
        assert.ok(prompt.textContent.includes('Đăng nhập để phản hồi.'));
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link);

        const expectedReturn = '/novel/chapters/chuong-1#novelChapterComments';
        const expectedHref = '/login?returnTo=' + encodeURIComponent(expectedReturn);
        assert.strictEqual(link.getAttribute('href'), expectedHref, 'Login link href must contain encoded canonical return target with #novelChapterComments');

        const returnParam = new URL(link.getAttribute('href'), 'https://example.com').searchParams.get('returnTo');
        assert.strictEqual(returnParam, expectedReturn, 'Decoded returnTo must exactly target fallback slug with #novelChapterComments');

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getActiveGuestPrompt(), prompt);
    });

    test('2a. Guest clicking "Phản hồi" with browser location preserving query string targets exact path, query, and #novelChapterComments', () => {
        const fixture = createReplyFixture({
            authenticated: false,
            location: {
                pathname: '/novel/chapters/test-chapter',
                search: '?ref=reader'
            }
        });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const prompt = fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt, 'Guest prompt must be rendered under root comment');
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link, 'Login link must exist');

        const expectedReturn = '/novel/chapters/test-chapter?ref=reader#novelChapterComments';
        const expectedHref = '/login?returnTo=' + encodeURIComponent(expectedReturn);
        assert.strictEqual(link.getAttribute('href'), expectedHref, 'Login link href must match encoded path, query, and #novelChapterComments');

        const returnParam = new URL(link.getAttribute('href'), 'https://example.com').searchParams.get('returnTo');
        assert.strictEqual(returnParam, expectedReturn, 'Decoded returnTo must exactly preserve query string and append #novelChapterComments');
    });

    test('2b. Guest clicking "Phản hồi" with browser location without query targets exact path and #novelChapterComments', () => {
        const fixture = createReplyFixture({
            authenticated: false,
            location: {
                pathname: '/novel/chapters/quyen-1-chuong-1',
                search: ''
            }
        });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const prompt = fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt);
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link);

        const expectedReturn = '/novel/chapters/quyen-1-chuong-1#novelChapterComments';
        const expectedHref = '/login?returnTo=' + encodeURIComponent(expectedReturn);
        assert.strictEqual(link.getAttribute('href'), expectedHref);

        const returnParam = new URL(link.getAttribute('href'), 'https://example.com').searchParams.get('returnTo');
        assert.strictEqual(returnParam, expectedReturn, 'Decoded returnTo without query must target exact path and #novelChapterComments');
    });

    test('2c. Guest clicking "Phản hồi" discards unrelated existing hash in location and strictly targets #novelChapterComments', () => {
        const fixture = createReplyFixture({
            authenticated: false,
            location: {
                pathname: '/novel/chapters/quyen-1-chuong-1',
                search: '?ref=test',
                hash: '#unrelatedExistingAnchor'
            }
        });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const prompt = fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt);
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link);

        const expectedReturn = '/novel/chapters/quyen-1-chuong-1?ref=test#novelChapterComments';
        const expectedHref = '/login?returnTo=' + encodeURIComponent(expectedReturn);
        assert.strictEqual(link.getAttribute('href'), expectedHref);

        const returnParam = new URL(link.getAttribute('href'), 'https://example.com').searchParams.get('returnTo');
        assert.strictEqual(returnParam, expectedReturn);
        assert.strictEqual(returnParam.includes('unrelatedExistingAnchor'), false, 'Unrelated hash must not be preserved');
    });

    test('2d. Guest clicking "Phản hồi" with fallback slug and special characters properly encodes slug and targets #novelChapterComments', () => {
        const fixture = createReplyFixture({
            authenticated: false,
            chapterSlug: 'tap-1/chuong-1'
        });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const prompt = fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt, 'Guest prompt must be rendered under root comment');
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link, 'Login link must exist');

        const expectedReturn = '/novel/chapters/' + encodeURIComponent('tap-1/chuong-1') + '#novelChapterComments';
        const expectedHref = '/login?returnTo=' + encodeURIComponent(expectedReturn);
        assert.strictEqual(link.getAttribute('href'), expectedHref, 'Login link href must contain encoded return target');

        const returnParam = new URL(link.getAttribute('href'), 'https://example.com').searchParams.get('returnTo');
        assert.strictEqual(returnParam, expectedReturn, 'Decoded returnTo must exactly match expected target');
    });

    test('3. Unauthenticated user clicking "Phản hồi" on reply renders guest prompt under that reply', () => {
        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc);

        fixture.reply1ReplyBtn.click();

        const prompt = fixture.reply1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt, 'Guest prompt must be rendered under reply comment');
        assert.ok(prompt.textContent.includes('Đăng nhập để phản hồi.'));
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link);
        assert.strictEqual(
            link.getAttribute('href'),
            '/login?returnTo=' + encodeURIComponent('/novel/chapters/chuong-1#novelChapterComments')
        );
    });

    test('4. Guest prompt toggle: clicking "Phản hồi" again on same comment closes the guest prompt', () => {
        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt'));

        fixture.root1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt'), null);
        assert.strictEqual(replyComposerModule.getActiveGuestPrompt(), null);
    });

    test('5. Authenticated user clicking "Phản hồi" on root mounts inline reply composer under root comment', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composer, 'Inline composer must be mounted in root comment');
        assert.strictEqual(replyComposerModule.getActiveComposer(), composer);

        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.ok(input);
        assert.strictEqual(input.isFocused, true, 'Textarea should be focused');
    });

    test('6. Replying to root sets activeCommentId = rootId and activeRootId = rootId', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const state = replyComposerModule.getState();
        assert.strictEqual(state.activeCommentId, 'root-1');
        assert.strictEqual(state.activeRootId, 'root-1');
    });

    test('7. Authenticated user clicking "Phản hồi" on nested reply mounts composer under reply with commentId=reply.id and rootId=root.id', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.reply1ReplyBtn.click();

        const composer = fixture.reply1.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composer, 'Composer must be mounted in reply comment');

        const state = replyComposerModule.getState();
        assert.strictEqual(state.activeCommentId, 'rep-1');
        assert.strictEqual(state.activeRootId, 'root-1');
    });

    test('8. Composer header displays "Phản hồi @AuthorName" with target author name', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.reply1ReplyBtn.click();

        const composer = fixture.reply1.querySelector('.novel-chapter-comment-reply-composer');
        const targetLabel = composer.querySelector('.novel-chapter-comment-reply-target-label');
        assert.ok(targetLabel);
        assert.strictEqual(targetLabel.textContent, 'Phản hồi @Người Phản Hồi 1');
    });

    test('9. Single-instance: opening composer on Comment B closes composer on Comment A', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.reply1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.ok(fixture.reply1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.root2ReplyBtn.click();
        assert.strictEqual(fixture.reply1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.ok(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'));
    });

    test('10. Toggle: clicking "Phản hồi" again on same comment closes composer and restores focus to trigger button', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.root1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(fixture.root1ReplyBtn.isFocused, true);
    });

    test('11. Clicking close button (✕) closes composer and restores focus to trigger button', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const closeBtn = composer.querySelector('.novel-chapter-comment-reply-close-btn');

        closeBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(fixture.root1ReplyBtn.isFocused, true);
    });

    test('12. Clicking "Hủy" closes composer and restores focus to trigger button', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');

        cancelBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(fixture.root1ReplyBtn.isFocused, true);
    });

    test('13. Validation: submitting empty or whitespace-only reply shows error without invoking mutation client', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let createReplyCalled = false;
        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => { createReplyCalled = true; return Promise.resolve({ ok: true }); }
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');

        input.value = '   ';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(createReplyCalled, false, 'createReply must not be called for empty body');
        assert.strictEqual(status.textContent, 'Vui lòng nhập nội dung phản hồi.');
        assert.ok(status.classList.contains('is-error'));
    });

    test('14. Single-flight submission: while submission in-flight, controls disabled, status indicates sending, double submit no-ops', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveMutation;
        let callCount = 0;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => {
                    callCount++;
                    return new Promise(r => { resolveMutation = r; });
                }
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');
        const submitBtn = composer.querySelector('.novel-chapter-comment-reply-submit-btn');
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');

        input.value = 'Nội dung phản hồi hợp lệ';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(callCount, 1);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.strictEqual(cancelBtn.disabled, false);
        assert.strictEqual(status.textContent, 'Đang gửi phản hồi...');

        // Second submit while in-flight
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        assert.strictEqual(callCount, 1, 'Double submit must be ignored');

        // Resolve
        resolveMutation({ ok: true, status: 201, commentId: 'rep-created' });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);
    });

    test('15. Successful submission (201 Created): invokes createReply, closes composer, and calls refreshRootThread', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let mutationInput = null;
        let refreshedRootId = null;
        let refreshOptions = null;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: (input) => {
                    mutationInput = input;
                    return Promise.resolve({ ok: true, status: 201, commentId: 'rep-created-123' });
                }
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshedRootId = rootId;
                    refreshOptions = opts;
                    return Promise.resolve({});
                }
            }
        });

        // Click reply on nested reply
        fixture.reply1ReplyBtn.click();
        const composer = fixture.reply1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Phản hồi cho reply 1';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        // Mutation client received correct params
        assert.strictEqual(mutationInput.chapterId, 'chap-reply-1');
        assert.strictEqual(mutationInput.parentCommentId, 'rep-1');
        assert.strictEqual(mutationInput.body, 'Phản hồi cho reply 1');

        // Composer is closed
        assert.strictEqual(fixture.reply1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // refreshRootThread was called with rootId and revealCommentId
        assert.strictEqual(refreshedRootId, 'root-1');
        assert.strictEqual(refreshOptions.revealCommentId, 'rep-created-123');
    });

    test('16. Refresh failure after 201 Created: does not restore draft or re-POST, sets #novelChapterCommentsStatus text', async () => {
        const fixture = createReplyFixture({ authenticated: true });

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => Promise.resolve({ ok: true, status: 201, commentId: 'rep-999' })
            },
            commentsModule: {
                refreshRootThread: () => Promise.reject(new Error('Network drop on refresh'))
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Bình luận phản hồi';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        // Composer is still closed
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);

        // Status on feed indicates failure
        assert.strictEqual(fixture.statusEl.textContent, 'Phản hồi đã được gửi, nhưng chưa thể tải lại thảo luận.');
    });

    test('17. Mutation failure preserves textarea draft, re-enables controls, and displays error message', async () => {
        const fixture = createReplyFixture({ authenticated: true });

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => Promise.reject(new Error('Lỗi kết nối máy chủ.'))
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');
        const submitBtn = composer.querySelector('.novel-chapter-comment-reply-submit-btn');

        input.value = 'Nội dung quan trọng không được mất';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        // Controls re-enabled
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        // Draft preserved
        assert.strictEqual(input.value, 'Nội dung quan trọng không được mất');
        // Error displayed
        assert.strictEqual(status.textContent, 'Lỗi kết nối máy chủ.');
        assert.ok(status.classList.contains('is-error'));
        // Focus restored to input
        assert.strictEqual(input.isFocused, true);
    });

    test('18. HTTP 401 failure displays "Vui lòng đăng nhập để phản hồi."', async () => {
        const fixture = createReplyFixture({ authenticated: true });

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => Promise.resolve({ ok: false, status: 401 })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');

        input.value = 'Thử phản hồi khi hết phiên';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(status.textContent, 'Vui lòng đăng nhập để phản hồi.');
    });

    test('19. Opening composer closes active actions menu', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let menuClosed = false;

        replyComposerModule.init(fixture.doc, {
            commentsModule: {
                closeActiveMenu: () => { menuClosed = true; }
            }
        });

        fixture.root1ReplyBtn.click();
        assert.strictEqual(menuClosed, true);
    });

    test('20. kiemlai:chapter-changed cancels in-flight submission and closes active reply composer', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveMutation;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveMutation = r; })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Pending mutation';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        const tokenBefore = replyComposerModule.getState().currentMutationToken;

        // Change chapter event
        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c-next' } });

        assert.ok(replyComposerModule.getState().currentMutationToken > tokenBefore);
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // Later resolving does nothing
        resolveMutation({ ok: true, status: 201, commentId: 'late-id' });
        await new Promise(r => setTimeout(r, 10));
    });

    test('21. kiemlai:chapter-comments-feed-replacing closes active reply composer / guest prompt', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });

        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    test('22. destroy cleans up active composer, detaches listeners, and resets state', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        replyComposerModule.destroy();

        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // Clicking reply button after destroy should not open composer
        fixture.root1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
    });

    test('23. Bottom Reply module completely ignores Reply clicks from #novelBlockDiscussionDrawer', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let createReplyCalled = false;
        const mockMutations = {
            createReply: () => {
                createReplyCalled = true;
                return Promise.resolve({ ok: true });
            }
        };

        replyComposerModule.init(fixture.doc, {
            mutations: mockMutations
        });

        // Add Drawer fixture to document
        const drawer = fixture.doc.createElement('div');
        drawer.id = 'novelBlockDiscussionDrawer';
        drawer.setAttribute('id', 'novelBlockDiscussionDrawer');

        const drawerComment = fixture.doc.createElement('div');
        drawerComment.className = 'novel-comment';
        drawerComment.setAttribute('data-comment-id', 'drawer-comment-1');

        const drawerReplyBtn = fixture.doc.createElement('button');
        drawerReplyBtn.className = 'novel-comment-reply-btn';
        drawerReplyBtn.setAttribute('data-action', 'reply');
        drawerReplyBtn.setAttribute('data-comment-id', 'drawer-comment-1');
        drawerReplyBtn.setAttribute('data-root-id', 'drawer-root-1');
        drawerReplyBtn.textContent = 'Phản hồi';

        drawerComment.appendChild(drawerReplyBtn);
        drawer.appendChild(drawerComment);
        fixture.doc.body.appendChild(drawer);

        // Click Drawer Reply button
        drawerReplyBtn.click();

        // Assert: NO Bottom reply composer, NO Bottom guest prompt, no active Bottom target, no createReply call
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getActiveGuestPrompt(), null);
        assert.strictEqual(drawerComment.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(drawerComment.querySelector('.novel-chapter-comment-reply-guest-prompt'), null);
        assert.strictEqual(replyComposerModule.getState().activeCommentId, null);
        assert.strictEqual(replyComposerModule.getState().activeRootId, null);
        assert.strictEqual(createReplyCalled, false);

        // Then prove a real Bottom Reply button still opens normally
        fixture.root1ReplyBtn.click();
        assert.notStrictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getState().activeCommentId, 'root-1');
        assert.notStrictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
    });

    test('24 (Test A). Submit A pending -> switch to B -> B usable -> resolve A success -> B remains, B draft remains, 0 calls to refreshRootThread for A', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveA;
        const refreshCalls = [];

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveA = r; })
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshCalls.push({ rootId, opts });
                    return Promise.resolve({});
                }
            }
        });

        // Open and submit A
        fixture.root1ReplyBtn.click();
        const composerA = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Draft A';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Switch to B (root-2)
        fixture.root2ReplyBtn.click();

        // Composer A removed, Composer B mounted
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        const composerB = fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composerB, 'Composer B should be mounted');
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(inputB.disabled, false);
        assert.strictEqual(inputB.value, '');

        // User writes draft in B
        inputB.value = 'Draft B content';

        // Now resolve A
        resolveA({ ok: true, status: 201, commentId: 'rep-created-a' });
        await new Promise(r => setTimeout(r, 15));

        // Composer B must remain mounted with draft intact
        assert.strictEqual(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'), composerB);
        assert.strictEqual(inputB.value, 'Draft B content');
        assert.strictEqual(inputB.disabled, false);

        // 0 refresh calls for A
        assert.strictEqual(refreshCalls.length, 0);
    });

    test('25 (Test B). Submit A pending -> switch to B -> submit B -> resolve A -> B lifecycle remains authoritative', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveA;
        let resolveB;
        let submitCount = 0;
        const refreshCalls = [];

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => {
                    submitCount++;
                    if (submitCount === 1) {
                        return new Promise(r => { resolveA = r; });
                    }
                    return new Promise(r => { resolveB = r; });
                }
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshCalls.push({ rootId, opts });
                    return Promise.resolve({});
                }
            }
        });

        // Submit A
        fixture.root1ReplyBtn.click();
        const composerA = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Draft A';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(submitCount, 1);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Switch to B
        fixture.root2ReplyBtn.click();
        const composerB = fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        inputB.value = 'Draft B';

        // Submit B
        composerB.dispatchEvent({ type: 'submit', preventDefault() {} });
        assert.strictEqual(submitCount, 2);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Resolve A -> must NOT affect B
        resolveA({ ok: true, status: 201, commentId: 'rep-a' });
        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'), composerB);
        assert.strictEqual(refreshCalls.length, 0);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Resolve B -> B authoritative completion
        resolveB({ ok: true, status: 201, commentId: 'rep-b' });
        await new Promise(r => setTimeout(r, 15));

        // Composer B closed
        assert.strictEqual(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);
        // Authoritative refresh for B's root
        assert.strictEqual(refreshCalls.length, 1);
        assert.strictEqual(refreshCalls[0].rootId, 'root-2');
        assert.strictEqual(refreshCalls[0].opts.revealCommentId, 'rep-b');
    });

    test('26 (Test C). Submit A pending -> click Hủy -> resolve A success -> composer stays closed, 0 refresh', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveA;
        const refreshCalls = [];

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveA = r; })
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshCalls.push({ rootId, opts });
                    return Promise.resolve({});
                }
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');

        input.value = 'Draft A to cancel';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Click Hủy during in-flight flight
        cancelBtn.click();

        // Composer removed from DOM immediately
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);

        // Resolve A
        resolveA({ ok: true, status: 201, commentId: 'rep-a-cancelled' });
        await new Promise(r => setTimeout(r, 15));

        // Stays closed, 0 refresh
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(refreshCalls.length, 0);
    });

    test('27 (Test D). Submit A pending -> click ✕ -> resolve A failure -> no stale error/UI', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let rejectA;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise((_, rej) => { rejectA = rej; })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const closeBtn = composer.querySelector('.novel-chapter-comment-reply-close-btn');

        input.value = 'Draft A close test';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Click ✕ during in-flight
        closeBtn.click();

        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);

        // Reject A
        rejectA(new Error('Internal 500 error'));
        await new Promise(r => setTimeout(r, 15));

        // No resurrected composer, no errors
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(fixture.statusEl.textContent, '');
    });

    test('28 (Test E). After cancelling pending A -> open B -> B can submit (isSubmitting does not block B)', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveB;
        let bSubmitted = false;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: (payload) => {
                    if (payload.parentCommentId === 'root-2') {
                        bSubmitted = true;
                        return new Promise(r => { resolveB = r; });
                    }
                    return new Promise(() => {}); // A never resolves
                }
            }
        });

        // Open and submit A
        fixture.root1ReplyBtn.click();
        const composerA = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        const cancelBtnA = composerA.querySelector('.novel-chapter-comment-reply-cancel-btn');

        inputA.value = 'Draft A';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Cancel A via Hủy
        cancelBtnA.click();
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);

        // Open B
        fixture.root2ReplyBtn.click();
        const composerB = fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composerB);
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        inputB.value = 'Draft B can submit';

        // Submit B
        composerB.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(bSubmitted, true, 'B must be able to submit after A cancellation');
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        resolveB({ ok: true, status: 201, commentId: 'b-ok' });
        await new Promise(r => setTimeout(r, 15));
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);
    });

    test('29 (Test F). Destroy -> re-init ABA safety remains monotonic (tokens never reset to 0)', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        const token1 = replyComposerModule.getState().currentMutationToken;

        replyComposerModule.destroy();
        const token2 = replyComposerModule.getState().currentMutationToken;
        assert.ok(token2 > token1, `destroy must advance token monotonically: ${token2} > ${token1}`);

        replyComposerModule.init(fixture.doc);
        const token3 = replyComposerModule.getState().currentMutationToken;
        assert.ok(token3 >= token2, `re-init must not reset token to zero: ${token3} >= ${token2}`);
        assert.notStrictEqual(token3, 0, 'Token must never be reset to 0');
    });

    test('30. MS-05E5H2F3B: opening Reply closes active Edit composer', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let closeEditCalled = false;
        const mockEdit = {
            closeEditComposer: () => { closeEditCalled = true; }
        };

        replyComposerModule.init(fixture.doc, {
            editComposerModule: mockEdit
        });

        fixture.root1ReplyBtn.click();
        assert.strictEqual(closeEditCalled, true, 'Opening Reply must close active Edit composer');
    });

    test('31. MS-05E5H2F3B: opening Reply closes active Delete confirmation', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let closeDeleteCalled = false;
        const mockDel = {
            closeDeleteConfirmation: () => { closeDeleteCalled = true; }
        };

        replyComposerModule.init(fixture.doc, {
            deleteModule: mockDel
        });

        fixture.root1ReplyBtn.click();
        assert.strictEqual(closeDeleteCalled, true, 'Opening Reply must close active Delete confirmation');
    });
});

describe('MS-05E5H2F4B1 — Bottom Reply Cross-Surface Synchronization', () => {

    afterEach(() => {
        replyComposerModule.destroy();
    });

    function setupSyncFixture() {
        const fixture = createReplyFixture({ authenticated: true, chapterId: 'chap-reply-sync' });
        const chapterBody = fixture.doc.createElement('div');
        chapterBody.className = 'novel-reader-chapter-body';
        fixture.doc.body.appendChild(chapterBody);

        let indicatorRefreshCalls = [];
        const mockIndicators = {
            refreshChapterIndicators: async (body) => {
                indicatorRefreshCalls.push(body);
            }
        };

        let drawerRefreshCalls = 0;
        let drawerOpen = true;
        let drawerContext = { chapterId: 'chap-reply-sync', blockKey: 'blk-1' };
        const mockDrawer = {
            isDrawerOpen: () => drawerOpen,
            getActiveContext: () => drawerContext,
            refreshActiveDiscussion: async () => {
                drawerRefreshCalls++;
            }
        };

        let refreshedRootId = null;
        const mockComments = {
            getState: () => ({
                items: [
                    {
                        id: 'root-1',
                        rootCommentId: 'root-1',
                        anchorStatus: 'CURRENT',
                        blockKey: 'blk-1'
                    },
                    {
                        id: 'root-2',
                        rootCommentId: 'root-2',
                        anchorStatus: 'UNANCHORED',
                        blockKey: null
                    }
                ]
            }),
            refreshRootThread: async (rootId) => {
                refreshedRootId = rootId;
            }
        };

        return {
            fixture,
            chapterBody,
            mockIndicators,
            getIndicatorRefreshCalls: () => indicatorRefreshCalls,
            mockDrawer,
            getDrawerRefreshCalls: () => drawerRefreshCalls,
            setDrawerOpen: (val) => { drawerOpen = val; },
            setDrawerContext: (ctx) => { drawerContext = ctx; },
            mockComments,
            getRefreshedRootId: () => refreshedRootId
        };
    }

    test('Case A: Anchored reply success triggers indicator refresh and same-block open Drawer refresh', async () => {
        const env = setupSyncFixture();
        let createReplyCalls = 0;

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => {
                    createReplyCalls++;
                    return { ok: true, status: 201, commentId: 'rep-new-1' };
                }
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(createReplyCalls, 1);
        assert.strictEqual(env.getRefreshedRootId(), 'root-1', 'Local root thread must refresh');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1, 'Indicator refresh must be called exactly once');
        assert.strictEqual(env.getIndicatorRefreshCalls()[0], env.chapterBody, 'Indicator refresh must receive chapterBody');
        assert.strictEqual(env.getDrawerRefreshCalls(), 1, 'Same-block open Drawer must refresh exactly once');
    });

    test('Case B: Anchored reply success with different-block Drawer open skips Drawer refresh', async () => {
        const env = setupSyncFixture();
        env.setDrawerContext({ chapterId: 'chap-reply-sync', blockKey: 'different-blk' });

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-1' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-1');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1, 'Indicator refresh must still run');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Drawer refresh must be skipped for different blockKey');
    });

    test('Case C: Anchored reply success with Drawer closed skips Drawer refresh', async () => {
        const env = setupSyncFixture();
        env.setDrawerOpen(false);

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-1' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-1');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1);
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Closed drawer must not be refreshed');
    });

    test('Case D: UNANCHORED root reply success performs local refresh only (zero indicator, zero Drawer)', async () => {
        const env = setupSyncFixture();

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-2' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root2ReplyBtn.click();
        const composer = env.fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to unanchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-2', 'Local root thread must refresh');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 0, 'Indicator must not refresh for UNANCHORED');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Drawer must not refresh for UNANCHORED');
    });

    test('Case E: STALE root reply success performs local refresh only (zero indicator, zero Drawer)', async () => {
        const env = setupSyncFixture();
        env.mockComments.getState = () => ({
            items: [
                {
                    id: 'root-1',
                    rootCommentId: 'root-1',
                    anchorStatus: 'STALE',
                    blockKey: 'blk-stale'
                }
            ]
        });

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-stale' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to stale root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-1');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 0, 'Indicator must not refresh for STALE');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Drawer must not refresh for STALE');
    });

    test('Case F: Secondary indicator rejection does not retry mutation or fail local refresh', async () => {
        const env = setupSyncFixture();
        let createReplyCalls = 0;
        env.mockIndicators.refreshChapterIndicators = () => Promise.reject(new Error('Network error on indicators'));

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => {
                    createReplyCalls++;
                    return { ok: true, status: 201, commentId: 'rep-new-1' };
                }
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(createReplyCalls, 1, 'Mutation must not be retried');
        assert.strictEqual(env.getRefreshedRootId(), 'root-1', 'Local refresh must still succeed');
        assert.strictEqual(env.getDrawerRefreshCalls(), 1, 'Drawer refresh still runs');
    });

    test('Case G: Secondary Drawer rejection does not retry mutation or fail local refresh', async () => {
        const env = setupSyncFixture();
        let createReplyCalls = 0;
        env.mockDrawer.refreshActiveDiscussion = () => Promise.reject(new Error('Drawer refresh error'));

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => {
                    createReplyCalls++;
                    return { ok: true, status: 201, commentId: 'rep-new-1' };
                }
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(createReplyCalls, 1, 'Mutation must not be retried');
        assert.strictEqual(env.getRefreshedRootId(), 'root-1', 'Local refresh must still succeed');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1, 'Indicator refresh still runs');
    });

    test('Case H: Stale A mutation completion performs zero cross-surface synchronization', async () => {
        const env = setupSyncFixture();
        let resolveReplyA;

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveReplyA = r; })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        // Start reply on Root 1 (anchored)
        env.fixture.root1ReplyBtn.click();
        const composerA = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Reply A in-flight';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Cancel A before it resolves
        const cancelBtnA = composerA.querySelector('.novel-chapter-comment-reply-cancel-btn');
        cancelBtnA.click();

        // Resolve A now
        resolveReplyA({ ok: true, status: 201, commentId: 'rep-stale-a' });
        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getIndicatorRefreshCalls().length, 0, 'Zero indicator refresh for stale mutation');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Zero Drawer refresh for stale mutation');
        assert.strictEqual(env.getRefreshedRootId(), null, 'Zero local refresh for stale mutation');
    });
});

// ============================================================================
// Test Suite: UX-DRAFT-01D2A Bottom Reply Draft Persistence & Active Inline Restore
// ============================================================================

describe('UX-DRAFT-01D2A — Bottom Reply Draft Persistence & Active Inline Restore', () => {

    afterEach(() => {
        replyComposerModule.destroy();
    });

    // 1. Manual empty start: opening composer when no draft saved initializes textarea with empty string
    test('1. Manual empty start: opening composer when no draft saved initializes textarea with empty string', () => {
        const { store } = createMockDraftStore();
        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();

        const composer = replyComposerModule.getActiveComposer();
        assert.ok(composer, 'Composer must be mounted');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(input.value, '');
    });

    // 2. Saved draft restore: opening composer restores draft from store if present
    test('2. Saved draft restore: opening composer restores draft from store if present', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        store.save(draftKey, 'Unsent reply draft text');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();

        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(input.value, 'Unsent reply draft text');
    });

    // 3. 400ms typing persistence: typing saves draft to store after ~400ms debounce
    test('3. 400ms typing persistence: typing saves draft to store after ~400ms debounce', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Typing reply in progress...';
        input.dispatchEvent({ type: 'input' });

        // Immediate check: debounce not expired yet
        assert.strictEqual(store.load(draftKey), null);

        // Wait for debounce (400ms + buffer)
        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(store.load(draftKey), 'Typing reply in progress...');
    });

    // 4. Whitespace removal: saving empty/whitespace-only input removes draft from store
    test('4. Whitespace removal: saving empty or whitespace-only input removes draft from store', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        store.save(draftKey, 'Previous draft content');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(input.value, 'Previous draft content');

        // Clear to whitespace
        input.value = '   \n  \t  ';
        input.dispatchEvent({ type: 'input' });

        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(store.load(draftKey), null, 'Whitespace-only input must remove draft');
    });

    // 5. Flush event before debounce: kiemlai:novel-comment-drafts-flush synchronously saves un-debounced typing before timer fires
    test('5. Flush event before debounce: kiemlai:novel-comment-drafts-flush synchronously saves un-debounced typing', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Fast draft before reload';
        input.dispatchEvent({ type: 'input' });

        // Synchronous flush without waiting 400ms
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        assert.strictEqual(store.load(draftKey), 'Fast draft before reload');
    });

    // 6. Active marker schema: opening composer writes marker { type: "reply", targetCommentId: "..." }
    test('6. Active marker schema: opening composer writes marker with type reply and targetCommentId', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.reply1ReplyBtn.click();

        const rawMarker = store.load(markerKey);
        assert.ok(rawMarker, 'Marker must be stored');
        const parsed = JSON.parse(rawMarker);
        assert.deepStrictEqual(parsed, { type: 'reply', targetCommentId: 'rep-1' });
    });

    // 7. Hủy button discard: clicking "Hủy" removes saved draft and removes marker
    test('7. Hủy button discard: clicking "Hủy" removes saved draft and removes marker', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(draftKey, 'Draft to discard');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        assert.ok(store.load(markerKey));

        const composer = replyComposerModule.getActiveComposer();
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');
        cancelBtn.click();

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(draftKey), null, 'Draft must be removed on Hủy');
        assert.strictEqual(store.load(markerKey), null, 'Marker must be removed on Hủy');
    });

    // 8. ✕ button discard: clicking "✕" removes saved draft and removes marker
    test('8. ✕ button discard: clicking close button (✕) removes saved draft and removes marker', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(draftKey, 'Draft to discard');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        assert.ok(store.load(markerKey));

        const composer = replyComposerModule.getActiveComposer();
        const closeBtn = composer.querySelector('.novel-chapter-comment-reply-close-btn');
        closeBtn.click();

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(draftKey), null, 'Draft must be removed on ✕');
        assert.strictEqual(store.load(markerKey), null, 'Marker must be removed on ✕');
    });

    // 9. Toggle close discard: clicking "Phản hồi" trigger again on same comment removes saved draft and removes marker
    test('9. Toggle close discard: clicking "Phản hồi" trigger again on same comment removes draft and marker', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(draftKey, 'Draft to discard on toggle');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        // Open composer
        fixture.root1ReplyBtn.click();
        assert.ok(replyComposerModule.getActiveComposer());
        assert.ok(store.load(markerKey));

        // Toggle close via same button
        fixture.root1ReplyBtn.click();

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(draftKey), null, 'Draft must be removed on toggle close');
        assert.strictEqual(store.load(markerKey), null, 'Marker must be removed on toggle close');
    });

    // 10. Switching A -> B preserves A: opening composer on B closes A without discarding A draft, and updates active marker to B
    test('10. Switching A -> B preserves A: opening composer on B closes A without discarding A draft, and updates active marker to B', () => {
        const { store } = createMockDraftStore();
        const draftKeyA = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const draftKeyB = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-2');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        // Open on A and type
        fixture.root1ReplyBtn.click();
        const composerA = replyComposerModule.getActiveComposer();
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Preserved Draft for A';

        // Switch to B directly
        fixture.root2ReplyBtn.click();

        // Composer on B is now open
        const composerB = replyComposerModule.getActiveComposer();
        assert.ok(composerB);
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(inputB.value, '');

        // Draft A was flushed and preserved
        assert.strictEqual(store.load(draftKeyA), 'Preserved Draft for A');
        // Marker now points to B
        const rawMarker = store.load(markerKey);
        assert.deepStrictEqual(JSON.parse(rawMarker), { type: 'reply', targetCommentId: 'root-2' });
    });

    // 11. Active marker persistence: marker key is canonical active inline marker key
    test('11. Active marker persistence: uses canonical kiemlai:draft:novel-comment:{chapterId}:active-inline key', () => {
        const { store } = createMockDraftStore();
        const expectedKey = 'kiemlai:draft:novel-comment:chap-reply-1:active-inline';
        assert.strictEqual(draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1'), expectedKey);

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        assert.ok(store.load(expectedKey));
    });

    // 12. Feed-replacing preserves active draft: kiemlai:chapter-comments-feed-replacing preserves draft and marker
    test('12. Feed-replacing preserves active draft: kiemlai:chapter-comments-feed-replacing preserves draft and marker', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Draft during filter change';

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(draftKey), 'Draft during filter change');
        assert.ok(store.load(markerKey), 'Marker must be preserved during feed-replacing');
    });

    // 13. Chapter A -> B preserves A draft under A key even if DOM changed to B first
    test('13. Chapter A -> B preserves A draft under A key even if DOM changed to B first', () => {
        const { store } = createMockDraftStore();
        const draftKeyA = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const draftKeyB = draftsAdapter.getChapterReplyDraftKey('chap-reply-2', 'root-1');

        const fixture = createReplyFixture({ chapterId: 'chap-reply-1' });
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Draft belonging to Chapter A';

        // Another listener prematurely changed DOM data-chapter-id to Chapter B
        fixture.section.setAttribute('data-chapter-id', 'chap-reply-2');

        // Chapter changed fires
        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });

        // Must be saved under Chapter A, never Chapter B
        assert.strictEqual(store.load(draftKeyA), 'Draft belonging to Chapter A');
        assert.strictEqual(store.load(draftKeyB), null, 'Draft must NOT leak to Chapter B');
    });

    // 14. EVENT_FEED_RENDERED restores marked Reply
    test('14. EVENT_FEED_RENDERED restores marked Reply and fills saved draft', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'rep-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        store.save(draftKey, 'Restored reply content for rep-1');
        store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: 'rep-1' }));

        const fixture = createReplyFixture();
        // Init with unmounted state
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        // Dispatch feed rendered event
        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        const composer = replyComposerModule.getActiveComposer();
        assert.ok(composer, 'Composer must be automatically restored');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(input.value, 'Restored reply content for rep-1');
        assert.strictEqual(replyComposerModule.getState().activeCommentId, 'rep-1');
        assert.strictEqual(replyComposerModule.getState().activeRootId, 'root-1');
    });

    // 15. Malformed marker safe removal: invalid marker JSON / non-reply / missing target
    test('15. Malformed marker safe removal: invalid marker JSON is safely removed from store', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(markerKey, '{bad-json-broken');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(markerKey), null, 'Corrupted marker JSON must be removed');
    });

    test('15b. Non-reply marker: safely ignored without removing (e.g. edit marker)', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        const editMarkerJson = JSON.stringify({ type: 'edit', commentId: 'root-1' });
        store.save(markerKey, editMarkerJson);

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(markerKey), editMarkerJson, 'Edit marker must NOT be removed by reply module');
    });

    test('15c. Missing targetCommentId in marker: safely removed from store', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: '' }));

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(markerKey), null, 'Marker with blank target must be removed');
    });

    // 16. Missing target no fabrication: marker targeting non-existent comment does not restore composer or create fake DOM
    test('16. Missing target no fabrication: marker targeting non-existent comment does not restore composer or create fake DOM', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: 'ghost-comment-999' }));

        const fixture = createReplyFixture();
        const initialChildCount = fixture.section.childNodes.length;
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(fixture.section.childNodes.length, initialChildCount, 'No DOM elements fabricated');
    });

    // 17. Authoritative rootId derived from live thread DOM: marker's rootCommentId is ignored in favor of live DOM parent root ID
    test('17. Authoritative rootId derived from live thread DOM: stale root in marker ignored in favor of live parent root ID', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        // Marker has WRONG_ROOT_999, but in DOM rep-1 is enclosed by thread data-root-id="root-1"
        store.save(markerKey, JSON.stringify({
            type: 'reply',
            targetCommentId: 'rep-1',
            rootCommentId: 'WRONG_ROOT_999'
        }));

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        assert.ok(replyComposerModule.getActiveComposer());
        assert.strictEqual(replyComposerModule.getState().activeCommentId, 'rep-1');
        assert.strictEqual(replyComposerModule.getState().activeRootId, 'root-1', 'Must resolve authoritative root-1 from DOM');
    });

    // 18. Repeated rendered events idempotent: dispatching multiple EVENT_FEED_RENDERED does not mount duplicate composers
    test('18. Repeated rendered events idempotent: dispatching multiple EVENT_FEED_RENDERED does not mount duplicate composers', () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: 'root-1' }));

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-rendered', detail: { chapterId: 'chap-reply-1' } });
        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-rendered', detail: { chapterId: 'chap-reply-1' } });
        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-rendered', detail: { chapterId: 'chap-reply-1' } });

        const composers = fixture.doc.querySelectorAll('.novel-chapter-comment-reply-composer');
        assert.strictEqual(composers.length, 1, 'Only exactly 1 composer mounted');
    });

    // 19 & 20. POST payload trimmed and Pre-submit storage preserves raw text
    test('19 & 20. POST payload trimmed and Pre-submit storage preserves exact raw text', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        let sentPayload = null;
        let draftAtSubmitTime = null;

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async (payload) => {
                    sentPayload = payload;
                    draftAtSubmitTime = store.load(draftKey);
                    return { ok: true, status: 201, commentId: 'new-rep-101' };
                }
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = '   \tRaw text with leading and trailing spaces\n\n  ';

        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        // 19. Trimmed payload sent to server
        assert.strictEqual(sentPayload.body, 'Raw text with leading and trailing spaces');
        // 20. Raw exact text was saved to store prior to dispatch
        assert.strictEqual(draftAtSubmitTime, '   \tRaw text with leading and trailing spaces\n\n  ');
    });

    // 21. 401 failure preserves draft + marker
    test('21. 401 failure preserves draft and marker in store', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => ({ ok: false, status: 401 })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'My reply before session expired';

        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(store.load(draftKey), 'My reply before session expired');
        assert.ok(store.load(markerKey));
        assert.strictEqual(input.value, 'My reply before session expired');
        const statusEl = composer.querySelector('.novel-chapter-comment-reply-composer-status');
        assert.strictEqual(statusEl.textContent, 'Vui lòng đăng nhập để phản hồi.');
    });

    // 22. Successful 201 removes draft + marker
    test('22. Successful 201 removes draft and marker from store', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-201' })
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply content to be successfully posted';

        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(store.load(draftKey), null, 'Draft must be cleared after 201');
        assert.strictEqual(store.load(markerKey), null, 'Marker must be cleared after 201');
        assert.strictEqual(replyComposerModule.getActiveComposer(), null, 'Composer must close after 201');
    });

    // 23. Stale 201 isolation: pending submit A when composer has switched to B does not delete B's draft or B's marker
    test('23. Stale 201 isolation: pending submit A does not delete B draft or B marker when resolved', async () => {
        const { store } = createMockDraftStore();
        const draftKeyA = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const draftKeyB = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-2');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        let resolveReplyA;

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => new Promise(r => { resolveReplyA = r; })
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        // Open A and submit (in-flight)
        fixture.root1ReplyBtn.click();
        const composerA = replyComposerModule.getActiveComposer();
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Reply A in-flight';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        // User switches to B while A is in-flight
        fixture.root2ReplyBtn.click();
        const composerB = replyComposerModule.getActiveComposer();
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        inputB.value = 'Draft for B that should not be touched';
        inputB.dispatchEvent({ type: 'input' });
        // Flush B's draft
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        assert.strictEqual(store.load(draftKeyB), 'Draft for B that should not be touched');
        const markerBeforeA = JSON.parse(store.load(markerKey));
        assert.strictEqual(markerBeforeA.targetCommentId, 'root-2');

        // Resolve A with 201
        resolveReplyA({ ok: true, status: 201, commentId: 'rep-a-done' });
        await new Promise(r => setTimeout(r, 20));

        // Stale A cleaned up A's draft, but did NOT touch B's draft or B's marker
        assert.strictEqual(store.load(draftKeyA), null, 'Draft A cleaned up');
        assert.strictEqual(store.load(draftKeyB), 'Draft for B that should not be touched', 'Draft B preserved');
        const markerAfterA = JSON.parse(store.load(markerKey));
        assert.strictEqual(markerAfterA.targetCommentId, 'root-2', 'Marker B preserved');
    });

    // 24. Same-key ABA newer draft survives
    test('24. Same-key ABA newer draft survives: older submit 201 does not delete newer draft under same key', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        let resolveReply1;

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => new Promise(r => { resolveReply1 = r; })
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        // Submit 1 on root-1
        fixture.root1ReplyBtn.click();
        const composer1 = replyComposerModule.getActiveComposer();
        const input1 = composer1.querySelector('.novel-chapter-comment-reply-composer-input');
        input1.value = 'First draft version';
        input1.dispatchEvent({ type: 'input' });
        composer1.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Close/cancel before resolve
        const cancelBtn1 = composer1.querySelector('.novel-chapter-comment-reply-cancel-btn');
        cancelBtn1.click();

        // Reopen root-1 and type newer draft
        fixture.root1ReplyBtn.click();
        const composer2 = replyComposerModule.getActiveComposer();
        const input2 = composer2.querySelector('.novel-chapter-comment-reply-composer-input');
        input2.value = 'Brand new second draft text';
        input2.dispatchEvent({ type: 'input' });
        // Flush newer draft
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        // Now resolve first submit with 201
        resolveReply1({ ok: true, status: 201, commentId: 'rep-first-done' });
        await new Promise(r => setTimeout(r, 20));

        // Newer draft must NOT be deleted
        assert.strictEqual(store.load(draftKey), 'Brand new second draft text');
    });

    // 25. Resurrection prevention on flush/destroy: after 201 clears draft, flush/destroy doesn't re-save cleared draft
    test('25. Resurrection prevention: after 201 clears draft, subsequent flush does not resurrect draft', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-201-res' })
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Sent draft that should not resurrect';
        input.dispatchEvent({ type: 'input' });

        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(store.load(draftKey), null);

        // Subsequent flush or pagehide
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });
        replyComposerModule.flushActiveDraft();

        assert.strictEqual(store.load(draftKey), null, 'Draft must NOT be resurrected');
    });

    // 26. Genuine newer edit persists normally: if user begins typing again after 201, new text can be saved
    test('26. Genuine newer edit persists normally: typing new draft after 201 saves correctly', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-201-success' })
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        // First submit succeeds
        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'First comment posted';
        input.dispatchEvent({ type: 'input' });
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(store.load(draftKey), null);

        // Open composer again on root-1 and type new comment
        fixture.root1ReplyBtn.click();
        const composer2 = replyComposerModule.getActiveComposer();
        const input2 = composer2.querySelector('.novel-chapter-comment-reply-composer-input');
        input2.value = 'Second separate reply to root';
        input2.dispatchEvent({ type: 'input' });

        // Flush
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        assert.strictEqual(store.load(draftKey), 'Second separate reply to root');
    });

    // 27. Server 500 error preserves draft and marker
    test('27. Server 500 error preserves draft and marker in store', async () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        const fixture = createReplyFixture();
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => ({ ok: false, status: 500 })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = replyComposerModule.getActiveComposer();
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Draft during 500 internal server error';

        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(store.load(draftKey), 'Draft during 500 internal server error');
        assert.ok(store.load(markerKey));
    });

    // 28. Guest prompt unchanged: unauthenticated users get guest prompt, not draft restore/save
    test('28. Guest prompt unchanged: unauthenticated users do not restore or save drafts', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        store.save(draftKey, 'Draft from previous session');
        store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: 'root-1' }));

        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        // Feed rendered should NOT restore composer for guest
        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-rendered', detail: { chapterId: 'chap-reply-1' } });
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // Clicking reply renders guest prompt, not composer
        fixture.root1ReplyBtn.click();
        assert.ok(replyComposerModule.getActiveGuestPrompt());
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    // 29. Drawer reply buttons ignored: Bottom Reply composer ignores clicks from drawer
    test('29. Drawer reply buttons ignored: Reply clicks from drawer are completely ignored', () => {
        const { store } = createMockDraftStore();
        const fixture = createReplyFixture();

        // Add fake drawer outside #novelChapterComments
        const drawer = fixture.doc.createElement('aside');
        drawer.setAttribute('id', 'novelBlockDiscussionDrawer');
        const drawerReplyBtn = fixture.doc.createElement('button');
        drawerReplyBtn.setAttribute('data-action', 'reply');
        drawerReplyBtn.setAttribute('data-comment-id', 'drawer-c1');
        drawerReplyBtn.className = 'novel-comment-reply-btn';
        drawer.appendChild(drawerReplyBtn);
        fixture.doc.body.appendChild(drawer);

        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        drawerReplyBtn.click();

        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    // 31. Active-Edit restore guard (Requirement 4)
    test('31. Active-Edit restore guard: active Bottom Edit prevents Reply restore without closing Edit or touching Reply draft/marker', () => {
        const { store } = createMockDraftStore();
        const draftKey = draftsAdapter.getChapterReplyDraftKey('chap-reply-1', 'root-1');
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');

        store.save(draftKey, 'Preserved Reply draft while Edit is active');
        store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: 'root-1' }));

        const fixture = createReplyFixture();

        // Create a fake mounted Edit composer
        const fakeEditForm = fixture.doc.createElement('form');
        fakeEditForm.className = 'novel-chapter-comment-edit-composer';
        fixture.root1.appendChild(fakeEditForm);

        let closeEditCalled = false;
        let isEditActive = true;
        const mockEditModule = {
            getActiveComposerEl: () => isEditActive ? fakeEditForm : null,
            getActiveEditTarget: () => isEditActive ? { commentId: 'root-1' } : null,
            closeEditComposer: () => { closeEditCalled = true; }
        };

        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            editComposerModule: mockEditModule
        });

        // Dispatch EVENT_FEED_RENDERED while Edit is active
        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        // Reply must NOT restore over active Edit
        assert.strictEqual(replyComposerModule.getActiveComposer(), null, 'Reply composer must not open over active Edit');
        assert.strictEqual(closeEditCalled, false, 'Edit closeEditComposer must NOT be called');
        assert.strictEqual(store.load(draftKey), 'Preserved Reply draft while Edit is active', 'Reply draft remains unchanged');
        assert.deepStrictEqual(JSON.parse(store.load(markerKey)), { type: 'reply', targetCommentId: 'root-1' }, 'Reply marker remains unchanged');

        // Now Edit is closed
        isEditActive = false;
        fakeEditForm.parentNode.removeChild(fakeEditForm);

        // Dispatch EVENT_FEED_RENDERED again
        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        // Now Reply restores exactly once with draft
        const restoredComposer = replyComposerModule.getActiveComposer();
        assert.ok(restoredComposer, 'Reply composer must restore once Edit is closed');
        const input = restoredComposer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(input.value, 'Preserved Reply draft while Edit is active');
    });

    // 32. Live-thread root authority (Requirement 5)
    test('32. Live-thread root authority: enclosing thread[data-root-id] overrides stale button root and marker root', async () => {
        const { store } = createMockDraftStore();
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-1');
        let refreshedRootId = null;

        // Marker deliberately contains PERSISTED-WRONG root
        store.save(markerKey, JSON.stringify({
            type: 'reply',
            targetCommentId: 'rep-1',
            rootCommentId: 'PERSISTED-WRONG'
        }));

        const fixture = createReplyFixture();
        // Set enclosing thread data-root-id="root-LIVE"
        fixture.thread1.setAttribute('data-root-id', 'root-LIVE');

        // Target rep-1 reply button deliberately contains root-WRONG
        fixture.reply1ReplyBtn.setAttribute('data-root-id', 'root-WRONG');

        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-live-new' })
            },
            commentsModule: {
                refreshRootThread: async (rId) => {
                    refreshedRootId = rId;
                }
            }
        });

        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-1' }
        });

        const composer = replyComposerModule.getActiveComposer();
        assert.ok(composer, 'Reply composer must restore');
        assert.strictEqual(replyComposerModule.getState().activeCommentId, 'rep-1');
        assert.strictEqual(replyComposerModule.getState().activeRootId, 'root-LIVE', 'activeRootId must be root-LIVE from live thread');
        assert.strictEqual(fixture.reply1ReplyBtn.getAttribute('data-root-id'), 'root-LIVE', 'Button root must be updated to root-LIVE');

        // Submit and verify later authoritative refresh uses root-LIVE, not root-WRONG
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply content on authoritative root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(refreshedRootId, 'root-LIVE', 'Authoritative refresh context must use root-LIVE');
    });

    // 33. Chapter context validation (Requirement 6)
    test('33. Chapter context validation: feed-rendered with non-matching chapterId is ignored completely', () => {
        const { store } = createMockDraftStore();
        // Chapter A draft and marker
        const draftKeyA = draftsAdapter.getChapterReplyDraftKey('chap-reply-A', 'root-1');
        const markerKeyA = draftsAdapter.getChapterActiveInlineMarkerKey('chap-reply-A');
        store.save(draftKeyA, 'Chapter A draft text');
        store.save(markerKeyA, JSON.stringify({ type: 'reply', targetCommentId: 'root-1' }));

        // Current Bottom DOM is Chapter B
        const fixture = createReplyFixture({ chapterId: 'chap-reply-B' });
        replyComposerModule.init(fixture.doc, { draftStore: store, draftAdapter: draftsAdapter });

        // Dispatch event with Chapter A
        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-A' }
        });

        // Assert no composer opened, no state mutated, Chapter A storage untouched
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(store.load(draftKeyA), 'Chapter A draft text');
        assert.ok(store.load(markerKeyA));

        // Now dispatch for current chapter (chap-reply-B) or with missing detail
        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId: 'chap-reply-B' }
        });
        // Still null because Chapter B has no marker
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    // 34. Real stale-accepted resurrection test (Requirement 7)
    test('34. Stale-accepted resurrection prevention: pending submit A stale-resolved after restore is NOT resurrected on flush/destroy', async () => {
        const { store } = createMockDraftStore();
        const chapterId = 'chap-reply-1';
        const targetId = 'root-1';
        const draftKey = draftsAdapter.getChapterReplyDraftKey(chapterId, targetId);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(chapterId);

        let resolveSubmitA;
        const fixture = createReplyFixture({ chapterId });

        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter,
            mutations: {
                createReply: async () => new Promise(r => { resolveSubmitA = r; })
            },
            commentsModule: { refreshRootThread: async () => {} }
        });

        // 1. Open Reply A
        fixture.root1ReplyBtn.click();
        const composerA = replyComposerModule.getActiveComposer();
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');

        // 2. Type Draft A and dispatch genuine input
        inputA.value = 'Draft A content';
        inputA.dispatchEvent({ type: 'input' });

        // 3. Submit A, keep request pending
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        // 4. Lifecycle-preserve close / destroy + re-init on SAME chapter/target
        replyComposerModule.destroy();

        // Check Draft A was preserved in store
        assert.strictEqual(store.load(draftKey), 'Draft A content');

        // Re-init on same DOM fixture
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter
        });

        // 5. Restore the same draft and marker
        fixture.doc.dispatchEvent({
            type: 'kiemlai:chapter-comments-feed-rendered',
            detail: { chapterId }
        });

        const restoredComposer = replyComposerModule.getActiveComposer();
        assert.ok(restoredComposer, 'Composer restored');
        const restoredInput = restoredComposer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(restoredInput.value, 'Draft A content');

        // 6. Do NOT perform a newer user input!

        // 7. Old A returns HTTP 201 and is now stale
        resolveSubmitA({ ok: true, status: 201, commentId: 'rep-stale-201' });
        await new Promise(r => setTimeout(r, 20));

        // 8. Assert stored Draft A is removed
        assert.strictEqual(store.load(draftKey), null, 'Draft A must be removed from store on stale 201');

        // 9. Dispatch EVENT_FLUSH_DRAFTS
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        // 10. Assert Draft A is STILL absent from store (not resurrected)
        assert.strictEqual(store.load(draftKey), null, 'Draft A must NOT be resurrected on flush');

        // 11. Destroy / preserve lifecycle
        replyComposerModule.destroy();

        // 12. Assert Draft A is STILL absent from store
        assert.strictEqual(store.load(draftKey), null, 'Draft A must NOT be resurrected on destroy');

        // 13. Reopen and perform a genuine new input Draft B
        replyComposerModule.init(fixture.doc, {
            draftStore: store,
            draftAdapter: draftsAdapter
        });
        const composerB = replyComposerModule.getActiveComposer() || (() => {
            fixture.root1ReplyBtn.click();
            return replyComposerModule.getActiveComposer();
        })();
        assert.ok(composerB, 'Composer must be open');
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        inputB.value = 'Brand new second draft text';
        inputB.dispatchEvent({ type: 'input' });

        // Flush Draft B
        fixture.doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        // 14. Assert Draft B persists normally
        assert.strictEqual(store.load(draftKey), 'Brand new second draft text', 'Genuine newer edit must persist normally');
    });
});
