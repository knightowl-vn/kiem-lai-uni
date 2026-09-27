const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const DeleteModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-delete.js'));

const {
    CONFIRMATION_CLASS,
    CONFIRMATION_MSG_CLASS,
    CONFIRMATION_STATUS_CLASS,
    CONFIRMATION_ACTIONS_CLASS,
    CONFIRM_BTN_CLASS,
    CANCEL_BTN_CLASS,
    initReaderBlockDiscussionDelete,
    resetDeleteState,
    openDeleteConfirmation,
    closeDeleteConfirmation,
    handleConfirmDelete,
    handleDeleteButtonClick,
    resolveTargetFromButton,
    getActiveDeleteTarget,
    getActiveConfirmationEl,
    isDeletingComment
} = DeleteModule;

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
        this.style = {};
        this.hidden = false;

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

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes.clear();
            const parts = String(value).trim().split(/\s+/);
            for (const p of parts) {
                if (p) this.classList.classes.add(p);
            }
        }
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
        if (child.parentNode) {
            child.parentNode.removeChild(child);
        }
        child.parentNode = this;
        child.parentElement = this;
        this.childNodes.push(child);
        return child;
    }

    insertBefore(newNode, referenceNode) {
        if (newNode.parentNode) {
            newNode.parentNode.removeChild(newNode);
        }
        newNode.parentNode = this;
        newNode.parentElement = this;
        if (!referenceNode) {
            this.childNodes.push(newNode);
            return newNode;
        }
        const idx = this.childNodes.indexOf(referenceNode);
        if (idx === -1) {
            this.childNodes.push(newNode);
        } else {
            this.childNodes.splice(idx, 0, newNode);
        }
        return newNode;
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

    closest(selector) {
        let cur = this;
        while (cur) {
            if (cur._matches(selector)) {
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

    _matches(selector) {
        return matchesSingleSelector(this, selector);
    }
}

function matchesSingleSelector(el, sel) {
    if (!el || !el.tagName) return false;
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
    const bracketIdx = sel.indexOf('[');
    if (bracketIdx !== -1 && sel.endsWith(']')) {
        const tag = sel.slice(0, bracketIdx);
        if (tag && el.tagName.toLowerCase() !== tag.toLowerCase()) {
            return false;
        }
        const raw = sel.slice(bracketIdx + 1, -1);
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
        this.listeners = {};
        this.head = new FakeElement('head');
        this.body = new FakeElement('body');
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }

    querySelector(selector) {
        const all = this.querySelectorAll(selector);
        return all[0] || null;
    }

    querySelectorAll(selector) {
        const results = [];
        if (this.head) results.push(...querySelectorAllDeep(this.head, selector));
        if (this.body) results.push(...querySelectorAllDeep(this.body, selector));
        return results;
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
    }

    dispatchEvent(evt) {
        const type = evt.type;
        const list = this.listeners[type] || [];
        for (const fn of list) {
            fn(evt);
        }
        return true;
    }
}

// ============================================================================
// Test Fixture Setup
// ============================================================================

const CHAPTER_ID = '11111111-1111-1111-1111-111111111111';
const BLOCK_KEY = 'blk-0123456789abcdef-1';
const ROOT_ID = 'root-del-1';
const REPLY_ID = 'reply-del-1';

function setupTestDOM() {
    const doc = new FakeDocument();

    const csrfToken = doc.createElement('meta');
    csrfToken.setAttribute('name', '_csrf');
    csrfToken.setAttribute('content', 'test-delete-csrf-token');
    doc.head.appendChild(csrfToken);

    const csrfHeader = doc.createElement('meta');
    csrfHeader.setAttribute('name', '_csrf_header');
    csrfHeader.setAttribute('content', 'X-XSRF-TOKEN');
    doc.head.appendChild(csrfHeader);

    // Discussion thread container
    const threadCard = doc.createElement('article');
    threadCard.className = 'novel-block-discussion-thread';
    threadCard.setAttribute('data-root-id', ROOT_ID);

    // Root comment
    const rootEl = doc.createElement('div');
    rootEl.className = 'novel-comment novel-comment--root';
    rootEl.setAttribute('data-comment-id', ROOT_ID);

    const rootBody = doc.createElement('div');
    rootBody.className = 'novel-comment-body';
    rootBody.textContent = 'Root comment content';
    rootEl.appendChild(rootBody);

    const rootActions = doc.createElement('div');
    rootActions.className = 'novel-comment-actions';

    const rootReplyBtn = doc.createElement('button');
    rootReplyBtn.type = 'button';
    rootReplyBtn.className = 'novel-comment-reply-btn';
    rootReplyBtn.setAttribute('data-action', 'reply');
    rootReplyBtn.setAttribute('data-comment-id', ROOT_ID);
    rootReplyBtn.setAttribute('data-root-id', ROOT_ID);
    rootReplyBtn.textContent = 'Trả lời';
    rootActions.appendChild(rootReplyBtn);

    const rootEditBtn = doc.createElement('button');
    rootEditBtn.type = 'button';
    rootEditBtn.className = 'novel-comment-edit-btn';
    rootEditBtn.setAttribute('data-action', 'edit');
    rootEditBtn.setAttribute('data-comment-id', ROOT_ID);
    rootEditBtn.setAttribute('data-root-id', ROOT_ID);
    rootEditBtn.textContent = 'Chỉnh sửa';
    rootActions.appendChild(rootEditBtn);

    const rootDeleteBtn = doc.createElement('button');
    rootDeleteBtn.type = 'button';
    rootDeleteBtn.className = 'novel-comment-delete-btn';
    rootDeleteBtn.setAttribute('data-action', 'delete');
    rootDeleteBtn.setAttribute('data-comment-id', ROOT_ID);
    rootDeleteBtn.setAttribute('data-root-id', ROOT_ID);
    rootDeleteBtn.textContent = 'Xóa';
    rootActions.appendChild(rootDeleteBtn);

    rootEl.appendChild(rootActions);
    threadCard.appendChild(rootEl);

    // Replies container
    const repliesContainer = doc.createElement('div');
    repliesContainer.className = 'novel-comment-replies';

    const replyEl = doc.createElement('div');
    replyEl.className = 'novel-comment novel-comment--reply';
    replyEl.setAttribute('data-comment-id', REPLY_ID);

    const replyBody = doc.createElement('div');
    replyBody.className = 'novel-comment-body';
    replyBody.textContent = 'Reply content';
    replyEl.appendChild(replyBody);

    const replyActions = doc.createElement('div');
    replyActions.className = 'novel-comment-actions';

    const replyBtn = doc.createElement('button');
    replyBtn.type = 'button';
    replyBtn.className = 'novel-comment-reply-btn';
    replyBtn.setAttribute('data-action', 'reply');
    replyBtn.setAttribute('data-comment-id', REPLY_ID);
    replyBtn.setAttribute('data-reply-id', REPLY_ID);
    replyBtn.setAttribute('data-root-id', ROOT_ID);
    replyBtn.textContent = 'Trả lời';
    replyActions.appendChild(replyBtn);

    const replyDeleteBtn = doc.createElement('button');
    replyDeleteBtn.type = 'button';
    replyDeleteBtn.className = 'novel-comment-delete-btn';
    replyDeleteBtn.setAttribute('data-action', 'delete');
    replyDeleteBtn.setAttribute('data-comment-id', REPLY_ID);
    replyDeleteBtn.setAttribute('data-reply-id', REPLY_ID);
    replyDeleteBtn.setAttribute('data-root-id', ROOT_ID);
    replyDeleteBtn.textContent = 'Xóa';
    replyActions.appendChild(replyDeleteBtn);

    replyEl.appendChild(replyActions);
    repliesContainer.appendChild(replyEl);
    threadCard.appendChild(repliesContainer);

    const drawer = doc.createElement('div');
    drawer.id = 'novelBlockDiscussionDrawer';
    drawer.appendChild(threadCard);
    doc.body.appendChild(drawer);

    return { doc, drawer, threadCard, rootEl, replyEl, rootDeleteBtn, replyDeleteBtn };
}

// ============================================================================
// Test Suite: reader-block-discussion-delete.test.js
// ============================================================================

describe('Reader Block Discussion Delete Module Tests (MS-05E5G4C2)', () => {
    let doc;
    let rootDeleteBtn;
    let replyDeleteBtn;
    let mockDrawerModule;
    let mockIndicatorsModule;
    let refreshDrawerCalled;
    let refreshIndicatorsCalled;

    beforeEach(() => {
        resetDeleteState();
        refreshDrawerCalled = false;
        refreshIndicatorsCalled = false;

        mockDrawerModule = {
            getActiveContext: () => ({
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                threadCount: 1,
                commentCount: 2
            }),
            refreshActiveDiscussion: async () => {
                refreshDrawerCalled = true;
            }
        };

        mockIndicatorsModule = {
            refreshChapterIndicators: async () => {
                refreshIndicatorsCalled = true;
            }
        };

        const setup = setupTestDOM();
        doc = setup.doc;
        rootDeleteBtn = setup.rootDeleteBtn;
        replyDeleteBtn = setup.replyDeleteBtn;
    });

    test('F. click Xóa opens exact confirmation UI attached to target comment', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const activeTarget = getActiveDeleteTarget();
        assert.notStrictEqual(activeTarget, null);
        assert.strictEqual(activeTarget.commentId, ROOT_ID);
        assert.strictEqual(activeTarget.isRoot, true);

        const confirmationEl = getActiveConfirmationEl();
        assert.notStrictEqual(confirmationEl, null);
        assert.strictEqual(confirmationEl.className, CONFIRMATION_CLASS);

        // Actions on root comment are hidden while confirmation is active
        const actionsEl = doc.querySelector('.novel-comment--root .novel-comment-actions');
        assert.strictEqual(actionsEl.hidden, true);
    });

    test('G. root comment gets stronger thread-hide warning message', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const msgEl = doc.querySelector('.' + CONFIRMATION_MSG_CLASS);
        assert.notStrictEqual(msgEl, null);
        assert.strictEqual(msgEl.textContent, 'Xóa bình luận này? Toàn bộ cuộc thảo luận này sẽ không còn hiển thị.');
    });

    test('H. reply comment gets standard warning message', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });

        const activeTarget = getActiveDeleteTarget();
        assert.notStrictEqual(activeTarget, null);
        assert.strictEqual(activeTarget.commentId, REPLY_ID);
        assert.strictEqual(activeTarget.isRoot, false);

        const msgEl = doc.querySelector('.' + CONFIRMATION_MSG_CLASS);
        assert.notStrictEqual(msgEl, null);
        assert.strictEqual(msgEl.textContent, 'Xóa bình luận này?');
    });

    test('I. cancel button sends no request, unhides actions, and restores focus', () => {
        let fetchCalled = false;
        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => {
                fetchCalled = true;
                return { status: 204 };
            }
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        const cancelBtn = doc.querySelector('.' + CANCEL_BTN_CLASS);
        assert.notStrictEqual(cancelBtn, null);

        doc.dispatchEvent({ type: 'click', target: cancelBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
        assert.strictEqual(fetchCalled, false);

        // Root comment actions are unhidden
        const actionsEl = doc.querySelector('.novel-comment--root .novel-comment-actions');
        assert.strictEqual(actionsEl.hidden, false);
        // Focus restored to delete button
        assert.strictEqual(rootDeleteBtn.isFocused, true);
    });

    test('J & K. confirm sends DELETE to exact endpoint with CSRF header and no unauthorized body/params', async () => {
        let requestedUrl = null;
        let requestedMethod = null;
        let requestedHeaders = null;
        let requestedBody = undefined;

        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            fetchFn: async (url, options) => {
                requestedUrl = url;
                requestedMethod = options.method;
                requestedHeaders = options.headers;
                requestedBody = options.body;
                return { status: 204 };
            }
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(requestedMethod, 'DELETE');
        assert.strictEqual(requestedUrl, `/api/novel/chapters/${CHAPTER_ID}/comments/${ROOT_ID}`);
        assert.strictEqual(requestedHeaders['X-XSRF-TOKEN'], 'test-delete-csrf-token');
        assert.strictEqual(requestedBody, undefined); // No body sent on DELETE
    });

    test('L. double submit is blocked while DELETE request is pending', async () => {
        let fetchCallCount = 0;
        let slowResolve;
        const slowPromise = new Promise(resolve => { slowResolve = resolve; });

        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            fetchFn: async () => {
                fetchCallCount++;
                return slowPromise;
            }
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        // First click
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });
        // Immediate second click while pending
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        assert.strictEqual(fetchCallCount, 1);
        assert.strictEqual(isDeletingComment(), true);
        assert.strictEqual(confirmBtn.disabled, true);

        slowResolve({ status: 204 });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(isDeletingComment(), false);
    });

    test('M & N & O. current 204 response triggers authoritative drawer and indicator refreshes without local count arithmetic', async () => {
        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            fetchFn: async () => ({ status: 204 })
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        await new Promise(r => setTimeout(r, 10));

        // Confirmation closed
        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);

        // Authoritative refreshes called
        assert.strictEqual(refreshDrawerCalled, true);
        assert.strictEqual(refreshIndicatorsCalled, true);
    });

    test('P. 401/403 failure keeps confirmation open, re-enables buttons, and shows friendly error', async () => {
        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            fetchFn: async () => ({ status: 403 })
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        const cancelBtn = doc.querySelector('.' + CANCEL_BTN_CLASS);
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        await new Promise(r => setTimeout(r, 10));

        assert.notStrictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(confirmBtn.disabled, false);
        assert.strictEqual(cancelBtn.disabled, false);

        const statusEl = doc.querySelector('.' + CONFIRMATION_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền xóa bình luận này.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.strictEqual(refreshDrawerCalled, false);
    });

    test('Q. 404 failure keeps confirmation open and displays bounded error', async () => {
        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            fetchFn: async () => ({ status: 404 })
        });

        doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        await new Promise(r => setTimeout(r, 10));

        assert.notStrictEqual(getActiveConfirmationEl(), null);
        const statusEl = doc.querySelector('.' + CONFIRMATION_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Bình luận không còn tồn tại.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
    });

    test('R. 5xx/network error keeps confirmation context and displays retry message', async () => {
        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            fetchFn: async () => { throw new Error('Network timeout'); }
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        await new Promise(r => setTimeout(r, 10));

        assert.notStrictEqual(getActiveConfirmationEl(), null);
        const statusEl = doc.querySelector('.' + CONFIRMATION_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Không thể xóa bình luận. Vui lòng thử lại.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
    });

    test('S. drawer closed event clears delete confirmation and invalidates tokens', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
    });

    test('T. chapter changed event clears delete confirmation and invalidates tokens', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
    });

    test('U. block discussion requested event clears delete confirmation and invalidates tokens', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-requested' });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
    });

    test('V. stale failure response is ignored and has no visible side effects', async () => {
        let slowReject;
        const slowPromise = new Promise((_, reject) => { slowReject = reject; });

        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => slowPromise
        });

        // 1. Open and confirm on A
        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        const oldConfirmPromise = handleConfirmDelete();

        // 2. Switch to B
        doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, REPLY_ID);

        // 3. Reject old A request
        slowReject(new Error('Stale fail'));
        try {
            await oldConfirmPromise;
        } catch (_) {}

        // Target B is completely unaffected
        assert.strictEqual(getActiveDeleteTarget().commentId, REPLY_ID);
        const statusEl = doc.querySelector('.' + CONFIRMATION_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, '');
    });

    test('W. exact ABA race: stale old A 204 does NOT close new A confirmation or trigger refreshes', async () => {
        let resolveSlowFetch;
        const slowFetchPromise = new Promise(resolve => {
            resolveSlowFetch = resolve;
        });

        let fetchCount = 0;
        const testFetch = () => {
            fetchCount++;
            if (fetchCount === 1) {
                return slowFetchPromise;
            }
            return Promise.resolve({ status: 204 });
        };

        initReaderBlockDiscussionDelete(doc, {
            fetchFn: testFetch,
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule
        });

        // 1. Open Delete on Comment A (root)
        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID);

        // 2. Click confirm on A (triggers slow pending fetch)
        const oldDeletePromise = handleConfirmDelete();

        // 3. Switch to Delete on Comment B (reply)
        doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, REPLY_ID);

        // 4. Return to Comment A and open a NEW Delete confirmation
        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID);
        const newConfirmationEl = getActiveConfirmationEl();
        assert.notStrictEqual(newConfirmationEl, null);

        // 5. Old A request resolves 204
        resolveSlowFetch({ status: 204 });
        await oldDeletePromise;

        // 6. Assert ALL:
        // - active target remains new A
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID);
        // - new confirmation remains mounted
        assert.strictEqual(getActiveConfirmationEl(), newConfirmationEl);
        assert.strictEqual(doc.querySelectorAll('.' + CONFIRMATION_CLASS).length, 1);
        // - refreshActiveDiscussion was NOT called by the stale old response
        assert.strictEqual(refreshDrawerCalled, false);
        // - refreshChapterIndicators was NOT called by the stale old response
        assert.strictEqual(refreshIndicatorsCalled, false);
    });

    test('Escape key cancels active delete confirmation and restores focus', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        doc.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
        assert.strictEqual(rootDeleteBtn.isFocused, true);
    });

    test('switching to Edit on any comment closes active delete confirmation', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
    });

    test('switching to Reply on any comment closes active delete confirmation', () => {
        initReaderBlockDiscussionDelete(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        const replyBtn = doc.querySelector('.novel-comment--reply .novel-comment-reply-btn');
        doc.dispatchEvent({ type: 'click', target: replyBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget(), null);
    });

    test('MS-05E5H2F3A: confirmed Delete calls shared deleteComment exactly once with correct inputs and no direct fetch', async () => {
        let deleteCommentCallCount = 0;
        let capturedInput = null;
        let directFetchCalled = false;

        const mockMutations = {
            deleteComment: async (input) => {
                deleteCommentCallCount++;
                capturedInput = input;
                return { ok: true, status: 204 };
            }
        };

        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule,
            commentMutations: mockMutations,
            fetchFn: async () => {
                directFetchCalled = true;
                return { status: 204 };
            }
        });

        doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });

        const confirmBtn = doc.querySelector('.' + CONFIRM_BTN_CLASS);
        doc.dispatchEvent({ type: 'click', target: confirmBtn, preventDefault: () => {} });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(deleteCommentCallCount, 1, 'deleteComment must be called exactly once');
        assert.strictEqual(capturedInput.chapterId, CHAPTER_ID);
        assert.strictEqual(capturedInput.commentId, ROOT_ID);
        assert.strictEqual(directFetchCalled, false, 'No direct fetch path outside shared client');
        assert.strictEqual(refreshDrawerCalled, true, 'Authoritative drawer refresh must be called');
        assert.strictEqual(refreshIndicatorsCalled, true, 'Authoritative indicators refresh must be called');
        assert.strictEqual(getActiveConfirmationEl(), null, 'Confirmation should close on success');
    });

    test('MS-05E5H2F3B: ignores delete button clicks originating outside the discussion drawer (e.g., bottom comments)', () => {
        initReaderBlockDiscussionDelete(doc, {
            drawerModule: mockDrawerModule,
            indicatorsModule: mockIndicatorsModule
        });

        const bottomSection = doc.createElement('section');
        bottomSection.id = 'novelChapterComments';

        const bottomComment = doc.createElement('div');
        bottomComment.className = 'novel-comment novel-comment--root';
        bottomComment.setAttribute('data-comment-id', 'bottom-comment-1');

        const bottomDeleteBtn = doc.createElement('button');
        bottomDeleteBtn.type = 'button';
        bottomDeleteBtn.className = 'novel-comment-delete-btn';
        bottomDeleteBtn.setAttribute('data-action', 'delete');
        bottomDeleteBtn.setAttribute('data-comment-id', 'bottom-comment-1');
        bottomComment.appendChild(bottomDeleteBtn);
        bottomSection.appendChild(bottomComment);
        doc.body.appendChild(bottomSection);

        doc.dispatchEvent({ type: 'click', target: bottomDeleteBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null, 'Drawer delete confirmation must not open for bottom comments');
        assert.strictEqual(getActiveDeleteTarget(), null);
    });

    describe('MS-05E5H2F4B2 — Drawer Delete → Bottom Synchronization', () => {

        beforeEach(() => {
            resetDeleteState();
            refreshDrawerCalled = false;
            refreshIndicatorsCalled = false;
            const fixture = setupTestDOM();
            doc = fixture.doc;
            rootDeleteBtn = fixture.rootDeleteBtn;
            replyDeleteBtn = fixture.replyDeleteBtn;
        });

        afterEach(() => {
            resetDeleteState();
        });

        test('Case A: Root delete unconditionally triggers Bottom refreshFromPageZero() (even if root loaded)', async () => {
            let deleteCalls = 0;
            let pageZeroCalls = 0;
            let refreshedRootThreadCalls = 0;

            const mockMutations = {
                deleteComment: async () => {
                    deleteCalls++;
                    return { ok: true, status: 204 };
                }
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async () => { refreshedRootThreadCalls++; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionDelete(doc, {
                drawerModule: mockDrawerModule,
                indicatorsModule: mockIndicatorsModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
            assert.ok(getActiveConfirmationEl());

            await handleConfirmDelete();

            assert.strictEqual(deleteCalls, 1);
            assert.strictEqual(refreshDrawerCalled, true);
            assert.strictEqual(refreshIndicatorsCalled, true);
            assert.strictEqual(pageZeroCalls, 1, 'Root delete must trigger refreshFromPageZero unconditionally');
            assert.strictEqual(refreshedRootThreadCalls, 0, 'Must NOT call refreshRootThread for root deletion');
            assert.strictEqual(getActiveConfirmationEl(), null);
        });

        test('Case B: Reply delete when root is loaded in rootPageMap triggers refreshRootThread(rootId) with thread rootId', async () => {
            let deleteInput = null;
            let refreshedRootId = null;
            let pageZeroCalls = 0;

            const mockMutations = {
                deleteComment: async (input) => {
                    deleteInput = input;
                    return { ok: true, status: 204 };
                }
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async (rootId) => { refreshedRootId = rootId; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionDelete(doc, {
                drawerModule: mockDrawerModule,
                indicatorsModule: mockIndicatorsModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });
            assert.ok(getActiveConfirmationEl());

            await handleConfirmDelete();

            assert.strictEqual(deleteInput.commentId, REPLY_ID, 'DELETE commentId must be the reply ID');
            assert.strictEqual(refreshedRootId, ROOT_ID, 'Bottom refreshRootThread must be called with thread ROOT_ID, not reply ID');
            assert.strictEqual(pageZeroCalls, 0, 'No page-0 reset when root thread is loaded');
            assert.strictEqual(refreshDrawerCalled, true);
            assert.strictEqual(refreshIndicatorsCalled, true);
        });

        test('Case C: Reply delete when root is NOT loaded in rootPageMap triggers refreshFromPageZero()', async () => {
            let refreshedRootThreadCalls = 0;
            let pageZeroCalls = 0;

            const mockMutations = {
                deleteComment: async () => ({ ok: true, status: 204 })
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { 'unloaded-root-id': 0 }
                }),
                refreshRootThread: async () => { refreshedRootThreadCalls++; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionDelete(doc, {
                drawerModule: mockDrawerModule,
                indicatorsModule: mockIndicatorsModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });
            assert.ok(getActiveConfirmationEl());

            await handleConfirmDelete();

            assert.strictEqual(refreshedRootThreadCalls, 0, 'Zero thread refresh when root is not loaded');
            assert.strictEqual(pageZeroCalls, 1, 'Must call refreshFromPageZero to update active comment counts');
            assert.strictEqual(refreshDrawerCalled, true);
            assert.strictEqual(refreshIndicatorsCalled, true);
        });

        test('Case D: Secondary Bottom rejection does not retry mutation or fail Drawer/indicator refresh', async () => {
            let deleteCalls = 0;
            let bottomCalls = 0;

            const mockMutations = {
                deleteComment: async () => {
                    deleteCalls++;
                    return { ok: true, status: 204 };
                }
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshFromPageZero: async () => {
                    bottomCalls++;
                    throw new Error('Bottom network error');
                }
            };

            initReaderBlockDiscussionDelete(doc, {
                drawerModule: mockDrawerModule,
                indicatorsModule: mockIndicatorsModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
            await handleConfirmDelete();

            assert.strictEqual(deleteCalls, 1, 'deleteComment must NOT be retried');
            assert.strictEqual(refreshDrawerCalled, true, 'Drawer refresh must still occur');
            assert.strictEqual(refreshIndicatorsCalled, true, 'Indicator refresh must still occur');
            assert.strictEqual(bottomCalls, 1, 'Bottom refresh was attempted once');
            assert.strictEqual(getActiveConfirmationEl(), null, 'Confirmation must still close on primary success');
        });

        test('Case E: Stale mutation completion performs zero Bottom synchronization', async () => {
            let resolveDelete;
            let refreshedRootThreadCalls = 0;
            let pageZeroCalls = 0;

            const mockMutations = {
                deleteComment: () => new Promise(r => { resolveDelete = r; })
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async () => { refreshedRootThreadCalls++; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionDelete(doc, {
                drawerModule: mockDrawerModule,
                indicatorsModule: mockIndicatorsModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            // 1. Open delete confirmation and confirm
            doc.dispatchEvent({ type: 'click', target: rootDeleteBtn, preventDefault: () => {} });
            const deletePromise = handleConfirmDelete();

            // 2. Cancel delete while in flight
            closeDeleteConfirmation(false);

            // 3. Resolve delete with 204
            resolveDelete({ ok: true, status: 204 });
            await deletePromise;
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(refreshedRootThreadCalls, 0, 'Zero Bottom thread refresh for stale mutation');
            assert.strictEqual(pageZeroCalls, 0, 'Zero Bottom page-0 refresh for stale mutation');
        });

        test('Case F: Reply delete when root is loaded on page 1 triggers refreshRootThread(rootId)', async () => {
            let refreshedRootId = null;
            let pageZeroCalls = 0;

            const mockMutations = {
                deleteComment: async () => ({ ok: true, status: 204 })
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 1 }
                }),
                refreshRootThread: async (rootId) => { refreshedRootId = rootId; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionDelete(doc, {
                drawerModule: mockDrawerModule,
                indicatorsModule: mockIndicatorsModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            doc.dispatchEvent({ type: 'click', target: replyDeleteBtn, preventDefault: () => {} });
            await handleConfirmDelete();

            assert.strictEqual(refreshedRootId, ROOT_ID, 'Must refresh exact root thread even when on page 1');
            assert.strictEqual(pageZeroCalls, 0, 'Zero page-0 refresh for loaded later-page reply delete');
        });

    });

});
