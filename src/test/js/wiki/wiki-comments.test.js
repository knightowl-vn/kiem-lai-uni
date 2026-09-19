const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const wikiCommentsModule = require(path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-comments.js'));

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
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
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

class FakeDocument {
    constructor() {
        this.elementsById = new Map();
        this.root = new FakeElement('html');
        this.head = new FakeElement('head');
        this.body = new FakeElement('body');
        this.root.appendChild(this.head);
        this.root.appendChild(this.body);

        this.defaultView = {
            location: {
                href: 'http://localhost/wiki/character/tran-binh-an',
                origin: 'http://localhost'
            }
        };
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
});

