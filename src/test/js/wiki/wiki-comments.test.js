const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const wikiCommentsModule = require(path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-comments.js'));
const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));

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

    focus() {
        this.isFocused = true;
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
        this.location = {
            href: href,
            origin: 'http://localhost'
        };
        this.listeners = {};
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

    test('16. Avatar sanitization rejects javascript: and dangerous protocols', () => {
        assert.strictEqual(wikiCommentsModule.sanitizeAvatarUrl('javascript:alert(1)'), null);
        assert.strictEqual(wikiCommentsModule.sanitizeAvatarUrl('data:text/html,<script>alert(1)</script>'), null);
        assert.strictEqual(wikiCommentsModule.sanitizeAvatarUrl('//evil.com/pic.png'), null);
        assert.strictEqual(wikiCommentsModule.sanitizeAvatarUrl('https://example.com/avatar.png'), 'https://example.com/avatar.png');
        assert.strictEqual(wikiCommentsModule.sanitizeAvatarUrl('/images/default-avatar.png'), '/images/default-avatar.png');
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
        assert.ok(historyBtn, 'History button must be rendered for active edited comment');
        assert.strictEqual(historyBtn.tagName, 'BUTTON', 'Must be a semantic <button>');
        assert.strictEqual(historyBtn.textContent, 'đã chỉnh sửa');
        assert.strictEqual(historyBtn.getAttribute('aria-label'), 'Xem lịch sử chỉnh sửa');
        assert.strictEqual(historyBtn.getAttribute('data-comment-id'), ROOT_ID);
        assert.ok(historyBtn.className.includes('wiki-comment-edited'));
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

