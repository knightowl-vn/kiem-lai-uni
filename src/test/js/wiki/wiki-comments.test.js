const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const wikiCommentsModule = require(path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-comments.js'));
const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));
const CommentReportModal = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-report-modal.js'));
const CommentPresentation = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-presentation.js'));

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
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this.hidden = false;
        this.disabled = false;
        this.value = '';
        this._textContent = '';

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    get nodeType() {
        return 1;
    }

    get firstChild() {
        return this.childNodes.length > 0 ? this.childNodes[0] : null;
    }

    get children() {
        return this.childNodes.filter(c => c.nodeType === 1);
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
        this.childNodes = [];
        this._textContent = String(val);
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes = new Set(String(value).trim().split(/\s+/).filter(Boolean));
        }
        if (name === 'hidden') {
            this.hidden = true;
        }
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
        if (name === 'hidden') {
            this.hidden = false;
        }
    }

    hasAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name);
    }

    appendChild(child) {
        if (!child) return child;
        if (child.parentNode) {
            child.parentNode.removeChild(child);
        }
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx >= 0) {
            this.childNodes.splice(idx, 1);
            child.parentNode = null;
        }
        return child;
    }

    replaceChild(newChild, oldChild) {
        const idx = this.childNodes.indexOf(oldChild);
        if (idx >= 0) {
            if (newChild.parentNode) {
                newChild.parentNode.removeChild(newChild);
            }
            newChild.parentNode = this;
            this.childNodes[idx] = newChild;
            oldChild.parentNode = null;
        }
        return oldChild;
    }

    replaceChildren(...newChildren) {
        for (const c of this.childNodes) {
            c.parentNode = null;
        }
        this.childNodes = [];
        this._textContent = '';
        for (const child of newChildren) {
            if (child) {
                this.appendChild(child);
            }
        }
    }

    addEventListener(event, handler) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(handler);
    }

    removeEventListener(event, handler) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(handler);
        if (idx >= 0) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(event) {
        const evt = typeof event === 'string' ? { type: event } : event;
        if (!evt.target) {
            evt.target = this;
        }
        let cur = this;
        while (cur) {
            const handlers = cur.listeners[evt.type] || [];
            for (const h of handlers) {
                h(evt);
            }
            if (evt.cancelBubble) break;
            cur = cur.parentNode;
        }
    }

    insertBefore(newChild, refChild) {
        if (!newChild) return newChild;
        if (newChild.parentNode) {
            newChild.parentNode.removeChild(newChild);
        }
        if (!refChild) {
            return this.appendChild(newChild);
        }
        const idx = this.childNodes.indexOf(refChild);
        if (idx >= 0) {
            newChild.parentNode = this;
            this.childNodes.splice(idx, 0, newChild);
        } else {
            this.appendChild(newChild);
        }
        return newChild;
    }

    focus(options) {
        this.isFocused = true;
        this.lastFocusOptions = options;
    }

    scrollIntoView(options) {
        this.scrolledIntoView = true;
        this.lastScrollOptions = options;
    }

    querySelector(selector) {
        return this._findFirst(this, selector);
    }

    querySelectorAll(selector) {
        const matches = [];
        this._findAll(this, selector, matches);
        return matches;
    }

    closest(selector) {
        let cur = this;
        while (cur) {
            if (cur._matchesSelector && cur._matchesSelector(selector)) {
                return cur;
            }
            cur = cur.parentNode;
        }
        return null;
    }

    _matchesSelector(selector) {
        if (!selector) return false;
        const compoundMatch = selector.match(/^(\.[\w-]+)(\[.+\])$/);
        if (compoundMatch) {
            return this._matchesSelector(compoundMatch[1]) && this._matchesSelector(compoundMatch[2]);
        }
        if (selector.startsWith('.')) {
            return this.classList.contains(selector.slice(1));
        }
        if (selector.startsWith('#')) {
            return this.getAttribute('id') === selector.slice(1);
        }
        if (selector.startsWith('[') && selector.endsWith(']')) {
            const raw = selector.slice(1, -1);
            if (raw.includes('=')) {
                const [attr, val] = raw.split('=').map(s => s.replace(/["']/g, '').trim());
                if (attr === 'data-action' && val === 'history') {
                    const actual = this.getAttribute('data-action');
                    return actual === 'history' || actual === 'view-revisions';
                }
                return this.getAttribute(attr) === val;
            }
            return this.hasAttribute(raw);
        }
        return this.tagName.toLowerCase() === selector.toLowerCase();
    }

    _findFirst(node, selector) {
        for (const child of node.childNodes) {
            if (child._matchesSelector && child._matchesSelector(selector)) {
                return child;
            }
            const found = this._findFirst(child, selector);
            if (found) return found;
        }
        return null;
    }

    _findAll(node, selector, matches) {
        for (const child of node.childNodes) {
            if (child._matchesSelector && child._matchesSelector(selector)) {
                matches.push(child);
            }
            this._findAll(child, selector, matches);
        }
    }
}

class FakeWindow {
    constructor(href = 'http://localhost/wiki/character/tran-binh-an') {
        this.listeners = {};
        this._updateUrl(href);
        this.history = {
            state: null,
            replaceState: (state, title, url) => {
                this.history.state = state;
                if (url) {
                    this._updateUrl(url);
                }
            }
        };
    }

    _updateUrl(rawUrl) {
        try {
            const parsed = new URL(rawUrl, 'http://localhost');
            this.location = {
                href: parsed.href,
                origin: parsed.origin,
                pathname: parsed.pathname,
                search: parsed.search,
                hash: parsed.hash
            };
        } catch (_) {
            this.location = {
                href: rawUrl,
                origin: 'http://localhost',
                pathname: rawUrl,
                search: '',
                hash: ''
            };
        }
    }

    addEventListener(event, handler) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(handler);
    }

    removeEventListener(event, handler) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(handler);
        if (idx >= 0) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(event) {
        const evt = typeof event === 'string' ? { type: event } : event;
        const handlers = this.listeners[evt.type] || [];
        for (const h of handlers) {
            h(evt);
        }
    }
}

class FakeDocument {
    constructor() {
        this.elementsById = new Map();
        this.root = new FakeElement('html');
        this.head = new FakeElement('head');
        this.body = new FakeElement('body');
        this.root.appendChild(this.head);
        this.root.appendChild(this.body);

        this.defaultView = new FakeWindow();
    }

    createElement(tag, attrs = {}) {
        return new FakeElement(tag, attrs);
    }

    createTextNode(text) {
        const el = new FakeElement('#text');
        el._textContent = String(text);
        return el;
    }

    getElementById(id) {
        if (this.elementsById.has(id)) {
            return this.elementsById.get(id);
        }
        return this.root.querySelector('#' + id);
    }

    registerElement(id, element) {
        element.setAttribute('id', id);
        this.elementsById.set(id, element);
    }

    querySelector(selector) {
        return this.root.querySelector(selector);
    }

    querySelectorAll(selector) {
        return this.root.querySelectorAll(selector);
    }

    addEventListener(event, handler) {
        if (!this.listeners) this.listeners = {};
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(handler);
    }

    removeEventListener(event, handler) {
        if (!this.listeners || !this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(handler);
        if (idx >= 0) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(event) {
        if (!this.listeners) return;
        const evt = typeof event === 'string' ? { type: event } : event;
        const handlers = (this.listeners[evt.type] || []).slice();
        for (const h of handlers) {
            h(evt);
        }
    }
}

// ============================================================================
// Environment Factory Helper
// ============================================================================

const ARTICLE_ID = '11111111-1111-1111-1111-111111111111';
const ROOT_ID = '44444444-4444-4444-4444-444444444444';
const REPLY_ID = '55555555-5555-5555-5555-555555555555';

function createEnvironment({
    authenticated = 'true',
    articleIdVal = ARTICLE_ID,
    csrfTokenVal = 'test-csrf-token-123',
    csrfHeaderVal = 'X-CSRF-TOKEN',
    loginUrlVal = '/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an%23wikiDiscussion'
} = {}) {
    const doc = new FakeDocument();

    const section = doc.createElement('section');
    doc.registerElement(wikiCommentsModule.SECTION_ID, section);
    if (articleIdVal) section.setAttribute('data-article-id', articleIdVal);
    section.setAttribute('data-authenticated', authenticated);
    section.setAttribute('data-csrf-token', csrfTokenVal);
    section.setAttribute('data-csrf-header', csrfHeaderVal);
    section.setAttribute('data-login-url', loginUrlVal);
    doc.body.appendChild(section);

    const countBadge = doc.createElement('span');
    doc.registerElement(wikiCommentsModule.COUNT_BADGE_ID, countBadge);
    section.appendChild(countBadge);

    const composerForm = doc.createElement('form');
    doc.registerElement('wikiRootComposerForm', composerForm);
    section.appendChild(composerForm);

    const composerInput = doc.createElement('textarea');
    doc.registerElement('wikiRootComposerInput', composerInput);
    composerForm.appendChild(composerInput);

    const composerSubmit = doc.createElement('button');
    composerSubmit.setAttribute('type', 'submit');
    doc.registerElement('wikiRootComposerSubmit', composerSubmit);
    composerForm.appendChild(composerSubmit);

    const composerError = doc.createElement('span');
    doc.registerElement('wikiRootComposerError', composerError);
    composerForm.appendChild(composerError);

    const statusEl = doc.createElement('div');
    doc.registerElement(wikiCommentsModule.STATUS_ID, statusEl);
    section.appendChild(statusEl);

    const threadList = doc.createElement('div');
    doc.registerElement(wikiCommentsModule.THREAD_LIST_ID, threadList);
    section.appendChild(threadList);

    const footer = doc.createElement('div');
    doc.registerElement(wikiCommentsModule.FOOTER_ID, footer);
    section.appendChild(footer);

    const loadMoreBtn = doc.createElement('button');
    doc.registerElement(wikiCommentsModule.LOAD_MORE_BTN_ID, loadMoreBtn);
    footer.appendChild(loadMoreBtn);

    return doc;
}

// ============================================================================
// Test Suite
// ============================================================================

describe('WikiArticleComments Module Tests', () => {

    beforeEach(() => {
        wikiCommentsModule.resetState();
    });

    test('1. Initial GET URL: initiates GET to /api/wiki/articles/{articleId}/comments?page=0&size=20', async () => {
        const doc = createEnvironment();
        let requestedUrl = null;
        let requestedMethod = null;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            requestedUrl = url;
            requestedMethod = (opts && opts.method) || 'GET';
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        const initialized = wikiCommentsModule.init(doc);
        assert.strictEqual(initialized, true);
        assert.strictEqual(requestedMethod, 'GET');
        assert.strictEqual(
            requestedUrl,
            '/api/wiki/articles/' + ARTICLE_ID + '/comments?page=0&size=20'
        );
    });

    test('2. Empty state: renders friendly empty message and hides load more button', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl.textContent.includes('Chưa có bình luận nào'));
        assert.ok(statusEl.className.includes('wiki-discussion-status--empty'));

        const footer = doc.getElementById(wikiCommentsModule.FOOTER_ID);
        assert.strictEqual(footer.hidden, true);

        const countBadge = doc.getElementById(wikiCommentsModule.COUNT_BADGE_ID);
        assert.strictEqual(countBadge.textContent, '0 bình luận');
    });

    test('3. Populated root and flat replies render in order without progressive nesting', async () => {
        const doc = createEnvironment();

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                parentCommentId: null,
                body: 'Hello world root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar User', avatarUrl: null },
                canEdit: true,
                canDelete: true
            },
            replies: [
                {
                    id: REPLY_ID,
                    authorUserId: '44444444-4444-4444-4444-444444444444',
                    parentCommentId: ROOT_ID,
                    body: 'First flat reply',
                    tombstone: false,
                    createdAt: '2026-09-19T10:05:00Z',
                    updatedAt: '2026-09-19T10:05:00Z',
                    author: { displayName: 'Reader User', avatarUrl: null },
                    canEdit: false,
                    canDelete: false
                }
            ]
        };

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [sampleThread],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const threadEl = listEl.querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(threadEl, 'Thread article should exist');

        // Root comment check
        const rootCommentEl = threadEl.querySelector('.wiki-comment--root');
        assert.ok(rootCommentEl);
        assert.ok(rootCommentEl.textContent.includes('Scholar User'));
        assert.ok(rootCommentEl.textContent.includes('Hello world root comment'));

        // Replies container check
        const repliesContainer = threadEl.querySelector('.wiki-thread-replies');
        assert.ok(repliesContainer, 'Flat replies container should exist');

        const replyCommentEl = repliesContainer.querySelector('.wiki-comment--reply');
        assert.ok(replyCommentEl, 'Reply comment should exist inside flat container');
        assert.ok(replyCommentEl.textContent.includes('Reader User'));
        assert.ok(replyCommentEl.textContent.includes('First flat reply'));

        // Flat level 1: replyCommentEl must NOT have another nested .wiki-thread-replies inside it!
        assert.strictEqual(replyCommentEl.querySelector('.wiki-thread-replies'), null);
    });

    test('4. Safe rendering: HTML in comment body is rendered as text, never executed', async () => {
        const doc = createEnvironment();

        const maliciousBody = '<script>alert("XSS")</script><img src="x" onerror="steal()"/>';
        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                parentCommentId: null,
                body: maliciousBody,
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: '<b id="evilName">Hacker</b>', avatarUrl: null },
                canEdit: false,
                canDelete: false
            },
            replies: []
        };

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [sampleThread],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        // Ensure no <script> or <b> tags were created as DOM elements
        assert.strictEqual(listEl.querySelector('script'), null);
        assert.strictEqual(listEl.querySelector('#evilName'), null);

        // Body text contains literal characters
        assert.ok(listEl.textContent.includes('<script>alert("XSS")</script>'));
        assert.ok(listEl.textContent.includes('<b id="evilName">Hacker</b>'));
    });

    test('5. Tombstone hides author, avatar, timestamp, and edit/delete actions', async () => {
        const doc = createEnvironment();

        const tombstoneReply = {
            id: REPLY_ID,
            authorUserId: null,
            parentCommentId: ROOT_ID,
            body: null,
            tombstone: true,
            createdAt: '2026-09-19T10:05:00Z',
            updatedAt: '2026-09-19T10:05:00Z',
            author: null,
            canEdit: false,
            canDelete: false
        };

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                parentCommentId: null,
                body: 'Root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar User', avatarUrl: null },
                canEdit: false,
                canDelete: false
            },
            replies: [tombstoneReply]
        };

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [sampleThread],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const replyEl = doc.querySelector('[data-reply-id="' + REPLY_ID + '"]');
        assert.ok(replyEl, 'Tombstone reply should render');
        assert.ok(replyEl.classList.contains('is-tombstone'));
        assert.ok(replyEl.textContent.includes('Bình luận đã bị xóa.'));

        // Author header or action buttons must NOT exist
        assert.strictEqual(replyEl.querySelector('.wiki-comment-header'), null);
        assert.strictEqual(replyEl.querySelector('.wiki-comment-actions'), null);
        assert.strictEqual(replyEl.querySelector('.wiki-comment-avatar'), null);
    });

    test('6. Load More appends next page without duplicate roots', async () => {
        const doc = createEnvironment();

        const SECOND_ROOT_ID = '66666666-6666-6666-6666-666666666666';

        let callCount = 0;
        wikiCommentsModule.setFetchImplementation(async (url) => {
            callCount++;
            if (url.includes('page=0')) {
                return {
                    status: 200,
                    json: async () => ({
                        threads: [
                            {
                                root: {
                                    id: ROOT_ID,
                                    authorUserId: '33333333-3333-3333-3333-333333333333',
                                    body: 'Thread 1',
                                    tombstone: false,
                                    createdAt: '2026-09-19T10:00:00Z',
                                    updatedAt: '2026-09-19T10:00:00Z',
                                    author: { displayName: 'User 1' }
                                },
                                replies: []
                            }
                        ],
                        threadCount: 2,
                        commentCount: 2,
                        page: 0,
                        size: 1,
                        hasNext: true
                    })
                };
            }
            // Page 1 contains second thread AND mistakenly repeats thread 1
            return {
                status: 200,
                json: async () => ({
                    threads: [
                        {
                            root: {
                                id: ROOT_ID, // duplicate
                                authorUserId: '33333333-3333-3333-3333-333333333333',
                                body: 'Thread 1 duplicate',
                                tombstone: false,
                                createdAt: '2026-09-19T10:00:00Z',
                                updatedAt: '2026-09-19T10:00:00Z',
                                author: { displayName: 'User 1' }
                            },
                            replies: []
                        },
                        {
                            root: {
                                id: SECOND_ROOT_ID,
                                authorUserId: '77777777-7777-7777-7777-777777777777',
                                body: 'Thread 2',
                                tombstone: false,
                                createdAt: '2026-09-19T10:01:00Z',
                                updatedAt: '2026-09-19T10:01:00Z',
                                author: { displayName: 'User 2' }
                            },
                            replies: []
                        }
                    ],
                    threadCount: 2,
                    commentCount: 2,
                    page: 1,
                    size: 1,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        assert.strictEqual(listEl.querySelectorAll('.wiki-thread').length, 1);

        const loadMoreBtn = doc.getElementById(wikiCommentsModule.LOAD_MORE_BTN_ID);
        loadMoreBtn.dispatchEvent({ type: 'click' });
        await new Promise(process.nextTick);

        // Should have 2 threads total (duplicate ROOT_ID ignored)
        const threads = listEl.querySelectorAll('.wiki-thread');
        assert.strictEqual(threads.length, 2);
        assert.ok(listEl.querySelector('[data-thread-id="' + SECOND_ROOT_ID + '"]'));
    });

    test('7. Root comment creation: sends POST with CSRF header, clears input and refreshes feed', async () => {
        const doc = createEnvironment({ authenticated: 'true', csrfTokenVal: 'secret-csrf-val' });

        let postCalled = false;
        let postHeaders = null;
        let postBody = null;
        let feedRefreshed = false;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                postCalled = true;
                postHeaders = opts.headers;
                postBody = JSON.parse(opts.body);
                return { status: 201, json: async () => ({ commentId: ROOT_ID }) };
            }
            if (url.includes('/comments?page=0')) {
                feedRefreshed = true;
                return {
                    status: 200,
                    json: async () => ({
                        threads: [],
                        threadCount: 1,
                        commentCount: 1,
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return { status: 200, json: async () => ({}) };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const composerInput = doc.getElementById('wikiRootComposerInput');
        composerInput.value = 'My new root discussion';

        const composerForm = doc.getElementById('wikiRootComposerForm');
        composerForm.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);

        assert.strictEqual(postCalled, true);
        assert.strictEqual(postHeaders['X-CSRF-TOKEN'], 'secret-csrf-val');
        assert.strictEqual(postBody.body, 'My new root discussion');
        assert.strictEqual(composerInput.value, '', 'Input should be cleared on 201');
        assert.strictEqual(feedRefreshed, true, 'Feed should be authoritatively refreshed from page 0');
    });

    test('8. Reply creation: sends POST and triggers targeted thread refresh', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Root',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: false
            },
            replies: []
        };

        let replyPostCalled = false;
        let threadRefreshed = false;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST' && url.includes('/replies')) {
                replyPostCalled = true;
                return { status: 201, json: async () => ({ commentId: REPLY_ID }) };
            }
            if (url.includes('/comments/' + ROOT_ID + '/thread')) {
                threadRefreshed = true;
                return {
                    status: 200,
                    json: async () => ({
                        root: sampleThread.root,
                        replies: [
                            {
                                id: REPLY_ID,
                                parentCommentId: ROOT_ID,
                                body: 'Reply text',
                                tombstone: false,
                                createdAt: '2026-09-19T10:05:00Z',
                                updatedAt: '2026-09-19T10:05:00Z',
                                author: { displayName: 'Responder' }
                            }
                        ]
                    })
                };
            }
            if (url.includes('/comments?page=0')) {
                return {
                    status: 200,
                    json: async () => ({
                        threads: [sampleThread],
                        threadCount: 1,
                        commentCount: 1,
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return { status: 200, json: async () => ({}) };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const replyBtn = listEl.querySelector('[data-action="reply"]');
        assert.ok(replyBtn, 'Reply button should be present');

        // Click reply button to open composer
        replyBtn.dispatchEvent({ type: 'click', target: replyBtn });

        const slot = listEl.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const replyForm = slot.querySelector('form');
        assert.ok(replyForm, 'Reply composer form should open');

        const replyTextarea = slot.querySelector('textarea');
        replyTextarea.value = 'Reply text';

        replyForm.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);

        assert.strictEqual(replyPostCalled, true);
        assert.strictEqual(threadRefreshed, true, 'Affected thread should be authoritatively refreshed');
    });

    test('9. Edit comment: executes PATCH only (no PUT) and refreshes affected thread', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Original body',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: true,
                canDelete: true
            },
            replies: []
        };

        let patchMethod = null;
        let patchUrl = null;
        let patchBody = null;
        let threadRefreshed = false;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && (opts.method === 'PATCH' || opts.method === 'PUT')) {
                patchMethod = opts.method;
                patchUrl = url;
                patchBody = JSON.parse(opts.body);
                return { status: 204 };
            }
            if (url.includes('/comments/' + ROOT_ID + '/thread')) {
                threadRefreshed = true;
                return {
                    status: 200,
                    json: async () => ({
                        root: {
                            ...sampleThread.root,
                            body: 'Updated body',
                            updatedAt: '2026-09-19T10:10:00Z'
                        },
                        replies: []
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const editBtn = listEl.querySelector('[data-action="edit"]');
        assert.ok(editBtn, 'Edit button should exist for canEdit: true');

        editBtn.dispatchEvent({ type: 'click', target: editBtn });

        const editForm = listEl.querySelector('.wiki-inline-edit-form');
        assert.ok(editForm, 'Edit form should replace body view');

        const editTextarea = editForm.querySelector('textarea');
        assert.strictEqual(editTextarea.value, 'Original body');
        editTextarea.value = 'Updated body';

        editForm.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);

        assert.strictEqual(patchMethod, 'PATCH', 'Must strictly use PATCH, not PUT');
        assert.strictEqual(patchUrl, '/api/wiki/articles/' + ARTICLE_ID + '/comments/' + ROOT_ID);
        assert.strictEqual(patchBody.body, 'Updated body');
        assert.strictEqual(threadRefreshed, true);
    });

    test('10. Delete comment: root delete prompts confirmation and reloads entire feed', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'To be deleted',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: true,
                canDelete: true
            },
            replies: []
        };

        let deleteMethod = null;
        let deleteUrl = null;
        let feedRefreshed = false;
        let confirmCalled = false;

        wikiCommentsModule.setConfirmImplementation((msg) => {
            confirmCalled = true;
            return true; // user confirms
        });

        let deletePerformed = false;
        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'DELETE') {
                deleteMethod = opts.method;
                deleteUrl = url;
                deletePerformed = true;
                return { status: 204 };
            }
            if (url.includes('/comments?page=0')) {
                if (deletePerformed) {
                    feedRefreshed = true;
                    return {
                        status: 200,
                        json: async () => ({
                            threads: [],
                            threadCount: 0,
                            commentCount: 0,
                            page: 0,
                            size: 20,
                            hasNext: false
                        })
                    };
                }
                return {
                    status: 200,
                    json: async () => ({
                        threads: [sampleThread],
                        threadCount: 1,
                        commentCount: 1,
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const deleteBtn = listEl.querySelector('[data-action="delete"]');
        assert.ok(deleteBtn);

        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(confirmCalled, true, 'Explicit confirmation required before delete');
        assert.strictEqual(deleteMethod, 'DELETE');
        assert.strictEqual(deleteUrl, '/api/wiki/articles/' + ARTICLE_ID + '/comments/' + ROOT_ID);
        assert.strictEqual(feedRefreshed, true, 'Root delete must reload entire feed');
    });

    test('11. Delete reply: reply delete refreshes affected thread', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Root',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: false
            },
            replies: [
                {
                    id: REPLY_ID,
                    parentCommentId: ROOT_ID,
                    body: 'Reply to be deleted',
                    tombstone: false,
                    createdAt: '2026-09-19T10:05:00Z',
                    updatedAt: '2026-09-19T10:05:00Z',
                    author: { displayName: 'Responder' },
                    canEdit: true,
                    canDelete: true
                }
            ]
        };

        let threadRefreshed = false;
        wikiCommentsModule.setConfirmImplementation(() => true);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'DELETE') {
                return { status: 204 };
            }
            if (url.includes('/comments/' + ROOT_ID + '/thread')) {
                threadRefreshed = true;
                return {
                    status: 200,
                    json: async () => ({
                        root: sampleThread.root,
                        replies: [
                            {
                                id: REPLY_ID,
                                parentCommentId: ROOT_ID,
                                body: null,
                                tombstone: true,
                                createdAt: '2026-09-19T10:05:00Z',
                                updatedAt: '2026-09-19T10:05:00Z',
                                author: null
                            }
                        ]
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const replyEl = listEl.querySelector('[data-reply-id="' + REPLY_ID + '"]');
        const deleteBtn = replyEl.querySelector('[data-action="delete"]');

        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(threadRefreshed, true, 'Reply delete must refresh affected thread');
    });

    test('12. Guest mutation attempt redirects to safe login return URL', async () => {
        const loginDest = '/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an%23wikiDiscussion';
        const doc = createEnvironment({ authenticated: 'false', loginUrlVal: loginDest });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [
                    {
                        root: {
                            id: ROOT_ID,
                            authorUserId: '33333333-3333-3333-3333-333333333333',
                            body: 'Root',
                            tombstone: false,
                            createdAt: '2026-09-19T10:00:00Z',
                            updatedAt: '2026-09-19T10:00:00Z',
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }
                ],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Guest attempts root comment submit
        const composerForm = doc.getElementById('wikiRootComposerForm');
        composerForm.dispatchEvent({ type: 'submit', preventDefault: () => {} });

        assert.strictEqual(
            doc.defaultView.location.href,
            'http://localhost' + loginDest,
            'Must redirect guest to login returnTo URL'
        );
    });

    test('13. Double-submit prevention: submit button disabled while request in flight', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        let resolveFetch = null;
        wikiCommentsModule.setFetchImplementation((url, opts) => {
            if (opts && opts.method === 'POST') {
                return new Promise((resolve) => {
                    resolveFetch = resolve;
                });
            }
            return Promise.resolve({
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            });
        });

        wikiCommentsModule.init(doc);

        const composerInput = doc.getElementById('wikiRootComposerInput');
        composerInput.value = 'In flight comment';

        const composerSubmit = doc.getElementById('wikiRootComposerSubmit');
        const composerForm = doc.getElementById('wikiRootComposerForm');

        composerForm.dispatchEvent({ type: 'submit', preventDefault: () => {} });

        assert.strictEqual(composerSubmit.disabled, true, 'Button must be disabled while in flight');

        // Resolve fetch
        resolveFetch({ status: 201, json: async () => ({ commentId: ROOT_ID }) });
        await new Promise(process.nextTick);

        assert.strictEqual(composerSubmit.disabled, false, 'Button re-enabled after completion');
    });

    test('14. Stale request protection: older async response discarded if superseded', async () => {
        const doc = createEnvironment();

        let resolveFirst = null;
        let resolveSecond = null;

        let callCount = 0;
        wikiCommentsModule.setFetchImplementation((url) => {
            callCount++;
            if (callCount === 1) {
                return new Promise((resolve) => { resolveFirst = resolve; });
            }
            return new Promise((resolve) => { resolveSecond = resolve; });
        });

        wikiCommentsModule.init(doc); // triggers load page 0
        wikiCommentsModule.refreshFeed(doc); // triggers refresh which supersedes page 0 load

        // Now resolve first (stale) load
        resolveFirst({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: ROOT_ID,
                        body: 'STALE DATA',
                        tombstone: false,
                        createdAt: '2026-09-19T10:00:00Z',
                        updatedAt: '2026-09-19T10:00:00Z',
                        author: { displayName: 'Stale' }
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        });
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        assert.strictEqual(listEl.textContent.includes('STALE DATA'), false, 'Stale response must be discarded');

        // Resolve second (latest) load
        resolveSecond({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: ROOT_ID,
                        body: 'FRESH DATA',
                        tombstone: false,
                        createdAt: '2026-09-19T10:00:00Z',
                        updatedAt: '2026-09-19T10:00:00Z',
                        author: { displayName: 'Fresh' }
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        });
        await new Promise(process.nextTick);

        assert.strictEqual(listEl.textContent.includes('FRESH DATA'), true);
    });

    test('15. Network error state: displays retryable error notice', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async () => {
            throw new Error('Network failure');
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl.textContent.includes('Lỗi kết nối'));
        assert.ok(statusEl.className.includes('wiki-discussion-status--error'));
    });

    test('16. Avatar safety through Wiki renderComment delegates to shared CommentPresentation', () => {
        const doc = new FakeDocument();

        // A. unsafe javascript: avatar URL -> no <img> with unsafe src, shared fallback avatar is rendered
        const commentUnsafeJs = {
            id: 'c-avatar-unsafe-1',
            author: { displayName: 'Hacker User', avatarUrl: 'javascript:alert(1)' },
            body: 'Unsafe javascript avatar test'
        };
        const elUnsafeJs = wikiCommentsModule.renderComment(commentUnsafeJs, 'c-avatar-unsafe-1', null, false, doc);
        assert.ok(elUnsafeJs);
        const imgUnsafeJs = elUnsafeJs.querySelector('img');
        assert.strictEqual(imgUnsafeJs, null, 'Must NOT render <img> for unsafe javascript: avatar URL');
        const fallbackJs = elUnsafeJs.querySelector('.kl-comment__avatar--fallback');
        assert.ok(fallbackJs, 'Must render shared fallback avatar for javascript: URL');
        assert.strictEqual(fallbackJs.classList.contains('kl-comment__avatar'), true, 'Must have canonical .kl-comment__avatar');
        assert.strictEqual(fallbackJs.classList.contains('wiki-comment-avatar'), true, 'Must have legacy .wiki-comment-avatar');
        assert.strictEqual(fallbackJs.classList.contains('wiki-comment-avatar--fallback'), true);
        assert.strictEqual(fallbackJs.textContent, 'H', 'Fallback initial must match display name');

        // B. protocol-relative //evil.example/avatar.png -> rejected -> shared fallback
        const commentProtoRel = {
            id: 'c-avatar-proto-2',
            author: { displayName: 'Proto User', avatarUrl: '//evil.example/avatar.png' },
            body: 'Protocol-relative avatar test'
        };
        const elProtoRel = wikiCommentsModule.renderComment(commentProtoRel, 'c-avatar-proto-2', null, false, doc);
        assert.ok(elProtoRel);
        const imgProtoRel = elProtoRel.querySelector('img');
        assert.strictEqual(imgProtoRel, null, 'Must NOT render <img> for protocol-relative URL');
        const fallbackProto = elProtoRel.querySelector('.kl-comment__avatar--fallback');
        assert.ok(fallbackProto, 'Must render shared fallback avatar for protocol-relative URL');
        assert.strictEqual(fallbackProto.classList.contains('kl-comment__avatar'), true);
        assert.strictEqual(fallbackProto.classList.contains('wiki-comment-avatar'), true);
        assert.strictEqual(fallbackProto.textContent, 'P');

        // C. safe https://example.com/avatar.png -> shared avatar img rendered with exact src
        const commentHttps = {
            id: 'c-avatar-https-3',
            author: { displayName: 'Safe User', avatarUrl: 'https://example.com/avatar.png' },
            body: 'Safe https avatar test'
        };
        const elHttps = wikiCommentsModule.renderComment(commentHttps, 'c-avatar-https-3', null, false, doc);
        assert.ok(elHttps);
        const imgHttps = elHttps.querySelector('img');
        assert.ok(imgHttps, 'Must render <img> for safe https URL');
        assert.strictEqual(imgHttps.src, 'https://example.com/avatar.png');
        assert.strictEqual(imgHttps.classList.contains('kl-comment__avatar'), true, 'Must have canonical .kl-comment__avatar');
        assert.strictEqual(imgHttps.classList.contains('wiki-comment-avatar'), true, 'Must have legacy .wiki-comment-avatar');
        assert.strictEqual(imgHttps.alt, 'Safe User');

        // D. safe same-origin-style /images/default-avatar.png -> shared avatar img rendered with exact src
        const commentSameOrigin = {
            id: 'c-avatar-origin-4',
            author: { displayName: 'Origin User', avatarUrl: '/images/default-avatar.png' },
            body: 'Safe same-origin avatar test'
        };
        const elSameOrigin = wikiCommentsModule.renderComment(commentSameOrigin, 'c-avatar-origin-4', null, false, doc);
        assert.ok(elSameOrigin);
        const imgSameOrigin = elSameOrigin.querySelector('img');
        assert.ok(imgSameOrigin, 'Must render <img> for safe same-origin URL');
        assert.strictEqual(imgSameOrigin.src, '/images/default-avatar.png');
        assert.strictEqual(imgSameOrigin.classList.contains('kl-comment__avatar'), true, 'Must have canonical .kl-comment__avatar');
        assert.strictEqual(imgSameOrigin.classList.contains('wiki-comment-avatar'), true, 'Must have legacy .wiki-comment-avatar');
        assert.strictEqual(imgSameOrigin.alt, 'Origin User');
    });

    test('17. Relative parent mention displays @ParentName for nested replies', async () => {
        const doc = createEnvironment();

        const SECOND_REPLY_ID = '88888888-8888-8888-8888-888888888888';
        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Root',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Alice' }
            },
            replies: [
                {
                    id: REPLY_ID,
                    parentCommentId: ROOT_ID,
                    body: 'First reply',
                    tombstone: false,
                    createdAt: '2026-09-19T10:05:00Z',
                    updatedAt: '2026-09-19T10:05:00Z',
                    author: { displayName: 'Bob' }
                },
                {
                    id: SECOND_REPLY_ID,
                    parentCommentId: REPLY_ID, // nested under Bob
                    body: 'Second reply replying to Bob',
                    tombstone: false,
                    createdAt: '2026-09-19T10:10:00Z',
                    updatedAt: '2026-09-19T10:10:00Z',
                    author: { displayName: 'Charlie' }
                }
            ]
        };

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [sampleThread],
                threadCount: 1,
                commentCount: 3,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const secondReplyEl = doc.querySelector('[data-reply-id="' + SECOND_REPLY_ID + '"]');
        assert.ok(secondReplyEl);
        const mention = secondReplyEl.querySelector('.wiki-comment-reply-mention');
        assert.ok(mention, 'Nested reply should display @Bob mention');
        assert.strictEqual(mention.textContent, '@Bob');
    });

    test('18. DELETE network failure: leaves rendered comment intact and displays retryable error', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Persistent root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: true
            },
            replies: []
        };

        let deleteAttempts = 0;
        wikiCommentsModule.setConfirmImplementation(() => true);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'DELETE') {
                deleteAttempts++;
                throw new Error('Network failure during delete');
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const deleteBtn = listEl.querySelector('[data-action="delete"]');
        assert.ok(deleteBtn);

        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(deleteAttempts, 1, 'DELETE must be attempted exactly once');

        // Comment remains rendered (no optimistic removal)
        const rootEl = listEl.querySelector('[data-comment-id="' + ROOT_ID + '"]');
        assert.ok(rootEl, 'Comment must remain rendered after DELETE network failure');

        // User-visible retryable error appears
        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl.textContent.includes('Lỗi kết nối khi xóa bình luận'));
        assert.ok(statusEl.className.includes('wiki-discussion-status--error'));
    });

    test('19. DELETE unexpected 5xx: no optimistic removal and displays retryable error', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Server error root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: true
            },
            replies: []
        };

        let deleteAttempts = 0;
        wikiCommentsModule.setConfirmImplementation(() => true);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'DELETE') {
                deleteAttempts++;
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const deleteBtn = listEl.querySelector('[data-action="delete"]');
        assert.ok(deleteBtn);

        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(deleteAttempts, 1);

        // No optimistic removal
        const rootEl = listEl.querySelector('[data-comment-id="' + ROOT_ID + '"]');
        assert.ok(rootEl, 'Comment must not be optimistically removed on 5xx');

        // Retryable error appears
        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl.textContent.includes('Không thể xóa bình luận. Vui lòng thử lại sau.'));
        assert.ok(statusEl.className.includes('wiki-discussion-status--error'));
    });

    test('20. DELETE 403: neutral authorization error appears and authoritative refresh is attempted', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Forbidden delete root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: true
            },
            replies: []
        };

        let deleteAttempted = false;
        let refreshAttempted = false;
        wikiCommentsModule.setConfirmImplementation(() => true);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'DELETE') {
                deleteAttempted = true;
                return { status: 403 };
            }
            if (url.includes('/comments?page=0')) {
                if (deleteAttempted) {
                    refreshAttempted = true;
                    return {
                        status: 200,
                        json: async () => ({
                            threads: [
                                {
                                    ...sampleThread,
                                    root: { ...sampleThread.root, canDelete: false } // refreshed without delete permission
                                }
                            ],
                            threadCount: 1,
                            commentCount: 1,
                            page: 0,
                            size: 20,
                            hasNext: false
                        })
                    };
                }
                return {
                    status: 200,
                    json: async () => ({
                        threads: [sampleThread],
                        threadCount: 1,
                        commentCount: 1,
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const deleteBtn = listEl.querySelector('[data-action="delete"]');
        assert.ok(deleteBtn);

        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });
        await new Promise(process.nextTick);

        // Error message appears
        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl.textContent.includes('Bạn không còn quyền xóa bình luận này.'));
        assert.ok(statusEl.className.includes('wiki-discussion-status--error'));

        // Authoritative refresh was attempted
        assert.strictEqual(refreshAttempted, true, 'Authoritative refresh must be attempted on 403');
    });

    test('21. Targeted refresh network failure: does not destroy existing thread DOM and displays user-visible error', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        const sampleThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Existing thread content',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: false
            },
            replies: []
        };

        let initialLoadDone = false;
        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (!initialLoadDone) {
                initialLoadDone = true;
                return {
                    status: 200,
                    json: async () => ({
                        threads: [sampleThread],
                        threadCount: 1,
                        commentCount: 1,
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            // Simulated network failure on targeted refresh
            throw new Error('Network failure on refreshThread');
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const initialThreadEl = listEl.querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(initialThreadEl, 'Initial thread should be rendered');

        // Trigger targeted refresh
        await wikiCommentsModule.refreshThread(ROOT_ID, doc);

        // Thread DOM must remain intact (not destroyed)
        const threadAfterFail = listEl.querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(threadAfterFail, 'Thread DOM must remain intact on refresh network failure');
        assert.ok(threadAfterFail.textContent.includes('Existing thread content'));

        // User-visible retryable error status appears
        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl.textContent.includes('Lỗi kết nối khi cập nhật thảo luận'));
        assert.ok(statusEl.className.includes('wiki-discussion-status--error'));
    });

    // ========================================================================
    // MS-05E6D — Revision History UI Tests
    // ========================================================================

    test('22. Unedited comment & tombstone render no revision-history action button', async () => {
        const doc = createEnvironment();

        const uneditedThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Unedited comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: false
            },
            replies: [
                {
                    id: REPLY_ID,
                    parentCommentId: ROOT_ID,
                    authorUserId: '22222222-2222-2222-2222-222222222222',
                    body: 'Tombstone reply',
                    tombstone: true,
                    createdAt: '2026-09-19T10:05:00Z',
                    updatedAt: '2026-09-19T10:10:00Z',
                    edited: true,
                    author: { displayName: 'Ghost' },
                    canEdit: false,
                    canDelete: false
                }
            ]
        };

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [uneditedThread],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const historyBtns = listEl.querySelectorAll('[data-action="history"]');
        assert.strictEqual(historyBtns.length, 0, 'No history button should be rendered for unedited comments or tombstones');
    });

    test('23. Active edited comment renders accessible history action button', async () => {
        const doc = createEnvironment();

        const editedThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Edited root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:30:00Z',
                edited: true,
                author: { displayName: 'Scholar' },
                canEdit: false,
                canDelete: false
            },
            replies: []
        };

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [editedThread],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const historyBtn = listEl.querySelector('[data-action="history"]');
        assert.ok(historyBtn, 'History button must be rendered in actions menu for active edited comment');
        assert.strictEqual(historyBtn.tagName, 'BUTTON', 'Must be a semantic <button>');
        assert.strictEqual(historyBtn.textContent, 'Xem lịch sử chỉnh sửa');
        assert.strictEqual(historyBtn.getAttribute('data-comment-id'), ROOT_ID);

        const editedSpan = listEl.querySelector('.wiki-comment-edited');
        assert.ok(editedSpan, 'Passive edited span must be rendered in header');
        assert.strictEqual(editedSpan.tagName, 'SPAN');
        assert.strictEqual(editedSpan.textContent, 'đã chỉnh sửa');
    });

    test('24. Lazy GET behavior & correct endpoint', async () => {
        const doc = createEnvironment();
        const fetches = [];

        const editedThread = {
            root: {
                id: ROOT_ID,
                authorUserId: '33333333-3333-3333-3333-333333333333',
                body: 'Edited comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:30:00Z',
                edited: true,
                author: { displayName: 'Scholar' }
            },
            replies: []
        };

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            fetches.push({ url, method: (opts && opts.method) || 'GET' });
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            { revisionNumber: 1, body: 'Historical revision 1', createdAt: '2026-09-19T10:00:00Z' }
                        ],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [editedThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Initial feed load must make ZERO revision calls
        const revisionCallsBeforeClick = fetches.filter(f => f.url.includes('/revisions'));
        assert.strictEqual(revisionCallsBeforeClick.length, 0, 'No revisions calls on initial discussion load');

        // Click history button
        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        const historyBtn = listEl.querySelector('[data-action="history"]');
        assert.ok(historyBtn);

        historyBtn.dispatchEvent({ type: 'click', target: historyBtn });
        await new Promise(process.nextTick);

        const revisionCallsAfterClick = fetches.filter(f => f.url.includes('/revisions'));
        assert.strictEqual(revisionCallsAfterClick.length, 1, 'Exactly one revision GET executed on click');
        const expectedUrl = '/api/wiki/articles/' + encodeURIComponent(ARTICLE_ID) +
            '/comments/' + encodeURIComponent(ROOT_ID) +
            '/revisions?page=0&size=20';
        assert.strictEqual(revisionCallsAfterClick[0].url, expectedUrl);
        assert.strictEqual(revisionCallsAfterClick[0].method, 'GET');
    });

    test('25. Safe body rendering: historical body with HTML/script text renders literally', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            {
                                revisionNumber: 1,
                                body: '<script>alert("xss")</script><img src="x" onerror="evil()"><div class="danger">payload</div>',
                                createdAt: '2026-09-19T10:00:00Z'
                            }
                        ],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: ROOT_ID,
                            authorUserId: '33333333-3333-3333-3333-333333333333',
                            body: 'Current body',
                            tombstone: false,
                            createdAt: '2026-09-19T10:00:00Z',
                            updatedAt: '2026-09-19T10:30:00Z',
                            edited: true,
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { listEl } = wikiCommentsModule.getHistoryElements(doc);
        assert.ok(listEl);

        // No executable script elements created
        const scriptEl = listEl.querySelector('script');
        assert.strictEqual(scriptEl, null, 'No script element should be created from revision body');

        const imgEl = listEl.querySelector('img');
        assert.strictEqual(imgEl, null, 'No img element should be created from revision body');

        // Rendered as literal text
        const bodyEl = listEl.querySelector('.wiki-comment-history-entry-body');
        assert.ok(bodyEl);
        assert.ok(bodyEl.textContent.includes('<script>alert("xss")</script>'));
    });

    test('26. Revision order & no current-body duplication', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            { revisionNumber: 2, body: 'Second historical version (B)', createdAt: '2026-09-19T10:15:00Z' },
                            { revisionNumber: 1, body: 'First historical version (A)', createdAt: '2026-09-19T10:00:00Z' }
                        ],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: ROOT_ID,
                            authorUserId: '33333333-3333-3333-3333-333333333333',
                            body: 'Current latest version (C)',
                            tombstone: false,
                            createdAt: '2026-09-19T10:00:00Z',
                            updatedAt: '2026-09-19T10:30:00Z',
                            edited: true,
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        const fakeRelativeTime = {
            formatAbsolute(value) {
                return 'EXACT:' + value;
            },
            formatElement(el) {
                const raw = el.getAttribute('datetime');
                el.textContent = 'RELATIVE:' + raw;
                el.setAttribute('title', 'EXACT:' + raw);
                el.setAttribute('aria-label', 'RELATIVE:' + raw + ', EXACT:' + raw);
                return true;
            }
        };
        wikiCommentsModule.setRelativeTime(fakeRelativeTime);

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { listEl } = wikiCommentsModule.getHistoryElements(doc);
        const entries = listEl.querySelectorAll('.wiki-comment-history-entry');
        assert.strictEqual(entries.length, 2, 'Must display exactly 2 previous revisions');

        // Order preserved: newest-first (2, then 1)
        assert.ok(entries[0].textContent.includes('Phiên bản #2'));
        assert.ok(entries[0].textContent.includes('Second historical version (B)'));
        assert.ok(entries[1].textContent.includes('Phiên bản #1'));
        assert.ok(entries[1].textContent.includes('First historical version (A)'));

        // Current body (C) is NOT duplicated in history list
        assert.strictEqual(listEl.textContent.includes('Current latest version (C)'), false, 'Current body must NOT be in history list');

        // UX-TIME-01B1: Revision timestamp adheres to RelativeTime DOM contract
        const revTime = entries[0].querySelector('.wiki-comment-history-time');
        assert.ok(revTime, 'Revision entry must render .wiki-comment-history-time');
        assert.strictEqual(revTime.hasAttribute('data-relative-time'), true, 'Must have data-relative-time attribute');
        assert.strictEqual(revTime.getAttribute('datetime'), '2026-09-19T10:15:00Z', 'Must have exact canonical datetime attribute from fixture');
        assert.strictEqual(revTime.textContent, 'RELATIVE:2026-09-19T10:15:00Z', 'Must format visible content using injected RelativeTime');
        assert.strictEqual(revTime.getAttribute('title'), 'EXACT:2026-09-19T10:15:00Z', 'Must have exact title attribute');
        assert.strictEqual(revTime.getAttribute('aria-label'), 'RELATIVE:2026-09-19T10:15:00Z, EXACT:2026-09-19T10:15:00Z', 'Must have accessible aria-label');
    });

    test('26b. Invalid revision timestamp robustness: empty time element is omitted', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            { revisionNumber: 1, body: 'Rev with invalid date', createdAt: 'invalid-date' }
                        ],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({ threads: [], threadCount: 0, commentCount: 0, page: 0, size: 20, hasNext: false })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { listEl } = wikiCommentsModule.getHistoryElements(doc);
        const entries = listEl.querySelectorAll('.wiki-comment-history-entry');
        assert.strictEqual(entries.length, 1);
        const revTime = entries[0].querySelector('.wiki-comment-history-time');
        assert.strictEqual(revTime, null, 'Invalid revision timestamp must NOT append an empty <time> element');
    });

    test('27. Load More fetches next page, appends in order, deduplicates, and hides when exhausted', async () => {
        const doc = createEnvironment();
        const requestedPages = [];

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                const match = url.match(/page=(\d+)/);
                const pageNum = match ? parseInt(match[1], 10) : 0;
                requestedPages.push(pageNum);

                if (pageNum === 0) {
                    return {
                        status: 200,
                        json: async () => ({
                            items: [
                                { revisionNumber: 3, body: 'Revision 3', createdAt: '2026-09-19T10:20:00Z' },
                                { revisionNumber: 2, body: 'Revision 2', createdAt: '2026-09-19T10:10:00Z' }
                            ],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                if (pageNum === 1) {
                    return {
                        status: 200,
                        json: async () => ({
                            items: [
                                { revisionNumber: 2, body: 'Revision 2 (duplicate)', createdAt: '2026-09-19T10:10:00Z' },
                                { revisionNumber: 1, body: 'Revision 1', createdAt: '2026-09-19T10:00:00Z' }
                            ],
                            page: 1,
                            size: 20,
                            hasNext: false
                        })
                    };
                }
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: ROOT_ID,
                            authorUserId: '33333333-3333-3333-3333-333333333333',
                            body: 'Active body',
                            tombstone: false,
                            createdAt: '2026-09-19T10:00:00Z',
                            updatedAt: '2026-09-19T10:30:00Z',
                            edited: true,
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { listEl, moreContainer, moreBtn } = wikiCommentsModule.getHistoryElements(doc);
        assert.strictEqual(moreContainer.hidden, false, 'Load More should be visible when hasNext=true');

        // Click Load More
        moreBtn.dispatchEvent({ type: 'click', target: moreBtn });
        await new Promise(process.nextTick);

        assert.deepStrictEqual(requestedPages, [0, 1]);

        // Entries appended and deduplicated: 3, 2, 1 (no duplicate #2)
        const entries = listEl.querySelectorAll('.wiki-comment-history-entry');
        assert.strictEqual(entries.length, 3, 'Must have 3 unique revisions after deduplication');
        assert.ok(entries[0].textContent.includes('Phiên bản #3'));
        assert.ok(entries[1].textContent.includes('Phiên bản #2'));
        assert.ok(entries[2].textContent.includes('Phiên bản #1'));

        // Load more hidden when exhausted
        assert.strictEqual(moreContainer.hidden, true, 'Load more hidden when exhausted');
    });

    test('28. Switching comments race protection: late response from Comment A does not overwrite Comment B', async () => {
        const doc = createEnvironment();

        let resolveA;
        const promiseA = new Promise((resolve) => { resolveA = resolve; });

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/comments/comment-A/revisions')) {
                await promiseA;
                return {
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Revision from Comment A', createdAt: '2026-09-19T10:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            if (url.includes('/comments/comment-B/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Revision from Comment B', createdAt: '2026-09-19T10:05:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open A (delayed)
        wikiCommentsModule.openRevisionHistory('comment-A', null, doc);

        // Immediately open B
        await wikiCommentsModule.openRevisionHistory('comment-B', null, doc);
        await new Promise(process.nextTick);

        const { listEl } = wikiCommentsModule.getHistoryElements(doc);
        assert.ok(listEl.textContent.includes('Revision from Comment B'));

        // Now resolve A late
        resolveA();
        await new Promise(process.nextTick);

        // Modal content must STILL be B, never overwritten by A
        assert.ok(listEl.textContent.includes('Revision from Comment B'));
        assert.strictEqual(listEl.textContent.includes('Revision from Comment A'), false, 'Stale response A must NOT overwrite B');
    });

    test('29. Closing modal invalidates in-flight request & restores focus', async () => {
        const doc = createEnvironment();

        let resolveFetch;
        const pendingPromise = new Promise((resolve) => { resolveFetch = resolve; });

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                await pendingPromise;
                return {
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Late arriving revision', createdAt: '2026-09-19T10:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const fakeTrigger = doc.createElement('button');
        doc.body.appendChild(fakeTrigger);

        // Open history
        wikiCommentsModule.openRevisionHistory('comment-X', fakeTrigger, doc);

        const { modal, closeBtn } = wikiCommentsModule.getHistoryElements(doc);
        assert.strictEqual(modal.hidden, false);

        // Close modal via close button
        closeBtn.dispatchEvent({ type: 'click', target: closeBtn });
        assert.strictEqual(modal.hidden, true, 'Modal should be hidden on close');
        assert.strictEqual(fakeTrigger.isFocused, true, 'Focus should return to trigger');

        // Resolve pending fetch late
        resolveFetch();
        await new Promise(process.nextTick);

        // Modal must remain hidden and not render stale content
        assert.strictEqual(modal.hidden, true, 'Modal remains hidden after late fetch resolution');
    });

    test('30. 404 response displays neutral unavailable message', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return { status: 404 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory('deleted-comment', null, doc);
        await new Promise(process.nextTick);

        const { statusEl } = wikiCommentsModule.getHistoryElements(doc);
        assert.ok(statusEl.textContent.includes('Lịch sử chỉnh sửa không còn khả dụng.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--unavailable'));
    });

    test('31. Network & 5xx failure: displays retryable error notice, leaves discussion feed DOM intact', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                throw new Error('Network error fetching revisions');
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: ROOT_ID,
                            authorUserId: '33333333-3333-3333-3333-333333333333',
                            body: 'Intact discussion thread body',
                            tombstone: false,
                            createdAt: '2026-09-19T10:00:00Z',
                            updatedAt: '2026-09-19T10:30:00Z',
                            edited: true,
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Discussion thread is rendered
        const threadEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID).querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(threadEl);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        // Status shows retryable error
        const { statusEl } = wikiCommentsModule.getHistoryElements(doc);
        assert.ok(statusEl.textContent.includes('Lỗi kết nối khi tải lịch sử chỉnh sửa.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--error'));

        // Discussion feed DOM must still exist and be intact
        const threadStillIntact = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID).querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(threadStillIntact, 'Discussion thread DOM remains intact on history failure');
        assert.ok(threadStillIntact.textContent.includes('Intact discussion thread body'));
    });

    test('32. Read-only transparency & privacy: no mutation actions or author/moderator identity', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            {
                                revisionNumber: 1,
                                body: 'Historical content',
                                createdAt: '2026-09-19T10:00:00Z'
                            }
                        ],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { modal, listEl } = wikiCommentsModule.getHistoryElements(doc);

        // Absolutely no mutation actions
        const buttons = modal.querySelectorAll('button');
        const disallowedActions = ['restore', 'revert', 'undo', 'use', 'edit', 'delete'];
        for (const btn of buttons) {
            const action = btn.getAttribute('data-action') || '';
            for (const disallowed of disallowedActions) {
                assert.strictEqual(action.includes(disallowed), false, 'No mutation action permitted: ' + disallowed);
            }
            const text = (btn.textContent || '').toLowerCase();
            assert.strictEqual(text.includes('khôi phục'), false, 'No restore button text');
            assert.strictEqual(text.includes('hoàn tác'), false, 'No undo button text');
        }

        // Privacy: no user UUIDs or author names in history entries
        assert.strictEqual(listEl.textContent.includes('Scholar'), false, 'Author name must not be exposed');
        assert.strictEqual(listEl.textContent.includes('33333333-3333'), false, 'User UUID must not be exposed');
    });

    test('33. Empty history slice displays clean neutral message', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { statusEl, listEl } = wikiCommentsModule.getHistoryElements(doc);
        assert.ok(statusEl.textContent.includes('Chưa có phiên bản chỉnh sửa trước đó.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--empty'));
        assert.strictEqual(listEl.childNodes.length, 0);
    });

    test('34. Escape key & backdrop click closes history modal', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Rev 1', createdAt: '2026-09-19T10:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { modal } = wikiCommentsModule.getHistoryElements(doc);
        assert.strictEqual(modal.hidden, false);

        // 1. Escape key closes modal
        doc.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27 });
        assert.strictEqual(modal.hidden, true, 'Escape key should close modal');

        // Re-open
        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);
        assert.strictEqual(modal.hidden, false);

        // 2. Backdrop click closes modal
        const backdrop = modal.querySelector('.wiki-comment-history-backdrop');
        assert.ok(backdrop);
        backdrop.dispatchEvent({ type: 'click', target: backdrop });
        assert.strictEqual(modal.hidden, true, 'Backdrop click should close modal');
    });

    // ========================================================================
    // MS-05E6D Correction Tests (A - F)
    // ========================================================================

    test('35. Initial history HTTP 500: retryable 5xx message appears and discussion DOM remains intact', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: ROOT_ID,
                            authorUserId: '33333333-3333-3333-3333-333333333333',
                            body: 'Intact thread root',
                            tombstone: false,
                            createdAt: '2026-09-19T10:00:00Z',
                            updatedAt: '2026-09-19T10:30:00Z',
                            edited: true,
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const threadElBefore = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID).querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(threadElBefore);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { statusEl } = wikiCommentsModule.getExistingHistoryElements(doc);
        assert.ok(statusEl.textContent.includes('Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--error'));

        // Discussion DOM intact
        const threadElAfter = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID).querySelector('[data-thread-id="' + ROOT_ID + '"]');
        assert.ok(threadElAfter, 'Discussion feed thread must remain intact');
        assert.ok(threadElAfter.textContent.includes('Intact thread root'));
    });

    test('36. Load More network failure: previously rendered revisions remain, error message appears, page not advanced, retry available', async () => {
        const doc = createEnvironment();
        let loadMoreAttempted = false;

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                if (url.includes('page=0')) {
                    return {
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Revision 2 (initial)', createdAt: '2026-09-19T10:10:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                loadMoreAttempted = true;
                throw new Error('Network error on loadMore');
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { statusEl, listEl, moreContainer, moreBtn } = wikiCommentsModule.getExistingHistoryElements(doc);
        assert.strictEqual(listEl.childNodes.length, 1);
        assert.ok(listEl.textContent.includes('Revision 2 (initial)'));
        assert.strictEqual(moreContainer.hidden, false);

        // Click Load More -> triggers network error
        moreBtn.dispatchEvent({ type: 'click', target: moreBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(loadMoreAttempted, true);

        // 1. Previously rendered revisions remain
        assert.strictEqual(listEl.childNodes.length, 1);
        assert.ok(listEl.textContent.includes('Revision 2 (initial)'));

        // 2. Network error message appears
        assert.ok(statusEl.textContent.includes('Lỗi kết nối khi tải lịch sử chỉnh sửa.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--error'));

        // 3. Current page is not advanced
        const state = wikiCommentsModule.getState();
        assert.strictEqual(state.historyCurrentPage, 0, 'Page must not advance on failure');
        assert.strictEqual(state.historyHasNext, true, 'hasNext must not change on transient failure');

        // 4. Retry button remains available with "Thử lại"
        assert.strictEqual(moreContainer.hidden, false);
        assert.strictEqual(moreBtn.disabled, false);
        assert.strictEqual(moreBtn.textContent, 'Thử lại');
    });

    test('37. Load More HTTP 500: previously rendered revisions remain, retryable 5xx message appears, page not advanced, retry available', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                if (url.includes('page=0')) {
                    return {
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 2, body: 'Revision 2 (initial)', createdAt: '2026-09-19T10:10:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { statusEl, listEl, moreContainer, moreBtn } = wikiCommentsModule.getExistingHistoryElements(doc);

        // Click Load More -> returns 500
        moreBtn.dispatchEvent({ type: 'click', target: moreBtn });
        await new Promise(process.nextTick);

        // 1. Previously rendered revisions remain
        assert.strictEqual(listEl.childNodes.length, 1);
        assert.ok(listEl.textContent.includes('Revision 2 (initial)'));

        // 2. Retryable 5xx message appears
        assert.ok(statusEl.textContent.includes('Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--error'));

        // 3. Current page is not advanced
        const state = wikiCommentsModule.getState();
        assert.strictEqual(state.historyCurrentPage, 0);
        assert.strictEqual(state.historyHasNext, true);

        // 4. Retry button remains available
        assert.strictEqual(moreContainer.hidden, false);
        assert.strictEqual(moreBtn.disabled, false);
        assert.strictEqual(moreBtn.textContent, 'Thử lại');
    });

    test('38. Successful retry after transient Load More failure: error status clears, next page appends once, ordering/dedupe correct', async () => {
        const doc = createEnvironment();
        let attempts = 0;

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                if (url.includes('page=0')) {
                    return {
                        status: 200,
                        json: async () => ({
                            items: [{ revisionNumber: 3, body: 'Revision 3', createdAt: '2026-09-19T10:20:00Z' }],
                            page: 0,
                            size: 20,
                            hasNext: true
                        })
                    };
                }
                attempts++;
                if (attempts === 1) {
                    return { status: 500 }; // Transient failure on first attempt
                }
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            { revisionNumber: 3, body: 'Revision 3 (dup)', createdAt: '2026-09-19T10:20:00Z' },
                            { revisionNumber: 2, body: 'Revision 2', createdAt: '2026-09-19T10:10:00Z' }
                        ],
                        page: 1,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        await wikiCommentsModule.openRevisionHistory(ROOT_ID, null, doc);
        await new Promise(process.nextTick);

        const { statusEl, listEl, moreContainer, moreBtn } = wikiCommentsModule.getExistingHistoryElements(doc);

        // First Load More attempt -> fails with 500
        moreBtn.dispatchEvent({ type: 'click', target: moreBtn });
        await new Promise(process.nextTick);

        assert.ok(statusEl.textContent.includes('Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.'));
        assert.ok(statusEl.className.includes('wiki-comment-history-status--error'));
        assert.strictEqual(moreBtn.textContent, 'Thử lại');

        // Retry Load More -> succeeds
        moreBtn.dispatchEvent({ type: 'click', target: moreBtn });
        await new Promise(process.nextTick);

        // 1. Error status is cleared
        assert.strictEqual(statusEl.textContent, '', 'Status error message must be cleared on successful retry');
        assert.strictEqual(statusEl.className.includes('wiki-comment-history-status--error'), false);

        // 2. Next page appends once with deduplication (Rev 3, Rev 2)
        const entries = listEl.querySelectorAll('.wiki-comment-history-entry');
        assert.strictEqual(entries.length, 2, 'Exactly 2 revisions after deduplicated append');
        assert.ok(entries[0].textContent.includes('Phiên bản #3'));
        assert.ok(entries[1].textContent.includes('Phiên bản #2'));

        // 3. Load More hidden when exhausted
        assert.strictEqual(moreContainer.hidden, true);
    });

    test('39. Escape before history was ever opened: does not create #wikiCommentHistoryModal', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Never opened history; press Escape
        doc.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27 });

        // Must NOT create history modal
        const modal = doc.getElementById(wikiCommentsModule.HISTORY_MODAL_ID);
        assert.strictEqual(modal, null, 'Modal must NOT be created by Escape before history was ever opened');
    });

    test('40. resetState/destroy before history was ever opened: does not create the history modal', async () => {
        const doc = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Destroy/reset before history was ever opened
        wikiCommentsModule.resetState();

        // Must NOT create history modal
        const modal = doc.getElementById(wikiCommentsModule.HISTORY_MODAL_ID);
        assert.strictEqual(modal, null, 'Modal must NOT be created by resetState/destroy before history was opened');
    });

    test('41. Stale revision history request across module lifecycles for same comment ID: stale response is ignored and cannot overwrite active session', async () => {
        // 1. Init session A
        const docA = createEnvironment();
        const commentX = ROOT_ID;

        let resolveSessionAFetch;
        const sessionAPromise = new Promise((resolve) => {
            resolveSessionAFetch = resolve;
        });

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return sessionAPromise;
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(docA);
        await new Promise(process.nextTick);

        // 2. Trigger openRevisionHistory(commentX) whose response promise is delayed
        const openPromiseA = wikiCommentsModule.openRevisionHistory(commentX, null, docA);
        await new Promise(process.nextTick);

        // 3. resetState() / destroy
        wikiCommentsModule.resetState();

        // 4. Init session B
        const docB = createEnvironment();

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                return {
                    status: 200,
                    json: async () => ({
                        items: [
                            { revisionNumber: 5, body: 'Session B Revision 5', createdAt: '2026-09-19T11:00:00Z' }
                        ],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(docB);
        await new Promise(process.nextTick);

        // 5. Trigger openRevisionHistory(commentX) for the SAME comment ID
        // 6. Complete session B with distinct data
        await wikiCommentsModule.openRevisionHistory(commentX, null, docB);
        await new Promise(process.nextTick);

        const { listEl: listElB } = wikiCommentsModule.getExistingHistoryElements(docB);
        assert.ok(listElB);
        assert.strictEqual(listElB.querySelectorAll('.wiki-comment-history-entry').length, 1);
        assert.ok(listElB.textContent.includes('Session B Revision 5'));

        const stateB = wikiCommentsModule.getState();
        assert.strictEqual(stateB.historyActiveCommentId, commentX);
        assert.strictEqual(stateB.historyCurrentPage, 0);
        assert.strictEqual(stateB.historyHasNext, false);

        // 7. Resolve the delayed response from session A
        resolveSessionAFetch({
            status: 200,
            json: async () => ({
                items: [
                    { revisionNumber: 1, body: 'Session A Stale Revision 1', createdAt: '2026-09-19T10:00:00Z' }
                ],
                page: 99,
                size: 20,
                hasNext: true
            })
        });

        await openPromiseA;
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        // 8. Verify:
        //    - session B's modal content is not overwritten
        //    - stale session A response is completely ignored
        //    - state (historyCurrentPage, historyHasNext, rendered revisions) reflects session B
        assert.ok(listElB.textContent.includes('Session B Revision 5'), 'Session B content must remain present');
        assert.strictEqual(listElB.textContent.includes('Session A Stale Revision 1'), false, 'Stale session A content must NOT be rendered');
        assert.strictEqual(listElB.querySelectorAll('.wiki-comment-history-entry').length, 1, 'Only session B entry should be in list');

        const finalState = wikiCommentsModule.getState();
        assert.strictEqual(finalState.historyActiveCommentId, commentX);
        assert.strictEqual(finalState.historyCurrentPage, 0, 'historyCurrentPage must reflect session B (0)');
        assert.strictEqual(finalState.historyHasNext, false, 'historyHasNext must reflect session B (false)');
        assert.strictEqual(finalState.historyIsLoading, false);
    });
});

describe('UX-DRAFT-01B Wiki Root Comment Draft Persistence Integration Tests', () => {

    class MockStorage {
        constructor() {
            this.store = new Map();
            this.shouldThrow = false;
        }

        getItem(key) {
            if (this.shouldThrow) {
                throw new Error('Storage access restricted');
            }
            return this.store.has(key) ? this.store.get(key) : null;
        }

        setItem(key, value) {
            if (this.shouldThrow) {
                throw new Error('QuotaExceededError');
            }
            this.store.set(key, String(value));
        }

        removeItem(key) {
            if (this.shouldThrow) {
                throw new Error('Storage access restricted');
            }
            this.store.delete(key);
        }

        clear() {
            this.store.clear();
        }
    }

    let mockStorage;
    let mockTime;
    let draftStore;

    beforeEach(() => {
        mockStorage = new MockStorage();
        mockTime = 1_000_000;
        draftStore = EphemeralDraftStore.createStore({
            storage: mockStorage,
            clock: () => mockTime,
            defaultTtlMs: 5 * 60 * 1000
        });
        wikiCommentsModule.resetState();
        wikiCommentsModule.setDraftStore(draftStore);
    });

    test('0. getRootDraftKey validation: hardens against accidental fallback for empty/whitespace/null arguments', () => {
        // Normal no-argument invocation when articleId is not set
        assert.strictEqual(wikiCommentsModule.getRootDraftKey(), null, 'No-arg call before init should return null');

        // Normal no-argument invocation after init
        const doc = createEnvironment({ articleIdVal: 'art-xyz-789' });
        wikiCommentsModule.init(doc);
        assert.strictEqual(
            wikiCommentsModule.getRootDraftKey(),
            'kiemlai:draft:wiki-comment:art-xyz-789:root',
            'No-argument getRootDraftKey() must use active articleId'
        );

        // Explicit arguments: MUST NOT fall back to active articleId ('art-xyz-789')
        assert.strictEqual(wikiCommentsModule.getRootDraftKey(''), null, 'Explicit empty string must return null');
        assert.strictEqual(wikiCommentsModule.getRootDraftKey('   '), null, 'Explicit whitespace string must return null');
        assert.strictEqual(wikiCommentsModule.getRootDraftKey(null), null, 'Explicit null must return null');
        assert.strictEqual(wikiCommentsModule.getRootDraftKey(undefined), null, 'Explicit undefined must return null');
        assert.strictEqual(wikiCommentsModule.getRootDraftKey(123), null, 'Non-string must return null');

        // Valid explicit argument overrides active articleId
        const keyUuid = wikiCommentsModule.getRootDraftKey('11111111-2222-3333-4444-555555555555');
        assert.strictEqual(keyUuid, 'kiemlai:draft:wiki-comment:11111111-2222-3333-4444-555555555555:root');

        const keyEncoded = wikiCommentsModule.getRootDraftKey('article/test#1');
        assert.strictEqual(keyEncoded, 'kiemlai:draft:wiki-comment:article%2Ftest%231:root');
    });

    test('1. Root draft is restored into empty textarea on initialization', async () => {
        const draftText = 'Bình luận dở dang từ phiên trước';
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        draftStore.save(key, draftText);

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        assert.strictEqual(inputEl.value, draftText, 'Draft text must be restored into textarea');
    });

    test('2. Root draft does NOT overwrite existing textarea content on initialization', async () => {
        const draftText = 'Bản nháp trong storage';
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        draftStore.save(key, draftText);

        const doc = createEnvironment({ authenticated: 'true' });
        const inputEl = doc.getElementById('wikiRootComposerInput');
        inputEl.value = 'Nội dung người dùng đã gõ trước khi init';

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        assert.strictEqual(inputEl.value, 'Nội dung người dùng đã gõ trước khi init', 'Existing content must not be overwritten');
    });

    test('3. Typing persists draft after 400ms debounce and clearing removes draft', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        const inputEl = doc.getElementById('wikiRootComposerInput');

        // User types
        inputEl.value = 'Đang gõ bình luận mới...';
        inputEl.dispatchEvent({ type: 'input' });

        // Immediate check: debounce not yet elapsed
        assert.strictEqual(draftStore.load(key), null, 'Draft should not be saved before debounce fires');

        // Wait 450ms for debounce
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), 'Đang gõ bình luận mới...', 'Draft should be saved after debounce');

        // User clears text
        inputEl.value = '   ';
        inputEl.dispatchEvent({ type: 'input' });

        // Wait 450ms for debounce
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), null, 'Draft should be removed when textarea is cleared');
    });

    test('4. Successful submit (201 Created) clears textarea and removes draft from storage', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        const textToSubmit = 'Bình luận sắp gửi thành công';

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                return {
                    status: 201,
                    json: async () => ({
                        id: 'new-comment-id',
                        body: textToSubmit,
                        createdAt: '2026-09-19T10:00:00Z'
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        const formEl = doc.getElementById('wikiRootComposerForm');

        inputEl.value = textToSubmit;
        draftStore.save(key, textToSubmit);
        assert.strictEqual(draftStore.load(key), textToSubmit);

        formEl.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(inputEl.value, '', 'Textarea must be cleared on 201');
        assert.strictEqual(draftStore.load(key), null, 'Draft must be removed from storage on 201');
    });

    test('5. Failed submit (400, 404, 500, network error) preserves textarea and draft', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        const textDraft = 'Bình luận không được mất khi lỗi';

        let returnStatus = 400;
        let throwNetwork = false;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                if (throwNetwork) {
                    throw new Error('Failed to fetch');
                }
                return { status: returnStatus, json: async () => ({}) };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [],
                    threadCount: 0,
                    commentCount: 0,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        const formEl = doc.getElementById('wikiRootComposerForm');

        // Test 400
        returnStatus = 400;
        inputEl.value = textDraft;
        formEl.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);
        assert.strictEqual(inputEl.value, textDraft, '400 must preserve textarea content');
        assert.strictEqual(draftStore.load(key), textDraft, '400 must preserve draft in storage');

        // Test 404
        returnStatus = 404;
        formEl.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);
        assert.strictEqual(inputEl.value, textDraft, '404 must preserve textarea content');
        assert.strictEqual(draftStore.load(key), textDraft, '404 must preserve draft in storage');

        // Test 500
        returnStatus = 500;
        formEl.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);
        assert.strictEqual(inputEl.value, textDraft, '500 must preserve textarea content');
        assert.strictEqual(draftStore.load(key), textDraft, '500 must preserve draft in storage');

        // Test Network Error
        throwNetwork = true;
        formEl.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);
        assert.strictEqual(inputEl.value, textDraft, 'Network error must preserve textarea content');
        assert.strictEqual(draftStore.load(key), textDraft, 'Network error must preserve draft in storage');
    });

    test('6. Guest submit attempt persists draft immediately before login redirect', async () => {
        const loginDest = '/login?returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an%23wikiDiscussion';
        const doc = createEnvironment({ authenticated: 'false', loginUrlVal: loginDest });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        const formEl = doc.getElementById('wikiRootComposerForm');
        const guestText = 'Bình luận khách gõ trước khi bị chuyển hướng đăng nhập';

        inputEl.value = guestText;
        // Do NOT wait for debounce; immediately dispatch submit
        formEl.dispatchEvent({ type: 'submit', preventDefault: () => {} });

        // Verify redirect
        assert.ok(doc.defaultView.location.href.includes('/login'));

        // Verify draft was saved immediately
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        assert.strictEqual(draftStore.load(key), guestText, 'Draft must be saved synchronously prior to redirect');
    });

    test('7. Guest returns authenticated: draft is restored seamlessly', async () => {
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        const guestText = 'Bình luận khách đã lưu trước khi đăng nhập';
        draftStore.save(key, guestText);

        // Advance clock by 2 minutes (120,000ms) - well within 5min TTL
        mockTime += 120_000;

        wikiCommentsModule.resetState();
        wikiCommentsModule.setDraftStore(draftStore);

        const authenticatedDoc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(authenticatedDoc);
        await new Promise(process.nextTick);

        const inputEl = authenticatedDoc.getElementById('wikiRootComposerInput');
        assert.strictEqual(inputEl.value, guestText, 'Draft must be restored when user returns authenticated');
    });

    test('8. Expired draft (>5 min) is not restored and is purged from storage', async () => {
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        draftStore.save(key, 'Bản nháp đã quá hạn');

        // Advance clock by 5 minutes + 1 second (301,000ms)
        mockTime += 301_000;

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        assert.strictEqual(inputEl.value, '', 'Expired draft must NOT be restored');
        assert.strictEqual(draftStore.load(key), null, 'Expired draft must return null');
        assert.strictEqual(mockStorage.getItem(key), null, 'Expired draft must be purged from storage');
    });

    test('9. Draft isolation across distinct articles', async () => {
        const articleA = ARTICLE_ID;
        const articleB = '99999999-9999-9999-9999-999999999999';

        const keyA = wikiCommentsModule.getRootDraftKey(articleA);
        const keyB = wikiCommentsModule.getRootDraftKey(articleB);

        draftStore.save(keyA, 'Draft for Article A');

        // Initialize for Article B
        const docB = createEnvironment({ articleIdVal: articleB, authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(docB);
        await new Promise(process.nextTick);

        const inputB = docB.getElementById('wikiRootComposerInput');
        assert.strictEqual(inputB.value, '', 'Article B must not receive Article A draft');

        // Type on Article B
        inputB.value = 'Draft for Article B';
        inputB.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));

        // Verify both drafts exist independently
        assert.strictEqual(draftStore.load(keyA), 'Draft for Article A');
        assert.strictEqual(draftStore.load(keyB), 'Draft for Article B');
    });

    test('10. Multiline text and special characters preserved accurately in draft', async () => {
        const multilineText = 'Dòng 1: Mở đầu thảo luận\n  Dòng 2: Thụt đầu dòng\n\n"Trích dẫn" & ký tự đặc biệt <script>alert(1)</script>';
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        draftStore.save(key, multilineText);

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        assert.strictEqual(inputEl.value, multilineText, 'Exact multiline and special characters must be preserved');
    });

    test('11. Immediate page-exit flush on pagehide before 400ms debounce expires saves text and restores upon re-init', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const inputEl = doc.getElementById('wikiRootComposerInput');
        const fastDraft = 'Bình luận gõ rất nhanh trước khi reload';

        // User types text
        inputEl.value = fastDraft;
        inputEl.dispatchEvent({ type: 'input' });

        // Verify that before 400ms debounce, text is NOT yet saved in store
        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        assert.strictEqual(draftStore.load(key), null, 'Draft should not be in store immediately after typing');

        // Trigger immediate page-exit boundary (pagehide) before 400ms timer fires
        doc.defaultView.dispatchEvent({ type: 'pagehide' });

        // Prove A: Synchronously saved without waiting for 400ms timer
        assert.strictEqual(
            draftStore.load(key),
            fastDraft,
            'pagehide must synchronously flush pending draft to storage before debounce expires'
        );

        // Prove B: Subsequent initialization restores that exact text
        wikiCommentsModule.resetState();
        wikiCommentsModule.setDraftStore(draftStore);

        const reloadDoc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(reloadDoc);
        await new Promise(process.nextTick);

        const reloadedInputEl = reloadDoc.getElementById('wikiRootComposerInput');
        assert.strictEqual(
            reloadedInputEl.value,
            fastDraft,
            'Subsequent initialization must restore the exact flushed draft text'
        );
    });

    test('12. destroy/reset removes pagehide listener and does not allow stale lifecycle handlers', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [],
                threadCount: 0,
                commentCount: 0,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Verify pagehide listener is registered on doc.defaultView
        assert.strictEqual(
            doc.defaultView.listeners['pagehide'] && doc.defaultView.listeners['pagehide'].length,
            1,
            'pagehide listener should be registered on defaultView after init'
        );

        // Type something
        const inputEl = doc.getElementById('wikiRootComposerInput');
        inputEl.value = 'Text before reset';
        inputEl.dispatchEvent({ type: 'input' });

        // Destroy/reset module
        wikiCommentsModule.resetState();

        // Verify listener was cleanly removed
        assert.strictEqual(
            doc.defaultView.listeners['pagehide'] && doc.defaultView.listeners['pagehide'].length,
            0,
            'resetState must remove pagehide listener from defaultView'
        );

        // Dispatch pagehide on old window - should have no effect
        inputEl.value = 'Stale text after destroy';
        doc.defaultView.dispatchEvent({ type: 'pagehide' });

        const key = wikiCommentsModule.getRootDraftKey(ARTICLE_ID);
        assert.strictEqual(
            draftStore.load(key),
            null,
            'Stale pagehide event on destroyed module must not save draft'
        );
    });
});

// ============================================================================
// UX-DRAFT-01C Wiki Reply + Edit Draft Persistence Integration Tests
// ============================================================================

describe('UX-DRAFT-01C Wiki Reply + Edit Draft Persistence Integration Tests', () => {
    class MockStorage {
        constructor() {
            this.store = new Map();
            this.shouldThrow = false;
        }

        getItem(key) {
            if (this.shouldThrow) {
                throw new Error('Storage access restricted');
            }
            return this.store.has(key) ? this.store.get(key) : null;
        }

        setItem(key, value) {
            if (this.shouldThrow) {
                throw new Error('Storage access restricted');
            }
            this.store.set(key, String(value));
        }

        removeItem(key) {
            if (this.shouldThrow) {
                throw new Error('Storage access restricted');
            }
            this.store.delete(key);
        }

        clear() {
            this.store.clear();
        }
    }

    let mockStorage;
    let mockTime;
    let draftStore;

    beforeEach(() => {
        mockStorage = new MockStorage();
        mockTime = 1_000_000;
        draftStore = EphemeralDraftStore.createStore({
            storage: mockStorage,
            clock: () => mockTime,
            defaultTtlMs: 5 * 60 * 1000
        });
        wikiCommentsModule.resetState();
        wikiCommentsModule.setDraftStore(draftStore);
    });

    function createPopulatedThread() {
        return {
            root: {
                id: ROOT_ID,
                body: 'Nội dung bình luận gốc',
                canEdit: true,
                canDelete: true,
                tombstone: false,
                author: { displayName: 'Tác Giả Gốc' },
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z'
            },
            replies: [
                {
                    id: REPLY_ID,
                    parentCommentId: ROOT_ID,
                    body: 'Nội dung phản hồi 1',
                    canEdit: true,
                    canDelete: true,
                    tombstone: false,
                    author: { displayName: 'Người Trả Lời' },
                    createdAt: '2026-09-19T10:05:00Z',
                    updatedAt: '2026-09-19T10:05:00Z'
                }
            ]
        };
    }

    test('1. Canonical key builders: reply, edit, and active-inline keys format and validation', () => {
        const doc = createEnvironment({ articleIdVal: 'art-123' });
        wikiCommentsModule.init(doc);

        // Reply key checks
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(null), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(''), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey('   '), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey('target-1', ''), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey('target-1', null), null);
        assert.strictEqual(
            wikiCommentsModule.getReplyDraftKey('target-1'),
            'kiemlai:draft:wiki-comment:art-123:reply:target-1'
        );
        assert.strictEqual(
            wikiCommentsModule.getReplyDraftKey('target/1#special', 'art/custom'),
            'kiemlai:draft:wiki-comment:art%2Fcustom:reply:target%2F1%23special'
        );

        // Edit key checks
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(null), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(''), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey('   '), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey('comm-1', ''), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey('comm-1', null), null);
        assert.strictEqual(
            wikiCommentsModule.getEditDraftKey('comm-1'),
            'kiemlai:draft:wiki-comment:art-123:edit:comm-1'
        );
        assert.strictEqual(
            wikiCommentsModule.getEditDraftKey('comm/1#special', 'art/custom'),
            'kiemlai:draft:wiki-comment:art%2Fcustom:edit:comm%2F1%23special'
        );

        // Active-inline marker key checks
        assert.strictEqual(wikiCommentsModule.getActiveInlineMarkerKey(''), null);
        assert.strictEqual(wikiCommentsModule.getActiveInlineMarkerKey(null), null);
        assert.strictEqual(
            wikiCommentsModule.getActiveInlineMarkerKey(),
            'kiemlai:draft:wiki-comment:art-123:active-inline'
        );
        assert.strictEqual(
            wikiCommentsModule.getActiveInlineMarkerKey('art/custom'),
            'kiemlai:draft:wiki-comment:art%2Fcustom:active-inline'
        );
    });

    test('2. Reply draft is restored into reply textarea on openReplyComposer', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const replyDraftText = 'Bản nháp phản hồi đã lưu từ trước';
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        draftStore.save(key, replyDraftText);

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open reply composer
        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);

        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        assert.ok(slot, 'Reply slot must exist');
        const textarea = slot.querySelector('textarea');
        assert.ok(textarea, 'Reply textarea must exist');
        assert.strictEqual(textarea.value, replyDraftText, 'Saved reply draft must be restored into textarea');

        // Verify active inline marker
        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.deepStrictEqual(marker, {
            type: 'reply',
            targetCommentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('3. Existing reply draft not overwritten if textarea already has user input', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');

        textarea.value = 'User typed reply text';

        // Re-saving or restoring does not clobber user typed text
        const restored = wikiCommentsModule.loadReplyDraft(ROOT_ID);
        assert.strictEqual(restored, null, 'No draft was saved yet before debounce');
        assert.strictEqual(textarea.value, 'User typed reply text');
    });

    test('4. Reply typing debounce (~400ms) saves draft to storage', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);

        textarea.value = 'Đang nhập phản hồi thử nghiệm...';
        textarea.dispatchEvent({ type: 'input' });

        // Immediate check: debounce not yet elapsed
        assert.strictEqual(draftStore.load(key), null, 'Draft must not be saved immediately before 400ms');

        // Wait 450ms
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), 'Đang nhập phản hồi thử nghiệm...', 'Draft must be saved after debounce');
    });

    test('5. Clearing reply input removes draft from storage', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);

        textarea.value = 'Nội dung ban đầu';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), 'Nội dung ban đầu');

        // User clears textarea
        textarea.value = '   ';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), null, 'Empty/blank text must purge draft from store');
    });

    test('6. Cancel reply removes draft from storage and removes active inline marker', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        const markerKey = wikiCommentsModule.getActiveInlineMarkerKey(ARTICLE_ID);

        textarea.value = 'Phản hồi sắp bị hủy';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));

        assert.strictEqual(draftStore.load(key), 'Phản hồi sắp bị hủy');
        assert.ok(draftStore.load(markerKey));

        // Click Cancel
        const cancelBtn = slot.querySelector('.wiki-comment-btn--secondary');
        cancelBtn.dispatchEvent({ type: 'click' });

        assert.strictEqual(draftStore.load(key), null, 'Cancel must remove draft from storage');
        assert.strictEqual(draftStore.load(markerKey), null, 'Cancel must remove active inline marker');
        assert.strictEqual(slot.childNodes.length, 0, 'Cancel must clear reply slot DOM');
        assert.strictEqual(wikiCommentsModule.getState().activeReplyTargetCommentId, null);
    });

    test('7. Successful reply submission (201) clears textarea, removes draft, and removes active marker', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        const markerKey = wikiCommentsModule.getActiveInlineMarkerKey(ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                return {
                    status: 201,
                    json: async () => ({
                        id: 'new-reply-id',
                        body: 'Phản hồi gửi thành công'
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const form = slot.querySelector('form');

        textarea.value = 'Phản hồi gửi thành công';
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(draftStore.load(key), null, '201 Created must remove reply draft');
        assert.strictEqual(draftStore.load(markerKey), null, '201 Created must remove active inline marker');
        assert.strictEqual(wikiCommentsModule.getState().activeReplyTargetCommentId, null);
    });

    test('8. Failed reply submission (400) keeps draft in storage and textarea content intact', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                return { status: 400 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const form = slot.querySelector('form');

        const testText = 'Nội dung phản hồi bị lỗi 400';
        textarea.value = testText;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, testText, '400 must preserve textarea content');
        assert.strictEqual(draftStore.load(key), testText, '400 must preserve draft in storage');
    });

    test('9. Failed reply submission (404) keeps draft in storage and textarea content intact', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                return { status: 404 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const form = slot.querySelector('form');

        const testText = 'Nội dung phản hồi bị lỗi 404';
        textarea.value = testText;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, testText, '404 must preserve textarea content');
        assert.strictEqual(draftStore.load(key), testText, '404 must preserve draft in storage');
    });

    test('10. Failed reply submission (500) keeps draft in storage and textarea content intact', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const form = slot.querySelector('form');

        const testText = 'Nội dung phản hồi bị lỗi 500';
        textarea.value = testText;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, testText, '500 must preserve textarea content');
        assert.strictEqual(draftStore.load(key), testText, '500 must preserve draft in storage');
    });

    test('11. Failed reply submission (network error) keeps draft in storage and textarea content intact', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                throw new Error('Network failure');
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const form = slot.querySelector('form');

        const testText = 'Nội dung phản hồi bị lỗi mạng';
        textarea.value = testText;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, testText, 'Network failure must preserve textarea content');
        assert.strictEqual(draftStore.load(key), testText, 'Network failure must preserve draft in storage');
    });

    test('12. Unauthenticated reply submit attempt flushes draft and active marker before redirecting to login', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        const form = slot.querySelector('form');

        const guestReplyText = 'Phản hồi của người dùng trước khi session hết hạn';
        textarea.value = guestReplyText;

        // Simulate session expiry by returning 401 on submit
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 401
        }));

        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.ok(doc.defaultView.location.href.includes('/login'), 'Must redirect to login on 401');

        const key = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        const markerKey = wikiCommentsModule.getActiveInlineMarkerKey(ARTICLE_ID);
        assert.strictEqual(draftStore.load(key), guestReplyText, 'Draft must be saved synchronously on auth redirect');
        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.deepStrictEqual(marker, {
            type: 'reply',
            targetCommentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('13. Reply draft isolation across distinct target comments', async () => {
        const keyRoot = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        const keyReply = wikiCommentsModule.getReplyDraftKey(REPLY_ID, ARTICLE_ID);

        draftStore.save(keyRoot, 'Draft for root comment');
        draftStore.save(keyReply, 'Draft for reply comment');

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open root reply composer
        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slotRoot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        assert.strictEqual(slotRoot.querySelector('textarea').value, 'Draft for root comment');

        // Open reply-to-reply composer
        wikiCommentsModule.openReplyComposer(REPLY_ID, ROOT_ID, 'Người Trả Lời', doc);
        const slotReply = doc.querySelector('[data-reply-slot="' + REPLY_ID + '"]');
        assert.strictEqual(slotReply.querySelector('textarea').value, 'Draft for reply comment');

        assert.strictEqual(draftStore.load(keyRoot), 'Draft for root comment');
        assert.strictEqual(draftStore.load(keyReply), 'Draft for reply comment');
    });

    test('14. Opening edit composer populates authoritative server body by default and does NOT persist to draft store', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');

        assert.strictEqual(textarea.value, 'Nội dung bình luận gốc', 'Must populate authoritative server body');

        // Critical rule: Opening unchanged server body must NOT write to draft store
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        assert.strictEqual(draftStore.load(key), null, 'Unchanged server body must not be saved as draft on open');
        assert.strictEqual(wikiCommentsModule.getState().activeEditHasUserTyped, false);
    });

    test('15. Opening edit composer with existing draft restores draft in place of server body', async () => {
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        draftStore.save(key, 'Bản sửa nháp dở dang từ phiên trước');

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');

        assert.strictEqual(
            textarea.value,
            'Bản sửa nháp dở dang từ phiên trước',
            'Restored draft must take precedence over authoritative server body'
        );
        assert.strictEqual(wikiCommentsModule.getState().activeEditHasUserTyped, true);
    });

    test('16. Edit typing debounce (~400ms) saves draft to storage and marks activeEditHasUserTyped = true', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        textarea.value = 'Chỉnh sửa mới chưa debounce';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(draftStore.load(key), null, 'Draft must not be saved immediately before 400ms');
        assert.strictEqual(wikiCommentsModule.getState().activeEditHasUserTyped, true);

        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), 'Chỉnh sửa mới chưa debounce');
    });

    test('17. Blank/cleared edit input removes draft from storage', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        textarea.value = 'Chỉnh sửa';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), 'Chỉnh sửa');

        textarea.value = '   ';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));
        assert.strictEqual(draftStore.load(key), null, 'Cleared edit textarea removes draft');
    });

    test('18. Cancel edit removes draft from storage, removes active marker, and restores original DOM', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        const markerKey = wikiCommentsModule.getActiveInlineMarkerKey(ARTICLE_ID);

        textarea.value = 'Bản sửa sắp bị hủy';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));

        assert.strictEqual(draftStore.load(key), 'Bản sửa sắp bị hủy');
        assert.ok(draftStore.load(markerKey));

        const cancelBtn = bodyContainer.querySelector('.wiki-comment-btn--secondary');
        cancelBtn.dispatchEvent({ type: 'click' });

        assert.strictEqual(draftStore.load(key), null, 'Cancel must remove edit draft');
        assert.strictEqual(draftStore.load(markerKey), null, 'Cancel must remove active inline marker');
        assert.strictEqual(bodyContainer.querySelector('form'), null, 'Cancel must remove edit form');
        assert.strictEqual(bodyContainer.textContent, 'Nội dung bình luận gốc', 'Cancel must restore original comment body');
    });

    test('19. Successful edit submission (204) removes draft, removes active marker, and refreshes thread', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        const markerKey = wikiCommentsModule.getActiveInlineMarkerKey(ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 204 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        textarea.value = 'Nội dung sửa thành công';
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(draftStore.load(key), null, '204 must remove edit draft');
        assert.strictEqual(draftStore.load(markerKey), null, '204 must remove active inline marker');
        assert.strictEqual(wikiCommentsModule.getState().activeEditCommentId, null);
    });

    test('20. Failed edit submission (400) preserves draft in storage and textarea content', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 400 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const editBody = 'Sửa gặp lỗi 400';
        textarea.value = editBody;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, editBody);
        assert.strictEqual(draftStore.load(key), editBody);
    });

    test('21. Failed edit submission (403) preserves draft in storage, displays permission error', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 403 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const editBody = 'Sửa gặp lỗi 403 không có quyền';
        textarea.value = editBody;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, editBody);
        assert.strictEqual(draftStore.load(key), editBody);
    });

    test('22. Failed edit submission (404) preserves draft in storage and textarea content', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 404 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const editBody = 'Sửa gặp lỗi 404';
        textarea.value = editBody;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, editBody);
        assert.strictEqual(draftStore.load(key), editBody);
    });

    test('23. Failed edit submission (500) preserves draft in storage and textarea content', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const editBody = 'Sửa gặp lỗi 500';
        textarea.value = editBody;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, editBody);
        assert.strictEqual(draftStore.load(key), editBody);
    });

    test('24. Failed edit submission (network error) preserves draft in storage and textarea content', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                throw new Error('Network error');
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const editBody = 'Sửa gặp lỗi kết nối mạng';
        textarea.value = editBody;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.strictEqual(textarea.value, editBody);
        assert.strictEqual(draftStore.load(key), editBody);
    });

    test('25. Unauthenticated edit submit attempt flushes draft (if typed) and active marker before login redirect', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 401 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const typedEdit = 'Đã sửa nhưng hết hạn phiên đăng nhập';
        textarea.value = typedEdit;
        textarea.dispatchEvent({ type: 'input' });

        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.ok(doc.defaultView.location.href.includes('/login'));

        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        assert.strictEqual(draftStore.load(key), typedEdit);
        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.deepStrictEqual(marker, {
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('26. Edit draft isolation across distinct comments', async () => {
        const keyRoot = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        const keyReply = wikiCommentsModule.getEditDraftKey(REPLY_ID, ARTICLE_ID);

        draftStore.save(keyRoot, 'Draft edit root');
        draftStore.save(keyReply, 'Draft edit reply');

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const rootContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const rootTextarea = rootContainer.querySelector('textarea');
        assert.strictEqual(rootTextarea.value, 'Draft edit root');

        wikiCommentsModule.openEditComposer(REPLY_ID, ROOT_ID, doc);
        const replyContainer = doc.querySelector('[data-body-container="' + REPLY_ID + '"]');
        const replyTextarea = replyContainer.querySelector('textarea');
        assert.strictEqual(replyTextarea.value, 'Draft edit reply');
    });

    test('27. Active inline marker key format and save/load/remove behavior', () => {
        const markerData = { type: 'reply', targetCommentId: ROOT_ID, rootCommentId: ROOT_ID };
        assert.strictEqual(wikiCommentsModule.saveActiveInlineMarker(markerData, ARTICLE_ID), true);

        const loaded = wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID);
        assert.deepStrictEqual(loaded, markerData);

        wikiCommentsModule.removeActiveInlineMarker(ARTICLE_ID);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);

        // TTL test (>5 min)
        wikiCommentsModule.saveActiveInlineMarker(markerData, ARTICLE_ID);
        mockTime += 301_000;
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);
    });

    test('28. Switching composers auto-flushes first composer draft before opening second composer', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open Reply on ROOT_ID
        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const rootSlot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const rootTextarea = rootSlot.querySelector('textarea');
        rootTextarea.value = 'Chưa kịp chờ debounce cho root reply';
        rootTextarea.dispatchEvent({ type: 'input' });

        // User directly clicks reply on REPLY_ID before debounce fires
        wikiCommentsModule.openReplyComposer(REPLY_ID, ROOT_ID, 'Người Trả Lời', doc);

        // Verify root reply was synchronously flushed on switch
        const keyRootReply = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        assert.strictEqual(
            draftStore.load(keyRootReply),
            'Chưa kịp chờ debounce cho root reply',
            'First composer text must be auto-flushed on composer switch'
        );

        // Verify active marker updated to REPLY_ID
        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.deepStrictEqual(marker, {
            type: 'reply',
            targetCommentId: REPLY_ID,
            rootCommentId: ROOT_ID
        });

        // Type in reply composer and switch to edit on ROOT_ID
        const replySlot = doc.querySelector('[data-reply-slot="' + REPLY_ID + '"]');
        const replyTextarea = replySlot.querySelector('textarea');
        replyTextarea.value = 'Chưa kịp chờ debounce cho reply 1';
        replyTextarea.dispatchEvent({ type: 'input' });

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);

        const keyReplyReply = wikiCommentsModule.getReplyDraftKey(REPLY_ID, ARTICLE_ID);
        assert.strictEqual(
            draftStore.load(keyReplyReply),
            'Chưa kịp chờ debounce cho reply 1',
            'Reply text must be auto-flushed before opening edit composer'
        );

        const marker2 = wikiCommentsModule.loadActiveInlineMarker();
        assert.deepStrictEqual(marker2, {
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('29. Reload / re-init reopens active reply composer and restores draft when marker is present', async () => {
        const key = wikiCommentsModule.getReplyDraftKey(REPLY_ID, ARTICLE_ID);
        const replyDraft = 'Phản hồi lưu trước khi reload trang';
        draftStore.save(key, replyDraft);
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'reply',
            targetCommentId: REPLY_ID,
            rootCommentId: ROOT_ID
        }, ARTICLE_ID);

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const slot = doc.querySelector('[data-reply-slot="' + REPLY_ID + '"]');
        assert.ok(slot, 'Reply slot must exist');
        const composerBox = slot.querySelector('.wiki-inline-composer');
        assert.ok(composerBox, 'Reply composer must be automatically reopened upon re-init');
        const textarea = slot.querySelector('textarea');
        assert.strictEqual(textarea.value, replyDraft, 'Draft must be restored in reopened reply composer');
    });

    test('30. Reload / re-init reopens active edit composer and restores draft when marker is present and permitted', async () => {
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        const editDraft = 'Bản sửa lưu trước khi reload trang';
        draftStore.save(key, editDraft);
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        }, ARTICLE_ID);

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const form = bodyContainer.querySelector('form');
        assert.ok(form, 'Edit composer must be automatically reopened upon re-init');
        const textarea = bodyContainer.querySelector('textarea');
        assert.strictEqual(textarea.value, editDraft, 'Draft must be restored in reopened edit composer');
    });

    test('31. Reload does NOT reopen edit composer if comment was tombstoned or canEdit === false', async () => {
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        draftStore.save(key, 'Sửa bình luận nhưng sau đó mất quyền');
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        }, ARTICLE_ID);

        const threadWithoutPermission = createPopulatedThread();
        threadWithoutPermission.root.canEdit = false; // Permission revoked

        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [threadWithoutPermission],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        assert.strictEqual(bodyContainer.querySelector('form'), null, 'Must not reopen edit composer without permission');
        assert.strictEqual(draftStore.load(key), null, 'Draft must be purged when permission is absent');
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(), null, 'Marker must be purged when permission is absent');
    });

    test('32. Immediate page-exit flush on pagehide synchronously flushes active reply and edit drafts before debounce expires', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open reply composer and type
        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const replyTextarea = slot.querySelector('textarea');
        const fastReplyText = 'Phản hồi gõ siêu nhanh trước khi reload';
        replyTextarea.value = fastReplyText;
        replyTextarea.dispatchEvent({ type: 'input' });

        const replyKey = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        assert.strictEqual(draftStore.load(replyKey), null, 'Before 400ms, reply draft is not in store');

        // Trigger pagehide
        doc.defaultView.dispatchEvent({ type: 'pagehide' });

        assert.strictEqual(
            draftStore.load(replyKey),
            fastReplyText,
            'pagehide must synchronously flush pending reply draft to storage'
        );

        // Re-open as edit composer and verify edit pagehide flush
        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const editTextarea = bodyContainer.querySelector('textarea');
        const fastEditText = 'Sửa bình luận rất nhanh';
        editTextarea.value = fastEditText;
        editTextarea.dispatchEvent({ type: 'input' });

        const editKey = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        assert.strictEqual(draftStore.load(editKey), null, 'Before 400ms, edit draft is not in store');

        doc.defaultView.dispatchEvent({ type: 'pagehide' });

        assert.strictEqual(
            draftStore.load(editKey),
            fastEditText,
            'pagehide must synchronously flush pending edit draft to storage'
        );
    });

    test('33. Unauthenticated guest feed load does NOT reopen inline composer', async () => {
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'reply',
            targetCommentId: ROOT_ID,
            rootCommentId: ROOT_ID
        }, ARTICLE_ID);

        const guestDoc = createEnvironment({ authenticated: 'false' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(guestDoc);
        await new Promise(process.nextTick);

        const slot = guestDoc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        assert.strictEqual(slot.childNodes.length, 0, 'Guest must not have inline composer opened');
        assert.strictEqual(guestDoc.defaultView.location.href, 'http://localhost/wiki/character/tran-binh-an', 'Guest must not be redirected during feed render');
    });

    test('34. destroy/resetState cancels active reply/edit debounce timers and cleans up state cleanly', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const replyTextarea = slot.querySelector('textarea');
        replyTextarea.value = 'Reply text before reset';
        replyTextarea.dispatchEvent({ type: 'input' });

        wikiCommentsModule.resetState();

        const state = wikiCommentsModule.getState();
        assert.strictEqual(state.activeReplyTargetCommentId, null);
        assert.strictEqual(state.activeReplyRootCommentId, null);
        assert.strictEqual(state.activeEditCommentId, null);
        assert.strictEqual(state.activeEditRootCommentId, null);
        assert.strictEqual(state.activeEditHasUserTyped, false);
    });

    test('35. getReplyDraftKey rejects number/object/boolean/array', () => {
        const doc = createEnvironment({ articleIdVal: 'art-123' });
        wikiCommentsModule.init(doc);

        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(12345), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(0), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(true), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(false), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey({}), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey({ id: 'target-1' }), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey([]), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(['target-1']), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(null), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(undefined), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey(''), null);
        assert.strictEqual(wikiCommentsModule.getReplyDraftKey('   '), null);
    });

    test('36. getEditDraftKey rejects number/object/boolean/array', () => {
        const doc = createEnvironment({ articleIdVal: 'art-123' });
        wikiCommentsModule.init(doc);

        assert.strictEqual(wikiCommentsModule.getEditDraftKey(12345), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(0), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(true), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(false), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey({}), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey({ id: 'comm-1' }), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey([]), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(['comm-1']), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(null), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(undefined), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey(''), null);
        assert.strictEqual(wikiCommentsModule.getEditDraftKey('   '), null);
    });

    test('37. Edit failure preserves EXACT raw whitespace in draft while PATCH body is trimmed', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);
        let patchBodyReceived = null;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                patchBodyReceived = JSON.parse(opts.body);
                return { status: 500 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const rawText = "  Nội dung sửa\n\n";
        textarea.value = rawText;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        // Assert PATCH body is trimmed
        assert.strictEqual(patchBodyReceived.body, "Nội dung sửa");

        // Assert stored draft preserves EXACT raw whitespace
        assert.strictEqual(draftStore.load(key), "  Nội dung sửa\n\n");

        // Assert textarea preserves EXACT raw text
        assert.strictEqual(textarea.value, "  Nội dung sửa\n\n");

        // Simulate reload: exact same raw draft restored
        const reloadedDoc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.init(reloadedDoc);
        await new Promise(process.nextTick);

        const reloadedContainer = reloadedDoc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const reloadedTextarea = reloadedContainer.querySelector('textarea');
        assert.ok(reloadedTextarea, 'Edit composer should reopen on reload');
        assert.strictEqual(reloadedTextarea.value, "  Nội dung sửa\n\n");
    });

    test('38. Edit 401/302 auth redirect also preserves exact raw text', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const key = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                return { status: 401 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');
        const form = bodyContainer.querySelector('form');

        const rawText = "  Sửa dở trước khi session hết hạn\n\n";
        textarea.value = rawText;
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        assert.ok(doc.defaultView.location.href.includes('/login'), 'Redirected to login');
        assert.strictEqual(draftStore.load(key), rawText, 'Exact raw text preserved in storage on 401 redirect');

        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.deepStrictEqual(marker, {
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('39. Reply active marker TTL is refreshed by continued typing', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open composer at T0 = 1,000,000
        mockTime = 1_000_000;
        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');

        // Advance clock to T0 + 280s (near original 5-minute expiry of 300s)
        mockTime += 280_000;

        // Perform new draft activity
        textarea.value = 'Đang tiếp tục nhập phản hồi sau 4.6 phút...';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));

        // Advance clock past original 5-minute expiry (T0 + 310s)
        mockTime += 30_000;

        // Marker must remain loadable because its savedAt was refreshed at T0 + 280s
        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.ok(marker, 'Marker must not be expired after refreshed savedAt');
        assert.deepStrictEqual(marker, {
            type: 'reply',
            targetCommentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('40. Edit active marker TTL is refreshed by continued typing', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open edit composer at T0 = 1,000,000
        mockTime = 1_000_000;
        wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        const textarea = bodyContainer.querySelector('textarea');

        // Advance clock to T0 + 280s
        mockTime += 280_000;

        // Perform new draft activity
        textarea.value = 'Đang tiếp tục sửa sau 4.6 phút...';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(resolve => setTimeout(resolve, 450));

        // Advance clock past original 5-minute expiry (T0 + 310s)
        mockTime += 30_000;

        // Marker must remain loadable
        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.ok(marker, 'Marker must remain loadable after refreshed savedAt');
        assert.deepStrictEqual(marker, {
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('41. pagehide refreshes active marker synchronously as well as body draft', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Open reply composer at T0 = 1,000,000
        mockTime = 1_000_000;
        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');

        // Advance clock to T0 + 250s
        mockTime += 250_000;

        // Fast typing immediately followed by pagehide (no debounce wait)
        const fastReply = 'Nội dung gõ ngay trước pagehide';
        textarea.value = fastReply;
        textarea.dispatchEvent({ type: 'input' });

        doc.defaultView.dispatchEvent({ type: 'pagehide' });

        // Advance clock past original 5-minute expiry (T0 + 310s)
        mockTime += 60_000;

        const replyKey = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        assert.strictEqual(draftStore.load(replyKey), fastReply, 'Draft must be loadable');

        const marker = wikiCommentsModule.loadActiveInlineMarker();
        assert.ok(marker, 'Marker must be loadable because pagehide refreshed its TTL');
        assert.deepStrictEqual(marker, {
            type: 'reply',
            targetCommentId: ROOT_ID,
            rootCommentId: ROOT_ID
        });
    });

    test('42. malformed active marker JSON/schema fails safely and does not reopen', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const markerKey = wikiCommentsModule.getActiveInlineMarkerKey(ARTICLE_ID);

        // Test 42a: Corrupted raw JSON
        draftStore.save(markerKey, '{bad-json-here');
        assert.doesNotThrow(() => {
            wikiCommentsModule.restoreActiveInlineComposer(doc);
        });
        assert.strictEqual(doc.querySelector('.wiki-inline-composer'), null);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);

        // Test 42b: Number comment ID (not nonblank string)
        draftStore.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: 12345, rootCommentId: ROOT_ID }));
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        assert.strictEqual(doc.querySelector('.wiki-inline-composer'), null);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);

        // Test 42c: Missing or empty targetCommentId
        draftStore.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: '   ', rootCommentId: ROOT_ID }));
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        assert.strictEqual(doc.querySelector('.wiki-inline-composer'), null);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);

        // Test 42d: Missing or non-string rootCommentId
        draftStore.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: ROOT_ID, rootCommentId: null }));
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        assert.strictEqual(doc.querySelector('.wiki-inline-composer'), null);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);

        // Test 42e: Unknown type
        draftStore.save(markerKey, JSON.stringify({ type: 'something_else', targetCommentId: ROOT_ID, rootCommentId: ROOT_ID }));
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        assert.strictEqual(doc.querySelector('.wiki-inline-composer'), null);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);

        // Test 42f: Edit malformed (number commentId)
        draftStore.save(markerKey, JSON.stringify({ type: 'edit', commentId: 99999, rootCommentId: ROOT_ID }));
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        assert.strictEqual(bodyContainer.querySelector('form'), null);
        assert.strictEqual(wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID), null);
    });

    test('43. marker for target not currently rendered: no crash, no fake composer, no API mutation, marker may remain', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        let fetchCalls = 0;
        wikiCommentsModule.setFetchImplementation(async () => {
            fetchCalls++;
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        const unrenderedId = '99999999-9999-9999-9999-999999999999';
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'reply',
            targetCommentId: unrenderedId,
            rootCommentId: unrenderedId
        }, ARTICLE_ID);

        // Initialize (1 GET for comments)
        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const fetchCallsAfterInit = fetchCalls;

        // Call restoreActiveInlineComposer
        assert.doesNotThrow(() => {
            wikiCommentsModule.restoreActiveInlineComposer(doc);
        });

        // No fake composer created
        assert.strictEqual(doc.querySelector('.wiki-inline-composer'), null);

        // No API mutations triggered
        assert.strictEqual(fetchCalls, fetchCallsAfterInit);
        assert.strictEqual(wikiCommentsModule.getState().isMutating, false);

        // Short-lived valid marker remains until later render or expiry
        const marker = wikiCommentsModule.loadActiveInlineMarker(ARTICLE_ID);
        assert.ok(marker, 'Marker should remain in storage for unrendered target');
        assert.strictEqual(marker.targetCommentId, unrenderedId);
    });

    test('44. repeated restoreActiveInlineComposer / feed refresh does NOT create duplicate reply/edit composers', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.saveActiveInlineMarker({
            type: 'reply',
            targetCommentId: ROOT_ID,
            rootCommentId: ROOT_ID
        }, ARTICLE_ID);

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        assert.strictEqual(slot.querySelectorAll('.wiki-inline-composer').length, 1);

        // Repeated restore calls
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        assert.strictEqual(slot.querySelectorAll('.wiki-inline-composer').length, 1);

        // Feed refresh
        await wikiCommentsModule.refreshFeed(doc);
        const slotAfterRefresh = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        assert.strictEqual(slotAfterRefresh.querySelectorAll('.wiki-inline-composer').length, 1);
    });

    test('45. resetState/destroy cancels pending timers/state and does NOT delete an already persisted valid reply/edit draft', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const replyKey = wikiCommentsModule.getReplyDraftKey(ROOT_ID, ARTICLE_ID);
        const editKey = wikiCommentsModule.getEditDraftKey(ROOT_ID, ARTICLE_ID);

        draftStore.save(replyKey, 'Valid persisted reply draft');
        draftStore.save(editKey, 'Valid persisted edit draft');

        wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Tác Giả Gốc', doc);
        const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
        const textarea = slot.querySelector('textarea');
        textarea.value = 'Typing something...';
        textarea.dispatchEvent({ type: 'input' });

        // Call resetState
        wikiCommentsModule.resetState();

        // State is reset
        const state = wikiCommentsModule.getState();
        assert.strictEqual(state.activeReplyTargetCommentId, null);
        assert.strictEqual(state.activeEditCommentId, null);

        // Valid persisted drafts are NOT deleted
        assert.strictEqual(draftStore.load(replyKey), 'Valid persisted reply draft');
        assert.strictEqual(draftStore.load(editKey), 'Valid persisted edit draft');
    });

    test('46. Restored reply marker with wrong rootCommentId reopens correctly and refreshes authoritative thread root on 201', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        let postCalledUrl = null;
        let refreshedRootId = null;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'POST') {
                postCalledUrl = url;
                return {
                    status: 201,
                    json: async () => ({ id: 'new-sub-reply-id' })
                };
            }
            if (url.includes('/thread')) {
                const match = url.match(/\/comments\/([^/]+)\/thread/);
                if (match) {
                    refreshedRootId = match[1];
                }
                return {
                    status: 200,
                    json: async () => createPopulatedThread()
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        const WRONG_ROOT_ID = '99999999-9999-9999-9999-wrongroot001';
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'reply',
            targetCommentId: REPLY_ID,
            rootCommentId: WRONG_ROOT_ID
        }, ARTICLE_ID);

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Verify composer reopened for correct target comment (REPLY_ID)
        const slot = doc.querySelector('[data-reply-slot="' + REPLY_ID + '"]');
        assert.ok(slot, 'Reply slot must exist for REPLY_ID');
        const textarea = slot.querySelector('textarea');
        assert.ok(textarea, 'Reply composer must be reopened');

        textarea.value = 'Phản hồi khi marker có root sai';
        const form = slot.querySelector('form');
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        // Assert POST still targeted correct comment
        assert.ok(postCalledUrl.includes('/comments/' + REPLY_ID + '/replies'), 'POST must target REPLY_ID');

        // Assert authoritative refresh used actual loaded root, NOT WRONG_ROOT_ID
        assert.strictEqual(refreshedRootId, ROOT_ID, 'Must refresh actual authoritative thread root');
        assert.notStrictEqual(refreshedRootId, WRONG_ROOT_ID, 'Must NOT use marker.rootCommentId');
    });

    test('47. Restored edit marker with wrong rootCommentId reopens correctly and refreshes authoritative thread root on 204', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        let patchCalledUrl = null;
        let refreshedRootId = null;

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'PATCH') {
                patchCalledUrl = url;
                return { status: 204 };
            }
            if (url.includes('/thread')) {
                const match = url.match(/\/comments\/([^/]+)\/thread/);
                if (match) {
                    refreshedRootId = match[1];
                }
                return {
                    status: 200,
                    json: async () => createPopulatedThread()
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [createPopulatedThread()],
                    threadCount: 1,
                    commentCount: 2,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        const WRONG_ROOT_ID = '99999999-9999-9999-9999-wrongroot002';
        wikiCommentsModule.saveActiveInlineMarker({
            type: 'edit',
            commentId: REPLY_ID,
            rootCommentId: WRONG_ROOT_ID
        }, ARTICLE_ID);

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Verify edit composer reopened for REPLY_ID
        const bodyContainer = doc.querySelector('[data-body-container="' + REPLY_ID + '"]');
        assert.ok(bodyContainer, 'Body container must exist for REPLY_ID');
        const form = bodyContainer.querySelector('form');
        assert.ok(form, 'Edit form must be reopened');

        const textarea = bodyContainer.querySelector('textarea');
        textarea.value = 'Sửa phản hồi khi marker có root sai';
        form.dispatchEvent({ type: 'submit', preventDefault: () => {} });
        await new Promise(process.nextTick);
        await new Promise(process.nextTick);

        // Assert PATCH targeted correct comment
        assert.ok(patchCalledUrl.includes('/comments/' + REPLY_ID), 'PATCH must target REPLY_ID');

        // Assert authoritative refresh used actual loaded root, NOT WRONG_ROOT_ID
        assert.strictEqual(refreshedRootId, ROOT_ID, 'Must refresh actual authoritative thread root');
        assert.notStrictEqual(refreshedRootId, WRONG_ROOT_ID, 'Must NOT use marker.rootCommentId');
    });

    test('48. Root comment target authoritative root resolves to its own ID and reply resolves to thread root', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // Root comment context check: resolves to its own ID
        const rootCtx = wikiCommentsModule.findCommentContext(ROOT_ID);
        assert.ok(rootCtx, 'Root context must be found');
        assert.strictEqual(rootCtx.comment.id, ROOT_ID);
        assert.strictEqual(rootCtx.rootCommentId, ROOT_ID, 'Root comment authoritative root is its own ID');

        // Reply comment context check: resolves to parent thread root ID
        const replyCtx = wikiCommentsModule.findCommentContext(REPLY_ID);
        assert.ok(replyCtx, 'Reply context must be found');
        assert.strictEqual(replyCtx.comment.id, REPLY_ID);
        assert.strictEqual(replyCtx.rootCommentId, ROOT_ID, 'Reply comment authoritative root is thread root ID');

        // Non-existent comment check: resolves to null
        assert.strictEqual(wikiCommentsModule.findCommentContext('non-existent-comment-id'), null);
        assert.strictEqual(wikiCommentsModule.findCommentContext(null), null);
        assert.strictEqual(wikiCommentsModule.findCommentContext(''), null);
    });

    test('49. Repeated restoreActiveInlineComposer and feed refresh do NOT create duplicate edit composers', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [createPopulatedThread()],
                threadCount: 1,
                commentCount: 2,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.saveActiveInlineMarker({
            type: 'edit',
            commentId: ROOT_ID,
            rootCommentId: ROOT_ID
        }, ARTICLE_ID);

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const bodyContainer = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        assert.strictEqual(bodyContainer.querySelectorAll('form').length, 1);

        // Repeated restore calls
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        wikiCommentsModule.restoreActiveInlineComposer(doc);
        assert.strictEqual(bodyContainer.querySelectorAll('form').length, 1);

        // Feed refresh
        await wikiCommentsModule.refreshFeed(doc);
        const bodyContainerAfter = doc.querySelector('[data-body-container="' + ROOT_ID + '"]');
        assert.strictEqual(bodyContainerAfter.querySelectorAll('form').length, 1);
    });

    test('50. Report button renders on non-owner active root comments and replies, but omitted on self comments and tombstones', async () => {
        const doc = createEnvironment();

        // 1. Non-owner root comment (canEdit: false, canDelete: false, tombstone: false)
        const nonOwnerRoot = {
            id: 'root-other-1',
            authorUserId: 'other-user-1',
            body: 'Comment by someone else',
            tombstone: false,
            createdAt: '2026-09-19T10:00:00Z',
            updatedAt: '2026-09-19T10:00:00Z',
            canEdit: false,
            canDelete: false
        };
        const renderedNonOwnerRoot = wikiCommentsModule.renderComment(nonOwnerRoot, 'root-other-1', null, false, doc);
        const reportBtnRoot = renderedNonOwnerRoot.querySelector('[data-action="report"]');
        assert.ok(reportBtnRoot, 'Report button must be rendered on non-owner active root');
        assert.strictEqual(reportBtnRoot.getAttribute('data-comment-id'), 'root-other-1');
        assert.strictEqual(reportBtnRoot.getAttribute('data-root-id'), 'root-other-1');
        assert.strictEqual(reportBtnRoot.textContent, 'Báo cáo');
        assert.ok(reportBtnRoot.className.includes('wiki-comment-action-btn'));

        // 2. Non-owner reply comment (canEdit: false, canDelete: false, tombstone: false)
        const nonOwnerReply = {
            id: 'reply-other-1',
            parentCommentId: 'root-other-1',
            authorUserId: 'other-user-2',
            body: 'Reply by someone else',
            tombstone: false,
            createdAt: '2026-09-19T10:05:00Z',
            updatedAt: '2026-09-19T10:05:00Z',
            canEdit: false,
            canDelete: false
        };
        const renderedNonOwnerReply = wikiCommentsModule.renderComment(nonOwnerReply, 'root-other-1', null, true, doc);
        const reportBtnReply = renderedNonOwnerReply.querySelector('[data-action="report"]');
        assert.ok(reportBtnReply, 'Report button must be rendered on non-owner active reply');
        assert.strictEqual(reportBtnReply.getAttribute('data-comment-id'), 'reply-other-1');
        assert.strictEqual(reportBtnReply.getAttribute('data-root-id'), 'root-other-1');
        assert.strictEqual(reportBtnReply.textContent, 'Báo cáo');

        // 3. Self root comment (canEdit: true, canDelete: true)
        const selfRoot = {
            id: 'root-self-1',
            authorUserId: 'current-user-1',
            body: 'My own comment',
            tombstone: false,
            createdAt: '2026-09-19T10:00:00Z',
            updatedAt: '2026-09-19T10:00:00Z',
            canEdit: true,
            canDelete: true
        };
        const renderedSelfRoot = wikiCommentsModule.renderComment(selfRoot, 'root-self-1', null, false, doc);
        assert.strictEqual(renderedSelfRoot.querySelector('[data-action="report"]'), null, 'Report button must NOT be rendered on self root comment');

        // 4. Self reply comment (canEdit: true, canDelete: true)
        const selfReply = {
            id: 'reply-self-1',
            parentCommentId: 'root-self-1',
            authorUserId: 'current-user-1',
            body: 'My own reply',
            tombstone: false,
            createdAt: '2026-09-19T10:05:00Z',
            updatedAt: '2026-09-19T10:05:00Z',
            canEdit: true,
            canDelete: true
        };
        const renderedSelfReply = wikiCommentsModule.renderComment(selfReply, 'root-self-1', null, true, doc);
        assert.strictEqual(renderedSelfReply.querySelector('[data-action="report"]'), null, 'Report button must NOT be rendered on self reply comment');

        // 5. Tombstone comment (tombstone: true)
        const tombstoneComment = {
            id: 'root-tombstone-1',
            body: '',
            tombstone: true,
            canEdit: false,
            canDelete: false
        };
        const renderedTombstone = wikiCommentsModule.renderComment(tombstoneComment, 'root-tombstone-1', null, false, doc);
        assert.strictEqual(renderedTombstone.querySelector('[data-action="report"]'), null, 'Report button must NOT be rendered on tombstone comment');
    });

    test('51. Active descendant under tombstoned intermediate reply remains reportable', async () => {
        const doc = createEnvironment();

        const activeRootId = 'root-active-1';
        const intermediateReplyId = 'reply-tombstone-A';
        const descendantReplyId = 'reply-active-B';

        const threadFixture = {
            root: {
                id: activeRootId,
                authorUserId: 'user-root-1',
                body: 'Active root comment',
                tombstone: false,
                createdAt: '2026-09-19T10:00:00Z',
                updatedAt: '2026-09-19T10:00:00Z',
                author: { displayName: 'Root Author' },
                canEdit: false,
                canDelete: false
            },
            replies: [
                {
                    id: intermediateReplyId,
                    parentCommentId: activeRootId,
                    authorUserId: 'user-reply-A',
                    body: '',
                    tombstone: true,
                    createdAt: '2026-09-19T10:05:00Z',
                    updatedAt: '2026-09-19T10:05:00Z',
                    author: null,
                    canEdit: false,
                    canDelete: false
                },
                {
                    id: descendantReplyId,
                    parentCommentId: intermediateReplyId, // B parentCommentId references tombstoned A
                    authorUserId: 'user-reply-B',
                    body: 'Active reply under tombstoned reply A',
                    tombstone: false,
                    createdAt: '2026-09-19T10:10:00Z',
                    updatedAt: '2026-09-19T10:10:00Z',
                    author: { displayName: 'Descendant Author' },
                    canEdit: false,
                    canDelete: false
                }
            ]
        };

        const renderedThread = wikiCommentsModule.renderThread(threadFixture, doc);

        // 1. Root is active and has Report action (as non-owner)
        const rootCommentEl = renderedThread.querySelector('[data-comment-id="' + activeRootId + '"]');
        assert.ok(rootCommentEl, 'Root comment element must exist');
        assert.strictEqual(rootCommentEl.classList.contains('is-tombstone'), false, 'Root must be active');
        const rootReportBtn = rootCommentEl.querySelector('[data-action="report"]');
        assert.ok(rootReportBtn, 'Non-owner active root has report action');
        assert.strictEqual(rootReportBtn.getAttribute('data-comment-id'), activeRootId);

        // 2. Intermediate reply A renders tombstone and has NO Report action
        const intermediateEl = renderedThread.querySelector('[data-comment-id="' + intermediateReplyId + '"]');
        assert.ok(intermediateEl, 'Intermediate reply element must exist');
        assert.strictEqual(intermediateEl.classList.contains('is-tombstone'), true, 'Intermediate reply A must render tombstone');
        assert.strictEqual(
            intermediateEl.querySelector('[data-action="report"]'),
            null,
            'Tombstoned intermediate reply A must have NO Report action'
        );

        // 3. Descendant reply B remains active and HAS Report action
        const descendantEl = renderedThread.querySelector('[data-comment-id="' + descendantReplyId + '"]');
        assert.ok(descendantEl, 'Descendant reply B element must exist');
        assert.strictEqual(descendantEl.classList.contains('is-tombstone'), false, 'Descendant reply B must be active');
        const descendantReportBtn = descendantEl.querySelector('[data-action="report"]');
        assert.ok(descendantReportBtn, 'Active descendant reply B must have Report action');

        // 4. Assert B report target is B own commentId and root-id references thread root
        assert.strictEqual(
            descendantReportBtn.getAttribute('data-comment-id'),
            descendantReplyId,
            'B report target must be B own commentId'
        );
        assert.strictEqual(
            descendantReportBtn.getAttribute('data-root-id'),
            activeRootId,
            'B report root-id must reference thread root'
        );

        // 5. Assert B parentCommentId references tombstoned A in fixture
        assert.strictEqual(threadFixture.replies[1].parentCommentId, intermediateReplyId);
    });

    test('52. Unauthenticated guest clicking Báo cáo triggers login redirection with returnTo and does NOT open modal', async () => {
        const doc = createEnvironment({ authenticated: 'false' });
        let modalOpenCalled = false;

        wikiCommentsModule.setReportModalImplementation({
            open: () => {
                modalOpenCalled = true;
                return true;
            }
        });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: ROOT_ID,
                        authorUserId: 'other-user-1',
                        body: 'Public comment',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const reportBtn = doc.querySelector('[data-action="report"]');
        assert.ok(reportBtn, 'Report button should exist for unauthenticated guest on public comment');

        // Click report button as guest
        reportBtn.dispatchEvent({ type: 'click' });

        // Assert redirect to login occurred
        assert.strictEqual(modalOpenCalled, false, 'Modal open must NOT be called for unauthenticated guest');
        assert.ok(doc.defaultView.location.href.includes('/login'), 'Guest must be redirected to login URL');
        assert.ok(doc.defaultView.location.href.includes('returnTo'), 'Login URL must contain returnTo parameter');
    });

    test('53. Authenticated user clicking Báo cáo delegates to CommentReportModal with correct arguments', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        const openCalls = [];

        wikiCommentsModule.setReportModalImplementation({
            open: (params) => {
                openCalls.push(params);
                return true;
            }
        });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: ROOT_ID,
                        authorUserId: 'other-user-1',
                        body: 'Reportable comment',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const reportBtn = doc.querySelector('[data-action="report"]');
        assert.ok(reportBtn);
        assert.strictEqual((reportBtn.listeners.click || []).length, 0, 'No direct click listeners on report button (delegated)');

        // Click report button as authenticated user
        reportBtn.dispatchEvent({ type: 'click' });

        assert.strictEqual(openCalls.length, 1, 'CommentReportModal.open must be called once');
        const capturedOpenParams = openCalls[0];
        assert.strictEqual(capturedOpenParams.commentId, ROOT_ID);
        assert.strictEqual(
            capturedOpenParams.submitUrl,
            '/api/wiki/articles/' + encodeURIComponent(ARTICLE_ID) + '/comments/' + encodeURIComponent(ROOT_ID) + '/reports'
        );
        assert.strictEqual(capturedOpenParams.contextLabel, 'wiki');
        assert.strictEqual(capturedOpenParams.triggerEl, reportBtn);
        assert.strictEqual(typeof capturedOpenParams.onSuccess, 'function');
    });

    test('54. Report submit success callback provides user feedback without leaking internal reportId and disables trigger button', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        let capturedOnSuccess = null;

        wikiCommentsModule.setReportModalImplementation({
            open: (params) => {
                capturedOnSuccess = params.onSuccess;
                return true;
            }
        });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: ROOT_ID,
                        authorUserId: 'other-user-1',
                        body: 'Reportable comment',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const reportBtn = doc.querySelector('[data-action="report"]');
        reportBtn.dispatchEvent({ type: 'click' });

        assert.strictEqual(typeof capturedOnSuccess, 'function');

        // Trigger the onSuccess callback with server response data
        const serverResponse = {
            id: 'secret-report-uuid-8888-9999',
            status: 'PENDING',
            createdAt: '2026-09-20T08:00:00Z'
        };
        capturedOnSuccess(serverResponse);

        // Assert polite user feedback in status region
        const statusEl = doc.getElementById(wikiCommentsModule.STATUS_ID);
        assert.ok(statusEl, 'Status element must exist');
        assert.ok(statusEl.textContent.includes('thành công'), 'Status message must indicate success');
        assert.ok(statusEl.className.includes('wiki-discussion-status--success'), 'Status element must have success class');

        // Assert internal reportId is NOT leaked
        assert.strictEqual(
            statusEl.textContent.includes('secret-report-uuid-8888-9999'),
            false,
            'Internal reportId must NEVER be leaked to user-facing feedback'
        );

        // Assert trigger button feedback
        assert.strictEqual(reportBtn.textContent, 'Đã báo cáo');
        assert.strictEqual(reportBtn.disabled, true);
    });

    test('55. Route isolation: Wiki submit URL strictly adheres to /api/wiki/articles/{articleId}/comments/{commentId}/reports', async () => {
        const doc = createEnvironment({ authenticated: 'true', articleIdVal: 'art-special-uuid' });
        let capturedSubmitUrl = null;

        wikiCommentsModule.setReportModalImplementation({
            open: (params) => {
                capturedSubmitUrl = params.submitUrl;
                return true;
            }
        });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: 'comment-special-1',
                        authorUserId: 'other-user-1',
                        body: 'Route isolation test',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const reportBtn = doc.querySelector('[data-action="report"]');
        reportBtn.dispatchEvent({ type: 'click' });

        assert.strictEqual(capturedSubmitUrl, '/api/wiki/articles/art-special-uuid/comments/comment-special-1/reports');
        assert.ok(!capturedSubmitUrl.includes('/novel/'), 'Must NOT target novel routes');
        assert.ok(!capturedSubmitUrl.includes('/chapter/'), 'Must NOT target chapter routes');
    });

    test('56. Module integration defaults to CommentReportModal singleton', () => {
        wikiCommentsModule.resetState();
        const resolvedModal = wikiCommentsModule.getReportModal();
        assert.strictEqual(resolvedModal, CommentReportModal, 'Production code must resolve to CommentReportModal singleton');
        assert.strictEqual(typeof resolvedModal.open, 'function', 'CommentReportModal.open must be a callable singleton function');
    });

    test('57. Unavailable or non-callable CommentReportModal fails safely without throw or navigation', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        wikiCommentsModule.setReportModalImplementation({});

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: ROOT_ID,
                        authorUserId: 'other-user-1',
                        body: 'Reportable comment',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const reportBtn = doc.querySelector('[data-action="report"]');
        assert.ok(reportBtn);

        const initialHref = doc.defaultView.location.href;
        assert.doesNotThrow(() => {
            reportBtn.dispatchEvent({ type: 'click', target: reportBtn });
        });

        assert.strictEqual(doc.defaultView.location.href, initialHref, 'Must not navigate when modal is non-callable');
        assert.strictEqual(wikiCommentsModule.openReportModal(ROOT_ID, reportBtn, doc), false, 'openReportModal must return false when modal is non-callable');
    });
});

// ============================================================================
// MS-05E / E8C4-UX3: Wiki Article Comments Shared CommentPresentation Migration
// ============================================================================

describe('MS-05E / E8C4-UX3: Wiki Article Comments Shared CommentPresentation Migration', () => {
    beforeEach(() => {
        wikiCommentsModule.resetState();
    });

    test('A. Active root comment renders via CommentPresentation with canonical classes (.kl-comment, .kl-comment__header, .kl-comment__author, .kl-comment__time, .kl-comment__body, .kl-comment__overflow, .kl-comment__primary-actions) and legacy classes (.wiki-comment, .wiki-comment--root, .wiki-comment-header, etc.)', () => {
        const doc = new FakeDocument();
        const rootComment = {
            id: 'root-ux3-1',
            authorUserId: 'user-ux3-1',
            body: 'Active root content for UX3',
            tombstone: false,
            createdAt: '2026-09-20T10:00:00Z',
            updatedAt: '2026-09-20T10:00:00Z',
            author: { displayName: 'Thư Sinh', avatarUrl: null },
            canEdit: true,
            canDelete: true
        };

        const rootEl = wikiCommentsModule.renderComment(rootComment, 'root-ux3-1', null, false, doc);
        assert.ok(rootEl, 'Root element must be rendered');

        // Root element canonical & legacy classes
        assert.ok(rootEl.classList.contains('kl-comment'), 'Must have canonical .kl-comment');
        assert.ok(rootEl.classList.contains('wiki-comment'), 'Must have legacy .wiki-comment');
        assert.ok(rootEl.classList.contains('wiki-comment--root'), 'Must have legacy .wiki-comment--root');
        assert.strictEqual(rootEl.getAttribute('data-comment-id'), 'root-ux3-1');
        assert.strictEqual(rootEl.getAttribute('data-author-user-id'), 'user-ux3-1');

        // Header
        const header = rootEl.querySelector('.kl-comment__header');
        assert.ok(header, 'Must have canonical .kl-comment__header');
        assert.ok(header.classList.contains('wiki-comment-header'), 'Must have legacy .wiki-comment-header');

        // Author
        const author = header.querySelector('.kl-comment__author');
        assert.ok(author, 'Must have canonical .kl-comment__author');
        assert.ok(author.classList.contains('wiki-comment-author'), 'Must have legacy .wiki-comment-author');
        assert.strictEqual(author.textContent, 'Thư Sinh');

        // Time
        const time = header.querySelector('.kl-comment__time');
        assert.ok(time, 'Must have canonical .kl-comment__time');
        assert.ok(time.classList.contains('wiki-comment-time'), 'Must have legacy .wiki-comment-time');

        // Overflow menu & accessibility trigger contract
        const overflow = header.querySelector('.kl-comment__overflow');
        assert.ok(overflow, 'Must have canonical .kl-comment__overflow');
        assert.ok(overflow.classList.contains('wiki-comment-actions-menu'), 'Must have legacy .wiki-comment-actions-menu');
        const menuTrigger = overflow.querySelector('.kl-comment__menu-trigger');
        assert.ok(menuTrigger, 'Menu trigger button must exist');
        assert.strictEqual(menuTrigger.getAttribute('aria-label'), 'Mở menu bình luận', 'Trigger must have canonical aria-label');
        assert.strictEqual(menuTrigger.getAttribute('aria-haspopup'), 'menu', 'Trigger must have aria-haspopup="menu"');
        assert.strictEqual(menuTrigger.getAttribute('aria-expanded'), 'false', 'Trigger must have initial aria-expanded="false"');
        assert.strictEqual(menuTrigger.textContent, '⋯', 'Trigger must have canonical dots text "⋯"');

        // Body
        const body = rootEl.querySelector('.kl-comment__body');
        assert.ok(body, 'Must have canonical .kl-comment__body');
        assert.ok(body.classList.contains('wiki-comment-body'), 'Must have legacy .wiki-comment-body');
        assert.ok(body.textContent.includes('Active root content for UX3'));

        // Primary actions
        const primaryActions = rootEl.querySelector('.kl-comment__primary-actions');
        assert.ok(primaryActions, 'Must have canonical .kl-comment__primary-actions');
        assert.ok(primaryActions.classList.contains('wiki-comment-actions'), 'Must have legacy .wiki-comment-actions');
    });

    test('B. Active reply comment renders via CommentPresentation with canonical and legacy classes (.wiki-comment--reply)', () => {
        const doc = new FakeDocument();
        const replyComment = {
            id: 'reply-ux3-1',
            parentCommentId: 'root-ux3-1',
            body: 'Active reply content for UX3',
            tombstone: false,
            createdAt: '2026-09-20T10:05:00Z',
            updatedAt: '2026-09-20T10:05:00Z',
            author: { displayName: 'Độc Giả', avatarUrl: null },
            canEdit: false,
            canDelete: false
        };

        const replyEl = wikiCommentsModule.renderComment(replyComment, 'root-ux3-1', null, true, doc);
        assert.ok(replyEl, 'Reply element must be rendered');

        assert.ok(replyEl.classList.contains('kl-comment'));
        assert.ok(replyEl.classList.contains('wiki-comment'));
        assert.ok(replyEl.classList.contains('wiki-comment--reply'));
        assert.strictEqual(replyEl.getAttribute('data-comment-id'), 'reply-ux3-1');
        assert.strictEqual(replyEl.getAttribute('data-reply-id'), 'reply-ux3-1');

        const body = replyEl.querySelector('.kl-comment__body');
        assert.ok(body.textContent.includes('Active reply content for UX3'));
    });

    test('C. Tombstone reply comment renders via CommentPresentation with tombstone class (.kl-comment--tombstone, .is-tombstone), tombstone text "Bình luận đã bị xóa.", and NO author header, avatar, actions menu, or reply button', () => {
        const doc = new FakeDocument();
        const tombstoneReply = {
            id: 'reply-tomb-1',
            parentCommentId: 'root-ux3-1',
            tombstone: true,
            status: 'DELETED'
        };

        const replyEl = wikiCommentsModule.renderComment(tombstoneReply, 'root-ux3-1', null, true, doc);
        assert.ok(replyEl, 'Tombstone reply element must be rendered');

        assert.ok(replyEl.classList.contains('kl-comment--tombstone'));
        assert.ok(replyEl.classList.contains('is-tombstone'));
        assert.ok(replyEl.textContent.includes('Bình luận đã bị xóa.'));

        // No header, avatar, overflow menu, or primary action buttons
        assert.strictEqual(replyEl.querySelector('.kl-comment__header'), null);
        assert.strictEqual(replyEl.querySelector('.kl-comment__avatar'), null);
        assert.strictEqual(replyEl.querySelector('.kl-comment__overflow'), null);
        assert.strictEqual(replyEl.querySelector('.kl-comment__primary-actions'), null);
        assert.strictEqual(replyEl.querySelector('.wiki-reply-composer-slot'), null);
    });

    test('D. Deleted root suppresses entire thread: renderThread returns null for deleted root (tombstone: true or status: "DELETED"), and thread list does not append anything', async () => {
        const doc = createEnvironment();
        const threadWithDeletedRoot = {
            root: {
                id: 'root-deleted-1',
                tombstone: true,
                status: 'DELETED',
                body: null
            },
            replies: [
                {
                    id: 'reply-orphaned-1',
                    parentCommentId: 'root-deleted-1',
                    body: 'Orphaned reply',
                    tombstone: false,
                    author: { displayName: 'User' }
                }
            ]
        };

        // Direct renderThread check
        const directThreadEl = wikiCommentsModule.renderThread(threadWithDeletedRoot, doc);
        assert.strictEqual(directThreadEl, null, 'renderThread must return null for deleted root');

        // Feed load integration check
        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [threadWithDeletedRoot],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const listEl = doc.getElementById(wikiCommentsModule.THREAD_LIST_ID);
        assert.strictEqual(listEl.childNodes.length, 0, 'Thread with deleted root must not be appended to thread list');
    });

    test('E. Deleted intermediate reply tombstone with active descendant: intermediate reply renders as tombstone, active descendant reply remains actionable with reply button and overflow menu', () => {
        const doc = new FakeDocument();
        const thread = {
            root: {
                id: 'root-ux3-active',
                body: 'Active root',
                tombstone: false,
                author: { displayName: 'Author 1' }
            },
            replies: [
                {
                    id: 'reply-intermediate-tomb',
                    parentCommentId: 'root-ux3-active',
                    tombstone: true,
                    status: 'DELETED'
                },
                {
                    id: 'reply-active-descendant',
                    parentCommentId: 'reply-intermediate-tomb',
                    body: 'Active descendant replying to tombstone',
                    tombstone: false,
                    author: { displayName: 'Descendant User' },
                    canEdit: false,
                    canDelete: false
                }
            ]
        };

        const threadEl = wikiCommentsModule.renderThread(thread, doc);
        assert.ok(threadEl, 'Thread article must be rendered');

        const repliesContainer = threadEl.querySelector('.wiki-thread-replies');
        assert.ok(repliesContainer, 'Replies container must exist');

        // Intermediate reply is tombstone
        const tombEl = repliesContainer.querySelector('[data-reply-id="reply-intermediate-tomb"]');
        assert.ok(tombEl, 'Intermediate tombstone reply must render');
        assert.ok(tombEl.classList.contains('is-tombstone'));
        assert.ok(tombEl.textContent.includes('Bình luận đã bị xóa.'));
        assert.strictEqual(tombEl.querySelector('.kl-comment__overflow'), null);
        assert.strictEqual(tombEl.querySelector('.kl-comment__primary-actions'), null);

        // Active descendant is fully actionable
        const descEl = repliesContainer.querySelector('[data-reply-id="reply-active-descendant"]');
        assert.ok(descEl, 'Active descendant reply must render');
        assert.strictEqual(descEl.classList.contains('is-tombstone'), false);
        assert.ok(descEl.querySelector('.kl-comment__overflow'), 'Descendant must have overflow actions menu');
        assert.ok(descEl.querySelector('[data-action="reply"]'), 'Descendant must have reply primary action button');
        assert.ok(descEl.querySelector('[data-action="report"]'), 'Descendant must have report overflow action');
    });

    test('F. Primary "Phản hồi" action button renders in .kl-comment__primary-actions (outside overflow menu) with correct attributes (data-action="reply", data-comment-id, data-root-id, data-author-name)', () => {
        const doc = new FakeDocument();
        const rootComment = {
            id: 'root-btn-test',
            body: 'Test comment for primary action',
            tombstone: false,
            author: { displayName: 'Nguyễn Du' }
        };

        const rootEl = wikiCommentsModule.renderComment(rootComment, 'root-btn-test', null, false, doc);
        assert.ok(rootEl);

        const primaryActions = rootEl.querySelector('.kl-comment__primary-actions');
        assert.ok(primaryActions, 'Primary actions container must exist');

        const replyBtn = primaryActions.querySelector('[data-action="reply"]');
        assert.ok(replyBtn, 'Reply button must exist in primary actions');
        assert.strictEqual(replyBtn.textContent, 'Phản hồi');
        assert.strictEqual(replyBtn.getAttribute('data-comment-id'), 'root-btn-test');
        assert.strictEqual(replyBtn.getAttribute('data-root-id'), 'root-btn-test');
        assert.strictEqual(replyBtn.getAttribute('data-author-name'), 'Nguyễn Du');

        // Must NOT be inside the overflow menu
        const overflow = rootEl.querySelector('.kl-comment__overflow');
        if (overflow) {
            assert.strictEqual(overflow.querySelector('[data-action="reply"]'), null, 'Reply button must not be inside overflow menu');
        }
    });

    test('G. Overflow menu items follow canonical order: view-revisions ("Xem lịch sử chỉnh sửa") -> edit ("Chỉnh sửa") -> delete ("Xóa") -> separator -> report ("Báo cáo")', () => {
        const descriptors = wikiCommentsModule.buildOverflowActionDescriptors({
            commentId: 'c-all-1',
            rootCommentId: 'r-1',
            isEdited: true,
            canEdit: true,
            canDelete: true,
            canReport: true
        });

        assert.strictEqual(descriptors.length, 4);
        assert.strictEqual(descriptors[0].key, 'view-revisions');
        assert.strictEqual(descriptors[0].label, 'Xem lịch sử chỉnh sửa');
        assert.strictEqual(descriptors[1].key, 'edit');
        assert.strictEqual(descriptors[1].label, 'Chỉnh sửa');
        assert.strictEqual(descriptors[2].key, 'delete');
        assert.strictEqual(descriptors[2].label, 'Xóa');
        assert.strictEqual(descriptors[2].danger, true);
        assert.strictEqual(descriptors[3].key, 'report');
        assert.strictEqual(descriptors[3].label, 'Báo cáo');
        assert.strictEqual(descriptors[3].danger, true);
        assert.strictEqual(descriptors[3].separatorBefore, true);
    });

    test('H. Non-owner permissions: canEdit: false, canDelete: false, status != "DELETED" -> overflow menu has report action, no edit or delete', () => {
        const descriptors = wikiCommentsModule.buildOverflowActionDescriptors({
            commentId: 'c-guest-1',
            rootCommentId: 'r-1',
            isEdited: false,
            canEdit: false,
            canDelete: false,
            canReport: true
        });

        assert.strictEqual(descriptors.length, 1);
        assert.strictEqual(descriptors[0].key, 'report');
        assert.strictEqual(descriptors[0].label, 'Báo cáo');
        assert.strictEqual(descriptors[0].separatorBefore, false, 'No separator if report is the sole item');
    });

    test('I. Owner permissions: canEdit: true, canDelete: true -> overflow menu has edit and delete actions, no report action', () => {
        const descriptors = wikiCommentsModule.buildOverflowActionDescriptors({
            commentId: 'c-owner-1',
            rootCommentId: 'r-1',
            isEdited: false,
            canEdit: true,
            canDelete: true,
            canReport: false
        });

        assert.strictEqual(descriptors.length, 2);
        assert.strictEqual(descriptors[0].key, 'edit');
        assert.strictEqual(descriptors[1].key, 'delete');
    });

    test('J. Edited comment shows "đã chỉnh sửa" indicator in header and includes view-revisions in overflow menu', () => {
        const doc = new FakeDocument();
        const editedComment = {
            id: 'c-edited-1',
            body: 'Edited comment content',
            tombstone: false,
            edited: true,
            author: { displayName: 'Editor' },
            canEdit: true,
            canDelete: true
        };

        const commentEl = wikiCommentsModule.renderComment(editedComment, 'c-edited-1', null, false, doc);
        assert.ok(commentEl);

        const editedBadge = commentEl.querySelector('.kl-comment__edited');
        assert.ok(editedBadge, 'Must render .kl-comment__edited');
        assert.strictEqual(editedBadge.textContent, 'đã chỉnh sửa');

        const historyItem = commentEl.querySelector('[data-action="view-revisions"]');
        assert.ok(historyItem, 'Must have view-revisions in overflow menu');
        assert.strictEqual(historyItem.textContent, 'Xem lịch sử chỉnh sửa');
    });

    test('K. Unedited comment does not show "đã chỉnh sửa" and excludes view-revisions from overflow menu', () => {
        const doc = new FakeDocument();
        const uneditedComment = {
            id: 'c-unedited-1',
            body: 'Fresh unedited comment',
            tombstone: false,
            edited: false,
            author: { displayName: 'Fresh' },
            canEdit: true,
            canDelete: true
        };

        const commentEl = wikiCommentsModule.renderComment(uneditedComment, 'c-unedited-1', null, false, doc);
        assert.ok(commentEl);

        assert.strictEqual(commentEl.querySelector('.kl-comment__edited'), null, 'Must NOT render edited badge');
        assert.strictEqual(commentEl.querySelector('[data-action="view-revisions"]'), null, 'Must NOT have view-revisions in overflow menu');
    });

    test('L. Separator in overflow menu: rendered before report action when preceded by history, edit, or delete; no separator if report is sole action', () => {
        const withPreceding = wikiCommentsModule.buildOverflowActionDescriptors({
            canEdit: true,
            canReport: true
        });
        const reportWithPreceding = withPreceding.find(d => d.key === 'report');
        assert.strictEqual(reportWithPreceding.separatorBefore, true);

        const soleReport = wikiCommentsModule.buildOverflowActionDescriptors({
            canReport: true
        });
        const reportSole = soleReport.find(d => d.key === 'report');
        assert.strictEqual(reportSole.separatorBefore, false);
    });

    test('M. Nested reply mention: reply to non-root parent renders @ParentName mention in body (.wiki-comment-reply-mention)', () => {
        const doc = new FakeDocument();
        const thread = {
            root: {
                id: 'root-100',
                body: 'Root 100',
                author: { displayName: 'Parent Root' }
            },
            replies: [
                {
                    id: 'reply-101',
                    parentCommentId: 'root-100',
                    body: 'Reply to root',
                    author: { displayName: 'Intermediate Parent' }
                },
                {
                    id: 'reply-102',
                    parentCommentId: 'reply-101',
                    body: 'Nested reply content',
                    author: { displayName: 'Nested Author' }
                }
            ]
        };

        const threadEl = wikiCommentsModule.renderThread(thread, doc);
        assert.ok(threadEl);

        const nestedEl = threadEl.querySelector('[data-reply-id="reply-102"]');
        assert.ok(nestedEl);

        const mention = nestedEl.querySelector('.wiki-comment-reply-mention');
        assert.ok(mention, 'Must render reply mention');
        assert.strictEqual(mention.textContent, '@Intermediate Parent');

        const replyText = nestedEl.querySelector('.wiki-comment-reply-text');
        assert.ok(replyText);
        assert.strictEqual(replyText.textContent, 'Nested reply content');
    });

    test('N. Direct reply to root does not render parent mention in body', () => {
        const doc = new FakeDocument();
        const thread = {
            root: {
                id: 'root-200',
                body: 'Root 200',
                author: { displayName: 'Root Author' }
            },
            replies: [
                {
                    id: 'reply-201',
                    parentCommentId: 'root-200',
                    body: 'Direct reply to root',
                    author: { displayName: 'Direct Responder' }
                }
            ]
        };

        const threadEl = wikiCommentsModule.renderThread(thread, doc);
        assert.ok(threadEl);

        const directReplyEl = threadEl.querySelector('[data-reply-id="reply-201"]');
        assert.ok(directReplyEl);

        assert.strictEqual(directReplyEl.querySelector('.wiki-comment-reply-mention'), null, 'Direct root reply must not render mention');
        const bodyEl = directReplyEl.querySelector('.kl-comment__body');
        assert.strictEqual(bodyEl.textContent, 'Direct reply to root');
    });

    test('O. Inline reply composer slot (.wiki-reply-composer-slot, data-reply-slot) is preserved on active root and reply comments', () => {
        const doc = new FakeDocument();
        const rootComment = {
            id: 'root-slot-test',
            body: 'Root slot check',
            author: { displayName: 'User' }
        };
        const replyComment = {
            id: 'reply-slot-test',
            parentCommentId: 'root-slot-test',
            body: 'Reply slot check',
            author: { displayName: 'User 2' }
        };

        const rootEl = wikiCommentsModule.renderComment(rootComment, 'root-slot-test', null, false, doc);
        assert.ok(rootEl.querySelector('[data-reply-slot="root-slot-test"]'), 'Root must have data-reply-slot');

        const replyEl = wikiCommentsModule.renderComment(replyComment, 'root-slot-test', null, true, doc);
        assert.ok(replyEl.querySelector('[data-reply-slot="reply-slot-test"]'), 'Reply must have data-reply-slot');
    });

    test('P. Inline edit composer target ([data-body-container]) is preserved on comment body', () => {
        const doc = new FakeDocument();
        const comment = {
            id: 'comment-body-target-test',
            body: 'Body container test',
            author: { displayName: 'User' }
        };

        const commentEl = wikiCommentsModule.renderComment(comment, 'comment-body-target-test', null, false, doc);
        const bodyContainer = commentEl.querySelector('[data-body-container="comment-body-target-test"]');
        assert.ok(bodyContainer, 'Body container must have data-body-container attribute matching commentId');
    });

    test('Q. Delegated click on report menu item opens CommentReportModal with correct parameters (commentId, submitUrl, contextLabel: "wiki", triggerEl)', async () => {
        const doc = createEnvironment({ authenticated: 'true', articleIdVal: 'art-report-delegate' });
        const openCalls = [];

        wikiCommentsModule.setReportModalImplementation({
            open: (params) => {
                openCalls.push(params);
                return true;
            }
        });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: 'comment-rep-del-1',
                        authorUserId: 'other-user',
                        body: 'Reportable comment',
                        tombstone: false,
                        canEdit: false,
                        canDelete: false
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const reportBtn = doc.querySelector('[data-action="report"]');
        assert.ok(reportBtn, 'Report button must exist');
        assert.strictEqual((reportBtn.listeners.click || []).length, 0, 'No direct click listeners on report button (delegated)');

        reportBtn.dispatchEvent({ type: 'click', target: reportBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(openCalls.length, 1, 'CommentReportModal.open must be invoked once');
        const capturedParams = openCalls[0];
        assert.strictEqual(capturedParams.commentId, 'comment-rep-del-1');
        assert.strictEqual(capturedParams.submitUrl, '/api/wiki/articles/art-report-delegate/comments/comment-rep-del-1/reports');
        assert.strictEqual(capturedParams.contextLabel, 'wiki');
        assert.strictEqual(capturedParams.triggerEl, reportBtn);
    });

    test('R. Delegated click on view-revisions menu item opens revision history modal', async () => {
        const doc = createEnvironment();
        let revisionsFetched = false;

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/revisions')) {
                revisionsFetched = true;
                return {
                    status: 200,
                    json: async () => ({
                        items: [{ revisionNumber: 1, body: 'Old version', createdAt: '2026-09-19T10:00:00Z' }],
                        page: 0,
                        size: 20,
                        hasNext: false
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: 'c-rev-1',
                            body: 'Current body',
                            tombstone: false,
                            edited: true,
                            author: { displayName: 'Scholar' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const revBtn = doc.querySelector('[data-action="view-revisions"]');
        assert.ok(revBtn, 'View revisions button must exist');

        revBtn.dispatchEvent({ type: 'click', target: revBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(revisionsFetched, true, 'Clicking view-revisions must trigger revisions fetch');
        const { modal } = wikiCommentsModule.getHistoryElements(doc);
        assert.strictEqual(modal.hidden, false, 'History modal must be open');
    });

    test('S. Delegated click on edit menu item opens inline edit composer', async () => {
        const doc = createEnvironment({ authenticated: 'true' });

        wikiCommentsModule.setFetchImplementation(async () => ({
            status: 200,
            json: async () => ({
                threads: [{
                    root: {
                        id: 'c-edit-del-1',
                        body: 'Editable body',
                        tombstone: false,
                        canEdit: true,
                        canDelete: true,
                        author: { displayName: 'Author' }
                    },
                    replies: []
                }],
                threadCount: 1,
                commentCount: 1,
                page: 0,
                size: 20,
                hasNext: false
            })
        }));

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const editBtn = doc.querySelector('[data-action="edit"]');
        assert.ok(editBtn, 'Edit button must exist');

        editBtn.dispatchEvent({ type: 'click', target: editBtn });
        await new Promise(process.nextTick);

        const bodyContainer = doc.querySelector('[data-body-container="c-edit-del-1"]');
        assert.ok(bodyContainer.querySelector('.wiki-inline-edit-form'), 'Inline edit form must be rendered in body container');
        assert.strictEqual(wikiCommentsModule.getState().activeEditCommentId, 'c-edit-del-1');
    });

    test('T. Delegated click on delete menu item invokes confirm and sends DELETE request', async () => {
        const doc = createEnvironment({ authenticated: 'true' });
        let confirmPrompted = false;
        let deleteExecuted = false;

        wikiCommentsModule.setConfirmImplementation(() => {
            confirmPrompted = true;
            return true;
        });

        wikiCommentsModule.setFetchImplementation(async (url, opts) => {
            if (opts && opts.method === 'DELETE') {
                deleteExecuted = true;
                return { status: 204 };
            }
            return {
                status: 200,
                json: async () => ({
                    threads: [{
                        root: {
                            id: 'c-delete-del-1',
                            body: 'Deletable body',
                            tombstone: false,
                            canEdit: true,
                            canDelete: true,
                            author: { displayName: 'Author' }
                        },
                        replies: []
                    }],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        const deleteBtn = doc.querySelector('[data-action="delete"]');
        assert.ok(deleteBtn, 'Delete button must exist');

        deleteBtn.dispatchEvent({ type: 'click', target: deleteBtn });
        await new Promise(process.nextTick);

        assert.strictEqual(confirmPrompted, true, 'Confirm must be prompted');
        assert.strictEqual(deleteExecuted, true, 'DELETE HTTP request must be executed');
    });

    test('U. Overflow menu state delegation: openMenu, closeActiveMenu, getActiveOpenMenu delegate to CommentPresentation; single open menu invariant enforced', () => {
        const doc = new FakeDocument();
        const root1 = { id: 'm-1', body: 'Comment 1', author: { displayName: 'U1' }, canEdit: true };
        const root2 = { id: 'm-2', body: 'Comment 2', author: { displayName: 'U2' }, canEdit: true };

        const el1 = wikiCommentsModule.renderComment(root1, 'm-1', null, false, doc);
        const el2 = wikiCommentsModule.renderComment(root2, 'm-2', null, false, doc);
        doc.body.appendChild(el1);
        doc.body.appendChild(el2);

        const trigger1 = el1.querySelector('.kl-comment__menu-trigger');
        const trigger2 = el2.querySelector('.kl-comment__menu-trigger');
        const popover1 = el1.querySelector('.kl-comment__menu');
        const popover2 = el2.querySelector('.kl-comment__menu');

        // Initially no active menu
        assert.strictEqual(wikiCommentsModule.getActiveOpenMenu(), null);

        // Click trigger 1 -> opens menu 1
        trigger1.dispatchEvent({ type: 'click', target: trigger1 });
        assert.ok(wikiCommentsModule.getActiveOpenMenu());
        assert.strictEqual(trigger1.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popover1.hidden, false);

        // Click trigger 2 -> closes menu 1, opens menu 2 (single open menu invariant)
        trigger2.dispatchEvent({ type: 'click', target: trigger2 });
        assert.strictEqual(trigger1.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(popover1.hidden, true);
        assert.strictEqual(trigger2.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popover2.hidden, false);

        // Close via module method
        wikiCommentsModule.closeActiveMenu(false);
        assert.strictEqual(wikiCommentsModule.getActiveOpenMenu(), null);
        assert.strictEqual(trigger2.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(popover2.hidden, true);
    });

    test('V. Missing CommentPresentation fail-safe: renderComment and renderThread safely return null when CommentPresentation is null/unavailable without throwing or crashing', () => {
        const doc = new FakeDocument();
        const testComment = {
            id: 'c-fail-safe',
            body: 'Fail safe test',
            author: { displayName: 'User' }
        };
        const testThread = {
            root: testComment,
            replies: []
        };

        wikiCommentsModule.setCommentPresentation(null);

        // Neither throws an exception, both safely return null
        let renderedComment = undefined;
        let renderedThread = undefined;

        assert.doesNotThrow(() => {
            renderedComment = wikiCommentsModule.renderComment(testComment, 'c-fail-safe', null, false, doc);
            renderedThread = wikiCommentsModule.renderThread(testThread, doc);
        });

        assert.strictEqual(renderedComment, null, 'renderComment must return null when presentation unavailable');
        assert.strictEqual(renderedThread, null, 'renderThread must return null when presentation unavailable');
    });

    test('W. Active root and reply comments render reaction widget host when reactionSummary is present', () => {
        const doc = new FakeDocument();
        const testComment = {
            id: 'c-reaction-1',
            body: 'Comment with reactions',
            author: { displayName: 'User 1' },
            reactionSummary: {
                targetType: 'COMMENT',
                targetId: 'c-reaction-1',
                currentUserReaction: 'LIKE',
                totalCount: 5,
                counts: { LIKE: 4, LOVE: 1, FIRE: 0, HAHA: 0, SAD: 0 }
            }
        };

        const commentEl = wikiCommentsModule.renderComment(testComment, 'c-reaction-1', null, false, doc);
        assert.ok(commentEl);

        const widgetHost = commentEl.querySelector('[data-reaction-widget]');
        assert.ok(widgetHost, 'Reaction widget host must be present');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-target-type'), 'COMMENT');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-target-id'), 'c-reaction-1');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-current'), 'LIKE');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-total'), '5');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-count-like'), '4');
        assert.strictEqual(widgetHost.getAttribute('data-reaction-count-love'), '1');
    });

    test('X. When reactionSummary is omitted or null, no reaction widget is rendered', () => {
        const doc = new FakeDocument();
        const testComment = {
            id: 'c-no-reaction',
            body: 'Comment without reactions',
            author: { displayName: 'User 2' }
        };

        const commentEl = wikiCommentsModule.renderComment(testComment, 'c-no-reaction', null, false, doc);
        assert.ok(commentEl);

        const widgetHost = commentEl.querySelector('[data-reaction-widget]');
        assert.strictEqual(widgetHost, null, 'Reaction widget host must NOT be present when summary is omitted');
    });

    test('Y. Tombstone comment does NOT render reaction widget host', () => {
        const doc = new FakeDocument();
        const tombstoneComment = {
            id: 'c-tombstone',
            tombstone: true,
            reactionSummary: {
                targetType: 'COMMENT',
                targetId: 'c-tombstone',
                totalCount: 3,
                counts: { LIKE: 3 }
            }
        };

        const commentEl = wikiCommentsModule.renderComment(tombstoneComment, 'c-tombstone', null, false, doc);
        assert.ok(commentEl);

        const widgetHost = commentEl.querySelector('[data-reaction-widget]');
        assert.strictEqual(widgetHost, null, 'Tombstone comment must never render reaction widget');
    });
});

describe('MS-05E / E8E-5C2B Wiki Public Discussion Exact Comment Context Focus (Cases A-L)', () => {
    function setupWikiDom(doc, articleId = 'article-uuid-1', authenticated = false) {
        const section = doc.createElement('section', {
            id: 'wikiDiscussion',
            'data-article-id': articleId,
            'data-authenticated': authenticated ? 'true' : 'false',
            'data-login-url': '/login'
        });
        section.className = 'wiki-discussion-section';

        const badge = doc.createElement('span', { id: 'wikiDiscussionCountBadge' });
        const list = doc.createElement('div', { id: 'wikiDiscussionThreadList' });
        const status = doc.createElement('div', { id: 'wikiDiscussionStatus' });
        const footer = doc.createElement('div', { id: 'wikiDiscussionFooter' });
        const moreBtn = doc.createElement('button', { id: 'wikiDiscussionLoadMoreBtn' });
        footer.appendChild(moreBtn);

        section.appendChild(badge);
        section.appendChild(status);
        section.appendChild(list);
        section.appendChild(footer);
        doc.body.appendChild(section);

        doc.registerElement('wikiDiscussion', section);
        doc.registerElement('wikiDiscussionCountBadge', badge);
        doc.registerElement('wikiDiscussionThreadList', list);
        doc.registerElement('wikiDiscussionStatus', status);
        doc.registerElement('wikiDiscussionFooter', footer);
        doc.registerElement('wikiDiscussionLoadMoreBtn', moreBtn);

        return { section, badge, list, status, footer, moreBtn };
    }

    beforeEach(() => {
        wikiCommentsModule.resetState();
    });

    test('Case A: No deep link parameters -> normal feed load, no /thread request, no highlight', async () => {
        const doc = new FakeDocument();
        setupWikiDom(doc);

        let threadEndpointCalled = false;
        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                threadEndpointCalled = true;
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 1, commentCount: 1, threads: [
                    { root: { id: 'r-1', body: 'Normal root', author: { displayName: 'User' } }, replies: [] }
                ] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(threadEndpointCalled, false, 'Exact /thread endpoint must NOT be called');
        assert.strictEqual(wikiCommentsModule.getHighlightedElement(), null, 'No element should be highlighted');
        assert.strictEqual(wikiCommentsModule.getState().pendingDeepLink, null);
    });

    test('Case B: Root comment already rendered in feed -> focused, highlighted, no /thread fetch, params scrubbed after', async () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=r-target&threadId=r-target#wikiDiscussion');
        setupWikiDom(doc);

        let threadEndpointCalled = false;
        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                threadEndpointCalled = true;
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 1, commentCount: 1, threads: [
                    { root: { id: 'r-target', body: 'Target root', author: { displayName: 'Author 1' } }, replies: [] }
                ] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        assert.strictEqual(threadEndpointCalled, false, 'Should not request /thread when already in feed');
        const highlighted = wikiCommentsModule.getHighlightedElement();
        assert.ok(highlighted, 'Target root comment must be highlighted');
        assert.ok(highlighted.classList.contains('is-restored-target'));
        assert.strictEqual(highlighted.getAttribute('tabindex'), '-1', 'Accessible tabindex must be set to -1');
        assert.strictEqual(highlighted.isFocused, true, 'Target must receive focus');
        assert.strictEqual(highlighted.scrolledIntoView, true, 'Target must be scrolled into view');
        assert.strictEqual(highlighted.lastScrollOptions?.block, 'center', 'Scroll block must be center');

        // URL scrubbed after terminal outcome
        assert.strictEqual(doc.defaultView.location.search, '', 'commentId and threadId must be scrubbed from search');
        assert.strictEqual(doc.defaultView.location.hash, '#wikiDiscussion', 'hash must be preserved');
    });

    test('Case C: Off-page root comment -> exact thread fetched, prepended to thread list, focused, highlighted, params scrubbed', async () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=r-offpage&threadId=r-offpage');
        const { list } = setupWikiDom(doc);

        let requestedThreadUrl = null;
        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                requestedThreadUrl = url;
                return {
                    status: 200,
                    json: async () => ({
                        root: { id: 'r-offpage', body: 'Offpage Root Body', author: { displayName: 'Offpage Author' } },
                        replies: []
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: true, threadCount: 5, commentCount: 5, threads: [
                    { root: { id: 'r-page0', body: 'Page 0 root', author: { displayName: 'P0' } }, replies: [] }
                ] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        assert.ok(requestedThreadUrl, '/thread endpoint must be requested');
        assert.ok(requestedThreadUrl.includes('/comments/r-offpage/thread'));

        const highlighted = wikiCommentsModule.getHighlightedElement();
        assert.ok(highlighted, 'Target off-page root comment must be highlighted');
        assert.ok(highlighted.classList.contains('is-restored-target'));
        assert.strictEqual(highlighted.isFocused, true);
        assert.strictEqual(highlighted.scrolledIntoView, true);

        // Prepend check: off-page thread must be first child in thread list
        const firstCard = list.childNodes[0];
        assert.strictEqual(firstCard.getAttribute('data-thread-id'), 'r-offpage');

        // URL scrubbed
        assert.strictEqual(doc.defaultView.location.search, '');
    });

    test('Case D: Reply comment inside exact thread -> exact reply focused, highlighted, params scrubbed', async () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=rep-target&threadId=r-root');
        setupWikiDom(doc);

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                return {
                    status: 200,
                    json: async () => ({
                        root: { id: 'r-root', body: 'Root Body', author: { displayName: 'Root Author' } },
                        replies: [
                            { id: 'rep-other', body: 'Other reply', author: { displayName: 'User 2' }, parentCommentId: 'r-root' },
                            { id: 'rep-target', body: 'Target reply body', author: { displayName: 'Target Author' }, parentCommentId: 'r-root' }
                        ]
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 0, commentCount: 0, threads: [] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        const highlighted = wikiCommentsModule.getHighlightedElement();
        assert.ok(highlighted, 'Target reply must be highlighted');
        assert.strictEqual(highlighted.getAttribute('data-comment-id'), 'rep-target');
        assert.strictEqual(highlighted.getAttribute('data-reply-id'), 'rep-target');
        assert.ok(highlighted.classList.contains('is-restored-target'));
        assert.strictEqual(highlighted.isFocused, true);
        assert.strictEqual(highlighted.scrolledIntoView, true);
        assert.strictEqual(highlighted.lastScrollOptions?.block, 'center');

        assert.strictEqual(doc.defaultView.location.search, '');
    });

    test('Case E: Duplicate prevention -> existing thread card is not duplicated', async () => {
        const doc = new FakeDocument();
        const { list } = setupWikiDom(doc);

        let threadRequests = 0;
        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                threadRequests++;
                return {
                    status: 200,
                    json: async () => ({
                        root: { id: 'r-1', body: 'Root 1', author: { displayName: 'Author' } },
                        replies: []
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 1, commentCount: 1, threads: [
                    { root: { id: 'r-1', body: 'Root 1', author: { displayName: 'Author' } }, replies: [] }
                ] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(list.childNodes.length, 1);

        // Explicitly trigger resolveDeepLink for already existing thread
        await wikiCommentsModule.resolveDeepLink({ commentId: 'r-1', threadId: 'r-1' }, doc);
        assert.strictEqual(threadRequests, 0, 'No /thread fetch should occur for already rendered thread');
        assert.strictEqual(list.childNodes.length, 1, 'Thread list must still have exactly 1 card');
    });

    test('Case F: Deleted or missing target (404 / tombstone root) -> fallback to #wikiDiscussion, no throw, params scrubbed', async () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=r-404&threadId=r-404');
        const { section } = setupWikiDom(doc);

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                return {
                    status: 404,
                    json: async () => ({ message: 'Not found' })
                };
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 0, commentCount: 0, threads: [] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        assert.strictEqual(wikiCommentsModule.getHighlightedElement(), null, 'No comment element should be highlighted on 404');
        assert.strictEqual(section.isFocused, true, 'Discussion container must receive fallback focus');
        assert.strictEqual(section.getAttribute('tabindex'), '-1');
        assert.strictEqual(section.scrolledIntoView, true);
        assert.strictEqual(section.lastScrollOptions?.block, 'start', 'Fallback scroll block must be start');
        assert.strictEqual(doc.defaultView.location.search, '', 'Params must be scrubbed after fallback');
    });

    test('Case G: Initial feed failure with pending deep link -> graceful fallback, params scrubbed, pending cleared', async () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=r-1&threadId=r-1');
        const { section } = setupWikiDom(doc);

        wikiCommentsModule.setFetchImplementation(async () => {
            return {
                status: 500,
                json: async () => ({ message: 'Internal Server Error' })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        assert.strictEqual(wikiCommentsModule.getState().pendingDeepLink, null);
        assert.strictEqual(section.isFocused, true, 'Discussion container must receive fallback focus on feed failure');
        assert.strictEqual(doc.defaultView.location.search, '', 'Params must be scrubbed on feed failure');
    });

    test('Case H: Parameter scrubbing preserves unrelated query params and hash', () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?filter=active&commentId=c-123&threadId=t-123&sort=asc#wikiDiscussion');

        wikiCommentsModule.scrubDeepLinkParams(doc);

        assert.strictEqual(doc.defaultView.location.search, '?filter=active&sort=asc');
        assert.strictEqual(doc.defaultView.location.hash, '#wikiDiscussion');
        assert.strictEqual(doc.defaultView.location.pathname, '/wiki/article/intro');
    });

    test('Case I: Params remain in URL while async context resolution is in flight', async () => {
        const doc = new FakeDocument();
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=r-slow&threadId=r-slow#wikiDiscussion');
        setupWikiDom(doc);

        let resolveThreadFetch;
        const threadPromise = new Promise(resolve => { resolveThreadFetch = resolve; });

        wikiCommentsModule.setFetchImplementation(async (url) => {
            if (url.includes('/thread')) {
                await threadPromise;
                return {
                    status: 200,
                    json: async () => ({ root: { id: 'r-slow', body: 'Slow Root' }, replies: [] })
                };
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 0, commentCount: 0, threads: [] })
            };
        });

        wikiCommentsModule.init(doc);
        await new Promise(process.nextTick);

        // While thread fetch is in flight, URL search must still contain commentId and threadId
        assert.ok(doc.defaultView.location.search.includes('commentId=r-slow'), 'Params must remain in URL while in flight');
        assert.ok(doc.defaultView.location.search.includes('threadId=r-slow'));

        // Now resolve thread fetch
        resolveThreadFetch();
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        // Terminal outcome reached -> params scrubbed
        assert.strictEqual(doc.defaultView.location.search, '');
    });

    test('Case J: Monotonic load token across reset/reinit protects same-article stale race', async () => {
        const doc = new FakeDocument();
        // 1. Initialize Wiki article A with old deep-link
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=old-comment&threadId=old-thread');
        const { list } = setupWikiDom(doc, 'article-intro');

        let resolveOldThread;
        const oldThreadPromise = new Promise(resolve => { resolveOldThread = resolve; });

        let resolveNewThread;
        const newThreadPromise = new Promise(resolve => { resolveNewThread = resolve; });

        const mockFetch = async (url) => {
            if (url.includes('/comments/old-thread/thread')) {
                // 2. Allow old exact-thread request to remain pending
                await oldThreadPromise;
                return {
                    status: 200,
                    json: async () => ({ root: { id: 'old-thread', body: 'Old Thread Body', author: { displayName: 'Old Author' } }, replies: [] })
                };
            }
            if (url.includes('/comments/new-thread/thread')) {
                // Hold new exact-thread request pending while resolving old request
                await newThreadPromise;
                return {
                    status: 200,
                    json: async () => ({
                        root: { id: 'new-thread', body: 'New Thread Body', author: { displayName: 'New Author' } },
                        replies: [
                            { id: 'new-comment', body: 'New Reply Body', author: { displayName: 'New Author' }, parentCommentId: 'new-thread' }
                        ]
                    })
                };
            }
            return {
                status: 200,
                json: async () => ({ page: 0, hasNext: false, threadCount: 0, commentCount: 0, threads: [] })
            };
        };

        wikiCommentsModule.init(doc, { fetch: mockFetch });
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 15));

        // 3. Call resetState()
        wikiCommentsModule.resetState();

        // 4. Reinitialize the SAME article A with a NEW deep-link
        doc.defaultView = new FakeWindow('http://localhost/wiki/article/intro?commentId=new-comment&threadId=new-thread');
        wikiCommentsModule.init(doc, { fetch: mockFetch });
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 15));

        // 5. Allow NEW flow to become current; new params are in URL
        assert.ok(doc.defaultView.location.search.includes('commentId=new-comment'), 'New params must be present in URL');
        assert.ok(doc.defaultView.location.search.includes('threadId=new-thread'));

        // 6. Resolve the OLD pending request
        resolveOldThread();
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        // Prove old response:
        // - does NOT insert old thread
        assert.strictEqual(list.querySelector('[data-thread-id="old-thread"]'), null, 'Old thread must NOT be inserted into DOM');
        // - does NOT highlight old comment
        assert.notStrictEqual(wikiCommentsModule.getHighlightedElement()?.getAttribute('data-comment-id'), 'old-comment');
        // - does NOT scrub newer URL state
        assert.ok(doc.defaultView.location.search.includes('commentId=new-comment'), 'Old response must NOT scrub newer URL state');
        assert.ok(doc.defaultView.location.search.includes('threadId=new-thread'));

        // Finish the new request and verify normal terminal behavior
        resolveNewThread();
        await new Promise(process.nextTick);
        await new Promise(resolve => setTimeout(resolve, 20));

        assert.ok(list.querySelector('[data-thread-id="new-thread"]'), 'New thread must be inserted into DOM');
        assert.strictEqual(wikiCommentsModule.getHighlightedElement()?.getAttribute('data-comment-id'), 'new-comment');
        assert.strictEqual(doc.defaultView.location.search, '', 'New URL params must be scrubbed after new resolution');
    });

    test('Case K: Accessible tabindex="-1" added only when element has no tabindex', () => {
        const doc = new FakeDocument();
        const el = doc.createElement('div');
        assert.strictEqual(el.hasAttribute('tabindex'), false);

        wikiCommentsModule.applyHighlight(el);
        assert.strictEqual(el.getAttribute('tabindex'), '-1');
    });

    test('Case L: Preserves existing tabindex="0" and tabindex="1" without overwriting', () => {
        const doc = new FakeDocument();
        const el0 = doc.createElement('div');
        el0.setAttribute('tabindex', '0');

        const el1 = doc.createElement('div');
        el1.setAttribute('tabindex', '1');

        wikiCommentsModule.applyHighlight(el0);
        assert.strictEqual(el0.getAttribute('tabindex'), '0', 'tabindex="0" must NOT be overwritten');

        wikiCommentsModule.applyHighlight(el1);
        assert.strictEqual(el1.getAttribute('tabindex'), '1', 'tabindex="1" must NOT be overwritten');
    });

    test('Highlight timer: clearHighlight removes is-restored-target immediately', () => {
        const doc = new FakeDocument();
        const el = doc.createElement('div');
        doc.body.appendChild(el);

        wikiCommentsModule.applyHighlight(el);
        assert.strictEqual(wikiCommentsModule.getHighlightedElement(), el);
        assert.ok(el.classList.contains('is-restored-target'));

        wikiCommentsModule.clearHighlight();
        assert.strictEqual(wikiCommentsModule.getHighlightedElement(), null);
        assert.strictEqual(el.classList.contains('is-restored-target'), false);
    });

    describe('Shared Comment Composer Design System Classes', () => {
        test('Inline reply composer renders shared kl-comment-composer classes alongside wiki classes', async () => {
            const doc = createEnvironment({ authenticated: 'true' });
            const sampleThread = {
                root: {
                    id: ROOT_ID,
                    authorUserId: '33333333-3333-3333-3333-333333333333',
                    body: 'Thread for reply composer test',
                    tombstone: false,
                    createdAt: '2026-09-19T10:00:00Z',
                    author: { displayName: 'Scholar' }
                },
                replies: []
            };

            wikiCommentsModule.setFetchImplementation(async () => ({
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            }));

            wikiCommentsModule.init(doc);
            await new Promise(process.nextTick);

            wikiCommentsModule.openReplyComposer(ROOT_ID, ROOT_ID, 'Scholar', doc);

            const slot = doc.querySelector('[data-reply-slot="' + ROOT_ID + '"]');
            assert.ok(slot);

            const composerBox = slot.querySelector('.wiki-inline-composer');
            assert.ok(composerBox);
            assert.ok(composerBox.classList.contains('kl-comment-composer'));

            const form = composerBox.querySelector('form');
            assert.ok(form.classList.contains('kl-comment-composer__form'));
            assert.ok(form.classList.contains('wiki-comment-composer-form'));

            const textarea = form.querySelector('textarea');
            assert.ok(textarea.classList.contains('kl-comment-composer__input'));
            assert.ok(textarea.classList.contains('wiki-comment-textarea'));

            const footer = form.querySelector('.wiki-comment-composer-footer');
            assert.ok(footer.classList.contains('kl-comment-composer__footer'));

            const errorSpan = form.querySelector('.wiki-comment-composer-error');
            assert.ok(errorSpan.classList.contains('kl-comment-composer__status'));
            assert.ok(errorSpan.classList.contains('kl-comment-composer__error'));

            const actions = form.querySelector('.wiki-comment-composer-actions');
            assert.ok(actions.classList.contains('kl-comment-composer__actions'));

            const cancelBtn = form.querySelector('.wiki-comment-btn--secondary');
            assert.ok(cancelBtn.classList.contains('kl-comment-composer__cancel'));

            const submitBtn = form.querySelector('.wiki-comment-btn--primary');
            assert.ok(submitBtn.classList.contains('kl-comment-composer__submit'));
        });

        test('Inline edit composer renders shared kl-comment-composer classes alongside wiki classes', async () => {
            const doc = createEnvironment({ authenticated: 'true' });
            const sampleThread = {
                root: {
                    id: ROOT_ID,
                    authorUserId: '11111111-1111-1111-1111-111111111111',
                    body: 'Thread for edit composer test',
                    tombstone: false,
                    canEdit: true,
                    createdAt: '2026-09-19T10:00:00Z',
                    author: { displayName: 'Me' }
                },
                replies: []
            };

            wikiCommentsModule.setFetchImplementation(async () => ({
                status: 200,
                json: async () => ({
                    threads: [sampleThread],
                    threadCount: 1,
                    commentCount: 1,
                    page: 0,
                    size: 20,
                    hasNext: false
                })
            }));

            wikiCommentsModule.init(doc);
            await new Promise(process.nextTick);

            wikiCommentsModule.openEditComposer(ROOT_ID, ROOT_ID, doc);

            const form = doc.querySelector('.wiki-inline-edit-form');
            assert.ok(form);
            assert.ok(form.classList.contains('kl-comment-composer__form'));

            const textarea = form.querySelector('textarea');
            assert.ok(textarea.classList.contains('kl-comment-composer__input'));
            assert.ok(textarea.classList.contains('wiki-comment-textarea'));

            const footer = form.querySelector('.wiki-comment-composer-footer');
            assert.ok(footer.classList.contains('kl-comment-composer__footer'));

            const errorSpan = form.querySelector('.wiki-comment-composer-error');
            assert.ok(errorSpan.classList.contains('kl-comment-composer__status'));
            assert.ok(errorSpan.classList.contains('kl-comment-composer__error'));

            const actions = form.querySelector('.wiki-comment-composer-actions');
            assert.ok(actions.classList.contains('kl-comment-composer__actions'));

            const cancelBtn = form.querySelector('.wiki-comment-btn--secondary');
            assert.ok(cancelBtn.classList.contains('kl-comment-composer__cancel'));

            const submitBtn = form.querySelector('.wiki-comment-btn--primary');
            assert.ok(submitBtn.classList.contains('kl-comment-composer__submit'));
        });
    });
});



