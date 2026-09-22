const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const editComposerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-edit-composer.js'));
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

const {
    SECTION_ID,
    STATUS_ID,
    EDIT_COMPOSER_CLASS,
    EDIT_INPUT_CLASS,
    EDIT_STATUS_CLASS,
    EDIT_ACTIONS_CLASS,
    EDIT_SUBMIT_CLASS,
    EDIT_CANCEL_CLASS,
    initReaderChapterCommentEditComposer,
    destroy,
    resetEditComposerState,
    openEditComposer,
    closeEditComposer,
    handleSubmit,
    extractCommentBody,
    getActiveEditTarget,
    getActiveComposerEl,
    isSubmittingEdit,
    getCurrentMutationToken,
    restoreActiveInlineComposer,
    flushActiveDraft,
    setDraftAdapterImplementation,
    setDraftStoreImplementation,
    getKeyEditGeneration,
    getAcceptedEditGeneration,
    isDirty,
    getActiveOriginalBody,
    EVENT_CHAPTER_CHANGED,
    EVENT_FEED_REPLACING,
    EVENT_FEED_RENDERED,
    EVENT_FLUSH_DRAFTS
} = editComposerModule;

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

const CHAPTER_ID = 'chap-bottom-1';
const ROOT_ID_1 = 'root-1';
const ROOT_ID_2 = 'root-2';
const REPLY_ID_1 = 'rep-1';

function createBottomFixture() {
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
    const rootEditBtn1 = doc.createElement('button');
    rootEditBtn1.className = 'novel-comment-menu-item novel-comment-edit-btn';
    rootEditBtn1.setAttribute('data-action', 'edit');
    rootEditBtn1.setAttribute('data-comment-id', ROOT_ID_1);
    rootEditBtn1.setAttribute('data-root-id', ROOT_ID_1);
    rootEditBtn1.textContent = 'Chỉnh sửa';
    popover1.appendChild(rootEditBtn1);
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
    const repEditBtn1 = doc.createElement('button');
    repEditBtn1.className = 'novel-comment-menu-item novel-comment-edit-btn';
    repEditBtn1.setAttribute('data-action', 'edit');
    repEditBtn1.setAttribute('data-comment-id', REPLY_ID_1);
    repEditBtn1.setAttribute('data-root-id', ROOT_ID_1);
    repEditBtn1.textContent = 'Chỉnh sửa';
    repPopover1.appendChild(repEditBtn1);
    repMenu1.appendChild(repPopover1);
    replyHeader1.appendChild(repMenu1);
    reply1.appendChild(replyHeader1);

    const replyBody1 = doc.createElement('div');
    replyBody1.className = 'novel-comment-body';
    const mentionSpan = doc.createElement('span');
    mentionSpan.className = 'novel-comment-reply-mention';
    mentionSpan.textContent = '@RootAuthor';
    const bodyTextSpan = doc.createElement('span');
    bodyTextSpan.className = 'novel-comment-reply-body-text';
    bodyTextSpan.textContent = 'Reply 1 clean body';
    replyBody1.appendChild(mentionSpan);
    replyBody1.appendChild(bodyTextSpan);
    reply1.appendChild(replyBody1);

    repliesContainer.appendChild(reply1);
    thread1.appendChild(repliesContainer);
    list.appendChild(thread1);

    // Thread 2
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
    const rootEditBtn2 = doc.createElement('button');
    rootEditBtn2.className = 'novel-comment-menu-item novel-comment-edit-btn';
    rootEditBtn2.setAttribute('data-action', 'edit');
    rootEditBtn2.setAttribute('data-comment-id', ROOT_ID_2);
    rootEditBtn2.setAttribute('data-root-id', ROOT_ID_2);
    rootEditBtn2.textContent = 'Chỉnh sửa';
    popover2.appendChild(rootEditBtn2);
    menu2.appendChild(popover2);
    rootHeader2.appendChild(menu2);
    rootComment2.appendChild(rootHeader2);

    const rootBody2 = doc.createElement('div');
    rootBody2.className = 'novel-comment-body';
    rootBody2.textContent = 'Root 2 original body';
    rootComment2.appendChild(rootBody2);

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
        rootEditBtn1,
        rootBody1,
        reply1,
        repEditBtn1,
        replyBody1,
        thread2,
        rootComment2,
        rootEditBtn2,
        rootBody2
    };
}

// ============================================================================
// Test Suite: reader-chapter-comment-edit-composer.test.js
// ============================================================================

describe('Reader Chapter Comment Edit Composer UI (MS-05E5H2F3B)', () => {
    let fixture;
    let mockMutations;
    let mockCommentsModule;
    let mockReplyComposer;
    let mockDeleteModule;
    let refreshRootThreadCalls;
    let closeReplyComposerCalls;
    let closeDeleteConfirmationCalls;

    beforeEach(() => {
        resetEditComposerState();
        fixture = createBottomFixture();
        refreshRootThreadCalls = [];
        closeReplyComposerCalls = 0;
        closeDeleteConfirmationCalls = 0;

        mockMutations = {
            editComment: async (input, options) => {
                return { ok: true, status: 204 };
            }
        };

        mockCommentsModule = {
            refreshRootThread: async (rootId) => {
                refreshRootThreadCalls.push(rootId);
            }
        };

        mockReplyComposer = {
            closeActiveComposer: (restoreFocus) => {
                closeReplyComposerCalls++;
            }
        };

        mockDeleteModule = {
            closeDeleteConfirmation: (restoreFocus) => {
                closeDeleteConfirmationCalls++;
            }
        };
    });

    test('1. Exports all required constants and lifecycle functions', () => {
        assert.strictEqual(SECTION_ID, 'novelChapterComments');
        assert.strictEqual(STATUS_ID, 'novelChapterCommentsStatus');
        assert.strictEqual(EDIT_COMPOSER_CLASS, 'novel-chapter-comment-edit-composer');
        assert.strictEqual(EDIT_INPUT_CLASS, 'novel-chapter-comment-edit-input');
        assert.strictEqual(EDIT_STATUS_CLASS, 'novel-chapter-comment-edit-status');
        assert.strictEqual(EDIT_ACTIONS_CLASS, 'novel-chapter-comment-edit-actions');
        assert.strictEqual(EDIT_SUBMIT_CLASS, 'novel-chapter-comment-edit-submit');
        assert.strictEqual(EDIT_CANCEL_CLASS, 'novel-chapter-comment-edit-cancel');
        assert.strictEqual(typeof initReaderChapterCommentEditComposer, 'function');
        assert.strictEqual(typeof destroy, 'function');
        assert.strictEqual(typeof resetEditComposerState, 'function');
        assert.strictEqual(typeof openEditComposer, 'function');
        assert.strictEqual(typeof closeEditComposer, 'function');
        assert.strictEqual(typeof handleSubmit, 'function');
        assert.strictEqual(typeof getCurrentMutationToken, 'function');
    });

    test('2. Init is idempotent: multiple calls do not duplicate listeners', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });
        // Click edit button
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);
    });

    test('3. Ignores Edit clicks originating outside #novelChapterComments (e.g., Drawer)', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        const drawer = fixture.doc.createElement('div');
        drawer.setAttribute('id', 'novelBlockDiscussionDrawer');
        const drawerEditBtn = fixture.doc.createElement('button');
        drawerEditBtn.className = 'novel-comment-edit-btn';
        drawerEditBtn.setAttribute('data-action', 'edit');
        drawerEditBtn.setAttribute('data-comment-id', 'drawer-c1');
        drawer.appendChild(drawerEditBtn);
        fixture.doc.body.appendChild(drawer);

        let defaultPrevented = false;
        fixture.doc.dispatchEvent({
            type: 'click',
            target: drawerEditBtn,
            preventDefault: () => { defaultPrevented = true; }
        });

        assert.strictEqual(getActiveComposerEl(), null, 'Composer must NOT open for Drawer button');
        assert.strictEqual(defaultPrevented, false, 'Should not prevent default for outside button');
    });

    test('4. Clean body prefill for root comment: prefill textarea and hide original body', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        assert.ok(composer);
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.ok(textarea);
        assert.strictEqual(textarea.value, 'Root 1 original body');
        assert.strictEqual(textarea.maxLength, 2000, 'Edit composer textarea must have maxLength=2000');
        assert.strictEqual(fixture.rootBody1.hidden, true);
        assert.strictEqual(fixture.rootBody1.style.display, 'none');
    });

    test('5. Clean body prefill for nested reply: strips Wattpad @mention', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.repEditBtn1, preventDefault: () => {} });

        const composer = getActiveComposerEl();
        assert.ok(composer);
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.ok(textarea);
        assert.strictEqual(textarea.value, 'Reply 1 clean body', 'Textarea must not contain @RootAuthor');
        assert.strictEqual(fixture.replyBody1.hidden, true);
    });

    test('6. Single active composer: opening edit on B closes edit on A and restores A body', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // Open A
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);
        assert.strictEqual(fixture.rootBody1.hidden, true);

        // Open B
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);
        assert.strictEqual(fixture.rootBody1.hidden, false, 'A body must be restored');
        assert.strictEqual(fixture.rootBody2.hidden, true, 'B body must be hidden');
    });

    test('7. Mutual exclusion: opening Edit closes active Reply composer', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.strictEqual(closeReplyComposerCalls, 1);
    });

    test('8. Mutual exclusion: opening Edit closes active Delete confirmation', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            deleteModule: mockDeleteModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.strictEqual(closeDeleteConfirmationCalls, 1);
    });

    test('9. Cancel button closes edit composer, restores body, and restores focus', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        const cancelBtn = composer.querySelector('.' + EDIT_CANCEL_CLASS);

        fixture.doc.dispatchEvent({ type: 'click', target: cancelBtn, preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(fixture.rootBody1.hidden, false);
        assert.strictEqual(fixture.rootEditBtn1.isFocused, true);
    });

    test('10. Escape key closes active edit composer and restores focus', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        fixture.doc.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(fixture.rootBody1.hidden, false);
        assert.strictEqual(fixture.rootEditBtn1.isFocused, true);
    });

    test('11. Blank body validation displays error and prevents editComment call', async () => {
        let editCommentCalled = false;
        mockMutations.editComment = async () => { editCommentCalled = true; };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = '   ';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(editCommentCalled, false, 'Must not call editComment with blank body');
        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, 'Vui lòng nhập nội dung bình luận.');
        assert.ok(statusEl.classList.contains('is-error'));
    });

    test('12. Submit Edit calls shared editComment with exact inputs and no direct fetch', async () => {
        let capturedInput = null;
        let directFetchCalled = false;

        mockMutations.editComment = async (input) => {
            capturedInput = input;
            return { ok: true, status: 204 };
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            fetchFn: async () => { directFetchCalled = true; }
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Chỉnh sửa nội dung thành công';

        await handleSubmit({ preventDefault: () => {} });

        assert.ok(capturedInput);
        assert.strictEqual(capturedInput.chapterId, CHAPTER_ID);
        assert.strictEqual(capturedInput.commentId, ROOT_ID_1);
        assert.strictEqual(capturedInput.body, 'Chỉnh sửa nội dung thành công');
        assert.strictEqual(directFetchCalled, false, 'Must not use direct fetch outside shared mutations client');
        assert.strictEqual(refreshRootThreadCalls.length, 1);
        assert.strictEqual(refreshRootThreadCalls[0], ROOT_ID_1);
        assert.strictEqual(getActiveComposerEl(), null, 'Composer must close on success');
    });

    test('13. No optimistic body mutation: original body unchanged on DOM until refreshRootThread', async () => {
        mockMutations.editComment = async () => ({ ok: true, status: 204 });

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'New unconfirmed text';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(fixture.rootBody1.textContent, 'Root 1 original body', 'No optimistic mutation');
    });

    test('14. Refresh failure after successful PATCH displays section warning and does not retry PATCH', async () => {
        let editCommentCallCount = 0;
        mockMutations.editComment = async () => {
            editCommentCallCount++;
            return { ok: true, status: 204 };
        };
        mockCommentsModule.refreshRootThread = async () => {
            throw new Error('Network error during refresh');
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(editCommentCallCount, 1, 'editComment must NOT be retried');
        assert.strictEqual(getActiveComposerEl(), null, 'Composer closes on successful PATCH');
        const sectionStatus = fixture.doc.getElementById(STATUS_ID);
        assert.strictEqual(sectionStatus.textContent, 'Bình luận đã được chỉnh sửa, nhưng chưa thể tải lại thảo luận.');
        assert.ok(sectionStatus.classList.contains('is-error'));
    });

    test('15. Single-flight: double submit is blocked while request is pending', async () => {
        let resolveEdit;
        let callCount = 0;
        mockMutations.editComment = () => {
            callCount++;
            return new Promise((res) => { resolveEdit = res; });
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        const submitBtn = composer.querySelector('.' + EDIT_SUBMIT_CLASS);

        const promise1 = handleSubmit({ preventDefault: () => {} });
        const promise2 = handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(callCount, 1, 'Only one editComment request should be issued');
        assert.strictEqual(textarea.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);

        resolveEdit({ ok: true, status: 204 });
        await promise1;
        await promise2;
    });

    test('16. Error handling: 400, 401/403, 404, network error display friendly messages and preserve draft', async () => {
        let statusToReturn = 400;
        mockMutations.editComment = async () => ({ ok: false, status: statusToReturn });

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft text';
        const statusEl = composer.querySelector('.' + EDIT_STATUS_CLASS);

        // 400
        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(statusEl.textContent, 'Nội dung chỉnh sửa không hợp lệ.');
        assert.strictEqual(textarea.value, 'Draft text');
        assert.strictEqual(textarea.disabled, false);

        // 401/403
        statusToReturn = 403;
        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(statusEl.textContent, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.');

        // 404
        statusToReturn = 404;
        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(statusEl.textContent, 'Bình luận không còn tồn tại.');

        // Network error / throw
        mockMutations.editComment = async () => { throw new Error('Connection refused'); };
        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(statusEl.textContent, 'Không thể lưu chỉnh sửa. Vui lòng thử lại.');
    });

    test('17. Stale A -> B race: stale response from A cannot close or modify B editor', async () => {
        let resolveA;
        mockMutations.editComment = async (input) => {
            if (input.commentId === ROOT_ID_1) {
                return new Promise((res) => { resolveA = res; });
            }
            return { ok: true, status: 204 };
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open A & submit (pending)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const pendingPromiseA = handleSubmit({ preventDefault: () => {} });

        // 2. User switches to B while A is pending
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);

        // 3. Now A resolves
        resolveA({ ok: true, status: 204 });
        await pendingPromiseA;

        // B must still be active!
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);
        assert.strictEqual(refreshRootThreadCalls.includes(ROOT_ID_1), false, 'A refresh must not have run');
    });

    test('18. Exact ABA race: old A response cannot close or refresh returning edit on A', async () => {
        let resolveOldA;
        mockMutations.editComment = async () => {
            return new Promise((res) => { resolveOldA = res; });
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open A and submit
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const promiseOldA = handleSubmit({ preventDefault: () => {} });

        // 2. Switch to B
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });

        // 3. Switch back to new A
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        // 4. Old A finishes
        resolveOldA({ ok: true, status: 204 });
        await promiseOldA;

        // New A must remain open and unaffected
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);
        assert.strictEqual(refreshRootThreadCalls.length, 0);
    });

    test('19. Lifecycle events: chapter-changed and feed-replacing clear composer and invalidate token', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-changed' });
        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(fixture.rootBody1.hidden, false);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });
        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(fixture.rootBody1.hidden, false);
    });

    test('20. UNANCHORED comment edit works identically', async () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // Root 2 is unanchored (has rootCommentId but no blockKey)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        assert.ok(composer);

        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Root 2 original body');

        await handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(refreshRootThreadCalls[0], ROOT_ID_2);
    });

    test('21 (Lifecycle A). init -> destroy -> init same document: one Edit click processes exactly one Edit interaction without handler duplication', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer,
            deleteModule: mockDeleteModule
        });

        // Destroy previous binding
        destroy();

        // Re-init on the same document
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer,
            deleteModule: mockDeleteModule
        });

        // Click Edit on Root 1
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        // Exactly one composer mounted
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);

        // Side effect counts prove handler was executed exactly ONCE (not twice)
        assert.strictEqual(closeReplyComposerCalls, 1, 'closeReplyComposer must be called exactly once');
        assert.strictEqual(closeDeleteConfirmationCalls, 1, 'closeDeleteConfirmation must be called exactly once');
    });

    test('22 (Lifecycle B). Edit A submit pending -> destroy -> re-init same doc -> open Edit B -> resolve old A does not affect new lifecycle', async () => {
        let resolveOldA;
        mockMutations.editComment = async () => {
            return new Promise((res) => { resolveOldA = res; });
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open Edit on Root 1 and submit (pending)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const oldPromiseA = handleSubmit({ preventDefault: () => {} });
        assert.strictEqual(isSubmittingEdit(), true);

        // 2. Destroy and re-init same document
        destroy();
        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(isSubmittingEdit(), false);

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 3. Open Edit on Root 2 and write new draft
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'New Root 2 draft in progress';

        // 4. Resolve OLD in-flight request A
        resolveOldA({ ok: true, status: 204 });
        await oldPromiseA;

        // 5. Assert old completion had zero effect on new lifecycle
        assert.notStrictEqual(getActiveComposerEl(), null, 'New composer must still be open');
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2, 'Target must remain Root 2');
        assert.strictEqual(textarea.value, 'New Root 2 draft in progress', 'New draft must remain intact');
        assert.strictEqual(refreshRootThreadCalls.length, 0, 'Must NOT invoke refreshRootThread for old A');
        const statusEl = getActiveComposerEl().querySelector('.' + EDIT_STATUS_CLASS);
        assert.strictEqual(statusEl.textContent, '', 'Status must not be altered by old completion');
    });

    test('23 (Lifecycle C). Token remains monotonic across destroy and re-init, never resets to 0', () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        const token1 = getCurrentMutationToken();
        destroy();
        const token2 = getCurrentMutationToken();
        assert.ok(token2 > token1, `destroy must advance mutation token monotonically (was ${token1}, now ${token2})`);

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });
        const token3 = getCurrentMutationToken();
        assert.ok(token3 >= token2, `re-init must preserve monotonic mutation token (was ${token2}, now ${token3})`);
        assert.ok(token3 > 0, 'Token must never reset to 0');
    });

    test('24 (Refresh Lifecycle Guard). Old refreshRootThread rejection after destroy/re-init does not display stale section warning', async () => {
        let editCommentCallCount = 0;
        mockMutations.editComment = async () => {
            editCommentCallCount++;
            return { ok: true, status: 204 };
        };

        let rejectOldRefresh;
        let refreshStarted = false;
        mockCommentsModule.refreshRootThread = async (rootId) => {
            refreshStarted = true;
            return new Promise((_, rej) => {
                rejectOldRefresh = rej;
            });
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 1. Open Edit on Root 1 and submit
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const editPromise = handleSubmit({ preventDefault: () => {} });

        // Let microtasks run so editComment resolves and refreshRootThread starts
        await new Promise((r) => setTimeout(r, 10));
        assert.strictEqual(editCommentCallCount, 1, 'editComment must be called');
        assert.strictEqual(refreshStarted, true, 'refreshRootThread must have started');
        assert.strictEqual(getActiveComposerEl(), null, 'Composer must close upon successful editComment');

        // 2. Lifecycle change: destroy and re-init on same document
        destroy();

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule
        });

        // 3. Open Edit on Root 2 in new lifecycle
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        assert.notStrictEqual(getActiveComposerEl(), null);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'New Root 2 draft';

        // 4. Reject the OLD refresh
        rejectOldRefresh(new Error('Network error during old refresh'));
        try {
            await editPromise;
        } catch (_) {}

        // Let microtasks settle
        await new Promise((r) => setTimeout(r, 10));

        // 5. Assert:
        // - no old section warning on #novelChapterCommentsStatus
        const sectionStatus = fixture.doc.getElementById(STATUS_ID);
        assert.strictEqual(sectionStatus.textContent, '', 'Stale refresh error must NOT be written to section status');
        assert.strictEqual(sectionStatus.classList.contains('is-error'), false);

        // - new lifecycle remains intact
        assert.notStrictEqual(getActiveComposerEl(), null, 'Root 2 editor must remain open');
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);
        assert.strictEqual(textarea.value, 'New Root 2 draft');

        // - no second PATCH
        assert.strictEqual(editCommentCallCount, 1, 'editComment must NOT be retried');
    });
});

describe('MS-05E5H2F4B1 — Bottom Edit Cross-Surface Synchronization', () => {
    let fixture;
    let mockMutations;
    let mockCommentsModule;
    let mockDrawerModule;
    let mockIndicatorsModule;
    let drawerRefreshCalls;
    let indicatorRefreshCalls;
    let refreshRootThreadCalls;
    let drawerOpen;
    let drawerContext;

    beforeEach(() => {
        resetEditComposerState();
        fixture = createBottomFixture();
        refreshRootThreadCalls = [];
        drawerRefreshCalls = 0;
        indicatorRefreshCalls = 0;
        drawerOpen = true;
        drawerContext = { chapterId: CHAPTER_ID, blockKey: 'blk-root-1' };

        mockMutations = {
            editComment: async () => ({ ok: true, status: 204 })
        };

        mockCommentsModule = {
            getState: () => ({
                items: [
                    {
                        id: ROOT_ID_1,
                        rootCommentId: ROOT_ID_1,
                        anchorStatus: 'CURRENT',
                        blockKey: 'blk-root-1'
                    },
                    {
                        id: ROOT_ID_2,
                        rootCommentId: ROOT_ID_2,
                        anchorStatus: 'UNANCHORED',
                        blockKey: null
                    }
                ]
            }),
            refreshRootThread: async (rootId) => {
                refreshRootThreadCalls.push(rootId);
            }
        };

        mockDrawerModule = {
            isDrawerOpen: () => drawerOpen,
            getActiveContext: () => drawerContext,
            refreshActiveDiscussion: async () => {
                drawerRefreshCalls++;
            }
        };

        mockIndicatorsModule = {
            refreshChapterIndicators: async () => {
                indicatorRefreshCalls++;
            }
        };
    });

    afterEach(() => {
        destroy();
    });

    test('Case A: Anchored same-block Drawer open refreshes Drawer once, local thread once, zero indicator refresh', async () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            drawerModule: mockDrawerModule
        });

        // Open edit on Root 1 (anchored to blk-root-1)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Updated root comment body';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(refreshRootThreadCalls.length, 1, 'Local thread must refresh');
        assert.strictEqual(refreshRootThreadCalls[0], ROOT_ID_1);
        assert.strictEqual(drawerRefreshCalls, 1, 'Same-block open Drawer must refresh once');
        assert.strictEqual(indicatorRefreshCalls, 0, 'Edit must NEVER refresh indicators (not count-changing)');
    });

    test('Case B: Anchored edit with different-block Drawer open skips Drawer refresh', async () => {
        drawerContext = { chapterId: CHAPTER_ID, blockKey: 'blk-different' };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            drawerModule: mockDrawerModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Updated root comment body';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(refreshRootThreadCalls.length, 1);
        assert.strictEqual(drawerRefreshCalls, 0, 'Drawer refresh must be skipped when blockKey differs');
    });

    test('Case C: UNANCHORED comment edit performs local refresh only (zero Drawer refresh)', async () => {
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            drawerModule: mockDrawerModule
        });

        // Open edit on Root 2 (UNANCHORED)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Updated unanchored body';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(refreshRootThreadCalls.length, 1);
        assert.strictEqual(refreshRootThreadCalls[0], ROOT_ID_2);
        assert.strictEqual(drawerRefreshCalls, 0, 'UNANCHORED comment must not trigger Drawer refresh');
    });

    test('Case D: Secondary Drawer rejection does not fail mutation or retry PATCH', async () => {
        let editCommentCalls = 0;
        mockMutations.editComment = async () => {
            editCommentCalls++;
            return { ok: true, status: 204 };
        };
        mockDrawerModule.refreshActiveDiscussion = () => Promise.reject(new Error('Drawer network error'));

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            drawerModule: mockDrawerModule
        });

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Updated root body';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(editCommentCalls, 1, 'PATCH must not be retried');
        assert.strictEqual(refreshRootThreadCalls.length, 1, 'Local refresh must still proceed');
        assert.strictEqual(getActiveComposerEl(), null, 'Composer must close');
    });

    test('Case E: Stale A completion performs zero Drawer refresh', async () => {
        let resolveEditA;
        mockMutations.editComment = () => new Promise(r => { resolveEditA = r; });

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            drawerModule: mockDrawerModule
        });

        // Start edit on Root 1
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const editPromiseA = handleSubmit({ preventDefault: () => {} });

        // Cancel A by pressing Escape
        fixture.doc.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {} });
        assert.strictEqual(getActiveComposerEl(), null);

        // Resolve old A
        resolveEditA({ ok: true, status: 204 });
        await editPromiseA;

        assert.strictEqual(drawerRefreshCalls, 0, 'Stale mutation must not trigger Drawer refresh');
        assert.strictEqual(refreshRootThreadCalls.length, 0, 'Stale mutation must not trigger local refresh');
    });
});

// ============================================================================
// Test Suite: UX-DRAFT-01D2B — Bottom Chapter Edit Draft Persistence
// ============================================================================

describe('UX-DRAFT-01D2B — Bottom Chapter Edit Draft Persistence', () => {
    let fixture;
    let mockDraftStore;
    let mockMutations;
    let mockCommentsModule;
    let mockReplyComposer;
    let mockDeleteModule;
    let refreshRootThreadCalls;

    beforeEach(() => {
        resetEditComposerState();
        fixture = createBottomFixture();
        mockDraftStore = createMockDraftStore();
        refreshRootThreadCalls = [];

        mockMutations = {
            editComment: async (input, options) => {
                return { ok: true, status: 204 };
            }
        };

        mockCommentsModule = {
            refreshRootThread: async (rootId) => {
                refreshRootThreadCalls.push(rootId);
            },
            getState: () => ({ items: [] })
        };

        mockReplyComposer = {
            closeActiveComposer: () => {},
            getActiveComposer: () => null,
            getState: () => ({ activeCommentId: null })
        };

        mockDeleteModule = {
            closeDeleteConfirmation: () => {},
            getActiveConfirmationEl: () => null,
            getActiveDeleteTarget: () => null,
            isDeletingComment: () => false,
            getState: () => ({ isDeleting: false })
        };

        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer,
            deleteModule: mockDeleteModule,
            draftAdapter: draftsAdapter,
            draftStore: mockDraftStore.store
        });
    });

    afterEach(() => {
        resetEditComposerState();
    });

    // ------------------------------------------------------------------------
    // Part 1: Dirty State Discipline (Tests 1-13)
    // ------------------------------------------------------------------------

    test('1. Opening edit composer loads server clean body, dirty is false, zero draft stored in EphemeralDraftStore', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const composer = getActiveComposerEl();
        assert.ok(composer);

        const textarea = composer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Root 1 original body');
        assert.strictEqual(isDirty(), false);
        assert.strictEqual(getActiveOriginalBody(), 'Root 1 original body');
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Untouched edit must not create draft');
    });

    test('2. Active inline marker { type: "edit", commentId } is saved upon opening', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        const rawMarker = mockDraftStore.store.load(markerKey);
        assert.ok(rawMarker);
        const parsed = JSON.parse(rawMarker);
        assert.strictEqual(parsed.type, 'edit');
        assert.strictEqual(parsed.commentId, ROOT_ID_1);
    });

    test('3. Typing changes sets dirty = true, bumps generation, debounces flush to store', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const initialGen = getKeyEditGeneration(draftKey);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        textarea.value = 'Modified draft text';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(isDirty(), true);
        assert.ok(getKeyEditGeneration(draftKey) > initialGen);

        // Before debounce fires: store is still empty
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);

        // Wait for 400ms debounce
        await new Promise(r => setTimeout(r, 450));
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Modified draft text');
    });

    test('4. Typing changes then reverting exactly back to clean body cancels debounce, sets dirty = false, immediately removes draft from store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        textarea.value = 'Modified draft text';
        textarea.dispatchEvent({ type: 'input' });
        flushActiveDraft();
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Modified draft text');

        // Revert to clean body
        textarea.value = 'Root 1 original body';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(isDirty(), false);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft must be immediately removed when clean');
    });

    test('5. Typing changes then deleting all text (empty or whitespace) sets dirty = false, removes draft from store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        textarea.value = 'Modified draft text';
        textarea.dispatchEvent({ type: 'input' });
        flushActiveDraft();
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Modified draft text');

        // Delete text to whitespace
        textarea.value = '   \n  ';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(isDirty(), false);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Whitespace-only draft must be removed');
    });

    test('6. flushActiveDraft() when clean (untouched server body) does not write draft to store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.strictEqual(isDirty(), false);

        flushActiveDraft();
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
    });

    test('7. flushActiveDraft() when dirty persists exact raw string (preserving whitespace, newlines)', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        const rawText = '  \nline 1\n  line 2\t\n  ';
        textarea.value = rawText;
        textarea.dispatchEvent({ type: 'input' });

        flushActiveDraft();
        assert.strictEqual(mockDraftStore.store.load(draftKey), rawText);
    });

    test('8. Opening composer when store has draft identical to server body removes redundant draft from store and marks clean', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        mockDraftStore.store.save(draftKey, 'Root 1 original body');

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        assert.strictEqual(isDirty(), false);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Redundant draft must be purged');
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Root 1 original body');
    });

    test('9. Opening composer when store has dirty draft pre-fills with saved draft and marks dirty = true', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        mockDraftStore.store.save(draftKey, 'Previously saved dirty draft');

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        assert.strictEqual(isDirty(), true);
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Previously saved dirty draft');
    });

    test('10. Nested reply clean body pre-fill (omitting @mention) serves as clean body baseline for dirty tracking', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, REPLY_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.repEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        assert.strictEqual(textarea.value, 'Reply 1 clean body');
        assert.strictEqual(getActiveOriginalBody(), 'Reply 1 clean body');
        assert.strictEqual(isDirty(), false);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
    });

    test('11. Generation tracking: each edit after clean bumps generation monotonically', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        textarea.value = 'Edit 1';
        textarea.dispatchEvent({ type: 'input' });
        const gen1 = getKeyEditGeneration(draftKey);
        assert.ok(gen1 > 0);

        textarea.value = 'Edit 2';
        textarea.dispatchEvent({ type: 'input' });
        const gen2 = getKeyEditGeneration(draftKey);
        assert.ok(gen2 > gen1);
    });

    test('12. getActiveOriginalBody() returns server clean body throughout editing session', () => {
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        textarea.value = 'Something completely different';
        textarea.dispatchEvent({ type: 'input' });

        assert.strictEqual(getActiveOriginalBody(), 'Root 1 original body');
    });

    test('13. Non-empty draft with trailing spaces is preserved exact in store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);

        textarea.value = 'Text with trailing spaces   ';
        textarea.dispatchEvent({ type: 'input' });
        flushActiveDraft();

        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Text with trailing spaces   ');
    });

    // ------------------------------------------------------------------------
    // Part 2: Discard vs. Preserve Discipline (Tests 14-23)
    // ------------------------------------------------------------------------

    test('14. Clicking "Hủy" (cancel button) discards draft, removes draft from store, removes matching marker from store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Dirty text before cancel';
        textarea.dispatchEvent({ type: 'input' });
        flushActiveDraft();

        assert.ok(mockDraftStore.store.load(draftKey));
        assert.ok(mockDraftStore.store.load(markerKey));

        const cancelBtn = getActiveComposerEl().querySelector('.' + EDIT_CANCEL_CLASS);
        cancelBtn.click();

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft must be discarded');
        assert.strictEqual(mockDraftStore.store.load(markerKey), null, 'Marker must be removed');
    });

    test('15. Pressing "Escape" discards draft, removes draft from store, removes matching marker from store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Dirty text before Escape';
        textarea.dispatchEvent({ type: 'input' });
        flushActiveDraft();

        fixture.doc.dispatchEvent({ type: 'keydown', key: 'Escape', preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft must be discarded');
        assert.strictEqual(mockDraftStore.store.load(markerKey), null, 'Marker must be removed');
    });

    test('16. Switching from Comment A to Comment B flushes A\'s dirty draft to store (preserved), and updates marker to B', () => {
        const draftKeyA = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        // Open A and type
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textareaA = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textareaA.value = 'Preserved A draft';
        textareaA.dispatchEvent({ type: 'input' });

        // Switch to B
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });

        // A draft must be preserved
        assert.strictEqual(mockDraftStore.store.load(draftKeyA), 'Preserved A draft');

        // Marker must point to B
        const markerRaw = mockDraftStore.store.load(markerKey);
        const markerParsed = JSON.parse(markerRaw);
        assert.strictEqual(markerParsed.type, 'edit');
        assert.strictEqual(markerParsed.commentId, ROOT_ID_2);
    });

    test('17. Switching from Comment A to Comment B when A was clean creates no draft for A in store', () => {
        const draftKeyA = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        // Open A (untouched)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });

        // Switch to B
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });

        assert.strictEqual(mockDraftStore.store.load(draftKeyA), null, 'Clean A must not save draft');
    });

    test('18. kiemlai:chapter-changed event flushes dirty draft to store (preserved), closes composer', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft before chapter changed';
        textarea.dispatchEvent({ type: 'input' });

        fixture.doc.dispatchEvent({ type: EVENT_CHAPTER_CHANGED });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft before chapter changed');
    });

    test('19. kiemlai:chapter-comments-feed-replacing flushes dirty draft to store, preserves marker, closes composer', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft before feed replacing';
        textarea.dispatchEvent({ type: 'input' });

        fixture.doc.dispatchEvent({ type: EVENT_FEED_REPLACING });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft before feed replacing');
        assert.ok(mockDraftStore.store.load(markerKey), 'Marker must be preserved for subsequent feed-rendered');
    });

    test('20. destroy() flushes dirty draft to store (preserved), cancels debounce', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft before destroy';
        textarea.dispatchEvent({ type: 'input' });

        destroy();

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft before destroy');
    });

    test('21. kiemlai:novel-comment-drafts-flush (pagehide bridge) immediately flushes dirty draft to store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft before pagehide flush';
        textarea.dispatchEvent({ type: 'input' });

        // Store is empty before debounce
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);

        fixture.doc.dispatchEvent({ type: EVENT_FLUSH_DRAFTS });

        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft before pagehide flush');
    });

    test('22. Mutual exclusion: opening Reply composer closes Edit composer preserving Edit\'s dirty draft in store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft preserved when reply opened';
        textarea.dispatchEvent({ type: 'input' });

        // Close via reply opening (discard: false)
        closeEditComposer(false);

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft preserved when reply opened');
    });

    test('23. Mutual exclusion: opening Delete confirmation closes Edit composer preserving Edit\'s dirty draft in store', () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft preserved when delete opened';
        textarea.dispatchEvent({ type: 'input' });

        // Close via delete opening (discard: false)
        closeEditComposer(false);

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft preserved when delete opened');
    });

    // ------------------------------------------------------------------------
    // Part 3: Automatic Inline Restore Foundation (Tests 24-38)
    // ------------------------------------------------------------------------

    test('24. restoreActiveInlineComposer() restores open edit composer if valid marker exists and feed is rendered', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        const composer = getActiveComposerEl();
        assert.ok(composer);
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);
    });

    test('25. restoreActiveInlineComposer() restores dirty draft value if draft is in store', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));
        mockDraftStore.store.save(draftKey, 'Restored dirty draft content');

        restoreActiveInlineComposer(fixture.doc);

        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Restored dirty draft content');
        assert.strictEqual(isDirty(), true);
    });

    test('26. restoreActiveInlineComposer() restores clean body if no draft in store', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Root 1 original body');
        assert.strictEqual(isDirty(), false);
    });

    test('27. restoreActiveInlineComposer() safely removes corrupted JSON marker and aborts restore', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, 'not-valid-json{{{');

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(markerKey), null, 'Corrupt marker must be removed');
    });

    test('28. restoreActiveInlineComposer() safely ignores reply marker ({ type: "reply" }) without deleting it', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        const replyMarkerJson = JSON.stringify({ type: 'reply', targetCommentId: ROOT_ID_1 });
        mockDraftStore.store.save(markerKey, replyMarkerJson);

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(markerKey), replyMarkerJson, 'Reply marker must not be touched');
    });

    test('29. restoreActiveInlineComposer() defers if Edit composer is already open', () => {
        // Open edit on Root 1
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);

        // Marker somehow points to Root 2
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_2 }));

        restoreActiveInlineComposer(fixture.doc);

        // Still Root 1, does not switch
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);
    });

    test('30. restoreActiveInlineComposer() defers if Bottom Reply composer is currently active', () => {
        mockReplyComposer.getActiveComposer = () => ({ parentNode: {} });

        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null);
        assert.ok(mockDraftStore.store.load(markerKey), 'Marker must be preserved for later restore');
    });

    test('31. restoreActiveInlineComposer() defers if Bottom Delete confirmation is currently active', () => {
        mockDeleteModule.getActiveConfirmationEl = () => ({ parentNode: {} });

        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null);
        assert.ok(mockDraftStore.store.load(markerKey), 'Marker must be preserved for later restore');
    });

    test('32. restoreActiveInlineComposer() aborts restore if target comment does not exist in DOM', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: 'non-existent-comment' }));

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null);
    });

    test('33. restoreActiveInlineComposer() aborts restore if target comment is a tombstone (.novel-comment--tombstone)', () => {
        fixture.rootComment1.classList.add('novel-comment--tombstone');

        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null);
    });

    test('34. restoreActiveInlineComposer() aborts restore if target comment has no Edit button (lacks edit permission)', () => {
        // Remove edit button
        fixture.rootEditBtn1.parentNode.removeChild(fixture.rootEditBtn1);

        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveComposerEl(), null, 'Must abort if edit button missing');
    });

    test('35. restoreActiveInlineComposer() derives authoritative rootId from enclosing live .novel-block-discussion-thread[data-root-id]', () => {
        // Alter edit button's data-root-id to stale value
        fixture.rootEditBtn1.setAttribute('data-root-id', 'stale-root-id');

        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        assert.strictEqual(getActiveEditTarget().rootId, ROOT_ID_1, 'Live enclosing thread data-root-id is authoritative');
        assert.strictEqual(fixture.rootEditBtn1.getAttribute('data-root-id'), ROOT_ID_1);
    });

    test('36. kiemlai:chapter-comments-feed-rendered triggers automatic restore for matching chapter', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        fixture.doc.dispatchEvent({
            type: EVENT_FEED_RENDERED,
            detail: { chapterId: CHAPTER_ID }
        });

        assert.ok(getActiveComposerEl());
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_1);
    });

    test('37. kiemlai:chapter-comments-feed-rendered ignores restore if event chapterId does not match current section', () => {
        const wrongChapterId = 'other-different-chapter';
        const wrongMarkerKey = draftsAdapter.getChapterActiveInlineMarkerKey(wrongChapterId);
        const wrongDraftKey = draftsAdapter.getChapterEditDraftKey(wrongChapterId, ROOT_ID_1);
        const wrongMarkerPayload = JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 });
        const wrongDraftPayload = 'Draft for other chapter';

        mockDraftStore.store.save(wrongMarkerKey, wrongMarkerPayload);
        mockDraftStore.store.save(wrongDraftKey, wrongDraftPayload);

        fixture.doc.dispatchEvent({
            type: EVENT_FEED_RENDERED,
            detail: { chapterId: wrongChapterId }
        });

        assert.strictEqual(getActiveComposerEl(), null, 'No composer opens for wrong chapter');
        assert.strictEqual(mockDraftStore.store.load(wrongMarkerKey), wrongMarkerPayload, 'Wrong-chapter marker remains unchanged');
        assert.strictEqual(mockDraftStore.store.load(wrongDraftKey), wrongDraftPayload, 'Wrong-chapter draft remains unchanged');
    });

    test('38. restoreActiveInlineComposer does not steal focus (isRestore: true)', () => {
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);
        mockDraftStore.store.save(markerKey, JSON.stringify({ type: 'edit', commentId: ROOT_ID_1 }));

        restoreActiveInlineComposer(fixture.doc);

        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.isFocused, false, 'Automatic restore must not steal focus');
    });

    // ------------------------------------------------------------------------
    // Part 4: Submission & Race Safety (Tests 39-48)
    // ------------------------------------------------------------------------

    test('39. Blank body validation displays error, prevents submission, does not persist blank draft', async () => {
        let editCalls = 0;
        mockMutations.editComment = async () => { editCalls++; return { ok: true, status: 204 }; };

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = '   ';

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(editCalls, 0);
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
    });

    test('40. Pre-submit synchronization flushes raw dirty draft before dispatching PATCH', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        let capturedDraftInStoreBeforePatch = null;

        mockMutations.editComment = async () => {
            capturedDraftInStoreBeforePatch = mockDraftStore.store.load(draftKey);
            return { ok: true, status: 204 };
        };

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Immediate submit without waiting for debounce';
        textarea.dispatchEvent({ type: 'input' });

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(capturedDraftInStoreBeforePatch, 'Immediate submit without waiting for debounce');
    });

    test('41. Pre-submit synchronization removes draft if text equals server clean body', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        mockDraftStore.store.save(draftKey, 'old draft');

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Root 1 original body';
        textarea.dispatchEvent({ type: 'input' });

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
    });

    test('42. Successful submit (200/204) removes draft from store, removes marker from store, closes composer without re-flushing', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Successful new body';
        textarea.dispatchEvent({ type: 'input' });

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft must be removed on success');
        assert.strictEqual(mockDraftStore.store.load(markerKey), null, 'Marker must be removed on success');
    });

    test('43. Failed submit (400, 401, 500, network error) preserves draft in store, preserves marker, keeps composer open', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        mockMutations.editComment = async () => {
            return { ok: false, status: 500 };
        };

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Failed edit body to preserve';
        textarea.dispatchEvent({ type: 'input' });

        await handleSubmit({ preventDefault: () => {} });

        assert.ok(getActiveComposerEl(), 'Composer must remain open on failure');
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Failed edit body to preserve');
        assert.ok(mockDraftStore.store.load(markerKey), 'Marker must remain on failure');
    });

    test('44. Stale A -> B race: user starts submit on A, switches to B, old A 204 succeeds -> deletes A draft, does not close B, does not remove B marker', async () => {
        let resolveEditA;
        mockMutations.editComment = (input) => {
            if (input.commentId === ROOT_ID_1) {
                return new Promise(r => { resolveEditA = r; });
            }
            return Promise.resolve({ ok: true, status: 204 });
        };

        const draftKeyA = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        // Open A and submit (in-flight)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textareaA = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textareaA.value = 'Body A';
        textareaA.dispatchEvent({ type: 'input' });
        const promiseA = handleSubmit({ preventDefault: () => {} });

        // Switch to B
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn2, preventDefault: () => {} });
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);

        // Resolve old A
        resolveEditA({ ok: true, status: 204 });
        await promiseA;

        // B must still be active and open!
        assert.ok(getActiveComposerEl());
        assert.strictEqual(getActiveEditTarget().commentId, ROOT_ID_2);

        // A draft removed, B marker intact
        assert.strictEqual(mockDraftStore.store.load(draftKeyA), null);
        const markerRaw = mockDraftStore.store.load(markerKey);
        const markerParsed = JSON.parse(markerRaw);
        assert.strictEqual(markerParsed.commentId, ROOT_ID_2);
    });

    test('45. ABA generation race safety: submit A (gen 1), re-open A and type new draft (gen 2), old A 204 succeeds -> DOES NOT delete newer gen 2 draft', async () => {
        let resolveEditA;
        mockMutations.editComment = (input) => {
            if (input.commentId === ROOT_ID_1) {
                return new Promise(r => { resolveEditA = r; });
            }
            return Promise.resolve({ ok: true, status: 204 });
        };

        const draftKeyA = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        // Submit A at generation 1
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textareaA = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textareaA.value = 'Body A Generation 1';
        textareaA.dispatchEvent({ type: 'input' });
        const submitPromiseA = handleSubmit({ preventDefault: () => {} });

        // User closes and re-opens A, typing newer draft (generation 2)
        closeEditComposer(false);
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const newTextarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        newTextarea.value = 'Newer Generation 2 Draft';
        newTextarea.dispatchEvent({ type: 'input' });
        flushActiveDraft();

        assert.strictEqual(mockDraftStore.store.load(draftKeyA), 'Newer Generation 2 Draft');

        // Resolve old submission from generation 1
        resolveEditA({ ok: true, status: 204 });
        await submitPromiseA;

        // The newer generation 2 draft MUST NOT be deleted!
        assert.strictEqual(mockDraftStore.store.load(draftKeyA), 'Newer Generation 2 Draft');
    });

    test('46. Accepted generation tracking: flushActiveDraft() will not resurrect already accepted generation', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Successfully submitted text';
        textarea.dispatchEvent({ type: 'input' });

        await handleSubmit({ preventDefault: () => {} });

        // Successfully submitted -> draft is removed and generation accepted
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
        const acceptedGen = getAcceptedEditGeneration(draftKey);
        assert.ok(acceptedGen > 0);

        // Attempting to flush an already accepted generation must not resurrect it
        flushActiveDraft();
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
    });

    test('47. Double submit blocked during in-flight PATCH', async () => {
        let editCalls = 0;
        let resolveEdit;
        mockMutations.editComment = () => {
            editCalls++;
            return new Promise(r => { resolveEdit = r; });
        };

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        const textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Single flight submit';
        textarea.dispatchEvent({ type: 'input' });

        const p1 = handleSubmit({ preventDefault: () => {} });
        const p2 = handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(editCalls, 1, 'Second submit call while in-flight must be ignored');

        resolveEdit({ ok: true, status: 204 });
        await Promise.all([p1, p2]);
    });

    test('48. Submitting clean text (if allowed by backend) does not create lingering draft', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        // Untouched: value equals original body
        assert.strictEqual(isDirty(), false);

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null);
        assert.strictEqual(mockDraftStore.store.load(markerKey), null);
    });

    test('49. Post-accepted direct-submit failure allocates new generation and preserves Draft 2 across flush and passive close', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        // 1. Submit Draft 1 successfully
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        let textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft 1';
        textarea.dispatchEvent({ type: 'input' });

        mockMutations.editComment = async () => ({ ok: true, status: 204 });
        await handleSubmit({ preventDefault: () => {} });

        const acceptedGen1 = getAcceptedEditGeneration(draftKey);
        assert.ok(acceptedGen1 > 0, 'First draft must have an accepted generation');
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft 1 removed upon 204');
        assert.strictEqual(getActiveComposerEl(), null, 'Composer closed upon 204');

        // 2. Reopen same comment and edit programmatically without typing event
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        assert.ok(getActiveComposerEl(), 'Composer reopened');
        textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft 2 after previous accepted edit';

        // 3. Configure PATCH to fail with HTTP 500
        let capturedBody = null;
        mockMutations.editComment = async (payload) => {
            capturedBody = payload.body;
            return { ok: false, status: 500 };
        };

        await handleSubmit({ preventDefault: () => {} });

        assert.strictEqual(capturedBody, 'Draft 2 after previous accepted edit');
        const newGen = getKeyEditGeneration(draftKey);
        assert.ok(newGen > acceptedGen1, 'New generation must be allocated and exceed accepted generation');
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft 2 after previous accepted edit', 'Draft 2 must be stored');
        assert.ok(getActiveComposerEl(), 'Composer remains open on failure');
        assert.ok(mockDraftStore.store.load(markerKey), 'Active marker remains on failure');

        // 4. Dispatch EVENT_FLUSH_DRAFTS
        fixture.doc.dispatchEvent({ type: EVENT_FLUSH_DRAFTS });
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft 2 after previous accepted edit', 'Draft 2 preserved after flush');

        // 5. Call passive closeEditComposer(false)
        closeEditComposer(false);
        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft 2 after previous accepted edit', 'Draft 2 preserved after passive close');
    });

    test('50. Stale-accepted remount resurrection prevention across flush, passive close, and genuine new edit', async () => {
        const draftKey = draftsAdapter.getChapterEditDraftKey(CHAPTER_ID, ROOT_ID_1);
        const markerKey = draftsAdapter.getChapterActiveInlineMarkerKey(CHAPTER_ID);

        let resolveEditA;
        mockMutations.editComment = (input) => {
            if (input.commentId === ROOT_ID_1) {
                return new Promise(r => { resolveEditA = r; });
            }
            return Promise.resolve({ ok: true, status: 204 });
        };

        // 1. Open Edit A, type Draft A, submit A (PATCH pending)
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        let textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft A';
        textarea.dispatchEvent({ type: 'input' });

        const submitPromiseA = handleSubmit({ preventDefault: () => {} });
        const genA = getKeyEditGeneration(draftKey);
        assert.ok(genA > 0);

        // 2. Destroy module while PATCH is pending
        destroy();
        assert.strictEqual(getActiveComposerEl(), null);

        // 3. Re-init module on same fixture and remount composer
        initReaderChapterCommentEditComposer(fixture.doc, {
            commentMutations: mockMutations,
            commentsModule: mockCommentsModule,
            replyComposerModule: mockReplyComposer,
            deleteModule: mockDeleteModule,
            draftAdapter: draftsAdapter,
            draftStore: mockDraftStore.store
        });

        const remountedComposer = getActiveComposerEl();
        assert.ok(remountedComposer, 'Composer remounted from marker');
        textarea = remountedComposer.querySelector('.' + EDIT_INPUT_CLASS);
        assert.strictEqual(textarea.value, 'Draft A');

        // 4. Resolve old pending PATCH with 204
        resolveEditA({ ok: true, status: 204 });
        await submitPromiseA;

        // Verify stale-accepted handling
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft A removed from store by accepted PATCH');
        assert.strictEqual(getAcceptedEditGeneration(draftKey), genA, 'Accepted generation recorded');
        assert.strictEqual(getActiveComposerEl(), remountedComposer, 'Remounted composer remains open');
        assert.strictEqual(textarea.value, 'Draft A', 'Remounted composer content not mutated');
        assert.ok(mockDraftStore.store.load(markerKey), 'Marker remains in store');

        // 5. Dispatch EVENT_FLUSH_DRAFTS -> Draft A not resurrected
        fixture.doc.dispatchEvent({ type: EVENT_FLUSH_DRAFTS });
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft A not resurrected by flush');

        // 6. Passive close -> Draft A not resurrected
        closeEditComposer(false);
        assert.strictEqual(getActiveComposerEl(), null);
        assert.strictEqual(mockDraftStore.store.load(draftKey), null, 'Draft A not resurrected by passive close');

        // 7. Reopen Edit on Comment 1, dispatch genuine input with Draft B, flush draft
        fixture.doc.dispatchEvent({ type: 'click', target: fixture.rootEditBtn1, preventDefault: () => {} });
        textarea = getActiveComposerEl().querySelector('.' + EDIT_INPUT_CLASS);
        textarea.value = 'Draft B';
        textarea.dispatchEvent({ type: 'input' });

        flushActiveDraft();
        assert.strictEqual(mockDraftStore.store.load(draftKey), 'Draft B', 'Draft B persisted to store');
        assert.ok(getKeyEditGeneration(draftKey) > genA, 'New edit generation exceeds accepted generation');
    });
});


