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

describe('MS-05E5G4B Novel Block Discussion Edit Composer Tests', () => {

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
        assert.strictEqual(textarea.maxLength, 2000, 'Drawer edit composer textarea must have maxLength=2000');

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

describe('UX-DRAFT-01D3C — Block Drawer Edit Draft Persistence', () => {
    const EphemeralDraftStore = require(path.join(__dirname, '../../../main/resources/static/js/shared/ephemeral-draft-store.js'));
    const draftsAdapter = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-comment-drafts.js'));

    class MockStorage {
        constructor() {
            this.store = new Map();
        }
        getItem(k) {
            return this.store.has(k) ? this.store.get(k) : null;
        }
        setItem(k, v) {
            this.store.set(k, String(v));
        }
        removeItem(k) {
            this.store.delete(k);
        }
        clear() {
            this.store.clear();
        }
    }

    let mockStorage;
    let draftStore;

    beforeEach(() => {
        mockStorage = new MockStorage();
        draftStore = EphemeralDraftStore.createStore({
            storage: mockStorage,
            defaultTtlMs: 5 * 60 * 1000
        });
        resetEditComposerState();
        setupDiscussionDOM(true);
        EditComposerModule.setDraftStore(draftStore);
        EditComposerModule.setDraftAdapter(draftsAdapter);
    });

    afterEach(() => {
        EditComposerModule.cancelDebounce();
        resetEditComposerState();
    });

    test('1. getEditDraftKey generates canonical edit draft key', () => {
        const key = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const expected = 'kiemlai:draft:novel-comment:' + encodeURIComponent(CHAPTER_ID) + ':block:' + encodeURIComponent(BLOCK_KEY) + ':edit:' + encodeURIComponent(ROOT_ID);
        assert.strictEqual(key, expected);
    });

    test('2. getEditDraftKey returns null for invalid / missing inputs', () => {
        assert.strictEqual(EditComposerModule.getEditDraftKey('', BLOCK_KEY, ROOT_ID), null);
        assert.strictEqual(EditComposerModule.getEditDraftKey(CHAPTER_ID, '', ROOT_ID), null);
        assert.strictEqual(EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ''), null);
        assert.strictEqual(EditComposerModule.getEditDraftKey(null, BLOCK_KEY, ROOT_ID), null);
        assert.strictEqual(EditComposerModule.getEditDraftKey(CHAPTER_ID, null, ROOT_ID), null);
        assert.strictEqual(EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, null), null);
    });

    test('3. getActiveMarkerKey generates canonical chapter-scoped active-block marker key', () => {
        const key = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        const expected = 'kiemlai:draft:novel-comment:' + encodeURIComponent(CHAPTER_ID) + ':active-block';
        assert.strictEqual(key, expected);
        assert.strictEqual(EditComposerModule.getActiveMarkerKey(''), null);
        assert.strictEqual(EditComposerModule.getActiveMarkerKey(null), null);
    });

    test('4. saveEditMarker saves structured payload { type: edit, blockKey, commentId }', () => {
        EditComposerModule.saveEditMarker(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        const raw = draftStore.load(markerKey);
        assert.notStrictEqual(raw, null);
        const parsed = JSON.parse(raw);
        assert.strictEqual(parsed.type, 'edit');
        assert.strictEqual(parsed.blockKey, BLOCK_KEY);
        assert.strictEqual(parsed.commentId, ROOT_ID);
    });

    test('5. removeEditMarkerIfMatching removes only matching edit marker', () => {
        EditComposerModule.saveEditMarker(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        assert.notStrictEqual(draftStore.load(markerKey), null);

        EditComposerModule.removeEditMarkerIfMatching(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        assert.strictEqual(draftStore.load(markerKey), null);
    });

    test('6. removeEditMarkerIfMatching preserves non-matching edit marker', () => {
        EditComposerModule.saveEditMarker(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        EditComposerModule.removeEditMarkerIfMatching(CHAPTER_ID, BLOCK_KEY, 'different-comment-id');
        assert.notStrictEqual(draftStore.load(markerKey), null);

        EditComposerModule.removeEditMarkerIfMatching(CHAPTER_ID, 'different-block', ROOT_ID);
        assert.notStrictEqual(draftStore.load(markerKey), null);
    });

    test('7. removeEditMarkerIfMatching preserves root and reply markers untouched', () => {
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        const rootPayload = JSON.stringify({ type: 'root', blockKey: BLOCK_KEY });
        draftStore.save(markerKey, rootPayload);
        EditComposerModule.removeEditMarkerIfMatching(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        assert.strictEqual(draftStore.load(markerKey), rootPayload, 'Root marker untouched');

        const replyPayload = JSON.stringify({ type: 'reply', blockKey: BLOCK_KEY, commentId: ROOT_ID });
        draftStore.save(markerKey, replyPayload);
        EditComposerModule.removeEditMarkerIfMatching(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        assert.strictEqual(draftStore.load(markerKey), replyPayload, 'Reply marker untouched');
    });

    test('8. removeEditMarkerIfMatching removes corrupted JSON marker safely', () => {
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        draftStore.save(markerKey, '{corrupted-edit-marker-json');
        EditComposerModule.removeEditMarkerIfMatching(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        assert.strictEqual(draftStore.load(markerKey), null);
    });

    test('9. Opening edit composer with no draft loads clean comment body (activeOriginalBody baseline)', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        assert.notStrictEqual(composer, null);
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Bình luận gốc ban đầu');

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        assert.strictEqual(draftStore.load(draftKey), null, 'No draft saved yet');
        assert.strictEqual(draftStore.load(markerKey), null, 'No active marker saved yet');
    });

    test('10. Opening edit composer with matching saved dirty draft pre-fills draft and sets active edit marker', () => {
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        draftStore.save(draftKey, 'Saved dirty draft edit text');

        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Saved dirty draft edit text');

        const markerPayload = draftStore.load(markerKey);
        assert.notStrictEqual(markerPayload, null);
        const parsed = JSON.parse(markerPayload);
        assert.strictEqual(parsed.type, 'edit');
        assert.strictEqual(parsed.commentId, ROOT_ID);
    });

    test('11. Opening edit composer when saved draft equals comment body (clean) discards draft and loads clean body', () => {
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        draftStore.save(draftKey, 'Bình luận gốc ban đầu');
        draftStore.save(markerKey, JSON.stringify({ type: 'edit', blockKey: BLOCK_KEY, commentId: ROOT_ID }));

        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Bình luận gốc ban đầu');
        assert.strictEqual(draftStore.load(draftKey), null, 'Stale clean draft removed');
        assert.strictEqual(draftStore.load(markerKey), null, 'Active marker removed');
    });

    test('12. Opening edit composer with whitespace-only draft discards draft and loads clean body', () => {
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        draftStore.save(draftKey, '     ');
        draftStore.save(markerKey, JSON.stringify({ type: 'edit', blockKey: BLOCK_KEY, commentId: ROOT_ID }));

        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Bình luận gốc ban đầu');
        assert.strictEqual(draftStore.load(draftKey), null, 'Blank draft removed');
        assert.strictEqual(draftStore.load(markerKey), null, 'Active marker removed');
    });

    test('13. Typing identical to activeOriginalBody cleans draft from store and removes active edit marker', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        // First type dirty text
        textarea.value = 'Different text';
        textarea.dispatchEvent({ type: 'input' });
        assert.notStrictEqual(draftStore.load(markerKey), null);

        // Now revert to exactly original body
        textarea.value = 'Bình luận gốc ban đầu';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(draftStore.load(draftKey), null, 'Draft removed when back to original');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed when back to original');
    });

    test('14. Typing whitespace-only cleans draft from store and removes active edit marker', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        textarea.value = 'Something edited';
        textarea.dispatchEvent({ type: 'input' });
        assert.notStrictEqual(draftStore.load(markerKey), null);

        textarea.value = '   ';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(draftStore.load(draftKey), null, 'Draft removed when blank');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed when blank');
    });

    test('15. Typing dirty text saves active edit marker immediately and debounces draft persistence ~400ms', async () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        textarea.value = 'Modified draft text';
        textarea.dispatchEvent({ type: 'input' });

        // Marker saved synchronously
        assert.notStrictEqual(draftStore.load(markerKey), null, 'Marker saved immediately');

        // Draft not saved before debounce
        assert.strictEqual(draftStore.load(draftKey), null, 'Draft not yet saved before debounce');

        // Wait for debounce timer (400ms)
        await new Promise(r => setTimeout(r, 450));

        assert.strictEqual(draftStore.load(draftKey), 'Modified draft text', 'Draft saved after debounce');
    });

    test('16. Typing dirty text then reverting back to activeOriginalBody before debounce fires cancels timer and cleans draft', async () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        textarea.value = 'Temporary modified text';
        textarea.dispatchEvent({ type: 'input' });

        textarea.value = 'Bình luận gốc ban đầu';
        textarea.dispatchEvent({ type: 'input' });

        await new Promise(r => setTimeout(r, 450));

        assert.strictEqual(draftStore.load(draftKey), null, 'Draft remains null');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker remains null');
    });

    test('17. Clicking Cancel button (Hủy) discards draft from store and removes active edit marker', async () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        textarea.value = 'Draft text to discard';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(draftStore.load(draftKey), 'Draft text to discard');

        const cancelBtn = composer.querySelector('.' + EDIT_CANCEL_CLASS);
        doc.dispatchEvent({ type: 'click', target: cancelBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed');
        assert.strictEqual(draftStore.load(draftKey), null, 'Draft deleted on Cancel');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker deleted on Cancel');
    });

    test('18. Pressing Escape key discards draft from store and removes active edit marker', async () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        textarea.value = 'Draft text before Escape';
        textarea.dispatchEvent({ type: 'input' });
        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(draftStore.load(draftKey), 'Draft text before Escape');

        doc.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27, preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed on Escape');
        assert.strictEqual(draftStore.load(draftKey), null, 'Draft deleted on Escape');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker deleted on Escape');
    });

    test('19. Passive close (switching to another comment) flushes dirty draft of previous target to store and removes its active marker', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        // Open editor on Root comment
        const rootEditBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });

        const composer1 = getActiveComposerEl();
        const textarea1 = composer1.querySelector('.' + EDIT_INPUT_CLASS);
        textarea1.value = 'Unsaved draft on root comment';

        const rootDraftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        // Click edit on direct reply without canceling root
        const directEditBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_DIRECT + '"] .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: directEditBtn, preventDefault: () => {} });

        // Root draft must have been passively flushed
        assert.strictEqual(draftStore.load(rootDraftKey), 'Unsaved draft on root comment');

        // Previous target's active marker must have been removed
        assert.strictEqual(draftStore.load(markerKey), null, 'Previous active marker removed when new target is clean');

        // When user types in new target, its active marker is established
        const composer2 = getActiveComposerEl();
        const textarea2 = composer2.querySelector('.' + EDIT_INPUT_CLASS);
        textarea2.value = 'Unsaved draft on direct reply';
        textarea2.dispatchEvent({ type: 'input' });

        const marker = JSON.parse(draftStore.load(markerKey) || '{}');
        assert.strictEqual(marker.commentId, REPLY_ID_DIRECT);
    });

    test('20. Passive close on chapter-changed flushes dirty draft to store and removes active marker', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Dirty draft before chapter change';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed');
        assert.strictEqual(draftStore.load(draftKey), 'Dirty draft before chapter change');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed on chapter change');
    });

    test('21. Passive close on drawer-closed flushes dirty draft to store and removes active marker', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Dirty draft before drawer closed';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed');
        assert.strictEqual(draftStore.load(draftKey), 'Dirty draft before drawer closed');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed on drawer closed');
    });

    test('22. Passive close on block-discussion-requested flushes dirty draft to store and removes active marker', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Dirty draft before block switch';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-requested', detail: { blockKey: 'blk-other-12345678-1' } });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed');
        assert.strictEqual(draftStore.load(draftKey), 'Dirty draft before block switch');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed on block switch');
    });

    test('23. kiemlai:novel-comment-drafts-flush (pagehide bridge) flushes active draft to store immediately', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Un-debounced draft text';
        // Note: NO debounce wait!

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        assert.strictEqual(draftStore.load(draftKey), null);

        doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        assert.strictEqual(draftStore.load(draftKey), 'Un-debounced draft text');
    });

    test('24. Submit with dirty body saves raw draft before network request and snapshots generation', async () => {
        let resolveEdit;
        const mockMutations = {
            editComment: () => new Promise(resolve => { resolveEdit = resolve; })
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Dirty text before submit   ';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        const submitPromise = handleSubmit({ preventDefault: () => {} });

        // Verify draft was saved immediately before network completes, preserving raw whitespace
        assert.strictEqual(draftStore.load(draftKey), 'Dirty text before submit   ');
        assert.notStrictEqual(draftStore.load(markerKey), null);

        resolveEdit({ ok: true, status: 204 });
        await submitPromise;
    });

    test('25. Submit success (HTTP 200/204) cleans draft and active marker when generation has not advanced', async () => {
        const mockMutations = {
            editComment: async () => ({ ok: true, status: 204 })
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Successfully edited text';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed on success');
        assert.strictEqual(draftStore.load(draftKey), null, 'Draft removed on success');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed on success');
    });

    test('26. Submit server error (HTTP 400/404/500) preserves draft and marker in store', async () => {
        const mockMutations = {
            editComment: async () => ({ ok: false, status: 500 })
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft text that failed on server';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        await handleSubmit({ preventDefault: () => {} });

        assert.notStrictEqual(getActiveComposerEl(), null, 'Composer stays open');
        assert.strictEqual(draftStore.load(draftKey), 'Draft text that failed on server', 'Draft preserved');
        assert.notStrictEqual(draftStore.load(markerKey), null, 'Marker preserved');
    });

    test('27. Submit network failure preserves draft and marker in store', async () => {
        const mockMutations = {
            editComment: async () => { throw new Error('Network offline'); }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft text with network error';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        await handleSubmit({ preventDefault: () => {} });

        assert.notStrictEqual(getActiveComposerEl(), null, 'Composer stays open');
        assert.strictEqual(draftStore.load(draftKey), 'Draft text with network error', 'Draft preserved');
        assert.notStrictEqual(draftStore.load(markerKey), null, 'Marker preserved');
    });

    test('28. Stale successful submit (user typed while submit was in flight) does NOT delete newer draft or marker', async () => {
        let resolveEdit;
        const mockMutations = {
            editComment: () => new Promise(resolve => { resolveEdit = resolve; })
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'First draft in flight';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        const submitPromise = handleSubmit({ preventDefault: () => {} });

        // In flight: user types newer text
        textarea.value = 'Second draft typed while in flight';
        textarea.dispatchEvent({ type: 'input' });

        // Resolve first submit
        resolveEdit({ ok: true, status: 204 });
        await submitPromise;

        // The newer draft must NOT be deleted!
        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(draftStore.load(draftKey), 'Second draft typed while in flight');
        assert.notStrictEqual(draftStore.load(markerKey), null);
    });

    test('29. ABA switch: Editing Comment A -> B -> A does not corrupt or overwrite drafts', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const rootDraftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const directDraftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, REPLY_ID_DIRECT);

        // 1. Open Root (A) and type draft
        const rootEditBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });
        let composer = getActiveComposerEl();
        let textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft for Comment A';

        // 2. Switch to Direct Reply (B) and type draft
        const directEditBtn = doc.querySelector('[data-comment-id="' + REPLY_ID_DIRECT + '"] .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: directEditBtn, preventDefault: () => {} });

        assert.strictEqual(draftStore.load(rootDraftKey), 'Draft for Comment A');

        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft for Comment B';

        // 3. Switch back to Root (A)
        doc.dispatchEvent({ type: 'click', target: rootEditBtn, preventDefault: () => {} });

        assert.strictEqual(draftStore.load(directDraftKey), 'Draft for Comment B');

        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Draft for Comment A', 'Comment A draft correctly restored');
    });

    test('30. Re-editing same comment after successful submit starts clean with newly saved comment body', async () => {
        let serverBody = 'Bình luận gốc ban đầu';
        const mockMutations = {
            editComment: async (input) => {
                serverBody = input.body;
                return { ok: true, status: 204 };
            }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        // 1. Edit and submit
        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        let composer = getActiveComposerEl();
        let textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'New authoritative comment content';

        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(getActiveComposerEl(), null);

        // Update DOM comment body to simulate drawer refresh with updated body
        const rootBody = doc.querySelector('.novel-comment--root .novel-comment-body');
        rootBody.textContent = 'New authoritative comment content';

        // 2. Re-open editor for same comment
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'New authoritative comment content');

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        assert.strictEqual(draftStore.load(draftKey), null, 'Clean baseline, no draft');
        assert.strictEqual(draftStore.load(markerKey), null, 'No active marker');
    });

    test('31. handleEditResumeRequested: valid event detail opens edit composer on exact comment, pre-fills draft, and focuses textarea', () => {
        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        draftStore.save(draftKey, 'Restored draft from resume event');

        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        doc.dispatchEvent({
            type: 'kiemlai:comment-edit-resume-requested',
            detail: {
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                commentId: ROOT_ID
            }
        });

        const composer = getActiveComposerEl();
        assert.notStrictEqual(composer, null);
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Restored draft from resume event');
        assert.strictEqual(textarea.isFocused, true);
    });

    test('32. True accepted-remount race: pending submit -> passive close -> remount -> old 204 resolves -> flush & discussion-requested do NOT resurrect A1', async () => {
        let resolveEditA1;
        let drawerRefreshes = 0;
        let bottomSyncCalls = 0;

        const mockMutations = {
            editComment: () => new Promise(resolve => { resolveEditA1 = resolve; })
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => { drawerRefreshes++; }
        };
        const mockBottom = {
            getState: () => ({ rootPageMap: { [ROOT_ID]: 0 } }),
            refreshRootThread: async () => { bottomSyncCalls++; }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawer,
            commentMutations: mockMutations,
            commentsModule: mockBottom
        });

        // 1. Open Edit A
        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        // 2. Capture original server body O: 'Bình luận gốc ban đầu'
        // 3. Type A1 via genuine input event
        let composer = getActiveComposerEl();
        let textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft A1 in flight';
        textarea.dispatchEvent({ type: 'input' });

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        // 4. Submit A1 with editComment Promise kept PENDING
        const submitPromiseA1 = handleSubmit({ preventDefault: () => {} });

        // 5. Passive-close Edit A
        closeEditComposer(false);

        // 6. Assert A1 was flushed to draft store
        assert.strictEqual(draftStore.load(draftKey), 'Draft A1 in flight');

        // 7. Reopen SAME Edit A
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        // 8. Assert textarea restored A1 FROM STORE
        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Draft A1 in flight');

        // 9. Dispatch NO new input

        // 10. Resolve OLD A1 request with success
        resolveEditA1({ ok: true, status: 204 });
        await submitPromiseA1;
        await new Promise(r => setTimeout(r, 10));

        // 11. Assert old response is stale:
        // - current remounted composer remains open
        // - textarea still visibly contains A1
        // - zero current Drawer refresh
        // - zero Bottom synchronization
        // - stored A1 draft is removed due accepted generation
        assert.notStrictEqual(getActiveComposerEl(), null, 'Current remounted composer remains open');
        assert.strictEqual(textarea.value, 'Draft A1 in flight', 'Textarea still visibly contains A1');
        assert.strictEqual(drawerRefreshes, 0, 'Zero Drawer refresh for stale A1 completion');
        assert.strictEqual(bottomSyncCalls, 0, 'Zero Bottom sync for stale A1 completion');
        assert.strictEqual(draftStore.load(draftKey), null, 'Stored draft removed on 204 acceptance');

        // 12. Dispatch EVENT_FLUSH_DRAFTS
        doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        // 13. Assert A1 does NOT resurrect
        assert.strictEqual(draftStore.load(draftKey), null, 'A1 does not resurrect on global flush');

        // 14. Dispatch REAL EVENT_DISCUSSION_REQUESTED
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-requested',
            detail: { chapterId: CHAPTER_ID, blockKey: 'blk-other' }
        });

        // 15. Assert:
        // - A1 still absent from store
        // - matching Edit marker removed
        assert.strictEqual(draftStore.load(draftKey), null, 'A1 remains absent on block requested');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed on block requested');

        // 16. Reopen A
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });
        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Bình luận gốc ban đầu', 'Clean body loaded');

        // 17. Type genuine A2 via input
        textarea.value = 'Genuine Draft A2 content';
        textarea.dispatchEvent({ type: 'input' });

        // 18. Flush
        doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });

        // 19. Assert A2 persists normally
        assert.strictEqual(draftStore.load(draftKey), 'Genuine Draft A2 content', 'Genuine Draft A2 persists');
    });

    test('33. Drawer-close accepted-remount: pending submit -> passive close -> remount -> old 204 resolves -> kiemlai:block-discussion-closed cleans marker and does NOT resurrect A1', async () => {
        let resolveEditA1;
        let drawerRefreshes = 0;
        let bottomSyncCalls = 0;

        const mockMutations = {
            editComment: () => new Promise(r => { resolveEditA1 = r; })
        };
        const mockDrawer = {
            getActiveContext: () => ({ chapterId: CHAPTER_ID, blockKey: BLOCK_KEY }),
            refreshActiveDiscussion: async () => { drawerRefreshes++; }
        };
        const mockBottom = {
            getState: () => ({ rootPageMap: { [ROOT_ID]: 0 } }),
            refreshRootThread: async () => { bottomSyncCalls++; }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawer,
            commentMutations: mockMutations,
            commentsModule: mockBottom
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        let composer = getActiveComposerEl();
        let textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft A1 in flight';
        textarea.dispatchEvent({ type: 'input' });

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);

        const submitPromiseA1 = handleSubmit({ preventDefault: () => {} });

        closeEditComposer(false);

        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });
        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Draft A1 in flight');

        // Old 204 resolves
        resolveEditA1({ ok: true, status: 204 });
        await submitPromiseA1;
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(draftStore.load(draftKey), null, 'Draft removed on 204 acceptance');
        assert.strictEqual(drawerRefreshes, 0, 'No refresh from stale response');
        assert.strictEqual(bottomSyncCalls, 0, 'No bottom sync from stale response');

        // Drawer closed event dispatched
        doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });

        assert.strictEqual(draftStore.load(draftKey), null, 'A1 does not resurrect on drawer close');
        assert.strictEqual(draftStore.load(markerKey), null, 'Matching Edit marker removed on drawer close');
    });

    test('34. Post-accepted direct-submit: Draft 1 accepted (G1) -> update server body -> reopen same target -> set textarea.value = "Draft 2" without input event -> submit fails (500) -> Draft 2 persists across flush and passive close', async () => {
        let mutationCalls = 0;
        const mockMutations = {
            editComment: async () => {
                mutationCalls++;
                if (mutationCalls === 1) {
                    return { ok: true, status: 204 };
                }
                const err = new Error('HTTP 500');
                err.status = 500;
                throw err;
            }
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentMutations: mockMutations
        });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        let composer = getActiveComposerEl();
        let textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft 1 initial content';
        textarea.dispatchEvent({ type: 'input' });

        // Submit 1 succeeds
        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(getActiveComposerEl(), null);

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        assert.strictEqual(draftStore.load(draftKey), null, 'Draft 1 cleaned on success');

        // Update live server body in DOM
        const rootBody = doc.querySelector('.novel-comment--root .novel-comment-body');
        rootBody.textContent = 'Server updated body content';

        // Reopen same target
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });
        composer = getActiveComposerEl();
        textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Server updated body content');

        // Set textarea.value = "Draft 2" WITHOUT dispatching input event
        textarea.value = 'Draft 2 content without input event';

        // Submit 2 fails (HTTP 500)
        await handleSubmit({ preventDefault: () => {} });

        // Assert:
        // - submit path allocated ownership newer than accepted generation behaviorally
        // - exact Draft 2 exists in draft store
        // - matching Edit marker exists
        assert.strictEqual(draftStore.load(draftKey), 'Draft 2 content without input event', 'Draft 2 exists in store');
        const marker = JSON.parse(draftStore.load(markerKey) || '{}');
        assert.strictEqual(marker.type, 'edit');
        assert.strictEqual(marker.commentId, ROOT_ID);

        // EVENT_FLUSH_DRAFTS preserves Draft 2
        doc.dispatchEvent({ type: 'kiemlai:novel-comment-drafts-flush' });
        assert.strictEqual(draftStore.load(draftKey), 'Draft 2 content without input event', 'Draft 2 preserved across flush');

        // closeEditComposer(false) preserves Draft 2
        closeEditComposer(false);
        assert.strictEqual(draftStore.load(draftKey), 'Draft 2 content without input event', 'Draft 2 preserved across passive close');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed on passive close');
    });

    test('35. Live Root Authority: live thread root = ROOT_ID, button data-root-id = WRONG_ROOT -> getActiveEditTarget().rootId === ROOT_ID and Bottom sync uses ROOT_ID', async () => {
        let refreshedRootId = null;
        const mockBottom = {
            getState: () => ({ rootPageMap: { [ROOT_ID]: 0 } }),
            refreshRootThread: async (rootId) => { refreshedRootId = rootId; }
        };
        const mockMutations = {
            editComment: async () => ({ ok: true, status: 204 })
        };

        initReaderBlockDiscussionEditComposer(doc, {
            drawerModule: mockDrawerModule,
            commentsModule: mockBottom,
            commentMutations: mockMutations
        });

        // Corrupt button's data-root-id
        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        editBtn.setAttribute('data-root-id', 'WRONG_ROOT_METADATA');

        // Open edit through real click
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        // Assert: live enclosing thread root wins over button metadata
        const activeTarget = EditComposerModule.getActiveEditTarget();
        assert.notStrictEqual(activeTarget, null);
        assert.strictEqual(activeTarget.rootId, ROOT_ID, 'Live thread root wins over button data-root-id');

        // Submit and assert Bottom sync uses live thread ROOT_ID and NEVER WRONG_ROOT
        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Updated text with root authority';

        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(refreshedRootId, ROOT_ID, 'Bottom refreshRootThread used live thread ROOT_ID');
        assert.notStrictEqual(refreshedRootId, 'WRONG_ROOT_METADATA');
    });

    test('36. Resume Broken-Thread: exact comment exists, not tombstone, has Edit action, body exists, BUT no enclosing thread -> zero Edit composer mounted', () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        // Move comment outside of any .novel-block-discussion-thread
        const rootComment = doc.querySelector('.novel-comment--root');
        content.appendChild(rootComment); // directly in content, outside of article.novel-block-discussion-thread

        doc.dispatchEvent({
            type: 'kiemlai:comment-edit-resume-requested',
            detail: {
                chapterId: CHAPTER_ID,
                blockKey: BLOCK_KEY,
                commentId: ROOT_ID
            }
        });

        // Assert zero Edit composer mounted, no fallback to commentId
        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(EditComposerModule.getActiveEditTarget(), null);
    });

    test('37. Server Body Baseline: stored draft D restored, activeOriginalBody is live DOM body O2 (not old historical O1), setting textarea to O2 cleans draft and marker', () => {
        // Historical O1: 'Old historical body'
        // Live DOM body O2: 'Current live DOM body O2'
        const rootBody = doc.querySelector('.novel-comment--root .novel-comment-body');
        rootBody.textContent = 'Current live DOM body O2';

        const draftKey = EditComposerModule.getEditDraftKey(CHAPTER_ID, BLOCK_KEY, ROOT_ID);
        const markerKey = EditComposerModule.getActiveMarkerKey(CHAPTER_ID);
        draftStore.save(draftKey, 'Stored draft text D');
        draftStore.save(markerKey, JSON.stringify({ type: 'edit', blockKey: BLOCK_KEY, commentId: ROOT_ID }));

        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Stored draft text D');

        const activeTarget = EditComposerModule.getActiveEditTarget();
        assert.strictEqual(activeTarget.activeOriginalBody, 'Current live DOM body O2', 'Baseline is live O2');

        // Set textarea exactly to O2 and dispatch input
        textarea.value = 'Current live DOM body O2';
        textarea.dispatchEvent({ type: 'input' });

        // Draft and marker removed immediately upon matching baseline
        assert.strictEqual(draftStore.load(draftKey), null, 'Draft removed when matching O2 baseline');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed when matching O2 baseline');
    });

    test('38. Single input ownership: one genuine input event produces one debounce/autosave lifecycle', async () => {
        initReaderBlockDiscussionEditComposer(doc, { drawerModule: mockDrawerModule });

        const editBtn = doc.querySelector('.novel-comment--root .novel-comment-edit-btn');
        doc.dispatchEvent({ type: 'click', target: editBtn, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);

        let saveCount = 0;
        const originalSave = draftStore.save.bind(draftStore);
        draftStore.save = (k, v) => {
            if (k.includes(':edit:')) {
                saveCount++;
            }
            return originalSave(k, v);
        };

        textarea.value = 'Single input test text';
        textarea.dispatchEvent({ type: 'input' });

        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(saveCount, 1, 'Exactly one save occurred for one input event debounce cycle');
    });
});
