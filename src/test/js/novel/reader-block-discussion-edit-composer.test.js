const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const EditComposerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-block-discussion-edit-composer.js'));

const {
    EDIT_COMPOSER_CLASS,
    EDIT_COMPOSER_FORM_ID,
    EDIT_INPUT_CLASS,
    EDIT_STATUS_CLASS,
    EDIT_SUBMIT_CLASS,
    EDIT_CANCEL_CLASS,
    EDIT_ACTIONS_CLASS,
    initReaderBlockDiscussionEditComposer,
    resetEditComposerState,
    openEditComposer,
    closeEditComposer,
    handleSubmit,
    handleEditButtonClick,
    resolveTargetFromButton,
    extractCommentBody,
    getActiveEditTarget,
    getActiveComposerEl,
    isSubmittingEdit
} = EditComposerModule;

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

    get nextSibling() {
        if (!this.parentNode) return null;
        const siblings = this.parentNode.childNodes;
        const idx = siblings.indexOf(this);
        return idx !== -1 && idx + 1 < siblings.length ? siblings[idx + 1] : null;
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
        return this.documentElement.querySelectorAll(selector);
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

describe('MS-05E5G4B Novel Block Discussion Edit Composer Tests', () => {

    let doc;
    let drawer;
    let content;
    let mockDrawerModule;
    let refreshActiveDiscussionCalled;

    const CHAPTER_ID = '11111111-1111-1111-1111-111111111111';
    const BLOCK_KEY = 'blk-0123456789abcdef-1';
    const ROOT_ID = 'root-uuid-1';
    const REPLY_ID_NESTED = 'reply-uuid-nested';
    const REPLY_ID_DIRECT = 'reply-uuid-direct';

    function setupDiscussionDOM(includeCsrf = true) {
        doc = new FakeDocument();

        if (includeCsrf) {
            const csrfMeta = doc.createElement('meta');
            csrfMeta.setAttribute('name', '_csrf');
            csrfMeta.setAttribute('content', 'test-csrf-token-12345');
            doc.head.appendChild(csrfMeta);

            const csrfHeaderMeta = doc.createElement('meta');
            csrfHeaderMeta.setAttribute('name', '_csrf_header');
            csrfHeaderMeta.setAttribute('content', 'X-CSRF-TOKEN');
            doc.head.appendChild(csrfHeaderMeta);
        }

        drawer = doc.createElement('div');
        drawer.id = 'novelBlockDiscussionDrawer';
        drawer.className = 'novel-block-discussion-drawer';
        drawer.setAttribute('data-chapter-slug', 'chapter-1-slug');
        drawer.setAttribute('data-authenticated', 'true');

        content = doc.createElement('div');
        content.id = 'novelBlockDiscussionContent';
        content.className = 'novel-block-discussion-content';

        // Thread Card
        const threadCard = doc.createElement('article');
        threadCard.className = 'novel-block-discussion-thread';
        threadCard.setAttribute('data-root-id', ROOT_ID);

        // 1. Root Comment (editable)
        const rootComment = doc.createElement('div');
        rootComment.className = 'novel-comment novel-comment--root';
        rootComment.setAttribute('data-comment-id', ROOT_ID);
        rootComment.setAttribute('data-author-user-id', 'author-user-1');

        const rootHeader = doc.createElement('header');
        rootHeader.className = 'novel-comment-header';
        const rootAuthor = doc.createElement('span');
        rootAuthor.className = 'novel-comment-author';
        rootAuthor.textContent = 'Tiêu Viêm';
        rootHeader.appendChild(rootAuthor);

        const rootBody = doc.createElement('div');
        rootBody.className = 'novel-comment-body';
        rootBody.textContent = 'Bình luận gốc ban đầu';

        const rootActions = doc.createElement('div');
        rootActions.className = 'novel-comment-actions';

        const rootEditBtn = doc.createElement('button');
        rootEditBtn.type = 'button';
        rootEditBtn.className = 'novel-comment-edit-btn';
        rootEditBtn.setAttribute('data-action', 'edit');
        rootEditBtn.setAttribute('data-comment-id', ROOT_ID);
        rootEditBtn.setAttribute('data-root-id', ROOT_ID);
        rootEditBtn.textContent = 'Chỉnh sửa';
        rootActions.appendChild(rootEditBtn);

        rootComment.appendChild(rootHeader);
        rootComment.appendChild(rootBody);
        rootComment.appendChild(rootActions);
        threadCard.appendChild(rootComment);

        // Replies Container
        const repliesContainer = doc.createElement('div');
        repliesContainer.className = 'novel-comment-replies';

        // 2. Direct Reply
        const directReply = doc.createElement('article');
        directReply.className = 'novel-comment novel-comment--reply';
        directReply.setAttribute('data-comment-id', REPLY_ID_DIRECT);
        directReply.setAttribute('data-reply-id', REPLY_ID_DIRECT);

        const directHeader = doc.createElement('header');
        directHeader.className = 'novel-comment-header';
        const directAuthor = doc.createElement('span');
        directAuthor.className = 'novel-comment-author';
        directAuthor.textContent = 'Dược Lão';
        directHeader.appendChild(directAuthor);

        const directBody = doc.createElement('div');
        directBody.className = 'novel-comment-body';
        directBody.textContent = 'Phản hồi trực tiếp gốc';

        const directActions = doc.createElement('div');
        directActions.className = 'novel-comment-actions';

        const directEditBtn = doc.createElement('button');
        directEditBtn.type = 'button';
        directEditBtn.className = 'novel-comment-edit-btn';
        directEditBtn.setAttribute('data-action', 'edit');
        directEditBtn.setAttribute('data-comment-id', REPLY_ID_DIRECT);
        directEditBtn.setAttribute('data-reply-id', REPLY_ID_DIRECT);
        directEditBtn.setAttribute('data-root-id', ROOT_ID);
        directEditBtn.textContent = 'Chỉnh sửa';
        directActions.appendChild(directEditBtn);

        directReply.appendChild(directHeader);
        directReply.appendChild(directBody);
        directReply.appendChild(directActions);
        repliesContainer.appendChild(directReply);

        // 3. Nested Reply with Wattpad-style @mention
        const nestedReply = doc.createElement('article');
        nestedReply.className = 'novel-comment novel-comment--reply';
        nestedReply.setAttribute('data-comment-id', REPLY_ID_NESTED);
        nestedReply.setAttribute('data-reply-id', REPLY_ID_NESTED);

        const nestedHeader = doc.createElement('header');
        nestedHeader.className = 'novel-comment-header';
        const nestedAuthor = doc.createElement('span');
        nestedAuthor.className = 'novel-comment-author';
        nestedAuthor.textContent = 'Hải Ba Đông';
        nestedHeader.appendChild(nestedAuthor);

        const nestedBody = doc.createElement('div');
        nestedBody.className = 'novel-comment-body';

        const mentionSpan = doc.createElement('span');
        mentionSpan.className = 'novel-comment-reply-mention';
        mentionSpan.textContent = '@Dược Lão';

        const bodyTextSpan = doc.createElement('span');
        bodyTextSpan.className = 'novel-comment-reply-body-text';
        bodyTextSpan.textContent = 'Phản hồi lồng ghép cho Dược Lão';

        nestedBody.appendChild(mentionSpan);
        nestedBody.appendChild(bodyTextSpan);

        const nestedActions = doc.createElement('div');
        nestedActions.className = 'novel-comment-actions';

        const nestedEditBtn = doc.createElement('button');
        nestedEditBtn.type = 'button';
        nestedEditBtn.className = 'novel-comment-edit-btn';
        nestedEditBtn.setAttribute('data-action', 'edit');
        nestedEditBtn.setAttribute('data-comment-id', REPLY_ID_NESTED);
        nestedEditBtn.setAttribute('data-reply-id', REPLY_ID_NESTED);
        nestedEditBtn.setAttribute('data-root-id', ROOT_ID);
        nestedEditBtn.textContent = 'Chỉnh sửa';
        nestedActions.appendChild(nestedEditBtn);

        nestedReply.appendChild(nestedHeader);
        nestedReply.appendChild(nestedBody);
        nestedReply.appendChild(nestedActions);
        repliesContainer.appendChild(nestedReply);

        threadCard.appendChild(repliesContainer);
        content.appendChild(threadCard);
        drawer.appendChild(content);
        doc.body.appendChild(drawer);

        refreshActiveDiscussionCalled = false;
        mockDrawerModule = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => {
                refreshActiveDiscussionCalled = true;
            }
        };
    }

    beforeEach(() => {
        resetEditComposerState();
        setupDiscussionDOM(true);
    });

    // =========================================================================
    // 1. Module Exports & Idempotent Init
    // =========================================================================

    test('1. exports all required constants and lifecycle functions', () => {
        assert.strictEqual(EDIT_COMPOSER_CLASS, 'novel-edit-composer');
        assert.strictEqual(EDIT_COMPOSER_FORM_ID, 'novelBlockDiscussionEditComposerForm');
        assert.strictEqual(EDIT_INPUT_CLASS, 'novel-edit-composer-input');
        assert.strictEqual(EDIT_STATUS_CLASS, 'novel-edit-composer-status');
        assert.strictEqual(EDIT_SUBMIT_CLASS, 'novel-edit-composer-submit');
        assert.strictEqual(EDIT_CANCEL_CLASS, 'novel-edit-composer-cancel');
        assert.strictEqual(EDIT_ACTIONS_CLASS, 'novel-edit-composer-actions');

        assert.strictEqual(typeof initReaderBlockDiscussionEditComposer, 'function');
        assert.strictEqual(typeof resetEditComposerState, 'function');
        assert.strictEqual(typeof openEditComposer, 'function');
        assert.strictEqual(typeof closeEditComposer, 'function');
        assert.strictEqual(typeof handleSubmit, 'function');
        assert.strictEqual(typeof handleEditButtonClick, 'function');
    });

    test('2. init is idempotent: multiple init calls bind listeners only once', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });
        const clickCount1 = doc.listeners['click'] ? doc.listeners['click'].length : 0;
        const submitCount1 = doc.listeners['submit'] ? doc.listeners['submit'].length : 0;

        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });
        const clickCount2 = doc.listeners['click'] ? doc.listeners['click'].length : 0;
        const submitCount2 = doc.listeners['submit'] ? doc.listeners['submit'].length : 0;

        assert.strictEqual(clickCount1, clickCount2);
        assert.strictEqual(submitCount1, submitCount2);
    });

    // =========================================================================
    // 2. Open Composer, Prefill & Mention Handling
    // =========================================================================

    test('3. clicking edit button on root comment opens edit composer with prefilled body', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        assert.notStrictEqual(composer, null);
        assert.strictEqual(composer.id, EDIT_COMPOSER_FORM_ID);
        assert.strictEqual(composer.classList.contains(EDIT_COMPOSER_CLASS), true);

        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.notStrictEqual(textarea, null);
        assert.strictEqual(textarea.value, 'Bình luận gốc ban đầu');

        const rootBody = doc.querySelector('.novel-comment--root .novel-comment-body');
        assert.strictEqual(rootBody.hidden, true);

        const rootActions = doc.querySelector('.novel-comment--root .novel-comment-actions');
        assert.strictEqual(rootActions.hidden, true);
    });

    test('4. clicking edit button on nested reply extracts clean body text omitting Wattpad @mention', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const nestedEditBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_NESTED + '"] .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: nestedEditBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        assert.notStrictEqual(composer, null);

        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.notStrictEqual(textarea, null);
        // MUST NOT contain "@Dược Lão"
        assert.strictEqual(textarea.value, 'Phản hồi lồng ghép cho Dược Lão');
        assert.strictEqual(textarea.value.includes('@Dược Lão'), false);

        const replyBody = doc.querySelector('[data-comment-id="' + REPLY_ID_NESTED + '"] .novel-comment-body');
        assert.strictEqual(replyBody.hidden, true);
    });

    test('5. single-composer discipline: opening editor on comment B closes editor on comment A', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const rootEditBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        const directEditBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_DIRECT + '"] .novel-comment-edit-btn');

        // 1. Open on Root
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID);
        const rootBody = doc.querySelector('.novel-comment--root .novel-comment-body');
        assert.strictEqual(rootBody.hidden, true);

        // 2. Open on Direct Reply
        doc.dispatchEvent({ type: 'click', target: directEditBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, REPLY_ID_DIRECT);

        // Root's body and actions MUST be restored
        assert.strictEqual(rootBody.hidden, false);
        const rootActions = doc.querySelector('.novel-comment--root .novel-comment-actions');
        assert.strictEqual(rootActions.hidden, false);

        // Direct reply's body MUST be hidden
        const directBody = doc.querySelector('[data-comment-id="' + REPLY_ID_DIRECT + '"] .novel-comment-body');
        assert.strictEqual(directBody.hidden, true);

        // Only one composer in DOM
        const composers = doc.querySelectorAll('.' + EDIT_COMPOSER_CLASS);
        assert.strictEqual(composers.length, 1);
    });

    // =========================================================================
    // 3. Cancel & Escape Key & Focus Restoration
    // =========================================================================

    test('6. clicking cancel button closes edit composer, restores body, and restores focus', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const cancelBtn = composer.querySelector('.' + EDIT_CANCEL_CLASS);
        assert.notStrictEqual(cancelBtn, null);

        doc.dispatchEvent({ type: 'click', target: cancelBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget(), null);

        const rootBody = doc.querySelector('.novel-comment--root .novel-comment-body');
        assert.strictEqual(rootBody.hidden, false);

        const rootActions = doc.querySelector('.novel-comment--root .novel-comment-actions');
        assert.strictEqual(rootActions.hidden, false);

        assert.strictEqual(editBtn.isFocused, true);
    });

    test('7. pressing Escape key closes active edit composer and restores focus', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        assert.notStrictEqual(getActiveComposerEl(), null);

        doc.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget(), null);
        assert.strictEqual(editBtn.isFocused, true);
    });

    // =========================================================================
    // 4. Validation & Missing CSRF
    // =========================================================================

    test('8. submitting with blank body displays error and prevents PATCH', async () => {
        let fetchCalled = false;
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => {
                fetchCalled = true;
                return { status: 204 };
            }
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = '   ';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(fetchCalled, false);
        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Vui lòng nhập nội dung bình luận.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    test('9. submitting with missing CSRF displays error and prevents PATCH', async () => {
        setupDiscussionDOM(false); // No CSRF meta tags
        let fetchCalled = false;
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => {
                fetchCalled = true;
                return { status: 204 };
            }
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Valid edited text';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(fetchCalled, false);
        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
    });

    // =========================================================================
    // 5. Successful PATCH Submission (HTTP 204)
    // =========================================================================

    test('10. successful submission issues PATCH with correct URL, headers, body, closes editor, and refreshes drawer', async () => {
        let requestedUrl = null;
        let requestedMethod = null;
        let requestedHeaders = null;
        let requestedBody = null;

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async (url, options) => {
                requestedUrl = url;
                requestedMethod = options.method;
                requestedHeaders = options.headers;
                requestedBody = JSON.parse(options.body);
                return { status: 204 };
            }
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Nội dung bình luận đã được chỉnh sửa thành công!';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(requestedUrl, '/api/novel/chapters/' + CHAPTER_ID + '/comments/' + ROOT_ID);
        assert.strictEqual(requestedMethod, 'PATCH');
        assert.strictEqual(requestedHeaders['X-CSRF-TOKEN'], 'test-csrf-token-12345');
        assert.strictEqual(requestedHeaders['Content-Type'], 'application/json');
        assert.deepStrictEqual(requestedBody, { body: 'Nội dung bình luận đã được chỉnh sửa thành công!' });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget(), null);
        assert.strictEqual(refreshActiveDiscussionCalled, true);
    });

    // =========================================================================
    // 6. Error Handling (400, 401/403, 404, 500, Network)
    // =========================================================================

    test('11. 400 validation error preserves draft and displays friendly message', async () => {
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => ({ status: 400 })
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Invalid text';

        await handleSubmit({ preventDefault: () => {} });

        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Nội dung bình luận không hợp lệ.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.strictEqual(textarea.disabled, false);
        assert.strictEqual(textarea.value, 'Invalid text');
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    test('12. 401/403 forbidden error preserves draft and displays friendly message', async () => {
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => ({ status: 403 })
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        await handleSubmit({ preventDefault: () => {} });

        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    test('13. 404 not found error preserves draft and displays friendly message', async () => {
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => ({ status: 404 })
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        await handleSubmit({ preventDefault: () => {} });

        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Bình luận không còn tồn tại.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    test('14. 500 server error preserves draft and displays friendly message', async () => {
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => ({ status: 500 })
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        await handleSubmit({ preventDefault: () => {} });

        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Không thể lưu thay đổi. Vui lòng thử lại.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    test('15. network failure preserves draft and displays connection error message', async () => {
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: async () => { throw new Error('Failed to fetch'); }
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        await handleSubmit({ preventDefault: () => {} });

        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Lỗi kết nối mạng. Vui lòng thử lại.');
        assert.strictEqual(statusEl.classList.contains('is-error'), true);
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    // =========================================================================
    // 7. Race Safety & Lifecycle Teardown
    // =========================================================================

    test('16. stale fetch response from canceled/switched edit cannot close or modify new editor', async () => {
        let resolveSlowFetch;
        const slowPromise = new Promise(resolve => {
            resolveSlowFetch = resolve;
        });

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: () => slowPromise
        });

        const rootEditBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        const directEditBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_DIRECT + '"] .novel-comment-edit-btn');

        // 1. Open editor on Root and start submitting
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });
        const rootComposer = getActiveComposerEl();
        rootComposer.querySelector('.' + EDIT_INPUT_CLASS).value = 'Draft A';
        const submitPromise = handleSubmit({ preventDefault: () => {} });

        // 2. While Root request is slow, user switches to Direct Reply (increments generation token)
        doc.dispatchEvent({ type: 'click', target: directEditBtn, preventDefault: () => {} });
        const newComposer = getActiveComposerEl();
        assert.strictEqual(getActiveEditTarget().commentId, REPLY_ID_DIRECT);
        newComposer.querySelector('.' + EDIT_INPUT_CLASS).value = 'Draft B in progress';

        // 3. Now slow Root request finally finishes with 204
        resolveSlowFetch({ status: 204 });
        await submitPromise;

        // Active composer MUST STILL be Direct Reply with Draft B intact!
        assert.strictEqual(getActiveEditTarget().commentId, REPLY_ID_DIRECT);
        assert.strictEqual(getActiveComposerEl(), newComposer);
        assert.strictEqual(newComposer.querySelector('.' + EDIT_INPUT_CLASS).value, 'Draft B in progress');
    });

    test('17. A -> B -> A stale edit race: stale fetch response from old A cannot close, clear, or refresh returning edit on A', async () => {
        let resolveSlowFetch;
        const slowPromise = new Promise(resolve => {
            resolveSlowFetch = resolve;
        });

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            fetchFn: () => slowPromise
        });

        const rootEditBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        const directEditBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_DIRECT + '"] .novel-comment-edit-btn');

        // 1. Open Edit on Root A
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });
        const oldRootComposer = getActiveComposerEl();

        // 2. Enter "Old A draft"
        oldRootComposer.querySelector('.' + EDIT_INPUT_CLASS).value = 'Old A draft';

        // 3. Submit A using a deferred fetch Promise
        const oldSubmitPromise = handleSubmit({ preventDefault: () => {} });

        // 4. Before old A resolves: open Edit on B
        doc.dispatchEvent({ type: 'click', target: directEditBtn, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, REPLY_ID_DIRECT);

        // 5. Then switch BACK to Edit on A
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });

        // 6. Capture this NEW A composer instance
        const newRootComposer = getActiveComposerEl();

        // 7. Enter "Fresh A draft after returning"
        newRootComposer.querySelector('.' + EDIT_INPUT_CLASS).value = 'Fresh A draft after returning';

        // 8. Confirm active target is A
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID);

        // 9. Resolve the ORIGINAL old A request with { status: 204 }
        resolveSlowFetch({ status: 204 });

        // 10. Await the old submit promise
        await oldSubmitPromise;

        // 11. Assert ALL:
        // - active edit target is still A
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID);
        // - active composer is the NEW A composer
        assert.strictEqual(getActiveComposerEl(), newRootComposer);
        // - textarea still equals: "Fresh A draft after returning"
        assert.strictEqual(newRootComposer.querySelector('.' + EDIT_INPUT_CLASS).value, 'Fresh A draft after returning');
        // - new A composer is not closed
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(doc.querySelectorAll('.' + EDIT_COMPOSER_CLASS).length, 1);
        // - draft is not cleared
        assert.notStrictEqual(newRootComposer.querySelector('.' + EDIT_INPUT_CLASS).value, '');
        // - refreshActiveDiscussion was NOT called by the stale old response
        assert.strictEqual(refreshActiveDiscussionCalled, false);
    });

    test('18. drawer closed event clears edit composer', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget(), null);
    });

    test('19. chapter changed event clears edit composer', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);

        doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget(), null);
    });

    test('20. block discussion requested event clears edit composer', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-requested' });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget(), null);
    });

    test('21. cross-mutation teardown: opening edit closes active reply composer via closeReplyComposer(false)', () => {
        let closeReplyCalled = false;
        let closeReplyRestoreFocusArg = null;
        const mockReplyComposer = {
            closeReplyComposer: (restoreFocus) => {
                closeReplyCalled = true;
                closeReplyRestoreFocusArg = restoreFocus;
            }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            replyComposerModule: mockReplyComposer
        });

        const rootEditBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });

        assert.strictEqual(closeReplyCalled, true);
        assert.strictEqual(closeReplyRestoreFocusArg, false);
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID);
    });

    test('22. MS-05E5H2F3A: submit Edit calls shared editComment exactly once with correct inputs and no direct fetch', async () => {
        let editCommentCallCount = 0;
        let capturedInput = null;
        let capturedOptions = null;
        let directFetchCalled = false;

        const mockMutations = {
            editComment: async (input, options) => {
                editCommentCallCount++;
                capturedInput = input;
                capturedOptions = options;
                return { ok: true, status: 204 };
            }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations,
            fetchFn: async () => {
                directFetchCalled = true;
                return { status: 204 };
            }
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Chỉnh sửa qua shared client';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(editCommentCallCount, 1, 'editComment must be called exactly once');
        assert.strictEqual(capturedInput.chapterId, CHAPTER_ID);
        assert.strictEqual(capturedInput.commentId, ROOT_ID);
        assert.strictEqual(capturedInput.body, 'Chỉnh sửa qua shared client');
        assert.strictEqual(directFetchCalled, false, 'No direct fetch path outside shared client');
        assert.strictEqual(refreshActiveDiscussionCalled, true, 'Authoritative drawer refresh must still be triggered');
        assert.strictEqual(getActiveComposerEl(), null, 'Composer should close on success');
    });

    test('23. MS-05E5H2F3B: ignores edit button clicks originating outside the discussion drawer (e.g., bottom comments)', () => {
        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule
        });

        const bottomSection = doc.createElement('section');
        bottomSection.id = 'novelChapterComments';

        const bottomComment = doc.createElement('div');
        bottomComment.className = 'novel-comment novel-comment--root';
        bottomComment.setAttribute('data-comment-id', 'bottom-comment-1');

        const bottomEditBtn = doc.createElement('button');
        bottomEditBtn.type = 'button';
        bottomEditBtn.className = 'novel-comment-edit-btn';
        bottomEditBtn.setAttribute('data-action', 'edit');
        bottomEditBtn.setAttribute('data-comment-id', 'bottom-comment-1');
        bottomComment.appendChild(bottomEditBtn);
        bottomSection.appendChild(bottomComment);
        doc.body.appendChild(bottomSection);

        doc.dispatchEvent({ type: 'click', target: bottomEditBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null, 'Drawer edit composer must not open for bottom comments');
        assert.strictEqual(getActiveEditTarget(), null);
    });

    describe('MS-05E5H2F4B2 — Drawer Edit → Bottom Synchronization', () => {

        beforeEach(() => {
            resetEditComposerState();
            setupDiscussionDOM(true);
        });

        afterEach(() => {
            resetEditComposerState();
        });

        test('Case A: Root edit when root is loaded in rootPageMap triggers refreshRootThread(rootId)', async () => {
            let editCommentCalls = 0;
            let refreshedRootId = null;
            let pageZeroCalls = 0;

            const mockMutations = {
                editComment: async () => {
                    editCommentCalls++;
                    return { ok: true, status: 204 };
                }
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async (rootId) => {
                    refreshedRootId = rootId;
                },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionEditComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
            doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

            const composer = getActiveComposerEl();
            const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
            textarea.value = 'Updated root comment';

            await handleSubmit({ preventDefault: () => {} });

            assert.strictEqual(editCommentCalls, 1);
            assert.strictEqual(refreshActiveDiscussionCalled, true, 'Drawer must refresh discussion');
            assert.strictEqual(refreshedRootId, ROOT_ID, 'Bottom refreshRootThread must receive ROOT_ID');
            assert.strictEqual(pageZeroCalls, 0, 'No page-0 refresh for edit');
            assert.strictEqual(getActiveComposerEl(), null, 'Composer should close on success');
        });

        test('Case B: Reply edit when root is loaded in rootPageMap triggers refreshRootThread(rootId) with thread rootId', async () => {
            let editCommentInput = null;
            let refreshedRootId = null;
            let pageZeroCalls = 0;

            const mockMutations = {
                editComment: async (input) => {
                    editCommentInput = input;
                    return { ok: true, status: 204 };
                }
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async (rootId) => {
                    refreshedRootId = rootId;
                },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionEditComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            const editBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_NESTED + '"] .novel-comment-edit-btn');
            doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

            const composer = getActiveComposerEl();
            const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
            textarea.value = 'Updated nested reply text';

            await handleSubmit({ preventDefault: () => {} });

            assert.strictEqual(editCommentInput.commentId, REPLY_ID_NESTED, 'PATCH commentId must be the reply ID');
            assert.strictEqual(refreshedRootId, ROOT_ID, 'Bottom refreshRootThread must be called with thread ROOT_ID, not reply ID');
            assert.strictEqual(pageZeroCalls, 0);
            assert.strictEqual(refreshActiveDiscussionCalled, true);
        });

        test('Case C: Edit root or reply when root is NOT loaded in rootPageMap triggers ZERO Bottom refresh', async () => {
            let refreshedRootThreadCalls = 0;
            let pageZeroCalls = 0;

            const mockMutations = {
                editComment: async () => ({ ok: true, status: 204 })
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { 'unloaded-root-id': 0 }
                }),
                refreshRootThread: async () => { refreshedRootThreadCalls++; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionEditComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
            doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

            const composer = getActiveComposerEl();
            const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
            textarea.value = 'Updated root comment not in bottom';

            await handleSubmit({ preventDefault: () => {} });

            assert.strictEqual(refreshedRootThreadCalls, 0, 'Zero thread refresh when root is not loaded');
            assert.strictEqual(pageZeroCalls, 0, 'Zero page-0 refresh when root is not loaded');
            assert.strictEqual(refreshActiveDiscussionCalled, true, 'Drawer must still refresh');
        });

        test('Case D: Secondary Bottom rejection does not retry mutation or fail Drawer refresh', async () => {
            let editCommentCalls = 0;
            let bottomCalls = 0;

            const mockMutations = {
                editComment: async () => {
                    editCommentCalls++;
                    return { ok: true, status: 204 };
                }
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async () => {
                    bottomCalls++;
                    throw new Error('Bottom network error');
                }
            };

            initReaderBlockDiscussionEditComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
            doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

            const composer = getActiveComposerEl();
            const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
            textarea.value = 'Updated root with bottom failure';

            await handleSubmit({ preventDefault: () => {} });

            assert.strictEqual(editCommentCalls, 1, 'editComment must NOT be retried');
            assert.strictEqual(refreshActiveDiscussionCalled, true, 'Drawer refresh must still occur');
            assert.strictEqual(bottomCalls, 1, 'Bottom refresh was attempted once');
            assert.strictEqual(getActiveComposerEl(), null, 'Composer must still close on primary success');
        });

        test('Case E: Stale mutation completion performs zero Bottom synchronization', async () => {
            let resolveEdit;
            let refreshedRootThreadCalls = 0;
            let pageZeroCalls = 0;

            const mockMutations = {
                editComment: () => new Promise(r => { resolveEdit = r; })
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 0 }
                }),
                refreshRootThread: async () => { refreshedRootThreadCalls++; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionEditComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            // 1. Open edit and submit
            const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
            doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

            const composer = getActiveComposerEl();
            const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
            textarea.value = 'In-flight edit text';

            const submitPromise = handleSubmit({ preventDefault: () => {} });

            // 2. Cancel edit while in flight
            closeEditComposer(false);

            // 3. Resolve edit with 204
            resolveEdit({ ok: true, status: 204 });
            await submitPromise;
            await new Promise(r => setTimeout(r, 10));

            assert.strictEqual(refreshedRootThreadCalls, 0, 'Zero Bottom thread refresh for stale mutation');
            assert.strictEqual(pageZeroCalls, 0, 'Zero Bottom page-0 refresh for stale mutation');
        });

        test('Case F: Later-page root loaded in rootPageMap (page 1) triggers refreshRootThread(rootId)', async () => {
            let refreshedRootId = null;
            let pageZeroCalls = 0;

            const mockMutations = {
                editComment: async () => ({ ok: true, status: 204 })
            };
            const mockBottom = {
                getState: () => ({
                    rootPageMap: { [ROOT_ID]: 1 }
                }),
                refreshRootThread: async (rootId) => { refreshedRootId = rootId; },
                refreshFromPageZero: async () => { pageZeroCalls++; }
            };

            initReaderBlockDiscussionEditComposer(doc, {
                drawerModule: mockDrawerModule,
                commentMutations: mockMutations,
                commentsModule: mockBottom
            });

            const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
            doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

            const composer = getActiveComposerEl();
            const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
            textarea.value = 'Updated later-page root';

            await handleSubmit({ preventDefault: () => {} });

            assert.strictEqual(refreshedRootId, ROOT_ID, 'Must refresh exact root thread even when on page 1');
            assert.strictEqual(pageZeroCalls, 0, 'Zero page-0 refresh');
        });

    });

});
