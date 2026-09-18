const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const ReplyComposerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-reply-composer.js'));

const {
    REPLY_COMPOSER_CLASS,
    REPLY_COMPOSER_FORM_ID,
    REPLY_INPUT_CLASS,
    REPLY_STATUS_CLASS,
    REPLY_SUBMIT_CLASS,
    REPLY_CANCEL_CLASS,
    REPLY_CONTEXT_CLASS,
    AUTH_MODAL_ID,
    AUTH_LOGIN_LINK_ID,
    AUTH_REGISTER_LINK_ID,
    AUTH_CANCEL_BTN_ID,
    initReaderBlockDiscussionReplyComposer,
    resetReplyComposerState,
    openReplyComposer,
    closeReplyComposer,
    closeAuthModal,
    handleSubmit,
    handleReplyButtonClick,
    handleReplyResumeRequested,
    handleGuestReply,
    buildGuestReturnUrl,
    isGuestUser,
    getActiveReplyTarget,
    getActiveComposerEl,
    isSubmittingReply
} = ReplyComposerModule;

// ============================================================================
// Lightweight DOM Test Fixture Helpers
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
        this._textContent = '';
        this.isFocused = false;
        this.disabled = !!attributes.disabled;
        this._value = attributes.value !== undefined ? String(attributes.value) : '';

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    get id() {
        return this.getAttribute('id') || '';
    }

    set id(val) {
        this.setAttribute('id', val);
    }

    get className() {
        return this.getAttribute('class') || '';
    }

    set className(val) {
        this.setAttribute('class', val);
    }

    get value() {
        return this._value;
    }

    set value(val) {
        this._value = String(val);
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

    get hidden() {
        return this.getAttribute('hidden') !== null;
    }

    set hidden(val) {
        if (val) {
            this.setAttribute('hidden', '');
        } else {
            this.removeAttribute('hidden');
        }
    }

    get href() {
        return this.getAttribute('href') || '';
    }

    set href(val) {
        this.setAttribute('href', val);
    }

    setAttribute(name, value) {
        const strVal = String(value);
        this.attributes[name] = strVal;
        if (name === 'class') {
            this.classList.classes = new Set(strVal.split(/\s+/).filter(Boolean));
        }
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
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

    focus() {
        this.isFocused = true;
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    dispatchEvent(event) {
        const fns = this.listeners[event.type] || [];
        for (const fn of fns) {
            fn.call(this, event);
        }
        return true;
    }

    closest(selector) {
        let current = this;
        while (current) {
            if (matchesSelector(current, selector)) {
                return current;
            }
            current = current.parentElement || current.parentNode;
        }
        return null;
    }

    querySelector(selector) {
        const results = this.querySelectorAll(selector);
        return results.length > 0 ? results[0] : null;
    }

    querySelectorAll(selector) {
        const parts = selector.trim().split(/\s+/);
        if (parts.length === 1) {
            const matches = [];
            function walk(node) {
                for (const child of node.childNodes) {
                    if (matchesSelector(child, parts[0])) {
                        matches.push(child);
                    }
                    walk(child);
                }
            }
            walk(this);
            return matches;
        }

        let currentLevel = [this];
        for (const part of parts) {
            const nextLevel = [];
            for (const node of currentLevel) {
                function walk(n) {
                    for (const child of n.childNodes) {
                        if (matchesSelector(child, part) && !nextLevel.includes(child)) {
                            nextLevel.push(child);
                        }
                        walk(child);
                    }
                }
                walk(node);
            }
            currentLevel = nextLevel;
        }
        return currentLevel;
    }
}

function matchesSelector(el, selector) {
    if (!el || !el.tagName) return false;
    const s = selector.trim();
    if (s.includes('[') && s.endsWith(']')) {
        const tag = s.slice(0, s.indexOf('['));
        if (tag && el.tagName.toLowerCase() !== tag.toLowerCase()) {
            return false;
        }
        const inner = s.slice(s.indexOf('[') + 1, -1);
        if (inner.includes('=')) {
            const [attr, val] = inner.split('=');
            const cleanVal = val.replace(/^["']|["']$/g, '');
            return el.getAttribute(attr.trim()) === cleanVal;
        }
        return el.hasAttribute(inner.trim());
    }
    if (s.startsWith('#')) {
        return el.getAttribute('id') === s.slice(1);
    }
    if (s.startsWith('.')) {
        return el.classList.contains(s.slice(1));
    }
    return el.tagName.toLowerCase() === s.toLowerCase();
}

class FakeDocument {
    constructor() {
        this.documentElement = new FakeElement('html');
        this.head = new FakeElement('head');
        this.body = new FakeElement('body');
        this.documentElement.appendChild(this.head);
        this.documentElement.appendChild(this.body);
        this.listeners = {};
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }

    getElementById(id) {
        return this.querySelector('#' + id);
    }

    querySelector(selector) {
        const results = this.querySelectorAll(selector);
        return results.length > 0 ? results[0] : null;
    }

    querySelectorAll(selector) {
        const matches = [];
        function walk(node) {
            for (const child of node.childNodes) {
                if (matchesSelector(child, selector)) {
                    matches.push(child);
                }
                walk(child);
            }
        }
        walk(this.documentElement);
        return matches;
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    dispatchEvent(event) {
        const fns = this.listeners[event.type] || [];
        for (const fn of fns) {
            fn(event);
        }
        return true;
    }
}

// ============================================================================
// Test Suite
// ============================================================================

describe('MS-05E5G4A Novel Block Discussion Reply Composer Tests', () => {

    let doc;
    let drawer;
    let content;
    let mockDrawerModule;
    let refreshActiveDiscussionCalled;

    const CHAPTER_ID = '11111111-1111-1111-1111-111111111111';
    const BLOCK_KEY = 'blk-0123456789abcdef-1';
    const ROOT_ID = 'root-uuid-1';
    const REPLY_ID = 'reply-uuid-2';

    beforeEach(() => {
        resetReplyComposerState();
        refreshActiveDiscussionCalled = 0;

        doc = new FakeDocument();

        // Add CSRF meta tags
        const csrfMeta = doc.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'test-csrf-token-123');
        doc.head.appendChild(csrfMeta);

        const csrfHeaderMeta = doc.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        doc.head.appendChild(csrfHeaderMeta);

        // Drawer element with chapter slug & authenticated state
        drawer = doc.createElement('aside');
        drawer.id = 'novelBlockDiscussionDrawer';
        drawer.setAttribute('data-chapter-slug', 'chuong-1');
        drawer.setAttribute('data-authenticated', 'true');
        doc.body.appendChild(drawer);

        // Authenticated root composer exists
        const rootComposer = doc.createElement('form');
        rootComposer.id = 'novelBlockDiscussionComposer';
        drawer.appendChild(rootComposer);

        // Drawer content
        content = doc.createElement('section');
        content.id = 'novelBlockDiscussionContent';
        drawer.appendChild(content);

        // Thread container
        const threadCard = doc.createElement('article');
        threadCard.className = 'novel-block-discussion-thread';
        threadCard.setAttribute('data-root-id', ROOT_ID);
        content.appendChild(threadCard);

        // Root comment
        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', ROOT_ID);

        const rootHeader = doc.createElement('header');
        rootHeader.className = 'novel-comment-header';
        const rootAuthor = doc.createElement('span');
        rootAuthor.className = 'novel-comment-author';
        rootAuthor.textContent = 'Tiêu Viêm';
        rootHeader.appendChild(rootAuthor);
        rootEl.appendChild(rootHeader);

        const rootBody = doc.createElement('div');
        rootBody.className = 'novel-comment-body';
        rootBody.textContent = 'Bình luận mở đầu';
        rootEl.appendChild(rootBody);

        const rootActions = doc.createElement('div');
        rootActions.className = 'novel-comment-actions';
        const rootReplyBtn = doc.createElement('button');
        rootReplyBtn.className = 'novel-comment-reply-btn';
        rootReplyBtn.setAttribute('data-action', 'reply');
        rootReplyBtn.setAttribute('data-comment-id', ROOT_ID);
        rootReplyBtn.setAttribute('data-root-id', ROOT_ID);
        rootReplyBtn.setAttribute('data-author-name', 'Tiêu Viêm');
        rootReplyBtn.textContent = 'Trả lời';
        rootActions.appendChild(rootReplyBtn);
        rootEl.appendChild(rootActions);

        threadCard.appendChild(rootEl);

        // Replies container
        const repliesContainer = doc.createElement('div');
        repliesContainer.className = 'novel-comment-replies';
        threadCard.appendChild(repliesContainer);

        // Reply comment
        const replyEl = doc.createElement('article');
        replyEl.className = 'novel-comment novel-comment--reply';
        replyEl.setAttribute('data-reply-id', REPLY_ID);
        replyEl.setAttribute('data-comment-id', REPLY_ID);

        const replyHeader = doc.createElement('header');
        replyHeader.className = 'novel-comment-header';
        const replyAuthor = doc.createElement('span');
        replyAuthor.className = 'novel-comment-author';
        replyAuthor.textContent = 'Dược Lão';
        replyHeader.appendChild(replyAuthor);
        replyEl.appendChild(replyHeader);

        const replyBody = doc.createElement('div');
        replyBody.className = 'novel-comment-body';
        replyBody.textContent = 'Phản hồi đầu tiên';
        replyEl.appendChild(replyBody);

        const replyActions = doc.createElement('div');
        replyActions.className = 'novel-comment-actions';
        const replyBtn = doc.createElement('button');
        replyBtn.className = 'novel-comment-reply-btn';
        replyBtn.setAttribute('data-action', 'reply');
        replyBtn.setAttribute('data-comment-id', REPLY_ID);
        replyBtn.setAttribute('data-reply-id', REPLY_ID);
        replyBtn.setAttribute('data-root-id', ROOT_ID);
        replyBtn.setAttribute('data-author-name', 'Dược Lão');
        replyBtn.textContent = 'Trả lời';
        replyActions.appendChild(replyBtn);
        replyEl.appendChild(replyActions);

        repliesContainer.appendChild(replyEl);

        // Tombstone reply
        const tombstoneEl = doc.createElement('article');
        tombstoneEl.className = 'novel-comment novel-comment--reply is-tombstone';
        tombstoneEl.setAttribute('data-reply-id', 'tombstone-1');
        tombstoneEl.setAttribute('data-comment-id', 'tombstone-1');
        const tombBody = doc.createElement('div');
        tombBody.className = 'novel-comment-body novel-comment-body--tombstone';
        tombBody.textContent = '[Bình luận đã bị xóa]';
        tombstoneEl.appendChild(tombBody);
        repliesContainer.appendChild(tombstoneEl);

        mockDrawerModule = {
            getActiveContext: () => ({
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                contentVersion: 1,
                threadCount: 1,
                canonicalText: 'Text',
                authoritative: true
            }),
            refreshActiveDiscussion: () => {
                refreshActiveDiscussionCalled++;
                return Promise.resolve();
            }
        };
    });

    test('D. authenticated click opens composer for exact comment with target context', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        assert.notStrictEqual(rootReplyBtn, null);

        // Simulate click
        doc.dispatchEvent({
            type: 'click',
            target: rootReplyBtn,
            preventDefault: () => {}
        });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composer, null);
        assert.strictEqual(composer.parentElement.getAttribute('data-comment-id'), ROOT_ID);

        const targetName = composer.querySelector('.novel-reply-composer-target-name');
        assert.strictEqual(targetName.textContent, 'Tiêu Viêm');

        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        assert.notStrictEqual(textarea, null);
        assert.strictEqual(textarea.isFocused, true);

        const activeTarget = getActiveReplyTarget();
        assert.notStrictEqual(activeTarget, null);
        assert.strictEqual(activeTarget.commentId, ROOT_ID);
        assert.strictEqual(activeTarget.rootId, ROOT_ID);
    });

    test('D2. authenticated click on child reply opens composer under that child reply', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const replyBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
        assert.notStrictEqual(replyBtn, null);

        doc.dispatchEvent({
            type: 'click',
            target: replyBtn,
            preventDefault: () => {}
        });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composer, null);
        assert.strictEqual(composer.parentElement.getAttribute('data-comment-id'), REPLY_ID);

        const targetName = composer.querySelector('.novel-reply-composer-target-name');
        assert.strictEqual(targetName.textContent, 'Dược Lão');

        const activeTarget = getActiveReplyTarget();
        assert.strictEqual(activeTarget.commentId, REPLY_ID);
        assert.strictEqual(activeTarget.rootId, ROOT_ID);
    });

    test('E. switching target updates composer safely and binds draft to selected target', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        const childReplyBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');

        // Click root
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });
        let composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.strictEqual(composer.parentElement.getAttribute('data-comment-id'), ROOT_ID);

        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Draft for root';

        // Click child reply
        doc.dispatchEvent({ type: 'click', target: childReplyBtn });
        composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.strictEqual(composer.parentElement.getAttribute('data-comment-id'), REPLY_ID);

        const newTextarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        // Previous draft is not accidentally attached
        assert.strictEqual(newTextarea.value, '');

        const activeTarget = getActiveReplyTarget();
        assert.strictEqual(activeTarget.commentId, REPLY_ID);
    });

    test('F. cancel clears target state and closes composer', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composer, null);

        const cancelBtn = composer.querySelector('.' + REPLY_CANCEL_CLASS);
        assert.notStrictEqual(cancelBtn, null);

        doc.dispatchEvent({
            type: 'click',
            target: cancelBtn,
            preventDefault: () => {}
        });

        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);
        assert.strictEqual(getActiveReplyTarget(), null);
        assert.strictEqual(getActiveComposerEl(), null);
    });

    test('G & H. submit sends exact parent ID and successful 201 refreshes authoritative drawer', async () => {
        let postedUrl = null;
        let postedHeaders = null;
        let postedBody = null;

        const fakeFetch = (url, options) => {
            postedUrl = url;
            postedHeaders = options.headers;
            postedBody = JSON.parse(options.body);
            return Promise.resolve({
                status: 201,
                json: () => Promise.resolve({ commentId: 'new-reply-uuid-999' })
            });
        };

        initReaderBlockDiscussionReplyComposer(doc, {
            fetchFn: fakeFetch,
            drawerModule: mockDrawerModule
        });

        const childReplyBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: childReplyBtn });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Phản hồi chi tiết cho Dược Lão';

        // Submit form
        doc.dispatchEvent({
            type: 'submit',
            target: composer,
            preventDefault: () => {}
        });

        await new Promise(r => setTimeout(r, 10));

        // Exact endpoint and payload
        assert.strictEqual(postedUrl, `/api/novel/chapters/${CHAPTER_ID}/comments/${REPLY_ID}/replies`);
        assert.strictEqual(postedHeaders['X-CSRF-TOKEN'], 'test-csrf-token-123');
        assert.strictEqual(postedBody.body, 'Phản hồi chi tiết cho Dược Lão');

        // Drawer refresh triggered
        assert.strictEqual(refreshActiveDiscussionCalled, 1);

        // Composer closed and target cleared
        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);
        assert.strictEqual(getActiveReplyTarget(), null);
        assert.strictEqual(isSubmittingReply(), false);
    });

    test('I. reply preserves root threadCount and refreshes chapter indicators', async () => {
        let indicatorsRefreshed = false;
        const mockIndicatorsModule = {
            refreshChapterIndicators: () => {
                indicatorsRefreshed = true;
                return Promise.resolve();
            }
        };

        const fakeFetch = () => Promise.resolve({
            status: 201,
            json: () => Promise.resolve({ commentId: 'new-reply-uuid' })
        });

        initReaderBlockDiscussionReplyComposer(doc, {
            fetchFn: fakeFetch,
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule
        });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        composer.querySelector('.' + REPLY_INPUT_CLASS).value = 'Reply content';

        doc.dispatchEvent({
            type: 'submit',
            target: composer,
            preventDefault: () => {}
        });

        await new Promise(r => setTimeout(r, 10));

        // Active context threadCount remained 1 (root threads only)
        assert.strictEqual(mockDrawerModule.getActiveContext().threadCount, 1);
        // Indicators refreshed to update user-facing commentCount
        assert.strictEqual(indicatorsRefreshed, true);
    });

    test('J. failed submit (403, 404, 500) preserves draft and shows friendly error', async () => {
        let returnStatus = 403;
        const fakeFetch = () => Promise.resolve({
            status: returnStatus,
            json: () => Promise.resolve({})
        });

        initReaderBlockDiscussionReplyComposer(doc, {
            fetchFn: fakeFetch,
            drawerModule: mockDrawerModule
        });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Important unsaved reply text';

        doc.dispatchEvent({
            type: 'submit',
            target: composer,
            preventDefault: () => {}
        });

        await new Promise(r => setTimeout(r, 10));

        // Draft preserved!
        assert.strictEqual(textarea.value, 'Important unsaved reply text');
        assert.strictEqual(textarea.disabled, false);

        const statusEl = composer.querySelector('.' + REPLY_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent.includes('Phiên đăng nhập đã hết hạn'), true);
        assert.strictEqual(isSubmittingReply(), false);

        // Test 404 parent missing
        returnStatus = 404;
        doc.dispatchEvent({
            type: 'submit',
            target: composer,
            preventDefault: () => {}
        });

        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(textarea.value, 'Important unsaved reply text');
        assert.strictEqual(statusEl.textContent.includes('Bình luận không còn tồn tại'), true);

        // Test 500 server error
        returnStatus = 500;
        doc.dispatchEvent({
            type: 'submit',
            target: composer,
            preventDefault: () => {}
        });

        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(textarea.value, 'Important unsaved reply text');
        assert.strictEqual(statusEl.textContent.includes('Không thể gửi phản hồi'), true);
    });

    test('K. in-flight double submit is blocked', async () => {
        let callCount = 0;
        let resolvePromise;
        const pendingPromise = new Promise(r => { resolvePromise = r; });

        const fakeFetch = () => {
            callCount++;
            return pendingPromise;
        };

        initReaderBlockDiscussionReplyComposer(doc, {
            fetchFn: fakeFetch,
            drawerModule: mockDrawerModule
        });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        composer.querySelector('.' + REPLY_INPUT_CLASS).value = 'Submitting...';

        doc.dispatchEvent({ type: 'submit', target: composer, preventDefault: () => {} });
        assert.strictEqual(callCount, 1);
        assert.strictEqual(isSubmittingReply(), true);

        // Second submit while in-flight
        doc.dispatchEvent({ type: 'submit', target: composer, preventDefault: () => {} });
        assert.strictEqual(callCount, 1);

        resolvePromise({
            status: 201,
            json: () => Promise.resolve({ commentId: 'reply-123' })
        });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(isSubmittingReply(), false);
    });

    test('L. chapter change invalidates pending reply UI and resets composer', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });
        assert.notStrictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);

        // Chapter changed
        doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });

        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);
        assert.strictEqual(getActiveReplyTarget(), null);
    });

    test('M. A3 reply-resume event opens composer for exact live target', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({
            type: 'kiemlai:comment-reply-resume-requested',
            detail: {
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                threadId: ROOT_ID,
                commentId: REPLY_ID
            }
        });

        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composer, null);
        assert.strictEqual(composer.parentElement.getAttribute('data-comment-id'), REPLY_ID);

        const targetName = composer.querySelector('.novel-reply-composer-target-name');
        assert.strictEqual(targetName.textContent, 'Dược Lão');

        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        assert.strictEqual(textarea.isFocused, true);
    });

    test('N. missing or tombstoned resume target does NOT silently retarget or reply to root', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        // Missing comment ID
        doc.dispatchEvent({
            type: 'kiemlai:comment-reply-resume-requested',
            detail: {
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                threadId: ROOT_ID,
                commentId: 'non-existent-comment-uuid'
            }
        });

        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);
        assert.strictEqual(getActiveReplyTarget(), null);

        // Tombstone comment ID
        doc.dispatchEvent({
            type: 'kiemlai:comment-reply-resume-requested',
            detail: {
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                threadId: ROOT_ID,
                commentId: 'tombstone-1'
            }
        });

        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);
        assert.strictEqual(getActiveReplyTarget(), null);
    });

    test('Guest UX: guest clicking Reply opens auth modal with validated semantic returnTo target', () => {
        // Configure as guest
        drawer.setAttribute('data-authenticated', 'false');

        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const childReplyBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
        doc.dispatchEvent({
            type: 'click',
            target: childReplyBtn,
            preventDefault: () => {}
        });

        // Reply composer did NOT open
        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);

        // Auth modal opened
        const modal = doc.getElementById(AUTH_MODAL_ID);
        assert.notStrictEqual(modal, null);
        assert.strictEqual(modal.hidden, false);
        assert.strictEqual(modal.getAttribute('aria-hidden'), 'false');

        const expectedReturn = '/novel/chapters/chuong-1?discussionBlock=' +
            encodeURIComponent(BLOCK_KEY) +
            '&threadId=' + encodeURIComponent(ROOT_ID) +
            '&replyTo=' + encodeURIComponent(REPLY_ID) +
            '&intent=reply';

        const loginLink = doc.getElementById(AUTH_LOGIN_LINK_ID);
        assert.strictEqual(loginLink.getAttribute('href'), '/login?returnTo=' + encodeURIComponent(expectedReturn));

        const registerLink = doc.getElementById(AUTH_REGISTER_LINK_ID);
        assert.strictEqual(registerLink.getAttribute('href'), '/register?returnTo=' + encodeURIComponent(expectedReturn));

        // Click cancel closes modal
        const cancelBtn = doc.getElementById(AUTH_CANCEL_BTN_ID);
        doc.dispatchEvent({
            type: 'click',
            target: cancelBtn,
            preventDefault: () => {}
        });

        assert.strictEqual(modal.hidden, true);
        assert.strictEqual(modal.getAttribute('aria-hidden'), 'true');
    });

    test('O. existing A3 DOM contract remains completely intact', () => {
        initReaderBlockDiscussionReplyComposer(doc, { drawerModule: mockDrawerModule });

        const threadCard = content.querySelector('.novel-block-discussion-thread');
        assert.strictEqual(threadCard.getAttribute('data-root-id'), ROOT_ID);
        assert.strictEqual(threadCard.getAttribute('data-comment-id'), null);
        assert.strictEqual(threadCard.getAttribute('data-thread-id'), null);

        const rootEl = threadCard.querySelector('.novel-comment--root');
        assert.strictEqual(rootEl.getAttribute('data-comment-id'), ROOT_ID);

        const replyEl = threadCard.querySelector('.novel-comment--reply');
        assert.strictEqual(replyEl.getAttribute('data-reply-id'), REPLY_ID);
        assert.strictEqual(replyEl.getAttribute('data-comment-id'), REPLY_ID);
    });

    test('P. regression: switching A -> B -> A does not let old in-flight request A close new composer or wipe new draft', async () => {
        let resolveFetchA;
        const deferredFetchA = new Promise(resolve => { resolveFetchA = resolve; });
        let fetchCalls = [];

        const fakeFetch = (url, options) => {
            fetchCalls.push({ url, options });
            if (url.includes(ROOT_ID)) {
                return deferredFetchA;
            }
            return Promise.resolve({
                status: 201,
                json: () => Promise.resolve({ commentId: 'reply-to-b-uuid' })
            });
        };

        initReaderBlockDiscussionReplyComposer(doc, {
            fetchFn: fakeFetch,
            drawerModule: mockDrawerModule
        });

        // 1. Open composer for Comment A (ROOT_ID)
        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

        const composerA = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composerA, null);
        assert.strictEqual(composerA.parentElement.getAttribute('data-comment-id'), ROOT_ID);

        const textareaA = composerA.querySelector('.' + REPLY_INPUT_CLASS);
        textareaA.value = 'Initial draft for Comment A';

        // 2. Submit A; request remains in-flight
        doc.dispatchEvent({
            type: 'submit',
            target: composerA,
            preventDefault: () => {}
        });
        assert.strictEqual(fetchCalls.length, 1);
        assert.strictEqual(fetchCalls[0].url.includes(ROOT_ID), true);

        // 3. User switches to Comment B (REPLY_ID)
        const childReplyBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: childReplyBtn });

        const composerB = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composerB, null);
        assert.strictEqual(composerB.parentElement.getAttribute('data-comment-id'), REPLY_ID);

        // 4. User switches back to Comment A (ROOT_ID)
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

        const newComposerA = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(newComposerA, null);
        assert.strictEqual(newComposerA.parentElement.getAttribute('data-comment-id'), ROOT_ID);
        assert.notStrictEqual(newComposerA, composerA);

        // 5. User types fresh draft into newly opened Comment A composer
        const newTextareaA = newComposerA.querySelector('.' + REPLY_INPUT_CLASS);
        newTextareaA.value = 'Fresh draft for Comment A after switching';

        // 6. Old request A completes with HTTP 201
        resolveFetchA({
            status: 201,
            json: () => Promise.resolve({ commentId: 'old-stale-reply-uuid' })
        });
        await new Promise(r => setTimeout(r, 15));

        // 7. Deterministic invariants:
        // - Old response must NOT close new composer
        assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), newComposerA);
        // - Active target must remain Comment A
        const activeTarget = getActiveReplyTarget();
        assert.notStrictEqual(activeTarget, null);
        assert.strictEqual(activeTarget.commentId, ROOT_ID);
        // - New draft must be preserved and remain editable
        assert.strictEqual(newTextareaA.value, 'Fresh draft for Comment A after switching');
        assert.strictEqual(newTextareaA.disabled, false);
        // - Stale response must NOT trigger unwarranted drawer refresh
        assert.strictEqual(refreshActiveDiscussionCalled, 0);
    });

    test('Q. cross-mutation teardown: opening reply closes active edit composer via closeEditComposer(false)', () => {
        let closeEditCalled = false;
        let closeEditRestoreFocusArg = null;
        const mockEditComposer = {
            closeEditComposer: (restoreFocus) => {
                closeEditCalled = true;
                closeEditRestoreFocusArg = restoreFocus;
            }
        };

        initReaderBlockDiscussionReplyComposer(doc, {
            drawerModule: mockDrawerModule,
            editComposerModule: mockEditComposer
        });

        const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault: () => {} });

        assert.strictEqual(closeEditCalled, true);
        assert.strictEqual(closeEditRestoreFocusArg, false);
        const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.notStrictEqual(composer, null);
        assert.strictEqual(getActiveReplyTarget().commentId, ROOT_ID);
    });

    describe('MS-05E5H2F2A Shared createReply Mutation Client Wiring', () => {
        test('A & B & C. drawer submit calls shared createReply exactly once with canonical inputs and triggers drawer refresh', async () => {
            let createReplyCalls = [];
            const mockMutationsClient = {
                createReply: async (input, options) => {
                    createReplyCalls.push({ input, options });
                    return {
                        ok: true,
                        status: 201,
                        commentId: 'reply-created-shared-123'
                    };
                }
            };

            initReaderBlockDiscussionReplyComposer(doc, {
                commentMutations: mockMutationsClient,
                drawerModule: mockDrawerModule
            });

            const childReplyBtn = content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
            doc.dispatchEvent({ type: 'click', target: childReplyBtn });

            const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
            const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
            textarea.value = 'Shared client test reply';

            // Submit
            doc.dispatchEvent({
                type: 'submit',
                target: composer,
                preventDefault: () => {}
            });

            await new Promise(r => setTimeout(r, 10));

            // A. Calls shared createReply exactly once
            assert.strictEqual(createReplyCalls.length, 1);

            // B. Correct canonical inputs passed
            const call = createReplyCalls[0];
            assert.strictEqual(call.input.chapterId, CHAPTER_ID);
            assert.strictEqual(call.input.parentCommentId, REPLY_ID);
            assert.strictEqual(call.input.body, 'Shared client test reply');

            // C. Successful createReply triggers existing authoritative drawer refresh
            assert.strictEqual(refreshActiveDiscussionCalled, 1);
            assert.strictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null);
            assert.strictEqual(getActiveReplyTarget(), null);
            assert.strictEqual(isSubmittingReply(), false);
        });

        test('D. client failure leaves existing drawer failure and draft semantics intact', async () => {
            const mockMutationsClient = {
                createReply: async () => {
                    const err = new Error('HTTP 404');
                    err.status = 404;
                    throw err;
                }
            };

            initReaderBlockDiscussionReplyComposer(doc, {
                commentMutations: mockMutationsClient,
                drawerModule: mockDrawerModule
            });

            const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
            doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

            const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
            const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
            textarea.value = 'Draft to be preserved on failure';

            doc.dispatchEvent({
                type: 'submit',
                target: composer,
                preventDefault: () => {}
            });

            await new Promise(r => setTimeout(r, 10));

            // Draft preserved, re-enabled
            assert.strictEqual(textarea.value, 'Draft to be preserved on failure');
            assert.strictEqual(textarea.disabled, false);

            // Friendly error rendered
            const statusEl = composer.querySelector('.' + REPLY_STATUS_CLASS);
            assert.strictEqual(statusEl.textContent, 'Bình luận không còn tồn tại.');
            assert.strictEqual(isSubmittingReply(), false);
            assert.strictEqual(refreshActiveDiscussionCalled, 0);
        });

        test('E. double submit still does not cause duplicate createReply calls', async () => {
            let callCount = 0;
            let resolveSharedCall;
            const mockMutationsClient = {
                createReply: () => {
                    callCount++;
                    return new Promise(resolve => {
                        resolveSharedCall = resolve;
                    });
                }
            };

            initReaderBlockDiscussionReplyComposer(doc, {
                commentMutations: mockMutationsClient,
                drawerModule: mockDrawerModule
            });

            const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
            doc.dispatchEvent({ type: 'click', target: rootReplyBtn });

            const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
            composer.querySelector('.' + REPLY_INPUT_CLASS).value = 'Single flight check';

            // First submit
            doc.dispatchEvent({ type: 'submit', target: composer, preventDefault: () => {} });
            assert.strictEqual(callCount, 1);
            assert.strictEqual(isSubmittingReply(), true);

            // Second submit while in-flight
            doc.dispatchEvent({ type: 'submit', target: composer, preventDefault: () => {} });
            assert.strictEqual(callCount, 1);

            resolveSharedCall({ ok: true, status: 201, commentId: 'r-1' });
            await new Promise(r => setTimeout(r, 10));
            assert.strictEqual(isSubmittingReply(), false);
        });

        test('F. Reply/Edit mutual exclusion is not regressed', () => {
            let closeEditCalled = false;
            let closeEditRestoreFocusArg = null;
            const mockEditComposer = {
                closeEditComposer: (restoreFocus) => {
                    closeEditCalled = true;
                    closeEditRestoreFocusArg = restoreFocus;
                }
            };

            initReaderBlockDiscussionReplyComposer(doc, {
                drawerModule: mockDrawerModule,
                editComposerModule: mockEditComposer
            });

            const rootReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
            doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault: () => {} });

            assert.strictEqual(closeEditCalled, true);
            assert.strictEqual(closeEditRestoreFocusArg, false);
            const composer = content.querySelector('.' + REPLY_COMPOSER_CLASS);
            assert.notStrictEqual(composer, null);
        });
    });

    describe('MS-05E5H2F2B Surface Ownership & Scope Tests', () => {
        test('Drawer Reply module completely ignores Reply clicks from #novelChapterComments', () => {
            let createReplyCalled = false;
            const mockMutations = {
                createReply: () => {
                    createReplyCalled = true;
                    return Promise.resolve({ ok: true });
                }
            };

            initReaderBlockDiscussionReplyComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations
            });

            // Create Bottom comments fixture
            const bottomSection = doc.createElement('section');
            bottomSection.id = 'novelChapterComments';
            bottomSection.setAttribute('id', 'novelChapterComments');

            const bottomComment = doc.createElement('div');
            bottomComment.className = 'novel-comment novel-comment--root';
            bottomComment.setAttribute('data-comment-id', 'bottom-root-1');

            const bottomReplyBtn = doc.createElement('button');
            bottomReplyBtn.className = 'novel-comment-reply-btn';
            bottomReplyBtn.setAttribute('data-action', 'reply');
            bottomReplyBtn.setAttribute('data-comment-id', 'bottom-root-1');
            bottomReplyBtn.setAttribute('data-root-id', 'bottom-root-1');
            bottomReplyBtn.textContent = 'Phản hồi';

            bottomComment.appendChild(bottomReplyBtn);
            bottomSection.appendChild(bottomComment);
            doc.body.appendChild(bottomSection);

            let preventDefaultCalled = false;
            const clickEvt = {
                type: 'click',
                target: bottomReplyBtn,
                preventDefault() { preventDefaultCalled = true; }
            };

            // Dispatch click on Bottom reply button
            doc.dispatchEvent(clickEvt);

            // Assertions for Bottom click:
            assert.strictEqual(preventDefaultCalled, false, 'Drawer must not preventDefault on external surface click');
            assert.strictEqual(doc.querySelector('.' + REPLY_COMPOSER_CLASS), null, 'Drawer composer must not open');
            assert.strictEqual(getActiveComposerEl(), null, 'Drawer activeComposerEl must remain null');
            assert.strictEqual(getActiveReplyTarget(), null, 'Drawer activeReplyTarget must remain null');
            assert.strictEqual(createReplyCalled, false, 'createReply must not be called');

            const authModal = doc.getElementById(AUTH_MODAL_ID);
            assert.ok(!authModal || authModal.hidden, 'Drawer auth modal must not be displayed');

            // Direct call to handleReplyButtonClick with bottom button also safely no-ops
            handleReplyButtonClick(bottomReplyBtn);
            assert.strictEqual(doc.querySelector('.' + REPLY_COMPOSER_CLASS), null);
            assert.strictEqual(getActiveComposerEl(), null);

            // Normal Drawer Reply button click still works normally
            const drawerReplyBtn = content.querySelector('.novel-comment--root .novel-comment-reply-btn');
            doc.dispatchEvent({ type: 'click', target: drawerReplyBtn, preventDefault() {} });

            assert.notStrictEqual(content.querySelector('.' + REPLY_COMPOSER_CLASS), null, 'Drawer composer must open for drawer button');
            assert.notStrictEqual(getActiveReplyTarget(), null);
            assert.strictEqual(getActiveReplyTarget().commentId, ROOT_ID);
        });
    });

});

describe('MS-05E5H2F4B2 — Drawer Reply Create → Bottom Synchronization', () => {

    const CHAPTER_ID = '11111111-1111-1111-1111-111111111111';
    const BLOCK_KEY = 'blk-0123456789abcdef-1';
    const ROOT_ID = 'root-uuid-1';
    const REPLY_ID = 'reply-uuid-2';

    function createFixture(options = {}) {
        const d = new FakeDocument();

        const csrfMeta = d.createElement('meta');
        csrfMeta.setAttribute('name', '_csrf');
        csrfMeta.setAttribute('content', 'test-csrf-token-123');
        d.head.appendChild(csrfMeta);

        const csrfHeaderMeta = d.createElement('meta');
        csrfHeaderMeta.setAttribute('name', '_csrf_header');
        csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
        d.head.appendChild(csrfHeaderMeta);

        const dDrawer = d.createElement('aside');
        dDrawer.id = 'novelBlockDiscussionDrawer';
        dDrawer.setAttribute('data-chapter-slug', 'chuong-1');
        dDrawer.setAttribute('data-authenticated', options.authenticated ? 'true' : 'false');
        d.body.appendChild(dDrawer);

        const rootComposer = d.createElement('form');
        rootComposer.id = 'novelBlockDiscussionComposer';
        dDrawer.appendChild(rootComposer);

        const dContent = d.createElement('section');
        dContent.id = 'novelBlockDiscussionContent';
        dDrawer.appendChild(dContent);

        const threadCard = d.createElement('article');
        threadCard.className = 'novel-block-discussion-thread';
        threadCard.setAttribute('data-root-id', ROOT_ID);
        dContent.appendChild(threadCard);

        const rootEl = d.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', ROOT_ID);
        threadCard.appendChild(rootEl);

        const rootHeader = d.createElement('header');
        rootHeader.className = 'novel-comment-header';
        rootEl.appendChild(rootHeader);

        const rootBody = d.createElement('div');
        rootBody.className = 'novel-comment-body';
        rootBody.textContent = 'Bình luận mở đầu';
        rootEl.appendChild(rootBody);

        const rootActions = d.createElement('div');
        rootActions.className = 'novel-comment-actions';
        const rootReplyBtn = d.createElement('button');
        rootReplyBtn.className = 'novel-comment-reply-btn';
        rootReplyBtn.setAttribute('data-action', 'reply');
        rootReplyBtn.setAttribute('data-comment-id', ROOT_ID);
        rootReplyBtn.setAttribute('data-root-id', ROOT_ID);
        rootReplyBtn.setAttribute('data-author-name', 'Tiêu Viêm');
        rootReplyBtn.textContent = 'Trả lời';
        rootActions.appendChild(rootReplyBtn);
        rootEl.appendChild(rootActions);

        const repliesContainer = d.createElement('div');
        repliesContainer.className = 'novel-comment-replies';
        threadCard.appendChild(repliesContainer);

        const replyEl = d.createElement('article');
        replyEl.className = 'novel-comment novel-comment--reply';
        replyEl.setAttribute('data-reply-id', REPLY_ID);
        replyEl.setAttribute('data-comment-id', REPLY_ID);
        repliesContainer.appendChild(replyEl);

        const replyHeader = d.createElement('header');
        replyHeader.className = 'novel-comment-header';
        replyEl.appendChild(replyHeader);

        const replyBody = d.createElement('div');
        replyBody.className = 'novel-comment-body';
        replyBody.textContent = 'Phản hồi';
        replyEl.appendChild(replyBody);

        const replyActions = d.createElement('div');
        replyActions.className = 'novel-comment-actions';
        const replyBtn = d.createElement('button');
        replyBtn.className = 'novel-comment-reply-btn';
        replyBtn.setAttribute('data-action', 'reply');
        replyBtn.setAttribute('data-comment-id', REPLY_ID);
        replyBtn.setAttribute('data-reply-id', REPLY_ID);
        replyBtn.setAttribute('data-root-id', ROOT_ID);
        replyBtn.setAttribute('data-author-name', 'Dược Lão');
        replyBtn.textContent = 'Trả lời';
        replyActions.appendChild(replyBtn);
        replyEl.appendChild(replyActions);

        return {
            doc: d,
            drawer: dDrawer,
            content: dContent,
            threadCard,
            rootEl,
            replyEl
        };
    }

    beforeEach(() => {
        resetReplyComposerState();
    });

    afterEach(() => {
        resetReplyComposerState();
    });

    test('Case A: Root loaded in rootPageMap triggers refreshRootThread with revealCommentId, no page-0 refresh', async () => {
        const fixture = createFixture({ authenticated: true });
        let createReplyCalls = 0;
        let drawerRefreshCalls = 0;
        let indicatorRefreshCalls = 0;
        let refreshedRootId = null;
        let refreshedOptions = null;
        let pageZeroCalls = 0;

        const mockMutations = {
            createReply: async () => {
                createReplyCalls++;
                return { ok: true, status: 201, commentId: 'rep-new-999' };
            }
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => { drawerRefreshCalls++; }
        };
        const mockIndicators = {
            refreshChapterIndicators: async () => { indicatorRefreshCalls++; }
        };
        const mockBottom = {
            getState: () => ({
                rootPageMap: { [ROOT_ID]: 0 }
            }),
            refreshRootThread: async (rootId, opts) => {
                refreshedRootId = rootId;
                refreshedOptions = opts;
            },
            refreshFromPageZero: async () => { pageZeroCalls++; }
        };

        initReaderBlockDiscussionReplyComposer(fixture.doc, {
            commentMutations: mockMutations,
            drawerModule: mockDrawer,
            indicatorsModule: mockIndicators,
            commentsModule: mockBottom
        });

        const rootReplyBtn = fixture.content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        fixture.doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault() {} });

        const composer = fixture.content.querySelector('.' + REPLY_COMPOSER_CLASS);
        assert.ok(composer);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Replying to root';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(createReplyCalls, 1);
        assert.strictEqual(drawerRefreshCalls, 1, 'Drawer must refresh active discussion');
        assert.strictEqual(indicatorRefreshCalls, 1, 'Indicators must refresh');
        assert.strictEqual(refreshedRootId, ROOT_ID, 'Bottom refreshRootThread must receive ROOT_ID');
        assert.deepStrictEqual(refreshedOptions, { revealCommentId: 'rep-new-999' }, 'Must pass revealCommentId');
        assert.strictEqual(pageZeroCalls, 0, 'Must NOT trigger page-0 refresh when root is loaded');
    });

    test('Case B: Nested reply uses exact THREAD ROOT ID, not immediate parentCommentId', async () => {
        const fixture = createFixture({ authenticated: true });
        let createReplyInput = null;
        let refreshedRootId = null;
        let refreshedOptions = null;
        let pageZeroCalls = 0;

        const mockMutations = {
            createReply: async (input) => {
                createReplyInput = input;
                return { ok: true, status: 201, commentId: 'rep-nested-888' };
            }
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => {}
        };
        const mockIndicators = {
            refreshChapterIndicators: async () => {}
        };
        const mockBottom = {
            getState: () => ({
                rootPageMap: { [ROOT_ID]: 0 }
            }),
            refreshRootThread: async (rootId, opts) => {
                refreshedRootId = rootId;
                refreshedOptions = opts;
            },
            refreshFromPageZero: async () => { pageZeroCalls++; }
        };

        initReaderBlockDiscussionReplyComposer(fixture.doc, {
            commentMutations: mockMutations,
            drawerModule: mockDrawer,
            indicatorsModule: mockIndicators,
            commentsModule: mockBottom
        });

        // Click reply button on the nested reply (REPLY_ID)
        const nestedReplyBtn = fixture.content.querySelector('.novel-comment--reply .novel-comment-reply-btn');
        fixture.doc.dispatchEvent({ type: 'click', target: nestedReplyBtn, preventDefault() {} });

        const composer = fixture.content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Replying to nested reply';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(createReplyInput.parentCommentId, REPLY_ID, 'API POST must use immediate parent commentId');
        assert.strictEqual(refreshedRootId, ROOT_ID, 'Bottom refreshRootThread must use thread ROOT_ID, not REPLY_ID');
        assert.deepStrictEqual(refreshedOptions, { revealCommentId: 'rep-nested-888' });
        assert.strictEqual(pageZeroCalls, 0);
    });

    test('Case C: Root NOT loaded in rootPageMap triggers refreshFromPageZero, no refreshRootThread', async () => {
        const fixture = createFixture({ authenticated: true });
        let refreshedRootThreadCalls = 0;
        let pageZeroCalls = 0;

        const mockMutations = {
            createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-777' })
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => {}
        };
        const mockIndicators = {
            refreshChapterIndicators: async () => {}
        };
        const mockBottom = {
            getState: () => ({
                rootPageMap: { 'some-other-root': 0 }
            }),
            refreshRootThread: async () => { refreshedRootThreadCalls++; },
            refreshFromPageZero: async () => { pageZeroCalls++; }
        };

        initReaderBlockDiscussionReplyComposer(fixture.doc, {
            commentMutations: mockMutations,
            drawerModule: mockDrawer,
            indicatorsModule: mockIndicators,
            commentsModule: mockBottom
        });

        const rootReplyBtn = fixture.content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        fixture.doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault() {} });

        const composer = fixture.content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Replying to unloaded root';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(refreshedRootThreadCalls, 0, 'Must NOT call refreshRootThread for unloaded root');
        assert.strictEqual(pageZeroCalls, 1, 'Must call refreshFromPageZero once to update active comment counts');
    });

    test('Case D: Secondary Bottom rejection does not retry mutation or fail Drawer/indicator refresh', async () => {
        const fixture = createFixture({ authenticated: true });
        let createReplyCalls = 0;
        let drawerRefreshCalls = 0;
        let indicatorRefreshCalls = 0;
        let bottomCalls = 0;

        const mockMutations = {
            createReply: async () => {
                createReplyCalls++;
                return { ok: true, status: 201, commentId: 'rep-new-666' };
            }
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => { drawerRefreshCalls++; }
        };
        const mockIndicators = {
            refreshChapterIndicators: async () => { indicatorRefreshCalls++; }
        };
        const mockBottom = {
            getState: () => ({
                rootPageMap: { [ROOT_ID]: 0 }
            }),
            refreshRootThread: async () => {
                bottomCalls++;
                throw new Error('Bottom network failure');
            }
        };

        initReaderBlockDiscussionReplyComposer(fixture.doc, {
            commentMutations: mockMutations,
            drawerModule: mockDrawer,
            indicatorsModule: mockIndicators,
            commentsModule: mockBottom
        });

        const rootReplyBtn = fixture.content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        fixture.doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault() {} });

        const composer = fixture.content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Replying with bottom failure';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(createReplyCalls, 1, 'createReply must NOT be retried');
        assert.strictEqual(drawerRefreshCalls, 1, 'Drawer refresh must still occur');
        assert.strictEqual(indicatorRefreshCalls, 1, 'Indicator refresh must still occur');
        assert.strictEqual(bottomCalls, 1, 'Bottom refresh was attempted once');
        assert.strictEqual(getActiveComposerEl(), null, 'Composer must still close on primary success');
    });

    test('Case E: Stale mutation completion performs zero Bottom synchronization', async () => {
        const fixture = createFixture({ authenticated: true });
        let resolveReply;
        let refreshedRootThreadCalls = 0;
        let pageZeroCalls = 0;

        const mockMutations = {
            createReply: () => new Promise(r => { resolveReply = r; })
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => {}
        };
        const mockBottom = {
            getState: () => ({
                rootPageMap: { [ROOT_ID]: 0 }
            }),
            refreshRootThread: async () => { refreshedRootThreadCalls++; },
            refreshFromPageZero: async () => { pageZeroCalls++; }
        };

        initReaderBlockDiscussionReplyComposer(fixture.doc, {
            commentMutations: mockMutations,
            drawerModule: mockDrawer,
            commentsModule: mockBottom
        });

        // 1. Open reply and submit
        const rootReplyBtn = fixture.content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        fixture.doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault() {} });

        const composer = fixture.content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'In-flight reply';

        const submitPromise = handleSubmit({ preventDefault: () => {} });

        // 2. Cancel reply composer while in flight
        closeReplyComposer(false);

        // 3. Resolve reply with 201
        resolveReply({ ok: true, status: 201, commentId: 'rep-stale' });
        await submitPromise;
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(refreshedRootThreadCalls, 0, 'Zero Bottom thread refresh for stale mutation');
        assert.strictEqual(pageZeroCalls, 0, 'Zero Bottom page-0 refresh for stale mutation');
    });

    test('Case F: Later-page root loaded in rootPageMap (page 1) uses refreshRootThread, not page-0', async () => {
        const fixture = createFixture({ authenticated: true });
        let refreshedRootId = null;
        let pageZeroCalls = 0;

        const mockMutations = {
            createReply: async () => ({ ok: true, status: 201, commentId: 'rep-later-page' })
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => {}
        };
        const mockBottom = {
            getState: () => ({
                // Root is loaded on page 1
                rootPageMap: { [ROOT_ID]: 1 }
            }),
            refreshRootThread: async (rootId) => { refreshedRootId = rootId; },
            refreshFromPageZero: async () => { pageZeroCalls++; }
        };

        initReaderBlockDiscussionReplyComposer(fixture.doc, {
            commentMutations: mockMutations,
            drawerModule: mockDrawer,
            commentsModule: mockBottom
        });

        const rootReplyBtn = fixture.content.querySelector('.novel-comment--root .novel-comment-reply-btn');
        fixture.doc.dispatchEvent({ type: 'click', target: rootReplyBtn, preventDefault() {} });

        const composer = fixture.content.querySelector('.' + REPLY_COMPOSER_CLASS);
        const textarea = composer.querySelector('.' + REPLY_INPUT_CLASS);
        textarea.value = 'Replying to later-page root';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(refreshedRootId, ROOT_ID, 'Must refresh the exact root thread on page 1');
        assert.strictEqual(pageZeroCalls, 0, 'Must NOT trigger page-0 reset when root is loaded on page 1');
    });

});
