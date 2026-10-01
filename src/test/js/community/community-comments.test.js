const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const CommentPresentation = require(path.join(__dirname, '../../../main/resources/static/js/shared/comment-presentation.js'));
const RelativeTime = require(path.join(__dirname, '../../../main/resources/static/js/shared/relative-time.js'));
const CommunityComments = require(path.join(__dirname, '../../../main/resources/static/js/community/community-comments.js'));

// Lightweight DOM Fixtures
class FakeClassList {
    constructor(el) {
        this.el = el;
        this.classes = new Set();
    }
    add(...names) {
        for (const n of names) if (n) this.classes.add(n);
        this._sync();
    }
    remove(...names) {
        for (const n of names) if (n) this.classes.delete(n);
        this._sync();
    }
    contains(name) { return this.classes.has(name); }
    _sync() {
        if (this.classes.size > 0) this.el.attributes['class'] = Array.from(this.classes).join(' ');
        else delete this.el.attributes['class'];
    }
}

class FakeElement {
    constructor(tagName, attrs = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.classList = new FakeClassList(this);
        this.disabled = false;
        this.value = '';
        this._textContent = '';
        this.listeners = {};

        for (const [k, v] of Object.entries(attrs)) {
            this.setAttribute(k, v);
        }
    }

    get hidden() {
        return this.attributes['hidden'] !== undefined;
    }
    set hidden(val) {
        if (val) {
            this.attributes['hidden'] = '';
        } else {
            delete this.attributes['hidden'];
        }
    }

    get id() { return this.attributes['id'] || ''; }
    set id(val) {
        if (val) this.setAttribute('id', val);
        else this.removeAttribute('id');
    }

    get dataset() {
        const ds = {};
        for (const [k, v] of Object.entries(this.attributes)) {
            if (k.startsWith('data-')) {
                const camel = k.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
                ds[camel] = v;
            }
        }
        return ds;
    }

    get children() { return this.childNodes; }

    get href() { return this.attributes['href'] || ''; }
    set href(val) {
        if (val) this.setAttribute('href', val);
        else this.removeAttribute('href');
    }

    get className() { return this.attributes['class'] || ''; }
    set className(val) {
        this.attributes['class'] = val || '';
        this.classList.classes.clear();
        if (val) {
            val.trim().split(/\s+/).forEach(p => { if (p) this.classList.classes.add(p); });
        }
    }

    get textContent() {
        if (this.childNodes.length === 0) return this._textContent;
        return this.childNodes.map(c => c.textContent || '').join('');
    }
    set textContent(val) {
        this.childNodes = [];
        this._textContent = String(val == null ? '' : val);
    }

    get innerHTML() { return this.textContent; }
    set innerHTML(val) {
        this.childNodes = [];
        this._textContent = '';
    }

    setAttribute(k, v) {
        this.attributes[k] = String(v);
        if (k === 'class') this.className = String(v);
        if (k === 'hidden') this.hidden = true;
    }
    getAttribute(k) { return this.attributes[k] !== undefined ? this.attributes[k] : null; }
    hasAttribute(k) { return this.attributes[k] !== undefined; }
    removeAttribute(k) {
        delete this.attributes[k];
        if (k === 'class') this.classList.classes.clear();
        if (k === 'hidden') this.hidden = false;
    }

    appendChild(child) {
        if (!child) return;
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }
    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx !== -1) {
            child.parentNode = null;
            this.childNodes.splice(idx, 1);
        }
        return child;
    }
    replaceChild(newChild, oldChild) {
        const idx = this.childNodes.indexOf(oldChild);
        if (idx !== -1) {
            oldChild.parentNode = null;
            newChild.parentNode = this;
            this.childNodes[idx] = newChild;
        }
        return oldChild;
    }

    remove() {
        if (this.parentNode) this.parentNode.removeChild(this);
    }

    focus() {}

    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }
    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }
    dispatchEvent(event) {
        const type = event.type || event;
        if (this.listeners[type]) {
            this.listeners[type].forEach(fn => fn(event));
        }
    }

    querySelector(selector) {
        return findOne(this, selector);
    }
    querySelectorAll(selector) {
        const res = [];
        findAll(this, selector, res);
        return res;
    }

    closest(selector) {
        let curr = this;
        while (curr) {
            if (matches(curr, selector)) return curr;
            curr = curr.parentNode;
        }
        return null;
    }
}

class FakeDocument {
    constructor() {
        this.body = new FakeElement('body');
        this.head = new FakeElement('head');
        this.listeners = {};
    }
    createElement(tag) {
        const el = new FakeElement(tag);
        el.ownerDocument = this;
        return el;
    }
    createTextNode(text) {
        const span = new FakeElement('span');
        span.textContent = text;
        return span;
    }
    getElementById(id) {
        return findOne(this.body, '#' + id);
    }
    querySelector(selector) {
        let found = findOne(this.head, selector);
        if (found) return found;
        return findOne(this.body, selector);
    }
    querySelectorAll(selector) {
        const res = [];
        findAll(this.head, selector, res);
        findAll(this.body, selector, res);
        return res;
    }
    addEventListener(event, fn) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(fn);
    }
    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }
    dispatchEvent(event) {
        const type = event.type || event;
        if (this.listeners[type]) {
            this.listeners[type].forEach(fn => fn(event));
        }
    }
}

function matches(el, sel) {
    if (!el || !el.tagName || !sel) return false;
    let remaining = sel;

    const tagMatch = remaining.match(/^([a-zA-Z0-9_-]+)/);
    if (tagMatch) {
        if (el.tagName.toLowerCase() !== tagMatch[1].toLowerCase()) return false;
        remaining = remaining.slice(tagMatch[1].length);
    }

    while (remaining.length > 0) {
        if (remaining.startsWith('#')) {
            const idMatch = remaining.match(/^#([a-zA-Z0-9_-]+)/);
            if (!idMatch) return false;
            if (el.getAttribute('id') !== idMatch[1]) return false;
            remaining = remaining.slice(idMatch[0].length);
        } else if (remaining.startsWith('.')) {
            const classMatch = remaining.match(/^\.([a-zA-Z0-9_-]+)/);
            if (!classMatch) return false;
            if (!el.classList || !el.classList.contains(classMatch[1])) return false;
            remaining = remaining.slice(classMatch[0].length);
        } else if (remaining.startsWith('[')) {
            const attrMatch = remaining.match(/^\[([a-zA-Z0-9_-]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]/);
            if (!attrMatch) return false;
            const attrName = attrMatch[1];
            const attrVal = attrMatch[2] !== undefined ? attrMatch[2] : (attrMatch[3] !== undefined ? attrMatch[3] : attrMatch[4]);
            if (attrVal !== undefined) {
                if (el.getAttribute(attrName) !== attrVal) return false;
            } else {
                if (!el.hasAttribute(attrName)) return false;
            }
            remaining = remaining.slice(attrMatch[0].length);
        } else {
            return false;
        }
    }
    return true;
}

function findOne(el, sel) {
    if (!el || !sel) return null;
    const tokens = sel.trim().split(/\s+/);
    if (tokens.length === 1) {
        if (matches(el, tokens[0])) return el;
        for (const child of el.childNodes) {
            const f = findOne(child, tokens[0]);
            if (f) return f;
        }
        return null;
    }
    let currentMatches = [el];
    for (const token of tokens) {
        const nextMatches = [];
        for (const candidate of currentMatches) {
            for (const child of candidate.childNodes) {
                findAll(child, token, nextMatches);
            }
        }
        currentMatches = nextMatches;
        if (currentMatches.length === 0) return null;
    }
    return currentMatches[0] || null;
}

function findAll(el, sel, out) {
    if (!el || !sel) return;
    const tokens = sel.trim().split(/\s+/);
    if (tokens.length === 1) {
        if (matches(el, tokens[0])) out.push(el);
        for (const child of el.childNodes) {
            findAll(child, tokens[0], out);
        }
        return;
    }
    let currentMatches = [el];
    for (const token of tokens) {
        const nextMatches = [];
        for (const candidate of currentMatches) {
            for (const child of candidate.childNodes) {
                findAll(child, token, nextMatches);
            }
        }
        currentMatches = nextMatches;
    }
    out.push(...currentMatches);
}

describe('CommunityComments Module Unit Tests', () => {
    let doc;
    const POST_ID = 'post-1111';

    beforeEach(() => {
        doc = new FakeDocument();
        globalThis.document = doc;
        globalThis.window = {
            location: { pathname: '/community', search: '' },
            scrollY: 0,
            scrollTo: (x, y) => { globalThis.window.scrollY = y; },
            matchMedia: (query) => ({
                matches: false,
                media: query
            }),
            CommentPresentation: CommentPresentation,
            RelativeTime: RelativeTime,
            InteractionReactions: { hydrate: () => {} }
        };

        // CSRF meta
        const csrfMeta = doc.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'token-abc');
        doc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = doc.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        doc.head.appendChild(csrfHeaderMeta);

        // Authenticated meta
        const authMeta = doc.createElement('meta');
        authMeta.setAttribute('name', '_authenticated');
        authMeta.setAttribute('content', 'true');
        doc.head.appendChild(authMeta);

        // Build Post Card DOM (2 cards for same post to test multi-instance count sync)
        for (let i = 0; i < 2; i++) {
            const article = doc.createElement('article');
            article.className = 'community-post-card';
            article.setAttribute('data-post-id', POST_ID);

            const footer = doc.createElement('footer');
            footer.className = 'post-footer';

            const toggleBtn = doc.createElement('button');
            toggleBtn.className = 'post-metric post-comment-toggle-btn';
            toggleBtn.setAttribute('data-action', 'toggle-comments');
            toggleBtn.setAttribute('data-post-id', POST_ID);
            toggleBtn.setAttribute('aria-expanded', 'false');

            const countSpan = doc.createElement('span');
            countSpan.className = 'post-comment-count';
            countSpan.textContent = '0';
            toggleBtn.appendChild(countSpan);
            footer.appendChild(toggleBtn);
            article.appendChild(footer);

            const commentsContainer = doc.createElement('section');
            commentsContainer.className = 'post-comments-container';
            commentsContainer.setAttribute('data-post-comments', POST_ID);
            commentsContainer.hidden = true;
            article.appendChild(commentsContainer);

            doc.body.appendChild(article);
        }

        CommunityComments.reset();
    });

    afterEach(() => {
        CommunityComments.reset();
        delete globalThis.document;
        delete globalThis.window;
    });

    test('A. Module exports authoritative API and zero initial fetch calls', () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return { ok: true, json: async () => ({}) };
        };

        CommunityComments.init({ force: true, fetch: mockFetch });

        assert.strictEqual(typeof CommunityComments.init, 'function');
        assert.strictEqual(typeof CommunityComments.toggleComments, 'function');
        assert.strictEqual(typeof CommunityComments.openCommentsDrawer, 'function');
        assert.strictEqual(typeof CommunityComments.closeCommentsDrawer, 'function');
        assert.strictEqual(typeof CommunityComments.isDrawerOpen, 'function');
        assert.strictEqual(typeof CommunityComments.setMobileViewport, 'function');
        assert.strictEqual(typeof CommunityComments.isMobileViewport, 'function');
        assert.strictEqual(typeof CommunityComments.fetchComments, 'function');
        assert.strictEqual(typeof CommunityComments.toggleThreadReplies, 'function');
        assert.strictEqual(typeof CommunityComments.submitRootComment, 'function');
        assert.strictEqual(typeof CommunityComments.submitReply, 'function');
        assert.strictEqual(typeof CommunityComments.refreshThread, 'function');
        assert.strictEqual(typeof CommunityComments.updateCommentCount, 'function');

        // Initial render has ZERO fetch calls
        assert.strictEqual(fetchCalls, 0);
    });

    test('B. toggleComments lazy-loads root feed only and ignores duplicate fetches on repeated toggle', async () => {
        let fetchCalls = 0;
        const mockFetch = async (url) => {
            fetchCalls++;
            assert.ok(!url.includes('/thread'), 'Must NOT fetch thread on root feed load');
            return {
                ok: true,
                json: async () => ({
                    roots: [],
                    commentCount: 0,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, fetch: mockFetch });

        const container = doc.querySelector('[data-post-comments="' + POST_ID + '"]');
        const btn = doc.querySelector('[data-action="toggle-comments"]');

        assert.strictEqual(container.hidden, true);

        // 1st click: expands and fetches root feed
        CommunityComments.toggleComments(POST_ID);
        assert.strictEqual(container.hidden, false);
        assert.strictEqual(btn.getAttribute('aria-expanded'), 'true');
        assert.ok(btn.classList.contains('is-active'));
        assert.strictEqual(fetchCalls, 1);

        // 2nd click: collapses without fetching
        CommunityComments.toggleComments(POST_ID);
        assert.strictEqual(container.hidden, true);
        assert.strictEqual(btn.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(fetchCalls, 1);

        // 3rd click: re-expands without duplicate fetching
        CommunityComments.toggleComments(POST_ID);
        assert.strictEqual(container.hidden, false);
        assert.strictEqual(btn.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(fetchCalls, 1);
    });

    test('C. updateCommentCount synchronizes all comment count spans across cards', () => {
        const countSpans = doc.querySelectorAll('.post-comment-count');
        assert.strictEqual(countSpans.length, 2);
        countSpans.forEach(s => assert.strictEqual(s.textContent, '0'));

        CommunityComments.updateCommentCount(POST_ID, 42);
        countSpans.forEach(s => assert.strictEqual(s.textContent, '42'));
    });

    test('D. fetchComments renders root comments with reply indicators without fetching replies eagerly', async () => {
        const mockResponse = {
            roots: [
                {
                    root: {
                        id: 'root-101',
                        body: 'Root comment body',
                        author: {
                            displayName: 'Quettye User',
                            avatarUrl: 'https://example.com/avatar.png',
                            publicHandle: 'quettye'
                        },
                        createdAt: '2026-10-01T10:00:00Z',
                        reactionSummary: null
                    },
                    replyCount: 5
                }
            ],
            commentCount: 6,
            page: 0,
            size: 10,
            hasNext: false
        };

        let fetchedUrls = [];
        CommunityComments.init({
            force: true,
            fetch: async (url) => {
                fetchedUrls.push(url);
                return {
                    ok: true,
                    json: async () => mockResponse
                };
            }
        });

        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        assert.strictEqual(fetchedUrls.length, 1);
        assert.ok(fetchedUrls[0].includes('/api/community/posts/' + POST_ID + '/comments?page=0'));

        const container = doc.querySelector('[data-post-comments="' + POST_ID + '"]');
        const threadList = container.querySelector('[data-thread-list]');
        assert.ok(threadList);
        assert.strictEqual(threadList.childNodes.length, 1);

        const threadEl = threadList.childNodes[0];
        assert.strictEqual(threadEl.getAttribute('data-thread-id'), 'root-101');

        // Root author handle link
        const rootAuthorLink = threadEl.querySelector('.kl-comment__author');
        assert.ok(rootAuthorLink);
        assert.strictEqual(rootAuthorLink.getAttribute('href'), '/community/@quettye');

        // Thread toggle button is present with replyCount = 5
        const toggleThreadBtn = threadEl.querySelector('.community-thread-toggle-btn');
        assert.ok(toggleThreadBtn);
        assert.strictEqual(toggleThreadBtn.textContent, 'Xem 5 phản hồi');
        assert.strictEqual(toggleThreadBtn.getAttribute('data-reply-count'), '5');

        // Replies container is hidden and not loaded yet
        const replies = threadEl.querySelector('.community-comment-replies');
        assert.ok(replies);
        assert.strictEqual(replies.hidden, true);
        assert.strictEqual(replies.childNodes.length, 0);

        // Comment count synchronized across all cards
        const countSpans = doc.querySelectorAll('.post-comment-count');
        countSpans.forEach(s => assert.strictEqual(s.textContent, '6'));
    });

    test('E. toggleThreadReplies explicitly loads thread replies on user demand and supports collapse', async () => {
        let fetchedThreadUrl = null;
        const mockThreadResponse = {
            root: {
                id: 'root-101',
                body: 'Root comment body',
                author: { displayName: 'Author 1', publicHandle: 'auth1' }
            },
            replies: [
                {
                    id: 'reply-201',
                    body: 'First reply',
                    author: { displayName: 'Author 2', publicHandle: 'auth2' }
                }
            ]
        };

        CommunityComments.init({
            force: true,
            fetch: async (url) => {
                if (url.includes('/thread')) {
                    fetchedThreadUrl = url;
                    return { ok: true, json: async () => mockThreadResponse };
                }
                return {
                    ok: true,
                    json: async () => ({
                        roots: [{
                            root: { id: 'root-101', body: 'Root body', author: { displayName: 'A1' } },
                            replyCount: 1
                        }],
                        commentCount: 2,
                        page: 0,
                        size: 10,
                        hasNext: false
                    })
                };
            }
        });

        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        // Click thread toggle to explicitly load replies
        await CommunityComments.toggleThreadReplies(POST_ID, 'root-101');

        assert.ok(fetchedThreadUrl);
        assert.ok(fetchedThreadUrl.includes('/api/community/posts/' + POST_ID + '/comments/root-101/thread'));

        const replies = doc.querySelector('.community-comment-replies[data-replies-for="root-101"]');
        assert.ok(replies);
        assert.strictEqual(replies.hidden, false);
        assert.strictEqual(replies.childNodes.length, 1);

        const toggleBtn = doc.querySelector('.community-thread-toggle-btn[data-root-id="root-101"]');
        assert.strictEqual(toggleBtn.textContent, 'Ẩn phản hồi');

        // Toggle again collapses without refetching
        fetchedThreadUrl = null;
        await CommunityComments.toggleThreadReplies(POST_ID, 'root-101');
        assert.strictEqual(replies.hidden, true);
        assert.strictEqual(toggleBtn.textContent, 'Xem 1 phản hồi');
        assert.strictEqual(fetchedThreadUrl, null);
    });

    test('F. failed fetch leaves loaded flag false and renders retry option', async () => {
        let attempt = 0;
        const mockFetch = async () => {
            attempt++;
            if (attempt === 1) {
                return { ok: false, status: 500 };
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [],
                    commentCount: 0,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        const state = CommunityComments.getPostState(POST_ID);
        assert.ok(state);
        assert.strictEqual(CommunityComments._states.has(POST_ID), false, 'Legacy unqualified postId key must not exist');
        assert.strictEqual(state.isLoaded, false);

        const errorEl = doc.querySelector('.community-comments-error');
        assert.ok(errorEl);

        // Retry comments
        await CommunityComments.fetchComments(POST_ID, 0);
        assert.strictEqual(state.isLoaded, true);
    });

    test('G. submitRootComment uses updatedCommentCount from response and refreshes root page', async () => {
        let postBody = null;
        let postHeaders = null;
        let refreshedPage = null;

        const mockFetch = async (url, opts) => {
            if (opts && opts.method === 'POST') {
                postBody = JSON.parse(opts.body);
                postHeaders = opts.headers;
                return {
                    ok: true,
                    json: async () => ({
                        commentId: 'new-root-999',
                        updatedCommentCount: 7
                    })
                };
            }
            if (url.includes('page=')) {
                refreshedPage = 0;
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [],
                    commentCount: 7,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        const container = doc.querySelector('[data-post-comments="' + POST_ID + '"]');
        const input = container.querySelector('[data-input-root]');
        input.value = 'New root comment';

        await CommunityComments.submitRootComment(POST_ID);

        assert.strictEqual(postBody.body, 'New root comment');
        assert.strictEqual(postHeaders['X-CSRF-TOKEN'], 'token-abc');
        assert.strictEqual(input.value, '');
        assert.strictEqual(refreshedPage, 0);

        // Count synchronized across all cards without count++
        const countSpans = doc.querySelectorAll('.post-comment-count');
        countSpans.forEach(s => assert.strictEqual(s.textContent, '7'));
    });

    test('H. submitReply refreshes ONLY affected thread and updates post count', async () => {
        let postUrl = null;
        let postBody = null;
        let refreshedThreadId = null;

        const mockFetch = async (url, opts) => {
            if (opts && opts.method === 'POST') {
                postUrl = url;
                postBody = JSON.parse(opts.body);
                return {
                    ok: true,
                    json: async () => ({
                        commentId: 'new-reply-888',
                        updatedCommentCount: 8
                    })
                };
            }
            if (url.includes('/thread')) {
                refreshedThreadId = 'root-1';
                return {
                    ok: true,
                    json: async () => ({
                        root: { id: 'root-1', body: 'R1', author: { displayName: 'Author 1' } },
                        replies: [{ id: 'new-reply-888', body: 'Reply 1', author: { displayName: 'Author 2' } }]
                    })
                };
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [{
                        root: { id: 'root-1', body: 'R1', author: { displayName: 'Author 1' } },
                        replyCount: 0
                    }],
                    commentCount: 7,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        CommunityComments.openReplyComposer(POST_ID, 'root-1', 'root-1', 'Author 1');
        const slot = doc.querySelector('[data-reply-slot="root-1"]');
        assert.ok(slot);
        const replyInput = slot.querySelector('.community-reply-composer__input');
        replyInput.value = 'My new reply text';

        await CommunityComments.submitReply(POST_ID, 'root-1', 'root-1');

        assert.ok(postUrl.includes('/api/community/posts/' + POST_ID + '/comments/root-1/replies'));
        assert.strictEqual(postBody.body, 'My new reply text');
        assert.strictEqual(refreshedThreadId, 'root-1');

        // Composer removed after success
        assert.strictEqual(slot.querySelector('.community-reply-composer'), null);

        // Count synchronized across all cards
        const countSpans = doc.querySelectorAll('.post-comment-count');
        countSpans.forEach(s => assert.strictEqual(s.textContent, '8'));
    });
});

describe('Mobile Comments Drawer Contract Tests (MS-07B8.2.2-MOBILE-COMMENTS-UX)', () => {
    let doc;
    const POST_ID = 'post-1111';

    beforeEach(() => {
        doc = new FakeDocument();
        globalThis.document = doc;
        globalThis.window = {
            location: { pathname: '/community', search: '' },
            scrollY: 0,
            scrollTo: (x, y) => { globalThis.window.scrollY = y; },
            matchMedia: (query) => ({
                matches: query.includes('max-width: 767.98px'),
                media: query
            }),
            CommentPresentation: CommentPresentation,
            RelativeTime: RelativeTime,
            InteractionReactions: { hydrate: () => {} }
        };

        const csrfMeta = doc.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'token-abc');
        doc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = doc.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        doc.head.appendChild(csrfHeaderMeta);

        const authMeta = doc.createElement('meta');
        authMeta.setAttribute('name', '_authenticated');
        authMeta.setAttribute('content', 'true');
        doc.head.appendChild(authMeta);

        // Build 2 post cards for POST_ID
        for (let i = 0; i < 2; i++) {
            const article = doc.createElement('article');
            article.className = 'community-post-card';
            article.setAttribute('data-post-id', POST_ID);

            const footer = doc.createElement('footer');
            footer.className = 'post-footer';

            const toggleBtn = doc.createElement('button');
            toggleBtn.className = 'post-metric post-comment-toggle-btn';
            toggleBtn.setAttribute('data-action', 'toggle-comments');
            toggleBtn.setAttribute('data-post-id', POST_ID);
            toggleBtn.setAttribute('aria-expanded', 'false');

            const countSpan = doc.createElement('span');
            countSpan.className = 'post-comment-count';
            countSpan.textContent = '0';
            toggleBtn.appendChild(countSpan);
            footer.appendChild(toggleBtn);
            article.appendChild(footer);

            const commentsContainer = doc.createElement('section');
            commentsContainer.className = 'post-comments-container';
            commentsContainer.setAttribute('data-post-comments', POST_ID);
            commentsContainer.hidden = true;
            article.appendChild(commentsContainer);

            doc.body.appendChild(article);
        }

        CommunityComments.reset();
    });

    afterEach(() => {
        CommunityComments.reset();
        delete globalThis.document;
        delete globalThis.window;
    });

    test('1. Mobile initialization has 0 fetch calls', () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return { ok: true, json: async () => ({}) };
        };

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });

        assert.strictEqual(fetchCalls, 0, 'Must make zero network requests on init');
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);
        assert.strictEqual(doc.body.classList.contains('has-community-comments-open'), false);
    });

    test('2. Mobile comment toggle opens drawer, makes exactly 1 root-feed fetch, and keeps inline container hidden', async () => {
        let fetchCalls = 0;
        let requestedUrl = null;

        const mockFetch = async (url) => {
            fetchCalls++;
            requestedUrl = url;
            return {
                ok: true,
                json: async () => ({
                    roots: [{
                        root: { id: 'root-101', body: 'Mobile root 101', author: { displayName: 'Tester' } },
                        replyCount: 0
                    }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });

        const toggleBtn = doc.querySelector('[data-action="toggle-comments"]');
        assert.ok(toggleBtn);

        // Click toggle button on mobile
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 10));

        // 1. Drawer becomes visible
        assert.strictEqual(CommunityComments.isDrawerOpen(), true);
        const drawer = doc.getElementById('communityCommentsDrawer');
        const backdrop = doc.getElementById('communityCommentsBackdrop');
        assert.ok(drawer);
        assert.ok(backdrop);
        assert.strictEqual(drawer.hidden, false);
        assert.strictEqual(backdrop.hidden, false);
        assert.ok(drawer.classList.contains('is-open'));
        assert.ok(backdrop.classList.contains('is-open'));
        assert.strictEqual(toggleBtn.getAttribute('aria-expanded'), 'true');
        assert.ok(toggleBtn.classList.contains('is-active'));

        // 2. Exactly 1 root-feed fetch
        assert.strictEqual(fetchCalls, 1);
        assert.ok(requestedUrl.includes('/api/community/posts/' + POST_ID + '/comments?page=0&size=10'));
        assert.ok(!requestedUrl.includes('/thread'));

        // 3. Inline post card discussion container does NOT expand
        const inlineContainers = doc.querySelectorAll('.post-comments-container');
        assert.strictEqual(inlineContainers.length, 2);
        inlineContainers.forEach(container => {
            assert.strictEqual(container.hidden, true, 'Inline container must remain hidden on mobile');
            assert.strictEqual(container.childNodes.length, 0, 'Inline container must remain empty on mobile');
        });

        // 4. Discussion is populated inside drawer body
        const drawerBody = drawer.querySelector('[data-drawer-body]');
        assert.ok(drawerBody);
        const threadList = drawerBody.querySelector('[data-thread-list="' + POST_ID + '"]');
        assert.ok(threadList);
        assert.strictEqual(threadList.childNodes.length, 1);

        // 5. Drawer footer has composer for authenticated user
        const drawerFooter = drawer.querySelector('[data-drawer-footer]');
        assert.ok(drawerFooter);
        assert.ok(drawerFooter.querySelector('[data-composer-root="' + POST_ID + '"]'));
    });

    test('3. Root with replies does NOT request thread upon opening drawer', async () => {
        let fetchUrls = [];

        const mockFetch = async (url) => {
            fetchUrls.push(url);
            return {
                ok: true,
                json: async () => ({
                    roots: [{
                        root: { id: 'root-202', body: 'Root with 5 replies', author: { displayName: 'Tester' } },
                        replyCount: 5,
                        replies: []
                    }],
                    commentCount: 6,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        assert.strictEqual(fetchUrls.length, 1);
        assert.ok(fetchUrls[0].includes('page=0'));
        assert.ok(!fetchUrls.some(u => u.includes('/thread')), 'No thread fetch on initial drawer open');

        const drawer = doc.getElementById('communityCommentsDrawer');
        const toggleThreadBtn = drawer.querySelector('[data-action="toggle-thread"][data-root-id="root-202"]');
        assert.ok(toggleThreadBtn);
        assert.strictEqual(toggleThreadBtn.textContent, 'Xem 5 phản hồi');

        const repliesContainer = drawer.querySelector('.community-comment-replies[data-replies-for="root-202"]');
        assert.ok(repliesContainer);
        assert.strictEqual(repliesContainer.hidden, true);
    });

    test('4. Explicit "Xem N phản hồi" click makes exactly 1 thread request and renders replies in drawer', async () => {
        let threadFetchCalls = 0;

        const mockFetch = async (url) => {
            if (url.includes('/thread')) {
                threadFetchCalls++;
                return {
                    ok: true,
                    json: async () => ({
                        root: { id: 'root-202', body: 'Root with replies', author: { displayName: 'Tester' } },
                        replies: [
                            { id: 'reply-1', body: 'Reply 1', author: { displayName: 'Replier 1' } },
                            { id: 'reply-2', body: 'Reply 2', author: { displayName: 'Replier 2' } }
                        ]
                    })
                };
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [{
                        root: { id: 'root-202', body: 'Root with replies', author: { displayName: 'Tester' } },
                        replyCount: 2,
                        replies: []
                    }],
                    commentCount: 3,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        assert.strictEqual(threadFetchCalls, 0);

        const drawer = doc.getElementById('communityCommentsDrawer');
        const toggleThreadBtn = drawer.querySelector('[data-action="toggle-thread"][data-root-id="root-202"]');
        assert.ok(toggleThreadBtn);

        // Click "Xem 2 phản hồi"
        doc.dispatchEvent({ type: 'click', target: toggleThreadBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 10));

        assert.strictEqual(threadFetchCalls, 1);
        assert.strictEqual(toggleThreadBtn.textContent, 'Ẩn phản hồi');

        const repliesContainer = drawer.querySelector('.community-comment-replies[data-replies-for="root-202"]');
        assert.strictEqual(repliesContainer.hidden, false);
        assert.strictEqual(repliesContainer.childNodes.length, 2);
    });

    test('5. Close hides drawer, removes body scroll lock, and preserves feed scroll position', async () => {
        const mockFetch = async () => ({
            ok: true,
            json: async () => ({ roots: [], commentCount: 0, page: 0, size: 10, hasNext: false })
        });

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });

        // Simulate user scrolled down 500px in the feed
        globalThis.window.scrollY = 500;

        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        const drawer = doc.getElementById('communityCommentsDrawer');
        const backdrop = doc.getElementById('communityCommentsBackdrop');

        assert.strictEqual(CommunityComments.isDrawerOpen(), true);
        assert.strictEqual(doc.body.classList.contains('has-community-comments-open'), true, 'Body must have scroll lock');

        // Simulate user scrolling inside drawer / modifying window scroll
        globalThis.window.scrollY = 120;

        // Click close button inside drawer
        const closeBtn = drawer.querySelector('[data-action="close-comments-drawer"]');
        assert.ok(closeBtn);
        doc.dispatchEvent({ type: 'click', target: closeBtn, preventDefault: () => {} });

        // Drawer hidden & backdrop hidden
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);
        assert.strictEqual(drawer.hidden, true);
        assert.strictEqual(backdrop.hidden, true);
        assert.strictEqual(drawer.classList.contains('is-open'), false);

        // Body scroll lock removed
        assert.strictEqual(doc.body.classList.contains('has-community-comments-open'), false);

        // Feed scroll position restored to saved position (500)
        assert.strictEqual(globalThis.window.scrollY, 500);

        // Toggle button aria-expanded reset
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"]');
        assert.strictEqual(toggleBtn.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(toggleBtn.classList.contains('is-active'), false);
    });

    test('6. Reopening drawer uses cached roots without duplicate fetch or duplicated event listeners', async () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-cached', body: 'Cached root' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });

        // First open: fetch 1
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));
        assert.strictEqual(fetchCalls, 1);
        assert.strictEqual(CommunityComments.isDrawerOpen(), true);

        // Close via backdrop click
        const backdrop = doc.getElementById('communityCommentsBackdrop');
        doc.dispatchEvent({ type: 'click', target: backdrop, preventDefault: () => {} });
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);

        // Reopen: no new network fetch
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));
        assert.strictEqual(fetchCalls, 1, 'Reopening must not trigger duplicate fetch');
        assert.strictEqual(CommunityComments.isDrawerOpen(), true);

        // Cached roots are populated in drawer
        const drawer = doc.getElementById('communityCommentsDrawer');
        const threadList = drawer.querySelector('[data-thread-list="' + POST_ID + '"]');
        assert.strictEqual(threadList.childNodes.length, 1);

        // Close via Escape key
        doc.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27, preventDefault: () => {} });
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);
    });

    test('7. Desktop viewport retains existing inline expandable behavior', async () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-desktop', body: 'Desktop root' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });

        const container = doc.querySelector('[data-post-comments="' + POST_ID + '"]');
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"]');
        assert.strictEqual(container.hidden, true);

        // Toggle on desktop
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        // Inline container expanded
        assert.strictEqual(container.hidden, false);
        assert.strictEqual(toggleBtn.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(fetchCalls, 1);

        // Drawer remains completely closed/hidden
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);
        const drawer = doc.getElementById('communityCommentsDrawer');
        if (drawer) {
            assert.strictEqual(drawer.hidden, true);
        }
        assert.strictEqual(doc.body.classList.contains('has-community-comments-open'), false);
    });

    test('8. Mobile drawer root comment submission updates badge and refreshes drawer discussion', async () => {
        let postBody = null;
        let fetchCalls = 0;

        const mockFetch = async (url, opts) => {
            fetchCalls++;
            if (opts && opts.method === 'POST') {
                postBody = JSON.parse(opts.body);
                return {
                    ok: true,
                    json: async () => ({
                        commentId: 'new-root-999',
                        updatedCommentCount: 5
                    })
                };
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [
                        { root: { id: 'new-root-999', body: 'New mobile root' }, replyCount: 0 }
                    ],
                    commentCount: 5,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        const drawer = doc.getElementById('communityCommentsDrawer');
        const input = drawer.querySelector('[data-input-root="' + POST_ID + '"]');
        assert.ok(input);
        input.value = 'Hello from mobile drawer';

        const submitBtn = drawer.querySelector('[data-action="submit-root-comment"]');
        assert.ok(submitBtn);

        doc.dispatchEvent({ type: 'click', target: submitBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 10));

        assert.strictEqual(postBody.body, 'Hello from mobile drawer');

        // All post card badges synchronized to 5
        const countSpans = doc.querySelectorAll('.post-comment-count');
        countSpans.forEach(s => assert.strictEqual(s.textContent, '5'));

        // Input cleared
        assert.strictEqual(input.value, '');
    });

    test('9. Guest user sees login prompt instead of composer inside mobile drawer', async () => {
        // Mark as unauthenticated
        const authMeta = doc.head.querySelector('meta[name="_authenticated"]');
        if (authMeta) authMeta.setAttribute('content', 'false');

        const mockFetch = async () => ({
            ok: true,
            json: async () => ({ roots: [], commentCount: 0, page: 0, size: 10, hasNext: false })
        });

        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });
        CommunityComments.toggleComments(POST_ID);
        await new Promise(resolve => setTimeout(resolve, 10));

        const drawer = doc.getElementById('communityCommentsDrawer');
        const footer = drawer.querySelector('[data-drawer-footer]');
        assert.ok(footer);

        const guestPrompt = footer.querySelector('.community-comment-guest-prompt');
        assert.ok(guestPrompt);
        const loginLink = guestPrompt.querySelector('a');
        assert.ok(loginLink);
        assert.ok(loginLink.getAttribute('href').includes('/login?returnTo='));

        // No textarea or submit button
        assert.strictEqual(footer.querySelector('textarea'), null);
        assert.strictEqual(footer.querySelector('[data-action="submit-root-comment"]'), null);
    });
});

describe('Desktop Inline Comments & Cross-Presentation Contract Tests (MS-07B8.2.2-DESKTOP-INLINE-REGRESSION)', () => {
    let doc;
    const POST_ID = 'post-desktop-contract';

    beforeEach(() => {
        doc = new FakeDocument();
        globalThis.document = doc;
        globalThis.window = {
            innerWidth: 1024,
            scrollY: 0,
            scrollTo: () => {},
            matchMedia: () => ({ matches: false }),
            location: { pathname: '/community', search: '' },
            CommentPresentation: CommentPresentation,
            RelativeTime: RelativeTime,
            InteractionReactions: { hydrate: () => {} }
        };

        const csrfMeta = doc.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'token-abc');
        doc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = doc.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        doc.head.appendChild(csrfHeaderMeta);

        const authMeta = doc.createElement('meta');
        authMeta.setAttribute('name', '_authenticated');
        authMeta.setAttribute('content', 'true');
        doc.head.appendChild(authMeta);

        // Build 2 post cards for POST_ID
        for (let i = 0; i < 2; i++) {
            const article = doc.createElement('article');
            article.className = 'community-post-card';
            article.setAttribute('data-post-id', POST_ID);

            const footer = doc.createElement('footer');
            footer.className = 'post-footer';

            const toggleBtn = doc.createElement('button');
            toggleBtn.className = 'post-metric post-comment-toggle-btn';
            toggleBtn.setAttribute('data-action', 'toggle-comments');
            toggleBtn.setAttribute('data-post-id', POST_ID);
            toggleBtn.setAttribute('aria-expanded', 'false');

            const countSpan = doc.createElement('span');
            countSpan.className = 'post-comment-count';
            countSpan.textContent = '2';
            toggleBtn.appendChild(countSpan);
            footer.appendChild(toggleBtn);
            article.appendChild(footer);

            const commentsContainer = doc.createElement('section');
            commentsContainer.className = 'post-comments-container';
            commentsContainer.setAttribute('data-post-comments', POST_ID);
            commentsContainer.hidden = true;
            commentsContainer.setAttribute('hidden', '');
            article.appendChild(commentsContainer);

            doc.body.appendChild(article);
        }

        CommunityComments.reset();
    });

    afterEach(() => {
        CommunityComments.reset();
        delete globalThis.document;
        delete globalThis.window;
    });

    test('1. desktop initial state: inline hidden, drawer closed, 0 fetches', () => {
        let fetchCalls = 0;
        const mockFetch = async () => { fetchCalls++; return { ok: true, json: async () => ({}) }; };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });

        const containers = doc.querySelectorAll('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(containers.length, 2);
        containers.forEach(c => {
            assert.strictEqual(c.hidden, true);
            assert.strictEqual(c.hasAttribute('hidden'), true);
            assert.strictEqual(c.childNodes.length, 0);
        });

        assert.strictEqual(CommunityComments.isDrawerOpen(), false);
        assert.strictEqual(fetchCalls, 0);
    });

    test('2. first desktop toggle: inline becomes visible, exactly 1 fetch, roots inserted into INLINE container, drawer closed', async () => {
        let fetchCalls = 0;
        let requestedUrl = null;
        const mockFetch = async (url) => {
            fetchCalls++;
            requestedUrl = url;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-d1', body: 'Bình luận desktop đầu tiên' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });

        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(container.hidden, false);
        assert.strictEqual(container.hasAttribute('hidden'), false);
        assert.strictEqual(toggleBtn.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(toggleBtn.classList.contains('is-active'), true);

        assert.strictEqual(fetchCalls, 1);
        assert.ok(requestedUrl.includes('/api/community/posts/' + POST_ID + '/comments?page=0&size=10'));
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);

        // Shell and roots are actually inserted inside INLINE container
        const listEl = container.querySelector('[data-thread-list="' + POST_ID + '"]');
        assert.ok(listEl, 'Inline listEl must exist');
        assert.ok(listEl.childNodes.length > 0, 'Inline listEl must contain rendered threads');
    });

    test('3. existing comment payload: visible comment body text exists under the post card', async () => {
        const mockFetch = async () => ({
            ok: true,
            json: async () => ({
                roots: [{
                    root: { id: 'root-d3', body: 'Đạo hữu viết rất xuất sắc!', author: { displayName: 'Hàn Lập' } },
                    replyCount: 0
                }],
                commentCount: 1,
                page: 0,
                size: 10,
                hasNext: false
            })
        });

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.ok(container.textContent.includes('Đạo hữu viết rất xuất sắc!'), 'Comment body must be in inline container');
        assert.ok(container.textContent.includes('Hàn Lập'), 'Author name must be in inline container');
        assert.ok(container.querySelector('[data-composer-root="' + POST_ID + '"]'), 'Composer must be in inline container');
    });

    test('4. second toggle: inline collapses', async () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-d4', body: 'Comment test 4' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');

        // First click expands
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(container.hidden, false);

        // Second click collapses
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(container.hidden, true);
        assert.strictEqual(container.hasAttribute('hidden'), true);
        assert.strictEqual(toggleBtn.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(toggleBtn.classList.contains('is-active'), false);
        assert.strictEqual(fetchCalls, 1, 'Collapsing makes zero network requests');
    });

    test('5. third toggle: inline reopens, cached roots render correctly, no unintended duplicate fetch', async () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-d5', body: 'Cached desktop body' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');

        // First click expands
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        // Second click collapses
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        // Third click reopens
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(container.hidden, false);
        assert.strictEqual(container.hasAttribute('hidden'), false);
        assert.strictEqual(toggleBtn.getAttribute('aria-expanded'), 'true');
        assert.ok(container.textContent.includes('Cached desktop body'));
        assert.strictEqual(fetchCalls, 1, 'Reopening must use cache without duplicate fetch');
    });

    test('6. cross-presentation cache: load mobile drawer, close drawer, switch to desktop, desktop inline renders cached roots', async () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-cross-1', body: 'Cross presentation shared comment!' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        // Start on mobile
        CommunityComments.init({ force: true, isMobile: true, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');

        // Mobile click -> drawer opens
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(CommunityComments.isDrawerOpen(), true);
        assert.strictEqual(fetchCalls, 1);
        const drawer = doc.getElementById('communityCommentsDrawer');
        assert.ok(drawer.textContent.includes('Cross presentation shared comment!'));

        // Close drawer
        CommunityComments.closeCommentsDrawer();
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);

        // Switch to desktop viewport
        CommunityComments.setMobileViewport(false);

        // Click desktop toggle button
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(container.hidden, false);
        assert.strictEqual(container.hasAttribute('hidden'), false);
        // Cached roots are populated in inline container
        assert.ok(container.textContent.includes('Cross presentation shared comment!'), 'Desktop inline must render cached roots');
        // ZERO additional fetch
        assert.strictEqual(fetchCalls, 1, 'Switching from mobile to desktop presentation must not refetch');
        assert.strictEqual(CommunityComments.isDrawerOpen(), false);
    });

    test('7. inverse: desktop inline loaded first, mobile drawer later renders cached roots', async () => {
        let fetchCalls = 0;
        const mockFetch = async () => {
            fetchCalls++;
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-cross-2', body: 'Desktop loaded first body' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        // Start on desktop
        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');

        // Click desktop toggle -> inline expands
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(container.hidden, false);
        assert.ok(container.textContent.includes('Desktop loaded first body'));
        assert.strictEqual(fetchCalls, 1);

        // Switch to mobile viewport
        CommunityComments.setMobileViewport(true);

        // Click toggle button on mobile
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(CommunityComments.isDrawerOpen(), true);
        const drawer = doc.getElementById('communityCommentsDrawer');
        assert.ok(drawer.textContent.includes('Desktop loaded first body'), 'Drawer must render cached roots from desktop');
        assert.strictEqual(fetchCalls, 1, 'Switching from desktop to mobile must not duplicate fetch');
    });

    test('8. profile and main-feed DOM contracts both work', async () => {
        // Clear body and build feed container and profile container side by side
        doc.body.childNodes = [];

        // Feed container
        const feedWrapper = doc.createElement('div');
        feedWrapper.id = 'communityFeedList';
        feedWrapper.setAttribute('data-authenticated', 'true');
        const feedCard = doc.createElement('article');
        feedCard.className = 'community-post-card';
        feedCard.setAttribute('data-post-id', 'feed-p1');
        const feedBtn = doc.createElement('button');
        feedBtn.className = 'post-metric post-comment-toggle-btn';
        feedBtn.setAttribute('data-action', 'toggle-comments');
        feedBtn.setAttribute('data-post-id', 'feed-p1');
        feedCard.appendChild(feedBtn);
        const feedContainer = doc.createElement('section');
        feedContainer.className = 'post-comments-container';
        feedContainer.setAttribute('data-post-comments', 'feed-p1');
        feedContainer.hidden = true;
        feedContainer.setAttribute('hidden', '');
        feedCard.appendChild(feedContainer);
        feedWrapper.appendChild(feedCard);
        doc.body.appendChild(feedWrapper);

        // Profile container
        const profileWrapper = doc.createElement('div');
        profileWrapper.className = 'community-profile-container';
        profileWrapper.setAttribute('data-authenticated', 'true');
        const profileCard = doc.createElement('article');
        profileCard.className = 'community-post-card';
        profileCard.setAttribute('data-post-id', 'prof-p1');
        const profBtn = doc.createElement('button');
        profBtn.className = 'post-metric post-comment-toggle-btn';
        profBtn.setAttribute('data-action', 'toggle-comments');
        profBtn.setAttribute('data-post-id', 'prof-p1');
        profileCard.appendChild(profBtn);
        const profContainer = doc.createElement('section');
        profContainer.className = 'post-comments-container';
        profContainer.setAttribute('data-post-comments', 'prof-p1');
        profContainer.hidden = true;
        profContainer.setAttribute('hidden', '');
        profileCard.appendChild(profContainer);
        profileWrapper.appendChild(profileCard);
        doc.body.appendChild(profileWrapper);

        const mockFetch = async (url) => {
            const pid = url.includes('feed-p1') ? 'feed-p1' : 'prof-p1';
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-' + pid, body: 'Body for ' + pid }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });

        // Toggle feed card
        doc.dispatchEvent({ type: 'click', target: feedBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(feedContainer.hidden, false);
        assert.ok(feedContainer.textContent.includes('Body for feed-p1'));

        // Toggle profile card
        doc.dispatchEvent({ type: 'click', target: profBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(profContainer.hidden, false);
        assert.ok(profContainer.textContent.includes('Body for prof-p1'));
    });

    test('9. thread loading remains explicit', async () => {
        let threadFetches = 0;
        const mockFetch = async (url) => {
            if (url.includes('/thread')) {
                threadFetches++;
                return {
                    ok: true,
                    json: async () => ({
                        root: { id: 'root-t9', body: 'Thread parent' },
                        replies: [{ id: 'reply-t9-1', body: 'Phản hồi chi tiết 1' }]
                    })
                };
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-t9', body: 'Thread parent' }, replyCount: 1 }],
                    commentCount: 2,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        assert.strictEqual(threadFetches, 0, 'Thread replies must NOT be fetched eagerly');

        const toggleThreadBtn = container.querySelector('[data-action="toggle-thread"][data-root-id="root-t9"]');
        assert.ok(toggleThreadBtn);
        assert.strictEqual(toggleThreadBtn.textContent, 'Xem 1 phản hồi');

        const repliesContainer = container.querySelector('.community-comment-replies[data-replies-for="root-t9"]');
        assert.ok(repliesContainer);
        assert.strictEqual(repliesContainer.hidden, true);

        // Click toggle-thread
        doc.dispatchEvent({ type: 'click', target: toggleThreadBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(threadFetches, 1, 'Exactly one thread fetch on explicit click');
        assert.strictEqual(repliesContainer.hidden, false);
        assert.ok(repliesContainer.textContent.includes('Phản hồi chi tiết 1'));
        assert.strictEqual(toggleThreadBtn.textContent, 'Ẩn phản hồi');
    });

    test('10. root/reply mutation count synchronization remains intact', async () => {
        let postBody = null;
        let currentCount = 3;
        const mockFetch = async (url, opts) => {
            if (opts && opts.method === 'POST') {
                postBody = JSON.parse(opts.body);
                currentCount = 7;
                return {
                    ok: true,
                    json: async () => ({
                        comment: { id: 'new-root', body: postBody.body },
                        updatedCommentCount: currentCount
                    })
                };
            }
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-sync', body: 'Sync root' }, replyCount: 0 }],
                    commentCount: currentCount,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        const container = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"]');
        const input = container.querySelector('[data-input-root="' + POST_ID + '"]');
        const submitBtn = container.querySelector('[data-action="submit-root-comment"]');
        assert.ok(input);
        assert.ok(submitBtn);

        input.value = 'New desktop root comment';
        doc.dispatchEvent({ type: 'click', target: submitBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(postBody.body, 'New desktop root comment');
        const countSpans = doc.querySelectorAll('.post-comment-count');
        countSpans.forEach(s => assert.strictEqual(s.textContent, '7'));
    });

    test('11. initial expansion defaults to NEWEST and appends sort=NEWEST', async () => {
        const requestedUrls = [];
        const mockFetch = async (url) => {
            requestedUrls.push(url);
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-n1', body: 'Newest root' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        assert.strictEqual(requestedUrls.length, 1);
        assert.ok(requestedUrls[0].includes('sort=NEWEST'), 'Must request sort=NEWEST by default: ' + requestedUrls[0]);
        assert.strictEqual(CommunityComments.getCurrentSort(POST_ID), 'NEWEST');

        const sortControls = doc.querySelector('.post-comments-container[data-post-comments="' + POST_ID + '"] .kl-sort-dropdown');
        assert.ok(sortControls, 'Sort dropdown must be rendered in desktop container');
    });

    test('12. switching to FEATURED triggers fetch with sort=FEATURED and switching back uses cache', async () => {
        const requestedUrls = [];
        const mockFetch = async (url) => {
            requestedUrls.push(url);
            const isFeatured = url.includes('sort=FEATURED');
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: isFeatured ? 'root-f' : 'root-n', body: isFeatured ? 'Featured' : 'Newest' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 1);
        assert.strictEqual(CommunityComments.getCurrentSort(POST_ID), 'NEWEST');

        // Switch to FEATURED
        await CommunityComments.switchSort(POST_ID, 'FEATURED');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2);
        assert.ok(requestedUrls[1].includes('sort=FEATURED'));
        assert.strictEqual(CommunityComments.getCurrentSort(POST_ID), 'FEATURED');

        // Switch back to NEWEST (served from cache)
        await CommunityComments.switchSort(POST_ID, 'NEWEST');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2, 'Must not issue another network fetch for cached NEWEST');
        assert.strictEqual(CommunityComments.getCurrentSort(POST_ID), 'NEWEST');
    });

    test('13. reaction update event invalidates FEATURED cache', async () => {
        const requestedUrls = [];
        const mockFetch = async (url) => {
            requestedUrls.push(url);
            return {
                ok: true,
                json: async () => ({
                    roots: [{ root: { id: 'root-1', body: 'Root' }, replyCount: 0 }],
                    commentCount: 1,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 1);

        // Switch to FEATURED (fetch 2)
        await CommunityComments.switchSort(POST_ID, 'FEATURED');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2);

        // Switch to NEWEST (served from cache, length remains 2)
        await CommunityComments.switchSort(POST_ID, 'NEWEST');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2);

        // Dispatch reaction update
        doc.dispatchEvent({
            type: 'kiemlai:reaction-updated',
            detail: { targetType: 'COMMENT', targetId: 'root-1' }
        });

        // Switching back to FEATURED must now refetch because FEATURED cache was invalidated
        await CommunityComments.switchSort(POST_ID, 'FEATURED');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 3, 'Must refetch FEATURED after reaction invalidation');
    });

    test('14. load NEWEST, load FEATURED with different root order/content, switch repeatedly without cross-sort cache contamination', async () => {
        const requestedUrls = [];
        const mockFetch = async (url) => {
            requestedUrls.push(url);
            if (url.includes('sort=FEATURED')) {
                return {
                    ok: true,
                    json: async () => ({
                        roots: [
                            { root: { id: 'root-featured-1', body: 'Featured Top Engagement' }, replyCount: 5 },
                            { root: { id: 'root-featured-2', body: 'Featured Second Engagement' }, replyCount: 2 }
                        ],
                        commentCount: 2,
                        page: 0,
                        size: 10,
                        hasNext: false
                    })
                };
            }
            // NEWEST response (different roots and order)
            return {
                ok: true,
                json: async () => ({
                    roots: [
                        { root: { id: 'root-newest-2', body: 'Newest Second Item' }, replyCount: 0 },
                        { root: { id: 'root-newest-1', body: 'Newest First Item' }, replyCount: 0 }
                    ],
                    commentCount: 2,
                    page: 0,
                    size: 10,
                    hasNext: false
                })
            };
        };

        CommunityComments.init({ force: true, isMobile: false, fetch: mockFetch });
        const toggleBtn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + POST_ID + '"]');
        doc.dispatchEvent({ type: 'click', target: toggleBtn, preventDefault: () => {} });
        await new Promise(resolve => setTimeout(resolve, 15));

        // Step 1: Initial load is NEWEST
        assert.strictEqual(requestedUrls.length, 1);
        assert.ok(requestedUrls[0].includes('sort=NEWEST'));
        assert.strictEqual(CommunityComments._states.has(POST_ID), false, 'Must never create unqualified postId cache key');
        assert.strictEqual(CommunityComments._states.has(POST_ID + ':NEWEST'), true);
        assert.strictEqual(CommunityComments._states.has(POST_ID + ':FEATURED'), false);

        const listEl = doc.querySelector('[data-thread-list="' + POST_ID + '"]');
        let renderedThreads = listEl.querySelectorAll('.community-comment-thread');
        assert.strictEqual(renderedThreads.length, 2);
        assert.strictEqual(renderedThreads[0].getAttribute('data-thread-id'), 'root-newest-2');
        assert.strictEqual(renderedThreads[1].getAttribute('data-thread-id'), 'root-newest-1');

        // Step 2: Switch to FEATURED
        await CommunityComments.switchSort(POST_ID, 'FEATURED');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2);
        assert.ok(requestedUrls[1].includes('sort=FEATURED'));
        assert.strictEqual(CommunityComments._states.has(POST_ID), false, 'Must never create unqualified postId cache key');
        assert.strictEqual(CommunityComments._states.has(POST_ID + ':FEATURED'), true);

        renderedThreads = listEl.querySelectorAll('.community-comment-thread');
        assert.strictEqual(renderedThreads.length, 2);
        assert.strictEqual(renderedThreads[0].getAttribute('data-thread-id'), 'root-featured-1');
        assert.strictEqual(renderedThreads[1].getAttribute('data-thread-id'), 'root-featured-2');

        // Step 3: Switch back to NEWEST (served strictly from cache, 0 new network calls)
        await CommunityComments.switchSort(POST_ID, 'NEWEST');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2, 'Zero network calls when switching to cached NEWEST');
        renderedThreads = listEl.querySelectorAll('.community-comment-thread');
        assert.strictEqual(renderedThreads.length, 2);
        assert.strictEqual(renderedThreads[0].getAttribute('data-thread-id'), 'root-newest-2');
        assert.strictEqual(renderedThreads[1].getAttribute('data-thread-id'), 'root-newest-1');

        // Step 4: Switch back to FEATURED (served strictly from cache, 0 new network calls)
        await CommunityComments.switchSort(POST_ID, 'FEATURED');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2, 'Zero network calls when switching to cached FEATURED');
        renderedThreads = listEl.querySelectorAll('.community-comment-thread');
        assert.strictEqual(renderedThreads.length, 2);
        assert.strictEqual(renderedThreads[0].getAttribute('data-thread-id'), 'root-featured-1');
        assert.strictEqual(renderedThreads[1].getAttribute('data-thread-id'), 'root-featured-2');

        // Step 5: Switch back to NEWEST again
        await CommunityComments.switchSort(POST_ID, 'NEWEST');
        await new Promise(resolve => setTimeout(resolve, 15));
        assert.strictEqual(requestedUrls.length, 2);
        renderedThreads = listEl.querySelectorAll('.community-comment-thread');
        assert.strictEqual(renderedThreads[0].getAttribute('data-thread-id'), 'root-newest-2');
        assert.strictEqual(renderedThreads[1].getAttribute('data-thread-id'), 'root-newest-1');
        assert.strictEqual(CommunityComments._states.has(POST_ID), false, 'Unqualified postId key remains non-existent throughout');
    });
});
