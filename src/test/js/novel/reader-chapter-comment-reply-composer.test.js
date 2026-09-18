const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const replyComposerModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comment-reply-composer.js'));

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
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.documentElement = new FakeElement('html');
        this.documentElement.ownerDocument = this;
        this.documentElement.appendChild(this.body);
        this.listeners = {};
        this.activeElement = null;
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    getElementById(id) {
        return querySelectorAllDeep(this.documentElement, '#' + id)[0] || null;
    }

    querySelector(selector) {
        return querySelectorAllDeep(this.documentElement, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this.documentElement, selector);
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
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

/**
 * Creates standard DOM fixture for reply composer tests.
 */
function createReplyFixture(options = {}) {
    const doc = new FakeDocument();
    const chapterId = options.chapterId || 'chap-reply-1';
    const authenticated = options.authenticated !== undefined ? options.authenticated : true;

    const section = doc.createElement('section');
    section.setAttribute('id', 'novelChapterComments');
    section.className = 'novel-chapter-comments';
    section.setAttribute('data-chapter-id', chapterId);
    section.setAttribute('data-chapter-slug', 'chuong-1');
    section.setAttribute('data-authenticated', authenticated ? 'true' : 'false');

    const statusEl = doc.createElement('div');
    statusEl.setAttribute('id', 'novelChapterCommentsStatus');
    statusEl.className = 'novel-chapter-comments-status';
    section.appendChild(statusEl);

    const listEl = doc.createElement('div');
    listEl.setAttribute('id', 'novelChapterCommentsList');
    listEl.className = 'novel-chapter-comments-list';

    // Thread 1 Card
    const thread1 = doc.createElement('article');
    thread1.className = 'novel-block-discussion-thread';
    thread1.setAttribute('data-root-id', 'root-1');

    // Root Comment in Thread 1
    const root1 = doc.createElement('div');
    root1.className = 'novel-comment novel-comment--root';
    root1.setAttribute('data-comment-id', 'root-1');

    const root1Actions = doc.createElement('div');
    root1Actions.className = 'novel-comment-actions';
    const root1ReplyBtn = doc.createElement('button');
    root1ReplyBtn.type = 'button';
    root1ReplyBtn.className = 'novel-comment-reply-btn';
    root1ReplyBtn.setAttribute('data-action', 'reply');
    root1ReplyBtn.setAttribute('data-comment-id', 'root-1');
    root1ReplyBtn.setAttribute('data-root-id', 'root-1');
    root1ReplyBtn.setAttribute('data-author-name', 'Tác Giả Gốc');
    root1ReplyBtn.textContent = 'Phản hồi';
    root1Actions.appendChild(root1ReplyBtn);
    root1.appendChild(root1Actions);
    thread1.appendChild(root1);

    // Reply 1 in Thread 1
    const repliesContainer = doc.createElement('div');
    repliesContainer.className = 'novel-comment-replies';

    const reply1 = doc.createElement('article');
    reply1.className = 'novel-comment novel-comment--reply';
    reply1.setAttribute('data-reply-id', 'rep-1');
    reply1.setAttribute('data-comment-id', 'rep-1');

    const reply1Actions = doc.createElement('div');
    reply1Actions.className = 'novel-comment-actions';
    const reply1ReplyBtn = doc.createElement('button');
    reply1ReplyBtn.type = 'button';
    reply1ReplyBtn.className = 'novel-comment-reply-btn';
    reply1ReplyBtn.setAttribute('data-action', 'reply');
    reply1ReplyBtn.setAttribute('data-comment-id', 'rep-1');
    reply1ReplyBtn.setAttribute('data-root-id', 'root-1');
    reply1ReplyBtn.setAttribute('data-author-name', 'Người Phản Hồi 1');
    reply1ReplyBtn.textContent = 'Phản hồi';
    reply1Actions.appendChild(reply1ReplyBtn);
    reply1.appendChild(reply1Actions);
    repliesContainer.appendChild(reply1);
    thread1.appendChild(repliesContainer);

    listEl.appendChild(thread1);

    // Thread 2 Card (for multi-thread tests)
    const thread2 = doc.createElement('article');
    thread2.className = 'novel-block-discussion-thread';
    thread2.setAttribute('data-root-id', 'root-2');

    const root2 = doc.createElement('div');
    root2.className = 'novel-comment novel-comment--root';
    root2.setAttribute('data-comment-id', 'root-2');

    const root2Actions = doc.createElement('div');
    root2Actions.className = 'novel-comment-actions';
    const root2ReplyBtn = doc.createElement('button');
    root2ReplyBtn.type = 'button';
    root2ReplyBtn.className = 'novel-comment-reply-btn';
    root2ReplyBtn.setAttribute('data-action', 'reply');
    root2ReplyBtn.setAttribute('data-comment-id', 'root-2');
    root2ReplyBtn.setAttribute('data-root-id', 'root-2');
    root2ReplyBtn.setAttribute('data-author-name', 'Tác Giả Gốc 2');
    root2ReplyBtn.textContent = 'Phản hồi';
    root2Actions.appendChild(root2ReplyBtn);
    root2.appendChild(root2Actions);
    thread2.appendChild(root2);

    listEl.appendChild(thread2);

    section.appendChild(listEl);
    doc.body.appendChild(section);

    return {
        doc,
        section,
        statusEl,
        listEl,
        thread1,
        root1,
        root1ReplyBtn,
        reply1,
        reply1ReplyBtn,
        thread2,
        root2,
        root2ReplyBtn
    };
}

// ============================================================================
// Test Suite: reader-chapter-comment-reply-composer (MS-05E5H2F2B)
// ============================================================================

describe('Reader Chapter Comment Reply Composer UI (MS-05E5H2F2B)', () => {

    afterEach(() => {
        replyComposerModule.destroy();
    });

    test('1. init on missing DOM elements exits gracefully without throwing', () => {
        const doc = new FakeDocument();
        assert.doesNotThrow(() => {
            replyComposerModule.init(doc);
        });
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    test('2. Unauthenticated user clicking "Phản hồi" on root renders guest prompt with login link', () => {
        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const prompt = fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt, 'Guest prompt must be rendered under root comment');
        assert.ok(prompt.textContent.includes('Đăng nhập để phản hồi.'));
        const link = prompt.querySelector('.novel-chapter-comment-login-link');
        assert.ok(link);
        assert.ok(link.getAttribute('href').includes('/login?returnTo='));
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getActiveGuestPrompt(), prompt);
    });

    test('3. Unauthenticated user clicking "Phản hồi" on reply renders guest prompt under that reply', () => {
        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc);

        fixture.reply1ReplyBtn.click();

        const prompt = fixture.reply1.querySelector('.novel-chapter-comment-reply-guest-prompt');
        assert.ok(prompt, 'Guest prompt must be rendered under reply comment');
        assert.ok(prompt.textContent.includes('Đăng nhập để phản hồi.'));
    });

    test('4. Guest prompt toggle: clicking "Phản hồi" again on same comment closes the guest prompt', () => {
        const fixture = createReplyFixture({ authenticated: false });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt'));

        fixture.root1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-guest-prompt'), null);
        assert.strictEqual(replyComposerModule.getActiveGuestPrompt(), null);
    });

    test('5. Authenticated user clicking "Phản hồi" on root mounts inline reply composer under root comment', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composer, 'Inline composer must be mounted in root comment');
        assert.strictEqual(replyComposerModule.getActiveComposer(), composer);

        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.ok(input);
        assert.strictEqual(input.isFocused, true, 'Textarea should be focused');
    });

    test('6. Replying to root sets activeCommentId = rootId and activeRootId = rootId', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();

        const state = replyComposerModule.getState();
        assert.strictEqual(state.activeCommentId, 'root-1');
        assert.strictEqual(state.activeRootId, 'root-1');
    });

    test('7. Authenticated user clicking "Phản hồi" on nested reply mounts composer under reply with commentId=reply.id and rootId=root.id', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.reply1ReplyBtn.click();

        const composer = fixture.reply1.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composer, 'Composer must be mounted in reply comment');

        const state = replyComposerModule.getState();
        assert.strictEqual(state.activeCommentId, 'rep-1');
        assert.strictEqual(state.activeRootId, 'root-1');
    });

    test('8. Composer header displays "Phản hồi @AuthorName" with target author name', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.reply1ReplyBtn.click();

        const composer = fixture.reply1.querySelector('.novel-chapter-comment-reply-composer');
        const targetLabel = composer.querySelector('.novel-chapter-comment-reply-target-label');
        assert.ok(targetLabel);
        assert.strictEqual(targetLabel.textContent, 'Phản hồi @Người Phản Hồi 1');
    });

    test('9. Single-instance: opening composer on Comment B closes composer on Comment A', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.reply1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.ok(fixture.reply1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.root2ReplyBtn.click();
        assert.strictEqual(fixture.reply1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.ok(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'));
    });

    test('10. Toggle: clicking "Phản hồi" again on same comment closes composer and restores focus to trigger button', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.root1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(fixture.root1ReplyBtn.isFocused, true);
    });

    test('11. Clicking close button (✕) closes composer and restores focus to trigger button', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const closeBtn = composer.querySelector('.novel-chapter-comment-reply-close-btn');

        closeBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(fixture.root1ReplyBtn.isFocused, true);
    });

    test('12. Clicking "Hủy" closes composer and restores focus to trigger button', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');

        cancelBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(fixture.root1ReplyBtn.isFocused, true);
    });

    test('13. Validation: submitting empty or whitespace-only reply shows error without invoking mutation client', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let createReplyCalled = false;
        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => { createReplyCalled = true; return Promise.resolve({ ok: true }); }
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');

        input.value = '   ';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(createReplyCalled, false, 'createReply must not be called for empty body');
        assert.strictEqual(status.textContent, 'Vui lòng nhập nội dung phản hồi.');
        assert.ok(status.classList.contains('is-error'));
    });

    test('14. Single-flight submission: while submission in-flight, controls disabled, status indicates sending, double submit no-ops', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveMutation;
        let callCount = 0;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => {
                    callCount++;
                    return new Promise(r => { resolveMutation = r; });
                }
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');
        const submitBtn = composer.querySelector('.novel-chapter-comment-reply-submit-btn');
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');

        input.value = 'Nội dung phản hồi hợp lệ';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(callCount, 1);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);
        assert.strictEqual(input.disabled, true);
        assert.strictEqual(submitBtn.disabled, true);
        assert.strictEqual(cancelBtn.disabled, false);
        assert.strictEqual(status.textContent, 'Đang gửi phản hồi...');

        // Second submit while in-flight
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });
        assert.strictEqual(callCount, 1, 'Double submit must be ignored');

        // Resolve
        resolveMutation({ ok: true, status: 201, commentId: 'rep-created' });
        await new Promise(r => setTimeout(r, 10));
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);
    });

    test('15. Successful submission (201 Created): invokes createReply, closes composer, and calls refreshRootThread', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let mutationInput = null;
        let refreshedRootId = null;
        let refreshOptions = null;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: (input) => {
                    mutationInput = input;
                    return Promise.resolve({ ok: true, status: 201, commentId: 'rep-created-123' });
                }
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshedRootId = rootId;
                    refreshOptions = opts;
                    return Promise.resolve({});
                }
            }
        });

        // Click reply on nested reply
        fixture.reply1ReplyBtn.click();
        const composer = fixture.reply1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Phản hồi cho reply 1';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        // Mutation client received correct params
        assert.strictEqual(mutationInput.chapterId, 'chap-reply-1');
        assert.strictEqual(mutationInput.parentCommentId, 'rep-1');
        assert.strictEqual(mutationInput.body, 'Phản hồi cho reply 1');

        // Composer is closed
        assert.strictEqual(fixture.reply1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // refreshRootThread was called with rootId and revealCommentId
        assert.strictEqual(refreshedRootId, 'root-1');
        assert.strictEqual(refreshOptions.revealCommentId, 'rep-created-123');
    });

    test('16. Refresh failure after 201 Created: does not restore draft or re-POST, sets #novelChapterCommentsStatus text', async () => {
        const fixture = createReplyFixture({ authenticated: true });

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => Promise.resolve({ ok: true, status: 201, commentId: 'rep-999' })
            },
            commentsModule: {
                refreshRootThread: () => Promise.reject(new Error('Network drop on refresh'))
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Bình luận phản hồi';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        // Composer is still closed
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);

        // Status on feed indicates failure
        assert.strictEqual(fixture.statusEl.textContent, 'Phản hồi đã được gửi, nhưng chưa thể tải lại thảo luận.');
    });

    test('17. Mutation failure preserves textarea draft, re-enables controls, and displays error message', async () => {
        const fixture = createReplyFixture({ authenticated: true });

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => Promise.reject(new Error('Lỗi kết nối máy chủ.'))
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');
        const submitBtn = composer.querySelector('.novel-chapter-comment-reply-submit-btn');

        input.value = 'Nội dung quan trọng không được mất';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        // Controls re-enabled
        assert.strictEqual(input.disabled, false);
        assert.strictEqual(submitBtn.disabled, false);
        // Draft preserved
        assert.strictEqual(input.value, 'Nội dung quan trọng không được mất');
        // Error displayed
        assert.strictEqual(status.textContent, 'Lỗi kết nối máy chủ.');
        assert.ok(status.classList.contains('is-error'));
        // Focus restored to input
        assert.strictEqual(input.isFocused, true);
    });

    test('18. HTTP 401 failure displays "Vui lòng đăng nhập để phản hồi."', async () => {
        const fixture = createReplyFixture({ authenticated: true });

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => Promise.resolve({ ok: false, status: 401 })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const status = composer.querySelector('.novel-chapter-comment-reply-composer-status');

        input.value = 'Thử phản hồi khi hết phiên';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(status.textContent, 'Vui lòng đăng nhập để phản hồi.');
    });

    test('19. Opening composer closes active actions menu', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let menuClosed = false;

        replyComposerModule.init(fixture.doc, {
            commentsModule: {
                closeActiveMenu: () => { menuClosed = true; }
            }
        });

        fixture.root1ReplyBtn.click();
        assert.strictEqual(menuClosed, true);
    });

    test('20. kiemlai:chapter-changed cancels in-flight submission and closes active reply composer', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveMutation;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveMutation = r; })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');

        input.value = 'Pending mutation';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        const tokenBefore = replyComposerModule.getState().currentMutationToken;

        // Change chapter event
        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-changed', detail: { chapterId: 'c-next' } });

        assert.ok(replyComposerModule.getState().currentMutationToken > tokenBefore);
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // Later resolving does nothing
        resolveMutation({ ok: true, status: 201, commentId: 'late-id' });
        await new Promise(r => setTimeout(r, 10));
    });

    test('21. kiemlai:chapter-comments-feed-replacing closes active reply composer / guest prompt', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        fixture.doc.dispatchEvent({ type: 'kiemlai:chapter-comments-feed-replacing' });

        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
    });

    test('22. destroy cleans up active composer, detaches listeners, and resets state', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        assert.ok(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'));

        replyComposerModule.destroy();

        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);

        // Clicking reply button after destroy should not open composer
        fixture.root1ReplyBtn.click();
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
    });

    test('23. Bottom Reply module completely ignores Reply clicks from #novelBlockDiscussionDrawer', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let createReplyCalled = false;
        const mockMutations = {
            createReply: () => {
                createReplyCalled = true;
                return Promise.resolve({ ok: true });
            }
        };

        replyComposerModule.init(fixture.doc, {
            mutations: mockMutations
        });

        // Add Drawer fixture to document
        const drawer = fixture.doc.createElement('div');
        drawer.id = 'novelBlockDiscussionDrawer';
        drawer.setAttribute('id', 'novelBlockDiscussionDrawer');

        const drawerComment = fixture.doc.createElement('div');
        drawerComment.className = 'novel-comment';
        drawerComment.setAttribute('data-comment-id', 'drawer-comment-1');

        const drawerReplyBtn = fixture.doc.createElement('button');
        drawerReplyBtn.className = 'novel-comment-reply-btn';
        drawerReplyBtn.setAttribute('data-action', 'reply');
        drawerReplyBtn.setAttribute('data-comment-id', 'drawer-comment-1');
        drawerReplyBtn.setAttribute('data-root-id', 'drawer-root-1');
        drawerReplyBtn.textContent = 'Phản hồi';

        drawerComment.appendChild(drawerReplyBtn);
        drawer.appendChild(drawerComment);
        fixture.doc.body.appendChild(drawer);

        // Click Drawer Reply button
        drawerReplyBtn.click();

        // Assert: NO Bottom reply composer, NO Bottom guest prompt, no active Bottom target, no createReply call
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getActiveGuestPrompt(), null);
        assert.strictEqual(drawerComment.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(drawerComment.querySelector('.novel-chapter-comment-reply-guest-prompt'), null);
        assert.strictEqual(replyComposerModule.getState().activeCommentId, null);
        assert.strictEqual(replyComposerModule.getState().activeRootId, null);
        assert.strictEqual(createReplyCalled, false);

        // Then prove a real Bottom Reply button still opens normally
        fixture.root1ReplyBtn.click();
        assert.notStrictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getState().activeCommentId, 'root-1');
        assert.notStrictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
    });

    test('24 (Test A). Submit A pending -> switch to B -> B usable -> resolve A success -> B remains, B draft remains, 0 calls to refreshRootThread for A', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveA;
        const refreshCalls = [];

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveA = r; })
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshCalls.push({ rootId, opts });
                    return Promise.resolve({});
                }
            }
        });

        // Open and submit A
        fixture.root1ReplyBtn.click();
        const composerA = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Draft A';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Switch to B (root-2)
        fixture.root2ReplyBtn.click();

        // Composer A removed, Composer B mounted
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        const composerB = fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composerB, 'Composer B should be mounted');
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        assert.strictEqual(inputB.disabled, false);
        assert.strictEqual(inputB.value, '');

        // User writes draft in B
        inputB.value = 'Draft B content';

        // Now resolve A
        resolveA({ ok: true, status: 201, commentId: 'rep-created-a' });
        await new Promise(r => setTimeout(r, 15));

        // Composer B must remain mounted with draft intact
        assert.strictEqual(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'), composerB);
        assert.strictEqual(inputB.value, 'Draft B content');
        assert.strictEqual(inputB.disabled, false);

        // 0 refresh calls for A
        assert.strictEqual(refreshCalls.length, 0);
    });

    test('25 (Test B). Submit A pending -> switch to B -> submit B -> resolve A -> B lifecycle remains authoritative', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveA;
        let resolveB;
        let submitCount = 0;
        const refreshCalls = [];

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => {
                    submitCount++;
                    if (submitCount === 1) {
                        return new Promise(r => { resolveA = r; });
                    }
                    return new Promise(r => { resolveB = r; });
                }
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshCalls.push({ rootId, opts });
                    return Promise.resolve({});
                }
            }
        });

        // Submit A
        fixture.root1ReplyBtn.click();
        const composerA = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Draft A';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(submitCount, 1);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Switch to B
        fixture.root2ReplyBtn.click();
        const composerB = fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        inputB.value = 'Draft B';

        // Submit B
        composerB.dispatchEvent({ type: 'submit', preventDefault() {} });
        assert.strictEqual(submitCount, 2);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Resolve A -> must NOT affect B
        resolveA({ ok: true, status: 201, commentId: 'rep-a' });
        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'), composerB);
        assert.strictEqual(refreshCalls.length, 0);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Resolve B -> B authoritative completion
        resolveB({ ok: true, status: 201, commentId: 'rep-b' });
        await new Promise(r => setTimeout(r, 15));

        // Composer B closed
        assert.strictEqual(fixture.root2.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);
        // Authoritative refresh for B's root
        assert.strictEqual(refreshCalls.length, 1);
        assert.strictEqual(refreshCalls[0].rootId, 'root-2');
        assert.strictEqual(refreshCalls[0].opts.revealCommentId, 'rep-b');
    });

    test('26 (Test C). Submit A pending -> click Hủy -> resolve A success -> composer stays closed, 0 refresh', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveA;
        const refreshCalls = [];

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveA = r; })
            },
            commentsModule: {
                refreshRootThread: (rootId, opts) => {
                    refreshCalls.push({ rootId, opts });
                    return Promise.resolve({});
                }
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const cancelBtn = composer.querySelector('.novel-chapter-comment-reply-cancel-btn');

        input.value = 'Draft A to cancel';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Click Hủy during in-flight flight
        cancelBtn.click();

        // Composer removed from DOM immediately
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);

        // Resolve A
        resolveA({ ok: true, status: 201, commentId: 'rep-a-cancelled' });
        await new Promise(r => setTimeout(r, 15));

        // Stays closed, 0 refresh
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(refreshCalls.length, 0);
    });

    test('27 (Test D). Submit A pending -> click ✕ -> resolve A failure -> no stale error/UI', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let rejectA;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: () => new Promise((_, rej) => { rejectA = rej; })
            }
        });

        fixture.root1ReplyBtn.click();
        const composer = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        const closeBtn = composer.querySelector('.novel-chapter-comment-reply-close-btn');

        input.value = 'Draft A close test';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Click ✕ during in-flight
        closeBtn.click();

        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);

        // Reject A
        rejectA(new Error('Internal 500 error'));
        await new Promise(r => setTimeout(r, 15));

        // No resurrected composer, no errors
        assert.strictEqual(fixture.root1.querySelector('.novel-chapter-comment-reply-composer'), null);
        assert.strictEqual(replyComposerModule.getActiveComposer(), null);
        assert.strictEqual(fixture.statusEl.textContent, '');
    });

    test('28 (Test E). After cancelling pending A -> open B -> B can submit (isSubmitting does not block B)', async () => {
        const fixture = createReplyFixture({ authenticated: true });
        let resolveB;
        let bSubmitted = false;

        replyComposerModule.init(fixture.doc, {
            mutations: {
                createReply: (payload) => {
                    if (payload.parentCommentId === 'root-2') {
                        bSubmitted = true;
                        return new Promise(r => { resolveB = r; });
                    }
                    return new Promise(() => {}); // A never resolves
                }
            }
        });

        // Open and submit A
        fixture.root1ReplyBtn.click();
        const composerA = fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        const cancelBtnA = composerA.querySelector('.novel-chapter-comment-reply-cancel-btn');

        inputA.value = 'Draft A';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        // Cancel A via Hủy
        cancelBtnA.click();
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);

        // Open B
        fixture.root2ReplyBtn.click();
        const composerB = fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        assert.ok(composerB);
        const inputB = composerB.querySelector('.novel-chapter-comment-reply-composer-input');
        inputB.value = 'Draft B can submit';

        // Submit B
        composerB.dispatchEvent({ type: 'submit', preventDefault() {} });

        assert.strictEqual(bSubmitted, true, 'B must be able to submit after A cancellation');
        assert.strictEqual(replyComposerModule.getState().isSubmitting, true);

        resolveB({ ok: true, status: 201, commentId: 'b-ok' });
        await new Promise(r => setTimeout(r, 15));
        assert.strictEqual(replyComposerModule.getState().isSubmitting, false);
    });

    test('29 (Test F). Destroy -> re-init ABA safety remains monotonic (tokens never reset to 0)', () => {
        const fixture = createReplyFixture({ authenticated: true });
        replyComposerModule.init(fixture.doc);

        fixture.root1ReplyBtn.click();
        const token1 = replyComposerModule.getState().currentMutationToken;

        replyComposerModule.destroy();
        const token2 = replyComposerModule.getState().currentMutationToken;
        assert.ok(token2 > token1, `destroy must advance token monotonically: ${token2} > ${token1}`);

        replyComposerModule.init(fixture.doc);
        const token3 = replyComposerModule.getState().currentMutationToken;
        assert.ok(token3 >= token2, `re-init must not reset token to zero: ${token3} >= ${token2}`);
        assert.notStrictEqual(token3, 0, 'Token must never be reset to 0');
    });

    test('30. MS-05E5H2F3B: opening Reply closes active Edit composer', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let closeEditCalled = false;
        const mockEdit = {
            closeEditComposer: () => { closeEditCalled = true; }
        };

        replyComposerModule.init(fixture.doc, {
            editComposerModule: mockEdit
        });

        fixture.root1ReplyBtn.click();
        assert.strictEqual(closeEditCalled, true, 'Opening Reply must close active Edit composer');
    });

    test('31. MS-05E5H2F3B: opening Reply closes active Delete confirmation', () => {
        const fixture = createReplyFixture({ authenticated: true });
        let closeDeleteCalled = false;
        const mockDel = {
            closeDeleteConfirmation: () => { closeDeleteCalled = true; }
        };

        replyComposerModule.init(fixture.doc, {
            deleteModule: mockDel
        });

        fixture.root1ReplyBtn.click();
        assert.strictEqual(closeDeleteCalled, true, 'Opening Reply must close active Delete confirmation');
    });
});

describe('MS-05E5H2F4B1 — Bottom Reply Cross-Surface Synchronization', () => {

    afterEach(() => {
        replyComposerModule.destroy();
    });

    function setupSyncFixture() {
        const fixture = createReplyFixture({ authenticated: true, chapterId: 'chap-reply-sync' });
        const chapterBody = fixture.doc.createElement('div');
        chapterBody.className = 'novel-reader-chapter-body';
        fixture.doc.body.appendChild(chapterBody);

        let indicatorRefreshCalls = [];
        const mockIndicators = {
            refreshChapterIndicators: async (body) => {
                indicatorRefreshCalls.push(body);
            }
        };

        let drawerRefreshCalls = 0;
        let drawerOpen = true;
        let drawerContext = { chapterId: 'chap-reply-sync', blockKey: 'blk-1' };
        const mockDrawer = {
            isDrawerOpen: () => drawerOpen,
            getActiveContext: () => drawerContext,
            refreshActiveDiscussion: async () => {
                drawerRefreshCalls++;
            }
        };

        let refreshedRootId = null;
        const mockComments = {
            getState: () => ({
                items: [
                    {
                        id: 'root-1',
                        rootCommentId: 'root-1',
                        anchorStatus: 'CURRENT',
                        blockKey: 'blk-1'
                    },
                    {
                        id: 'root-2',
                        rootCommentId: 'root-2',
                        anchorStatus: 'UNANCHORED',
                        blockKey: null
                    }
                ]
            }),
            refreshRootThread: async (rootId) => {
                refreshedRootId = rootId;
            }
        };

        return {
            fixture,
            chapterBody,
            mockIndicators,
            getIndicatorRefreshCalls: () => indicatorRefreshCalls,
            mockDrawer,
            getDrawerRefreshCalls: () => drawerRefreshCalls,
            setDrawerOpen: (val) => { drawerOpen = val; },
            setDrawerContext: (ctx) => { drawerContext = ctx; },
            mockComments,
            getRefreshedRootId: () => refreshedRootId
        };
    }

    test('Case A: Anchored reply success triggers indicator refresh and same-block open Drawer refresh', async () => {
        const env = setupSyncFixture();
        let createReplyCalls = 0;

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => {
                    createReplyCalls++;
                    return { ok: true, status: 201, commentId: 'rep-new-1' };
                }
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(createReplyCalls, 1);
        assert.strictEqual(env.getRefreshedRootId(), 'root-1', 'Local root thread must refresh');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1, 'Indicator refresh must be called exactly once');
        assert.strictEqual(env.getIndicatorRefreshCalls()[0], env.chapterBody, 'Indicator refresh must receive chapterBody');
        assert.strictEqual(env.getDrawerRefreshCalls(), 1, 'Same-block open Drawer must refresh exactly once');
    });

    test('Case B: Anchored reply success with different-block Drawer open skips Drawer refresh', async () => {
        const env = setupSyncFixture();
        env.setDrawerContext({ chapterId: 'chap-reply-sync', blockKey: 'different-blk' });

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-1' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-1');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1, 'Indicator refresh must still run');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Drawer refresh must be skipped for different blockKey');
    });

    test('Case C: Anchored reply success with Drawer closed skips Drawer refresh', async () => {
        const env = setupSyncFixture();
        env.setDrawerOpen(false);

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-1' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-1');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1);
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Closed drawer must not be refreshed');
    });

    test('Case D: UNANCHORED root reply success performs local refresh only (zero indicator, zero Drawer)', async () => {
        const env = setupSyncFixture();

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-2' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root2ReplyBtn.click();
        const composer = env.fixture.root2.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to unanchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-2', 'Local root thread must refresh');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 0, 'Indicator must not refresh for UNANCHORED');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Drawer must not refresh for UNANCHORED');
    });

    test('Case E: STALE root reply success performs local refresh only (zero indicator, zero Drawer)', async () => {
        const env = setupSyncFixture();
        env.mockComments.getState = () => ({
            items: [
                {
                    id: 'root-1',
                    rootCommentId: 'root-1',
                    anchorStatus: 'STALE',
                    blockKey: 'blk-stale'
                }
            ]
        });

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => ({ ok: true, status: 201, commentId: 'rep-new-stale' })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to stale root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getRefreshedRootId(), 'root-1');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 0, 'Indicator must not refresh for STALE');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Drawer must not refresh for STALE');
    });

    test('Case F: Secondary indicator rejection does not retry mutation or fail local refresh', async () => {
        const env = setupSyncFixture();
        let createReplyCalls = 0;
        env.mockIndicators.refreshChapterIndicators = () => Promise.reject(new Error('Network error on indicators'));

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => {
                    createReplyCalls++;
                    return { ok: true, status: 201, commentId: 'rep-new-1' };
                }
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(createReplyCalls, 1, 'Mutation must not be retried');
        assert.strictEqual(env.getRefreshedRootId(), 'root-1', 'Local refresh must still succeed');
        assert.strictEqual(env.getDrawerRefreshCalls(), 1, 'Drawer refresh still runs');
    });

    test('Case G: Secondary Drawer rejection does not retry mutation or fail local refresh', async () => {
        const env = setupSyncFixture();
        let createReplyCalls = 0;
        env.mockDrawer.refreshActiveDiscussion = () => Promise.reject(new Error('Drawer refresh error'));

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: async () => {
                    createReplyCalls++;
                    return { ok: true, status: 201, commentId: 'rep-new-1' };
                }
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        env.fixture.root1ReplyBtn.click();
        const composer = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const input = composer.querySelector('.novel-chapter-comment-reply-composer-input');
        input.value = 'Reply to anchored root';
        composer.dispatchEvent({ type: 'submit', preventDefault() {} });

        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(createReplyCalls, 1, 'Mutation must not be retried');
        assert.strictEqual(env.getRefreshedRootId(), 'root-1', 'Local refresh must still succeed');
        assert.strictEqual(env.getIndicatorRefreshCalls().length, 1, 'Indicator refresh still runs');
    });

    test('Case H: Stale A mutation completion performs zero cross-surface synchronization', async () => {
        const env = setupSyncFixture();
        let resolveReplyA;

        replyComposerModule.init(env.fixture.doc, {
            mutations: {
                createReply: () => new Promise(r => { resolveReplyA = r; })
            },
            commentsModule: env.mockComments,
            indicatorsModule: env.mockIndicators,
            drawerModule: env.mockDrawer
        });

        // Start reply on Root 1 (anchored)
        env.fixture.root1ReplyBtn.click();
        const composerA = env.fixture.root1.querySelector('.novel-chapter-comment-reply-composer');
        const inputA = composerA.querySelector('.novel-chapter-comment-reply-composer-input');
        inputA.value = 'Reply A in-flight';
        composerA.dispatchEvent({ type: 'submit', preventDefault() {} });

        // Cancel A before it resolves
        const cancelBtnA = composerA.querySelector('.novel-chapter-comment-reply-cancel-btn');
        cancelBtnA.click();

        // Resolve A now
        resolveReplyA({ ok: true, status: 201, commentId: 'rep-stale-a' });
        await new Promise(r => setTimeout(r, 15));

        assert.strictEqual(env.getIndicatorRefreshCalls().length, 0, 'Zero indicator refresh for stale mutation');
        assert.strictEqual(env.getDrawerRefreshCalls(), 0, 'Zero Drawer refresh for stale mutation');
        assert.strictEqual(env.getRefreshedRootId(), null, 'Zero local refresh for stale mutation');
    });
});
