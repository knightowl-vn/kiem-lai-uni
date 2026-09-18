const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const deleteModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-delete.js'));

const {
    SECTION_ID,
    STATUS_ID,
    CONFIRMATION_CLASS,
    CONFIRMATION_MSG_CLASS,
    CONFIRMATION_STATUS_CLASS,
    CONFIRMATION_ACTIONS_CLASS,
    CONFIRM_BTN_CLASS,
    CANCEL_BTN_CLASS,
    initReaderChapterCommentDelete,
    destroy,
    resetDeleteState,
    openDeleteConfirmation,
    closeDeleteConfirmation,
    handleConfirmDelete,
    getActiveDeleteTarget,
    getActiveConfirmationEl,
    isDeletingComment,
    getCurrentMutationToken
} = deleteModule;

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
        newChild.ownerDocument = this.ownerDocument;
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
        this.head.ownerDocument = this;
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.activeElement = null;
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    getElementById(id) {
        const all = this.querySelectorAll('#' + id);
        return all[0] || null;
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
        const idx = this.listeners[event].indexOf(fn);
        if (idx !== -1) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(evt) {
        try { evt.target = evt.target || this; } catch (_) {}
        try { evt.currentTarget = this; } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

// ============================================================================
// Fixture Builder
// ============================================================================

const CHAPTER_ID = 'chap-del-1';
const ROOT_ID_1 = 'root-del-1';
const ROOT_ID_2 = 'root-del-2';
const REPLY_ID_1 = 'rep-del-1';

function createDeleteFixture() {
    const doc = new FakeDocument();

    const section = doc.createElement('section');
    section.setAttribute('id', SECTION_ID);
    section.setAttribute('data-chapter-id', CHAPTER_ID);

    const status = doc.createElement('div');
    status.setAttribute('id', STATUS_ID);
    section.appendChild(status);

    const list = doc.createElement('div');
    list.setAttribute('id', 'novelChapterCommentsList');

    // Thread 1
    const thread1 = doc.createElement('article');
    thread1.className = 'novel-block-discussion-thread';
    thread1.setAttribute('data-root-id', ROOT_ID_1);

    const rootComment1 = doc.createElement('div');
    rootComment1.className = 'novel-comment novel-comment--root';
    rootComment1.setAttribute('data-comment-id', ROOT_ID_1);

    const rootHeader1 = doc.createElement('header');
    rootHeader1.className = 'novel-comment-header';
    const menu1 = doc.createElement('div');
    menu1.className = 'novel-comment-actions-menu';
    const popover1 = doc.createElement('div');
    popover1.className = 'novel-comment-menu-popover';
    const rootDeleteBtn1 = doc.createElement('button');
    rootDeleteBtn1.className = 'novel-comment-menu-item novel-comment-delete-btn';
    rootDeleteBtn1.setAttribute('data-action', 'delete');
    rootDeleteBtn1.setAttribute('data-comment-id', ROOT_ID_1);
    rootDeleteBtn1.setAttribute('data-root-id', ROOT_ID_1);
    rootDeleteBtn1.textContent = 'Xóa';
    popover1.appendChild(rootDeleteBtn1);
    menu1.appendChild(popover1);
    rootHeader1.appendChild(menu1);
    rootComment1.appendChild(rootHeader1);

    const rootBody1 = doc.createElement('div');
    rootBody1.className = 'novel-comment-body';
    rootBody1.textContent = 'Root 1 original body';
    rootComment1.appendChild(rootBody1);

    const rootActions1 = doc.createElement('div');
    rootActions1.className = 'novel-comment-actions';
    const replyBtn1 = doc.createElement('button');
    replyBtn1.className = 'novel-comment-reply-btn';
    replyBtn1.setAttribute('data-action', 'reply');
    replyBtn1.textContent = 'Phản hồi';
    rootActions1.appendChild(replyBtn1);
    rootComment1.appendChild(rootActions1);

    thread1.appendChild(rootComment1);

    // Replies container
    const repliesContainer = doc.createElement('div');
    repliesContainer.className = 'novel-comment-replies';

    const reply1 = doc.createElement('article');
    reply1.className = 'novel-comment novel-comment--reply';
    reply1.setAttribute('data-comment-id', REPLY_ID_1);
    reply1.setAttribute('data-reply-id', REPLY_ID_1);

    const replyHeader1 = doc.createElement('header');
    replyHeader1.className = 'novel-comment-header';
    const repMenu1 = doc.createElement('div');
    repMenu1.className = 'novel-comment-actions-menu';
    const repPopover1 = doc.createElement('div');
    repPopover1.className = 'novel-comment-menu-popover';
    const repDeleteBtn1 = doc.createElement('button');
    repDeleteBtn1.className = 'novel-comment-menu-item novel-comment-delete-btn';
    repDeleteBtn1.setAttribute('data-action', 'delete');
    repDeleteBtn1.setAttribute('data-comment-id', REPLY_ID_1);
    repDeleteBtn1.setAttribute('data-root-id', ROOT_ID_1);
    repDeleteBtn1.textContent = 'Xóa';
    repPopover1.appendChild(repDeleteBtn1);
    repMenu1.appendChild(repPopover1);
    replyHeader1.appendChild(repMenu1);
    reply1.appendChild(replyHeader1);

    const replyBody1 = doc.createElement('div');
    replyBody1.className = 'novel-comment-body';
    replyBody1.textContent = 'Reply 1 body';
    reply1.appendChild(replyBody1);

    const replyActions1 = doc.createElement('div');
    replyActions1.className = 'novel-comment-actions';
    const repReplyBtn1 = doc.createElement('button');
    repReplyBtn1.className = 'novel-comment-reply-btn';
    repReplyBtn1.setAttribute('data-action', 'reply');
    replyActions1.appendChild(repReplyBtn1);
    reply1.appendChild(replyActions1);

    repliesContainer.appendChild(reply1);
    thread1.appendChild(repliesContainer);
    list.appendChild(thread1);

    // Thread 2 (UNANCHORED)
    const thread2 = doc.createElement('article');
    thread2.className = 'novel-block-discussion-thread';
    thread2.setAttribute('data-root-id', ROOT_ID_2);

    const rootComment2 = doc.createElement('div');
    rootComment2.className = 'novel-comment novel-comment--root';
    rootComment2.setAttribute('data-comment-id', ROOT_ID_2);

    const rootHeader2 = doc.createElement('header');
    rootHeader2.className = 'novel-comment-header';
    const menu2 = doc.createElement('div');
    menu2.className = 'novel-comment-actions-menu';
    const popover2 = doc.createElement('div');
    popover2.className = 'novel-comment-menu-popover';
    const rootDeleteBtn2 = doc.createElement('button');
    rootDeleteBtn2.className = 'novel-comment-menu-item novel-comment-delete-btn';
    rootDeleteBtn2.setAttribute('data-action', 'delete');
    rootDeleteBtn2.setAttribute('data-comment-id', ROOT_ID_2);
    rootDeleteBtn2.setAttribute('data-root-id', ROOT_ID_2);
    rootDeleteBtn2.textContent = 'Xóa';
    popover2.appendChild(rootDeleteBtn2);
    menu2.appendChild(popover2);
    rootHeader2.appendChild(menu2);
    rootComment2.appendChild(rootHeader2);

    const rootBody2 = doc.createElement('div');
    rootBody2.className = 'novel-comment-body';
    rootBody2.textContent = 'Root 2 original body';
    rootComment2.appendChild(rootBody2);

    const rootActions2 = doc.createElement('div');
    rootActions2.className = 'novel-comment-actions';
    const replyBtn2 = doc.createElement('button');
    replyBtn2.className = 'novel-comment-reply-btn';
    replyBtn2.setAttribute('data-action', 'reply');
    rootActions2.appendChild(replyBtn2);
    rootComment2.appendChild(rootActions2);

    thread2.appendChild(rootComment2);
    list.appendChild(thread2);

    section.appendChild(list);
    doc.body.appendChild(section);

    return {
        doc,
        section,
        status,
        list,
        thread1,
        rootComment1,
        rootDeleteBtn1,
        rootActions1,
        reply1,
        repDeleteBtn1,
        replyActions1,
        thread2,
        rootComment2,
        rootDeleteBtn2,
        rootActions2
    };
}

// ============================================================================
// Test Suite: reader-chapter-comment-delete.test.js
// ============================================================================

describe('Reader Chapter Comment Delete UI (MS-05E5H2F3B)', () => {
    let fixture;
    let mockMutations;
    let mockCommentsModule;
    let mockReplyComposer;
    let mockEditComposer;
    let refreshRootThreadCalls;
    let refreshFromPageZeroCalls;
    let closeReplyComposerCalls;
    let closeEditComposerCalls;

    beforeEach(() => {
        resetDeleteState();
        fixture = createDeleteFixture();
        refreshRootThreadCalls = [];
        refreshFromPageZeroCalls = 0;
        closeReplyComposerCalls = 0;
        closeEditComposerCalls = 0;

        mockMutations = {
            deleteComment: async (input, options) => {
                return { ok: true, status: 204 };
            }
        };

        mockCommentsModule = {
            refreshRootThread: async (rootId) => {
                refreshRootThreadCalls.push(rootId);
            },
            refreshFromPageZero: async () => {
                refreshFromPageZeroCalls++;
            }
        };

        mockReplyComposer = {
            closeActiveComposer: () => {
                closeReplyComposerCalls++;
            }
        };

        mockEditComposer = {
            closeEditComposer: () => {
                closeEditComposerCalls++;
            }
        };
    });

    test('1. Exports all required constants and functions', () => {
        assert.strictEqual(SECTION_ID, 'novelChapterComments');
        assert.strictEqual(STATUS_ID, 'novelChapterCommentsStatus');
        assert.strictEqual(CONFIRMATION_CLASS, 'novel-chapter-comment-delete-confirmation');
        assert.strictEqual(CONFIRMATION_MSG_CLASS, 'novel-chapter-comment-delete-message');
        assert.strictEqual(CONFIRMATION_STATUS_CLASS, 'novel-chapter-comment-delete-status');
        assert.strictEqual(CONFIRMATION_ACTIONS_CLASS, 'novel-chapter-comment-delete-actions');
        assert.strictEqual(CONFIRM_BTN_CLASS, 'novel-chapter-comment-delete-confirm');
        assert.strictEqual(CANCEL_BTN_CLASS, 'novel-chapter-comment-delete-cancel');
        assert.strictEqual(typeof initReaderChapterCommentDelete, 'function');
        assert.strictEqual(typeof destroy, 'function');
        assert.strictEqual(typeof resetDeleteState, 'function');
        assert.strictEqual(typeof openDeleteConfirmation, 'function');
        assert.strictEqual(typeof closeDeleteConfirmation, 'function');
        assert.strictEqual(typeof handleConfirmDelete, 'function');
        assert.strictEqual(typeof getCurrentMutationToken, 'function');
    });

    test('2. Ignores Delete clicks originating outside #novelChapterComments (e.g., Drawer)', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        const drawer = fixture.doc.createElement('div');
        drawer.setAttribute('id', 'novelBlockDiscussionDrawer');
        const drawerDeleteBtn = fixture.doc.createElement('button');
        drawerDeleteBtn.className = 'novel-comment-delete-btn';
        drawerDeleteBtn.setAttribute('data-action', 'delete');
        drawerDeleteBtn.setAttribute('data-comment-id', 'drawer-c1');
        drawer.appendChild(drawerDeleteBtn);
        fixture.doc.body.appendChild(drawer);

        let defaultPrevented = false;
        fixture.doc.dispatchEvent({
            type: 'click',
            target: drawerDeleteBtn,
            preventDefault: () => { defaultPrevented = true; }
        });

        assert.strictEqual(getActiveConfirmationEl(), null, 'Confirmation must NOT open for Drawer button');
        assert.strictEqual(defaultPrevented, false, 'Should not prevent default for outside button');
    });

    test('3. Root comment confirmation copy: includes thread-hide warning', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });

        const confirmation = getActiveConfirmationEl();
        assert.ok(confirmation);
        const msgEl = confirmation.querySelector('.' + CONFIRMATION_MSG_CLASS);
        assert.ok(msgEl);
        assert.strictEqual(msgEl.textContent, 'Xóa bình luận này? Toàn bộ cuộc thảo luận này sẽ không còn hiển thị.');
        assert.strictEqual(fixture.rootActions1.hidden, true);
    });

    test('4. Reply comment confirmation copy: standard short warning', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.repDeleteBtn1, preventDefault: () => {} });

        const confirmation = getActiveConfirmationEl();
        assert.ok(confirmation);
        const msgEl = confirmation.querySelector('.' + CONFIRMATION_MSG_CLASS);
        assert.ok(msgEl);
        assert.strictEqual(msgEl.textContent, 'Xóa bình luận này?');
        assert.strictEqual(fixture.replyActions1.hidden, true);
    });

    test('5. Mutual exclusion: opening Delete closes active Reply composer', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        assert.strictEqual(closeReplyComposerCalls, 1);
    });

    test('6. Mutual exclusion: opening Delete closes active Edit composer', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            editComposerModule: mockEditComposer
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        assert.strictEqual(closeEditComposerCalls, 1);
    });

    test('7. Single confirmation discipline: opening Delete on B closes Delete on A and restores A actions', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_1);
        assert.strictEqual(fixture.rootActions1.hidden, true);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn2, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2);
        assert.strictEqual(fixture.rootActions1.hidden, false, 'A actions must be unhidden');
        assert.strictEqual(fixture.rootActions2.hidden, true, 'B actions must be hidden');
    });

    test('8. Cancel button closes confirmation, restores actions, and restores focus', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const confirmation = getActiveConfirmationEl();
        const cancelBtn = confirmation.querySelector('.' + CANCEL_BTN_CLASS);

        fixture.doc.dispatchEvent({ type: 'click', target: cancelBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(fixture.rootActions1.hidden, false);
        assert.strictEqual(fixture.rootDeleteBtn1.isFocused, true);
    });

    test('9. Escape key closes active confirmation and restores focus', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        fixture.doc.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {} });

        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(fixture.rootActions1.hidden, false);
        assert.strictEqual(fixture.rootDeleteBtn1.isFocused, true);
    });

    test('10. Confirmed Reply Delete calls shared deleteComment with exact inputs and refreshes root thread', async () => {
        let capturedInput = null;
        let directFetchCalled = false;

        mockMutations.deleteComment = async (input) => {
            capturedInput = input;
            return { ok: true, status: 204 };
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            fetchFn: async () => { directFetchCalled = true; }
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.repDeleteBtn1, preventDefault: () => {} });
        const confirmation = getActiveConfirmationEl();
        const confirmBtn = confirmation.querySelector('.' + CONFIRM_BTN_CLASS);

        await handleConfirmDelete();

        assert.ok(capturedInput);
        assert.strictEqual(capturedInput.chapterId, CHAPTER_ID);
        assert.strictEqual(capturedInput.commentId, REPLY_ID_1);
        assert.strictEqual(directFetchCalled, false, 'Must not use direct fetch');
        assert.strictEqual(refreshRootThreadCalls.length, 1);
        assert.strictEqual(refreshRootThreadCalls[0], ROOT_ID_1);
        assert.strictEqual(refreshFromPageZeroCalls, 0, 'Must NOT refresh page 0 for reply delete');
        assert.strictEqual(getActiveConfirmationEl(), null);
    });

    test('11. Confirmed Root Delete calls shared deleteComment and refreshes from page 0 without local card removal', async () => {
        let capturedInput = null;
        mockMutations.deleteComment = async (input) => {
            capturedInput = input;
            return { ok: true, status: 204 };
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        await handleConfirmDelete();

        assert.ok(capturedInput);
        assert.strictEqual(capturedInput.chapterId, CHAPTER_ID);
        assert.strictEqual(capturedInput.commentId, ROOT_ID_1);
        assert.strictEqual(refreshFromPageZeroCalls, 1, 'Must invoke refreshFromPageZero for root delete');
        assert.strictEqual(refreshRootThreadCalls.length, 0);
        // Card was NOT removed optimistically
        assert.strictEqual(fixture.list.childNodes.includes(fixture.thread1), true);
        assert.strictEqual(getActiveConfirmationEl(), null);
    });

    test('12. Refresh failure after successful DELETE displays section warning and does not retry DELETE', async () => {
        let deleteCallCount = 0;
        mockMutations.deleteComment = async () => {
            deleteCallCount++;
            return { ok: true, status: 204 };
        };
        mockCommentsModule.refreshFromPageZero = async () => {
            throw new Error('Network error during page 0 refresh');
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        await handleConfirmDelete();

        assert.strictEqual(deleteCallCount, 1, 'DELETE must NOT be retried');
        assert.strictEqual(getActiveConfirmationEl(), null);
        const sectionStatus = fixture.doc.getElementById(STATUS_ID);
        assert.strictEqual(sectionStatus.textContent, 'Bình luận đã được xóa, nhưng chưa thể tải lại danh sách.');
        assert.ok(sectionStatus.classList.contains('is-error'));
    });

    test('13. Single-flight: double submit is blocked while request is pending', async () => {
        let resolveDelete;
        let callCount = 0;
        mockMutations.deleteComment = () => {
            callCount++;
            return new Promise((res) => { resolveDelete = res; });
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const confirmation = getActiveConfirmationEl();
        const confirmBtn = confirmation.querySelector('.' + CONFIRM_BTN_CLASS);
        const cancelBtn = confirmation.querySelector('.' + CANCEL_BTN_CLASS);

        const promise1 = handleConfirmDelete();
        const promise2 = handleConfirmDelete();

        assert.strictEqual(callCount, 1, 'Only one deleteComment request should be issued');
        assert.strictEqual(confirmBtn.disabled, true);
        assert.strictEqual(cancelBtn.disabled, true);

        resolveDelete({ ok: true, status: 204 });
        await promise1;
        await promise2;
    });

    test('14. Error handling: 401/403, 404, network error display friendly messages and keep confirmation open', async () => {
        let statusToReturn = 403;
        mockMutations.deleteComment = async () => ({ ok: false, status: statusToReturn });

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const confirmation = getActiveConfirmationEl();
        const statusEl = confirmation.querySelector('.' + CONFIRMATION_STATUS_CLASS);
        const confirmBtn = confirmation.querySelector('.' + CONFIRM_BTN_CLASS);

        // 401/403
        await handleConfirmDelete();
        assert.strictEqual(statusEl.textContent, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền xóa bình luận này.');
        assert.strictEqual(confirmBtn.disabled, false);
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        // 404
        statusToReturn = 404;
        await handleConfirmDelete();
        assert.strictEqual(statusEl.textContent, 'Bình luận không còn tồn tại.');

        // Network error / throw
        mockMutations.deleteComment = async () => { throw new Error('Connection refused'); };
        await handleConfirmDelete();
        assert.strictEqual(statusEl.textContent, 'Không thể xóa bình luận. Vui lòng thử lại.');
    });

    test('15. Stale A -> B race: stale response from A cannot close or refresh B confirmation', async () => {
        let resolveA;
        mockMutations.deleteComment = async (input) => {
            if (input.commentId === ROOT_ID_1) {
                return new Promise((res) => { resolveA = res; });
            }
            return { ok: true, status: 204 };
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open A & confirm (pending)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const pendingPromiseA = handleConfirmDelete();

        // 2. User switches to B while A is pending
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn2, preventDefault: () => {} });
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2);

        // 3. Now A resolves
        resolveA({ ok: true, status: 204 });
        await pendingPromiseA;

        // B confirmation must still be active!
        assert.notStrictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2);
        assert.strictEqual(refreshFromPageZeroCalls, 0, 'A refresh must not run');
    });

    test('16. Exact ABA race: old A response cannot close or refresh new confirmation on A', async () => {
        let resolveOldA;
        mockMutations.deleteComment = async () => {
            return new Promise((res) => { resolveOldA = res; });
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open A and confirm
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const promiseOldA = handleConfirmDelete();

        // 2. Switch to B
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn2, preventDefault: () => {} });

        // 3. Switch back to new A
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });

        // 4. Old A finishes
        resolveOldA({ ok: true, status: 204 });
        await promiseOldA;

        // New A confirmation must remain open and unaffected
        assert.notStrictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_1);
        assert.strictEqual(refreshFromPageZeroCalls, 0);
    });

    test('17. Lifecycle events: chapter-changed and feed-replacing clear confirmation and invalidate token', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });
        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(fixture.rootActions1.hidden, false);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });
        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(fixture.rootActions1.hidden, false);
    });

    test('18. UNANCHORED comment delete works identically', async () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // Root 2 is unanchored
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn2, preventDefault: () => {} });
        const confirmation = getActiveConfirmationEl();
        assert.ok(confirmation);

        await handleConfirmDelete();
        assert.strictEqual(refreshFromPageZeroCalls, 1);
    });

    test('19 (Lifecycle A). init -> destroy -> init same document: one Delete click processes exactly one Delete interaction without handler duplication', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer,
            editComposerModule: mockEditComposer
        });

        // Destroy previous binding
        destroy();

        // Re-init on the same document
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer,
            editComposerModule: mockEditComposer
        });

        // Click Delete on Root 1
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });

        // Exactly one confirmation mounted
        assert.notStrictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_1);

        // Side effect counts prove handler was executed exactly ONCE (not duplicated)
        assert.strictEqual(closeReplyComposerCalls, 1, 'closeReplyComposer must be called exactly once');
        assert.strictEqual(closeEditComposerCalls, 1, 'closeEditComposer must be called exactly once');
    });

    test('20 (Lifecycle B). Delete A pending -> destroy -> re-init same doc -> open new Delete -> resolve old A does not affect new lifecycle', async () => {
        let resolveOldA;
        mockMutations.deleteComment = async () => {
            return new Promise((res) => { resolveOldA = res; });
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open Delete on Root 1 and submit (pending)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const oldPromiseA = handleConfirmDelete();
        assert.strictEqual(isDeletingComment(), true);

        // 2. Destroy and re-init same document
        destroy();
        assert.strictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(isDeletingComment(), false);

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 3. Open new Delete on Root 2
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn2, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null);
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2);

        // 4. Resolve OLD in-flight request A
        resolveOldA({ ok: true, status: 204 });
        await oldPromiseA;

        // 5. Assert old completion had zero effect on new lifecycle
        assert.notStrictEqual(getActiveConfirmationEl(), null, 'New confirmation must still be open');
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2, 'Target must remain Root 2');
        assert.strictEqual(refreshFromPageZeroCalls, 0, 'Must NOT invoke refreshFromPageZero for old A');
        assert.strictEqual(refreshRootThreadCalls.length, 0, 'Must NOT invoke refreshRootThread for old A');
        const statusEl = getActiveConfirmationEl().querySelector('.' + CONFIRMATION_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, '', 'Status must not be altered by old completion');
    });

    test('21 (Lifecycle C). Token remains monotonic across destroy and re-init, never resets to 0', () => {
        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        const token1 = getCurrentMutationToken();
        destroy();
        const token2 = getCurrentMutationToken();
        assert.ok(token2 > token1, `destroy must advance mutation token monotonically (was ${token1}, now ${token2})`);

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });
        const token3 = getCurrentMutationToken();
        assert.ok(token3 >= token2, `re-init must preserve monotonic mutation token (was ${token2}, now ${token3})`);
        assert.ok(token3 > 0, 'Token must never reset to 0');
    });

    test('22 (Refresh Lifecycle Guard). Old refreshFromPageZero rejection after destroy/re-init does not display stale section warning', async () => {
        let deleteCallCount = 0;
        mockMutations.deleteComment = async () => {
            deleteCallCount++;
            return { ok: true, status: 204 };
        };

        let rejectOldRefresh;
        let refreshStarted = false;
        mockCommentsModule.refreshFromPageZero = async () => {
            refreshStarted = true;
            return new Promise((_, rej) => {
                rejectOldRefresh = rej;
            });
        };

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open Delete on Root 1 and confirm
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn1, preventDefault: () => {} });
        const deletePromise = handleConfirmDelete();

        // Let microtasks run so deleteComment resolves and refreshFromPageZero starts
        await new Promise((r) => setTimeout(r, 10));
        assert.strictEqual(deleteCallCount, 1, 'deleteComment must be called');
        assert.strictEqual(refreshStarted, true, 'refreshFromPageZero must have started');
        assert.strictEqual(getActiveConfirmationEl(), null, 'Confirmation closes upon successful deleteComment');

        // 2. Lifecycle change: destroy and re-init on same document
        destroy();

        initReaderChapterCommentDelete(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 3. Open new Delete on Root 2 in new lifecycle
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootDeleteBtn2, preventDefault: () => {} });
        assert.notStrictEqual(getActiveConfirmationEl(), null, 'New confirmation must open on Root 2');
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2);

        // 4. Reject the OLD refresh
        rejectOldRefresh(new Error('Network error during old page-0 refresh'));
        try {
            await deletePromise;
        } catch (_) {}

        // Let microtasks settle
        await new Promise((r) => setTimeout(r, 10));

        // 5. Assert:
        // - no old section warning on #novelChapterCommentsStatus
        const sectionStatus = fixture.doc.getElementById(STATUS_ID);
        assert.strictEqual(sectionStatus.textContent, '', 'Stale refresh error must NOT be written to section status');
        assert.strictEqual(sectionStatus.classList.contains('is-error'), false);

        // - new confirmation/lifecycle remains intact
        assert.notStrictEqual(getActiveConfirmationEl(), null, 'Root 2 confirmation must remain open');
        assert.strictEqual(getActiveDeleteTarget().commentId, ROOT_ID_2);

        // - DELETE called exactly once
        assert.strictEqual(deleteCallCount, 1, 'DELETE must NOT be retried');
    });
});
